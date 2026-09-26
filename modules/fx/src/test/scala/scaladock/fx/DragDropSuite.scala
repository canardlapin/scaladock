package scaladock.fx

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.stage.Stage
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Full drag gestures through synthetic events. The tab node needs live screen coordinates, so
  * these tests put the dock in a real (off-screen-positioned) Stage.
  */
final class DragDropSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.drag.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def staged(state: LayoutState): (Dock, Stage) =
    val dock  = Dock(factories, initial = state)
    val stage = new Stage
    stage.setScene(new Scene(dock.view, 1200, 800))
    stage.setX(0); stage.setY(0)
    stage.show()
    dock.view.applyCss()
    dock.view.layout()
    (dock, stage)

  private def mouseAt(
      tpe: javafx.event.EventType[MouseEvent],
      sx: Double,
      sy: Double
  ): MouseEvent =
    new MouseEvent(
      tpe,
      0,
      0,
      sx,
      sy,
      MouseButton.PRIMARY,
      1,
      false,
      false,
      false,
      false,
      true,
      false,
      false,
      true,
      false,
      false,
      null
    )

  private def tabOf(dock: Dock, gid: NodeId, index: Int): javafx.scene.Node =
    dock.groupViewFor(gid).get.lookupAll(".dock-tab").toArray
      .map(_.asInstanceOf[javafx.scene.Node]).apply(index)

  private def dragFromTo(tab: javafx.scene.Node, from: (Double, Double), to: (Double, Double))
      : Unit =
    Event.fireEvent(tab, mouseAt(MouseEvent.MOUSE_PRESSED, from._1, from._2))
    Event.fireEvent(tab, mouseAt(MouseEvent.MOUSE_DRAGGED, from._1 + 15, from._2 + 15))
    Event.fireEvent(tab, mouseAt(MouseEvent.MOUSE_DRAGGED, to._1, to._2))
    Event.fireEvent(tab, mouseAt(MouseEvent.MOUSE_RELEASED, to._1, to._2))

  test("dragging a tab onto another group's bottom half splits it vertically 50/50"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          row(
            group(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b")),
            TxtPane(Txt("target")).titled("target")
          )
        )
      )
      try
        val leftGroup   = dock.state.groups.head
        val rightGroup  = dock.state.groups.last
        val bId         = dock.state.panes.find(_.title == "b").get.id
        val bNodeBefore = dock.nodeOf(bId).get

        // the right group's content occupies roughly x in [600,1200]; drop into its lower half
        val tab    = tabOf(dock, leftGroup.id, 1)
        val origin = tab.localToScreen(5, 5)
        val dropAt = dock.view.localToScreen(900, 700)
        dragFromTo(tab, (origin.getX, origin.getY), (dropAt.getX, dropAt.getY))

        edit.parentOf(dock.state, rightGroup.id) match
          case Some((sp, i)) =>
            assertEquals(sp.axis, Axis.Vertical)
            assertEquals(i, 0, "target stays on top; the dropped pane lands below")
            assertEquals(sp.cells.length, 2)
            assertEquals(sp.cells(1).node.panes.map(_.id), Vector(bId))
          case None => fail(s"right group has no parent split: ${dock.state}")

        assert(dock.nodeOf(bId).get eq bNodeBefore, "the pane node survived the drag")
        assertEquals(dock.state.focused, Some(bId))
      finally stage.close()

  test("dropping on a header inserts as a tab; a hopeless miss returns the pane home"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          row(
            group(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b")),
            TxtPane(Txt("target")).titled("target")
          )
        )
      )
      try
        val leftGroup  = dock.state.groups.head
        val rightGroup = dock.state.groups.last
        val bId        = dock.state.panes.find(_.title == "b").get.id

        // drop b onto the right group's header: it becomes a tab there
        val tab    = tabOf(dock, leftGroup.id, 1)
        val origin = tab.localToScreen(5, 5)
        val header = dock.view.localToScreen(900, 12)
        dragFromTo(tab, (origin.getX, origin.getY), (header.getX, header.getY))

        val right = dock.state.findGroup(rightGroup.id).get
        assertEquals(right.tabs.map(_.title).toSet, Set("target", "b"))
        assertEquals(right.active, bId)

        // now drag b far outside every window and release: it returns to its group
        val tabsNow = dock.state.groupOf(bId).get
        val bIndex  = tabsNow.tabs.indexWhere(_.id == bId)
        val tab2    = tabOf(dock, tabsNow.id, bIndex)
        val origin2 = tab2.localToScreen(5, 5)
        dragFromTo(tab2, (origin2.getX, origin2.getY), (9000.0, 9000.0))
        assertEquals(dock.state.groupOf(bId).map(_.id), Some(tabsNow.id))
      finally stage.close()

  test("a window's lone tab dropped on that window's own edge changes nothing"):
    // the pane is not detached at drag start, so its window stays a drop surface; edit.drop
    // would dissolve the lone group (and, for a floating window, the window) and re-dock the
    // pane elsewhere under a fresh group id: the gesture must be recognised as a no-op
    onFx:
      val (dock, stage) = staged(LayoutState.of(group(TxtPane(Txt("solo")).titled("solo"))))
      try
        val before = dock.state
        val gid    = before.groups.head.id
        val tab    = tabOf(dock, gid, 0)
        val origin = tab.localToScreen(5, 5)
        val edge   = dock.view.localToScreen(10, 400) // inside the left window-edge band
        dragFromTo(tab, (origin.getX, origin.getY), (edge.getX, edge.getY))
        assertEquals(dock.state.groups.map(_.id), Vector(gid), "the group keeps its identity")
        assertEquals(dock.state.root, before.root)
      finally stage.close()

  test("reordering within a group lands at the index shown, accounting for the tab's own removal"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          group(
            TxtPane(Txt("a")).titled("a"),
            TxtPane(Txt("b")).titled("b"),
            TxtPane(Txt("c")).titled("c")
          )
        )
      )
      try
        val gid    = dock.state.groups.head.id
        val first  = tabOf(dock, gid, 0)
        val last   = tabOf(dock, gid, 2)
        val origin = first.localToScreen(5, 5)
        // just past the last tab's midpoint: insert after "c"
        val lb = last.localToScreen(last.getLayoutBounds)
        dragFromTo(
          first,
          (origin.getX, origin.getY),
          (lb.getMaxX - 2, lb.getMinY + lb.getHeight / 2)
        )
        assertEquals(dock.state.groups.head.tabs.map(_.title), Vector("b", "c", "a"))
      finally stage.close()

  test("a drag source fabricates a pane on drop and nothing on a miss"):
    onFx:
      val (dock, stage) = staged(LayoutState.of(group(TxtPane(Txt("home")).titled("home"))))
      try
        val palette = new Label("palette")
        var minted  = 0
        val sub = dock.dragSource(palette): () =>
          minted += 1
          TxtPane(Txt(s"minted-$minted")).titled(s"minted-$minted")

        // drag from the palette into the dock's right half
        val dropAt = dock.view.localToScreen(900, 400)
        dragFromTo(palette, (2000, 2000), (dropAt.getX, dropAt.getY))
        assertEquals(minted, 1)
        assertEquals(dock.state.panes.map(_.title).toSet, Set("home", "minted-1"))

        // a miss mints a pane but does not add it (no home to return to)
        dragFromTo(palette, (2000, 2000), (9000, 9000))
        assertEquals(minted, 2)
        assertEquals(dock.state.panes.map(_.title).toSet, Set("home", "minted-1"))

        sub.cancel()
        dragFromTo(palette, (2000, 2000), (dropAt.getX, dropAt.getY))
        assertEquals(minted, 2, "a cancelled drag source mints nothing")
      finally stage.close()
end DragDropSuite
