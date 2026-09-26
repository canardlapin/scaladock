package scaladock.fx

import javafx.scene.Scene
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Header actions are chrome for the group you are working in: shown on the focused (or hovered)
  * group, hidden elsewhere, so a layout of many groups does not repeat the same buttons everywhere.
  */
class HeaderActionsSuite extends FunSuite:
  final case class T(t: String) derives ReadWriter
  val TP: PaneType[T] = PaneType[T]("header-actions.t")

  test("header actions show on the focused group only"):
    onFx:
      val factories = PaneFactories.empty.register(TP): s =>
        new PaneView[T]:
          val node          = new javafx.scene.control.Label(s.t)
          def snapshot(): T = s
      val dock = Dock(
        factories,
        initial = LayoutState.of(row(group(TP(T("a")).titled("a")), group(TP(T("b")).titled("b"))))
      )
      val scene = new Scene(dock.view, 1000, 700)
      assert(scene != null)
      val a = dock.state.panes.find(_.title == "a").get
      dock.focus(a.id)
      dock.view.applyCss(); dock.view.layout(); dock.view.applyCss()

      def opacityIn(title: String): Double =
        val gid = dock.state.groups.find(_.tabs.exists(_.title == title)).get.id
        dock.groupViewFor(gid).get.lookup(".dock-header-buttons").getOpacity
      assertEquals(opacityIn("a"), 1.0, "the focused group shows its actions")
      assertEquals(opacityIn("b"), 0.0, "an unfocused, unhovered group hides them")

      // while a drag is in flight, even the focused group's actions step back
      dock.dragActive(true)
      dock.view.applyCss()
      assertEquals(opacityIn("a"), 0.0, "a drag quiets the focused group's actions too")
      dock.dragActive(false)
      dock.view.applyCss()
      assertEquals(opacityIn("a"), 1.0)
end HeaderActionsSuite
