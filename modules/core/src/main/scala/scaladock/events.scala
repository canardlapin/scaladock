package scaladock

/** Everything observable about a layout, as one typed vocabulary — no string event names. */
enum DockEvent derives CanEqual:
  case LayoutChanged(state: LayoutState)
  case PaneOpened(id: PaneId, tpe: String)
  case PaneClosed(id: PaneId)

  /** A pane left the layout but its view was retained (a perspective switch, say): it is alive,
    * detached from every window. Pause expensive work; it may be reattached later.
    */
  case PaneDetached(id: PaneId)

  /** A retained pane's view re-entered the layout — the same live view, state intact. */
  case PaneReattached(id: PaneId)
  case PaneRetitled(id: PaneId, title: String)
  case PaneFocused(id: PaneId, previous: Option[PaneId])
  case FocusCleared(previous: PaneId)
  case ActiveTabChanged(group: NodeId, pane: PaneId)
  case GroupMaximized(group: NodeId)
  case GroupRestored(group: NodeId)
  case GroupMinimized(group: NodeId)
  case GroupUnminimized(group: NodeId)
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
    nextPanes.foreach: (id, pane) =>
      prevPanes.get(id) match
        case Some(before) if before.title != pane.title => events += PaneRetitled(id, pane.title)
        case _                                          => ()

    if prev.focused != next.focused then
      (prev.focused, next.focused) match
        case (_, Some(id))      => events += PaneFocused(id, prev.focused)
        case (Some(gone), None) => events += FocusCleared(gone)
        case _                  => ()

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

    (next.minimized -- prev.minimized).foreach(g => events += GroupMinimized(g))
    (prev.minimized -- next.minimized).foreach(g => events += GroupUnminimized(g))

    val prevWindows = prev.floating.map(_.window).toSet
    val nextWindows = next.floating.map(_.window).toSet
    (nextWindows -- prevWindows).foreach(w => events += WindowOpened(w))
    (prevWindows -- nextWindows).foreach(w => events += WindowClosed(w))

    events.result()
  end diff

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

  /** Mutable publisher end. Single-threaded by design: publish from the UI thread.
    *
    * Subscriptions are token-based (subscribing the same function twice yields two independent
    * subscriptions), and one throwing subscriber cannot starve the rest — its exception is reported
    * through the thread's uncaught-exception handler after delivery completes.
    */
  final class Topic[A] extends Events[A]:
    private var nextToken: Long                        = 0
    private var subscribers: Vector[(Long, A => Unit)] = Vector.empty

    def publish(a: A): Unit =
      var thrown: Throwable = null
      subscribers.foreach: (_, f) =>
        try f(a)
        catch case t: Throwable => if thrown == null then thrown = t
      if thrown != null then
        val t = Thread.currentThread
        t.getUncaughtExceptionHandler.uncaughtException(t, thrown)

    def subscribe(f: A => Unit): Subscription =
      val token = nextToken
      nextToken += 1
      subscribers = subscribers :+ (token, f)
      () => subscribers = subscribers.filterNot(_(0) == token)
