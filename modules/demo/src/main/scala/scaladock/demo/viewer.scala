package scaladock.demo

import java.util.concurrent.atomic.AtomicInteger
import javafx.beans.InvalidationListener
import javafx.scene.Cursor
import javafx.scene.image.{ImageView, PixelFormat, WritableImage}
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.shape.{Line, Rectangle}
import scaladock.demo.DemoApp.ViewerState
import scaladock.fx.PaneView

/** A radiology-style slice viewer over the procedural phantom. Hue picks the variant: below 120 it
  * is a functional view (anatomy plus hot-colormap z-statistics), otherwise plain anatomy.
  */
final class ViewerPane(state: ViewerState, session: Session)
    extends PaneView[ViewerState]:
  private val viewport = Viewport(state, ViewerPane.instances.incrementAndGet(), session)

  val node: Region                               = viewport
  def snapshot(): ViewerState                    = viewport.snapshot()
  override def icon(): Option[javafx.scene.Node] = Some(Icons.viewer())
  override def dispose(): Unit                   = viewport.detach()

object ViewerPane:
  /** Counts constructions: if any interaction ever recreated a pane view, the "view #N" tag would
    * move on screen and betray it. The load-bearing guarantee, made visible.
    */
  private[demo] val instances = AtomicInteger(0)

