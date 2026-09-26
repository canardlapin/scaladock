package scaladock.fx

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure, Try}

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
  private val closing  = mutable.Map.empty[(Set[PaneId], Boolean), Future[Boolean]]

  private val main   = WindowRenderer(this, settings, floating = false)
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

  /** Admit a user close. Low-level update/edit and dispose remain force operations. */
  def close(id: PaneId): Unit =
    val _ = requestClose(id)

  def requestClose(id: PaneId): Future[Boolean] =
    if current.findPane(id).exists(_.closable) then admitClose(Set(id))
    else Future.successful(false)

  /** Host shutdown: prepare all panes, including non-closable chrome, before removing any. */
  def requestCloseAll(): Future[Boolean] = admitClose(panes.keySet.toSet, wholeDock = true)

  private def admitClose(ids: Set[PaneId], wholeDock: Boolean = false): Future[Boolean] =
    require(javafx.application.Platform.isFxApplicationThread, "Close admission requires JavaFX")
    val key = (ids, wholeDock)
    closing.get(key) match
      case Some(pending)                                             => pending
      case None if closing.keys.exists(_._1.intersect(ids).nonEmpty) => Future.successful(false)
      case None =>
        val captured = ids.toVector.flatMap(id => panes.get(id).map(id -> _))
        val done     = Promise[Boolean]()
        closing(key) = done.future
        def finish(allowed: Boolean): Unit =
          val unchanged = captured.forall((id, pane) => panes.get(id).exists(_ eq pane)) &&
            (!wholeDock || panes.keySet.toSet == ids)
          val _ = closing.remove(key)
          if allowed && unchanged then
            update(s => ids.foldLeft(s)((state, id) => edit.removePane(state, id)))
            val _ = done.trySuccess(true)
          else
            captured.foreach((id, pane) =>
              if panes.get(id).exists(_ eq pane) then
                val _ = Try(pane.view.closeCancelled())
            )
            val _ = done.trySuccess(false)
        val decisions = captured.map { (_, pane) =>
          Try(pane.view.prepareClose()) match
            case Success(value) => value
            case Failure(error) => Future.failed(error)
        }
        if decisions.forall(_.value.nonEmpty) then
          finish(decisions.forall(_.value.contains(Success(true))))
        else
          Future.sequence(decisions.map(_.recover { case scala.util.control.NonFatal(_) => false }))
            .onComplete(result =>
              javafx.application.Platform.runLater(() =>
                finish(result.toOption.exists(_.forall(identity)))
              )
            )
        done.future
  def focus(id: PaneId): Unit             = update(edit.focus(_, id))
  def maximize(group: NodeId): Unit       = update(edit.maximize(_, group))
  def restore(): Unit                     = update(edit.unmaximized)
  def toggleMaximize(group: NodeId): Unit = update(edit.toggleMaximize(_, group))
  def minimize(group: NodeId): Unit       = update(edit.minimize(_, group))
  def unminimize(group: NodeId): Unit     = update(edit.unminimize(_, group))
  def toggleMinimize(group: NodeId): Unit = update(edit.toggleMinimize(_, group))

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
    val changed = theme != currentTheme
    currentTheme = theme
    allRegions.foreach(_.setThemeSheet(theme.stylesheet))
    // floating windows are the dock's own: their title bars follow the theme too
    floats.values.foreach(_.applyTheme(theme))
    if changed then themeTopic.publish(theme)

  /** What the main window shows when the layout is empty. A custom placeholder is laid out to fill
    * the window (a Region) or centred (any other node), and receives input normally — put the
    * actions that repopulate the layout here. `None` restores the built-in invitation.
    */
  def setPlaceholder(node: Option[javafx.scene.Node]): Unit = main.region.setPlaceholder(node)

  /** Fires after every theme change, so host chrome styled with the dock's variables can follow. */
  def themeChanges: Events[DockTheme] = themeTopic

  private val themeTopic = Events.Topic[DockTheme]()

  private var currentTheme: DockTheme = DockTheme.Dark

  /** The theme currently applied to every dock window. */
  def theme: DockTheme = currentTheme

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
      if current.findPane(id).exists(_.closable) then close(id)
    gv.onGroupClosed = () =>
      current.findGroup(groupId).foreach { group =>
        val _ = admitClose(group.tabs.filter(_.closable).map(_.id).toSet)
      }
    gv.onMaximizeToggled = () => update(edit.toggleMaximize(_, groupId))
    gv.onMinimizeToggled = () => update(edit.toggleMinimize(_, groupId))
    gv.onPopOut = () =>
      // in a window of its own, the pop-out button reads "dock back"
      loneFloatingWindowOf(groupId) match
        case Some(window) => dockBack(window)
        case None         => popOut(groupId)
    // resize notifications originate inside a layout pass: defer so a handler that mutates
    // the scene graph (or calls update) never runs mid-layout
    gv.onContentResized = (id, w, h) =>
      javafx.application.Platform.runLater(() => signal(id, PaneSignal.Resized(w, h)))

  private[fx] def wireDividerView(dv: DividerView, region: DockRegion): Unit =
    dv.dragContext = () => dividerDragContext(dv.splitId, dv.index, region)
    dv.onCommit = (fraction, ctx) =>
      update(edit.dragDivider(_, dv.splitId, dv.index, fraction, ctx.span, settings))
    dv.onPreview = (fraction, ctx) =>
      current.findSplit(dv.splitId).foreach: sp =>
        val cells = sizing.commitDivider(sp.cells, sp.axis, dv.index, fraction, ctx.span, settings)
        region.previewSplit(Some(sp.id -> cells.map(_.size)))
    dv.onPreviewEnd = () => region.previewSplit(None)

  /** Snapshot what a divider drag needs: neighbour pixels, recursive minimums, span. */
  private def dividerDragContext(
      splitId: NodeId,
      index: Int,
      region: DockRegion
  ): Option[DividerView.DragContext] =
    region.layout() // a press can land between a state change and the next pulse: refresh
    def minimizedCell(c: Cell): Boolean = c.node match
      case g: Node.Group => current.minimized(g.id)
      case _             => false
    for
      split <- current.findSplit(splitId)
      rect  <- region.geometry.splits.get(splitId)
      if index >= 0 && index < split.cells.length - 1
      // a strip's extent is fixed: dragging its divider would corrupt the stored size
      if !minimizedCell(split.cells(index)) && !minimizedCell(split.cells(index + 1))
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

  // -- drag presentation -----------------------------------------------------------------------

  /** Dim the tab being dragged (or clear the dimming): the layout itself stays untouched. */
  private[fx] def markDragging(pane: Option[PaneId]): Unit =
    val owner = pane.flatMap(current.groupOf).map(_.id)
    current.groups.foreach: g =>
      groupViewFor(g.id).foreach(_.markDragging(if owner.contains(g.id) then pane else None))

  /** A drag began or ended (tab or drag source): every window quiets its chrome. */
  private[fx] def dragActive(on: Boolean): Unit =
    allRegions.foreach(_.setDragging(on))
    current.groups.foreach(g => groupViewFor(g.id).foreach(_.setGestureActive(on)))

  /** A fresh icon node for a pane's chrome, if its view supplies one. */
  private[fx] def iconOf(id: PaneId): Option[javafx.scene.Node] =
    panes.get(id).flatMap: bp =>
      try bp.view.icon()
      catch case scala.util.control.NonFatal(_) => None

  /** A title-bar chip that stands in for a tab routes its gestures exactly like the tab would. */
  private[fx] def chipPressed(id: PaneId, e: javafx.scene.input.MouseEvent): Unit =
    dragController.tabPressed(id, e)
    update(edit.focus(_, id))
  private[fx] def chipDragged(id: PaneId, e: javafx.scene.input.MouseEvent): Unit =
    dragController.tabDragged(id, e)
  private[fx] def chipReleased(id: PaneId, e: javafx.scene.input.MouseEvent): Unit =
    dragController.tabReleased(id, e)

  /** The floating window whose root is exactly this group, if any. */
  private[fx] def loneFloatingWindowOf(group: NodeId): Option[WindowId] =
    current.floating.find(_.root.id == group).map(_.window)

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
    panes.keys.toVector.filterNot(nextPanes.contains).foreach: id =>
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
          created.applyTheme(currentTheme)
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

  /** The panes actually on screen: each non-minimised group's active tab — except while a group is
    * maximised, when it hides every other group in its own window.
    */
  private def visiblePanes(s: LayoutState): Set[PaneId] =
    def activesOf(root: Node): Set[PaneId] =
      val groups = root.groups
      s.maximized.flatMap(id => groups.find(_.id == id)) match
        case Some(maxed) => Set(maxed.active)
        case None        => groups.filterNot(g => s.minimized(g.id)).map(_.active).toSet
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

  /** Whether this theme is dark, light, or unknown (a custom sheet). */
  def isDark: Option[Boolean] = this match
    case Dark      => Some(true)
    case Light     => Some(false)
    case Custom(_) => None

  /** Ask the platform to draw a window's native decorations (title bar, window controls) in this
    * theme's colour scheme. Needs JavaFX 25+ at runtime (`Scene.getPreferences`); on older
    * runtimes, or for a custom theme, it does nothing and returns false.
    */
  def applyWindowScheme(scene: javafx.scene.Scene): Boolean =
    isDark.exists: dark =>
      try
        // resolve on the public API types: the implementation class lives in a package JavaFX
        // does not export, so invoking through it fails for apps on the module path
        val prefs     = classOf[javafx.scene.Scene].getMethod("getPreferences").invoke(scene)
        val prefsType = Class.forName("javafx.scene.Scene$Preferences")
        val scheme    = Class.forName("javafx.application.ColorScheme")
        val value =
          scheme.getMethod(
            "valueOf",
            classOf[String]
          ).invoke(null, if dark then "DARK" else "LIGHT")
        prefsType.getMethod("setColorScheme", scheme).invoke(prefs, value)
        true
      catch case scala.util.control.NonFatal(_) | _: LinkageError => false

  /** Every stylesheet this theme needs, base first: hosts add these to their own chrome (a toolbar,
    * a status bar) and mark it with the `dock` style class to share the `-dock-*` variables.
    */
  def stylesheets: Vector[String] =
    Option(getClass.getResource("/scaladock/dock.css")).map(_.toExternalForm).toVector ++ stylesheet

  private[fx] def stylesheet: Option[String] = this match
    case Dark  => None // the base sheet's defaults are the dark theme
    case Light => Option(getClass.getResource("/scaladock/dock-light.css")).map(_.toExternalForm)
    case Custom(url) => Some(url)
