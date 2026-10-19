package scaladock.fx

import java.nio.file.{Files, Path}
import javafx.animation.{KeyFrame, Timeline}
import javafx.application.{Application, Platform}
import javafx.scene.Scene
import javafx.scene.control.TextField
import javafx.stage.Stage
import javafx.util.Duration
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter

/** An owned synthetic window for external macOS AX probes; always exits within five minutes. */
object AccessibilityNativeProbe:
  @volatile private[fx] var failure: Option[Throwable] = None
  final case class Text(value: String) derives ReadWriter
  val kind: PaneType[Text] = PaneType[Text]("s4.native.synthetic")
  def main(args: Array[String]): Unit =
    Application.launch(classOf[AccessibilityProbeApp], args*)
    failure.foreach(throw _)

final class AccessibilityProbeApp extends Application:
  import AccessibilityNativeProbe.*
  private var dock: Option[Dock]      = None
  private var timer: Option[Timeline] = None
  private var owner: Option[Stage]    = None
  private var ticks                   = 0
  override def start(stage: Stage): Unit =
    try
      val output = Path.of(getParameters.getRaw.get(0))
      Files.createDirectories(output): Unit
      val factories = PaneFactories.empty.register(kind): text =>
        new PaneView[Text]:
          val node             = new TextField(text.value)
          def snapshot(): Text = text
      val d = Dock(
        factories,
        initial = LayoutState.of(row(
          group(
            kind(Text("alpha content")).titled("Alpha"),
            kind(Text("beta content")).titled("Beta")
          ),
          group(kind(Text("inspector content")).titled("Inspector"))
        ))
      )
      dock = Some(d)
      owner = Some(stage)
      var standardFired = 0
      val standard      = new javafx.scene.control.Button("Standard JavaFX button")
      standard.setOnAction(_ => standardFired += 1)
      val root = new javafx.scene.layout.BorderPane(d.view)
      root.setBottom(standard)
      stage.setScene(new Scene(root, 1100, 650))
      stage.setTitle("ScalaDock S4 synthetic accessibility probe")
      stage.show()
      d.focus(d.state.panes.head.id); d.focusContent(d.state.panes.head.id)
      Files.writeString(output.resolve("pid"), ProcessHandle.current().pid().toString): Unit
      val timeline = new Timeline(new KeyFrame(
        Duration.millis(200),
        _ =>
          try
            ticks += 1
            val state = ujson.Obj(
              "java"                   -> System.getProperty("java.version"),
              "javafx"                 -> System.getProperty("javafx.runtime.version"),
              "accessibilityActive"    -> Platform.isAccessibilityActive,
              "standardButtonFired"    -> standardFired,
              "standardButtonDisabled" -> standard.isDisabled,
              "buttons" -> ujson.Arr.from(d.view.lookupAll(
                ".dock-header-button"
              ).toArray.toVector.map(_.asInstanceOf[javafx.scene.Node]).map(n =>
                ujson.Obj(
                  "name"     -> n.getAccessibleText,
                  "disabled" -> n.isDisabled,
                  "accessibleDisabled" -> String.valueOf(
                    n.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.DISABLED)
                  )
                )
              )),
              "panes"     -> ujson.Arr.from(d.state.panes.map(_.title)),
              "focused"   -> d.state.focused.flatMap(d.state.findPane).fold("")(_.title),
              "minimized" -> d.state.minimized.size,
              "maximized" -> d.state.maximized.isDefined,
              "floating"  -> d.state.floating.size,
              "focusName" -> Option(d.view.getScene.getFocusOwner).flatMap(n =>
                Option(n.getAccessibleText)
              ).getOrElse("")
            )
            Files.writeString(output.resolve("state.json"), ujson.write(state, indent = 2)): Unit
            if Files.exists(output.resolve("finish")) || ticks >= 1500 then
              if ticks >= 1500 then failure = Some(new IllegalStateException("AX probe timed out"))
              cleanup()
          catch
            case error: Throwable => failure = Some(error); cleanup()
      ))
      timer = Some(timeline)
      timeline.setCycleCount(javafx.animation.Animation.INDEFINITE)
      timeline.play()
    catch
      case error: Throwable => failure = Some(error); cleanup()
  private def cleanup(): Unit =
    timer.foreach(_.stop())
    dock.foreach(_.dispose())
    owner.foreach(_.close())
    Platform.exit()
end AccessibilityProbeApp
