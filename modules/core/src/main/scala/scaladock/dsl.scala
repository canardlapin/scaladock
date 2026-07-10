package scaladock

/** The layout construction language. A layout description should read like a picture of the layout
  * — plain expressions building the immutable model, no builders, no implicit magic:
  *
  * {{{
  * val layout = LayoutState.of(
  *   row(
  *     Explorer(ExplorerState(root)) sized 260.px,
  *     column(
  *       row(group(Viewer(a)), group(Viewer(b))) sized 1.fr,
  *       group(Console(ConsoleState.empty), Problems(())) sized 220.px
  *     ) sized 1.fr,
  *     group(Outline(()), Layers(defaults)) sized 280.px
  *   )
  * )
  * }}}
  *
  * Union types let panes, nodes, and sized slots mix freely as split children; a bare pane
  * auto-wraps into its own tab group — the model's invariant, honoured at the source.
  */
object dsl:

  /** Size literals travel with the language: `import scaladock.dsl.*` is all a layout needs. */
  export Size.{fr, pct, px}

  /** A split child with its share attached. */
  final case class Slot(cell: Cell)

  /** Anything that can occupy a slot in a split. */
  type SlotLike = Node | PaneDef | Slot

  private def toCell(item: SlotLike): Cell = item match
    case n: Node    => Cell(n)
    case p: PaneDef => Cell(Node.solo(p.toPane))
    case s: Slot    => s.cell

  /** A horizontal split: children side by side. A single child is just itself. */
  def row(items: SlotLike*): Node = split(Axis.Horizontal, items)

  /** A vertical split: children stacked top to bottom. A single child is just itself. */
  def column(items: SlotLike*): Node = split(Axis.Vertical, items)

  private def split(axis: Axis, items: Seq[SlotLike]): Node = items match
    case Seq(one) => toCell(one).node
    case many     => Node.Split(NodeId.fresh(), axis, many.iterator.map(toCell).toVector)

  /** A tab group; the first pane is the active tab. */
  def group(first: PaneDef, rest: PaneDef*): Node =
    val tabs = (first +: rest).iterator.map(_.toPane).toVector
    Node.Group(NodeId.fresh(), tabs, tabs.head.id)

  extension (item: SlotLike)
    /** Attach a share of the parent split: `pane sized 260.px`, `node sized 2.fr`. */
    infix def sized(s: Size): Slot = Slot(toCell(item).copy(size = s))

    /** Attach a minimum extent in pixels along the parent split's axis. */
    infix def atLeast(px: Double): Slot = Slot(toCell(item).copy(minPx = px))

end dsl
