package scaladock.demo

import javafx.animation.{Animation, FadeTransition}
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.{
  ListCell,
  ListView,
  ScrollPane,
  Slider,
  TextArea,
  ToggleButton,
  TreeItem,
  TreeView
}
import javafx.scene.input.MouseButton
import javafx.scene.layout.{HBox, Priority, Region, StackPane, VBox}
import javafx.util.Duration
import scala.jdk.CollectionConverters.*
import scaladock.demo.DemoApp.*
import scaladock.fx.PaneView

/** Every pane root: the demo sheets (so it styles even inside a popped-out window) + base class. */
private def paneRoot[R <: Region](r: R, classes: String*): R =
  r.getStyleClass.add("demo-pane"): Unit
  r.getStyleClass.addAll(classes*): Unit
  DemoStyle.adopt(r)
  r

/** A slim section header row, VS Code style: an uppercase title and trailing detail. */
private def paneHeader(title: String, trailing: Node*): HBox =
  val row = HBox(label(title, "section-title"), spacer())
  row.getChildren.addAll(trailing*): Unit
  row.getStyleClass.add("pane-header"): Unit
  row

// -- Explorer ---------------------------------------------------------------------------------

final class ExplorerPane(state: ExplorerState, session: Session)
    extends PaneView[ExplorerState]:

  private def dir(name: String, expanded: Boolean, children: TreeItem[String]*): TreeItem[String] =
    val item = TreeItem[String](name, Icons.folder())
    item.getChildren.addAll(children*): Unit
    item.setExpanded(expanded)
    item

  private def file(name: String): TreeItem[String] = TreeItem[String](name, Icons.file(name))

  private val root = dir(
    state.root,
    true,
    dir("anat", true, file("scan-01.nii.gz"), file("scan-01.json")),
    dir("func", true, file("scan-02.nii.gz"), file("scan-02.json"), file("task-nback_events.tsv")),
    dir(
      "derivatives",
      true,
      dir("motion", false, file("confounds.tsv"), file("fd.tsv")),
      dir("registration", false, file("func2anat.json")),
      dir("stats", true, file("zstat1.nii.gz"), file("design.tsv")),
      dir("atlas", true, file("aal-outline.nii.gz"), file("aal-labels.tsv"))
    ),
    file("dataset_description.json"),
    file("participants.tsv")
  )

  private val tree = TreeView[String](root)

  /** Path relative to the study root, e.g. `anat/scan-01.nii.gz`. */
  private def pathOf(item: TreeItem[String]): String =
    Iterator.iterate(item)(
      _.getParent
    ).takeWhile(i => i != null && (i ne root)).map(_.getValue).toVector.reverse.mkString("/")

  private def find(path: String): Option[TreeItem[String]] =
    def go(i: TreeItem[String]): Option[TreeItem[String]] =
      if pathOf(i) == path && (i ne root) then Some(i)
      else i.getChildren.asScala.iterator.map(go).collectFirst { case Some(x) => x }
    go(root)

  val node: VBox =
    paneRoot(VBox(paneHeader(state.root.toUpperCase, label("BIDS 1.9", "badge")), tree), "explorer")

  def snapshot(): ExplorerState =
    val sel = Option(tree.getSelectionModel.getSelectedItem).map(pathOf).getOrElse(state.selected)
    state.copy(selected = sel)

  override def icon(): Option[Node] = Some(Icons.folder())

  locally:
    VBox.setVgrow(tree, Priority.ALWAYS)
    tree.setShowRoot(true)
    find(state.selected).foreach(tree.getSelectionModel.select)
    tree.setOnMouseClicked: e =>
      if e.getButton == MouseButton.PRIMARY && e.getClickCount == 2 then
        Option(tree.getSelectionModel.getSelectedItem)
          .filter(i => i.isLeaf && i.getValue.endsWith(".nii.gz"))
          .foreach(i => session.openFile(pathOf(i)))
end ExplorerPane

// -- Console ----------------------------------------------------------------------------------

