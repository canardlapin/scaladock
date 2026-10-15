package scaladock.fx

import javafx.event.Event
import javafx.scene.{Parent, Scene}
import javafx.scene.control.{ContextMenu, Label, MenuButton}
import javafx.scene.input.{ContextMenuEvent, KeyCode, KeyEvent, MouseButton, MouseEvent, PickResult}
import javafx.scene.layout.{Region, StackPane}
import javafx.scene.paint.Color
import javafx.stage.{Stage, Window}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class PresentationSuite extends FunSuite:
  final case class Text(value: String) derives ReadWriter
  private val TextPane = PaneType[Text]("test.presentation.text")
  private final class Counts:
    var built    = 0
    var disposed = 0
  private def pane(s: String): PaneDef = TextPane(Text(s)).titled(s)

  private def fixture(body: (Dock, Stage, Counts) => Unit): Unit =
    val count = new Counts
    val factories = PaneFactories.empty.register(TextPane): text =>
      count.built += 1
      new PaneView[Text]:
        val node                     = new Label(text.value)
        def snapshot(): Text         = text
        override def dispose(): Unit = count.disposed += 1
    val dock = Dock(
      factories,
      initial = LayoutState.of(row(
        group(pane("a"), pane("b")),
        group(pane("c"), pane("d"))
      ))
    )
    val owner = new Stage
    owner.setScene(new Scene(dock.view, 600, 450))
    owner.show()
    dock.view.applyCss()
    dock.view.layout()
    try body(dock, owner, count)
    finally
      dock.dispose()
      owner.close()

  private def sheets(body: (DockTheme, DockTheme, String) => Unit): Unit =
    val dir     = java.nio.file.Files.createTempDirectory("scaladock-s3-synthetic")
    val dark    = dir.resolve("light-name.css")
    val light   = dir.resolve("dark-name.css")
    val foreign = dir.resolve("foreign.css")
    try
      java.nio.file.Files.writeString(
        dark,
        ".dock { -dock-base: #18202f; -dock-surface: #26344a; -dock-surface-raised: #30405a; -dock-text: #edf1f7; -dock-text-strong: #edf1f7; }"
      ): Unit
      java.nio.file.Files.writeString(
        light,
        ".dock { -dock-base: #ffffff; -dock-surface: #f2f3f5; -dock-surface-raised: #f5eee2; -dock-text: #1b293c; -dock-text-strong: #1b293c; }"
      ): Unit
      java.nio.file.Files.writeString(foreign, ".host-marker { -fx-opacity: 0.91; }"): Unit
      body(
        DockTheme.CustomWithScheme(dark.toUri.toString, true),
        DockTheme.CustomWithScheme(light.toUri.toString, false),
        foreign.toUri.toString
      )
    finally
      for path <- Vector(dark, light, foreign, dir) do
        java.nio.file.Files.deleteIfExists(path): Unit

  private def context(dock: Dock): ContextMenu =
    val tab = dock.view.lookup(".dock-tab")
    val p   = tab.localToScreen(5, 5)
    Event.fireEvent(
      tab,
      new ContextMenuEvent(
        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
        5,
        5,
        p.getX,
        p.getY,
        false,
        new PickResult(tab, 5, 5)
      )
    )
    Window.getWindows.toArray.collectFirst:
      case menu: ContextMenu if menu.getOwnerNode eq tab => menu
    .getOrElse(fail("No owned tab menu"))

  private def assertPaint(root: Parent, selector: String, expected: String): Unit =
    root.applyCss()
    root.layout()
    val colors = root.lookupAll(selector).toArray.toVector.collect:
      case r: Region if r.getBackground != null =>
        import scala.jdk.CollectionConverters.*
        r.getBackground.getFills.asScala.toVector.map(_.getFill)
    .flatten
    assert(colors.contains(Color.web(expected)), s"$selector expected $expected; paints=$colors")

  private def mouse(kind: javafx.event.EventType[MouseEvent], x: Double, y: Double): MouseEvent =
    new MouseEvent(
      kind,
      0,
      0,
      x,
      y,
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

  private def startDrag(dock: Dock): javafx.stage.Popup =
    val tab = dock.view.lookup(".dock-tab")
    val p   = tab.localToScreen(5, 5)
    Event.fireEvent(tab, mouse(MouseEvent.MOUSE_PRESSED, p.getX, p.getY))
    Event.fireEvent(tab, mouse(MouseEvent.MOUSE_DRAGGED, p.getX + 20, p.getY + 20))
    Window.getWindows.toArray.toVector.collect { case popup: javafx.stage.Popup => popup }
      .find(popup =>
        popup.getContent.toArray.exists {
          case r: Parent => r.getStyleClass.contains("dock-ghost")
          case _         => false
        }
      ).getOrElse(fail("No owned drag ghost"))

  test("repeated host root leases preserve foreign styles/classes and share one live binding"):
    onFx:
      sheets: (dark, light, foreign) =>
        fixture: (dock, _, _) =>
          val root = new StackPane(new Label("synthetic host"))
          root.getStyleClass.add("host-marker")
          root.getStylesheets.addAll(foreign, dark.stylesheets.head)
          val initial = root.getStylesheets.toArray.toVector
          val scene   = new Scene(root, 100, 100)
          assert(scene != null)
          val originalClasses = root.getStyleClass.toArray.toVector
          dock.setTheme(dark)
          val a = dock.bindStylesheets(root)
          val b = dock.bindStylesheets(root)
          assertEquals(dock.styleBindingCount, 1)
          for _ <- 0 until 10 do
            dock.setTheme(light)
            dock.setTheme(dark)
            assertEquals(root.getStylesheets.size, 3)
            assertEquals(root.getStylesheets.toArray.distinct.length, 3)
            assert(root.getStylesheets.contains(foreign))
          a.cancel()
          assert(root.getStylesheets.contains(dark.stylesheets.last))
          b.cancel()
          b.cancel()
          assertEquals(dock.styleBindingCount, 0)
          assertEquals(root.getStylesheets.toArray.toVector, initial)
          assertEquals(root.getStyleClass.toArray.toVector, originalClasses)

  test("open tab menus dismiss on theme/density changes and reopen with current computed colors"):
    onFx:
      sheets: (dark, light, _) =>
        fixture: (dock, _, _) =>
          dock.setTheme(dark)
          val before = dock.ownedBindingCount
          val first  = context(dock)
          assertPaint(first.getScene.getRoot, ".context-menu", "#30405a")
          assertEquals(dock.styleBindingCount, 1)
          dock.setTheme(light)
          assert(!first.isShowing)
          assertEquals(first.getItems.size, 0)
          assertEquals(dock.styleBindingCount, 0)
          val second = context(dock)
          assertPaint(second.getScene.getRoot, ".context-menu", "#f5eee2")
          dock.setSettings(LayoutSettings(headerPx = 28))
          assert(!second.isShowing)
          val third = context(dock)
          assertPaint(third.getScene.getRoot, ".context-menu", "#f5eee2")
          third.hide()
          assertEquals(dock.ownedBindingCount, before)

  test("overflow menus and tooltips dismiss and reopen using current custom styles"):
    onFx:
      sheets: (dark, light, _) =>
        fixture: (dock, _, _) =>
          dock.setTheme(dark)
          val group    = dock.groupViewFor(dock.state.groups.head.id).get
          val overflow = group.lookup(".dock-tab-overflow").asInstanceOf[MenuButton]
          overflow.show()
          val menu = Window.getWindows.toArray.collectFirst:
            case m: ContextMenu if m.getOwnerNode eq overflow => m
          .getOrElse(fail("No overflow popup"))
          assertPaint(menu.getScene.getRoot, ".context-menu", "#30405a")
          dock.setTheme(light)
          assert(!overflow.isShowing && !menu.isShowing)
          overflow.show()
          assertPaint(menu.getScene.getRoot, ".context-menu", "#f5eee2")
          dock.setSettings(LayoutSettings(headerPx = 28))
          assert(!overflow.isShowing)
          val button = group.lookup(".dock-header-button.close")
          val tip    = group.tipOf(button).get
          val p      = button.localToScreen(0, 0)
          tip.show(button, p.getX, p.getY)
          assertPaint(tip.getScene.getRoot, ".tooltip", "#f5eee2")
          dock.setTheme(dark)
          assert(!tip.isShowing)
          tip.show(button, p.getX, p.getY)
          assertPaint(tip.getScene.getRoot, ".tooltip", "#30405a")
          dock.setSettings(LayoutSettings(headerPx = 32))
          assert(!tip.isShowing)
          assertEquals(dock.styleBindingCount, 0)

  test("theme and metric changes cancel ghosts; next ghost owns the current independent root"):
    onFx:
      sheets: (dark, light, _) =>
        fixture: (dock, _, _) =>
          dock.setTheme(dark)
          val first = startDrag(dock)
          val root  = first.getContent.get(0).asInstanceOf[Parent]
          assert(root.getStyleClass.contains("dock"))
          assertPaint(root, ".dock-ghost-chip", "#30405a")
          val state = dock.state
          dock.setTheme(light)
          assert(!first.isShowing)
          assertEquals(first.getContent.size, 0)
          assertEquals(dock.state, state)
          val second = startDrag(dock)
          assertPaint(second.getContent.get(0).asInstanceOf[Parent], ".dock-ghost-chip", "#f5eee2")
          dock.setSettings(LayoutSettings(headerPx = 28))
          assert(!second.isShowing)
          assertEquals(second.getContent.size, 0)

  test("repeated bindings/subscriptions and source handles are released by Dock.dispose"):
    onFx:
      sheets: (_, light, foreign) =>
        fixture: (dock, _, counts) =>
          val baseline = dock.ownedBindingCount
          val root     = new StackPane
          root.getStylesheets.add(foreign)
          var notifications = 0
          for _ <- 0 until 20 do
            val styles = dock.bindStylesheets(root)
            val sub    = dock.themeChanges.subscribe(_ => notifications += 1)
            styles.cancel()
            sub.cancel()
            assertEquals(dock.ownedBindingCount, baseline)
          val styles = dock.bindStylesheets(root)
          val sub    = dock.themeChanges.subscribe(_ => notifications += 1)
          val source = new Label("synthetic source")
          var minted = 0
          val drag = dock.dragSource(source): () =>
            minted += 1
            pane("new")
          dock.setTheme(light)
          assertEquals(notifications, 1)
          val menu = context(dock)
          dock.dispose()
          assert(!menu.isShowing)
          assertEquals(dock.styleBindingCount, 0)
          assertEquals(dock.ownedBindingCount, 0)
          assertEquals(root.getStylesheets.toArray.toVector, Vector(foreign))
          assertEquals(counts.disposed, counts.built)
          Event.fireEvent(source, mouse(MouseEvent.MOUSE_PRESSED, 0, 0))
          Event.fireEvent(source, mouse(MouseEvent.MOUSE_DRAGGED, 20, 20))
          assertEquals(minted, 0)
          styles.cancel(); sub.cancel(); drag.cancel()
          dock.dispose()
          assertEquals(counts.disposed, 4)

  test(
    "scene/window transfer detaches old owners; hide/show recreates float presentation without rebuilding panes"
  ):
    onFx:
      fixture: (dock, first, counts) =>
        val ids = dock.state.panes.map(p => p.id -> dock.nodeOf(p.id).get).toMap
        dock.popOut(dock.state.groups.last.id)
        val originalScene = first.getScene
        originalScene.setRoot(new StackPane)
        val second = new Stage
        second.setScene(new Scene(dock.view, 600, 450))
        second.show()
        try
          val bindings = dock.ownedBindingCount
          val roots    = dock.state.roots
          first.hide()
          assert(
            dock.dragSurfaces.exists(_._1.isDefined),
            "Old owner's hide must not close new owner's floats"
          )
          for _ <- 0 until 3 do
            val menu = context(dock)
            second.hide()
            assert(!menu.isShowing)
            assertEquals(dock.dragSurfaces.count(_._1.isDefined), 0)
            assertEquals(dock.state.roots, roots)
            second.show()
            assertEquals(dock.dragSurfaces.count(_._1.isDefined), 1)
            assertEquals(dock.ownedBindingCount, bindings)
            ids.foreach((id, node) => assert(dock.nodeOf(id).get eq node))
            assertEquals(counts.disposed, 0)
            assertEquals(counts.built, 4)
        finally second.close()

  test("retired floating regions have no input bindings and preserve unrelated styles"):
    onFx:
      sheets: (dark, light, foreign) =>
        fixture: (dock, _, _) =>
          dock.setTheme(dark)
          dock.popOut(dock.state.groups.last.id)
          val (_, floating, _) = dock.dragSurfaces.find(_._1.isDefined).get
          floating.getStylesheets.add(foreign)
          val outer = floating.getScene.getRoot
          outer.getStylesheets.add(foreign)
          dock.setTheme(light)
          assert(floating.getStylesheets.contains(foreign))
          assert(outer.getStylesheets.contains(foreign))
          dock.dockAllBack()
          assertEquals(floating.bindingCount, 0)
          assert(floating.getStylesheets.contains(foreign))
          assert(outer.getStylesheets.contains(foreign))
          val before = dock.state
          Event.fireEvent(
            floating,
            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F6, false, false, false, false)
          )
          assertEquals(dock.state, before)
end PresentationSuite
