package scaladock.demo

import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import javafx.application.Platform
import javafx.geometry.{Point2D, Rectangle2D}
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.{KeyCode, MouseButton}
import javafx.scene.robot.Robot
import javafx.stage.{Stage, Window}
import scala.jdk.CollectionConverters.*
import scaladock.*
import scaladock.fx.DockTheme

/** The screenshot rig: drives the real demo with real OS input (the JavaFX Robot) and captures the
  * real window with macOS `screencapture`. Nothing is simulated; every frame is the live app.
  *
  * {{{sbt "demo/runMain scaladock.demo.Shots <outDir> [scenario-prefix ...]"}}}
  */
object Shots:

  /** Scenarios captured with the pointer visible. */
  val WithCursor: Set[String] = Set("03", "04", "05", "06", "10", "13")

  private var app: DemoApp.Built = null
  private var stage: Stage       = null
  private var robot: Robot       = null
  private var out: Path          = null

  def main(args: Array[String]): Unit =
    out = Path.of(args.headOption.getOrElse("target/shots")).toAbsolutePath
    Files.createDirectories(out)
    val only = args.drop(1).toSet

    val started = CountDownLatch(1)
    Platform.startup(() => started.countDown())
    started.await()
    Platform.setImplicitExit(false)

    fx:
      app = DemoApp.build()
      robot = Robot()
      stage = Stage()
      stage.setTitle("scaladock demo")
      stage.setScene(Scene(app.shell, 1280, 800))
      stage.setX(60)
      stage.setY(60)
      stage.setAlwaysOnTop(true)
      stage.show()
      stage.toFront()
    val home = fx(robot.getMousePosition)
    settle(900)

    val scenarios = Vector[(String, () => Unit)](
      "01-overview-dark"  -> overview,
      "02-overview-light" -> overviewLight,
      "03-hover-tab"      -> hoverTab,
      "04-drag-split"     -> dragSplit,
      "05-drag-header"    -> dragHeader,
      "06-drag-window"    -> dragWindowEdge,
      "07-minimized"      -> minimized,
      "08-maximized"      -> maximized,
      "09-overflow"       -> overflow,
      "10-divider-drag"   -> dividerDrag,
      "11-floating"       -> floating,
      "12-empty"          -> empty,
      "13-drag-new-empty" -> dragNewIntoEmpty
    )
    try
      scenarios.foreach: (name, run) =>
        if only.isEmpty || only.exists(name.startsWith) then
          reset()
          run()
          capture(name)
          cleanup()
    finally
      fx(robot.mouseMove(home))
      fx(stage.close())
      Platform.exit()
    println(s"shots written to $out")
  end main

  // -- scenarios -------------------------------------------------------------------------------

  private def overview(): Unit =
    focusPane("scan-01")

  private def overviewLight(): Unit =
    fx(app.dock.setTheme(DockTheme.Light))
    focusPane("scan-01")

  private def hoverTab(): Unit =
    focusPane("scan-01")
    moveTo(tabCenter("Problems"))
    settle(300)

  private def dragSplit(): Unit =
    val target = groupContent("scan-02").map(r =>
      Point2D(r.getMaxX - r.getWidth * 0.12, r.getMinY + r.getHeight * 0.5)
    )
    dragTab("Console", target.get)

  private def dragHeader(): Unit =
    // insert between Console and Problems, so the slide-aside of the following tab shows
    val t = tabBounds("Problems").get
    dragTab("Layers", Point2D(t.getMinX + t.getWidth * 0.2, t.getMinY + t.getHeight / 2))

  private def dragWindowEdge(): Unit =
    val r = dockScreenBounds()
    dragTab("scan-02", Point2D(r.getMinX + 20, r.getMinY + r.getHeight * 0.55))

  private def minimized(): Unit =
    fx:
      groupOf("Explorer").foreach(app.dock.minimize)
      groupOf("Console").foreach(app.dock.minimize)
    focusPane("scan-01")
    // hover the Explorer's stripe button in the side rail
    val rail = fx:
      app.shell.lookupAll(".dock-strip-stack").asScala.find(_.isVisible)
        .map(n => n.localToScreen(n.getLayoutBounds))
    rail.foreach(b => glide(fx(robot.getMousePosition), Point2D(b.getCenterX, b.getMinY + 40), 12))
    settle(250)

  private def maximized(): Unit =
    focusPane("scan-01")
    fx(groupOf("scan-01").foreach(app.dock.maximize))

  private def overflow(): Unit =
    fx:
      groupOf("Layers").foreach: g =>
        for (t, i) <- Vector("Histogram", "Metadata", "Atlas", "ROIs", "Colormap").zipWithIndex do
          val _ = app.dock.open(
            DemoApp.Note(DemoApp.NoteState(s"$t panel")).titled(t),
            DockAt.InGroup(g, i + 1)
          )
    focusPane("Metadata")

  private def dividerDrag(): Unit =
    focusPane("scan-01")
    val d     = fx(dividerRects().minBy(_.getMinX)) // the Explorer | centre divider
    val start = Point2D(d.getMinX + d.getWidth / 2, d.getMinY + d.getHeight * 0.4)
    moveTo(start); settle(150)
    fx(robot.mousePress(MouseButton.PRIMARY))
    glide(start, Point2D(start.getX + 90, start.getY), 12)
    settle(250)

  private def floating(): Unit =
    focusPane("scan-01")
    fx(groupOf("Layers").foreach(app.dock.popOut))
    settle(500)
    fx:
      Window.getWindows.asScala.collect { case s: Stage if s ne stage => s }.foreach: s =>
        s.setX(stage.getX + stage.getWidth - 470)
        s.setY(stage.getY + 200)
        s.setWidth(420)
        s.setHeight(460)
        s.setAlwaysOnTop(true)
        s.toFront()
    settle(500)

  private def empty(): Unit =
    fx(app.dock.update(_ => LayoutState.empty))

  /** The palette chip fabricates a viewer; drag it into an empty window. */
  private def dragNewIntoEmpty(): Unit =
    empty()
    settle(200)
    val chip = fx:
      app.shell.lookupAll(".label").asScala.collectFirst {
        case l: Label if l.getText == "New viewer" && l.isVisible && l.getScene != null => l
      }.map(l => l.localToScreen(l.getLayoutBounds))
    val b     = chip.getOrElse(sys.error("no palette chip"))
    val start = Point2D(b.getMinX + b.getWidth / 2, b.getMinY + b.getHeight / 2)
    val r     = dockScreenBounds()
    moveTo(start); settle(150)
    fx(robot.mousePress(MouseButton.PRIMARY))
    settle(80)
    glide(start, Point2D(r.getMinX + r.getWidth * 0.55, r.getMinY + r.getHeight * 0.6), 24)
    settle(350)

  // -- driving ----------------------------------------------------------------------------------

  /** Press a tab, glide to the target with real pointer motion, and hold for the capture. */
  private def dragTab(title: String, target: Point2D): Unit =
    focusPane("scan-01")
    val start = tabCenter(title)
    moveTo(start); settle(150)
    fx(robot.mousePress(MouseButton.PRIMARY))
    settle(80)
    glide(start, target, 24)
    settle(350)

  private def glide(from: Point2D, to: Point2D, steps: Int): Unit =
    for i <- 1 to steps do
      val t = i.toDouble / steps
      moveTo(Point2D(from.getX + (to.getX - from.getX) * t, from.getY + (to.getY - from.getY) * t))
      Thread.sleep(16)

  private def cleanup(): Unit =
    // cancel any drag before letting go so no scenario mutates the next one
    fx(robot.keyPress(KeyCode.ESCAPE)); fx(robot.keyRelease(KeyCode.ESCAPE))
    fx(robot.mouseRelease(MouseButton.PRIMARY))
    settle(120)

  private def reset(): Unit =
    fx(stage.requestFocus()) // capture the main window active, as a user would see it
    fx:
      app.dock.dockAllBack()
      app.dock.update(_ => app.layout)
      app.dock.setTheme(DockTheme.Dark)
    // leave the window the way a hand does — gliding out — so the platform reports the exit and
    // no group keeps a stale :hover (a teleporting robot pointer skips the exit event)
    glide(fx(robot.getMousePosition), parkingSpot(), 10)
    settle(250)

  private def focusPane(title: String): Unit =
    fx(app.dock.state.panes.find(_.title == title).foreach(p => app.dock.focus(p.id)))
    settle(200)

  private def moveTo(p: Point2D): Unit = fx(robot.mouseMove(p))

  private def parkingSpot(): Point2D =
    fx(Point2D(stage.getX + stage.getWidth + 40, stage.getY + 40))

  // -- lookups (all on the FX thread) -----------------------------------------------------------

  private def groupOf(title: String): Option[NodeId] =
    app.dock.state.groups.find(_.tabs.exists(_.title == title)).map(_.id)

  private def allTabs: Vector[javafx.scene.Node] =
    Window.getWindows.asScala.toVector
      .flatMap(w => Option(w.getScene).map(_.getRoot))
      .flatMap(_.lookupAll(".dock-tab").asScala)

  private def tabBounds(title: String): Option[javafx.geometry.Bounds] = fx:
    allTabs
      .find(_.lookupAll(".dock-tab-title").asScala.exists {
        case l: Label => l.getText == title
        case _        => false
      })
      .map(n => n.localToScreen(n.getLayoutBounds))

  private def tabCenter(title: String): Point2D =
    val b = tabBounds(title).getOrElse(sys.error(s"no tab '$title'"))
    Point2D(b.getMinX + b.getWidth * 0.4, b.getMinY + b.getHeight / 2)

  private def groupContent(title: String): Option[javafx.geometry.Bounds] = fx:
    app.shell
      .lookupAll(".dock-group")
      .asScala
      .find(g =>
        g.isVisible && g.lookupAll(".dock-tab-title").asScala.exists {
          case l: Label => l.getText == title
          case _        => false
        }
      )
      .flatMap(g => Option(g.lookup(".dock-content")))
      .map(c => c.localToScreen(c.getLayoutBounds))

  private def dividerRects(): Vector[javafx.geometry.Bounds] =
    app.shell.lookupAll(".dock-divider").asScala.toVector.filter(_.isVisible).map(n =>
      n.localToScreen(n.getLayoutBounds)
    )

  private def dockScreenBounds(): javafx.geometry.Bounds = fx:
    val v = app.dock.view
    v.localToScreen(v.getLayoutBounds)

  // -- capture ----------------------------------------------------------------------------------

  private def capture(name: String): Unit =
    // only ever the main window's own rectangle: never leak whatever else is on the desktop
    val r: Rectangle2D = fx(Rectangle2D(stage.getX, stage.getY, stage.getWidth, stage.getHeight))
    val file           = out.resolve(s"$name.png").toString
    val rect = s"${r.getMinX.round},${r.getMinY.round},${r.getWidth.round},${r.getHeight.round}"
    // gesture scenarios include the real pointer: the cursor is part of the feedback
    val cursor = if Shots.WithCursor.exists(name.startsWith) then Seq("-C") else Seq.empty
    val cmd    = Seq("screencapture", "-x") ++ cursor ++ Seq("-R", rect, file)
    val p      = new ProcessBuilder(cmd*).inheritIO().start()
    p.waitFor(20, TimeUnit.SECONDS): Unit
    println(s"captured $name")

  // -- threading ----------------------------------------------------------------------------------

  private def fx[A](body: => A): A =
    if Platform.isFxApplicationThread then body
    else
      val f = CompletableFuture[A]()
      Platform.runLater: () =>
        try f.complete(body): Unit
        catch case t: Throwable => f.completeExceptionally(t): Unit
      f.get(20, TimeUnit.SECONDS)

  /** Let pulses, CSS and layout catch up before a capture. */
  private def settle(ms: Long): Unit =
    Thread.sleep(ms)
    fx(()) // drain the FX queue
end Shots
