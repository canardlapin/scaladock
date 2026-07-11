package scaladock

/** Metrics the pure layout math needs; the rendering layer styles everything else. */
final case class LayoutSettings(
    dividerPx: Double = 5,
    headerPx: Double = 28,
    edgeBandPx: Double = 50,
    defaultMinPanePx: Double = 40
) derives CanEqual

object LayoutSettings:
  val default: LayoutSettings = LayoutSettings()

final case class GroupGeometry(bounds: Rect, header: Rect, content: Rect) derives CanEqual

final case class DividerGeometry(split: NodeId, index: Int, axis: Axis, bounds: Rect)
    derives CanEqual

/** Pixel-space realization of one window's tree: rects for every group, every divider strip, and
  * each group's header/content sub-rects. The renderer applies this verbatim; it never re-derives
  * layout math.
  */
final case class LayoutGeometry(
    groups: Map[NodeId, GroupGeometry],
    splits: Map[NodeId, Rect],
    dividers: Vector[DividerGeometry],
    maximized: Option[NodeId]
) derives CanEqual:
  def rectOf(id: NodeId): Option[Rect] = groups.get(id).map(_.bounds)

object LayoutGeometry:
  val empty: LayoutGeometry = LayoutGeometry(Map.empty, Map.empty, Vector.empty, None)

