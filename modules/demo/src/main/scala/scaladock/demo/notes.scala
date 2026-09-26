package scaladock.demo

import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.layout.{GridPane, HBox, Priority, Region, VBox}
import javafx.scene.shape.{Circle, Line, Rectangle}

/** Small, plausible tool panels for notes titled "<Name> panel" (the screenshot rig opens these).
  * Each is compact and themed with the dock variables; anything else stays a plain note.
  */
private[demo] object NotePanels:

  private val Panel = "(\\w+) panel".r

  def forText(text: String): Option[(String, Node)] = text.trim match
    case Panel("Histogram") => Some("HISTOGRAM" -> histogram())
    case Panel("Metadata")  => Some("METADATA" -> metadata())
    case Panel("Atlas")     => Some("ATLAS" -> atlas())
    case Panel("ROIs")      => Some("ROIS" -> rois())
    case Panel("Colormap")  => Some("COLORMAP" -> colormap())
    case _                  => None

  private def scrolled(content: Region): ScrollPane =
    val s = ScrollPane(content)
    s.setFitToWidth(true)
    s

  private def row(classes: String, children: Node*): HBox =
    val h = HBox(children*)
    h.getStyleClass.add(classes): Unit
    h.setAlignment(Pos.CENTER_LEFT)
    h

  // -- Metadata: key/value header fields --------------------------------------------------------

  private def metadata(): Node =
    val fields = Vector(
      "file"        -> "func/scan-02.nii.gz",
      "dimensions"  -> "96 × 96 × 60",
      "volumes"     -> "240",
      "voxel size"  -> "2.0 × 2.0 × 2.0 mm",
      "TR"          -> "2.0 s",
      "TE"          -> "30 ms",
      "flip angle"  -> "77°",
      "orientation" -> "RAS+",
      "datatype"    -> "float32",
      "slice order" -> "interleaved ↑",
      "scanner"     -> "Prisma 3T · 64ch"
    )
    val grid = GridPane()
    grid.getStyleClass.add("kv-grid"): Unit
    fields.zipWithIndex.foreach: (kv, i) =>
      val (k, v) = kv
      val value  = label(v, "kv-value", "mono")
      value.setMinWidth(0)
      grid.add(label(k, "kv-key"), 0, i)
      grid.add(value, 1, i)
      GridPane.setHgrow(value, Priority.ALWAYS)
    scrolled(grid)

  // -- Histogram: intensity distribution drawn with rectangles ----------------------------------

  private def histogram(): Node =
    // a bimodal T1 intensity profile: grey-matter and white-matter peaks over a CSF shoulder
    val bins = Vector.tabulate(40): i =>
      val x                                       = i / 39.0
      def peak(mu: Double, sd: Double, a: Double) = a * math.exp(-math.pow((x - mu) / sd, 2) / 2)
      peak(0.14, 0.06, 0.35) + peak(0.45, 0.07, 0.8) + peak(0.74, 0.06, 1.0) + 0.02
    val chart = HistogramChart(bins)
    VBox.setVgrow(chart, Priority.ALWAYS)
    val axis = row(
      "hist-axis",
      label("0", "hist-tick", "mono"),
      spacer(),
      label("intensity", "hist-tick"),
      spacer(),
      label("1 024", "hist-tick", "mono")
    )
    val stats = row("hist-stats", label("mean 412  ·  sd 138  ·  n 1.2 M", "kv-value", "mono"))
    val box   = VBox(chart, axis, stats)
    box.getStyleClass.add("hist"): Unit
    box

  // -- Atlas: labelled regions with colour keys --------------------------------------------------

  private val atlasColours =
    Vector("#e0685c", "#e3a33c", "#b5c24a", "#4cb782", "#3fb3c4", "#5b8def", "#9a74e0", "#d46fb0")

  private def dot(hex: String): Circle =
    val c = Circle(4)
    c.setStyle(s"-fx-fill: $hex;") // label colours are data, not chrome
    c

  private def atlas(): Node =
    val regions = Vector(
      ("Precentral_L", "1 204"),
      ("Precentral_R", "1 187"),
      ("Frontal_Mid_L", "2 316"),
      ("Frontal_Mid_R", "2 402"),
      ("Parietal_Sup_L", "  968"),
      ("Occipital_Mid_L", "1 055"),
      ("Cingulum_Ant_L", "  612"),
      ("Hippocampus_L", "  470")
    )
    val list = VBox(regions.zipWithIndex.map { case ((name, vox), i) =>
      val n = label(name, "list-name")
      n.setMinWidth(0)
      HBox.setHgrow(n, Priority.ALWAYS)
      row("list-row", dot(atlasColours(i % atlasColours.size)), n, label(vox, "list-meta", "mono"))
    }*)
    list.getStyleClass.add("list-rows"): Unit
    VBox(
      row(
        "list-caption",
        label("AAL3 · 170 labels", "list-meta"),
        spacer(),
        label("voxels", "list-meta")
      ),
      scrolled(list)
    )

  // -- ROIs: regions of interest with summary statistics ----------------------------------------

  private def rois(): Node =
    val items = Vector(
      ("L DLPFC", "sphere 8 mm", "z̄ 4.1"),
      ("R DLPFC", "sphere 8 mm", "z̄ 3.6"),
      ("V1", "atlas mask", "z̄ 2.9"),
      ("dACC", "drawn", "z̄ 2.4")
    )
    val list = VBox(items.zipWithIndex.map { case ((name, kind, z), i) =>
      val text = VBox(label(name, "list-name"), label(kind, "list-meta"))
      text.setMinWidth(0)
      HBox.setHgrow(text, Priority.ALWAYS)
      row(
        "list-row",
        dot(atlasColours((i * 3) % atlasColours.size)),
        text,
        label(z, "list-value", "mono")
      )
    }*)
    list.getStyleClass.add("list-rows"): Unit
    scrolled(list)

  // -- Colormap: choice of lookup tables and the display range ----------------------------------

  private val Selected = PseudoClass.getPseudoClass("selected")

  private def colormap(): Node =
    val maps = Vector("hot", "viridis", "gray", "cool-warm")
    val rows = maps.map: name =>
      val strip = Region()
      strip.getStyleClass.addAll("cmap-strip", s"cmap-$name"): Unit
      HBox.setHgrow(strip, Priority.ALWAYS)
      val n = label(name, "list-name", "mono")
      n.setMinWidth(72)
      row("list-row", n, strip)
    rows.foreach: r =>
      r.setOnMousePressed(_ => rows.foreach(o => o.pseudoClassStateChanged(Selected, o eq r)))
    rows.headOption.foreach(_.pseudoClassStateChanged(Selected, true))
    val list = VBox(rows*)
    list.getStyleClass.add("list-rows"): Unit
    val range = row(
      "list-caption",
      label("range", "list-meta"),
      spacer(),
      label("2.3 – 6.0", "list-value", "mono")
    )
    VBox(list, range)
