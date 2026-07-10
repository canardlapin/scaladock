package scaladock.fx

import javafx.css.PseudoClass
import javafx.scene.Node as FxNode
import javafx.scene.control.{Label, MenuButton, MenuItem}
import javafx.scene.input.MouseButton
import javafx.scene.layout.{HBox, Priority, Region, StackPane}
import scaladock.*

private[fx] object pseudo:
  val Selected: PseudoClass = PseudoClass.getPseudoClass("selected")
  val Active: PseudoClass   = PseudoClass.getPseudoClass("active")
  val Vertical: PseudoClass = PseudoClass.getPseudoClass("vertical")
  val Dragging: PseudoClass = PseudoClass.getPseudoClass("dragging")

/** The visible face of one [[Node.Group]]: a tab header above a content host. The chrome (tabs,
  * buttons, overflow menu) is cheap and rebuilt freely; the hosted pane nodes are only ever
  * reparented.
  */
private[fx] final class GroupView(val nodeId: NodeId, settings: LayoutSettings) extends Region:
  getStyleClass.add("dock-group")

  // gesture callbacks, wired once by Dock at creation
  private[fx] var onTabActivated: PaneId => Unit                                 = _ => ()
  private[fx] var onTabPressed: (PaneId, javafx.scene.input.MouseEvent) => Unit  = (_, _) => ()
  private[fx] var onTabDragged: (PaneId, javafx.scene.input.MouseEvent) => Unit  = (_, _) => ()
  private[fx] var onTabReleased: (PaneId, javafx.scene.input.MouseEvent) => Unit = (_, _) => ()
  private[fx] var onTabClosed: PaneId => Unit                                    = _ => ()
  private[fx] var onGroupClosed: () => Unit                                      = () => ()
  private[fx] var onMaximizeToggled: () => Unit                                  = () => ()
  private[fx] var onPopOut: () => Unit                                           = () => ()
  private[fx] var onContentResized: (PaneId, Double, Double) => Unit             = (_, _, _) => ()

  private val tabsBox = new HBox
  tabsBox.getStyleClass.add("dock-tabs")

  private val overflow = new MenuButton("⋯") // ⋯
  overflow.getStyleClass.add("dock-tab-overflow")
  overflow.setVisible(false)
  overflow.setFocusTraversable(false)

  private val popOutButton = headerButton("↗", "popout") // ↗
  popOutButton.setOnMouseClicked(_ => onPopOut())

  private val maximizeButton = headerButton("□", "maximize") // □
  maximizeButton.setOnMouseClicked(_ => onMaximizeToggled())

  private val closeButton = headerButton("✕", "close") // ✕
  closeButton.setOnMouseClicked(_ => onGroupClosed())

  private val buttons = new HBox(popOutButton, maximizeButton, closeButton)
  buttons.getStyleClass.add("dock-header-buttons")

  private val header = new HBox(tabsBox, overflow, buttons)
  header.getStyleClass.add("dock-header")
  HBox.setHgrow(tabsBox, Priority.ALWAYS)

  private val content = new StackPane
  content.getStyleClass.add("dock-content")

  getChildren.addAll(header, content)

  private var activePane: Option[PaneId] = None
  private var headerVisible              = true

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
      nodeFor: PaneId => Option[FxNode]
  ): Unit =
    activePane = Some(group.active)
    headerVisible = chrome.isDefined
    header.setVisible(headerVisible)
    header.setManaged(headerVisible)

    val tabs = group.tabs.map(pane => makeTab(pane, pane.id == group.active))
    tabsBox.getChildren.setAll(tabs*)

    overflow.getItems.setAll(group.tabs.map { pane =>
      val item = new MenuItem(pane.title)
      item.setOnAction(_ => onTabActivated(pane.id))
      item
    }*)

    chrome.foreach: b =>
      popOutButton.setVisible(b.popOut)
      popOutButton.setManaged(b.popOut)
      maximizeButton.setVisible(b.maximize)
      maximizeButton.setManaged(b.maximize)
      maximizeButton.setText(if isMaximized then "❐" else "□") // ❐ / □
      val closable = b.close && group.tabs.forall(_.closable)
      closeButton.setVisible(closable)
      closeButton.setManaged(closable)

    nodeFor(group.active) match
      case Some(node) if content.getChildren.size == 1 && (content.getChildren.get(0) eq node) =>
        () // already hosting the right node: do not touch it
      case Some(node) => content.getChildren.setAll(node)
      case None       => content.getChildren.clear()

  private def makeTab(pane: Pane, selected: Boolean): FxNode =
    val title = new Label(pane.title)
    title.getStyleClass.add("dock-tab-title")
    title.setMaxHeight(Double.MaxValue)

    val close = new Label("✕")
    close.getStyleClass.add("dock-tab-close")
    close.setVisible(pane.closable)
    close.setManaged(pane.closable)
    close.setOnMouseClicked: e =>
      onTabClosed(pane.id)
      e.consume()

    val tab = new HBox(title, close)
    tab.getStyleClass.add("dock-tab")
    tab.pseudoClassStateChanged(pseudo.Selected, selected)
    tab.setOnMousePressed: e =>
      if e.getButton == MouseButton.PRIMARY then onTabPressed(pane.id, e)
    tab.setOnMouseDragged(e => onTabDragged(pane.id, e))
    tab.setOnMouseReleased(e => onTabReleased(pane.id, e))
    tab.setOnMouseClicked: e =>
      if e.getButton == MouseButton.MIDDLE && pane.closable then onTabClosed(pane.id)
    tab

  /** The tab-insertion index for a header drop at the given screen x: the number of tabs whose
    * midpoint lies left of the pointer.
    */
  def tabIndexAt(screenX: Double): Int =
    val local = tabsBox.screenToLocal(screenX, 0)
    if local == null then tabsBox.getChildren.size
    else
      var i = 0
      tabsBox.getChildren.forEach: t =>
        if t.getLayoutX + t.getLayoutBounds.getWidth / 2 < local.getX then i += 1
      i

  private def headerButton(glyph: String, kind: String): Label =
    val b = new Label(glyph)
    b.getStyleClass.addAll("dock-header-button", kind)
    b.setMaxHeight(Double.MaxValue)
    b

  def setActiveStyle(focused: Boolean): Unit =
    pseudoClassStateChanged(pseudo.Active, focused)

  override def layoutChildren(): Unit =
    val w       = getWidth
    val headerH = if headerVisible then math.min(settings.headerPx, getHeight) else 0.0
    header.resizeRelocate(0, 0, w, headerH)
    content.resizeRelocate(0, headerH, w, math.max(0, getHeight - headerH))
    // tab overflow: show the ⋯ menu when the tab strip wants more room than it has
    val wanted = tabsBox.prefWidth(-1)
    overflow.setVisible(headerVisible && wanted > tabsBox.getWidth + 0.5)

