package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestPanes.*

final class EditSuite extends ScalaCheckSuite:

  private def paneIds(s: LayoutState): Set[PaneId] = s.panes.map(_.id).toSet

  private val stateWithGroup: Gen[(LayoutState, Node.Group)] =
    stateGen.flatMap(s => Gen.oneOf(s.groups).map(g => (s, g)))

  private val edgeGen: Gen[Edge] = Gen.oneOf(Edge.Left, Edge.Right, Edge.Top, Edge.Bottom)

  // -- drop: the nine cases ----------------------------------------------------------------

  property("drop IntoGroup inserts the tab at the index, activates and focuses it"):
    forAll(stateWithGroup, paneGen, Gen.choose(0, 5)) { case ((s, g), pane, i) =>
      val next  = edit.drop(s, pane, DropTarget.IntoGroup(g.id, i))
      val group = next.findGroup(g.id).getOrElse(fail(s"group ${g.id} vanished"))
      assertEquals(group.tabs(i.min(g.tabs.length)).id, pane.id)
      assertEquals(group.active, pane.id)
      assertEquals(next.focused, Some(pane.id))
      Invariants.assertCanonical(next)
      true
    }

  property("drop Beside adds exactly the dropped pane and stays canonical"):
    forAll(stateWithGroup, paneGen, edgeGen) { case ((s, g), pane, edge) =>
      val next = edit.drop(s, pane, DropTarget.Beside(g.id, edge))
      assertEquals(paneIds(next), paneIds(s) + pane.id)
      Invariants.assertCanonical(next)
      // the target group survives with its tabs untouched
      assertEquals(next.findGroup(g.id).map(_.tabs), Some(g.tabs))
      true
    }

  test("drop Beside merges into a parent split when the axis matches (no new split)"):
    val a    = Node.solo(doc("a"))
    val b    = Node.solo(doc("b"))
    val root = Node.Split(NodeId.fresh(), Axis.Horizontal, Vector(Cell(a), Cell(b)))
    val s    = LayoutState.of(root)
    val p    = doc("c")

    val next = edit.drop(s, p, DropTarget.Beside(a.id, Edge.Right))
    next.root match
      case Some(Node.Split(id, Axis.Horizontal, cells)) =>
        assertEquals(id, root.id, "the existing split must be reused, not replaced")
        assertEquals(cells.length, 3)
        assertEquals(cells.map(_.node.id), Vector(a.id, next.groupOf(p.id).get.id, b.id))
      case other => fail(s"unexpected root: $other")

  test("drop Beside on the cross axis wraps the target in a fresh split, 50/50"):
    val a    = Node.solo(doc("a"))
    val b    = Node.solo(doc("b"))
    val root = Node.Split(NodeId.fresh(), Axis.Horizontal, Vector(Cell(a), Cell(b)))
    val s    = LayoutState.of(root)
    val p    = doc("c")

    val next = edit.drop(s, p, DropTarget.Beside(a.id, Edge.Bottom))
    next.root match
      case Some(Node.Split(_, Axis.Horizontal, cells)) =>
        cells.head.node match
          case Node.Split(_, Axis.Vertical, inner) =>
            assertEquals(inner.map(_.node.id), Vector(a.id, next.groupOf(p.id).get.id))
            assertEquals(inner.map(_.size), Vector[Size](Size.Fr(1), Size.Fr(1)))
          case other => fail(s"expected a vertical wrap, got $other")
      case other => fail(s"unexpected root: $other")

  test("drop at a window edge merges into a same-axis root split, halving the neighbour"):
    val a    = Node.solo(doc("a"))
    val b    = Node.solo(doc("b"))
    val root = Node.Split(NodeId.fresh(), Axis.Horizontal, Vector(Cell(a, Size.Fr(2)), Cell(b)))
    val s    = LayoutState.of(root)

    val next = edit.drop(s, doc("c"), DropTarget.AtWindowEdge(None, Edge.Left))
    next.root match
      case Some(Node.Split(id, Axis.Horizontal, cells)) =>
        assertEquals(id, root.id)
        assertEquals(cells.length, 3)
        assertEquals(cells(0).size, Size.Fr(1)) // the halved neighbour's share, mirrored
        assertEquals(cells(1).size, Size.Fr(1))
        assertEquals(cells(1).node.id, a.id)
      case other => fail(s"unexpected root: $other")

  property("drop at a window edge on an empty layout creates the root group"):
    forAll(paneGen, edgeGen) { (pane, edge) =>
      val next = edit.drop(LayoutState.empty, pane, DropTarget.AtWindowEdge(None, edge))
      assertEquals(paneIds(next), Set(pane.id))
      Invariants.assertCanonical(next)
      true
    }

  // -- detach / close ----------------------------------------------------------------------

  property("detach then drop anywhere preserves the pane multiset"):
    forAll(stateGen, edgeGen) { (s, edge) =>
      val pane          = s.panes.head
      val (rest, taken) = edit.detach(s, pane.id)
      assertEquals(taken.map(_.id), Some(pane.id))
      Invariants.assertCanonical(rest)
      val next = edit.drop(rest, taken.get, DropTarget.AtWindowEdge(None, edge))
      assertEquals(paneIds(next), paneIds(s))
      Invariants.assertCanonical(next)
      true
    }

  property("closing every pane empties the layout"):
    forAll(stateGen) { s =>
      val emptied = s.panes.foldLeft(s)((acc, p) => edit.close(acc, p.id))
      assert(emptied.isEmpty, s"leftover structure: $emptied")
      true
    }

  // -- focus, tabs, maximise ---------------------------------------------------------------

  property("focus activates the pane's tab in its group"):
    forAll(stateGen) { s =>
      val pane = s.panes.last
      val next = edit.focus(s, pane.id)
      assertEquals(next.focused, Some(pane.id))
      assertEquals(next.groupOf(pane.id).map(_.active), Some(pane.id))
      true
    }

  property("maximize is at most one group and survives only while the group exists"):
    forAll(stateWithGroup) { case (s, g) =>
      val maxed = edit.maximize(s, g.id)
      assertEquals(maxed.maximized, Some(g.id))
      val gone = g.tabs.foldLeft(maxed)((acc, p) => edit.close(acc, p.id))
      assertEquals(gone.maximized, None)
      true
    }

  property("a drop always restores from maximise first (golden-layout rule)"):
    forAll(stateWithGroup, paneGen) { case ((s, g), pane) =>
      val maxed = edit.maximize(s, g.id)
      val next  = edit.drop(maxed, pane, DropTarget.IntoGroup(g.id, 0))
      assertEquals(next.maximized, None)
      true
    }

  // -- dividers ----------------------------------------------------------------------------

  property("dragDivider touches only the named split"):
    forAll(stateGen, Gen.choose(0.0, 1.0)) { (s, f) =>
      val splits = s.roots.flatMap(collectSplits)
      splits.headOption match
        case None => true
        case Some(sp) =>
          val next = edit.dragDivider(s, sp.id, 0, f, 1000.0, LayoutSettings.default)
          Invariants.assertCanonical(next)
          assertEquals(paneIds(next), paneIds(s))
          // every other split's cells are untouched
          next.roots.flatMap(collectSplits).filter(_.id != sp.id).foreach { other =>
            val before = splits.find(_.id == other.id)
            assertEquals(Some(other.cells.map(_.size)), before.map(_.cells.map(_.size)))
          }
          true
    }

  // -- floating ----------------------------------------------------------------------------

  test("popOut then dockBack restores the original tree exactly"):
    val a = Node.solo(doc("a"))
    val b = Node.solo(doc("b"))
    val c = Node.solo(doc("c"))
    val root =
      Node.Split(NodeId.fresh(), Axis.Horizontal, Vector(Cell(a), Cell(b, Size.Px(300)), Cell(c)))
    val s = LayoutState.of(root)

    val out = edit.popOut(s, b.id, Rect(100, 100, 400, 300))
    assertEquals(out.floating.length, 1)
    assertEquals(out.root.map(n => n.groups.map(_.id)), Some(Vector(a.id, c.id)))
    Invariants.assertCanonical(out)

    val back = edit.dockBack(out, out.floating.head.window)
    assertEquals(back, s) // ids, sizes, order — everything

  property("popOut preserves panes and stays canonical; dockBack always re-docks them"):
    forAll(stateWithGroup) { case (s, g) =>
      val out = edit.popOut(s, g.id, Rect(0, 0, 400, 300))
      Invariants.assertCanonical(out)
      assertEquals(paneIds(out), paneIds(s))
      val back = edit.dockBack(out, out.floating.last.window)
      Invariants.assertCanonical(back)
      assertEquals(paneIds(back), paneIds(s))
      assert(!back.floating.exists(_.window == out.floating.last.window), "window not closed")
      true
    }

  private def collectSplits(n: Node): Vector[Node.Split] = n match
    case _: Node.Group => Vector.empty
    case sp @ Node.Split(_, _, cells) =>
      sp +: cells.flatMap(c => collectSplits(c.node))
end EditSuite
