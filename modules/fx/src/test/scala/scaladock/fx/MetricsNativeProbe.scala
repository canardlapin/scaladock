package scaladock.fx

import java.nio.file.{Files, Path}
import javafx.animation.PauseTransition
import javafx.application.{Application, Platform}
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.stage.Stage
import javafx.util.Duration
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter

object MetricsNativeProbe:
  @volatile private[fx] var failure: Option[Throwable] = None
  final case class Text(value: String) derives ReadWriter
  val TextPane: PaneType[Text] = PaneType[Text]("metrics.native.synthetic")
  def main(args: Array[String]): Unit =
    Application.launch(classOf[MetricsProbeApp], args*)
    failure.foreach(e => throw IllegalStateException("S2 native probe failed", e))

final class MetricsProbeApp extends Application:
  import MetricsNativeProbe.*
  private var dock: Option[Dock]   = None
  private var owner: Option[Stage] = None
  private var output: Path         = Path.of(".")
  private var phase                = 0
  private var built                = 0
  private var disposed             = 0
  private var identities           = Map.empty[PaneId, javafx.scene.Node]
  private var modelRoots           = Vector.empty[Node]
  private val heights              = Vector(32.0, 28.0, 32.0, 64.0, 28.0, 28.0)

  override def start(stage: Stage): Unit =
    owner = Some(stage)
    try
      require(getParameters.getRaw.size == 1, "Pass a new evidence directory")
      output = Path.of(getParameters.getRaw.get(0)).toAbsolutePath
      require(!Files.exists(output), "Evidence directory must be new")
      Files.createDirectories(output): Unit
      val factories = PaneFactories.empty.register(TextPane): text =>
        built += 1
        new PaneView[Text]:
          val node                     = new Label(text.value)
          def snapshot(): Text         = text
          override def dispose(): Unit = disposed += 1
      def pane(title: String): PaneDef = TextPane(Text(title)).titled(title)
      val value = Dock(
        factories,
        initial = LayoutState.of(row(
          group(pane("Dataset"), pane("Analysis")),
          group(pane("Inspector"), pane("Metadata"))
        ))
      )
      dock = Some(value)
      value.setTheme(DockTheme.Light)
      stage.setScene(new Scene(value.view, 1000, 650))
      stage.setTitle("S2 synthetic presentation fixture")
      stage.show()
      identities = value.state.panes.map(p => p.id -> value.nodeOf(p.id).get).toMap
      value.popOut(value.state.groups.last.id)
      modelRoots = value.state.roots
      schedule()
    catch case error: Throwable => fail(error)

  private def schedule(): Unit =
    val value = dock.get
    if phase == 2 then
      val saved = value.save()
      value.dockAllBack()
      require(value.load(saved).isRight, "Failed to restore floating layout")
    if phase == 4 || phase == 5 then
      val style = if phase == 4 then "-fx-font-size: 42px;" else ""
      value.view.lookupAll(".dock-tab-title").forEach(_.setStyle(style))
    value.setSettings(LayoutSettings(headerPx = heights(phase)))
    val pause = new PauseTransition(Duration.millis(400))
    pause.setOnFinished: _ =>
      try captureAndAdvance()
      catch case error: Throwable => fail(error)
    pause.play()

  private def captureAndAdvance(): Unit =
    val value = dock.get
    value.setSettings(value.requestedSettings)
    require(
      value.state.roots == modelRoots,
      "Metrics changed model roots, selection or application state"
    )
    require(built == 4 && disposed == 0, "Metrics rebuilt or disposed pane views")
    identities.foreach((id, node) => require(value.nodeOf(id).get eq node, "Pane identity changed"))
    val label = Vector(
      "comfortable-32",
      "compact-28",
      "restored-comfortable-32",
      "enlarged-64",
      "css-font-42",
      "css-reset-28"
    )(phase)
    if phase == 4 then require(value.settings.headerPx > 42, "Text preference was clipped")
    else
      require(
        math.abs(value.settings.headerPx - heights(phase)) < 0.01,
        s"Wrong effective metrics: $label / " + value.settings
      )
    val rows = Vector.newBuilder[String]
    for (window, region, root) <- value.dragSurfaces do
      region.layout()
      for g <- root.toVector.flatMap(_.groups) do
        val view = value.groupViewFor(g.id).get
        view.layout()
        val geometry = region.geometry.groups(g.id)
        val header   = view.lookup(".dock-header").asInstanceOf[Region]
        val content  = view.lookup(".dock-content").asInstanceOf[Region]
        require(math.abs(header.getHeight - geometry.header.height) < 0.6, "Header/model mismatch")
        require(
          math.abs(content.getLayoutY - geometry.header.height) < 0.6,
          "Content/model mismatch"
        )
        require(
          math.abs(content.getHeight - geometry.content.height) < 0.6,
          "Content height mismatch"
        )
        require(header.getHeight + 0.6 >= value.settings.headerPx, "Viewport did not settle")
        rows += s"$label\t$window\theader=" + header.getHeight + "\tcontent=" + content.getHeight
      val name = label + (if window.isEmpty then "-main" else "-floating")
      writeImage(region.getScene, output.resolve(name + ".png"))
    val report = Vector(
      "capture=JavaFX scene snapshots after native pulses; no OS window capture",
      "java=" + System.getProperty("java.version"),
      "javafx=" + System.getProperty("javafx.runtime.version"),
      "os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
      "requested=" + value.requestedSettings,
      "effective=" + value.settings,
      "built=" + built,
      "disposedBeforeCleanup=" + disposed
    ) ++ rows.result()
    Files.writeString(output.resolve(label + ".txt"), report.mkString("\n") + "\n"): Unit
    println(
      s"S2_NATIVE_FRAME_OK phase=$label built=$built disposed=$disposed metrics=" + value.settings
    )
    phase += 1
    if phase < heights.length then schedule()
    else
      cleanup()
      require(disposed == 4, "Cleanup did not dispose exactly four owned panes")
      println("S2_NATIVE_PROBE_OK phases=6 captures=12 paneRebuilds=0 ownedWindowsClosed=true")
  end captureAndAdvance

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
    MetricsNativeProbe.failure = Some(error)
    cleanup()
end MetricsProbeApp
