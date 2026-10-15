package scaladock.fx

import javafx.event.Event
import javafx.scene.{Cursor, Scene}
import javafx.scene.control.Label
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.scene.layout.Region
import javafx.stage.{Stage, Window}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class MetricsSuite extends FunSuite:
  final case class Text(value: String) derives ReadWriter
  private val TextPane                     = PaneType[Text]("test.metrics.text")
  private def pane(title: String): PaneDef = TextPane(Text(title)).titled(title)

  private final class Counts:
    var built    = 0
    var disposed = 0

  private def staged(counts: Counts, state: LayoutState): (Dock, Stage) =
    val factories = PaneFactories.empty.register(TextPane): text =>
      counts.built += 1
      new PaneView[Text]:
        private val label            = new Label(text.value)
        def node                     = label
        def snapshot(): Text         = Text(label.getText)
        override def dispose(): Unit = counts.disposed += 1
    val dock = Dock(factories, initial = state)
    dock.setTheme(DockTheme.Light)
    val stage = new Stage
    stage.setScene(new Scene(dock.view, 1100, 700))
    stage.show()
    dock.view.applyCss()
    dock.view.layout()
    (dock, stage)

  private def withDock(state: LayoutState)(body: (Dock, Stage, Counts) => Unit): Unit =
    val counts        = new Counts
    val (dock, stage) = staged(counts, state)
    try body(dock, stage, counts)
    finally
      dock.dispose()
      stage.close()

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

  private def fire(
      node: javafx.scene.Node,
      kind: javafx.event.EventType[MouseEvent],
      x: Double,
      y: Double
  ): Unit =
    Event.fireEvent(node, mouse(kind, x, y))

  private def tab(dock: Dock, group: NodeId): javafx.scene.Node =
    dock.groupViewFor(group).get.lookup(".dock-tab")

  private def ghosts: Int =
    Window.getWindows.toArray.count:
      case w: Window => w.getScene != null && w.getScene.lookup(".dock-ghost") != null
      case _         => false

  private def capture(dock: Dock, scene: Scene, name: String): Unit =
    sys.props.get("scaladock.metrics.captureDir").foreach: directory =>
      val output = java.nio.file.Path.of(directory)
      java.nio.file.Files.createDirectories(output): Unit
      val image = scene.snapshot(null)
      val png = new java.awt.image.BufferedImage(
        image.getWidth.toInt,
        image.getHeight.toInt,
        java.awt.image.BufferedImage.TYPE_INT_ARGB
      )
      for y <- 0 until png.getHeight; x <- 0 until png.getWidth do
        png.setRGB(x, y, image.getPixelReader.getArgb(x, y))
      assert(javax.imageio.ImageIO.write(png, "png", output.resolve(name + ".png").toFile))
      val report = Vector(
        "capture=JavaFX scene snapshot; OS decorations not captured",
        "java=" + System.getProperty("java.version"),
        "javafx=" + System.getProperty("javafx.runtime.version"),
        "os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
        "requested=" + dock.requestedSettings,
        "effective=" + dock.settings
      ).mkString("\n") + "\n"
      java.nio.file.Files.writeString(output.resolve(name + ".txt"), report): Unit

  private def checkBounds(dock: Dock): Unit =
    for (_, region, root) <- dock.dragSurfaces do
      region.layout()
      val areas = sizing.dropAreas(
        root,
        region.geometry,
        Rect(0, 0, region.getWidth, region.getHeight),
        None,
        dock.settings
      )
      for group <- root.toVector.flatMap(_.groups) if !dock.state.minimized(group.id) do
        val view = dock.groupViewFor(group.id).get
        view.layout()
        val geometry = region.geometry.groups(group.id)
        val header   = view.lookup(".dock-header").asInstanceOf[Region]
        val content  = view.lookup(".dock-content").asInstanceOf[Region]
        assertEqualsDouble(header.getHeight, geometry.header.height, 0.6)
        assertEqualsDouble(content.getLayoutY, geometry.header.height, 0.6)
        assertEqualsDouble(content.getHeight, geometry.content.height, 0.6)
        if geometry.header.height > 0 then
          val p = Point(
            geometry.header.x + geometry.header.width / 2,
            geometry.header.bottom - 0.5
          )
          val headers = areas.filter(_.zone == DropZone.HeaderOf(group.id))
          assertEquals(headers.map(_.hover), Vector(geometry.header))
          assertEquals(sizing.hitTest(headers, p).map(_.zone), Some(DropZone.HeaderOf(group.id)))
          // Tiny windows may give a smaller competing window-edge zone priority.
          assert(sizing.hitTest(areas, p).exists(_.hover.contains(p)))

  test("32 -> 28 -> 32 -> enlarged metrics preserve identity, selected IDs and application state"):
    onFx:
      withDock(LayoutState.of(row(group(pane("a"), pane("b")), pane("c")))):
        (dock, stage, counts) =>
          val identities = dock.state.panes.map(p => p.id -> dock.nodeOf(p.id).get).toMap
          identities.values.head.asInstanceOf[Label].setText("unsaved application state")
          val before = dock.state
          val saved  = dock.save()
          val events = collection.mutable.Buffer.empty[DockEvent]
          val sub    = dock.events.subscribe(e => events += e)
          try
            for (height, index) <- Vector(32.0, 28.0, 32.0, 64.0).zipWithIndex do
              dock.setSettings(LayoutSettings(headerPx = height))
              assertEqualsDouble(dock.settings.headerPx, height, 0.01)
              checkBounds(dock)
              capture(dock, stage.getScene, "density-" + index + "-" + height.toInt)
              assertEquals(dock.state, before)
              assertEquals(
                dock.save(),
                saved,
                "presentation must not alter persisted schema or values"
              )
              identities.foreach((id, n) => assert(dock.nodeOf(id).get eq n))
              assertEquals(counts.built, 3)
              assertEquals(counts.disposed, 0)
            assert(events.forall(e => !e.isInstanceOf[DockEvent.LayoutChanged]))
          finally sub.cancel()

  test("existing, newly created and restored floating renderers share current effective metrics"):
    onFx:
      withDock(LayoutState.of(row(
        group(pane("main"), pane("main2")),
        group(pane("float"), pane("float2"))
      ))): (dock, _, counts) =>
        val ids      = dock.state.panes.map(p => p.id -> dock.nodeOf(p.id).get).toMap
        val floating = dock.state.groupOf(dock.state.panes.find(_.title == "float").get.id).get.id
        dock.setSettings(LayoutSettings(headerPx = 28))
        dock.popOut(floating)
        for height <- Vector(28.0, 32.0, 64.0) do
          dock.setSettings(LayoutSettings(headerPx = height))
          checkBounds(dock)
          val scene = ids(dock.state.findGroup(floating).get.active).getScene
          capture(dock, scene, "floating-" + height.toInt)
          assertEquals(dock.state.floating.length, 1)
        val saved = dock.save()
        dock.dockAllBack()
        dock.setSettings(LayoutSettings(headerPx = 28))
        assertEquals(dock.load(saved), Right(()))
        for (height, index) <- Vector(32.0, 28.0, 32.0, 64.0).zipWithIndex do
          dock.setSettings(LayoutSettings(headerPx = height))
          checkBounds(dock)
          assertEqualsDouble(dock.settings.headerPx, height, 0.01)
          val scene = ids(dock.state.findGroup(floating).get.active).getScene
          capture(dock, scene, "floating-restored-" + index + "-" + height.toInt)
        ids.foreach((id, n) => assert(dock.nodeOf(id).get eq n))
        assertEquals(counts.built, 4)
        assertEquals(counts.disposed, 0)

  test("minimized horizontal and vertical strips use live metrics without changing stored sizes"):
    onFx:
      withDock(LayoutState.of(row(column(pane("main"), pane("bottom")), pane("side")))):
        (dock, _, _) =>
          val bottom = dock.state.groupOf(dock.state.panes.find(_.title == "bottom").get.id).get.id
          val side   = dock.state.groupOf(dock.state.panes.find(_.title == "side").get.id).get.id
          dock.minimize(bottom)
          dock.minimize(side)
          val before = dock.state
          val region = dock.view.asInstanceOf[DockRegion]
          for height <- Vector(32.0, 28.0, 64.0, 32.0) do
            dock.setSettings(LayoutSettings(headerPx = height))
            assertEqualsDouble(region.geometry.groups(bottom).bounds.height, height, 0.6)
            assertEqualsDouble(region.geometry.groups(side).bounds.width, height, 0.6)
            assertEquals(region.geometry.groups(bottom).content.height, 0.0)
            assertEquals(region.geometry.groups(side).content.height, 0.0)
            assertEquals(dock.state, before)

  test("CSS preferred text and control minima raise density globally and can shrink again"):
    onFx:
      withDock(LayoutState.of(row(group(pane("a"), pane("b")), group(pane("c"), pane("d"))))):
        (dock, _, counts) =>
          val floating = dock.state.groups.last.id
          dock.popOut(floating)
          val first  = dock.groupViewFor(dock.state.groups.head.id).get
          val title  = first.lookup(".dock-tab-title").asInstanceOf[Label]
          val button = first.lookup(".dock-header-button").asInstanceOf[Region]
          title.setStyle("-fx-font-size: 42px;")
          dock.setSettings(LayoutSettings(headerPx = 28))
          checkBounds(dock)
          assertEquals(title.getFont.getSize, 42.0)
          assert(dock.settings.headerPx > 28 && dock.settings.headerPx >= title.prefHeight(-1))
          assert(title.getHeight + 0.5 >= title.prefHeight(-1))
          capture(dock, dock.view.getScene, "css-font-42")
          button.setStyle("-fx-min-height: 58px; -fx-pref-height: 58px; -fx-max-height: 58px;")
          dock.setSettings(dock.requestedSettings)
          assert(dock.settings.headerPx >= 58)
          assert(dock.settings.headerPx >= title.prefHeight(-1))
          assertEquals(dock.requestedSettings.headerPx, 28.0)
          checkBounds(dock)
          assert(button.getHeight >= 58)
          capture(dock, dock.view.getScene, "css-control-58")
          title.setStyle("")
          button.setStyle("")
          dock.view.applyCss()
          dock.view.requestLayout()
          dock.view.layout() // CSS enlargement also settles without an explicit metrics call.
          assertEqualsDouble(dock.settings.headerPx, 28.0, 0.01)
          dock.setSettings(dock.requestedSettings)
          checkBounds(dock)
          capture(dock, dock.view.getScene, "css-reset-28")
          assertEquals(counts.built, 4)
          assertEquals(counts.disposed, 0)

  test(
    "metrics update cancels both armed and active tab gestures; the next drop uses enlarged headers"
  ):
    onFx:
      withDock(LayoutState.of(row(group(pane("a"), pane("b")), pane("target")))): (dock, _, _) =>
        val source = dock.state.groups.head.id
        val target = dock.state.groups.last.id
        val handle = tab(dock, source)
        val paneId = dock.state.findGroup(source).get.tabs.head.id
        val p      = handle.localToScreen(5, 5)
        fire(handle, MouseEvent.MOUSE_PRESSED, p.getX, p.getY)
        val pressedState = dock.state
        dock.setSettings(LayoutSettings(headerPx = 28))
        fire(handle, MouseEvent.MOUSE_DRAGGED, p.getX + 20, p.getY + 20)
        fire(handle, MouseEvent.MOUSE_RELEASED, p.getX + 20, p.getY + 20)
        assertEquals(dock.state, pressedState)
        assertEquals(ghosts, 0)
        fire(handle, MouseEvent.MOUSE_PRESSED, p.getX, p.getY)
        fire(handle, MouseEvent.MOUSE_DRAGGED, p.getX + 20, p.getY + 20)
        val targetView = dock.groupViewFor(target).get
        val point      = targetView.localToScreen(targetView.getWidth / 2, 12)
        fire(handle, MouseEvent.MOUSE_DRAGGED, point.getX, point.getY)
        assertEquals(ghosts, 1)
        assert(dock.view.lookup(".dock-drop-indicator").isVisible)
        val activeState = dock.state
        dock.setSettings(LayoutSettings(headerPx = 64))
        assertEquals(ghosts, 0)
        assert(!dock.view.lookup(".dock-drop-indicator").isVisible)
        assert(!handle.getPseudoClassStates.contains(pseudo.Dragging))
        assert(!dock.view.getPseudoClassStates.contains(pseudo.Dragging))
        dock.state.groups.foreach(g =>
          assert(!dock.groupViewFor(g.id).get.lookup(".dock-content").isMouseTransparent)
        )
        dock.groupViewFor(source).get.lookupAll(".dock-tab").forEach(n =>
          assertEquals(n.getTranslateX, 0.0)
        )
        fire(handle, MouseEvent.MOUSE_RELEASED, point.getX, point.getY)
        assertEquals(dock.state, activeState)
        val next =
          targetView.localToScreen(targetView.getWidth / 2, 48) // content at 28px; header at 64px
        fire(handle, MouseEvent.MOUSE_PRESSED, p.getX, p.getY)
        fire(handle, MouseEvent.MOUSE_DRAGGED, p.getX + 20, p.getY + 20)
        fire(handle, MouseEvent.MOUSE_DRAGGED, next.getX, next.getY)
        fire(handle, MouseEvent.MOUSE_RELEASED, next.getX, next.getY)
        assertEquals(dock.state.groupOf(paneId).map(_.id), Some(target))
        assertEquals(ghosts, 0)

  test("metrics invalidate pending and active external drag sources"):
    onFx:
      withDock(LayoutState.of(group(pane("home")))): (dock, _, _) =>
        val handle = new Label("synthetic source")
        var minted = 0
        val sub = dock.dragSource(handle): () =>
          minted += 1
          pane(s"minted-$minted")
        try
          fire(handle, MouseEvent.MOUSE_PRESSED, 2000, 2000)
          dock.setSettings(LayoutSettings(headerPx = 28))
          fire(handle, MouseEvent.MOUSE_DRAGGED, 2020, 2020)
          fire(handle, MouseEvent.MOUSE_RELEASED, 2020, 2020)
          assertEquals(minted, 0)
          fire(handle, MouseEvent.MOUSE_PRESSED, 2000, 2000)
          fire(handle, MouseEvent.MOUSE_DRAGGED, 2020, 2020)
          assertEquals(minted, 1)
          dock.setSettings(LayoutSettings(headerPx = 32))
          val target = dock.view.localToScreen(500, 300)
          fire(handle, MouseEvent.MOUSE_RELEASED, target.getX, target.getY)
          assertEquals(dock.state.panes.length, 1)
          assertEquals(ghosts, 0)
          fire(handle, MouseEvent.MOUSE_PRESSED, 2000, 2000)
          fire(handle, MouseEvent.MOUSE_DRAGGED, 2020, 2020)
          fire(handle, MouseEvent.MOUSE_DRAGGED, target.getX, target.getY)
          fire(handle, MouseEvent.MOUSE_RELEASED, target.getX, target.getY)
          assertEquals(minted, 2)
          assertEquals(dock.state.panes.length, 2)
        finally sub.cancel()

  test(
    "metrics cancel divider preview and cursor without commit; next drag honors enlarged minima"
  ):
    onFx:
      withDock(LayoutState.of(column(pane("top"), pane("bottom")))): (dock, stage, _) =>
        val region = dock.view.asInstanceOf[DockRegion]
        val divider = region.getChildrenUnmodifiable.toArray.collectFirst { case d: DividerView =>
          d
        }.get
        stage.getScene.setCursor(Cursor.CROSSHAIR)
        val before = dock.state
        fire(divider, MouseEvent.MOUSE_PRESSED, 200, 300)
        fire(divider, MouseEvent.MOUSE_DRAGGED, 200, 200)
        assertEquals(stage.getScene.getCursor, Cursor.V_RESIZE)
        dock.setSettings(LayoutSettings(headerPx = 64))
        assertEquals(stage.getScene.getCursor, Cursor.CROSSHAIR)
        assert(!divider.getPseudoClassStates.contains(pseudo.Dragging))
        fire(divider, MouseEvent.MOUSE_RELEASED, 200, 100)
        assertEquals(dock.state, before)
        fire(divider, MouseEvent.MOUSE_PRESSED, 200, 300)
        fire(divider, MouseEvent.MOUSE_DRAGGED, 200, -10000)
        fire(divider, MouseEvent.MOUSE_RELEASED, 200, -10000)
        region.layout()
        val top = dock.state.groups.head.id
        assertEqualsDouble(
          region.geometry.groups(top).bounds.height,
          dock.settings.headerPx + dock.settings.defaultMinPanePx,
          0.6
        )

  test("zero available FX space clips chrome and content without leaking child minima"):
    onFx:
      withDock(LayoutState.of(row(pane("a"), pane("b")))): (dock, _, _) =>
        dock.setSettings(LayoutSettings(0, 0, 0, 0))
        dock.view.resize(0, 0)
        dock.view.layout()
        dock.state.groups.foreach: g =>
          val view = dock.groupViewFor(g.id).get
          view.layout()
          val geometry = dock.view.asInstanceOf[DockRegion].geometry.groups(g.id)
          assertEquals(geometry.bounds, Rect.zero)
          for selector <- Vector(".dock-header", ".dock-content") do
            val region = view.lookup(selector).asInstanceOf[Region]
            val clip   = region.getClip.asInstanceOf[javafx.scene.shape.Rectangle]
            assertEquals(clip.getWidth, 0.0)
            assertEquals(clip.getHeight, 0.0)

  test("metrics mutation requires JavaFX and null or invalid values do not change live settings"):
    val counts        = new Counts
    val (dock, stage) = onFx(staged(counts, LayoutState.of(group(pane("one")))))
    try
      val _ = intercept[IllegalArgumentException](dock.setSettings(LayoutSettings(headerPx = 28)))
      onFx:
        val before = dock.settings
        val _      = intercept[IllegalArgumentException](dock.setSettings(null))
        val _ = intercept[IllegalArgumentException](dock.setSettings(LayoutSettings(headerPx =
          Double.NaN
        )))
        assertEquals(dock.settings, before)
    finally
      onFx:
        dock.dispose()
        stage.close()
end MetricsSuite
