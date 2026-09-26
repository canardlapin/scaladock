package scaladock.demo

import javafx.scene.Node
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.shape.{Rectangle, SVGPath}

/** One outline icon family, 16px, drawn as stroked paths on half-pixel centres so the 1.25px
  * strokes match the dock's own chrome glyphs. Every call builds a fresh node (chrome rebuilds
  * freely); stroke colours come from demo.css (`.demo-icon`, `.demo-icon-<kind>`).
  */
private[demo] object Icons:

  private def circle(cx: Double, cy: Double, r: Double): String =
    s" M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

  private def box(content: Node*): Pane =
    val p = Pane(content*)
    p.setMinSize(16, 16)
    p.setPrefSize(16, 16)
    p.setMaxSize(16, 16)
    p.getStyleClass.add("demo-icon-box"): Unit
    DemoStyle.adopt(p)
    p

  private def glyph(kind: String, path: String): Node =
    val svg = SVGPath()
    svg.setContent(path)
    svg.setFill(null)
    svg.setStroke(Color.GRAY) // only if no demo sheet reaches it
    svg.getStyleClass.addAll("demo-icon", s"demo-icon-$kind"): Unit
    box(svg)

  // -- pane glyphs -------------------------------------------------------------------------------

  def viewer(): Node =
    glyph("viewer", "M2.5 3.5h11v9h-11z M2.5 10.5l3-3 3 3 1.5-1.5 3.5 3.5" + circle(10.5, 6.25, 1))

  def folder(): Node = glyph("folder", "M1.5 3.5h4.5l1.5 1.5h7v7.5h-13z")

  def terminal(): Node = glyph("terminal", "M1.5 3h13v10h-13z M4.5 6.5l2 1.75-2 1.75 M8.5 10.5h3")

  def problems(): Node = glyph("problems", triangle)
  def warning(): Node  = glyph("warning", triangle)

  def info(): Node = glyph("info", circle(8, 8, 6) + " M8 7.5v3.5 M8 5v.01")

  def layers(): Node = glyph("layers", "M8 2l6 3-6 3-6-3z M2 8l6 3 6-3 M2 11l6 3 6-3")

  def note(): Node = glyph("note", filePath + " M5.5 8.5h5 M5.5 11h3.5")

  private val triangle = "M8 2l6.5 11.5h-13z M8 6.5v3 M8 11.5v.01"
  private val filePath = "M3.5 1.5h5.5l3.5 3.5v9.5h-9z M9 1.5v3.5h3.5"

  // -- file types ----------------------------------------------------------------------------------

  def file(name: String): Node =
    val kind =
      if name.endsWith(".nii.gz") || name.endsWith(".nii") then "nifti"
      else if name.endsWith(".json") then "json"
      else if name.endsWith(".tsv") then "tsv"
      else "file"
    glyph(kind, filePath)

  // -- actions -------------------------------------------------------------------------------------

  def save(): Node = glyph("action", "M2.5 2.5h9l2 2v9h-11z M5 2.5v3h5v-3 M5 13.5v-4h6v4")

  def load(): Node =
    glyph("action", "M1.5 3.5h4.5l1.5 1.5h7v7.5h-13z M8 11.5v-4 M6.25 9.25L8 7.5l1.75 1.75")

  def reset(): Node = glyph("action", "M3 8a5 5 0 1 0 1.46-3.54 M4.5 1.75v2.75h2.75")

  def plus(): Node = glyph("action", "M8 3v10 M3 8h10")

  def sun(): Node =
    glyph(
      "action",
      circle(8, 8, 2.75) +
        " M8 1.5v1.5 M8 13v1.5 M1.5 8h1.5 M13 8h1.5 M3.4 3.4l1 1 M11.6 11.6l1 1 M12.6 3.4l-1 1 M4.4 11.6l-1 1"
    )

  def moon(): Node = glyph("action", "M13.5 10A6 6 0 1 1 6 2.5a4.75 4.75 0 0 0 7.5 7.5z")

  def eye(): Node =
    glyph(
      "eye",
      "M1.5 8s2.4-4.5 6.5-4.5 6.5 4.5 6.5 4.5-2.4 4.5-6.5 4.5S1.5 8 1.5 8z" + circle(8, 8, 2)
    )

  /** The drag-handle grip: two columns of dots (filled: dots have no outline). */
  def grip(): Node =
    glyph(
      "grip",
      Vector(4.0, 8.0, 12.0).flatMap(y => Vector(circle(6, y, 1), circle(10, y, 1))).mkString
    )

  /** The wordmark tile: a docked layout in miniature, the focused group in accent. */
  def brand(): Node =
    def tile(x: Double, y: Double, w: Double, h: Double, cls: String): Rectangle =
      val r = Rectangle(x, y, w, h)
      r.setArcWidth(3)
      r.setArcHeight(3)
      r.getStyleClass.add(cls): Unit
      r
    box(
      tile(1, 1, 6, 14, "brand-tile"),
      tile(9, 1, 6, 6, "brand-tile-accent"),
      tile(9, 9, 6, 6, "brand-tile")
    )
end Icons
