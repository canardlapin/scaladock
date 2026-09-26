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

final class ChromeSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.chrome.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def dockWith(state: LayoutState): Dock =
    val dock  = Dock(factories, initial = state)
    val scene = new Scene(dock.view, 1000, 700)
    assert(scene != null)
    dock.view.applyCss()
    dock.view.layout()
    dock

  private def groupViewOf(dock: Dock, id: NodeId): GroupView =
    dock.view.asInstanceOf[DockRegion].getChildrenUnmodifiable.toArray
      .collectFirst { case g: GroupView if g.nodeId == id => g }
      .getOrElse(fail(s"no view for group $id"))

  private def click(target: javafx.scene.Node, button: MouseButton = MouseButton.PRIMARY): Unit =
    val press = new MouseEvent(
      MouseEvent.MOUSE_PRESSED,
      0,
      0,
      0,
      0,
      button,
      1,
      false,
      false,
      false,
      false,
      button == MouseButton.PRIMARY,
      button == MouseButton.MIDDLE,
      false,
      true,
      false,
      false,
      null
    )
    val release = new MouseEvent(
      MouseEvent.MOUSE_CLICKED,
      0,
      0,
      0,
      0,
      button,
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
    Event.fireEvent(target, press)
    Event.fireEvent(target, release)
  end click

  test("pressing a tab that moves focus restyles it in place: the pressed node is never replaced"):
    // A drag gesture belongs to the node that received the press. If focusing rebuilt the tab
    // strip, the pressed tab would leave the scene and the platform could route the rest of the
    // gesture elsewhere (seen as a drag that dies after its first step).
    onFx:
      val dock = dockWith(
        LayoutState.of(
          row(
            group(TxtPane(Txt("a")).titled("a")),
            group(TxtPane(Txt("b")).titled("b"), TxtPane(Txt("c")).titled("c"))
          )
        )
      )
      val a = dock.state.panes.find(_.title == "a").get
      dock.focus(a.id)
      val right  = dock.state.groups.last.id
      val gv     = groupViewOf(dock, right)
      val before = gv.lookupAll(".dock-tab").toArray.map(_.asInstanceOf[javafx.scene.Node]).toVector
      click(before(1)) // "c": focuses it, activating its tab and moving focus across groups

      val c = dock.state.panes.find(_.title == "c").get
      assertEquals(dock.state.focused, Some(c.id))
      val after = gv.lookupAll(".dock-tab").toArray.map(_.asInstanceOf[javafx.scene.Node]).toVector
      assertEquals(after.length, 2)
      assert(after.zip(before).forall(_ eq _), "tab nodes must survive a focus change")
      assert(before(1).getScene != null, "the pressed tab is still in the scene")

  test("a strip whose tabs fit is not clipped short: tabs slid aside by a drop slot stay visible"):
    onFx:
      val dock = dockWith(
        LayoutState.of(group(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b")))
      )
      val gv = groupViewOf(dock, dock.state.groups.head.id)
      dock.view.applyCss(); dock.view.layout()
      val viewport = gv.lookup(".dock-tabs-viewport").asInstanceOf[javafx.scene.layout.Region]
      val clip     = viewport.getClip.getLayoutBounds
      assertEqualsDouble(clip.getWidth, viewport.getWidth, 0.5)

  test("the tab close glyph closes exactly that pane"):
    onFx:
      val dock = dockWith(
        LayoutState.of(group(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b")))
      )
      val a     = dock.state.panes.find(_.title == "a").get
      val gid   = dock.state.groups.head.id
      val gv    = groupViewOf(dock, gid)
      val close = gv.lookupAll(".dock-tab-close").toArray.head.asInstanceOf[javafx.scene.Node]

      click(close)
      assertEquals(dock.state.panes.map(_.title), Vector("b"))
      assertEquals(dock.state.findPane(a.id), None)

  test("middle-click closes a closable tab; non-closable panes show no close glyph"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          group(TxtPane(Txt("keep")).titled("keep").fixed, TxtPane(Txt("go")).titled("go"))
        )
      )
      val gid = dock.state.groups.head.id
      val gv  = groupViewOf(dock, gid)
      dock.view.applyCss(); dock.view.layout()

      val tabs = gv.lookupAll(".dock-tab").toArray.map(_.asInstanceOf[javafx.scene.Node])
      assertEquals(tabs.length, 2)
      val closeGlyphs = gv.lookupAll(".dock-tab-close").toArray
        .map(_.asInstanceOf[javafx.scene.Node]).filter(_.isVisible)
      assertEquals(closeGlyphs.length, 1, "only the closable tab shows a close glyph")

      click(tabs(1), MouseButton.MIDDLE)
      assertEquals(dock.state.panes.map(_.title), Vector("keep"))

      click(tabs(0), MouseButton.MIDDLE) // non-closable: middle-click must not close it
      assertEquals(dock.state.panes.map(_.title), Vector("keep"))

  test("the maximize button toggles; the group close button closes closable tabs"):
    onFx:
      val dock = dockWith(
        LayoutState.of(
          row(
            group(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b")),
            TxtPane(Txt("side")).titled("side")
          )
        )
      )
      val gid = dock.state.groups.head.id
      val gv  = groupViewOf(dock, gid)

      val maxButton = gv.lookupAll(".dock-header-button.maximize").toArray.head
        .asInstanceOf[javafx.scene.Node]
      click(maxButton)
      assertEquals(dock.state.maximized, Some(gid))
      click(maxButton)
      assertEquals(dock.state.maximized, None)

      val closeButton = gv.lookupAll(".dock-header-button.close").toArray.head
        .asInstanceOf[javafx.scene.Node]
      click(closeButton)
      assertEquals(dock.state.panes.map(_.title), Vector("side"))

  test("a hidden header renders no header bar and geometry gives the content the full rect"):
    onFx:
      val hidden = Node.Group(
        NodeId.fresh(),
        Vector(TxtPane(Txt("naked")).titled("naked").toPane),
        PaneId.fresh()
      ).copy(header = Header.Hidden)
      val state = edit.canonical(LayoutState.of(hidden))
      val dock  = dockWith(state)

      val geom = sizing.geometry(dock.state.root, Rect(0, 0, 1000, 700), dock.settings)
      val gg   = geom.groups(dock.state.groups.head.id)
      assertEquals(gg.header.height, 0.0)
      assertEquals(gg.content, gg.bounds)

      val gv = groupViewOf(dock, dock.state.groups.head.id)
      assert(!gv.lookupAll(".dock-header").toArray.head.asInstanceOf[javafx.scene.Node].isVisible)

  test("overflow menu lists every tab and appears only when the strip is too tight"):
    onFx:
      val manyTabs = (1 to 12).map(i => TxtPane(Txt(s"pane $i")).titled(s"a-rather-long-title-$i"))
      val dock     = dockWith(LayoutState.of(group(manyTabs.head, manyTabs.tail*)))
      val gid      = dock.state.groups.head.id
      val gv       = groupViewOf(dock, gid)
      dock.view.applyCss(); dock.view.layout()

      val menu = gv.lookupAll(".dock-tab-overflow").toArray.head
        .asInstanceOf[javafx.scene.control.MenuButton]
      assert(menu.isVisible, "12 long tabs in 1000px must overflow")
      val tabEntries =
        menu.getItems.toArray.count(_.isInstanceOf[javafx.scene.control.CheckMenuItem])
      assertEquals(tabEntries, 12, "the menu lists every tab")

      // now with a single short tab the menu hides
      val dock2 = dockWith(LayoutState.of(group(TxtPane(Txt("only")).titled("only"))))
      val gv2   = groupViewOf(dock2, dock2.state.groups.head.id)
      dock2.view.applyCss(); dock2.view.layout()
      val menu2 = gv2.lookupAll(".dock-tab-overflow").toArray.head
        .asInstanceOf[javafx.scene.control.MenuButton]
      assert(!menu2.isVisible)
end ChromeSuite
