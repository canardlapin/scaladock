package scaladock.fx

import javafx.event.Event
import javafx.scene.{AccessibleAction, AccessibleAttribute, AccessibleRole, Node as FxNode, Scene}
import javafx.scene.control.{ContextMenu, MenuButton, TextField}
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout.Region
import javafx.stage.{Stage, Window}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import FxFixture.onFx

final class AccessibilitySuite extends FunSuite:
  final case class Text(value: String) derives ReadWriter
  private val kind = PaneType[Text]("a11y.synthetic")

  private def make(prepare: () => Future[Boolean] = () => Future.successful(true)): Dock =
    val factories = PaneFactories.empty.register(kind): text =>
      new PaneView[Text]:
        val node: TextField                          = new TextField(text.value)
        def snapshot(): Text                         = Text(node.getText)
        override def prepareClose(): Future[Boolean] = prepare()
    val dock = Dock(
      factories,
      initial = LayoutState.of(row(
        group(kind(Text("alpha")).titled("Alpha"), kind(Text("beta")).titled("Beta")),
        group(kind(Text("side")).titled("Side"))
      ))
    )
    val scene = new Scene(dock.view, 1100, 650)
    assert(scene != null)
    dock.view.applyCss(); dock.view.layout()
    dock

  private def press(node: FxNode, code: KeyCode, shift: Boolean = false): Unit =
    Event.fireEvent(
      node,
      new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false)
    )

  private def tab(d: Dock, title: String): FxNode =
    d.view.lookupAll(".dock-tab").toArray.collectFirst:
      case n: FxNode if n.getAccessibleText == title => n
    .get

  test("assistive tab request selects; arrow keys rove; selection and close names follow state"):
    onFx:
      val d = make()
      try
        val a = tab(d, "Alpha")
        val b = tab(d, "Beta")
        b.executeAccessibleAction(AccessibleAction.REQUEST_FOCUS)
        assertEquals(d.state.focused.flatMap(d.state.findPane).map(_.title), Some("Beta"))
        assertEquals(
          b.queryAccessibleAttribute(AccessibleAttribute.SELECTED),
          java.lang.Boolean.TRUE
        )
        assertEquals(
          a.queryAccessibleAttribute(AccessibleAttribute.SELECTED),
          java.lang.Boolean.FALSE
        )
        assert(b.isFocusTraversable && !a.isFocusTraversable)
        assert(d.view.getScene.getFocusOwner eq b)
        press(b, KeyCode.LEFT)
        assert(d.view.getScene.getFocusOwner eq a)
        press(a, KeyCode.END)
        assert(d.view.getScene.getFocusOwner eq b)
        assertEquals(b.lookup(".dock-tab-close").getAccessibleText, "Close Beta")
      finally d.dispose()

  test("header and minimized actions support keyboard and assistive FIRE with live focus"):
    onFx:
      val d = make()
      try
        val id = d.state.panes.head.id
        val g  = d.state.groupOf(id).get.id
        val v  = d.groupViewFor(g).get
        d.focus(id); d.focusContent(id)
        val max = v.lookup(".dock-header-button.maximize")
        max.executeAccessibleAction(AccessibleAction.FIRE)
        assertEquals(d.state.maximized, Some(g))
        assertEquals(max.getAccessibleText, "Restore layout")
        press(max, KeyCode.SPACE)
        assertEquals(d.state.maximized, None)
        val min = v.lookup(".dock-header-button.minimize")
        press(min, KeyCode.ENTER)
        assert(d.state.minimized(g))
        d.view.layout()
        val strip = v.minimizedFocusTarget.get
        assertEquals(strip.getAccessibleRole, AccessibleRole.BUTTON)
        assertEquals(strip.getAccessibleText, "Restore Alpha")
        strip.executeAccessibleAction(AccessibleAction.FIRE)
        assert(!d.state.minimized(g))
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(id).get)
      finally d.dispose()

  test("keyboard-visible chrome survives inactive styling and narrow overflow has a stable name"):
    val (d, st) = onFx:
      val d  = make()
      val st = new Stage
      st.setScene(d.view.getScene); st.show(); st.requestFocus()
      (d, st)
    try
      Thread.sleep(200)
      onFx:
        val v      = d.groupViewFor(d.state.groups.head.id).get
        val button = v.lookup(".dock-header-button.maximize").asInstanceOf[Region]
        button.requestFocus()
        d.view.pseudoClassStateChanged(pseudo.Inactive, true)
        d.view.applyCss()
        assertEquals(v.lookup(".dock-header-buttons").getOpacity, 1.0)
        assert(!button.getBorder.getStrokes.isEmpty)
        v.resize(250, 300); v.layout()
        val overflow = v.lookup(".dock-tab-overflow").asInstanceOf[MenuButton]
        assert(overflow.isVisible && overflow.isFocusTraversable)
        assertEquals(overflow.getAccessibleText, "Tabs and group actions")
    finally onFx { d.dispose(); st.close() }

  test("assistive and keyboard close share asynchronous admission; reopen uses the live snapshot"):
    val gate = Promise[Boolean]()
    val (d, id, pending) = onFx:
      val d  = make(() => gate.future)
      val id = d.state.panes.head.id
      d.focus(id); d.focusContent(id)
      d.nodeOf(id).get.asInstanceOf[TextField].setText("edited")
      tab(d, "Alpha").lookup(".dock-tab-close").executeAccessibleAction(AccessibleAction.FIRE)
      val pending = d.requestClose(id)
      assert(d.state.findPane(id).isDefined)
      (d, id, pending)
    try
      gate.success(true)
      assert(Await.result(pending, 5.seconds))
      onFx:
        assert(d.state.findPane(id).isEmpty)
        val owner = d.view.getScene.getFocusOwner
        assert(owner != null && owner.getScene != null)
        d.perform(DockAction.ReopenClosed)
        assertEquals(d.nodeOf(id).get.asInstanceOf[TextField].getText, "edited")
        assertEquals(d.state.focused, Some(id))
        val count = d.state.panes.size
        d.perform(DockAction.ReopenClosed)
        assertEquals(d.state.panes.size, count)
    finally onFx(d.dispose())

  test("vetoed accessible close leaves pane, focus and reopen history untouched"):
    onFx:
      val d = make(() => Future.successful(false))
      try
        val id = d.state.panes.head.id
        d.focus(id); d.focusContent(id)
        val before = d.state
        val close  = tab(d, "Alpha").lookup(".dock-tab-close")
        press(close, KeyCode.SPACE)
        assertEquals(d.state, before)
        d.perform(DockAction.ReopenClosed)
        assertEquals(d.state, before)
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(id).get)
      finally d.dispose()

  test(
    "Shift+F10 and assistive SHOW_MENU open live menus; Escape dismisses; consumed keys stay local"
  ):
    val (d, st) = onFx:
      val d  = make()
      val st = new Stage
      st.setScene(d.view.getScene); st.show()
      d.focus(d.state.panes.head.id); d.focusContent(d.state.panes.head.id)
      (d, st)
    try
      onFx:
        val content = d.nodeOf(d.state.focused.get).get
        press(content, KeyCode.F10, shift = true)
        def menu =
          Window.getWindows.toArray.collectFirst { case m: ContextMenu if m.isShowing => m }.get
        val opened = menu
        assert(opened.getItems.size > 0)
        press(opened.getScene.getRoot, KeyCode.ESCAPE)
        assert(!opened.isShowing)
        assertEquals(d.styleBindingCount, 0)
        tab(d, "Beta").executeAccessibleAction(AccessibleAction.SHOW_MENU)
        assert(menu.isShowing)
        menu.hide()
        content.addEventHandler(KeyEvent.KEY_PRESSED, (e: KeyEvent) => e.consume())
        press(content, KeyCode.F10, shift = true)
        assert(!Window.getWindows.toArray.exists {
          case m: ContextMenu => m.isShowing; case _ => false
        })
    finally onFx { d.dispose(); st.close() }

  test("pop-out, dock-back and perspective changes preserve live focus and pane identity"):
    val (d, st) = onFx:
      val d  = make()
      val st = new Stage
      st.setScene(d.view.getScene); st.show()
      (d, st)
    try
      onFx:
        val id = d.state.panes.head.id
        d.focus(id); d.focusContent(id)
        val original = d.nodeOf(id).get
        val saved    = d.state
        d.perform(DockAction.TogglePopOut)
        assertEquals(d.state.floating.size, 1)
        assert(original.getScene.getFocusOwner eq original)
        d.perform(DockAction.TogglePopOut)
        assert(d.state.floating.isEmpty)
        assert(d.view.getScene.getFocusOwner eq original)
        val side = saved.panes.last.id
        d.switchTo(edit.focus(edit.removePane(saved, id), side))
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(side).get)
        d.switchTo(saved)
        assert(d.nodeOf(id).get eq original)
        assert(d.view.getScene.getFocusOwner eq original)
    finally onFx { d.dispose(); st.close() }
  test("a vertically minimized tab has no hidden close target; its menu still admits close"):
    onFx:
      val d = make()
      try
        val layout = d.state
        d.update(_ => LayoutState.of(column(layout.groups.map(g => g: Node)*)))
        val id    = d.state.panes.head.id
        val group = d.state.groupOf(id).get.id
        d.focus(id); d.minimize(group)
        d.view.applyCss(); d.view.layout()
        val close = tab(d, "Alpha").lookup(".dock-tab-close")
        assert(!close.isVisible && !close.isFocusTraversable)
        d.tabMenuItems(id).find(_.getText == "Close").get.fire()
        assert(d.state.findPane(id).isEmpty)
      finally d.dispose()

  test("closing main focus is not masked by a floating window's retained focus owner"):
    val (d, st, id) = onFx:
      val d  = make()
      val st = new Stage
      st.setScene(d.view.getScene); st.show()
      val id   = d.state.panes.head.id
      val side = d.state.panes.last.id
      d.popOut(d.state.groupOf(side).get.id)
      d.focus(side); d.focusContent(side)
      d.focus(id); d.focusContent(id)
      (d, st, id)
    try
      Thread.sleep(200)
      onFx:
        d.close(id)
        assert(d.state.focused.isDefined)
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(d.state.focused.get).get)
      Thread.sleep(200)
      onFx:
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(d.state.focused.get).get)
    finally onFx { d.dispose(); st.close() }

  test("JavaFX27 titlebar buttons route group keys and menus from their own focus"):
    val (d, st, side) = onFx:
      val d  = make()
      val st = new Stage
      st.setScene(d.view.getScene); st.show()
      val side = d.state.panes.last.id
      d.popOut(d.state.groupOf(side).get.id)
      (d, st, side)
    try
      Thread.sleep(200)
      val button = onFx:
        val region = d.dragSurfaces.find(_._1.isDefined).get._2
        val root   = region.getScene.getRoot
        val major  = System.getProperty("javafx.runtime.version").takeWhile(_.isDigit).toInt
        if major >= 27 then
          val button = root.lookup(".dock-window-actions .popout")
          assert(button != null, "the 27 lane must actually exercise its custom titlebar")
          region.getScene.getWindow.requestFocus()
          button.requestFocus()
          Some(button)
        else
          assert(root.lookup(".dock-window-actions") == null)
          None
      Thread.sleep(200)
      onFx:
        button.foreach: b =>
          assert(b.isFocused, "native focus reached the titlebar button")
          assertEquals(d.state.focused, Some(side), "titlebar focus aligns the model")
          press(b, KeyCode.F10, shift = true)
          val menu =
            Window.getWindows.toArray.collectFirst { case m: ContextMenu if m.isShowing => m }.get
          assert(menu.getItems.toArray.exists(
            _.asInstanceOf[javafx.scene.control.MenuItem].getText == "Dock Back"
          ))
          menu.hide()
          press(b, KeyCode.F6)
          assert(d.state.focused != Some(side))
          b.getScene.getWindow.requestFocus()
          b.requestFocus()
      Thread.sleep(200)
      onFx:
        button.foreach: b =>
          assertEquals(d.state.focused, Some(side))
          press(b, KeyCode.SPACE)
          assert(d.state.floating.isEmpty)
          assert(d.view.getScene.getFocusOwner eq d.nodeOf(side).get)
    finally onFx { d.dispose(); st.close() }
end AccessibilitySuite
