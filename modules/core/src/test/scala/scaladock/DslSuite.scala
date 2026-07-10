package scaladock

import munit.FunSuite
import TestPanes.*
import dsl.*

final class DslSuite extends FunSuite:

  test("a VS Code-style layout reads as a picture and builds the expected tree"):
    val explorer = Text(Doc("explorer", 0)).titled("Explorer")
    val viewerA  = Text(Doc("scan-01", 0)).titled("scan-01")
    val viewerB  = Text(Doc("scan-02", 0)).titled("scan-02")
    val console  = Counter(0).titled("Console")
    val problems = Counter(1).titled("Problems")
    val outline  = Counter(2).titled("Outline")

    val layout = LayoutState.of(
      row(
        explorer sized 260.px,
        column(
          row(group(viewerA), group(viewerB)) sized 1.fr,
          group(console, problems) sized 220.px
        ) sized 1.fr,
        group(outline) sized 280.px
      )
    )

    // already canonical: the DSL builds well-formed trees directly
    assertEquals(edit.canonical(layout), layout)
    Invariants.assertCanonical(layout)

    layout.root match
      case Some(Node.Split(_, Axis.Horizontal, cells)) =>
        assertEquals(cells.map(_.size), Vector[Size](Size.Px(260), Size.Fr(1), Size.Px(280)))
        cells(1).node match
          case Node.Split(_, Axis.Vertical, inner) =>
            assertEquals(inner.map(_.size), Vector[Size](Size.Fr(1), Size.Px(220)))
            inner(1).node match
              case Node.Group(_, tabs, active, _) =>
                assertEquals(tabs.map(_.title), Vector("Console", "Problems"))
                assertEquals(active, tabs.head.id, "first pane of a group is the active tab")
              case other => fail(s"expected the bottom panel group, got $other")
          case other => fail(s"expected the vertical centre column, got $other")
      case other => fail(s"unexpected root: $other")

  test("bare panes auto-wrap into their own group"):
    val layout = row(Text(Doc("a", 0)), Text(Doc("b", 0)))
    layout.groups.foreach(g => assertEquals(g.tabs.length, 1))
    assertEquals(layout.groups.length, 2)

  test("atLeast attaches a minimum pixel extent"):
    val slot = Text(Doc("a", 0)) atLeast 150
    assertEquals(slot.cell.minPx, 150.0)
end DslSuite
