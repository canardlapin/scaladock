package scaladock.fx

import java.nio.file.{Files, Path}
import javafx.animation.PauseTransition
import javafx.application.{Application, Platform}
import javafx.event.Event
import javafx.scene.{Parent, Scene}
import javafx.scene.control.{ContextMenu, Label, Tooltip}
import javafx.scene.input.{ContextMenuEvent, PickResult}
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.stage.{Stage, Window}
import javafx.util.Duration
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter

object PresentationNativeProbe:
  @volatile private[fx] var failure: Option[Throwable] = None
  final case class Text(value: String) derives ReadWriter
  val TextPane: PaneType[Text] = PaneType[Text]("s3.native.synthetic")
  def main(args: Array[String]): Unit =
    Application.launch(classOf[PresentationProbeApp], args*)
    failure.foreach(e => throw IllegalStateException("S3 native probe failed", e))

final class PresentationProbeApp extends Application:
  import PresentationNativeProbe.*
  private var dock: Option[Dock]        = None
  private var owner: Option[Stage]      = None
  private var output                    = Path.of(".")
  private var phase                     = 0
  private var darkSheet                 = ""
  private var lightSheet                = ""
  private var foreignSheet              = ""
  private var built                     = 0
  private var disposed                  = 0
  private var menu: Option[ContextMenu] = None
  private var tip: Option[Tooltip]      = None
  private var identities                = Map.empty[PaneId, javafx.scene.Node]
  private val heights                   = Vector(32.0, 28.0, 28.0, 32.0)
  private val names = Vector(
    "dark-comfortable",
    "light-compact",
    "dark-compact-floating",
    "light-comfortable-restored"
  )

  override def start(stage: Stage): Unit =
    owner = Some(stage)
    try
      require(getParameters.getRaw.size == 1, "Pass a new evidence directory")
      output = Path.of(getParameters.getRaw.get(0)).toAbsolutePath
      require(!Files.exists(output), "Output must be new")
      Files.createDirectories(output): Unit
      val dark    = output.resolve("light-name-dark-palette.css")
      val light   = output.resolve("dark-name-light-palette.css")
      val foreign = output.resolve("foreign.css")
      Files.writeString(
        dark,
        ".dock { -dock-base:#18202f; -dock-surface:#26344a; -dock-surface-raised:#30405a; -dock-text:#edf1f7; -dock-text-strong:#edf1f7; } .dock-content > .label { -fx-text-fill:-dock-text; }"
      ): Unit
      Files.writeString(
        light,
        ".dock { -dock-base:#ffffff; -dock-surface:#f2f3f5; -dock-surface-raised:#f5eee2; -dock-text:#1b293c; -dock-text-strong:#1b293c; } .dock-content > .label { -fx-text-fill:-dock-text; }"
      ): Unit
      Files.writeString(foreign, ".host-marker { -fx-opacity:1; }"): Unit
      darkSheet = dark.toUri.toString
      lightSheet = light.toUri.toString
      foreignSheet = foreign.toUri.toString
      val factories = PaneFactories.empty.register(TextPane): text =>
        built += 1
        new PaneView[Text]:
          val node                     = new Label(text.value)
          def snapshot(): Text         = text
          override def dispose(): Unit = disposed += 1
      def pane(s: String): PaneDef = TextPane(Text(s)).titled(s)
      val value = Dock(
        factories,
        initial = LayoutState.of(row(
          group(pane("Datasets"), pane("Analysis")),
          group(pane("Inspector"), pane("Metadata"))
        ))
      )
      dock = Some(value)
      identities = value.state.panes.map(p => p.id -> value.nodeOf(p.id).get).toMap
      value.view.getStylesheets.add(foreignSheet)
      stage.setScene(new Scene(value.view, 850, 550))
      stage.setTitle("S3 synthetic popup fixture")
      stage.show()
      schedule()
    catch case e: Throwable => fail(e)
    end try
  end start

  private def later(body: => Unit): Unit =
    val pause = new PauseTransition(Duration.millis(350))
    pause.setOnFinished: _ =>
      try body
      catch case e: Throwable => fail(e)
    pause.play()

  private def schedule(): Unit =
    val value = dock.get
    if phase == 2 then value.popOut(value.state.groups.last.id)
    if phase == 3 then
      val saved = value.save()
      value.dockAllBack()
      require(value.load(saved).isRight, "Restore failed")
    val dark = phase % 2 == 0
    value.setTheme(DockTheme.CustomWithScheme(if dark then darkSheet else lightSheet, dark))
    value.setSettings(LayoutSettings(headerPx = heights(phase)))
    require(menu.forall(!_.isShowing) && tip.forall(!_.isShowing), "Old overlays remained visible")
    require(value.styleBindingCount == 0, "Old popup style leases survived dismissal")
    later(openOverlays())

  private def openOverlays(): Unit =
    val value = dock.get
    val tab   = value.view.lookup(".dock-tab")
    val p     = tab.localToScreen(6, 6)
    Event.fireEvent(
      tab,
      new ContextMenuEvent(
        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
        6,
        6,
        p.getX,
        p.getY,
        false,
        new PickResult(tab, 6, 6)
      )
    )
    menu = Window.getWindows.toArray.collectFirst:
      case m: ContextMenu if m.getOwnerNode eq tab => m
    require(menu.exists(_.isShowing), "Owned context menu did not open")
    val group  = value.groupViewFor(value.state.root.get.groups.head.id).get
    val button = group.lookup(".dock-header-button.close")
    tip = group.tipOf(button)
    val point = button.localToScreen(0, 0)
    tip.get.show(button, point.getX, point.getY + 45)
    later(capture())

  private def paint(root: Parent, selector: String, expected: Color): Unit =
    root.applyCss()
    root.layout()
    val fills = root.lookupAll(selector).toArray.toVector.collect:
      case r: Region if r.getBackground != null =>
        import scala.jdk.CollectionConverters.*
        r.getBackground.getFills.asScala.toVector.map(_.getFill)
    .flatten
    require(fills.contains(expected), s"$selector expected $expected / $fills")

  private def capture(): Unit =
    val value = dock.get
    val label = names(phase)
    require(
      menu.exists(_.isShowing) && tip.exists(_.isShowing),
      "Owned overlay unexpectedly closed"
    )
    val expected = Color.web(if phase % 2 == 0 then "#30405a" else "#f5eee2")
    paint(menu.get.getScene.getRoot, ".context-menu", expected)
    paint(tip.get.getScene.getRoot, ".tooltip", expected)
    require(value.styleBindingCount == 2, "Popup bindings duplicated or missing")
    require(value.view.getStylesheets.contains(foreignSheet), "Foreign owner sheet lost")
    require(value.view.getStylesheets.toArray.distinct.length == value.view.getStylesheets.size)
    require(built == 4 && disposed == 0, "Presentation rebuilt or disposed application panes")
    identities.foreach((id, n) => require(value.nodeOf(id).get eq n, "Pane identity changed"))
    writeImage(owner.get.getScene, output.resolve(label + "-main.png"))
    writeImage(menu.get.getScene, output.resolve(label + "-menu.png"))
    writeImage(tip.get.getScene, output.resolve(label + "-tooltip.png"))
    for (_, region, _) <- value.dragSurfaces.filter(_._1.isDefined) do
      writeImage(region.getScene, output.resolve(label + "-floating.png"))
    val report = Vector(
      "capture=Owned JavaFX scene snapshots; no OS screen capture",
      "java=" + System.getProperty("java.version"),
      "javafx=" + System.getProperty("javafx.runtime.version"),
      "os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
      "expectedRaised=" + expected,
      "metrics=" + value.settings,
      "livePopupStyleRoots=" + value.styleBindingCount,
      "ownedBindingScopes=" + value.ownedBindingCount,
      "built=" + built,
      "disposedBeforeCleanup=" + disposed
    )
    Files.writeString(output.resolve(label + ".txt"), report.mkString("\n") + "\n"): Unit
    println(
      s"S3_NATIVE_FRAME_OK phase=$label color=$expected styleRoots=" + value.styleBindingCount
    )
    phase += 1
    if phase < names.length then schedule()
    else
      cleanup()
      require(
        value.styleBindingCount == 0 && value.ownedBindingCount == 0,
        "Presentation scopes leaked"
      )
      require(disposed == 4, "Pane cleanup count mismatch")
      require(!menu.get.isShowing && !tip.get.isShowing, "Overlay survived disposal")
      println(
        "S3_NATIVE_PROBE_OK phases=4 captures=14 bindingsAfterDispose=0 ownedWindowsClosed=true"
      )
  end capture

  private def writeImage(scene: Scene, file: Path): Unit =
    val image = scene.snapshot(null)
    val png = new java.awt.image.BufferedImage(
      image.getWidth.toInt,
      image.getHeight.toInt,
      java.awt.image.BufferedImage.TYPE_INT_ARGB
    )
    for y <- 0 until png.getHeight; x <- 0 until png.getWidth do
      png.setRGB(x, y, image.getPixelReader.getArgb(x, y))
    require(javax.imageio.ImageIO.write(png, "png", file.toFile))

  private def cleanup(): Unit =
    dock.foreach(_.dispose())
    owner.foreach(_.close())
    Platform.exit()

  private def fail(error: Throwable): Unit =
    PresentationNativeProbe.failure = Some(error)
    cleanup()
end PresentationProbeApp
