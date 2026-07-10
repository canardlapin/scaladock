package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class ThemeSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.theme.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def firstFill(r: Region): Color =
    r.getBackground.getFills.get(0).getFill.asInstanceOf[Color]

  test("the drop indicator resolves its colors from theme variables, not hardcoded values"):
    onFx:
      val dock  = Dock(factories, initial = LayoutState.of(group(TxtPane(Txt("a")).titled("a"))))
      val scene = new Scene(dock.view, 800, 600)
      assert(scene != null)
      val region = dock.view.asInstanceOf[DockRegion]
      region.showIndicator(Rect(10, 10, 100, 100))
      dock.view.applyCss()
      dock.view.layout()

      val indicator = region.lookup(".dock-drop-indicator").asInstanceOf[Region]
      val darkFill  = firstFill(indicator)
      // -dock-accent-soft (dark): #3574f04d — a translucent blue, decidedly not black
      assert(darkFill.getOpacity < 0.99, s"expected translucent accent fill, got $darkFill")
      assert(darkFill.getBlue > darkFill.getRed, s"expected a blue accent, got $darkFill")

      dock.setTheme(DockTheme.Light)
      dock.view.applyCss()
      val lightFill = firstFill(indicator)
      assertNotEquals(lightFill, darkFill, "the light theme must restyle the indicator")

  test("the ghost popup carries the variable-bearing marker class"):
    onFx:
      val ghost = GhostPopup("title", None, None)
      val box   = ghost.getContent.get(0).asInstanceOf[Region]
      assert(box.getStyleClass.contains("dock"), "ghost must resolve theme variables itself")
      assert(box.getStyleClass.contains("dock-ghost"))
end ThemeSuite
