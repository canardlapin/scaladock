package scaladock.fx

import javafx.css.PseudoClass
import javafx.scene.Node as FxNode
import javafx.scene.control.{CheckMenuItem, Label, MenuButton, MenuItem, SeparatorMenuItem, Tooltip}
import javafx.scene.input.MouseButton
import javafx.scene.layout.{HBox, Pane as FxPane, Region, StackPane, VBox}
import scaladock.*

private[fx] object pseudo:
  val Selected: PseudoClass  = PseudoClass.getPseudoClass("selected")
  val Active: PseudoClass    = PseudoClass.getPseudoClass("active")
  val Vertical: PseudoClass  = PseudoClass.getPseudoClass("vertical")
  val Dragging: PseudoClass  = PseudoClass.getPseudoClass("dragging")
  val Minimized: PseudoClass = PseudoClass.getPseudoClass("minimized")
  val Maximized: PseudoClass = PseudoClass.getPseudoClass("maximized")
  val Floating: PseudoClass  = PseudoClass.getPseudoClass("floating")
  val Hot: PseudoClass       = PseudoClass.getPseudoClass("hot")
  val Quiet: PseudoClass     = PseudoClass.getPseudoClass("quiet")
  val Slot: PseudoClass      = PseudoClass.getPseudoClass("slot")
  val Inactive: PseudoClass  = PseudoClass.getPseudoClass("inactive")

/** A glyph whose outline lives in CSS (`-fx-shape` on `.dock-icon.<kind>`), so themes can replace
  * every icon without touching code.
  */
private[fx] def dockIcon(kind: String): Region =
  val r = new Region
  r.getStyleClass.addAll("dock-icon", kind)
  r.setMouseTransparent(true)
  r

/** The visible face of one [[Node.Group]]: a tab header above a content host. The chrome (tabs,
  * buttons, overflow menu) is cheap and rebuilt freely; the hosted pane nodes are only ever
  * reparented.
  */
