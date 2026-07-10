package scaladock.fx

import javafx.event.EventHandler
import javafx.scene.SnapshotParameters
import javafx.scene.control.Label
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import javafx.scene.layout.VBox
import javafx.stage.Popup
import scaladock.*

/** The drag gesture, golden-layout style: arm on press, start after a 10px move, float a ghost
  * popup, hit-test a screen-space snapshot of drop areas taken at drag start (across every window,
  * front-most first), highlight the hovered zone, and commit exactly one transition on release. The
  * last highlighted zone stays lit and wins a near-miss release; ESC — or a drag that never found a
  * zone at all — returns the pane home. Every path out of a drag runs through one idempotent
  * cleanup door, so an exception can never leave a parked pane or a stray ghost behind.
  */
private[fx] final class DragController(dock: Dock, region: DockRegion):

  private val settings = dock.settings

  private enum Phase:
    case Idle
    case Armed(pane: PaneId, home: DropTarget, startX: Double, startY: Double)
    case Dragging(session: Session)

  /** One window's screen-attached drop snapshot. */
  private final class Surface(
      val region: DockRegion,
      val areas: Vector[DropArea]
  )

  private final class Session(
      val pane: Pane,
      val home: Option[DropTarget],  // None: a sourced pane that lands nowhere is discarded
      val surfaces: Vector[Surface], // front-most first
      val ghost: Option[GhostPopup],
      val filteredScenes: Vector[javafx.scene.Scene] // the scenes the ESC filter was added to
  ):
    var lastValid: Option[(Surface, DropArea)] = None
    var closed: Boolean                        = false

  private var phase: Phase = Phase.Idle

  private val escFilter: EventHandler[KeyEvent] = e =>
    if e.getCode == KeyCode.ESCAPE then
      cancel()
      e.consume()

  // -- wiring from tabs and drag sources ------------------------------------------------------

  def tabPressed(pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Idle =>
        phase = Phase.Armed(pane, homeOf(pane), e.getScreenX, e.getScreenY)
      case _ => ()

  def tabDragged(pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Armed(armed, home, sx, sy) if armed == pane =>
        if math.abs(e.getScreenX - sx) > DragController.ThresholdPx
          || math.abs(e.getScreenY - sy) > DragController.ThresholdPx
        then startDrag(pane, home, e)
      case Phase.Dragging(_) => moveDrag(e)
      case _                 => ()

  def tabReleased(pane: PaneId, e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(_) => completeDrop(e)
      case _                 => phase = Phase.Idle

  /** Abort any in-flight drag, returning the pane home. Safe to call at any time. */
  def cancel(): Unit =
    phase match
      case Phase.Dragging(session) =>
        try session.home.foreach(home => dock.update(edit.drop(_, session.pane, home)))
        finally endSession(session)
      case _ => phase = Phase.Idle

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
              beginSession(pane, home = None, snapshot = None, e)
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

  /** Where a detached pane returns if the drop misses everything. */
  private def homeOf(pane: PaneId): DropTarget =
    dock.state.groupOf(pane) match
      case Some(g) => DropTarget.IntoGroup(g.id, g.tabs.indexWhere(_.id == pane).max(0))
      case None    => DropTarget.AtWindowEdge(None, Edge.Right)

  private def startDrag(paneId: PaneId, home: DropTarget, e: MouseEvent): Unit =
    val snapshot = dock.nodeOf(paneId).filter(_.getScene != null).map: node =>
      node.snapshot(new SnapshotParameters, null)
    dock.state.findPane(paneId) match
      case None => phase = Phase.Idle
      case Some(pane) =>
        dock.park(paneId) // the view outlives its detached time in the tree
        try
          dock.update(s => edit.detach(s, paneId)._1)
          beginSession(pane, Some(home), snapshot, e)
        catch
          case t: Throwable =>
            // a throwing event subscriber must not strand the pane outside the layout
            dock.update(edit.drop(_, pane, home))
            dock.unpark(paneId)
            phase = Phase.Idle
            throw t

  private def beginSession(
      pane: Pane,
      home: Option[DropTarget],
      snapshot: Option[Image],
      e: MouseEvent
  ): Unit =
    // refresh geometry after the detach, then snapshot every window's drop areas
    val surfaces = dock.dragSurfaces.map: (windowRef, surfaceRegion, root) =>
      surfaceRegion.layout()
      val viewport = Rect(0, 0, surfaceRegion.getWidth, surfaceRegion.getHeight)
      Surface(
        surfaceRegion,
        sizing.dropAreas(root, surfaceRegion.geometry, viewport, windowRef, settings)
      )
    // ESC must work whichever window holds keyboard focus
    val scenes = surfaces.flatMap(s => Option(s.region.getScene)).distinct
    scenes.foreach(_.addEventFilter(KeyEvent.KEY_PRESSED, escFilter))
    // the ghost is a Popup: it follows the pointer across windows without stealing focus
    val ghost = Option(region.getScene).map(_.getWindow).filter(_ != null).map: owner =>
      val g = GhostPopup(pane.title, snapshot, dock.themeStylesheet)
      g.showAt(owner, e.getScreenX, e.getScreenY)
      g
    phase = Phase.Dragging(Session(pane, home, surfaces, ghost, scenes))
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
        session.ghost.foreach(_.moveTo(e.getScreenX, e.getScreenY))
        hitAt(session, e.getScreenX, e.getScreenY) match
          case Some((surface, area)) =>
            session.lastValid = Some((surface, area))
            session.surfaces.foreach: s =>
              if s eq surface then s.region.showIndicator(area.highlight)
              else s.region.hideIndicator()
          case None =>
            // the last valid zone still wins a near-miss release, so its highlight stays lit
            if session.lastValid.isEmpty then session.surfaces.foreach(_.region.hideIndicator())
      case _ => ()

  private def completeDrop(e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(session) =>
        try
          val chosen = hitAt(session, e.getScreenX, e.getScreenY).orElse(session.lastValid)
          chosen.map((_, area) => targetOf(area, e)).orElse(session.home) match
            case Some(target) =>
              dock.update(edit.drop(_, session.pane, target))
              dock.publish(DockEvent.Dropped(session.pane.id, target))
            case None => () // sourced pane, no landing zone: it simply never existed
        finally endSession(session)
      case _ => ()

  /** The single, idempotent cleanup door: ghost, indicators, filters, parked pane, phase. */
  private def endSession(session: Session): Unit =
    if !session.closed then
      session.closed = true
      session.ghost.foreach(_.hide())
      session.surfaces.foreach(_.region.hideIndicator())
      session.filteredScenes.foreach(_.removeEventFilter(KeyEvent.KEY_PRESSED, escFilter))
      dock.unpark(session.pane.id)
    phase = Phase.Idle

  /** Resolve a hovered zone into the final drop: header hits compute the tab-insertion index from
    * the pointer's x among the group's live tab bounds.
    */
  private def targetOf(area: DropArea, e: MouseEvent): DropTarget = area.zone match
    case DropZone.HeaderOf(group) =>
      val index = dock.groupViewFor(group).fold(0)(_.tabIndexAt(e.getScreenX))
      DropTarget.IntoGroup(group, index)
    case DropZone.BodyEdge(group, edge)    => DropTarget.Beside(group, edge)
    case DropZone.WindowEdge(window, edge) => DropTarget.AtWindowEdge(window, edge)
    case DropZone.EmptyWindow(window)      => DropTarget.AtWindowEdge(window, Edge.Right)
end DragController

object DragController:
  /** Movement (in any axis) that turns a press into a drag — golden-layout's 10px. */
  val ThresholdPx: Double = 10

/** The floating drag ghost: a Popup, not a Stage — a Popup never takes keyboard focus (so ESC keeps
  * working in the dragged-from window) yet still crosses window boundaries.
  */
private[fx] final class GhostPopup(title: String, snapshot: Option[Image], theme: Option[String])
    extends Popup:
  setAutoFix(false) // follow the pointer honestly, even near screen edges
  setAutoHide(false)

  private val titleLabel = new Label(title)
  titleLabel.getStyleClass.add("dock-ghost-title")

  private val box = new VBox(titleLabel)
  snapshot.foreach: img =>
    val view  = new ImageView(img)
    val scale = math.min(1.0, GhostPopup.MaxContentPx / math.max(img.getWidth, img.getHeight))
    view.setFitWidth(img.getWidth * scale)
    view.setFitHeight(img.getHeight * scale)
    box.getChildren.add(view): Unit
  // .dock carries the theme variables — the ghost is not a descendant of any dock window,
  // so it must hold the marker class itself to resolve them
  box.getStyleClass.addAll("dock", "dock-ghost")

  locally:
    box.setMouseTransparent(true)
    val css = getClass.getResource("/scaladock/dock.css")
    if css != null then box.getStylesheets.add(css.toExternalForm): Unit
    theme.foreach(sheet => box.getStylesheets.add(sheet): Unit)
    getContent.add(box): Unit

  def showAt(owner: javafx.stage.Window, screenX: Double, screenY: Double): Unit =
    show(owner, screenX + GhostPopup.OffsetPx, screenY + GhostPopup.OffsetPx)

  def moveTo(screenX: Double, screenY: Double): Unit =
    setX(screenX + GhostPopup.OffsetPx)
    setY(screenY + GhostPopup.OffsetPx)

object GhostPopup:
  val OffsetPx: Double     = 10
  val MaxContentPx: Double = 220
