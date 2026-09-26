package scaladock.fx

import javafx.event.EventHandler
import javafx.scene.control.Label
import javafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import javafx.scene.layout.{HBox, StackPane}
import javafx.stage.Popup
import scaladock.*

/** The drag gesture, golden-layout style: arm on press, start after a 10px move, float a ghost
  * chip, hit-test a screen-space snapshot of drop areas taken at drag start (across every window,
  * front-most first), preview the hovered zone, and commit exactly one transition on release.
  *
  * The layout does not change while dragging: the source tab merely dims. Each zone's preview is
  * honest by construction — it is the landing rect of the dragged pane in the geometry of the state
  * the drop would produce, computed by running the pure `edit.drop` ahead of time. The last
  * previewed zone stays lit and wins a near-miss release; ESC — or a drag that never found a zone —
  * leaves everything as it was. Every path out of a drag runs through one idempotent cleanup door,
  * so an exception can never leave a dimmed tab or a stray ghost behind.
  */
private[fx] final class DragController(dock: Dock, region: DockRegion):

  private val settings = dock.settings

  private enum Phase:
    case Idle
    case Armed(pane: PaneId, startX: Double, startY: Double)
    case Dragging(session: Session)

  /** One window's screen-attached drop snapshot. */
  private final class Surface(
      val region: DockRegion,
      val window: Option[WindowId],
      val areas: Vector[DropArea]
  )

  private final class Session(
      val pane: Pane,
      val sourced: Boolean,          // fabricated by a drag source: not yet in the layout
      val surfaces: Vector[Surface], // front-most first
      val ghost: Option[GhostPopup],
      val filteredScenes: Vector[javafx.scene.Scene] // the scenes the ESC filter was added to
  ):
    var lastValid: Option[(Surface, DropArea)] = None
    var openedGap: Option[NodeId]              = None // the header showing a slot
    var lastTarget: Option[DropTarget]         = None // what the preview last showed
    var closed: Boolean                        = false
    val previews: collection.mutable.Map[DropArea, Option[Rect]] = collection.mutable.Map.empty

  private var phase: Phase = Phase.Idle

  private val escFilter: EventHandler[KeyEvent] = e =>
    if e.getCode == KeyCode.ESCAPE then
      cancel()
      e.consume()

  // -- wiring from tabs and drag sources ------------------------------------------------------

  def tabPressed(pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Idle => phase = Phase.Armed(pane, e.getScreenX, e.getScreenY)
      case _          => ()

  def tabDragged(pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Armed(armed, sx, sy) if armed == pane =>
        if math.abs(e.getScreenX - sx) > DragController.ThresholdPx
          || math.abs(e.getScreenY - sy) > DragController.ThresholdPx
        then startDrag(pane, e)
      case Phase.Dragging(_) => moveDrag(e)
      case _                 => ()

  def tabReleased(@scala.annotation.unused pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(_) => completeDrop(e)
      case _                 => phase = Phase.Idle

  /** Abort any in-flight drag; the layout was never touched, so nothing needs restoring. */
  def cancel(): Unit =
    phase match
      case Phase.Dragging(session) => endSession(session)
      case _                       => phase = Phase.Idle

  /** Arm an external node as a factory of new panes (golden-layout's DragSource). */
  def installSource(handle: javafx.scene.Node)(make: () => PaneDef): Subscription =
    var pressed: Option[(Double, Double)] = None
    val onPress: EventHandler[MouseEvent] = e => pressed = Some((e.getScreenX, e.getScreenY))
    val onDrag: EventHandler[MouseEvent] = e =>
      phase match
        case Phase.Idle =>
          pressed.foreach: (sx, sy) =>
            if math.abs(e.getScreenX - sx) > DragController.ThresholdPx
              || math.abs(e.getScreenY - sy) > DragController.ThresholdPx
            then
              val pane = make().toPane
              // no home: a sourced pane that lands nowhere is simply not created
              beginSession(pane, sourced = true, e)
        case Phase.Dragging(_) => moveDrag(e)
        case _                 => ()
    val onRelease: EventHandler[MouseEvent] = e =>
      pressed = None
      phase match
        case Phase.Dragging(_) => completeDrop(e)
        case _                 => ()
    handle.addEventHandler(MouseEvent.MOUSE_PRESSED, onPress)
    handle.addEventHandler(MouseEvent.MOUSE_DRAGGED, onDrag)
    handle.addEventHandler(MouseEvent.MOUSE_RELEASED, onRelease)
    () =>
      handle.removeEventHandler(MouseEvent.MOUSE_PRESSED, onPress)
      handle.removeEventHandler(MouseEvent.MOUSE_DRAGGED, onDrag)
      handle.removeEventHandler(MouseEvent.MOUSE_RELEASED, onRelease)

  // -- the drag itself -------------------------------------------------------------------------

  private def startDrag(paneId: PaneId, e: MouseEvent): Unit =
    dock.state.findPane(paneId) match
      case None => phase = Phase.Idle
      case Some(pane) =>
        dock.markDragging(Some(paneId))
        try beginSession(pane, sourced = false, e)
        catch
          case t: Throwable =>
            dock.markDragging(None)
            phase = Phase.Idle
            throw t

  private def beginSession(pane: Pane, sourced: Boolean, e: MouseEvent): Unit =
    val surfaces = dock.dragSurfaces.map: (windowRef, surfaceRegion, root) =>
      surfaceRegion.layout()
      val viewport = Rect(0, 0, surfaceRegion.getWidth, surfaceRegion.getHeight)
      Surface(
        surfaceRegion,
        windowRef,
        sizing.dropAreas(root, surfaceRegion.geometry, viewport, windowRef, settings)
      )
    // ESC must work whichever window holds keyboard focus
    val scenes = surfaces.flatMap(s => Option(s.region.getScene)).distinct
    scenes.foreach(_.addEventFilter(KeyEvent.KEY_PRESSED, escFilter))
    // the ghost is a Popup: it follows the pointer across windows without stealing focus
    val ghost = Option(region.getScene).map(_.getWindow).filter(_ != null).map: owner =>
      val g = GhostPopup(pane.title, dock.iconOf(pane.id), dock.themeStylesheet)
      g.showAt(owner, e.getScreenX, e.getScreenY)
      g
    phase = Phase.Dragging(Session(pane, sourced, surfaces, ghost, scenes))
    dock.dragActive(true)
    dock.publish(DockEvent.DragStarted(pane.id))

  /** The front-most surface (and area) under the pointer, if any. */
  private def hitAt(session: Session, screenX: Double, screenY: Double)
      : Option[(Surface, DropArea)] =
    session.surfaces.iterator
      .flatMap: surface =>
        Option(surface.region.screenToLocal(screenX, screenY))
          .map(p => Point(p.getX, p.getY))
          .filter(p => p.x >= 0 && p.y >= 0)
          .filter(p => p.x < surface.region.getWidth && p.y < surface.region.getHeight)
          .flatMap(p => sizing.hitTest(surface.areas, p))
          .map(area => (surface, area))
      .nextOption()

  private def moveDrag(e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(session) =>
        hitAt(session, e.getScreenX, e.getScreenY) match
          case Some((surface, area)) =>
            session.lastValid = Some((surface, area))
            session.surfaces.foreach(s => if !(s eq surface) then s.region.hideIndicator())
            preview(session, surface, area, e.getScreenX)
          case None =>
            // the last valid zone still wins a near-miss release, so its highlight stays lit
            if session.lastValid.isEmpty then session.surfaces.foreach(_.region.hideIndicator())
        // place the chip after the preview, so it can keep clear of the preview's edges
        val avoid = session.surfaces.iterator.flatMap(_.region.previewScreenBounds).nextOption()
        session.ghost.foreach(_.moveTo(e.getScreenX, e.getScreenY, avoid))
      case _ => ()

  /** Light up where the pane would actually land. */
  private def preview(session: Session, surface: Surface, area: DropArea, screenX: Double): Unit =
    // leaving a header (for another zone or another header) closes the slot it opened
    val header = area.zone match
      case DropZone.HeaderOf(g) => Some(g)
      case _                    => None
    if session.openedGap != header then closeGap(session)
    // a near-miss release commits exactly what was last previewed, not a re-derived index
    session.lastTarget = targetOf(session, area, screenX)
    area.zone match
      case DropZone.HeaderOf(group) =>
        targetOf(session, area, screenX) match
          case None => surface.region.hideIndicator()
          case Some(_) =>
            val slot =
              for
                gv <- dock.groupViewFor(group)
                hb <- gv.headerScreenBounds
                index = gv.tabIndexAt(screenX)
                if hb.getHeight > 1 // a header replaced by a window's title bar has none
                gapX <- gv.gapScreenX(index)
                p    <- Option(surface.region.screenToLocal(gapX, hb.getMinY))
              yield
                val w = slotWidth(session)
                gv.previewInsertion(Some(index -> w))
                session.openedGap = Some(group)
                Rect(p.getX, p.getY, w, hb.getHeight - 1) // stop at the strip's hairline
            slot match
              case Some(r) => surface.region.showIndicator(r, slot = true)
              case None    => surface.region.showIndicator(area.highlight)
      case DropZone.WindowEdge(_, _) =>
        // a window-edge drop halves the edge cell, not the window: show the true landing rect
        if session.lastTarget.isEmpty then surface.region.hideIndicator()
        else
          val landing = session.previews.getOrElseUpdate(area, landingRect(session, surface, area))
          surface.region.showIndicator(landing.getOrElse(area.highlight))
      case _ =>
        if targetOf(session, area, screenX = 0).isDefined then
          surface.region.showIndicator(area.highlight)
        else surface.region.hideIndicator() // the sole tab over its own group: nothing to do

  private def closeGap(session: Session, immediate: Boolean = false): Unit =
    session.openedGap.foreach: g =>
      dock.groupViewFor(g).foreach(gv =>
        if immediate then gv.resetInsertion() else gv.previewInsertion(None)
      )
    session.openedGap = None

  /** The slot a header drop opens: the dragged tab's own width when it has one. */
  private def slotWidth(session: Session): Double =
    dock.state.groupOf(session.pane.id)
      .flatMap(g => dock.groupViewFor(g.id))
      .flatMap(_.tabWidth(session.pane.id))
      .getOrElse(DragController.DefaultSlotPx)

  /** The dragged pane's group rect in the geometry of the state this drop would produce. */
  private def landingRect(session: Session, surface: Surface, area: DropArea): Option[Rect] =
    targetOf(session, area, screenX = 0).flatMap: target =>
      val next = edit.drop(dock.state, session.pane, target)
      val root = surface.window match
        case None    => next.root
        case Some(w) => next.floating.find(_.window == w).map(_.root)
      val viewport = Rect(0, 0, surface.region.getWidth, surface.region.getHeight)
      val geom     = sizing.geometry(root, viewport, settings, next.maximized, next.minimized)
      next.groupOf(session.pane.id).flatMap(g => geom.groups.get(g.id)).map(_.bounds)

  private def completeDrop(e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(session) =>
        try
          val target = hitAt(session, e.getScreenX, e.getScreenY) match
            case Some((_, area)) => targetOf(session, area, e.getScreenX)
            case None            => session.lastTarget
          val stillThere = session.sourced || dock.state.findPane(session.pane.id).isDefined
          // settle any opened slot before the layout changes, or its tabs would jump and slide back
          closeGap(session, immediate = true)
          target match
            case Some(target) if stillThere =>
              dock.update(edit.drop(_, session.pane, target))
              dock.publish(DockEvent.Dropped(session.pane.id, target))
            case _ => () // a miss: a tab stays put; a sourced pane simply never existed
        finally endSession(session)
      case _ => ()

  /** The single, idempotent cleanup door: ghost, indicators, filters, dimmed tab, phase. */
  private def endSession(session: Session): Unit =
    if !session.closed then
      session.closed = true
      session.ghost.foreach(_.hide())
      session.surfaces.foreach(_.region.hideIndicator())
      closeGap(session)
      dock.dragActive(false)
      session.filteredScenes.foreach(_.removeEventFilter(KeyEvent.KEY_PRESSED, escFilter))
      dock.markDragging(None)
    phase = Phase.Idle

  /** Resolve a hovered zone into the final drop, or `None` when the drop would change nothing (a
    * group's only tab dropped onto its own group). Header hits compute the tab-insertion index from
    * the pointer's x; reordering within the source group accounts for the tab's own removal.
    */
  private def targetOf(session: Session, area: DropArea, screenX: Double): Option[DropTarget] =
    val source    = Option.unless(session.sourced)(dock.state.groupOf(session.pane.id)).flatten
    val soleTabOf = source.filter(_.tabs.length == 1).map(_.id)
    // the sole tab of a window's only group, dropped on that same window: nothing would change
    // (and edit.drop would dissolve the window first, sending the pane elsewhere)
    def wholeWindow(window: Option[WindowId]): Boolean =
      val root = window match
        case None    => dock.state.root
        case Some(w) => dock.state.floating.find(_.window == w).map(_.root)
      soleTabOf.exists(g => root.exists(_.id == g))
    area.zone match
      case DropZone.HeaderOf(group) =>
        if soleTabOf.contains(group) then None
        else
          val raw = dock.groupViewFor(group).fold(0)(_.tabIndexAt(screenX))
          val own = source.filter(_.id == group).map(_.tabs.indexWhere(_.id == session.pane.id))
          Some(DropTarget.IntoGroup(group, own.filter(_ < raw).fold(raw)(_ => raw - 1)))
      case DropZone.BodyEdge(group, edge) =>
        Option.unless(soleTabOf.contains(group))(DropTarget.Beside(group, edge))
      case DropZone.WindowEdge(window, edge) =>
        Option.unless(wholeWindow(window))(DropTarget.AtWindowEdge(window, edge))
      case DropZone.EmptyWindow(window) =>
        Option.unless(wholeWindow(window))(DropTarget.AtWindowEdge(window, Edge.Right))
end DragController

object DragController:
  /** Movement (in any axis) that turns a press into a drag — golden-layout's 10px. */
  val ThresholdPx: Double = 10

  /** Header slot width for a pane with no tab yet (a drag-source pane). */
  val DefaultSlotPx: Double = 110

/** The floating drag chip: a Popup, not a Stage — a Popup never takes keyboard focus (so ESC keeps
  * working in the dragged-from window) yet still crosses window boundaries. It carries the pane's
  * icon and title at full legibility, lifted by a soft shadow.
  */
private[fx] final class GhostPopup(
    title: String,
    icon: Option[javafx.scene.Node],
    theme: Option[String]
) extends Popup:
  setAutoFix(false) // follow the pointer honestly, even near screen edges
  setAutoHide(false)

  private val titleLabel = new Label(title)
  titleLabel.getStyleClass.add("dock-ghost-title")

  private val chip = new HBox()
  icon.foreach: i =>
    val holder = new StackPane(i)
    holder.getStyleClass.add("dock-tab-icon")
    chip.getChildren.add(holder): Unit
  chip.getChildren.add(titleLabel)
  chip.getStyleClass.add("dock-ghost-chip")

  // .dock carries the theme variables — the ghost is not a descendant of any dock window, so its
  // root must hold the marker class itself; the root's padding leaves room for the shadow
  private val box = new StackPane(chip)
  box.getStyleClass.addAll("dock", "dock-ghost")

  locally:
    box.setMouseTransparent(true)
    val css = getClass.getResource("/scaladock/dock.css")
    if css != null then box.getStylesheets.add(css.toExternalForm): Unit
    theme.foreach(sheet => box.getStylesheets.add(sheet): Unit)
    getContent.add(box): Unit

  def showAt(owner: javafx.stage.Window, screenX: Double, screenY: Double): Unit =
    show(owner, screenX + GhostPopup.OffsetPx, screenY + GhostPopup.OffsetPx)
    moveTo(screenX, screenY)

  /** Follow the pointer. The chip sits below-right of it, unless that would straddle an edge of the
    * drop preview — then it takes the first corner (below-left, above-right, above-left) that lies
    * wholly inside or wholly outside the preview, so no border ever cuts through it.
    */
  def moveTo(screenX: Double, screenY: Double, avoid: Option[javafx.geometry.Bounds] = None): Unit =
    val cw      = chip.getWidth.max(chip.prefWidth(-1))
    val ch      = chip.getHeight.max(chip.prefHeight(-1))
    val gap     = GhostPopup.OffsetPx
    val corners = Vector((gap, gap), (-gap - cw, gap), (gap, -gap - ch), (-gap - cw, -gap - ch))
    def straddles(dx: Double, dy: Double): Boolean = avoid.exists: b =>
      val (x0, y0, x1, y1) = (screenX + dx, screenY + dy, screenX + dx + cw, screenY + dy + ch)
      val m                = GhostPopup.ClearPx
      val inside =
        x0 >= b.getMinX + m && x1 <= b.getMaxX - m && y0 >= b.getMinY + m && y1 <= b.getMaxY - m
      val outside =
        x1 <= b.getMinX - m || x0 >= b.getMaxX + m || y1 <= b.getMinY - m || y0 >= b.getMaxY + m
      !inside && !outside
    // a preview too narrow (or short) to hold the chip: step just outside it, level with the
    // pointer — to its right when that fits on the pointer's screen, else to its left
    def outside: (Double, Double) = avoid.fold(corners.head): b =>
      val m = GhostPopup.ClearPx
      val screenRight = javafx.stage.Screen
        .getScreensForRectangle(screenX, screenY, 1, 1)
        .toArray
        .collectFirst { case sc: javafx.stage.Screen => sc.getVisualBounds.getMaxX }
        .getOrElse(Double.MaxValue)
      if b.getMaxX + m + cw <= screenRight then (b.getMaxX + m - screenX, gap)
      else (b.getMinX - m - cw - screenX, gap)
    val (dx, dy) = corners.find((x, y) => !straddles(x, y)).getOrElse(outside)
    // the chip sits inside the popup root's shadow padding
    val pad = box.getPadding
    setX(screenX + dx - pad.getLeft)
    setY(screenY + dy - pad.getTop)
end GhostPopup

object GhostPopup:
  val OffsetPx: Double = 15

  /** Clearance the chip keeps from any edge of the drop preview. */
  val ClearPx: Double = 6
