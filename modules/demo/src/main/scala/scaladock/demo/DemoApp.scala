package scaladock.demo

import javafx.scene.layout.BorderPane
import scaladock.*
import scaladock.dsl.*
import scaladock.fx.{Dock, PaneFactories}
import upickle.default.ReadWriter

/** The imaging-workstation showcase: living documentation and acceptance harness. */
object DemoApp:

  // -- pane types (typed state, wire-tagged) -------------------------------------------------

  /** A slice viewer. `hue` picks the variant (below 120: functional overlay); `x`/`y` are the
    * crosshair in millimetres.
    */
  final case class ViewerState(
      name: String,
      hue: Double,
      slice: Int = 42,
      x: Double = 12.0,
      y: Double = -34.5
  ) derives ReadWriter
  final case class NoteState(text: String) derives ReadWriter
  final case class ExplorerState(root: String = "study-7", selected: String = "anat/scan-01.nii.gz")
      derives ReadWriter
  final case class ConsoleState(job: String = "preproc") derives ReadWriter
  final case class ProblemsState(scope: String = "SUB-01") derives ReadWriter
  final case class LayerSetting(id: String, visible: Boolean, opacity: Double) derives ReadWriter
  final case class LayersState(layers: Vector[LayerSetting] = Vector.empty) derives ReadWriter

  val Viewer: PaneType[ViewerState]     = PaneType[ViewerState]("demo.viewer")
  val Note: PaneType[NoteState]         = PaneType[NoteState]("demo.note")
  val Explorer: PaneType[ExplorerState] = PaneType[ExplorerState]("demo.explorer")
  val Console: PaneType[ConsoleState]   = PaneType[ConsoleState]("demo.console")
  val Problems: PaneType[ProblemsState] = PaneType[ProblemsState]("demo.problems")
  val Layers: PaneType[LayersState]     = PaneType[LayersState]("demo.layers")

  // -- wiring ---------------------------------------------------------------------------------

  /** The demo's layout, factories and shell: shared by the app and the screenshot rig. */
  final class Built(val dock: Dock, val shell: BorderPane, val layout: LayoutState)

  def build(): Built =
    val session = Session()
    val factories = PaneFactories.empty
      .register(Viewer)(s => ViewerPane(s, session))
      .register(Note)(s => NotePane(s))
      .register(Explorer)(s => ExplorerPane(s, session))
      .register(Console)(s => ConsolePane(s))
      .register(Problems)(s => ProblemsPane(s))
      .register(Layers)(s => LayersPane(s, session))

    val layout = LayoutState.of(
      row(
        group(Explorer(ExplorerState()).titled("Explorer")) sized 240.px atLeast 120,
        column(
          row(
            Viewer(ViewerState("scan-01.nii.gz", 210)).titled("scan-01"),
            Viewer(ViewerState("scan-02.nii.gz", 30)).titled("scan-02")
          ) sized 1.fr,
          group(
            Console(ConsoleState()).titled("Console"),
            Problems(ProblemsState()).titled("Problems")
          ) sized 200.px
        ) sized 1.fr,
        group(Layers(LayersState()).titled("Layers")) sized 260.px
      )
    )

    val dock = Dock(factories, initial = layout)

    session.openFile = path =>
      val name       = path.split('/').last
      val functional = path.startsWith("func/") || path.contains("/stats/")
      val pane = Viewer(ViewerState(
        name,
        if functional then 30 else 210
      )).titled(name.stripSuffix(".nii.gz"))
      dock.open(pane): Unit

    val rng = java.util.Random()
    def newViewer(): PaneDef =
      val n = ViewerPane.instances.get() + 1
      Viewer(ViewerState(s"untitled-$n.nii.gz", rng.nextDouble() * 360)).titled(s"untitled-$n")

    Built(dock, Shell(dock, session, layout, () => newViewer()), layout)
  end build
end DemoApp