end DockTheme

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
private[fx] final class WindowRenderer(dock: Dock, settings: LayoutSettings, floating: Boolean):
  private[fx] val region = DockRegion(settings)

  /** Set by a floating window whose title bar stands in for its lone single-tab group's header. */
  private[fx] var barOwnsLoneHeader: Boolean = false

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
      val minimizedAxis = Option.when(state.minimized(g.id)):
        edit.parentOf(state, g.id).map(_._1.axis).getOrElse(Axis.Vertical)
      val barIsHeader =
        barOwnsLoneHeader && root.exists(_.id == g.id) && g.tabs.length == 1
      gv.update(
        g,
        if barIsHeader then None else dock.resolvedHeader(g),
        state.maximized.contains(g.id),
        minimizedAxis,
        dock.paneNodeFor,
        dock.iconOf,
        loneFloating = floating && root.exists(_.id == g.id),
        hiddenByMaximize = root.fold(0)(_.panes.length) - g.tabs.length
      )
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

    region.show(root, state.maximized, state.minimized)
  end sync

end WindowRenderer

/** A floating window: a Stage whose content is reconciled from `LayoutState.floating`. Closing it
  * docks its content back at its home anchor; moving or resizing it writes its live bounds back
  * into the state so saves capture reality.
  */
private[fx] final class FloatingStage(dock: Dock, initial: Floating):
  val window: WindowId         = initial.window
  val renderer: WindowRenderer = WindowRenderer(dock, dock.settings, floating = true)
  private val stage: Stage     = new Stage
  private var applyingBounds   = false

  /** On JavaFX 25+ the window draws its own title bar in the dock's theme; a single-tab window's
    * bar then carries the pane's identity and actions, so there is one row of chrome, not two.
    */
  private val bar: Option[WindowBar] = WindowBar.create(dock, window)
  renderer.barOwnsLoneHeader = bar.isDefined

  /** The scene root: the dock region, under the themed title bar when there is one. */
  private val root: javafx.scene.Parent = bar match
    case Some(b) =>
      val pane = new javafx.scene.layout.BorderPane(renderer.region)
      pane.setTop(b.node)
      pane.getStyleClass.addAll("dock", "dock-window")
      pane
    case None => renderer.region

  /** Theme the window's own chrome (the region themes itself). */
  def applyTheme(theme: DockTheme): Unit =
    if !(root eq renderer.region) then root.getStylesheets.setAll(theme.stylesheets*)
    Option(root.getScene).foreach(theme.applyWindowScheme(_): Unit)

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
      if bar.isDefined then stage.initStyle(javafx.stage.StageStyle.valueOf("EXTENDED"))
      stage.setScene(new Scene(root))
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

  def setTitleFrom(tree: Node): Unit =
    val title = tree.panes.headOption.map(_.title).getOrElse("scaladock")
    if stage.getTitle != title then stage.setTitle(title)
    bar.foreach(_.sync(tree))

  def dispose(): Unit = stage.close()
