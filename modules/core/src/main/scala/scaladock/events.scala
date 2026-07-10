package scaladock

/** Everything observable about a layout, as one typed vocabulary — no string event names. */
enum DockEvent derives CanEqual:
  case LayoutChanged(state: LayoutState)
  case PaneOpened(id: PaneId, tpe: String)
  case PaneClosed(id: PaneId)
  case PaneFocused(id: PaneId, previous: Option[PaneId])
  case ActiveTabChanged(group: NodeId, pane: PaneId)
  case GroupMaximized(group: NodeId)
  case GroupRestored(group: NodeId)
  case WindowOpened(id: WindowId)
  case WindowClosed(id: WindowId)
  case DragStarted(pane: PaneId)
  case Dropped(pane: PaneId, target: DropTarget)

object DockEvent:

  /** Derive the fine-grained events implied by one state transition. Because every mutation flows
    * through a single point, event derivation is a pure diff — golden-layout's bubbling emitter
    * tiers collapse into this one function.
    */
  def diff(prev: LayoutState, next: LayoutState): Vector[DockEvent] =
    val events = Vector.newBuilder[DockEvent]

    val prevPanes = prev.panes.map(p => p.id -> p).toMap
    val nextPanes = next.panes.map(p => p.id -> p).toMap
    nextPanes.keysIterator.filterNot(prevPanes.contains).foreach: id =>
      events += PaneOpened(id, nextPanes(id).content.tpe.name)
    prevPanes.keysIterator.filterNot(nextPanes.contains).foreach: id =>
      events += PaneClosed(id)

    if prev.focused != next.focused then
      next.focused.foreach(id => events += PaneFocused(id, prev.focused))

    val prevActive = prev.groups.map(g => g.id -> g.active).toMap
    next.groups.foreach: g =>
      prevActive.get(g.id) match
        case Some(before) if before != g.active => events += ActiveTabChanged(g.id, g.active)
        case _                                  => ()

    (prev.maximized, next.maximized) match
      case (a, b) if a == b => ()
      case (Some(g), None)  => events += GroupRestored(g)
      case (_, Some(g))     => events += GroupMaximized(g)
      case _                => ()

    val prevWindows = prev.floating.map(_.window).toSet
    val nextWindows = next.floating.map(_.window).toSet
    (nextWindows -- prevWindows).foreach(w => events += WindowOpened(w))
    (prevWindows -- nextWindows).foreach(w => events += WindowClosed(w))

    events.result()

end DockEvent

/** Per-pane lifecycle notifications, delivered through the pane's own context. */
enum PaneSignal derives CanEqual:
  case Shown, Hidden
  case Resized(width: Double, height: Double)
  case FocusGained, FocusLost

/** A cancellable subscription. */
trait Subscription:
  def cancel(): Unit

/** A minimal typed topic — enough for a UI library, deliberately not a streaming framework. */
trait Events[+A]:
  def subscribe(f: A => Unit): Subscription

  final def collect[B](pf: PartialFunction[A, B]): Events[B] =
    val self = this
    new Events[B]:
      def subscribe(f: B => Unit): Subscription =
        self.subscribe(a => if pf.isDefinedAt(a) then f(pf(a)))

object Events:

  /** Mutable publisher end. Single-threaded by design: publish from the UI thread. */
  final class Topic[A] extends Events[A]:
    private var subscribers: Vector[A => Unit] = Vector.empty

    def publish(a: A): Unit = subscribers.foreach(_(a))

    def subscribe(f: A => Unit): Subscription =
      subscribers = subscribers :+ f
      () => subscribers = subscribers.filterNot(_ eq f)
