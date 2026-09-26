package scaladock.demo

import java.nio.charset.StandardCharsets
import java.util.Base64
import javafx.beans.property.{SimpleBooleanProperty, SimpleDoubleProperty, SimpleStringProperty}
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.layout.{HBox, Priority, Region}
import javafx.scene.text.Font

/** One overlay layer shared by the Layers panel (which edits it) and every viewer (which draws it).
  */
final class LayerModel(
    val id: String,
    val name: String,
    val detail: String,
    val swatch: String,
    visible0: Boolean,
    opacity0: Double
):
  val visible: SimpleBooleanProperty = SimpleBooleanProperty(visible0)
  val opacity: SimpleDoubleProperty  = SimpleDoubleProperty(opacity0)

/** Cross-pane application state for one demo shell: the cursor, the layer stack, file opening. */
final class Session:
  val ras: SimpleStringProperty = SimpleStringProperty(Session.ras(12.0, -34.5, 18.0))

  val layers: Vector[LayerModel] = Vector(
    LayerModel("t1w", "T1w anatomical", "anat/scan-01.nii.gz", "layer-swatch-gray", true, 1.0),
    LayerModel("zstat", "z-stat · n-back", "stats/zstat1.nii.gz", "layer-swatch-hot", true, 0.85),
    LayerModel(
      "atlas",
      "Atlas outline",
      "atlas/aal-outline.nii.gz",
      "layer-swatch-atlas",
      false,
      0.6
    )
  )

  def layer(id: String): LayerModel = layers.find(_.id == id).getOrElse(layers.head)

  /** Open a study file in a new viewer; wired by the shell once the dock exists. */
  var openFile: String => Unit = _ => ()

object Session:
  def ras(x: Double, y: Double, z: Double): String =
    f"RAS   x $x%.1f   y $y%.1f   z $z%.1f"

/** The demo's stylesheets. Pane roots and icons carry them too, so popped-out windows and the drag
  * chip (separate scenes that only inherit the dock's own sheets) still style demo content.
  */
private[demo] object DemoStyle:
  lazy val sheet: String =
    Option(getClass.getResource("/scaladock/demo/demo.css")).map(_.toExternalForm).getOrElse("")

  /** JavaFX CSS takes a single font family, so resolve the monospace fallback chain here. */
  lazy val monoFamily: String =
    val installed = Font.getFamilies
    Vector("JetBrains Mono", "SF Mono", "Menlo", "Cascadia Mono", "Consolas", "DejaVu Sans Mono")
      .find(f => installed.contains(f))
      .getOrElse("Monospaced")

  private lazy val monoSheet: String =
    val css = s""".mono { -fx-font-family: "$monoFamily"; }"""
    "data:text/css;base64," + Base64.getEncoder.encodeToString(css.getBytes(StandardCharsets.UTF_8))

  lazy val sheets: Vector[String] = Vector(sheet, monoSheet).filter(_.nonEmpty)

  def adopt(p: Parent): Unit = p.getStylesheets.addAll(sheets*): Unit

private[demo] def label(text: String, classes: String*): Label =
  val l = Label(text)
  l.getStyleClass.addAll(classes*): Unit
  l

private[demo] def spacer(): Region =
  val r = Region()
  HBox.setHgrow(r, Priority.ALWAYS)
  r
