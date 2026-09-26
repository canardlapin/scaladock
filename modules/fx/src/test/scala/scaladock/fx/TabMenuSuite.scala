package scaladock.fx

import javafx.scene.{AccessibleRole, Scene}
import javafx.scene.control.{Label, MenuItem}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** The tab context menu: sensible defaults, a host hook that sees the pane and its group, and
  * accessible names on the chrome.
  */
final class TabMenuSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.menu.txt")

  private def dockWith(state: LayoutState): Dock =
    val factories = PaneFactories.empty.register(TxtPane): s =>
      new PaneView[Txt]:
        val node            = new Label(s.text)
        def snapshot(): Txt = s
    val dock  = Dock(factories, initial = state)
    val scene = new Scene(dock.view, 1000, 700)
    assert(scene != null)
    dock

  private def labels(items: Seq[MenuItem]): Vector[String] =
    items.toVector.map(i => Option(i.getText).getOrElse("—"))

  test("default items, and Close Others closes exactly the others"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          row(
            group(
              TxtPane(Txt("a")).titled("a"),
              TxtPane(Txt("b")).titled("b"),
              TxtPane(Txt("keep")).titled("keep").fixed
            ),
            TxtPane(Txt("side")).titled("side")
          )
        )
      )
      val a     = dock.state.panes.find(_.title == "a").get.id
      val items = dock.tabMenuItems(a)
      assertEquals(
        labels(items),
        Vector(
          "Close",
          "Close Others",
          "Close All",
          "—",
          "Open in New Window",
          "Maximize",
          "Minimize"
        )
      )
      items.find(_.getText == "Close Others").get.fire()
      assertEquals(
        dock.state.panes.map(_.title).toSet,
        Set("a", "keep", "side"),
        "non-closable stays"
      )

  test("the hook sees the pane and its group, and returns the final list"):
    onFx:
      val dock = dockWith(LayoutState.of(group(TxtPane(Txt("a")).titled("a"))))
      val a    = dock.state.panes.head.id
      var seen = Option.empty[TabMenuContext]
      dock.setTabMenu: (ctx, defaults) =>
        seen = Some(ctx)
        new MenuItem("Show as table") +: defaults.take(1)
      assertEquals(labels(dock.tabMenuItems(a)), Vector("Show as table", "Close"))
      assertEquals(seen.map(_.pane), Some(a))
      assertEquals(seen.map(_.group), dock.state.groupOf(a).map(_.id))

  test("tabs and header buttons carry accessible roles and names"):
    onFx:
      val dock = dockWith(LayoutState.of(group(TxtPane(Txt("a")).titled("Explorer"))))
      dock.view.applyCss(); dock.view.layout()
      val tab = dock.view.lookup(".dock-tab")
      assertEquals(tab.getAccessibleRole, AccessibleRole.TAB_ITEM)
      assertEquals(tab.getAccessibleText, "Explorer")
      val max = dock.view.lookup(".dock-header-button.maximize")
      assertEquals(max.getAccessibleRole, AccessibleRole.BUTTON)
      assertEquals(max.getAccessibleText, "Maximize")
end TabMenuSuite
