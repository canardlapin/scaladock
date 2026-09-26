package scaladock.fx

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.{MouseButton, MouseEvent}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class DividerSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.divider.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def mouse(tpe: javafx.event.EventType[MouseEvent], sx: Double, sy: Double): MouseEvent =
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
      false, // shift, ctrl, alt, meta
      true,
      false,
      false, // primary, middle, secondary down
      true,
      false,
      false, // synthesized, popupTrigger, stillSincePress
      null
    )

  test("dragging a divider commits the new ratio, clamped to recursive minimums"):
    onFx:
      val layout = LayoutState.of(
        row(
          TxtPane(Txt("left")).titled("left") sized 1.fr,
          TxtPane(Txt("right")).titled("right") sized 1.fr
        )
      )
      val dock  = Dock(factories, initial = layout)
      val span  = 1200 + dock.settings.dividerPx // two 600px halves either side of the divider
      val scene = new Scene(dock.view, span, 800)
      dock.view.applyCss()
      dock.view.layout()
      assert(scene != null)

      val region = dock.view.asInstanceOf[DockRegion]
      val divider = region.getChildrenUnmodifiable.toArray
        .collectFirst { case d: DividerView => d }
        .getOrElse(fail("no divider"))

      // press in the middle, drag 200px left, release: left pane shrinks to ~400px
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_PRESSED, 600, 400))
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_DRAGGED, 400, 400))
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_RELEASED, 400, 400))
      dock.view.layout()

      val split = dock.state.findSplit(dock.state.root.get.id).getOrElse(fail("no root split"))
      val alloc = sizing.allocate(split.cells, span, dock.settings.dividerPx)
      assertEqualsDouble(alloc(0), 400.0, 1.0)
      assertEqualsDouble(alloc(1), 800.0, 1.0)

      // now try to crush the left pane far past its minimum: the commit clamps
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_PRESSED, 400, 400))
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_DRAGGED, -2000, 400))
      Event.fireEvent(divider, mouse(MouseEvent.MOUSE_RELEASED, -2000, 400))
      dock.view.layout()

      val alloc2 = sizing.allocate(
        dock.state.findSplit(split.id).get.cells,
        span,
        dock.settings.dividerPx
      )
      assertEqualsDouble(alloc2(0), dock.settings.defaultMinPanePx, 1.0)
end DividerSuite
