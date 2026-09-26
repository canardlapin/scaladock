package scaladock.fx

import javafx.scene.layout.Region
import scaladock.*

/** The root of one window's dock: a flat set of group views and divider strips positioned by core
  * geometry. Splits have no scene-graph presence — they exist only as arithmetic.
  */
private[fx] final class DockRegion(settings: LayoutSettings) extends Region:
  getStyleClass.addAll("dock", "dock-layout") // .dock carries the theme variables

  locally:
    val css = getClass.getResource("/scaladock/dock.css")
    if css != null then getStylesheets.add(css.toExternalForm): Unit

  private var themeSheet: Option[String] = None

  // `:inactive` while this region's window lacks OS focus (only once it is in a focusable window)
  private val onWindowFocus: javafx.beans.value.ChangeListener[java.lang.Boolean] =
    (_, _, focused) => pseudoClassStateChanged(pseudo.Inactive, !focused)
  locally:
    sceneProperty.addListener: (_, oldScene, scene) =>
      Option(
        oldScene
      ).flatMap(sc => Option(sc.getWindow)).foreach(_.focusedProperty.removeListener(onWindowFocus))
      Option(scene).foreach: sc =>
        sc.windowProperty.addListener: (_, oldW, w) =>
          Option(oldW).foreach(_.focusedProperty.removeListener(onWindowFocus))
          Option(w).foreach: win =>
            win.focusedProperty.addListener(onWindowFocus)
            pseudoClassStateChanged(pseudo.Inactive, !win.isFocused)

  /** Swap the theme stylesheet (appended after the base sheet, so its variables win). */
  private[fx] def setThemeSheet(url: Option[String]): Unit =
    themeSheet.foreach(s => getStylesheets.remove(s): Unit)
    themeSheet = url
    url.foreach(s => getStylesheets.add(s): Unit)

  private var root: Option[Node]           = None
  private var maximized: Option[NodeId]    = None
  private var minimized: Set[NodeId]       = Set.empty
  private[fx] var geometry: LayoutGeometry = LayoutGeometry.empty

  /** The drop-zone highlight, always the top child; positioned by [[showIndicator]]. */
  private val indicator = new Region
  indicator.getStyleClass.add("dock-drop-indicator")
  indicator.setMouseTransparent(true)
  indicator.setVisible(false)

  /** What an empty window shows: an invitation, not a void. */
  private val defaultPlaceholder =
    val icon = new Region
    icon.getStyleClass.addAll("dock-icon", "empty-layout")
    val title = new javafx.scene.control.Label("No open panels")
    title.getStyleClass.add("dock-empty-title")
    val hint = new javafx.scene.control.Label("Drag a tab here, or open a panel")
    hint.getStyleClass.add("dock-empty-hint")
    val box = new javafx.scene.layout.VBox(icon, title, hint)
    box.getStyleClass.add("dock-empty")
    box.setAlignment(javafx.geometry.Pos.CENTER)
    box.setMouseTransparent(true)
    box.setVisible(false)
    box.setManaged(false)
    box
  private var placeholder: javafx.scene.Node = defaultPlaceholder
  getChildren.addAll(placeholder, indicator)

  /** Replace what this window shows when empty (`None` restores the built-in placeholder). */
  private[fx] def setPlaceholder(node: Option[javafx.scene.Node]): Unit =
    val next = node.getOrElse(defaultPlaceholder)
    if !(next eq placeholder) then
      getChildren.remove(placeholder)
      placeholder = next
      getChildren.add(next)
      raiseOverlay()
      requestLayout()

  private var indicatorRect: Option[Rect] = None // where the highlight is heading
  private var shownRect: Option[Rect]     = None // where it is drawn this frame

  /** Eases the highlight between zones instead of teleporting (exponential approach, ~60 ms). */
  private val glide = new javafx.animation.AnimationTimer:
    private var last = 0L
    // a glide interrupted by hideIndicator must not resume with one enormous time step
    override def start(): Unit =
      last = 0L
      super.start()
    def handle(now: Long): Unit =
      val dt = if last == 0L then 1.0 / 60 else (now - last) / 1e9
      last = now
      (shownRect, indicatorRect) match
        case (Some(cur), Some(target)) =>
          val k                         = 1 - math.exp(-dt / DockRegion.GlideTau)
          def mix(a: Double, b: Double) = if math.abs(b - a) < 0.5 then b else a + (b - a) * k
          val next = Rect(
            mix(cur.x, target.x),
            mix(cur.y, target.y),
            mix(cur.width, target.width),
            mix(cur.height, target.height)
          )
          shownRect = Some(next)
          indicator.resizeRelocate(next.x, next.y, next.width, next.height)
          if next == target then
            last = 0L; stop()
        case _ => last = 0L; stop()

  /** A live divider drag: lay one split out with these cell sizes until the drag commits. Sizes
    * only, matched by index — never child subtrees, which could go stale.
    */
  private var splitPreview: Option[(NodeId, Vector[Size])] = None

  private[fx] def previewSplit(p: Option[(NodeId, Vector[Size])]): Unit =
    splitPreview = p
    requestLayout()

  /** Adopt this window's tree; positioning happens in the next layout pass. */
  def show(
      nextRoot: Option[Node],
      nextMaximized: Option[NodeId],
      nextMinimized: Set[NodeId]
  ): Unit =
    root = nextRoot
    maximized = nextMaximized
    minimized = nextMinimized
    splitPreview = None // any state change supersedes a live divider preview
    requestLayout()

  private[fx] def addView(n: javafx.scene.Node): Unit =
    getChildren.add(n): Unit
    raiseOverlay()

  private[fx] def removeView(n: javafx.scene.Node): Unit = getChildren.remove(n): Unit

  /** Show the drop preview. A `slot` (a header drop) is tab-shaped and flush with the strip; every
    * other zone is an inset, rounded region.
    */
  private[fx] def showIndicator(r: Rect, slot: Boolean = false): Unit =
    indicator.pseudoClassStateChanged(pseudo.Slot, slot)
    val target = if slot then r else DockRegion.inset(r, DockRegion.IndicatorInsetPx)
    if !indicator.isVisible || shownRect.isEmpty then
      shownRect = Some(target)
      indicator.resizeRelocate(target.x, target.y, target.width, target.height)
      indicator.setOpacity(0)
      val fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(90), indicator)
      fade.setToValue(1)
      fade.play()
    indicatorRect = Some(target)
    indicator.setVisible(true)
    if shownRect != indicatorRect then glide.start()
    requestLayout()

  private[fx] def hideIndicator(): Unit =
    indicatorRect = None
    shownRect = None
    glide.stop()
    indicator.setVisible(false)

  /** Where the drop preview is heading, in screen coordinates (the drag chip keeps clear of it). */
  private[fx] def previewScreenBounds: Option[javafx.geometry.Bounds] =
    indicatorRect
      .filter(_ => indicator.isVisible)
      .flatMap(r =>
        Option(localToScreen(new javafx.geometry.BoundingBox(r.x, r.y, r.width, r.height)))
      )

  /** A drag is in flight anywhere: quiet this window's chrome and let the empty state recede. */
  private[fx] def setDragging(on: Boolean): Unit =
    pseudoClassStateChanged(pseudo.Dragging, on)
    placeholder.setOpacity(if on then 0.35 else 1.0)

  /** Restore the drop overlays to the top of the stack after z-order changes. */
  private[fx] def raiseOverlay(): Unit =
    placeholder.toFront()
    indicator.toFront()

  override def layoutChildren(): Unit =
    val viewport = Rect(0, 0, getWidth, getHeight)
    def withSizes(n: Node, id: NodeId, sizes: Vector[Size]): Node = n match
      case sp: Node.Split if sp.id == id && sp.cells.length == sizes.length =>
        sp.copy(cells = sp.cells.zip(sizes).map((c, sz) => c.copy(size = sz)))
      case sp: Node.Split =>
        sp.copy(cells = sp.cells.map(c => c.copy(node = withSizes(c.node, id, sizes))))
      case g => g
    val laidOut = splitPreview.fold(root)((id, sizes) => root.map(withSizes(_, id, sizes)))
    geometry = sizing.geometry(laidOut, viewport, settings, maximized, minimized)
    val dividerRects =
      geometry.dividers.map(d => (d.split, d.index) -> d).toMap

    // snap manually-placed children to the pixel grid (Region only does this for its own
    // layout algorithms): fractional rects render as blurry 1px borders on scaled displays
    def snapped(n: javafx.scene.Node, r: Rect): Unit =
      val x = snapPositionX(r.x)
      val y = snapPositionY(r.y)
      n.resizeRelocate(x, y, snapPositionX(r.right) - x, snapPositionY(r.bottom) - y)

    getChildren.forEach:
      case gv: GroupView =>
        geometry.groups.get(gv.nodeId) match
          case Some(gg) if visibleUnderMaximise(gv.nodeId) =>
            gv.setVisible(true)
            snapped(gv, gg.bounds)
          case _ =>
            gv.setVisible(false)
      case dv: DividerView =>
        dividerRects.get((dv.splitId, dv.index)) match
          case Some(d) if geometry.maximized.isEmpty =>
            dv.setVisible(true)
            dv.setAxis(d.axis)
            snapped(dv, d.bounds)
          case _ =>
            dv.setVisible(false)
      case other if other eq indicator =>
        shownRect.foreach(r => indicator.resizeRelocate(r.x, r.y, r.width, r.height))
      case other if other eq placeholder =>
        placeholder.setVisible(root.isEmpty)
        placeholder match
          case r: javafx.scene.layout.Region => r.resizeRelocate(0, 0, getWidth, getHeight)
          case n =>
            n.autosize();
            n.relocate(
              (getWidth - n.getLayoutBounds.getWidth) / 2,
              (getHeight - n.getLayoutBounds.getHeight) / 2
            )
      case other =>
        other.resizeRelocate(0, 0, getWidth, getHeight) // overlay layers fill the viewport
  end layoutChildren

  private def visibleUnderMaximise(id: NodeId): Boolean =
    geometry.maximized.forall(_ == id)
end DockRegion

object DockRegion:
  /** The drop highlight sits this far inside its zone, so neighbouring chrome stays readable. */
  val IndicatorInsetPx: Double = 4

  /** Time constant of the highlight's glide between zones, in seconds. */
  val GlideTau: Double = 0.035

  private[fx] def inset(r: Rect, by: Double): Rect =
    val dx = math.min(by, r.width / 4)
    val dy = math.min(by, r.height / 4)
    Rect(r.x + dx, r.y + dy, math.max(0, r.width - 2 * dx), math.max(0, r.height - 2 * dy))