final class ConsolePane(state: ConsoleState)
    extends PaneView[ConsoleState]:

  private val log = Vector(
    ("09:41:02.118", "INFO", "fmriprep 23.2.1 · participant sub-01 · 8 threads"),
    ("09:41:02.304", "INFO", "load anat/scan-01.nii.gz  256×256×96  1.0 mm iso"),
    ("09:41:04.771", "INFO", "N4 bias-field correction · 4 levels × 50 iterations"),
    ("09:41:09.052", "OK", "skull strip  brain mask 1.42 L"),
    ("09:41:11.460", "INFO", "load func/scan-02.nii.gz  64×64×36 × 240 vols  TR 2.0 s"),
    ("09:41:12.009", "INFO", "motion correction (mcflirt) · reference vol 120"),
    ("09:41:31.337", "WARN", "vol 187: framewise displacement 0.62 mm > 0.50 mm"),
    ("09:41:31.340", "OK", "motion correction  mean FD 0.14 mm · 3 outliers censored"),
    ("09:41:33.918", "INFO", "slice-timing correction · interleaved ascending"),
    ("09:41:40.225", "INFO", "bbregister func → anat · 6 dof · boundary cost"),
    ("09:41:52.604", "WARN", "registration cost 0.61 above advisory 0.55"),
    ("09:41:53.117", "OK", "normalise → MNI152NLin2009cAsym  2 mm"),
    ("09:41:58.480", "INFO", "GLM  3 task regressors + 24 confounds · AR(1)"),
    ("09:42:07.902", "OK", "wrote derivatives/stats/zstat1.nii.gz  peak z 6.2")
  )

  private def line(ts: String, level: String, msg: String): HBox =
    val m = label(msg, "console-msg", "mono")
    HBox.setHgrow(m, Priority.ALWAYS)
    val row = HBox(
      label(ts, "console-ts", "mono"),
      label(level, "console-level", s"level-${level.toLowerCase}", "mono"),
      m
    )
    row.getStyleClass.add("console-line"): Unit
    row

  private val caret = Region()

  private val blink =
    val f = FadeTransition(Duration.millis(530), caret)
    f.setFromValue(1)
    f.setToValue(0)
    f.setAutoReverse(true)
    f.setCycleCount(Animation.INDEFINITE)
    f

  private val prompt =
    val row = HBox(label(s"${state.job} ❯", "console-prompt", "mono"), caret)
    row.getStyleClass.addAll("console-line", "console-prompt-line"): Unit
    row

  private val lines  = VBox()
  private val scroll = ScrollPane(lines)

  /** A soft fade over the log's top edge, so a partly scrolled-off line dissolves under the header
    * instead of being cut in half.
    */
  private val fade = Region()

  val node: StackPane = paneRoot(StackPane(scroll, fade), "console")

  def snapshot(): ConsoleState = state

  override def icon(): Option[Node] = Some(Icons.terminal())
  override def dispose(): Unit      = blink.stop()

  locally:
    caret.getStyleClass.add("console-caret"): Unit
    lines.getStyleClass.add("console-lines"): Unit
    fade.getStyleClass.add("console-fade"): Unit
    fade.setMouseTransparent(true)
    StackPane.setAlignment(fade, Pos.TOP_CENTER)
    log.foreach((ts, lvl, msg) => lines.getChildren.add(line(ts, lvl, msg)): Unit)
    lines.getChildren.add(prompt): Unit
    scroll.setFitToWidth(true)
    scroll.setVvalue(1.0)
    blink.play()
end ConsolePane

// -- Problems ---------------------------------------------------------------------------------

private final case class Problem(warning: Boolean, message: String, location: String)

private final class ProblemCell extends ListCell[Problem]:
  setPrefWidth(0) // fit the list width: ellipsize instead of scrolling sideways

  override protected def updateItem(p: Problem, empty: Boolean): Unit =
    super.updateItem(p, empty)
    setText(null)
    if empty || p == null then setGraphic(null)
    else
      val msg = label(p.message, "problem-msg")
      msg.setMinWidth(Region.USE_PREF_SIZE)
      val loc = label(p.location, "problem-loc", "mono")
      val row = HBox(if p.warning then Icons.warning() else Icons.info(), msg, loc)
      row.getStyleClass.add("problem-row"): Unit
      row.setAlignment(Pos.CENTER_LEFT)
      setGraphic(row)

