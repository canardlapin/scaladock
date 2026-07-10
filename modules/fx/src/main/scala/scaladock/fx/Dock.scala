package scaladock.fx

import javafx.scene.{Parent, Scene}
import javafx.scene.control.Label
import javafx.stage.Stage
import scala.collection.mutable
import scaladock.*

/** The JavaFX entry point: one dock, one immutable state value, one mutation door.
  *
  * Every change flows through [[update]], which canonicalizes the next state, reconciles the
  * long-lived JavaFX views (keyed by stable ids — pane nodes are reparented, never rebuilt, even
  * across OS windows), and publishes the derived [[DockEvent]]s. Floating windows are plain data in
  * [[LayoutState]]; their Stages are reconciled from it exactly like group views. Construct and use
  * on the JavaFX thread.
  */
final class Dock private (
    factories: PaneFactories,
    val settings: LayoutSettings,
    defaultHeader: HeaderButtons
):
  private var current: LayoutState = LayoutState.empty
  private var updating             = false
  private val queued               = mutable.Queue.empty[LayoutState => LayoutState]

  private val topic    = Events.Topic[DockEvent]()
  private val panes    = mutable.Map.empty[PaneId, BoundPane[?]]
  private val contexts = mutable.Map.empty[PaneId, UntypedPaneContext]

  private val main   = WindowRenderer(this, settings)
  private val floats = mutable.Map.empty[WindowId, FloatingStage]

  private val dragController = DragController(this, main.region)

  // floating windows must not outlive the main window: when the scene's window hides
  // (app closing), close every floating Stage — the state is untouched, so a save made
  // before exit still restores them
  locally:
    main.region.sceneProperty.addListener: (_, _, scene) =>
      if scene != null then
        scene.windowProperty.addListener: (_, _, window) =>
          if window != null then
            window.addEventHandler(
              javafx.stage.WindowEvent.WINDOW_HIDDEN,
              _ => floats.values.foreach(_.dispose())
            )

  // -- public surface --------------------------------------------------------------------------

  /** Put this into your scene. */
  def view: Parent = main.region

  def state: LayoutState = current

  def events: Events[DockEvent] = topic

  /** The single mutation door: canonicalize, reconcile views, publish events. Reentrant calls (a
    * pane closing itself from an event handler, say) are queued and applied in order.
    */
  def update(f: LayoutState => LayoutState): Unit =
    require(
      javafx.application.Platform.isFxApplicationThread,
      "Dock must be used from the JavaFX application thread"
    )
    queued.enqueue(f)
    if !updating then
      updating = true
      try
        while queued.nonEmpty do applyOne(queued.dequeue())
      finally updating = false

  // convenience operations — sugar over update + edit.*

  def open(pane: PaneDef, at: DockAt = DockAt.Preferred): PaneId =
    val p = pane.toPane
    update(edit.open(_, p, at))
    p.id

  def close(id: PaneId): Unit             = update(edit.close(_, id))
  def focus(id: PaneId): Unit             = update(edit.focus(_, id))
  def maximize(group: NodeId): Unit       = update(edit.maximize(_, group))
  def restore(): Unit                     = update(edit.unmaximized)
  def toggleMaximize(group: NodeId): Unit = update(edit.toggleMaximize(_, group))

  /** Pop a subtree out into its own OS window, appearing in place over its current bounds. */
  def popOut(node: NodeId): Unit =
    val bounds = groupViewFor(node)
      .flatMap(gv => Option(gv.localToScreen(0.0, 0.0)).map(p => (gv, p)))
      .map((gv, p) => Rect(p.getX, p.getY, gv.getWidth.max(300), gv.getHeight.max(200)))
      .getOrElse(Rect(120, 120, 640, 480))
    update(edit.popOut(_, node, bounds))

  def dockBack(window: WindowId): Unit = update(edit.dockBack(_, window))

  def dockAllBack(): Unit =
    update(s => s.floating.map(_.window).foldLeft(s)(edit.dockBack))

  /** Snapshot every live pane's typed state and encode the whole layout (all windows). */
  def save(): ujson.Value =
    LayoutCodec.encode(withLiveState(current))

  def load(v: ujson.Value): Either[LoadError, Unit] =
    LayoutCodec.decode(v, factories.paneTypes).map(next => update(_ => next))

  /** Arm an external node (a palette entry, say) so dragging from it fabricates a new pane and
    * enters the ordinary drag-and-drop machinery — golden-layout's DragSource.
    */
  def dragSource(handle: javafx.scene.Node)(make: () => PaneDef): Subscription =
    dragController.installSource(handle)(make)

  /** Tear the dock down: abort any drag, close floating windows and the ghost, dispose every pane
    * view. The dock must not be used afterwards.
    */
  def dispose(): Unit =
    dragController.cancel()
    floats.values.foreach(_.dispose())
    floats.clear()
    panes.values.foreach(_.dispose())
    panes.clear()
    contexts.clear()

  /** Restyle every dock window. Themes redefine only CSS variables; see docs/styling.md. */
  def setTheme(theme: DockTheme): Unit =
    currentTheme = theme
    allRegions.foreach(_.setThemeSheet(theme.stylesheet))

  private var currentTheme: DockTheme = DockTheme.Dark

  private def allRegions: Vector[DockRegion] =
    main.region +: floats.values.toVector.map(_.renderer.region)

  // -- internals ---------------------------------------------------------------------------------

  /** The live JavaFX node of a pane (test and interaction plumbing). */
  private[fx] def nodeOf(id: PaneId): Option[javafx.scene.Node] =
    panes.get(id).map(_.node)

  private[fx] def publish(e: DockEvent): Unit = topic.publish(e)

  private[fx] def boundPane(content: PaneContent, id: PaneId): BoundPane[?] =
    val ctx = contexts.getOrElseUpdate(id, UntypedPaneContext(id, this))
    try factories.bind(content, ctx).getOrElse(unresolvedPane(content, ctx))
    catch
      case scala.util.control.NonFatal(t) =>
        // one throwing factory must not abort the whole reconcile: carry the pane unresolved
        System.err.println(s"scaladock: pane factory for '${content.tpe.name}' threw: $t")
        unresolvedPane(content, ctx)

  private[fx] def paneNodeFor(id: PaneId): Option[javafx.scene.Node] = nodeOf(id)

  private[fx] def groupViewFor(id: NodeId): Option[GroupView] =
    main.groupViewFor(
      id
    ).orElse(floats.values.iterator.flatMap(_.renderer.groupViewFor(id)).nextOption())

  /** Every dockable surface, front-most first: floating windows by most-recent OS focus, then the
    * main window.
    */
  private[fx] def dragSurfaces: Vector[(Option[WindowId], DockRegion, Option[Node])] =
    floats.toVector
      .sortBy((_, f) => -f.focusStamp)
      .map((id, f) =>
        (Some(id), f.renderer.region, current.floating.find(_.window == id).map(_.root))
      )
      :+ (None, main.region, current.root)

  private var focusCounter: Long = 0

  private[fx] def nextFocusStamp(): Long =
    focusCounter += 1
    focusCounter

  private[fx] def themeStylesheet: Option[String] = currentTheme.stylesheet

  private[fx] def resolvedHeader(g: Node.Group): Option[HeaderButtons] =
    Header.resolve(g.header, defaultHeader)

  private[fx] def signal(id: PaneId, sig: PaneSignal): Unit =
    contexts.get(id).foreach(_.topic.publish(sig))

  private[fx] def wireGroupView(gv: GroupView): Unit =
    val groupId = gv.nodeId
    gv.onTabActivated = id => update(edit.focus(_, id))
    gv.onTabPressed = (id, e) =>
      // arm the drag BEFORE the focus update: focusing reconciles and rebuilds the tab strip,
      // and the controller must capture the press coordinates from the original gesture
      dragController.tabPressed(id, e)
      update(edit.focus(_, id))
    gv.onTabDragged = (id, e) => dragController.tabDragged(id, e)
    gv.onTabReleased = (id, e) => dragController.tabReleased(id, e)
    gv.onTabClosed = id =>
      if current.findPane(id).exists(_.closable) then update(edit.close(_, id))
    gv.onGroupClosed = () =>
      update: s =>
        s.findGroup(groupId).fold(s): g =>
          g.tabs.filter(_.closable).foldLeft(s)((acc, p) => edit.removePane(acc, p.id))
    gv.onMaximizeToggled = () => update(edit.toggleMaximize(_, groupId))
    gv.onPopOut = () => popOut(groupId)
    // resize notifications originate inside a layout pass: defer so a handler that mutates
    // the scene graph (or calls update) never runs mid-layout
    gv.onContentResized = (id, w, h) =>
      javafx.application.Platform.runLater(() => signal(id, PaneSignal.Resized(w, h)))

  private[fx] def wireDividerView(dv: DividerView, region: DockRegion): Unit =
    dv.dragContext = () => dividerDragContext(dv.splitId, dv.index, region)
    dv.onCommit = (fraction, ctx) =>
      update(edit.dragDivider(_, dv.splitId, dv.index, fraction, ctx.span, settings))

  /** Snapshot what a divider drag needs: neighbour pixels, recursive minimums, span. */
  private def dividerDragContext(
      splitId: NodeId,
      index: Int,
      region: DockRegion
  ): Option[DividerView.DragContext] =
    region.layout() // a press can land between a state change and the next pulse: refresh
    for
      split <- current.findSplit(splitId)
      rect  <- region.geometry.splits.get(splitId)
      if index >= 0 && index < split.cells.length - 1
    yield
      val span = split.axis match
        case Axis.Horizontal => rect.width
        case Axis.Vertical   => rect.height
      val alloc = sizing.allocate(split.cells, span, settings.dividerPx)
      DividerView.DragContext(
        aPx = alloc(index),
        bPx = alloc(index + 1),
        minA = sizing.cellMin(split.cells(index), split.axis, settings),
        minB = sizing.cellMin(split.cells(index + 1), split.axis, settings),
        span = span
      )

  // -- in-flight panes: alive while detached from the tree during a drag ------------------------

  private val parked = mutable.Set.empty[PaneId]

  /** Keep a pane's view alive across its detached time between drag start and drop. */
  private[fx] def park(id: PaneId): Unit = parked += id: Unit

  /** End a pane's protection; if it did not land back in the layout, dispose it now. */
  private[fx] def unpark(id: PaneId): Unit =
    parked -= id
    if current.findPane(id).isEmpty then
      panes.remove(id).foreach(_.dispose())
      contexts.remove(id): Unit

  // -- reconciliation ----------------------------------------------------------------------------

  private def applyOne(f: LayoutState => LayoutState): Unit =
    val prev = current
    val next = edit.canonical(f(prev))
    if next != prev then
      current = next
      reconcile(prev, next)
      DockEvent.diff(prev, next).foreach(topic.publish)
      topic.publish(DockEvent.LayoutChanged(next))

  private def reconcile(prev: LayoutState, next: LayoutState): Unit =
    val nextPanes = next.panes.map(p => p.id -> p).toMap

    // panes (across every window): bind newcomers once, dispose leavers
    nextPanes.keysIterator.filterNot(panes.contains).foreach: id =>
      panes(id) = boundPane(nextPanes(id).content, id)
    panes.keys.toVector.filterNot(id => nextPanes.contains(id) || parked(id)).foreach: id =>
      panes.remove(id).foreach(_.dispose())
      contexts.remove(id): Unit

    // the main window's tree
    main.sync(next.root, next)

    // floating windows: Stages reconciled from state, keyed by WindowId
    val liveWindows = next.floating.map(_.window).toSet
    next.floating.foreach: fl =>
      val f = floats.getOrElseUpdate(
        fl.window, {
          val created = FloatingStage(this, fl)
          created.renderer.region.setThemeSheet(currentTheme.stylesheet)
          created
        }
      )
      f.renderer.sync(Some(fl.root), next)
      f.setBoundsIfChanged(fl.bounds)
      f.setTitleFrom(fl.root)
    floats.keys.toVector.filterNot(liveWindows).foreach: id =>
      floats.remove(id).foreach(_.dispose())

    publishPaneSignals(prev, next)

  private def publishPaneSignals(prev: LayoutState, next: LayoutState): Unit =
    val prevVisible = visiblePanes(prev)
    val nextVisible = visiblePanes(next)
    (nextVisible -- prevVisible).foreach(signal(_, PaneSignal.Shown))
    (prevVisible -- nextVisible).foreach(signal(_, PaneSignal.Hidden))
    if prev.focused != next.focused then
      prev.focused.foreach(signal(_, PaneSignal.FocusLost))
      next.focused.foreach(signal(_, PaneSignal.FocusGained))

  /** The panes actually on screen: each group's active tab — except while a group is maximised,
    * when it hides every other group in its own window.
    */
  private def visiblePanes(s: LayoutState): Set[PaneId] =
    def activesOf(root: Node): Set[PaneId] =
      val groups = root.groups
      s.maximized.flatMap(id => groups.find(_.id == id)) match
        case Some(maxed) => Set(maxed.active)
        case None        => groups.map(_.active).toSet
    s.roots.flatMap(activesOf).toSet

  /** Carrier view for panes whose type has no registered factory: shows a notice, preserves the
    * original state verbatim so a later save loses nothing.
    */
  private def unresolvedPane(content: PaneContent, ctx: UntypedPaneContext): BoundPane[?] =
    val keep: ujson.Value =
      if content.tpe eq PaneType.Unresolved then content.encoded
      else ujson.Obj("type" -> content.tpe.name, "state" -> content.encoded)
    val view = new PaneView[ujson.Value]:
      val node = new Label(s"Unavailable pane type\n${content.tpe.name}")
      node.getStyleClass.add("dock-unresolved")
      def snapshot(): ujson.Value = keep
    BoundPane(PaneType.Unresolved, view, ctx.typed[ujson.Value])

  /** Replace each pane's persisted state with its live snapshot. Snapshotting is best-effort per
    * pane: one throwing `snapshot()` falls back to that pane's last persisted state rather than
    * failing the whole save.
    */
  private def withLiveState(s: LayoutState): LayoutState =
    def refresh(p: Pane): Pane =
      panes.get(p.id).fold(p): bp =>
        try p.copy(content = bp.snapshotContent)
        catch
          case scala.util.control.NonFatal(t) =>
            System.err.println(s"scaladock: snapshot() of pane '${p.title}' threw: $t")
            p
    def go(n: Node): Node = n match
      case g: Node.Group => g.copy(tabs = g.tabs.map(refresh))
      case sp @ Node.Split(_, _, cells) =>
        sp.copy(cells = cells.map(c => c.copy(node = go(c.node))))
    s.copy(
      root = s.root.map(go),
      floating = s.floating.map(fl => fl.copy(root = go(fl.root)))
    )