end FloatingStage

/** A floating window's own title bar: a JavaFX `HeaderBar` styled by the dock theme. It needs
  * JavaFX 27+ (or 25/26 run with `-Djavafx.enablePreview=true`, where it was a preview feature); it
  * is reached reflectively, so older runtimes keep native decorations. It shows the window's pane
  * as a chip — draggable exactly like a tab — and the window's actions; the empty bar moves the
  * window, and the platform's window controls keep their native place.
  */
private[fx] final class WindowBar private (
    dock: Dock,
    window: WindowId,
    val node: javafx.scene.layout.Region
):
  private val iconBox = new javafx.scene.layout.StackPane
  iconBox.getStyleClass.add("dock-tab-icon")
  private val title = new Label
  title.getStyleClass.add("dock-window-title")
  private val chip = new javafx.scene.layout.HBox(iconBox, title)
  chip.getStyleClass.add("dock-window-chip")

  private var paneId: Option[PaneId] = None
  // only a single-pane window's chip stands in for a tab; otherwise the title is plain bar
  // surface, and presses fall through so it moves the window like the rest of the bar
  chip.setOnMousePressed: e =>
    paneId.foreach: id =>
      if e.getButton == javafx.scene.input.MouseButton.PRIMARY then dock.chipPressed(id, e)
      e.consume()
  chip.setOnMouseDragged: e =>
    paneId.foreach: id =>
      dock.chipDragged(id, e)
      e.consume()
  chip.setOnMouseReleased: e =>
    paneId.foreach: id =>
      dock.chipReleased(id, e)
      e.consume()

  private def button(kind: String, icon: String, tip: String)(action: => Unit): javafx.scene.Node =
    val b = new javafx.scene.layout.StackPane(dockIcon(icon))
    b.getStyleClass.addAll("dock-header-button", kind)
    javafx.scene.control.Tooltip.install(b, new javafx.scene.control.Tooltip(tip))
    b.setOnMouseClicked(_ => action)
    b

  /** A single-pane window's bar is its only chrome, so it carries the pane's close too. */
  private val closeAction =
    button("close", "close", "Close")(paneId.foreach(dock.close))

  private val actions = new javafx.scene.layout.HBox(
    button("popout", "dock-back", "Dock back into main window")(dock.dockBack(window)),
    closeAction
  )
  actions.getStyleClass.add("dock-window-actions")

  locally:
    node.getStyleClass.add("dock-window-bar")
    // JavaFX 27 names the slots left/right; the 25/26 previews called them leading/trailing
    WindowBar.call(node, Seq("setLeft", "setLeading"), chip)
    WindowBar.call(node, Seq("setRight", "setTrailing"), actions)

  /** Mirror the window's tree: the chip names the first group's active pane. */
  def sync(tree: Node): Unit =
    val g      = tree.groups.headOption
    val active = g.map(_.active)
    val single = tree.panes.length == 1
    paneId = active.filter(_ => single)
    val pane = g.flatMap(gr => gr.tabs.find(_.id == gr.active))
    title.setText(pane.fold("")(_.title))
    iconBox.getChildren.setAll(active.flatMap(dock.iconOf).toSeq*)
    closeAction.setVisible(single && pane.exists(_.closable))
    closeAction.setManaged(single && pane.exists(_.closable))
    // with several tabs the group keeps its own tab strip, and the bar just titles the window
    chip.pseudoClassStateChanged(pseudo.Quiet, tree.panes.length > 1)
end WindowBar

private[fx] object WindowBar:
  /** Whether this runtime offers client-drawn title bars (see [[WindowBar]] for versions). */
  lazy val supported: Boolean =
    try
      javafx.stage.StageStyle.valueOf("EXTENDED")
      Class.forName("javafx.scene.layout.HeaderBar")
      true
    catch case scala.util.control.NonFatal(_) | _: LinkageError => false

  def create(dock: Dock, window: WindowId): Option[WindowBar] =
    if !supported then None
    else
      try
        val bar = Class.forName("javafx.scene.layout.HeaderBar").getConstructor().newInstance()
        Some(new WindowBar(dock, window, bar.asInstanceOf[javafx.scene.layout.Region]))
      catch case scala.util.control.NonFatal(_) | _: LinkageError => None

  private def call(bar: AnyRef, names: Seq[String], arg: javafx.scene.Node): Unit =
    val cls = Class.forName("javafx.scene.layout.HeaderBar")
    val m = names.iterator
      .flatMap(n => scala.util.Try(cls.getMethod(n, classOf[javafx.scene.Node])).toOption)
      .nextOption()
      .getOrElse(throw new NoSuchMethodException(names.mkString("HeaderBar.", "/", "")))
    m.invoke(bar, arg): Unit

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
