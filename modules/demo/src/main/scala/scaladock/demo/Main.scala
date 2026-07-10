package scaladock.demo

import java.util.concurrent.atomic.AtomicInteger
import javafx.geometry.Insets
import javafx.scene.control.{Label, TextArea}
import javafx.scene.layout.{BorderPane, StackPane}
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import scalafx.Includes.*
import scalafx.application.JFXApp3
import scalafx.scene.Scene
import scaladock.*
import scaladock.dsl.*
import scaladock.fx.{Dock, PaneContext, PaneFactories, PaneView}
import upickle.default.ReadWriter

/** The VS Code-style imaging mock: living documentation and acceptance harness. */
object Main extends JFXApp3:

  // -- pane types (typed state, wire-tagged) -------------------------------------------------

  final case class ViewerState(name: String, hue: Double) derives ReadWriter
  final case class NoteState(text: String) derives ReadWriter

  val Viewer: PaneType[ViewerState] = PaneType[ViewerState]("demo.viewer")
  val Note: PaneType[NoteState]     = PaneType[NoteState]("demo.note")

  /** Counts constructions: if any interaction ever recreates a pane view, this number moves on
    * screen and betrays it. The load-bearing guarantee, made visible.
    */
  private val viewerInstances = AtomicInteger(0)

  final class ViewerPane(state: ViewerState)(using PaneContext[ViewerState])
      extends PaneView[ViewerState]:
    private val instance = viewerInstances.incrementAndGet()

    val node: StackPane =
      val swatch = new Rectangle(360, 240, Color.hsb(state.hue, 0.55, 0.55))
      swatch.setArcWidth(12); swatch.setArcHeight(12)
      val caption = new Label(s"${state.name}\ninstance #$instance")
      caption.setStyle("-fx-text-fill: white; -fx-font-size: 15; -fx-text-alignment: center;")
      val pane = new StackPane(swatch, caption)
      pane.setPadding(new Insets(12))
      pane

    def snapshot(): ViewerState = state

  final class NotePane(state: NoteState)(using PaneContext[NoteState])
      extends PaneView[NoteState]:
    private val area = new TextArea(state.text)
    area.setWrapText(true)
    val node: BorderPane      = new BorderPane(area)
    def snapshot(): NoteState = NoteState(area.getText)

  // -- wiring ---------------------------------------------------------------------------------

  override def start(): Unit =
    val factories = PaneFactories.empty
      .register(Viewer)(s => ViewerPane(s))
      .register(Note)(s => NotePane(s))

    val layout = LayoutState.of(
      row(
        group(Note(NoteState("study-7/\n  scan-01.nii\n  scan-02.nii")).titled("Explorer"))
          sized 240.px atLeast 120,
        column(
          row(
            Viewer(ViewerState("scan-01.nii", 210)).titled("scan-01"),
            Viewer(ViewerState("scan-02.nii", 30)).titled("scan-02")
          ) sized 1.fr,
          group(
            Note(NoteState("> loaded study-7")).titled("Console"),
            Note(NoteState("no problems")).titled("Problems")
          ) sized 200.px
        ) sized 1.fr,
        group(Note(NoteState("layers: anatomical, functional")).titled("Layers"))
          sized 260.px
      )
    )

    val dock = Dock(factories, initial = layout)

    // toolbar: persistence round trip + a drag-source palette entry
    val layoutFile = java.nio.file.Path.of(sys.props("user.home"), ".scaladock-demo-layout.json")

    val saveButton = new javafx.scene.control.Button("Save layout")
    saveButton.setOnAction: _ =>
      java.nio.file.Files.writeString(layoutFile, ujson.write(dock.save(), indent = 2)): Unit

    val loadButton = new javafx.scene.control.Button("Load layout")
    loadButton.setOnAction: _ =>
      if java.nio.file.Files.exists(layoutFile) then
        dock.load(ujson.read(java.nio.file.Files.readString(layoutFile))) match
          case Left(err) => System.err.println(s"load failed: ${err.message}")
          case Right(()) => ()

    val resetButton = new javafx.scene.control.Button("Reset")
    resetButton.setOnAction(_ => dock.update(_ => layout))

    val palette = new Label("✛ drag me: new viewer")
    palette.setStyle("-fx-padding: 4 10 4 10; -fx-border-color: #666; -fx-border-radius: 4;")
    val rng = java.util.Random()
    dock.dragSource(palette): () =>
      val n = viewerInstances.get() + 1
      Viewer(ViewerState(s"untitled-$n.nii", rng.nextDouble() * 360)).titled(s"untitled-$n")

    val themeButton = new javafx.scene.control.Button("Light theme")
    themeButton.setOnAction: _ =>
      val toLight = themeButton.getText == "Light theme"
      dock.setTheme(if toLight then scaladock.fx.DockTheme.Light else scaladock.fx.DockTheme.Dark)
      themeButton.setText(if toLight then "Dark theme" else "Light theme")

    val bar = new javafx.scene.control.ToolBar(
      saveButton,
      loadButton,
      resetButton,
      themeButton,
      palette
    )
    val shell = new BorderPane(dock.view)
    shell.setTop(bar)

    stage = new JFXApp3.PrimaryStage:
      title = "scaladock demo"
      width = 1280
      height = 840
      scene = new Scene:
        root = shell

  end start
end Main