end Dock

/** Built-in looks. `Custom` takes a stylesheet URL that redefines the `-dock-*` variables. */
enum DockTheme:
  case Dark
  case Light
  case Custom(url: String)

  private[fx] def stylesheet: Option[String] = this match
    case Dark  => None // the base sheet's defaults are the dark theme
    case Light => Option(getClass.getResource("/scaladock/dock-light.css")).map(_.toExternalForm)
    case Custom(url) => Some(url)

object Dock:
  def apply(
      factories: PaneFactories,
      initial: LayoutState = LayoutState.empty,
      settings: LayoutSettings = LayoutSettings.default,
      defaultHeader: HeaderButtons = HeaderButtons()
  ): Dock =
    val dock = new Dock(factories, settings, defaultHeader)
    dock.update(_ => initial)
    dock

/** One window's rendering: a flat region plus keyed maps of group and divider views. The main
  * window and every floating window each own one of these.
  */
private[fx] final class WindowRenderer(dock: Dock, settings: LayoutSettings):
  private[fx] val region = DockRegion(settings)

  private val groupViews   = mutable.Map.empty[NodeId, GroupView]
  private val dividerViews = mutable.Map.empty[(NodeId, Int), DividerView]

  def groupViewFor(id: NodeId): Option[GroupView] = groupViews.get(id)

  /** Bring this window's views in line with its tree; unchanged views are reused in place. */
  def sync(root: Option[Node], state: LayoutState): Unit =
    val groupsHere = root.toVector.flatMap(_.groups)
    groupsHere.foreach: g =>
      val gv = groupViews.getOrElseUpdate(
        g.id, {
          val created = GroupView(g.id, settings)
          dock.wireGroupView(created)
          region.addView(created)
          created
        }
      )
      gv.update(g, dock.resolvedHeader(g), state.maximized.contains(g.id), dock.paneNodeFor)
      gv.setActiveStyle(state.focused.exists(f => g.tabs.exists(_.id == f)))
    val liveGroups = groupsHere.map(_.id).toSet
    groupViews.keys.toVector.filterNot(liveGroups).foreach: id =>
      groupViews.remove(id).foreach(region.removeView)

    def splitsOf(n: Node): Vector[Node.Split] = n match
      case _: Node.Group                => Vector.empty
      case sp @ Node.Split(_, _, cells) => sp +: cells.flatMap(c => splitsOf(c.node))
    val neededDividers = root.toVector
      .flatMap(splitsOf)
      .flatMap(sp => (0 until sp.cells.length - 1).map(i => (sp.id, i)))
      .toSet
    neededDividers.filterNot(dividerViews.contains).foreach: key =>
      val dv = DividerView(key._1, key._2)
      dock.wireDividerView(dv, region)
      dividerViews(key) = dv
      region.addView(dv)
    dividerViews.keys.toVector.filterNot(neededDividers).foreach: key =>
      dividerViews.remove(key).foreach(region.removeView)

    // dividers above groups (their invisible grab reach extends over neighbours), then the
    // drop indicator above everything — addView keeps the indicator frontmost
    dividerViews.values.foreach(_.toFront())
    region.raiseOverlay()

    region.show(root, state.maximized)

