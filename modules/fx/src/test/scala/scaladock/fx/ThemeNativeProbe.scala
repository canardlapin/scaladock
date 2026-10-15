package scaladock.fx

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}
import java.util.Properties
import javax.imageio.ImageIO
import javafx.animation.PauseTransition
import javafx.application.{Application, Platform}
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.image.WritableImage
import javafx.scene.robot.Robot
import javafx.stage.Stage
import javafx.util.Duration
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter

/** Synthetic native scheme proof; screen capture is restricted to a focused owned stage. */
object ThemeNativeProbe:
  @volatile private[fx] var failure: Option[Throwable] = None
  final case class Text(value: String) derives ReadWriter
  val TextPane: PaneType[Text] = PaneType[Text]("test.native.scheme")
  def main(args: Array[String]): Unit =
    Application.launch(classOf[ThemeProbeApp], args*)
    failure.foreach(error => throw IllegalStateException("Native scheme probe failed", error))

final class ThemeProbeApp extends Application:
  import ThemeNativeProbe.*
  private var dock: Option[Dock]         = None
  private var mainStage: Option[Stage]   = None
  private var output: Path               = Path.of(".")
  private var target: Option[PaneId]     = None
  private var saved: Option[ujson.Value] = None
  private var darkSheet                  = ""
  private var lightSheet                 = ""
  private var phase                      = 0
  private var sceneOnly                  = false

  override def start(stage: Stage): Unit =
    mainStage = Some(stage)
    try
      require(
        getParameters.getRaw.size >= 1 && getParameters.getRaw.size <= 2,
        "Pass a new capture directory and optional --scene-only"
      )
      sceneOnly = getParameters.getRaw.size == 2 && getParameters.getRaw.get(1) == "--scene-only"
      require(getParameters.getRaw.size == 1 || sceneOnly, "Unknown probe option")
      output = Path.of(getParameters.getRaw.get(0)).toAbsolutePath
      require(!Files.exists(output), s"Output exists: $output")
      Files.createDirectories(output): Unit
      val dark  = output.resolve("light-name-dark-palette.css")
      val light = output.resolve("dark-name-light-palette.css")
      Files.writeString(
        dark,
        ".dock { -dock-base: #151923; -dock-surface: #252c3a; -dock-surface-raised: #252c3a; -dock-text: #edf0f5; -dock-text-strong: #edf0f5; } .dock .label { -fx-text-fill: -dock-text; }"
      ): Unit
      Files.writeString(
        light,
        ".dock { -dock-base: #ffffff; -dock-surface: #f1f3f7; -dock-surface-raised: #f1f3f7; -dock-text: #202530; -dock-text-strong: #202530; } .dock .label { -fx-text-fill: -dock-text; }"
      ): Unit
      darkSheet = dark.toUri.toString
      lightSheet = light.toUri.toString
      val factories = PaneFactories.empty.register(TextPane): text =>
        new PaneView[Text]:
          val node             = new Label(text.value)
          def snapshot(): Text = text
      val value = Dock(
        factories,
        initial = LayoutState.of(row(
          TextPane(Text("Synthetic main pane")).titled("Main"),
          TextPane(Text("Synthetic floating pane")).titled("S1 custom theme")
        ))
      )
      dock = Some(value)
      stage.setTitle("ScalaDock S1 synthetic main fixture")
      stage.setScene(new Scene(value.view, 720, 460))
      stage.show()
      val pane = value.state.panes.find(_.title == "S1 custom theme").get
      target = Some(pane.id)
      value.setTheme(DockTheme.CustomWithScheme(darkSheet, dark = true))
      value.popOut(value.state.groupOf(pane.id).get.id)
      saved = Some(value.save())
      schedule()
    catch case error: Throwable => fail(error)
    end try
  end start

  private def floating: Stage =
    dock.get.nodeOf(target.get).get.getScene.getWindow.asInstanceOf[Stage]

  private def schedule(): Unit =
    floating.toFront()
    floating.requestFocus()
    val pause = new PauseTransition(Duration.millis(if sceneOnly then 250 else 1500))
    pause.setOnFinished: _ =>
      try captureAndAdvance()
      catch case error: Throwable => fail(error)
    pause.play()

  private def captureAndAdvance(): Unit =
    val window = floating
    require(
      window.isShowing && (sceneOnly || window.isFocused),
      "Owned stage must be visible/focused before screen capture"
    )
    val scene = window.getScene
    scene.getRoot.applyCss()
    scene.getRoot.layout()
    val expected = phase match
      case 0 | 2 => "DARK"
      case 1     => "LIGHT"
      case _     => Platform.getPreferences.getColorScheme.toString
    val prefs = classOf[Scene].getMethod("getPreferences").invoke(scene)
    val actual = Class.forName(
      "javafx.scene.Scene$Preferences"
    ).getMethod("getColorScheme").invoke(prefs).toString
    require(actual == expected, s"Native scheme $actual != $expected")
    val label = Vector(
      "custom-dark-new",
      "custom-light-existing",
      "custom-dark-restored",
      "unknown-platform"
    )(phase)
    val snapshot = scene.snapshot(null)
    writePng(snapshot, output.resolve(label + "-scene.png"))
    if !sceneOnly then
      val screen = new Robot().getScreenCapture(
        null,
        window.getX,
        window.getY,
        window.getWidth,
        window.getHeight,
        true
      )
      val x = (scene.getWidth * 0.65).toInt
      val y = (scene.getHeight * 0.70).toInt
      val a = snapshot.getPixelReader.getColor(x, y)
      val b = screen.getPixelReader.getColor((scene.getX + x).toInt, (scene.getY + y).toInt)
      require(
        math.abs(a.getRed - b.getRed) < 0.03 && math.abs(a.getGreen - b.getGreen) < 0.03 &&
          math.abs(a.getBlue - b.getBlue) < 0.03,
        s"Screen capture did not match owned scene: $a / $b"
      )
      writePng(screen, output.resolve(label + "-window.png"))
    val props = new Properties
    for key <- Vector("java.version", "javafx.runtime.version", "os.name", "os.version", "os.arch")
    do
      props.setProperty(key, System.getProperty(key, "unknown")): Unit
    props.setProperty("scheme.actual", actual): Unit
    props.setProperty("scheme.expected", expected): Unit
    val style = window.getStyle.toString
    props.setProperty("stage.style", style): Unit
    props.setProperty(
      "capture",
      if sceneOnly then "Scene snapshot only; OS decorations not captured"
      else "Focused owned stage rectangle; synthetic scene sample matched"
    ): Unit
    props.setProperty("outputScaleX", window.getOutputScaleX.toString): Unit
    val stream = Files.newOutputStream(output.resolve(label + ".properties"))
    try props.store(stream, "S1 native scheme")
    finally stream.close()
    val prefix  = if sceneOnly then "S1_SCENE_FRAME_OK" else "S1_NATIVE_FRAME_OK"
    val focused = window.isFocused
    println(s"$prefix phase=$label scheme=$actual style=$style focused=$focused")
    phase += 1
    phase match
      case 1 =>
        dock.get.setTheme(DockTheme.CustomWithScheme(lightSheet, dark = false))
        schedule()
      case 2 =>
        dock.get.dockAllBack()
        dock.get.setTheme(DockTheme.CustomWithScheme(darkSheet, dark = true))
        require(dock.get.load(saved.get).isRight)
        schedule()
      case 3 =>
        dock.get.setTheme(DockTheme.Custom(lightSheet))
        schedule()
      case _ =>
        val prefix = if sceneOnly then "S1_SCENE_PROBE_OK" else "S1_NATIVE_PROBE_OK"
        println(s"$prefix frames=4 ownedWindowsClosed=true")
        cleanup()
  end captureAndAdvance

  private def cleanup(): Unit =
    dock.foreach(_.dispose())
    mainStage.foreach(_.close())
    Platform.exit()

  private def fail(error: Throwable): Unit =
    ThemeNativeProbe.failure = Some(error)
    cleanup()

  private def writePng(image: WritableImage, file: Path): Unit =
    val width  = image.getWidth.toInt
    val height = image.getHeight.toInt
    val png    = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for y <- 0 until height; x <- 0 until width do
      png.setRGB(x, y, image.getPixelReader.getArgb(x, y))
    require(ImageIO.write(png, "png", file.toFile), "No PNG writer")
end ThemeProbeApp
