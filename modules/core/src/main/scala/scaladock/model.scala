package scaladock

/** Header configuration for a tab group. `Inherit` falls through to the layout default. */
enum Header derives CanEqual:
  case Inherit
  case Hidden
  case Shown(buttons: HeaderButtons)

object Header:
  /** The effective chrome of a group's header: `None` means no header bar at all. */
  def resolve(h: Header, default: HeaderButtons): Option[HeaderButtons] = h match
    case Header.Inherit  => Some(default)
    case Header.Hidden   => None
    case Header.Shown(b) => Some(b)

final case class HeaderButtons(
    close: Boolean = true,
    maximize: Boolean = true,
    popOut: Boolean = true
) derives CanEqual

/** A pane: a leaf of the layout carrying typed user content. Panes live only inside groups. */
final case class Pane(
    id: PaneId,
    content: PaneContent,
    title: String,
    closable: Boolean = true
) derives CanEqual

/** A cell of a split: a child node plus its share of the split's main axis. */
final case class Cell(node: Node, size: Size = Size.Fr(1), minPx: Double = 0) derives CanEqual

/** The layout tree. Two shapes only: splits partition space; groups tab panes.
  *
  * The grammar itself carries golden-layout's two central invariants: groups hold only panes
  * (`tabs: Vector[Pane]`), and panes cannot appear outside a group (`Pane` is not a `Node`).
  */
enum Node derives CanEqual:
  case Split(id: NodeId, axis: Axis, cells: Vector[Cell])
  case Group(id: NodeId, tabs: Vector[Pane], active: PaneId, header: Header = Header.Inherit)

  /** All groups in this subtree, depth-first. */
  def groups: Vector[Node.Group] = this match
    case g: Group           => Vector(g)
    case Split(_, _, cells) => cells.flatMap(_.node.groups)

  /** All panes in this subtree, depth-first. */
  def panes: Vector[Pane] = groups.flatMap(_.tabs)

  def findGroup(id: NodeId): Option[Node.Group] = groups.find(_.id == id)

object Node:
  extension (n: Node)
    /** The node's stable identity (uniform across both cases). */
    def id: NodeId = n match
      case Split(id, _, _)    => id
      case Group(id, _, _, _) => id

  /** A fresh single-pane group — the canonical way a pane enters a tree. */
  def solo(pane: Pane, header: Header = Header.Inherit): Node.Group =
    Node.Group(NodeId.fresh(), Vector(pane), pane.id, header)

/** Where a floating window's content re-docks: expressed relative to a *sibling* node (not the
  * parent split, which dissolves when only the sibling remains — the everyday case of popping one
  * half of a pair). Restoring inserts on the given edge of the sibling with the recorded sizes,
  * falling back to a window edge if the sibling is gone too.
  */
final case class Anchor(
    sibling: NodeId,
    edge: Edge,
    size: Size = Size.Fr(1),
    siblingSize: Size = Size.Fr(1),
    minPx: Double = 0
) derives CanEqual

/** A popped-out branch of the layout living in its own OS window. */
final case class Floating(
    window: WindowId,
    bounds: Rect, // screen coordinates
    root: Node,
    home: Option[Anchor]
) derives CanEqual

/** The whole layout — one immutable value, including floating windows.
  *
  * golden-layout's invisible GroundItem collapses into `root: Option[Node]`; its
  * one-maximised-stack rule is carried by `maximized: Option[NodeId]`.
  */
final case class LayoutState(
    root: Option[Node],
    floating: Vector[Floating] = Vector.empty,
    maximized: Option[NodeId] = None,
    focused: Option[PaneId] = None
) derives CanEqual:

  /** Roots of every window: the main window first, then floating windows. */
  def roots: Vector[Node] = root.toVector ++ floating.map(_.root)

  def groups: Vector[Node.Group] = roots.flatMap(_.groups)
  def panes: Vector[Pane]        = roots.flatMap(_.panes)

  def findPane(id: PaneId): Option[Pane]        = panes.find(_.id == id)
  def findGroup(id: NodeId): Option[Node.Group] = groups.find(_.id == id)

  def findSplit(id: NodeId): Option[Node.Split] =
    def go(n: Node): Option[Node.Split] = n match
      case sp: Node.Split if sp.id == id => Some(sp)
      case Node.Split(_, _, cells)       => cells.iterator.flatMap(c => go(c.node)).nextOption()
      case _                             => None
    roots.iterator.flatMap(go).nextOption()
  def groupOf(pane: PaneId): Option[Node.Group] = groups.find(_.tabs.exists(_.id == pane))
  def isEmpty: Boolean                          = root.isEmpty && floating.isEmpty

object LayoutState:
  val empty: LayoutState          = LayoutState(None)
  def of(node: Node): LayoutState = LayoutState(Some(node))

/** The final say of a completed drop: what the layout does with the dragged pane. */
enum DropTarget derives CanEqual:
  /** Insert as a tab of an existing group at the given index (a header drop). */
  case IntoGroup(group: NodeId, tabIndex: Int)

  /** Split beside an existing group, 50/50, merging into the parent split if the axis matches. */
  case Beside(group: NodeId, edge: Edge)

  /** Dock against a whole window edge (`None` = the main window). */
  case AtWindowEdge(window: Option[WindowId], edge: Edge)

/** A hoverable region of the drop-target snapshot taken at drag start. The zone says what kind of
  * target the pointer is over; converting it to a [[DropTarget]] (e.g. computing the tab-insertion
  * index from the pointer's x within a header) is the interaction layer's job.
  */
enum DropZone derives CanEqual:
  case HeaderOf(group: NodeId)
  case BodyEdge(group: NodeId, edge: Edge)
  case WindowEdge(window: Option[WindowId], edge: Edge)
  case EmptyWindow(window: Option[WindowId])

/** A drop-snapshot entry: where the pointer must be (`hover`) and what to light up (`highlight`).
  */
final case class DropArea(hover: Rect, highlight: Rect, zone: DropZone) derives CanEqual

/** Where a programmatic `open` places a new pane — golden-layout's location selectors as data. */
enum DockAt derives CanEqual:
  /** The focused group, else the first group, else a new root group. Always succeeds. */
  case Preferred
  case InGroup(group: NodeId, tabIndex: Int)
  case Beside(group: NodeId, edge: Edge)
  case AtEdge(edge: Edge)