end WindowRenderer

/** A floating window: a Stage whose content is reconciled from `LayoutState.floating`. Closing it
  * docks its content back at its home anchor; moving or resizing it writes its live bounds back
  * into the state so saves capture reality.
  */
private[fx] final class FloatingStage(dock: Dock, initial: Floating):
  val window: WindowId         = initial.window
  val renderer: WindowRenderer = WindowRenderer(dock, dock.settings)
  private val stage: Stage     = new Stage
  private var applyingBounds   = false

  /** Ordering stamp for hit-testing: bumped whenever this window gains OS focus. */
  private[fx] var focusStamp: Long = 0

  locally:
    // convention: state bounds are the OUTER (decorated) stage bounds, both directions.
    // Guard the whole construction and attach the listeners only after show() — a Window's
    // x/y/width/height are NaN until shown, and unguarded listeners would write NaN (and
    // then decoration-inflated sizes) back into LayoutState, growing the window every
    // save/load cycle.
    applyingBounds = true
    try
      val b = FloatingStage.clampToScreens(initial.bounds)
      stage.setScene(new Scene(renderer.region))
      stage.setX(b.x)
      stage.setY(b.y)
      stage.setWidth(b.width)
      stage.setHeight(b.height)
      stage.setOnCloseRequest: e =>
        e.consume()
        dock.dockBack(window)
      stage.show()
    finally applyingBounds = false

    val onMoved: javafx.beans.value.ChangeListener[Number] = (_, _, _) =>
      if !applyingBounds then
        val r = Rect(stage.getX, stage.getY, stage.getWidth, stage.getHeight)
        val finite =
          r.x.isFinite && r.y.isFinite && r.width.isFinite && r.height.isFinite
        if finite then dock.update(edit.moveWindow(_, window, r))
    stage.xProperty.addListener(onMoved)
    stage.yProperty.addListener(onMoved)
    stage.widthProperty.addListener(onMoved)
    stage.heightProperty.addListener(onMoved)
    stage.focusedProperty.addListener: (_, _, focused) =>
      if focused then focusStamp = dock.nextFocusStamp()

  def setBoundsIfChanged(b: Rect): Unit =
    val differs =
      math.abs(stage.getX - b.x) > 1 || math.abs(stage.getY - b.y) > 1 ||
        math.abs(stage.getWidth - b.width) > 1 || math.abs(stage.getHeight - b.height) > 1
    if differs then
      applyingBounds = true
      try
        stage.setX(b.x); stage.setY(b.y)
        stage.setWidth(b.width); stage.setHeight(b.height)
      finally applyingBounds = false

  def setTitleFrom(root: Node): Unit =
    val title = root.panes.headOption.map(_.title).getOrElse("scaladock")
    if stage.getTitle != title then stage.setTitle(title)

  def dispose(): Unit = stage.close()
end FloatingStage

object FloatingStage:
  /** Keep a restored window reachable: monitors change between sessions. If the requested bounds
    * don't intersect any visible screen, relocate onto the primary one.
    */
  private[fx] def clampToScreens(b: Rect): Rect =
    import javafx.stage.Screen
    val onSomeScreen = Screen.getScreensForRectangle(b.x, b.y, b.width, b.height).size > 0
    if onSomeScreen then b
    else
      val vis = Screen.getPrimary.getVisualBounds
      Rect(
        vis.getMinX + 40,
        vis.getMinY + 40,
        b.width.min(vis.getWidth - 80).max(200),
        b.height.min(vis.getHeight - 80).max(150)
      )