private[fx] final class GroupView(val nodeId: NodeId, settings: LayoutSettings) extends Region:
  // gesture callbacks, wired once by Dock at creation
  private[fx] var onTabActivated: PaneId => Unit                                 = _ => ()
  private[fx] var onTabPressed: (PaneId, javafx.scene.input.MouseEvent) => Unit  = (_, _) => ()
  private[fx] var onTabDragged: (PaneId, javafx.scene.input.MouseEvent) => Unit  = (_, _) => ()
  private[fx] var onTabReleased: (PaneId, javafx.scene.input.MouseEvent) => Unit = (_, _) => ()
  private[fx] var onTabClosed: PaneId => Unit                                    = _ => ()
  private[fx] var onGroupClosed: () => Unit                                      = () => ()
  private[fx] var onMaximizeToggled: () => Unit                                  = () => ()
  private[fx] var onMinimizeToggled: () => Unit                                  = () => ()
  private[fx] var onPopOut: () => Unit                                           = () => ()
  private[fx] var onContentResized: (PaneId, Double, Double) => Unit             = (_, _, _) => ()

  /** Tooltips are installed once and only retitled on update. */
  private val tips = collection.mutable.Map.empty[FxNode, Tooltip]

  private val tabsBox = new HBox
  tabsBox.getStyleClass.add("dock-tabs")
  tabsBox.setMinWidth(Region.USE_PREF_SIZE)
  tabsBox.setManaged(false) // positioned by layoutHeader; the viewport Pane must not autosize it

  /** The tab strip's viewport: tabs keep their natural width and scroll under a clip. */
  private val tabsViewport = new FxPane(tabsBox)
  tabsViewport.getStyleClass.add("dock-tabs-viewport")
  private val tabsClip = new javafx.scene.shape.Rectangle
  tabsViewport.setClip(tabsClip)

  /** The first tab shown after wheel scrolling; cleared whenever the selected tab changes. */
  private var manualFirst: Option[Int] = None
  private var lastFirst                = 0
  private var wheel                    = 0.0 // accumulated wheel travel, for trackpads

  private val overflow = new MenuButton()
  overflow.getStyleClass.add("dock-tab-overflow")
  overflow.setGraphic(dockIcon("chevron-down"))
  overflow.setVisible(false)
  overflow.setFocusTraversable(false)

  private val popOutButton = headerButton("popout", "Open in new window")
  popOutButton.setOnMouseClicked(_ => onPopOut())

  private val minimizeButton = headerButton("minimize", "Minimize")
  minimizeButton.setOnMouseClicked(_ => onMinimizeToggled())

  private val maximizeButton = headerButton("maximize", "Maximize")
  maximizeButton.setOnMouseClicked(_ => onMaximizeToggled())

  private val closeButton = headerButton("close", "Close group")
  closeButton.setOnMouseClicked(_ => onGroupClosed())

  /** While maximised: how many groups the maximise hides; clicking it restores the layout. */
  private val hiddenBadge = new Label
  hiddenBadge.getStyleClass.add("dock-maximized-badge")
  hiddenBadge.setOnMouseClicked(_ => onMaximizeToggled())
  hiddenBadge.setVisible(false)
  hiddenBadge.setManaged(false)
  tooltip(hiddenBadge, "Restore the layout")

  private val buttons =
    new HBox(hiddenBadge, popOutButton, minimizeButton, maximizeButton, closeButton)
  buttons.getStyleClass.add("dock-header-buttons")
  buttons.setMinWidth(Region.USE_PREF_SIZE)

  private val header = GroupView.HeaderBar(layoutHeader)

  private val content = new StackPane
  content.getStyleClass.add("dock-content")

  // the sideways presentation of a group minimised inside a row: icon above a rotated title
  private val stripLabel = new Label
  stripLabel.getStyleClass.add("dock-strip-title")
  stripLabel.setRotate(-90)
  private val stripIconBox = new StackPane
  stripIconBox.getStyleClass.add("dock-strip-icon")
  private val stripStack = new VBox(stripIconBox, new javafx.scene.Group(stripLabel))
  stripStack.getStyleClass.add("dock-strip-stack")
  stripStack.setMaxHeight(Region.USE_PREF_SIZE) // a button in the rail, not the whole rail
  private val stripFace = new StackPane(stripStack)
  StackPane.setAlignment(stripStack, javafx.geometry.Pos.TOP_CENTER)
  stripFace.getStyleClass.add("dock-strip")
  stripFace.setVisible(false)
  stripFace.setOnMouseClicked(_ => onMinimizeToggled())

  private var activePane: Option[PaneId]               = None
  private var chromeAllowed: HeaderButtons             = HeaderButtons()
  private var headerVisible                            = true
  private var stripMode                                = false
  private var draggingPane: Option[PaneId]             = None
  private var foldedItems: Vector[(MenuItem, Boolean)] = Vector.empty
  private val foldSeparator: MenuItem                  = new SeparatorMenuItem
  private var tabNodes: Vector[(PaneId, FxNode)]       = Vector.empty
  private val tabViews = collection.mutable.Map.empty[PaneId, TabView]
  private val slides =
    collection.mutable.Map.empty[PaneId, (javafx.animation.TranslateTransition, Double)]

  // Everything below touches this Region's inherited JavaFX surface or hands out callbacks that
  // close over it, so it runs only once every field above is initialized (checked by -Wsafe-init).
  getStyleClass.add("dock-group")
  header.getStyleClass.add("dock-header")
  header.add(tabsViewport, overflow, buttons)
  getChildren.addAll(header, content, stripFace)

  tabsViewport.setOnScroll: e =>
    val delta = if math.abs(e.getDeltaX) > math.abs(e.getDeltaY) then e.getDeltaX else e.getDeltaY
    wheel += delta
    if math.abs(wheel) >= GroupView.WheelStepPx then
      manualFirst = Some(lastFirst + (if wheel < 0 then 1 else -1))
      wheel = 0
      header.requestLayout()
    e.consume()

  // clicking anywhere in the content focuses the pane (VS Code behaviour); a filter so the
  // pane's own handlers still see the event
  content.addEventFilter(
    javafx.scene.input.MouseEvent.MOUSE_PRESSED,
    (_: javafx.scene.input.MouseEvent) => activePane.foreach(onTabActivated)
  )

  content.layoutBoundsProperty.addListener: (_, _, bounds) =>
    activePane.foreach(id => onContentResized(id, bounds.getWidth, bounds.getHeight))

  /** Sync chrome and hosted content to the model. Adding the active pane's node to our content host
    * automatically reparents it (JavaFX removes it from any former parent); inactive panes' nodes
    * are simply left unparented, fully alive.
    */
  def update(
      group: Node.Group,
      chrome: Option[HeaderButtons],
      isMaximized: Boolean,
      minimizedAxis: Option[Axis],
      nodeFor: PaneId => Option[FxNode],
      iconFor: PaneId => Option[FxNode] = _ => None,
      loneFloating: Boolean = false,
      hiddenByMaximize: Int = 0
  ): Unit =
    val badge = isMaximized && hiddenByMaximize > 0
    hiddenBadge.setText(s"$hiddenByMaximize hidden")
    hiddenBadge.setVisible(badge)
    hiddenBadge.setManaged(badge)
    tipOf(hiddenBadge).foreach(_.setText("Restore the layout"))
    if !activePane.contains(group.active) then manualFirst = None
    activePane = Some(group.active)
    pseudoClassStateChanged(pseudo.Minimized, minimizedAxis.isDefined)
    pseudoClassStateChanged(pseudo.Maximized, isMaximized)
    pseudoClassStateChanged(pseudo.Floating, loneFloating)
    // Zero allocated height does not clip children with their own minimum size.
    // Keep the live pane attached, but suppress rendering, input and accessibility.
    content.setVisible(minimizedAxis.isEmpty)

    // a group minimised inside a row is a sideways strip: rotated title, click to restore
    stripMode = minimizedAxis.contains(Axis.Horizontal)
    stripFace.setVisible(stripMode)
    stripLabel.setText(group.tabs.find(_.id == group.active).fold("")(_.title))
    stripIconBox.getChildren.setAll(iconFor(group.active).toSeq*)

    headerVisible = chrome.isDefined && !stripMode
    header.setVisible(headerVisible)
    header.setManaged(headerVisible)

    tipOf(minimizeButton).foreach(_.setText(if minimizedAxis.isDefined then "Restore"
    else "Minimize"))
    tipOf(maximizeButton).foreach(_.setText(if isMaximized then "Restore layout" else "Maximize"))
    tipOf(popOutButton).foreach(
      _.setText(if loneFloating then "Dock back into main window" else "Open in new window")
    )

    val views = group.tabs.map: pane =>
      val v = tabViews.getOrElseUpdate(pane.id, TabView(pane.id, iconFor(pane.id)))
      v.sync(pane, pane.id == group.active)
      v
    tabViews.keys.toVector.filterNot(group.tabs.map(_.id).toSet).foreach(tabViews.remove(_): Unit)
    tabNodes = views.map(v => v.id -> v)
    // touch the strip only when membership or order changed: never detach a live tab needlessly
    val unchanged = tabsBox.getChildren.size == views.size &&
      views.indices.forall(i => tabsBox.getChildren.get(i) eq views(i))
    if !unchanged then tabsBox.getChildren.setAll(views*): Unit

    // the chevron menu: every tab, then (in a narrow group) the actions folded out of the header
    val tabItems = group.tabs.map { pane =>
      val item = new CheckMenuItem(pane.title)
      item.setSelected(pane.id == group.active)
      iconFor(pane.id).foreach(item.setGraphic)
      item.setOnAction(_ => onTabActivated(pane.id))
      item
    }
    // a window's only group already IS the window: minimise/maximise mean nothing there, and
    // pop-out becomes "dock back"; a minimised group offers only expand and close
    val allowed = chrome.getOrElse(HeaderButtons(false, false, false, false))
    chromeAllowed = allowed.copy(
      minimize = allowed.minimize && !loneFloating,
      maximize = allowed.maximize && !loneFloating && minimizedAxis.isEmpty,
      popOut = allowed.popOut && minimizedAxis.isEmpty,
      close = allowed.close && group.tabs.forall(_.closable)
    )
    val popItem = new MenuItem(if loneFloating then "Dock back" else "Open in new window")
    popItem.setOnAction(_ => onPopOut())
    val minItem = new MenuItem(if minimizedAxis.isDefined then "Restore" else "Minimize")
    minItem.setOnAction(_ => onMinimizeToggled())
    val maxItem = new MenuItem(if isMaximized then "Restore layout" else "Maximize")
    maxItem.setOnAction(_ => onMaximizeToggled())
    foldedItems = Vector(
      popItem -> chromeAllowed.popOut,
      minItem -> chromeAllowed.minimize,
      maxItem -> chromeAllowed.maximize
    )
    overflow.getItems.setAll((tabItems ++ (foldSeparator +: foldedItems.map(_._1)))*)

    nodeFor(group.active) match
      case Some(node) if content.getChildren.size == 1 && (content.getChildren.get(0) eq node) =>
        () // already hosting the right node: do not touch it
      case Some(node) => content.getChildren.setAll(node)
      case None       => content.getChildren.clear()
    header.requestLayout()
  end update

  /** While any drag is in flight, content ignores the pointer: no hover or crosshair reacts to a
    * pointer that is carrying a pane.
    */
  def setGestureActive(on: Boolean): Unit = content.setMouseTransparent(on)

  /** Dim the tab being dragged: the layout itself does not change until the drop commits. */
  def markDragging(pane: Option[PaneId]): Unit =
    draggingPane = pane
    tabNodes.foreach((id, n) => n.pseudoClassStateChanged(pseudo.Dragging, pane.contains(id)))

  /** One tab, keyed by pane and long-lived like the pane views: a focus or selection change only
    * restyles it. Replacing the node would pull it out of the scene between a press and the drag
    * that follows — and the platform may then route the gesture to whatever lies beneath.
    */
  private final class TabView(val id: PaneId, icon: Option[FxNode]) extends HBox:
    private var closable = true

    private val title = new Label
    title.getStyleClass.add("dock-tab-title")
    title.setMaxHeight(Double.MaxValue)
    title.setMinWidth(0) // a shrunk tab ellipsizes its title

    /** Width wanted at full title length, ignoring any shrink imposed by the header. */
    def naturalWidth(h: Double): Double = computePrefWidth(h)

    /** Impose (or lift, with `None`) a shrunk width; writes only on change, so layout settles. */
    def shrinkTo(width: Option[Double]): Unit =
      val next = width.getOrElse(Region.USE_COMPUTED_SIZE)
      if math.abs(getPrefWidth - next) > 0.5 then setPrefWidth(next)

    private val close = new StackPane(dockIcon("close"))
    close.getStyleClass.add("dock-tab-close")
    close.setOnMouseClicked: e =>
      onTabClosed(id)
      e.consume()
    // a press on the close glyph must not arm a tab drag
    close.setOnMousePressed(_.consume())

    icon.foreach: i =>
      val holder = new StackPane(i)
      holder.getStyleClass.add("dock-tab-icon")
      getChildren.add(holder): Unit
    getChildren.addAll(title, close)
    getStyleClass.add("dock-tab")
    setMinWidth(Region.USE_PREF_SIZE)

    setOnMousePressed: e =>
      if e.getButton == MouseButton.PRIMARY then onTabPressed(id, e)
    setOnMouseDragged(e => onTabDragged(id, e))
    setOnMouseReleased(e => onTabReleased(id, e))
    setOnMouseClicked: e =>
      if e.getButton == MouseButton.MIDDLE && closable then onTabClosed(id)
      // double-click a tab: maximise its group (or restore), as in VS Code and JetBrains
      else if e.getButton == MouseButton.PRIMARY && e.getClickCount == 2 && chromeAllowed.maximize
      then onMaximizeToggled()

    def sync(pane: Pane, selected: Boolean): Unit =
      if title.getText != pane.title then title.setText(pane.title)
      closable = pane.closable
      close.setVisible(closable)
      close.setManaged(closable)
      pseudoClassStateChanged(pseudo.Selected, selected)
      pseudoClassStateChanged(pseudo.Dragging, draggingPane.contains(id))
  end TabView

  /** Open (or close, with `None`) a gap of the given width before tab `index`: the tabs after it
    * slide right, so a header drop previews as a real slot rather than a hairline.
    */
  def previewInsertion(gap: Option[(Int, Double)]): Unit =
    tabNodes.zipWithIndex.foreach:
      case ((id, node), i) =>
        val to = gap.collect { case (at, w) if i >= at => w }.getOrElse(0.0)
        // one slide per tab, retargeted rather than stacked: compare against where the running
        // slide is heading, not where the tab happens to be this frame
        val heading = slides.get(id).fold(node.getTranslateX)(_._2)
        if math.abs(heading - to) > 0.5 then
          slides.get(id).foreach(_._1.stop())
          val slide = new javafx.animation.TranslateTransition(GroupView.SlideTime, node)
          slide.setToX(to)
          slide.setInterpolator(javafx.animation.Interpolator.EASE_OUT)
          slide.setOnFinished(_ => slides.remove(id): Unit)
          slides(id) = (slide, to)
          slide.play()

  /** Close any opened slot at once (a drop is about to re-lay the strip). */
  def resetInsertion(): Unit =
    slides.values.foreach(_._1.stop())
    slides.clear()
    tabNodes.foreach(_._2.setTranslateX(0))

  /** The laid-out width of a pane's tab, if this group shows it. */
  def tabWidth(pane: PaneId): Option[Double] =
    tabNodes.find(_._1 == pane).map(_._2.getLayoutBounds.getWidth)

  /** Screen x of the left edge of the gap before tab `index` (the strip's end past the last). */
  def gapScreenX(index: Int): Option[Double] =
    val kids = tabsBox.getChildren
    val x =
      if kids.isEmpty then 0.0
      else if index < kids.size then kids.get(index).getLayoutX
      else kids.get(kids.size - 1).getLayoutX + kids.get(kids.size - 1).getLayoutBounds.getWidth
    Option(tabsBox.localToScreen(x, 0)).map(_.getX)

  /** The tab-insertion index for a header drop at the given screen x — the number of tabs whose
    * midpoint lies left of the pointer — together with the screen x of that gap.
    */
  def insertionAt(screenX: Double): (Int, Double) =
    val kids  = tabsBox.getChildren
    val local = tabsBox.screenToLocal(screenX, 0)
    if local == null || kids.isEmpty then (kids.size, screenX)
    else
      var i = 0
      kids.forEach: t =>
        if t.getLayoutX + t.getLayoutBounds.getWidth / 2 < local.getX then i += 1
      val gapX =
        if i < kids.size then kids.get(i).getLayoutX
        else kids.get(kids.size - 1).getLayoutX + kids.get(kids.size - 1).getLayoutBounds.getWidth
      val p = tabsBox.localToScreen(gapX, 0)
      (i, if p == null then screenX else p.getX)

  def tabIndexAt(screenX: Double): Int = insertionAt(screenX)._1

  /** Screen bounds of the header strip (for the tab-insert marker). */
  private[fx] def headerScreenBounds: Option[javafx.geometry.Bounds] =
    Option(header.localToScreen(header.getLayoutBounds))

  private def headerButton(kind: String, tip: String): StackPane =
    val b = new StackPane(dockIcon(kind))
    b.getStyleClass.addAll("dock-header-button", kind)
    tooltip(b, tip)
    b

  private def tooltip(n: FxNode, text: String): Unit =
    val t = new Tooltip(text)
    t.setShowDelay(javafx.util.Duration.millis(600))
    Tooltip.install(n, t)
    tips(n) = t

  private def tipOf(n: FxNode): Option[Tooltip] = tips.get(n)

  def setActiveStyle(focused: Boolean): Unit =
    pseudoClassStateChanged(pseudo.Active, focused)

  /** Tabs keep their natural width; when they don't fit, the strip clips, scrolls to keep the
    * selected tab in view, and the chevron menu appears. Header buttons never get pushed out.
    */
  private def layoutHeader(w: Double, h: Double): Unit =
    // a narrow group folds pop-out, minimise and maximise into the chevron menu: tabs need the room
    val compact = w < GroupView.CompactPx
    def show(n: FxNode, on: Boolean): Unit =
      if n.isVisible != on then n.setVisible(on)
      if n.isManaged != on then n.setManaged(on)
    show(popOutButton, chromeAllowed.popOut && !compact)
    show(minimizeButton, chromeAllowed.minimize && !compact)
    show(maximizeButton, chromeAllowed.maximize && !compact)
    show(closeButton, chromeAllowed.close)
    val folded = compact && foldedItems.exists(_._2)
    foldedItems.foreach((item, allowed) => item.setVisible(compact && allowed))
    foldSeparator.setVisible(folded)

    val buttonsW = buttons.prefWidth(h)
    shrinkTabs(w - buttonsW - GroupView.ChevronReservePx, h)
    val wanted   = tabNodes.map(_._2.prefWidth(h)).sum
    val fits     = wanted <= w - buttonsW + 0.5
    val showMenu = headerVisible && (!fits || folded)
    overflow.setVisible(showMenu)
    // measure the chevron with its count already set, so a two-digit count never ellipsizes
    overflow.setText(if fits then ""
    else overflowCount(wanted, w - buttonsW - overflow.prefWidth(h), h))
    val overflowW = overflow.prefWidth(h)
    val tabsW     = math.max(0, w - buttonsW - (if showMenu then overflowW else 0))
    tabsViewport.resizeRelocate(0, 0, tabsW, h)
    tabsClip.setWidth(tabsW)
    tabsClip.setHeight(h)

    // tab extents along the strip
    val widths                             = tabNodes.map(_._2.prefWidth(h))
    val starts                             = widths.scanLeft(0.0)(_ + _)
    val n                                  = widths.length
    val sel                                = tabNodes.indexWhere(t => activePane.contains(t._1))
    def fitsIn(used: Double, more: Double) = used + more <= tabsW + 0.5
    // when tabs overflow, show a window of WHOLE tabs — never a sliver of one — around the
    // selected tab (or from the tab the wheel scrolled to), and clip exactly at a tab boundary
    val (first, count) =
      if wanted <= tabsW + 0.5 || n == 0 then (0, n)
      else
        manualFirst match
          case Some(f0) =>
            val f    = f0.max(0).min(n - 1)
            var k    = 0
            var used = 0.0
            while f + k < n && fitsIn(used, widths(f + k)) do
              used += widths(f + k); k += 1
            (f, k.max(1))
          case None =>
            val anchor = sel.max(0)
            var lo     = anchor
            var hi     = anchor
            var used   = widths(anchor)
            var grew   = true
            while grew do
              grew = false
              if hi + 1 < n && fitsIn(used, widths(hi + 1)) then
                hi += 1; used += widths(hi); grew = true
              if lo > 0 && fitsIn(used, widths(lo - 1)) then
                lo -= 1; used += widths(lo); grew = true
            (lo, hi - lo + 1)
    lastFirst = first
    tabsBox.resizeRelocate(-starts(first), 0, wanted, h)
    // clip at a tab boundary only when tabs overflow: a strip that fits keeps its full width,
    // so tabs slid aside by a drop slot stay visible
    tabsClip.setWidth(
      if count == n then tabsW else math.min(tabsW, starts(first + count) - starts(first))
    )

    // the chevron counts the tabs not fully in view
    val hidden = n - count
    overflow.setText(if hidden > 0 then hidden.toString else "")
    // holding only folded actions (no hidden tabs), the chevron is chrome like the buttons
    overflow.pseudoClassStateChanged(pseudo.Quiet, hidden == 0)
    if showMenu then overflow.resizeRelocate(tabsW, 0, overflowW, h)
    buttons.resizeRelocate(w - buttonsW, 0, buttonsW, h)
  end layoutHeader

  /** VS Code's "shrink" sizing: when tabs overflow, unselected tabs give up width (down to
    * [[GroupView.MinTabPx]], ellipsizing their titles) before the strip resorts to scrolling.
    */
  private def shrinkTabs(room: Double, h: Double): Unit =
    val views   = tabNodes.collect { case (_, t: TabView) => t }
    val natural = views.map(_.naturalWidth(h))
    val excess  = natural.sum - room
    if excess <= 0.5 then views.foreach(_.shrinkTo(None))
    else
      val give = views.zip(natural).map: (t, n) =>
        if activePane.contains(t.id) then 0.0 else math.max(0.0, n - GroupView.MinTabPx)
      val f = if give.sum <= 0 then 0.0 else math.min(1.0, excess / give.sum)
      views.lazyZip(natural).lazyZip(give).foreach: (t, n, g) =>
        t.shrinkTo(if g > 0 then Some(n - g * f) else None)

  /** A first estimate of the chevron count (tabs not wholly within `room`), for measuring it. */
  private def overflowCount(wanted: Double, room: Double, h: Double): String =
    if wanted <= room then ""
    else
      var x = 0.0
      val shown = tabNodes.count: (_, n) =>
        x += n.prefWidth(h)
        x <= room
      (tabNodes.length - shown).toString

  override def layoutChildren(): Unit =
    if stripMode then
      stripFace.resizeRelocate(0, 0, getWidth, getHeight)
      header.resizeRelocate(0, 0, 0, 0)
      content.resizeRelocate(0, 0, 0, 0)
    else
      val w       = getWidth
      val headerH = if headerVisible then math.min(settings.headerPx, getHeight) else 0.0
      header.resizeRelocate(0, 0, w, headerH)
      content.resizeRelocate(0, headerH, w, math.max(0, getHeight - headerH))