final class ProblemsPane(state: ProblemsState)
    extends PaneView[ProblemsState]:

  private val problems = Vector(
    Problem(true, "Framewise displacement above 0.5 mm in 3 volumes", "func/scan-02.nii.gz:187"),
    Problem(
      true,
      "Registration cost 0.61 exceeds advisory 0.55",
      "derivatives/registration/func2anat.json:12"
    ),
    Problem(true, "Missing value in column 'handedness'", "participants.tsv:3"),
    Problem(false, "SliceTiming inferred from JSON sidecar", "func/scan-02.json:14"),
    Problem(false, "Atlas resampled to 2 mm functional grid", "derivatives/atlas/aal-labels.tsv:1")
  )

  private val list = ListView[Problem]()

  private def tally(icon: Node, n: Int): HBox =
    val h = HBox(icon, label(n.toString, "tally-count"))
    h.getStyleClass.add("tally"): Unit
    h

  val node: VBox =
    val warnings = problems.count(_.warning)
    paneRoot(
      VBox(
        paneHeader(
          state.scope,
          tally(Icons.warning(), warnings),
          tally(Icons.info(), problems.size - warnings)
        ),
        list
      ),
      "problems"
    )

  def snapshot(): ProblemsState = state

  override def icon(): Option[Node] = Some(Icons.problems())

  locally:
    VBox.setVgrow(list, Priority.ALWAYS)
    list.setCellFactory(_ => ProblemCell())
    list.getItems.setAll(problems.asJava): Unit
end ProblemsPane

// -- Layers -----------------------------------------------------------------------------------

final class LayersPane(state: LayersState, session: Session)
    extends PaneView[LayersState]:

  private val Hidden   = PseudoClass.getPseudoClass("hidden-layer")
  private val Selected = PseudoClass.getPseudoClass("selected")

  private def row(m: LayerModel): VBox =
    val eye = ToggleButton()
    eye.setGraphic(Icons.eye())
    eye.getStyleClass.setAll("layer-eye")
    eye.selectedProperty.bindBidirectional(m.visible)

    val swatch = Region()
    swatch.getStyleClass.addAll("layer-swatch", m.swatch): Unit

    // the secondary line is only the file name: the folder lives in the Explorer
    val text =
      VBox(label(m.name, "layer-name"), label(m.detail.split('/').last, "layer-detail", "mono"))
    text.setMinWidth(0)
    HBox.setHgrow(text, Priority.ALWAYS)

    val pct = label("", "layer-pct", "mono")
    pct.textProperty.bind(m.opacity.multiply(100).asString("%.0f%%"))

    val slider = Slider(0, 1, m.opacity.get)
    slider.valueProperty.bindBidirectional(m.opacity)
    HBox.setHgrow(slider, Priority.ALWAYS)

    val top = HBox(eye, swatch, text, pct)
    top.getStyleClass.add("layer-top"): Unit
    val bottom = HBox(slider)
    bottom.getStyleClass.add("layer-slider-row"): Unit

    val r = VBox(top, bottom)
    r.getStyleClass.add("layer-row"): Unit
    def sync(): Unit = r.pseudoClassStateChanged(Hidden, !m.visible.get)
    m.visible.addListener((_, _, _) => sync())
    sync()
    r

  private val rows = session.layers.map(row)

  val node: VBox =
    paneRoot(
      VBox(paneHeader("LAYER STACK", label(s"${rows.size}", "badge")), VBox(rows*)),
      "layers"
    )

  def snapshot(): LayersState =
    LayersState(session.layers.map(m => LayerSetting(m.id, m.visible.get, m.opacity.get)))

  override def icon(): Option[Node] = Some(Icons.layers())

  locally:
    // a saved stack wins over the session defaults
    state.layers.foreach: s =>
      val m = session.layer(s.id)
      m.visible.set(s.visible)
      m.opacity.set(s.opacity.max(0).min(1))
    rows.foreach: r =>
      r.setOnMousePressed: _ =>
        rows.foreach(o => o.pseudoClassStateChanged(Selected, o eq r))
    rows.drop(1).headOption.foreach(_.pseudoClassStateChanged(Selected, true))
end LayersPane

// -- Note (free text; the screenshot rig opens extra panels as notes) -------------------------

final class NotePane(state: NoteState) extends PaneView[NoteState]:
  /** "<Name> panel" notes render as a small tool panel; everything else is an editable note. */
  private val panel = NotePanels.forText(state.text)
  private val area  = TextArea(state.text)

  private val body: Node = panel.map(_._2).getOrElse(area)

  val node: VBox =
    val header = panel match
      case Some((title, _)) => paneHeader(title)
      case None             => paneHeader("NOTES", label("markdown", "badge"))
    paneRoot(VBox(header, body), "note")

  def snapshot(): NoteState = if panel.isDefined then state else NoteState(area.getText)

  override def icon(): Option[Node] = Some(Icons.note())

  locally:
    area.setWrapText(true)
    area.setPromptText("Write a note…")
    VBox.setVgrow(body, Priority.ALWAYS)