private final class Viewport(initial: ViewerState, instance: Int, session: Session) extends Region:
  import Viewport.*

  private val functional = initial.hue < 120
  private val seed       = initial.name.hashCode.toLong * 31 + initial.hue.toLong
  private var slice      = initial.slice.max(0).min(Phantom.Slices - 1)
  private var xMm        = initial.x
  private var yMm        = initial.y
  private var data       = Phantom.slice(slice, seed)
  private var scrollAcc  = 0.0

  private val image   = WritableImage(Phantom.Size, Phantom.Size)
  private val argb    = new Array[Int](Phantom.Size * Phantom.Size)
  private val picture = ImageView(image)
  private val cross   = Vector.fill(4)(Line())

  private val title      = label(initial.name, "viewer-title", "mono")
  private val sliceInfo  = label("", "viewer-meta", "mono")
  private val windowInfo = label("W 400  L 40", "viewer-meta", "mono")
  private val zoomInfo   = label("100%", "viewer-meta", "mono")
  private val viewTag    = label(s"view #$instance", "viewer-instance", "mono")
  private val bar        = ImageView(colorbar(functional))
  private val barFrame   = Rectangle()
  private val barTop     = label(if functional then "z 6.0" else "240", "viewer-scale", "mono")
  private val barBottom  = label(if functional then "2.3" else "-160", "viewer-scale", "mono")
  private val frame      = Rectangle() // the black, hairline-bordered image block
  private val imageClip  = Rectangle()
  private val orient     = Vector("R", "L", "A", "P").map(label(_, "viewer-orient", "mono"))

  // image placement, recomputed by layout and read by the mouse handlers
  private var imgX = 0.0
  private var imgY = 0.0
  private var side = 0.0

  private val onLayer: InvalidationListener = _ => render()
  private val watched = session.layers.flatMap(m => Vector(m.visible, m.opacity))

  def snapshot(): ViewerState = initial.copy(slice = slice, x = xMm, y = yMm)

  def detach(): Unit = watched.foreach(_.removeListener(onLayer))

  private def zMm: Double = (slice - 30) * 1.5

  private def render(): Unit =
    def weight(id: String): Double =
      val m = session.layer(id)
      if m.visible.get then m.opacity.get else 0.0
    Phantom.composite(
      data,
      argb,
      weight("t1w"),
      if functional then weight("zstat") else 0.0,
      weight("atlas")
    )
    image.getPixelWriter.setPixels(
      0,
      0,
      Phantom.Size,
      Phantom.Size,
      PixelFormat.getIntArgbInstance,
      argb,
      0,
      Phantom.Size
    )

  private def publish(): Unit = session.ras.set(Session.ras(xMm, yMm, zMm))

  private def aim(e: MouseEvent): Unit =
    if side > 0 then
      val fx = (e.getX / side).max(0).min(1) // picture-local: the fitted image starts at 0
      val fy = (e.getY / side).max(0).min(1)
      xMm = (fx - 0.5) * SpanX
      yMm = (0.5 - fy) * SpanY
      publish()
      requestLayout()

  private def step(delta: Int): Unit =
    val next = (slice + delta).max(0).min(Phantom.Slices - 1)
    if next != slice then
      slice = next
      data = Phantom.slice(slice, seed)
      sliceInfo.setText(s"axial · slice $slice/${Phantom.Slices}")
      render()
      publish()

  override protected def computePrefWidth(height: Double): Double = 360
  override protected def computePrefHeight(width: Double): Double = 280
  override protected def computeMinWidth(height: Double): Double  = 0
  override protected def computeMinHeight(width: Double): Double  = 0

  override protected def layoutChildren(): Unit =
    val w = getWidth
    val h = getHeight
    (Vector(
      title,
      sliceInfo,
      windowInfo,
      zoomInfo,
      viewTag,
      barTop,
      barBottom
    ) ++ orient).foreach(_.autosize())

    // the image block and its colour bar are centred together on the themed surround
    val scaleW  = math.max(barTop.getWidth, barBottom.getWidth)
    val reserve = BarGap + BarWidth + LabelGap + scaleW
    side = math.max(16.0, math.min(w - 2 * Gutter - reserve, h - 2 * Gutter))
    imgX = snapPositionX((w - side - reserve) / 2)
    imgY = snapPositionY((h - side) / 2)
    picture.setFitWidth(side)
    picture.setFitHeight(side)
    picture.relocate(imgX, imgY)
    imageClip.setWidth(side)
    imageClip.setHeight(side)
    frame.setX(imgX - 0.5)
    frame.setY(imgY - 0.5)
    frame.setWidth(side + 1)
    frame.setHeight(side + 1)

    // crosshair through the cursor, on half pixels for crisp 1px strokes
    val px = snapPositionX(imgX + side * (0.5 + xMm / SpanX)) + 0.5
    val py = snapPositionY(imgY + side * (0.5 - yMm / SpanY)) + 0.5
    def seg(l: Line, x0: Double, y0: Double, x1: Double, y1: Double): Unit =
      l.setStartX(x0); l.setStartY(y0); l.setEndX(x1); l.setEndY(y1)
    seg(cross(0), imgX, py, px - Gap, py)
    seg(cross(1), px + Gap, py, imgX + side, py)
    seg(cross(2), px, imgY, px, py - Gap)
    seg(cross(3), px, py + Gap, px, imgY + side)

    // radiology corners, inside the black block
    zoomInfo.setText(s"${(side / Phantom.Size * 100).round}%")
    zoomInfo.autosize()
    val right  = imgX + side - Pad
    val bottom = imgY + side - Pad
    title.relocate(imgX + Pad, imgY + Pad)
    sliceInfo.relocate(imgX + Pad, imgY + Pad + title.getHeight + 2)
    zoomInfo.relocate(right - zoomInfo.getWidth, imgY + Pad)
    windowInfo.relocate(imgX + Pad, bottom - windowInfo.getHeight)
    viewTag.relocate(right - viewTag.getWidth, bottom - viewTag.getHeight)

    val (r, l, a, p) = (orient(0), orient(1), orient(2), orient(3))
    r.relocate(imgX + 8, imgY + side / 2 - r.getHeight - 4)
    l.relocate(imgX + side - 8 - l.getWidth, imgY + side / 2 - l.getHeight - 4)
    a.relocate(imgX + side / 2 + 6, imgY + 6)
    p.relocate(imgX + side / 2 + 6, imgY + side - 6 - p.getHeight)

    // colour bar on the surround, centred on the block, labels in theme colours
    val barH = snapSizeY(math.min(160.0, side * 0.42))
    val barX = snapPositionX(imgX + side + BarGap)
    val barY = snapPositionY(imgY + (side - barH) / 2)
    bar.setFitWidth(BarWidth)
    bar.setFitHeight(barH)
    bar.relocate(barX, barY)
    barFrame.setX(barX - 0.5)
    barFrame.setY(barY - 0.5)
    barFrame.setWidth(BarWidth + 1)
    barFrame.setHeight(barH + 1)
    barTop.relocate(barX + BarWidth + LabelGap, barY - barTop.getHeight / 2)
    barBottom.relocate(barX + BarWidth + LabelGap, barY + barH - barBottom.getHeight / 2)
  end layoutChildren

  // -- assembly (last, so every field above is initialized) ------------------------------------

  locally:
    getStyleClass.add("viewer"): Unit
    picture.setCursor(Cursor.CROSSHAIR)
    picture.setSmooth(true)
    imageClip.setArcWidth(8)
    imageClip.setArcHeight(8)
    picture.setClip(imageClip)
    frame.setArcWidth(8)
    frame.setArcHeight(8)
    frame.getStyleClass.add("viewer-frame"): Unit
    frame.setManaged(false)
    bar.setPreserveRatio(false)
    bar.setSmooth(true)
    barFrame.getStyleClass.add("viewer-bar-frame"): Unit
    barFrame.setManaged(false)
    cross.foreach: l =>
      l.getStyleClass.add("viewer-crosshair"): Unit
      l.setManaged(false)
    val clip = Rectangle()
    clip.widthProperty.bind(widthProperty)
    clip.heightProperty.bind(heightProperty)
    setClip(clip)
    getChildren.addAll(frame, picture): Unit
    cross.foreach(getChildren.add(_): Unit)
    getChildren.addAll(
      bar,
      barFrame,
      barTop,
      barBottom,
      title,
      sliceInfo,
      windowInfo,
      zoomInfo,
      viewTag
    ): Unit
    orient.foreach(getChildren.add(_): Unit)
    // overlays never steal the pointer from the image underneath
    (cross ++ orient ++ Vector(
      title,
      sliceInfo,
      windowInfo,
      zoomInfo,
      viewTag
    )).foreach(_.setMouseTransparent(true))
    sliceInfo.setText(s"axial · slice $slice/${Phantom.Slices}")
    picture.setOnMousePressed(aim)
    picture.setOnMouseDragged(aim)
    setOnScroll: e =>
      scrollAcc += e.getDeltaY
      while math.abs(scrollAcc) >= ScrollStep do
        step(if scrollAcc > 0 then 1 else -1)
        scrollAcc -= math.signum(scrollAcc) * ScrollStep
      e.consume()
    watched.foreach(_.addListener(onLayer))
    render()
end Viewport

private object Viewport:
  private val Gutter     = 16.0
  private val BarGap     = 16.0
  private val LabelGap   = 6.0
  private val Pad        = 8.0
  private val Gap        = 0.0   // continuous: a gap reads as a grey square over tissue
  private val BarWidth   = 7.0
  private val SpanX      = 180.0 // field of view, mm
  private val SpanY      = 216.0
  private val ScrollStep = 24.0

  /** A 1×128 legend: hot colormap for functional views, grayscale otherwise; top is maximum. */
  private def colorbar(functional: Boolean): WritableImage =
    val n   = 128
    val img = WritableImage(1, n)
    val pw  = img.getPixelWriter
    for i <- 0 until n do
      val t         = 1.0 - i / (n - 1.0)
      val (r, g, b) = if functional then Phantom.hot(0.3 + 0.7 * t) else (t, t, t)
      pw.setColor(0, i, javafx.scene.paint.Color.color(r, g, b))
    img