object sizing:

  /** Distribute `span` pixels along a split's main axis.
    *
    * `Px` cells take their pixels, `Pct` cells their share of the available span (span minus
    * divider strips), and `Fr` cells split what remains by weight. Shortfalls scale proportionally;
    * min-sizes are enforced afterwards by [[respectMinSizes]]. The result has one entry per cell
    * and sums exactly to the available span (when it is non-negative).
    */
  def allocate(cells: Vector[Cell], span: Double, dividerPx: Double): Vector[Double] =
    val n = cells.length
    if n == 0 then Vector.empty
    else
      val available = math.max(0.0, span - (n - 1) * dividerPx)
      val raw       = firstPass(cells, available)
      val mins      = cells.map(c => math.max(0.0, c.minPx))
      respectMinSizes(raw, mins, available)

  private def firstPass(cells: Vector[Cell], available: Double): Vector[Double] =
    val fixed = cells.map: c =>
      c.size match
        case Size.Px(v)  => math.max(0.0, v)
        case Size.Pct(v) => math.max(0.0, v) / 100.0 * available
        case Size.Fr(_)  => 0.0
    val weights = cells.map: c =>
      c.size match
        case Size.Fr(w) => math.max(0.0, w)
        case _          => 0.0
    val fixedSum  = fixed.sum
    val weightSum = weights.sum
    if weightSum > 0 && fixedSum < available then
      // fr cells share the remainder by weight
      val remainder = available - fixedSum
      cells.indices.toVector.map(i => fixed(i) + remainder * weights(i) / weightSum)
    else if fixedSum > 0 then
      // no fr capacity (or over-subscribed): scale fixed claims to fit exactly
      fixed.map(_ * available / fixedSum)
    else
      // nothing claims anything: split evenly
      Vector.fill(cells.length)(available / cells.length)

  /** golden-layout's min-size rule: take the shortfall of under-min cells out of the over-min
    * cells, proportionally to their slack. If total slack cannot cover the shortfall, mins are
    * violated proportionally (the container is simply too small).
    */
  private def respectMinSizes(
      alloc: Vector[Double],
      mins: Vector[Double],
      available: Double
  ): Vector[Double] =
    if mins.sum >= available then
      // infeasible: scale mins themselves to fit
      val minSum = mins.sum
      if minSum <= 0 then alloc else mins.map(_ * available / minSum)
    else
      val shortfall = alloc.indices.map(i => math.max(0.0, mins(i) - alloc(i))).sum
      if shortfall <= 0 then alloc
      else
        val slack = alloc.indices.map(i => math.max(0.0, alloc(i) - mins(i))).sum
        alloc.indices.toVector.map: i =>
          if alloc(i) < mins(i) then mins(i)
          else alloc(i) - (alloc(i) - mins(i)) / slack * shortfall

  /** Rewrite the two cells adjacent to a divider after a drag ends at `fraction` (the first
    * neighbour's share of their combined pixels). Only those two cells change; each keeps its unit
    * kind where possible (`Px` stays `Px`; `Pct`/`Fr` become `Pct` of the available span, matching
    * golden-layout's everything-normalizes-to-percent behaviour). The clamp respects the recursive
    * minimum of each neighbour's whole subtree, not just its own cell.
    */
  def commitDivider(
      cells: Vector[Cell],
      axis: Axis,
      index: Int,
      fraction: Double,
      span: Double,
      settings: LayoutSettings
  ): Vector[Cell] =
    require(index >= 0 && index < cells.length - 1, s"divider index $index out of range")
    val dividerPx = settings.dividerPx
    val available = math.max(0.0, span - (cells.length - 1) * dividerPx)
    if available <= 0 then cells
    else
      val alloc    = allocate(cells, span, dividerPx)
      val combined = alloc(index) + alloc(index + 1)
      val minA     = cellMin(cells(index), axis, settings)
      val minB     = cellMin(cells(index + 1), axis, settings)
      if minA + minB > combined then cells // both sides already below min: nothing sane to commit
      else
        commitAt(
          cells,
          index,
          (fraction * combined).max(minA).min(combined - minB),
          combined,
          available
        )

  private def commitAt(
      cells: Vector[Cell],
      index: Int,
      aPx: Double,
      combined: Double,
      available: Double
  ): Vector[Cell] =
    val bPx = combined - aPx
    def rewrite(cell: Cell, px: Double): Cell =
      cell.size match
        case Size.Px(_) => cell.copy(size = Size.Px(px))
        case _          => cell.copy(size = Size.Pct(px / available * 100.0))
    cells
      .updated(index, rewrite(cells(index), aPx))
      .updated(index + 1, rewrite(cells(index + 1), bPx))

  /** A cell's effective minimum along its split's axis: its own floor or its subtree's. */
  def cellMin(cell: Cell, axis: Axis, settings: LayoutSettings): Double =
    math.max(math.max(0.0, cell.minPx), minSpan(cell.node, axis, settings))

  /** Recursive minimum span of a subtree along `axis` — the constraint a divider drag must respect
    * on each side (golden-layout's calculateContentItemsTotalMinSize). A cell's own `minPx`
    * constrains it only along its split's axis.
    */
  def minSpan(node: Node, axis: Axis, settings: LayoutSettings): Double = node match
    case _: Node.Group =>
      axis match
        case Axis.Vertical   => settings.headerPx + settings.defaultMinPanePx
        case Axis.Horizontal => settings.defaultMinPanePx
    case Node.Split(_, splitAxis, cells) =>
      if splitAxis == axis then
        cells.map(c => cellMin(c, axis, settings)).sum + (cells.length - 1) * settings.dividerPx
      else if cells.isEmpty then 0.0
      else cells.map(c => minSpan(c.node, axis, settings)).max

  /** The extent of a minimised group's strip along its parent split's axis. */
  def stripPx(settings: LayoutSettings): Double = settings.headerPx

  /** Realize one window's tree in pixels. Minimised groups are *presented* as header-thin strips —
    * their cells are overridden to `Px(stripPx)` at allocation time only, so the stored sizes stay
    * untouched and restoring is exact by construction.
    */
  def geometry(
      root: Option[Node],
      viewport: Rect,
      settings: LayoutSettings,
      maximized: Option[NodeId] = None,
      minimized: Set[NodeId] = Set.empty
  ): LayoutGeometry =
    root match
      case None => LayoutGeometry.empty
      case Some(node) =>
        val groups   = Map.newBuilder[NodeId, GroupGeometry]
        val splits   = Map.newBuilder[NodeId, Rect]
        val dividers = Vector.newBuilder[DividerGeometry]

        def isMinimized(n: Node): Boolean = n match
          case g: Node.Group => minimized(g.id)
          case _             => false

        def place(n: Node, rect: Rect): Unit = n match
          case g: Node.Group if minimized(g.id) =>
            // the whole strip is chrome; the content rect is deliberately zero-extent.
            // Inside a split the rect is already strip-thin along the parent axis; a
            // minimised ROOT group has no parent to shrink it, so it becomes a top strip.
            val s            = stripPx(settings)
            val alreadyStrip = rect.width <= s + 0.5 || rect.height <= s + 0.5
            val strip =
              if alreadyStrip then rect
              else Rect(rect.x, rect.y, rect.width, math.min(s, rect.height))
            groups += g.id -> GroupGeometry(
              strip,
              strip,
              Rect(strip.x, strip.bottom, strip.width, 0)
            )
          case g: Node.Group =>
            val headerH = g.header match
              case Header.Hidden => 0.0
              case _             => math.min(settings.headerPx, rect.height)
            val header  = Rect(rect.x, rect.y, rect.width, headerH)
            val content = Rect(rect.x, rect.y + headerH, rect.width, rect.height - headerH)
            groups += g.id -> GroupGeometry(rect, header, content)
          case Node.Split(id, axis, cells) =>
            splits += id -> rect
            val span = axis match
              case Axis.Horizontal => rect.width
              case Axis.Vertical   => rect.height
            // two-phase allocation: minimised cells take EXACTLY their strip (never scaled,
            // whatever the sibling unit mix), and the live cells share what remains
            val strip     = stripPx(settings)
            val live      = cells.filterNot(c => isMinimized(c.node))
            val stripSum  = (cells.length - live.length) * strip
            val available = math.max(0.0, span - (cells.length - 1) * settings.dividerPx)
            val liveSpan = math.max(0.0, available - stripSum) +
              math.max(0, live.length - 1) * settings.dividerPx
            val liveSizes = allocate(live, liveSpan, settings.dividerPx).iterator
            val sizes     = cells.map(c => if isMinimized(c.node) then strip else liveSizes.next())
            var offset    = 0.0
            // a rect too small for its divider strips (available clamped to 0) must not leak
            // children past its own edge: clamp every placement into [0, span]
            def clamped(at: Double, extent: Double): (Double, Double) =
              val start = at.min(span)
              (start, extent.min(span - start).max(0.0))
            cells.indices.foreach: i =>
              val (cAt, cExtent) = clamped(offset, sizes(i))
              val childRect = axis match
                case Axis.Horizontal => Rect(rect.x + cAt, rect.y, cExtent, rect.height)
                case Axis.Vertical   => Rect(rect.x, rect.y + cAt, rect.width, cExtent)
              place(cells(i).node, childRect)
              offset += sizes(i)
              if i < cells.length - 1 then
                val (dAt, dExtent) = clamped(offset, settings.dividerPx)
                val strip = axis match
                  case Axis.Horizontal => Rect(rect.x + dAt, rect.y, dExtent, rect.height)
                  case Axis.Vertical   => Rect(rect.x, rect.y + dAt, rect.width, dExtent)
                dividers += DividerGeometry(id, i, axis, strip)
                offset += settings.dividerPx

        place(node, viewport)
        val base = LayoutGeometry(groups.result(), splits.result(), dividers.result(), None)

        // A maximised group overrides its own rect with the full viewport; the renderer
        // hides everything else. No tree surgery, exactly as planned.
        maximized.filter(base.groups.contains) match
          case None => base
          case Some(id) =>
            val headerH = math.min(settings.headerPx, viewport.height)
            val full = GroupGeometry(
              viewport,
              Rect(viewport.x, viewport.y, viewport.width, headerH),
              Rect(viewport.x, viewport.y + headerH, viewport.width, viewport.height - headerH)
            )
            base.copy(groups = base.groups.updated(id, full), maximized = Some(id))

  /** The drop-target snapshot for one window: golden-layout's area system. Hit testing picks the
    * smallest containing hover rect, so inner (group) zones naturally beat the broad window-edge
    * bands.
    */
  def dropAreas(
      root: Option[Node],
      geom: LayoutGeometry,
      viewport: Rect,
      window: Option[WindowId],
      settings: LayoutSettings
  ): Vector[DropArea] =
    root match
      case None =>
        Vector(DropArea(viewport, viewport, DropZone.EmptyWindow(window)))
      case Some(node) =>
        val band = settings.edgeBandPx
        val windowEdges = Vector(
          DropArea(
            Rect(viewport.x, viewport.y, band, viewport.height),
            Rect(viewport.x, viewport.y, viewport.width / 2, viewport.height),
            DropZone.WindowEdge(window, Edge.Left)
          ),
          DropArea(
            Rect(viewport.right - band, viewport.y, band, viewport.height),
            Rect(viewport.x + viewport.width / 2, viewport.y, viewport.width / 2, viewport.height),
            DropZone.WindowEdge(window, Edge.Right)
          ),
          DropArea(
            Rect(viewport.x, viewport.y, viewport.width, band),
            Rect(viewport.x, viewport.y, viewport.width, viewport.height / 2),
            DropZone.WindowEdge(window, Edge.Top)
          ),
          DropArea(
            Rect(viewport.x, viewport.bottom - band, viewport.width, band),
            Rect(viewport.x, viewport.y + viewport.height / 2, viewport.width, viewport.height / 2),
            DropZone.WindowEdge(window, Edge.Bottom)
          )
        )
        // while a group is maximised only ITS zones exist — everything else is invisible
        val targetableGroups = geom.maximized match
          case Some(id) => node.groups.filter(_.id == id)
          case None     => node.groups
        val groupZones = targetableGroups.flatMap: g =>
          geom.groups.get(g.id).toVector.flatMap: gg =>
            val c = gg.content
            Vector(
              DropArea(gg.header, gg.header, DropZone.HeaderOf(g.id)),
              // hover 25% band, highlight 50% — golden-layout's stack body carve-up
              DropArea(
                Rect(c.x, c.y, c.width * 0.25, c.height),
                Rect(c.x, c.y, c.width * 0.5, c.height),
                DropZone.BodyEdge(g.id, Edge.Left)
              ),
              DropArea(
                Rect(c.x + c.width * 0.75, c.y, c.width * 0.25, c.height),
                Rect(c.x + c.width * 0.5, c.y, c.width * 0.5, c.height),
                DropZone.BodyEdge(g.id, Edge.Right)
              ),
              DropArea(
                Rect(c.x + c.width * 0.25, c.y, c.width * 0.5, c.height * 0.5),
                Rect(c.x, c.y, c.width, c.height * 0.5),
                DropZone.BodyEdge(g.id, Edge.Top)
              ),
              DropArea(
                Rect(c.x + c.width * 0.25, c.y + c.height * 0.5, c.width * 0.5, c.height * 0.5),
                Rect(c.x, c.y + c.height * 0.5, c.width, c.height * 0.5),
                DropZone.BodyEdge(g.id, Edge.Bottom)
              )
            )
        (if geom.maximized.isEmpty then windowEdges else Vector.empty) ++ groupZones

  /** Smallest containing hover rect wins — nested targets beat broad ones. */
  def hitTest(areas: Vector[DropArea], p: Point): Option[DropArea] =
    areas.filter(_.hover.contains(p)) match
      case Vector() => None
      case hits     => Some(hits.minBy(_.hover.area))

end sizing
