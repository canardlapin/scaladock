package scaladock

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import TestPanes.*

final class CanonicalSuite extends ScalaCheckSuite:

  private def paneIds(s: LayoutState): List[String] =
    s.panes.map(_.id.value).sorted.toList

  property("canonical is idempotent"):
    forAll(rawStateGen) { s =>
      val once = edit.canonical(s)
      assertEquals(edit.canonical(once), once)
      true
    }

  property("canonical preserves the multiset of panes"):
    forAll(rawStateGen) { s =>
      assertEquals(paneIds(edit.canonical(s)), paneIds(s))
      true
    }

  property("canonical output satisfies the tree grammar's laws"):
    forAll(rawStateGen) { s =>
      Invariants.assertCanonical(edit.canonical(s))
      true
    }

  property("canonical clears dangling maximized and focused references"):
    forAll(rawStateGen) { s =>
      val decorated = s.copy(maximized = Some(NodeId.fresh()), focused = Some(PaneId.fresh()))
      val c         = edit.canonical(decorated)
      assertEquals(c.maximized, None)
      assertEquals(c.focused, None)
      true
    }

  test("a dissolving split's promoted child keeps the dissolved slot's size"):
    val inner = Node.solo(doc("a"))
    val outer = Node.Split(
      NodeId.fresh(),
      Axis.Horizontal,
      Vector(
        Cell(Node.Split(NodeId.fresh(), Axis.Vertical, Vector(Cell(inner))), Size.Px(300)),
        Cell(Node.solo(doc("b")), Size.Fr(1))
      )
    )
    edit.canonical(LayoutState.of(outer)).root match
      case Some(Node.Split(_, _, cells)) =>
        assertEquals(cells.head.size, Size.Px(300)) // slot size survived the dissolution
        assertEquals(cells.head.node.id, inner.id)  // and the child was promoted, not rebuilt
      case other => fail(s"unexpected root: $other")
end CanonicalSuite