end GroupView

/** A divider strip between two cells of a split. During a drag only the strip itself moves
  * (translated, clamped to both subtrees' recursive minimums); one transition commits the new ratio
  * on release — golden-layout's ghost-then-commit semantics.
  */
private[fx] final class DividerView(val splitId: NodeId, val index: Int) extends Region:
  getStyleClass.add("dock-divider")

  /** Wider invisible grab handle: generous picking without a fat visual. */
  private val grab = new Region
  grab.setStyle("-fx-background-color: transparent;")
  getChildren.add(grab)

  private var axis: Axis                                              = Axis.Horizontal
  private[fx] var dragContext: () => Option[DividerView.DragContext]  = () => None
  private[fx] var onCommit: (Double, DividerView.DragContext) => Unit = (_, _) => ()

  private var active: Option[(Double, DividerView.DragContext)] = None

  setOnMousePressed: e =>
    dragContext().foreach: ctx =>
      active = Some((pointerCoord(e), ctx))
      pseudoClassStateChanged(pseudo.Dragging, true)
    e.consume()

  setOnMouseDragged: e =>
    active.foreach: (start, ctx) =>
      translate(clampedDelta(pointerCoord(e) - start, ctx))
    e.consume()

  setOnMouseReleased: e =>
    active.foreach: (start, ctx) =>
      val delta = clampedDelta(pointerCoord(e) - start, ctx)
      translate(0)
      pseudoClassStateChanged(pseudo.Dragging, false)
      active = None
      if ctx.aPx + ctx.bPx > 0 then onCommit((ctx.aPx + delta) / (ctx.aPx + ctx.bPx), ctx)
    e.consume()

  private def pointerCoord(e: javafx.scene.input.MouseEvent): Double = axis match
    case Axis.Horizontal => e.getScreenX
    case Axis.Vertical   => e.getScreenY

  private def clampedDelta(delta: Double, ctx: DividerView.DragContext): Double =
    delta.max(-(ctx.aPx - ctx.minA)).min(ctx.bPx - ctx.minB)

  private def translate(v: Double): Unit = axis match
    case Axis.Horizontal => setTranslateX(v)
    case Axis.Vertical   => setTranslateY(v)

  def setAxis(a: Axis): Unit =
    axis = a
    // the strip between horizontally-arranged cells is a vertical bar
    pseudoClassStateChanged(pseudo.Vertical, a == Axis.Horizontal)
    setCursor(a match
      case Axis.Horizontal => javafx.scene.Cursor.H_RESIZE
      case Axis.Vertical   => javafx.scene.Cursor.V_RESIZE)

  override def layoutChildren(): Unit =
    val reach = DividerView.GrabReachPx
    axis match
      case Axis.Horizontal => grab.resizeRelocate(-reach, 0, getWidth + 2 * reach, getHeight)
      case Axis.Vertical   => grab.resizeRelocate(0, -reach, getWidth, getHeight + 2 * reach)
end DividerView

object DividerView:
  /** Extra pixels of grab area on each side of the visible strip. */
  val GrabReachPx: Double = 4

  /** Everything a drag needs, captured at press time: the two neighbours' current pixels, their
    * recursive minimums, and the split's main-axis span.
    */
  final case class DragContext(aPx: Double, bPx: Double, minA: Double, minB: Double, span: Double)
