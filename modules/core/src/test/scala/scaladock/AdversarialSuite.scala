package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestPanes.*

/** Laws proposed by the adversarial core review: drop totality under stale targets, invariant
  * preservation over random op sequences, codec robustness at numeric extremes, and the everyday
  * popOut/dockBack case (2-cell splits).
  */
final class AdversarialSuite extends ScalaCheckSuite:

  private def paneIds(s: LayoutState): Set[PaneId] = s.panes.map(_.id).toSet

  // -- drop is total: the pane is NEVER lost -------------------------------------------------

  private val anyTargetGen: Gen[LayoutState => DropTarget] =
    Gen.oneOf[LayoutState => DropTarget](
      s => DropTarget.IntoGroup(s.groups.headOption.fold(NodeId.fresh())(_.id), 0),
      _ => DropTarget.IntoGroup(NodeId.fresh(), 3),      // stale group
      _ => DropTarget.Beside(NodeId.fresh(), Edge.Left), // stale group
      s => DropTarget.Beside(s.groups.lastOption.fold(NodeId.fresh())(_.id), Edge.Bottom),
      _ => DropTarget.AtWindowEdge(None, Edge.Top),
      _ => DropTarget.AtWindowEdge(Some(WindowId.fresh()), Edge.Left) // stale window
    )

  property("drop never loses the pane, even on stale targets"):
    forAll(stateGen, paneGen, anyTargetGen) { (s, pane, mkTarget) =>
      val next = edit.drop(s, pane, mkTarget(s))
      assertEquals(paneIds(next), paneIds(s) + pane.id)
      assertEquals(next.focused, Some(pane.id))
      Invariants.assertCanonical(next)
      true
    }

  test("detaching a group's only tab and dropping back on its own header keeps the pane"):
    val solo          = doc("only")
    val s             = edit.canonical(LayoutState.of(Node.solo(solo)))
    val originalGroup = s.groups.head.id
    val (rest, taken) = edit.detach(s, solo.id)
    assert(rest.isEmpty) // the group dissolved with its only tab
    val back = edit.drop(rest, taken.get, DropTarget.IntoGroup(originalGroup, 0))
    assertEquals(paneIds(back), Set(solo.id))
    Invariants.assertCanonical(back)

  // -- random op sequences keep every invariant ----------------------------------------------

  private val opGen: Gen[LayoutState => LayoutState] = Gen.oneOf[LayoutState => LayoutState](
    s => s.panes.headOption.fold(s)(p => edit.focus(s, p.id)),
    s => s.panes.lastOption.fold(s)(p => edit.close(s, p.id)),
    s => s.groups.headOption.fold(s)(g => edit.maximize(s, g.id)),
    s => edit.unmaximized(s),
    s => s.groups.lastOption.fold(s)(g => edit.popOut(s, g.id, Rect(0, 0, 300, 200))),
    s => s.floating.headOption.fold(s)(f => edit.dockBack(s, f.window)),
    s => edit.open(s, counter(7), DockAt.Preferred),
    s =>
      s.groups.headOption.fold(s): g =>
        edit.drop(s, doc("dropped"), DropTarget.Beside(g.id, Edge.Top)),
    s => edit.dragDivider(s, NodeId.fresh(), 0, 0.5, 1000, LayoutSettings.default)
  )

  property("every public transition sequence preserves canonical invariants"):
    forAll(stateGen, Gen.listOfN(8, opGen)) { (s0, ops) =>
      val states = ops.scanLeft(s0)((s, op) => op(s))
      states.foreach(Invariants.assertCanonical)
      true
    }

  // -- codec at numeric extremes ---------------------------------------------------------------

  private val extremeSizeGen: Gen[Size] = Gen.oneOf(
    Gen.oneOf(1e-9, 5e-4, 1e16, 123.456789012345).map(Size.Pct.apply),
    Gen.oneOf(1e-9, 7.5e-5, 2e15).map(Size.Fr.apply),
    Gen.oneOf(0.0001, 9e7).map(Size.Px.apply)
  )

  property("sizes at numeric extremes survive the codec round trip"):
    forAll(extremeSizeGen) { size =>
      val a = doc("a")
      val b = doc("b")
      val s = edit.canonical(
        LayoutState.of(
          Node.Split(
            NodeId.fresh(),
            Axis.Horizontal,
            Vector(Cell(Node.solo(a), size), Cell(Node.solo(b)))
          )
        )
      )
      LayoutCodec.decode(LayoutCodec.encode(s), registry) match
        case Right(decoded) => assertEquals(decoded, s)
        case Left(err)      => fail(s"decode failed for $size: $err")
      true
    }

  test("divider commits near the edge persist and reload (the 8.3e-4 percent case)"):
    val cells = Vector(
      Cell(Node.solo(doc("a")), Size.Fr(1), minPx = 0),
      Cell(Node.solo(doc("b")), Size.Fr(1), minPx = 0)
    )
    val split = Node.Split(NodeId.fresh(), Axis.Horizontal, cells)
    val s     = edit.canonical(LayoutState.of(split))
    // squeeze pane a to (near) its recursive minimum — tiny percentages appear
    val squeezed = edit.dragDivider(s, split.id, 0, 1e-6, 100000.0, LayoutSettings.default)
    LayoutCodec.decode(LayoutCodec.encode(squeezed), registry) match
      case Right(decoded) => assertEquals(decoded, squeezed)
      case Left(err)      => fail(s"decode failed: $err")

  test("duplicate ids are rejected with a LoadError, not accepted silently"):
    val p = doc("dup")
    val group = ujson.write(
      LayoutCodec.encode(edit.canonical(LayoutState.of(Node.solo(p))))
    )
    // duplicate the whole group under a new split by hand
    val root = ujson.read(group)
    val g    = root("root")
    val forged = ujson.Obj(
      "version" -> 1,
      "root" -> ujson.Obj(
        "split" -> ujson.Obj(
          "id"   -> "forged",
          "axis" -> "row",
          "cells" -> ujson.Arr(
            ujson.Obj("size" -> "1fr", "node" -> g),
            ujson.Obj("size" -> "1fr", "node" -> g)
          )
        )
      ),
      "floating"  -> ujson.Arr(),
      "maximized" -> ujson.Null,
      "focused"   -> ujson.Null
    )
    assert(LayoutCodec.decode(forged, registry).isLeft)

  // -- popOut/dockBack: the everyday 2-cell case ----------------------------------------------

  test("popping one half of a pair and docking back restores structure, sizes, and minPx"):
    val a = Node.solo(doc("a"))
    val b = Node.solo(doc("b"))
    val root = Node.Split(
      NodeId.fresh(),
      Axis.Vertical,
      Vector(Cell(a, Size.Fr(2), minPx = 120), Cell(b, Size.Px(300), minPx = 80))
    )
    val s = LayoutState.of(root)

    val out = edit.popOut(s, b.id, Rect(50, 50, 400, 300))
    assertEquals(out.root.map(_.id), Some(a.id)) // pair dissolved to the survivor
    Invariants.assertCanonical(out)

    val back = edit.dockBack(out, out.floating.head.window)
    Invariants.assertCanonical(back)
    back.root match
      case Some(Node.Split(_, Axis.Vertical, cells)) =>
        assertEquals(cells.map(_.node.id), Vector(a.id, b.id))
        assertEquals(cells(1).size, Size.Px(300))
        assertEquals(cells(1).minPx, 80.0)      // b's floor survives the round trip
        assertEquals(cells(0).size, Size.Fr(2)) // the sibling's recorded share is restored too
      case other => fail(s"unexpected root: $other")

  test("popping out a floating window's root is a no-op"):
    val g = Node.solo(doc("float"))
    val s = LayoutState(
      root = Some(Node.solo(doc("main"))),
      floating = Vector(Floating(WindowId.fresh(), Rect(0, 0, 100, 100), g, None))
    )
    assertEquals(edit.popOut(s, g.id, Rect(0, 0, 50, 50)), s)

  // -- misc regression guards -------------------------------------------------------------------

  property("diff of identical states is empty"):
    forAll(stateGen) { s =>
      assertEquals(DockEvent.diff(s, s), Vector.empty[DockEvent])
      true
    }

  property("commitDivider is a fixed point when reapplied with its own outcome"):
    // normalized regime, as in SizingSuite: fixed claims fit and fr capacity absorbs slack
    val minFree = cellsGen
      .filter(_.length >= 2)
      .map(_.map(_.copy(minPx = 0)))
      .filter: cells =>
        val available = 2000 - (cells.length - 1) * LayoutSettings.default.dividerPx
        val claims = cells.map: c =>
          c.size match
            case Size.Px(v)  => math.max(0.0, v)
            case Size.Pct(p) => math.max(0.0, p) / 100.0 * available
            case Size.Fr(_)  => 0.0
        val hasFr = cells.exists(c =>
          c.size match
            case Size.Fr(_) => true;
            case _          => false
        )
        hasFr && claims.sum <= available
    forAll(minFree, Gen.choose(0.3, 0.7)) { (cells, f) =>
      val once  = sizing.commitDivider(cells, Axis.Horizontal, 0, f, 2000, LayoutSettings.default)
      val alloc = sizing.allocate(once, 2000, LayoutSettings.default.dividerPx)
      val ratio = alloc(0) / (alloc(0) + alloc(1))
      val twice =
        sizing.commitDivider(once, Axis.Horizontal, 0, ratio, 2000, LayoutSettings.default)
      val again = sizing.allocate(twice, 2000, LayoutSettings.default.dividerPx)
      assertEqualsDouble(again(0), alloc(0), 1e-3)
      true
    }
end AdversarialSuite
