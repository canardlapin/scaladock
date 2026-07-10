package scaladock.fx

import javafx.scene.layout.Region
import scaladock.*

/** The root of one window's dock: a flat set of group views and divider strips positioned by core
  * geometry. Splits have no scene-graph presence — they exist only as arithmetic.
  */
private[fx] final class DockRegion(settings: LayoutSettings) extends Region:
  getStyleClass.add("dock-layout")

  locally:
    val css = getClass.getResource("/scaladock/dock.css")
    if css != null then getStylesheets.add(css.toExternalForm): Unit

  private var themeSheet: Option[String] = None

  /** Swap the theme stylesheet (appended after the base sheet, so its variables win). */
  private[fx] def setThemeSheet(url: Option[String]): Unit =
    themeSheet.foreach(s => getStylesheets.remove(s): Unit)
    themeSheet = url
    url.foreach(s => getStylesheets.add(s): Unit)

  private var root: Option[Node]           = None
  private var maximized: Option[NodeId]    = None
  private[fx] var geometry: LayoutGeometry = LayoutGeometry.empty

  /** The drop-zone highlight, always the top child; positioned by [[showIndicator]]. */
  private val indicator = new Region
  indicator.getStyleClass.add("dock-drop-indicator")
  indicator.setMouseTransparent(true)
  indicator.setVisible(false)
  getChildren.add(indicator)

  private var indicatorRect: Option[Rect] = None

  /** Adopt this window's tree; positioning happens in the next layout pass. */
  def show(nextRoot: Option[Node], nextMaximized: Option[NodeId]): Unit =
    root = nextRoot
    maximized = nextMaximized
    requestLayout()

  private[fx] def addView(n: javafx.scene.Node): Unit =
    getChildren.add(n): Unit
    indicator.toFront()

  private[fx] def removeView(n: javafx.scene.Node): Unit = getChildren.remove(n): Unit

  private[fx] def showIndicator(r: Rect): Unit =
    indicatorRect = Some(r)
    indicator.setVisible(true)
    requestLayout()

  private[fx] def hideIndicator(): Unit =
    indicatorRect = None
    indicator.setVisible(false)

  override def layoutChildren(): Unit =
    val viewport = Rect(0, 0, getWidth, getHeight)
    geometry = sizing.geometry(root, viewport, settings, maximized)
    val dividerRects =
      geometry.dividers.map(d => (d.split, d.index) -> d).toMap

    getChildren.forEach:
      case gv: GroupView =>
        geometry.groups.get(gv.nodeId) match
          case Some(gg) if visibleUnderMaximise(gv.nodeId) =>
            gv.setVisible(true)
            gv.resizeRelocate(gg.bounds.x, gg.bounds.y, gg.bounds.width, gg.bounds.height)
          case _ =>
            gv.setVisible(false)
      case dv: DividerView =>
        dividerRects.get((dv.splitId, dv.index)) match
          case Some(d) if geometry.maximized.isEmpty =>
            dv.setVisible(true)
            dv.setAxis(d.axis)
            dv.resizeRelocate(d.bounds.x, d.bounds.y, d.bounds.width, d.bounds.height)
          case _ =>
            dv.setVisible(false)
      case other if other eq indicator =>
        indicatorRect.foreach(r => indicator.resizeRelocate(r.x, r.y, r.width, r.height))
      case other =>
        other.resizeRelocate(0, 0, getWidth, getHeight) // overlay layers fill the viewport

  private def visibleUnderMaximise(id: NodeId): Boolean =
    geometry.maximized.forall(_ == id)
end DockRegion
