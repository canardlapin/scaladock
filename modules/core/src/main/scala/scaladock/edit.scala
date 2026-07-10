package scaladock

/** Pure transitions over [[LayoutState]]. Every public function is total and returns a canonical
  * state; the interaction layer commits exactly one transition per user gesture.
  */
object edit:

  // ---------------------------------------------------------------------------------------
  // Canonicalization — the tree-simplification laws
  // ---------------------------------------------------------------------------------------

  /** Canonical form: no empty groups, no single-cell splits, no same-axis nested splits, every
    * group's active tab present, no dangling maximized/focused/floating references.
    *
    * Laws (property-tested): idempotent; preserves the multiset of pane ids.
    */
  def canonical(s: LayoutState): LayoutState =
    val root = s.root.flatMap(canonicalRoot)
    val floating = s.floating.flatMap: f =>
      canonicalRoot(f.root).map(r => f.copy(root = r))
    val next = s.copy(root = root, floating = floating)
    next.copy(
      maximized = next.maximized.filter(id => next.findGroup(id).isDefined),
      focused = next.focused.filter(id => next.findPane(id).isDefined)
    )

  private def canonicalRoot(n: Node): Option[Node] =
    canonicalCell(Cell(n)).map(_.node)

  /** Canonicalize a subtree in its cell, so a dissolving single-cell split can merge its inner
    * cell's min constraint into the surviving slot instead of losing it.
    */
  private def canonicalCell(c: Cell): Option[Cell] = c.node match
    case g @ Node.Group(_, tabs, active, _) =>
      Option.when(tabs.nonEmpty):
        val repaired = if tabs.exists(_.id == active) then g else g.copy(active = tabs.head.id)
        c.copy(node = repaired)
    case Node.Split(id, axis, cells) =>
      val kept = cells.flatMap(canonicalCell)
      val flat = kept.flatMap:
        case Cell(Node.Split(_, a, inner), size, _) if a == axis => rescale(inner, size)
        case cc                                                  => Vector(cc)
      flat match
        case Vector()     => None
        case Vector(only) =>
          // dissolve: the child takes this slot (this size), keeping the stricter min
          Some(Cell(only.node, c.size, math.max(c.minPx, only.minPx)))
        case many => Some(c.copy(node = Node.Split(id, axis, many)))

  /** Scale a dissolving same-axis split's cells into their parent's slot, preserving relative
    * proportions where the units allow it (best effort; allocation renormalizes anyway).
    */
  private def rescale(inner: Vector[Cell], outer: Size): Vector[Cell] =
    val frSum = inner.collect { case Cell(_, Size.Fr(w), _) => w }.sum
    outer match
      case Size.Fr(w) if frSum > 0 =>
        inner.map:
          case c @ Cell(_, Size.Fr(wi), _) => c.copy(size = Size.Fr(w * wi / frSum))
          case c                           => c
      case Size.Pct(p) if frSum > 0 =>
        inner.map:
          case c @ Cell(_, Size.Fr(wi), _) => c.copy(size = Size.Pct(p * wi / frSum))
          case c                           => c
      case _ => inner

  // ---------------------------------------------------------------------------------------
  // Structural queries
  // ---------------------------------------------------------------------------------------

  /** The split containing `id` and the index of its cell there, searched across all windows. */
  def parentOf(s: LayoutState, id: NodeId): Option[(Node.Split, Int)] =
    s.roots.iterator.flatMap(parentIn(_, id)).nextOption()

  private def parentIn(root: Node, id: NodeId): Option[(Node.Split, Int)] = root match
    case _: Node.Group => None
    case sp @ Node.Split(_, _, cells) =>
      cells.indexWhere(_.node.id == id) match
        case -1 => cells.iterator.flatMap(c => parentIn(c.node, id)).nextOption()
        case i  => Some((sp, i))

  // ---------------------------------------------------------------------------------------
  // Node rewriting machinery (private)
  // ---------------------------------------------------------------------------------------

  /** Rewrite the node with the given id, wherever it lives (main root or floating). */
  private def rewriteNode(s: LayoutState, id: NodeId)(f: Node => Node): LayoutState =
    s.copy(
      root = s.root.map(rewriteIn(_, id, f)),
      floating = s.floating.map(fl => fl.copy(root = rewriteIn(fl.root, id, f)))
    )

  private def rewriteIn(root: Node, id: NodeId, f: Node => Node): Node =
    if root.id == id then f(root)
    else
      root match
        case g: Node.Group => g
        case sp @ Node.Split(_, _, cells) =>
          sp.copy(cells = cells.map(c => c.copy(node = rewriteIn(c.node, id, f))))

  private def mapGroups(s: LayoutState)(f: Node.Group => Node.Group): LayoutState =
    def go(n: Node): Node = n match
      case g: Node.Group => f(g)
      case sp @ Node.Split(_, _, cells) =>
        sp.copy(cells = cells.map(c => c.copy(node = go(c.node))))
    s.copy(
      root = s.root.map(go),
      floating = s.floating.map(fl => fl.copy(root = go(fl.root)))
    )

  private def halved(size: Size): Size = size match
    case Size.Fr(w)  => Size.Fr(w / 2)
    case Size.Pct(p) => Size.Pct(p / 2)
    case Size.Px(v)  => Size.Px(v / 2)

  /** Dock a node against a window edge: merge into a same-axis top-level split (halving the
    * adjacent neighbour, as golden-layout does) or wrap the old root 50/50.
    */
  private def dockAtEdge(root: Option[Node], incoming: Node, edge: Edge): Node = root match
    case None => incoming
    case Some(Node.Split(id, axis, cells)) if axis == edge.axis && cells.nonEmpty =>
      val i        = if edge.leading then 0 else cells.length - 1
      val neighbor = cells(i).copy(size = halved(cells(i).size))
      val fresh    = Cell(incoming, neighbor.size)
      val pair     = if edge.leading then Vector(fresh, neighbor) else Vector(neighbor, fresh)
      Node.Split(id, axis, cells.patch(i, pair, 1))
    case Some(old) =>
      val pair =
        if edge.leading then Vector(Cell(incoming), Cell(old))
        else Vector(Cell(old), Cell(incoming))
      Node.Split(NodeId.fresh(), edge.axis, pair)

  /** Insert `incoming` on the given edge of the node `target`: as a sibling if the enclosing
    * split's axis matches, else by wrapping the target in a fresh split.
    */
  private def insertBeside(
      s: LayoutState,
      target: NodeId,
      incoming: Cell,
      edge: Edge,
      targetSize: Option[Size] = None
  ): LayoutState =
    parentOf(s, target) match
      case Some((sp, i)) if sp.axis == edge.axis =>
        rewriteNode(s, sp.id):
          case Node.Split(id, axis, cells) =>
            val at = if edge.leading then i else i + 1
            Node.Split(id, axis, cells.patch(at, Vector(incoming), 0))
          case other => other
      case _ =>
        rewriteNode(s, target): old =>
          val oldCell = Cell(old, targetSize.getOrElse(Size.Fr(1)))
          val pair =
            if edge.leading then Vector(incoming, oldCell) else Vector(oldCell, incoming)
          Node.Split(NodeId.fresh(), edge.axis, pair)

  // ---------------------------------------------------------------------------------------
  // Panes: insert, remove, detach
  // ---------------------------------------------------------------------------------------

  /** Remove a pane wherever it is; cascading collapse via canonical. */
  def removePane(s: LayoutState, pane: PaneId): LayoutState =
    canonical(mapGroups(s)(g => g.copy(tabs = g.tabs.filterNot(_.id == pane))))

  /** Detach a pane for a drag: the remaining tree collapses; the pane is returned for the drop that
    * follows (or for returning home on a cancelled drag).
    */
  def detach(s: LayoutState, pane: PaneId): (LayoutState, Option[Pane]) =
    (removePane(s, pane), s.findPane(pane))

  def close(s: LayoutState, pane: PaneId): LayoutState = removePane(s, pane)

  /** Complete a drop: golden-layout's nine cases, total over the model.
    *
    * Totality is meant literally: the pane is never lost. If the target no longer exists (the drag
    * itself may have dissolved it — detaching a group's only tab removes the group), the pane docks
    * at the window's right edge instead. Dropping a pane that is still attached first detaches it,
    * so drop is safely idempotent.
    */
  def drop(s: LayoutState, pane: Pane, target: DropTarget): LayoutState =
    val base = unmaximized(removePane(s, pane.id))
    val next = target match
      case DropTarget.IntoGroup(gid, tabIndex) =>
        mapGroups(base): g =>
          if g.id != gid then g
          else
            val i = tabIndex.max(0).min(g.tabs.length)
            g.copy(tabs = g.tabs.patch(i, Vector(pane), 0), active = pane.id)

      case DropTarget.Beside(gid, edge) =>
        val incoming = Node.solo(pane)
        parentOf(base, gid) match
          case Some((sp, i)) if sp.axis == edge.axis =>
            // the axis already matches: become a sibling, splitting the target's share 50/50
            rewriteNode(base, sp.id):
              case Node.Split(id, axis, cells) =>
                val old  = cells(i)
                val half = halved(old.size)
                val pair =
                  if edge.leading then Vector(Cell(incoming, half), old.copy(size = half))
                  else Vector(old.copy(size = half), Cell(incoming, half))
                Node.Split(id, axis, cells.patch(i, pair, 1))
              case other => other
          case _ if base.findGroup(gid).isDefined =>
            // wrap the target group in a fresh split on the drop's axis, 50/50
            rewriteNode(base, gid): old =>
              val pair =
                if edge.leading then Vector(Cell(incoming), Cell(old))
                else Vector(Cell(old), Cell(incoming))
              Node.Split(NodeId.fresh(), edge.axis, pair)
          case _ => base // stale target: caught by the safety net below

      case DropTarget.AtWindowEdge(window, edge) =>
        val incoming = Node.solo(pane)
        window match
          case None => base.copy(root = Some(dockAtEdge(base.root, incoming, edge)))
          case Some(w) =>
            base.copy(floating = base.floating.map: fl =>
              if fl.window == w then fl.copy(root = dockAtEdge(Some(fl.root), incoming, edge))
              else fl)

    val ensured =
      if next.findPane(pane.id).isDefined then next
      else next.copy(root = Some(dockAtEdge(next.root, Node.solo(pane), Edge.Right)))
    canonical(ensured.copy(focused = Some(pane.id)))
  end drop

  /** Programmatic placement — the location-selector chain as data. Always succeeds. */
  def open(s: LayoutState, pane: Pane, at: DockAt = DockAt.Preferred): LayoutState = at match
    case DockAt.Preferred =>
      val targetGroup = s.focused.flatMap(f => s.groupOf(f)).orElse(s.groups.headOption)
      targetGroup match
        case Some(g) => drop(s, pane, DropTarget.IntoGroup(g.id, g.tabs.length))
        case None    => drop(s, pane, DropTarget.AtWindowEdge(None, Edge.Right))
    case DockAt.InGroup(g, i) => drop(s, pane, DropTarget.IntoGroup(g, i))
    case DockAt.Beside(g, e)  => drop(s, pane, DropTarget.Beside(g, e))
    case DockAt.AtEdge(e)     => drop(s, pane, DropTarget.AtWindowEdge(None, e))

  // ---------------------------------------------------------------------------------------
  // Tabs, focus, maximise
  // ---------------------------------------------------------------------------------------

  /** Make a pane its group's visible tab (without moving keyboard focus to it). */
  def activate(s: LayoutState, pane: PaneId): LayoutState =
    mapGroups(s): g =>
      if g.tabs.exists(_.id == pane) then g.copy(active = pane) else g

  /** Focus a pane: single focused pane per layout; focusing also activates its tab. */
  def focus(s: LayoutState, pane: PaneId): LayoutState =
    if s.findPane(pane).isEmpty then s
    else activate(s, pane).copy(focused = Some(pane))

  def maximize(s: LayoutState, group: NodeId): LayoutState =
    if s.findGroup(group).isDefined then s.copy(maximized = Some(group)) else s

  def unmaximized(s: LayoutState): LayoutState = s.copy(maximized = None)

  def toggleMaximize(s: LayoutState, group: NodeId): LayoutState =
    if s.maximized.contains(group) then unmaximized(s) else maximize(s, group)

  /** Retitle a pane in place (e.g. from a `PaneContext.setTitle`). */
  def retitle(s: LayoutState, pane: PaneId, title: String): LayoutState =
    mapGroups(s): g =>
      g.copy(tabs = g.tabs.map(p => if p.id == pane then p.copy(title = title) else p))

  // ---------------------------------------------------------------------------------------
  // Dividers
  // ---------------------------------------------------------------------------------------

  /** Commit a divider drag: `fraction` is the first neighbour's share of the two neighbours'
    * combined pixels; `span` is the split's main-axis extent in pixels (from the geometry the
    * renderer already has). Only the two adjacent cells change; the commit clamps to the recursive
    * minimum of each neighbour's whole subtree.
    */
  def dragDivider(
      s: LayoutState,
      split: NodeId,
      index: Int,
      fraction: Double,
      span: Double,
      settings: LayoutSettings
  ): LayoutState =
    rewriteNode(s, split):
      case Node.Split(id, axis, cells) if index >= 0 && index < cells.length - 1 =>
        Node.Split(id, axis, sizing.commitDivider(cells, axis, index, fraction, span, settings))
      case other => other

  // ---------------------------------------------------------------------------------------
  // Floating windows
  // ---------------------------------------------------------------------------------------

  /** Pop a subtree out into its own window. A sibling-relative anchor records where it re-docks.
    * Popping out a floating window's root is a no-op (it already floats); popping out always
    * restores from maximise first.
    */
  def popOut(s: LayoutState, node: NodeId, bounds: Rect): LayoutState =
    if s.floating.exists(_.root.id == node) then s
    else
      s.roots.iterator.flatMap(findNode(_, node)).nextOption() match
        case None => s
        case Some(subtree) =>
          val home = parentOf(s, node).map: (sp, i) =>
            val cell    = sp.cells(i)
            val sibIdx  = if i > 0 then i - 1 else i + 1
            val sibling = sp.cells(sibIdx)
            val edge = (sp.axis, i > 0) match
              case (Axis.Horizontal, true)  => Edge.Right
              case (Axis.Horizontal, false) => Edge.Left
              case (Axis.Vertical, true)    => Edge.Bottom
              case (Axis.Vertical, false)   => Edge.Top
            Anchor(sibling.node.id, edge, cell.size, sibling.size, cell.minPx)
          val pruned = pruneNode(unmaximized(s), node)
          canonical(
            pruned.copy(floating =
              pruned.floating :+ Floating(WindowId.fresh(), bounds, subtree, home)
            )
          )

  /** Re-dock a floating window at its home anchor, falling back to the right window edge. */
  def dockBack(s: LayoutState, window: WindowId): LayoutState =
    s.floating.find(_.window == window) match
      case None => s
      case Some(fl) =>
        val without = s.copy(floating = s.floating.filterNot(_.window == window))
        val anchored = fl.home
          .filter(a => without.roots.exists(r => findNode(r, a.sibling).isDefined))
          .map: a =>
            insertBeside(
              without,
              a.sibling,
              Cell(fl.root, a.size, a.minPx),
              a.edge,
              targetSize = Some(a.siblingSize)
            )
        canonical(anchored.getOrElse(
          without.copy(root = Some(dockAtEdge(without.root, fl.root, Edge.Right)))
        ))

  /** Record a floating window's live geometry (so saves capture reality). */
  def moveWindow(s: LayoutState, window: WindowId, bounds: Rect): LayoutState =
    s.copy(floating = s.floating.map: fl =>
      if fl.window == window then fl.copy(bounds = bounds) else fl)

  // ---------------------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------------------

  private def findNode(root: Node, id: NodeId): Option[Node] =
    if root.id == id then Some(root)
    else
      root match
        case _: Node.Group => None
        case Node.Split(_, _, cells) =>
          cells.iterator.flatMap(c => findNode(c.node, id)).nextOption()

  /** Remove the node with the given id from whichever window holds it. */
  private def pruneNode(s: LayoutState, id: NodeId): LayoutState =
    def prune(n: Node): Option[Node] = n match
      case node if node.id == id => None
      case g: Node.Group         => Some(g)
      case sp @ Node.Split(_, _, cells) =>
        Some(sp.copy(cells = cells.flatMap(c => prune(c.node).map(n => c.copy(node = n)))))
    s.copy(
      root = s.root.flatMap(prune),
      floating = s.floating.flatMap(fl => prune(fl.root).map(r => fl.copy(root = r)))
    )

end edit