end NotePanels

/** A resizable bar chart: bars fill the width, heights normalized to the tallest bin. */
private final class HistogramChart(bins: Vector[Double]) extends Region:
  private val bars     = bins.map(_ => Rectangle())
  private val baseline = Line()
  private val top      = bins.max

  override protected def computePrefHeight(width: Double): Double = 96
  override protected def computeMinHeight(width: Double): Double  = 48
  override protected def computePrefWidth(height: Double): Double = 200
  override protected def computeMinWidth(height: Double): Double  = 0

  override protected def layoutChildren(): Unit =
    val w    = getWidth
    val h    = getHeight
    val step = w / bars.size
    for (b, i) <- bars.zipWithIndex do
      val bh = math.max(1.0, snapSizeY(bins(i) / top * (h - 4)))
      b.setX(snapPositionX(i * step))
      b.setWidth(math.max(1.0, snapSizeX(step) - 1))
      b.setY(h - bh)
      b.setHeight(bh)
    baseline.setStartX(0)
    baseline.setEndX(w)
    baseline.setStartY(h - 0.5)
    baseline.setEndY(h - 0.5)

  locally:
    getStyleClass.add("hist-chart"): Unit
    bars.foreach: b =>
      b.getStyleClass.add("hist-bar"): Unit
      b.setManaged(false)
    baseline.getStyleClass.add("hist-baseline"): Unit
    baseline.setManaged(false)
    bars.foreach(getChildren.add(_): Unit)
    getChildren.add(baseline): Unit
