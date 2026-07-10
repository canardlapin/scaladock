package scaladock.fx

import javafx.event.EventHandler
import javafx.scene.control.Label
import javafx.scene.image.{Image, ImageView}
import javafx.scene.input.{KeyCode, KeyEvent, MouseEvent}
import javafx.scene.layout.{StackPane, VBox}
import javafx.scene.{Scene, SnapshotParameters}
import javafx.stage.{Stage, StageStyle}
import scaladock.*

/** The drag gesture, golden-layout style: arm on press, start after a 10px move, float a ghost
  * window, hit-test a screen-space snapshot of drop areas taken at drag start, highlight the
  * hovered zone, and commit exactly one transition on release. ESC or a hopeless miss returns the
  * pane home.
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
      val surfaces: Vector[Surface], // front-most first: floating windows above the main one
      val ghost: GhostStage
  ):
    var lastValid: Option[(Surface, DropArea)] = None

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
        dock.update(s => edit.detach(s, paneId)._1)
        beginSession(pane, Some(home), snapshot, e)

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
    val ghost = GhostStage(pane.title, snapshot)
    ghost.moveTo(e.getScreenX, e.getScreenY)
    ghost.show()
    phase = Phase.Dragging(Session(pane, home, surfaces, ghost))
    dock.publish(DockEvent.DragStarted(pane.id))
    Option(region.getScene).foreach(_.addEventFilter(KeyEvent.KEY_PRESSED, escFilter))

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
        session.ghost.moveTo(e.getScreenX, e.getScreenY)
        hitAt(session, e.getScreenX, e.getScreenY) match
          case Some((surface, area)) =>
            session.lastValid = Some((surface, area))
            session.surfaces.foreach: s =>
              if s eq surface then s.region.showIndicator(area.highlight)
              else s.region.hideIndicator()
          case None =>
            session.surfaces.foreach(_.region.hideIndicator())
      case _ => ()

  private def completeDrop(e: MouseEvent): Unit =
    phase match
      case Phase.Dragging(session) =>
        val chosen = hitAt(session, e.getScreenX, e.getScreenY).orElse(session.lastValid)
        endSession(session)
        chosen.map((_, area) => targetOf(area, e)).orElse(session.home) match
          case Some(target) =>
            dock.update(edit.drop(_, session.pane, target))
            dock.publish(DockEvent.Dropped(session.pane.id, target))
          case None => () // sourced pane, no landing zone: it simply never existed
        dock.unpark(session.pane.id)
      case _ => ()

  private def cancel(): Unit =
    phase match
      case Phase.Dragging(session) =>
        endSession(session)
        session.home.foreach(home => dock.update(edit.drop(_, session.pane, home)))
        dock.unpark(session.pane.id)
      case _ => ()
    phase = Phase.Idle

  private def endSession(session: Session): Unit =
    session.ghost.close()
    session.surfaces.foreach(_.region.hideIndicator())
    Option(region.getScene).foreach(_.removeEventFilter(KeyEvent.KEY_PRESSED, escFilter))
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

/** The floating drag ghost: a transparent, undecorated, always-on-top utility Stage that can cross
  * window boundaries — the reason it is a Stage and not an overlay node.
  */
private[fx] final class GhostStage(title: String, snapshot: Option[Image]) extends Stage:
  initStyle(StageStyle.TRANSPARENT)
  setAlwaysOnTop(true)

  private val titleLabel = new Label(title)
  titleLabel.getStyleClass.add("dock-ghost-title")

  private val box = new VBox(titleLabel)
  snapshot.foreach: img =>
    val view  = new ImageView(img)
    val scale = math.min(1.0, GhostStage.MaxContentPx / math.max(img.getWidth, img.getHeight))
    view.setFitWidth(img.getWidth * scale)
    view.setFitHeight(img.getHeight * scale)
    box.getChildren.add(view): Unit
  box.getStyleClass.add("dock-ghost")

  private val root = new StackPane(box)
  root.setStyle("-fx-background-color: transparent;")
  root.setMouseTransparent(true)

  locally:
    val scene = new Scene(root)
    scene.setFill(javafx.scene.paint.Color.TRANSPARENT)
    val css = getClass.getResource("/scaladock/dock.css")
    if css != null then scene.getStylesheets.add(css.toExternalForm): Unit
    setScene(scene)

  def moveTo(screenX: Double, screenY: Double): Unit =
    setX(screenX + GhostStage.OffsetPx)
    setY(screenY + GhostStage.OffsetPx)

object GhostStage:
  val OffsetPx: Double     = 10
  val MaxContentPx: Double = 220
