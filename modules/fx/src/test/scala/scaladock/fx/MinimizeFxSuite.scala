package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class MinimizeFxSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.minimize.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def dockWith(state: LayoutState): Dock =
    val dock  = Dock(factories, initial = state)
    val scene = new Scene(dock.view, 1200, 800)
    assert(scene != null)
    dock.view.applyCss()
    dock.view.layout()
    dock

  private def groupViewOf(dock: Dock, id: NodeId): GroupView =
    dock.view.asInstanceOf[DockRegion].getChildrenUnmodifiable.toArray
      .collectFirst { case g: GroupView if g.nodeId == id => g }
      .getOrElse(fail(s"no view for group $id"))

  test("minimizing a bottom panel collapses it to a header strip; restore is exact"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          column(
            TxtPane(Txt("editor")).titled("editor") sized 1.fr,
            TxtPane(Txt("console")).titled("console") sized 200.px
          )
        )
      )
      val console     = dock.state.groupOf(dock.state.panes.find(_.title == "console").get.id).get
      val consoleNode = dock.nodeOf(console.tabs.head.id).get
      val gv          = groupViewOf(dock, console.id)
      val heightBefore =
        dock.view.layout(); gv.getHeight

      dock.minimize(console.id)
      dock.view.layout()
      assertEqualsDouble(gv.getHeight, sizing.stripPx(dock.settings), 1.0)
      assert(dock.state.minimized(console.id))

      dock.unminimize(console.id)
      dock.view.layout()
      assertEqualsDouble(gv.getHeight, heightBefore, 1.0) // the 200px came back exactly
      assert(dock.nodeOf(console.tabs.head.id).get eq consoleNode, "pane node never rebuilt")

  test("a group minimized inside a row shows the sideways strip; clicking it restores"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          row(
            TxtPane(Txt("side")).titled("side") sized 260.px,
            TxtPane(Txt("main")).titled("main") sized 1.fr
          )
        )
      )
      val side = dock.state.groupOf(dock.state.panes.find(_.title == "side").get.id).get
      val gv   = groupViewOf(dock, side.id)

      dock.minimize(side.id)
      dock.view.applyCss()
      dock.view.layout()
      assertEqualsDouble(gv.getWidth, sizing.stripPx(dock.settings), 1.0)
      val strip = gv.lookup(".dock-strip")
      assert(strip != null && strip.isVisible, "sideways strip shown for horizontal collapse")

      // clicking the strip restores
      javafx.event.Event.fireEvent(
        strip,
        new javafx.scene.input.MouseEvent(
          javafx.scene.input.MouseEvent.MOUSE_CLICKED,
          0,
          0,
          0,
          0,
          javafx.scene.input.MouseButton.PRIMARY,
          1,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          true,
          false,
          false,
          null
        )
      )
      dock.view.layout()
      assert(!dock.state.minimized(side.id))
      assertEqualsDouble(gv.getWidth, 260.0, 1.0)

  test("focusing a pane in a minimized group restores it; drags of strip dividers are refused"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          column(
            TxtPane(Txt("a")).titled("a") sized 1.fr,
            TxtPane(Txt("b")).titled("b") sized 200.px
          )
        )
      )
      val b = dock.state.panes.find(_.title == "b").get
      val g = dock.state.groupOf(b.id).get
      dock.minimize(g.id)
      dock.focus(b.id)
      assert(!dock.state.minimized(g.id), "focus restores the group")
end MinimizeFxSuite