end GroupView

object GroupView:
  val SlideTime: javafx.util.Duration = javafx.util.Duration.millis(110)

  /** Below this header width, secondary header actions fold into the chevron menu. */
  val CompactPx: Double = 340

  /** A shrunk tab never gets narrower than this (icon, a few letters, the close button). */
  val MinTabPx: Double = 100

  /** Room kept for the chevron when deciding whether tabs must shrink. */
  val ChevronReservePx: Double = 40

  /** Wheel travel that scrolls the strip by one tab. */
  val WheelStepPx: Double = 24

  /** The header strip: a plain Region laid out by hand (tabs clip; buttons stay pinned). */
  private final class HeaderBar(layout: (Double, Double) => Unit) extends Region:
    def add(ns: FxNode*): Unit          = getChildren.addAll(ns*): Unit
    override def layoutChildren(): Unit = layout(getWidth, getHeight)

/** A divider strip between two cells of a split. During a drag the split resizes live — the window
  * lays out a transient override of the split's cells (clamped to both subtrees' recursive
  * minimums) — and exactly one transition commits the new ratio on release.
  */
private[fx] final class DividerView(val splitId: NodeId, val index: Int) extends Region:
  /** Wider invisible grab handle: generous picking without a fat visual. */
  private val grab = new Region
  grab.setStyle("-fx-background-color: transparent;")

  /** The accent line: shown while dragging, and on hover after a short delay (VS Code's sash). */
  private val sash = new Region
  sash.getStyleClass.add("dock-divider-sash")
  sash.setMouseTransparent(true)

  private val hoverDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250))

  private var axis: Axis                                              = Axis.Horizontal
  private[fx] var dragContext: () => Option[DividerView.DragContext]  = () => None
  private[fx] var onCommit: (Double, DividerView.DragContext) => Unit = (_, _) => ()

  /** Live resize: lay the split out at this fraction without committing a state change. */
  private[fx] var onPreview: (Double, DividerView.DragContext) => Unit = (_, _) => ()
  private[fx] var onPreviewEnd: () => Unit                             = () => ()

  private var active: Option[(Double, DividerView.DragContext)] = None

  /** The scene whose cursor the gesture borrowed, and the cursor to hand back. */
  private var borrowed: Option[(javafx.scene.Scene, javafx.scene.Cursor)] = None

  // Everything below touches this Region's inherited JavaFX surface, so it runs only once every
  // field above is initialized (checked by -Wsafe-init).
  getStyleClass.add("dock-divider")
  getChildren.addAll(sash, grab)

  hoverDelay.setOnFinished(_ => pseudoClassStateChanged(pseudo.Hot, true))
  hoverProperty.addListener: (_, _, hovering) =>
    if hovering then hoverDelay.playFromStart()
    else
      hoverDelay.stop()
      pseudoClassStateChanged(pseudo.Hot, false)

  private def endGesture(): Unit =
    active = None
    pseudoClassStateChanged(pseudo.Dragging, false)
    borrowed.foreach((scene, cursor) => scene.setCursor(cursor))
    borrowed = None
    onPreviewEnd()

  // a divider removed mid-gesture (its split dissolved) never sees the release: clean up now
  sceneProperty.addListener: (_, _, scene) =>
    if scene == null && active.isDefined then endGesture()

  private def fractionOf(delta: Double, ctx: DividerView.DragContext): Double =
    (ctx.aPx + delta) / (ctx.aPx + ctx.bPx)

  setOnMousePressed: e =>
    dragContext().foreach: ctx =>
      active = Some((pointerCoord(e), ctx))
      pseudoClassStateChanged(pseudo.Dragging, true)
      // hold the resize cursor for the whole gesture, even when the pointer outruns the strip
      borrowed = Option(getScene).map(scene => (scene, scene.getCursor))
      borrowed.foreach((scene, _) => scene.setCursor(getCursor))
    e.consume()

  setOnMouseDragged: e =>
    active.foreach: (start, ctx) =>
      if ctx.aPx + ctx.bPx > 0 then
        onPreview(fractionOf(clampedDelta(pointerCoord(e) - start, ctx), ctx), ctx)
    e.consume()

  setOnMouseReleased: e =>
    active.foreach: (start, ctx) =>
      val delta = clampedDelta(pointerCoord(e) - start, ctx)
      endGesture()
      if ctx.aPx + ctx.bPx > 0 then onCommit(fractionOf(delta, ctx), ctx)
    e.consume()

  private def pointerCoord(e: javafx.scene.input.MouseEvent): Double = axis match
    case Axis.Horizontal => e.getScreenX
    case Axis.Vertical   => e.getScreenY

  private def clampedDelta(delta: Double, ctx: DividerView.DragContext): Double =
    delta.max(-(ctx.aPx - ctx.minA)).min(ctx.bPx - ctx.minB)

  def setAxis(a: Axis): Unit =
    axis = a
    // the strip between horizontally-arranged cells is a vertical bar
    pseudoClassStateChanged(pseudo.Vertical, a == Axis.Horizontal)
    setCursor(a match
      case Axis.Horizontal => javafx.scene.Cursor.H_RESIZE
      case Axis.Vertical   => javafx.scene.Cursor.V_RESIZE)

  override def layoutChildren(): Unit =
    val reach = DividerView.GrabReachPx
    val t     = DividerView.SashPx
    axis match
      case Axis.Horizontal =>
        grab.resizeRelocate(-reach, 0, getWidth + 2 * reach, getHeight)
        sash.resizeRelocate((getWidth - t) / 2, 0, t, getHeight)
      case Axis.Vertical =>
        grab.resizeRelocate(0, -reach, getWidth, getHeight + 2 * reach)
        sash.resizeRelocate(0, (getHeight - t) / 2, getWidth, t)
end DividerView

object DividerView:
  /** Extra pixels of grab area on each side of the visible strip. */
  val GrabReachPx: Double = 4

  /** Thickness of the accent line drawn over the (hairline) divider when hot or dragging. */
  val SashPx: Double = 2

  /** Everything a drag needs, captured at press time: the two neighbours' current pixels, their
    * recursive minimums, and the split's main-axis span.
    */
  final case class DragContext(aPx: Double, bPx: Double, minA: Double, minB: Double, span: Double)
