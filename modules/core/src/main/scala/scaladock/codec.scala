package scaladock

/** The registry of pane types known at load time, keyed by wire tag. */
final class PaneTypes private (private val byName: Map[String, PaneType[?]]):
  def get(name: String): Option[PaneType[?]] = byName.get(name)
  def and(more: PaneType[?]*): PaneTypes     = new PaneTypes(byName ++ more.map(t => t.name -> t))

object PaneTypes:
  val empty: PaneTypes                      = new PaneTypes(Map.empty)
  def apply(types: PaneType[?]*): PaneTypes = empty.and(types*)

final case class LoadError(message: String) derives CanEqual

/** JSON persistence for whole layouts. The encoded form is the document of record: stable,
  * human-readable, and tolerant — panes whose type is unknown at load time round-trip unharmed as
  * [[PaneType.Unresolved]].
  */
object LayoutCodec:

  private val Version = 1

  // -- encoding ----------------------------------------------------------------------------

  def encode(s: LayoutState): ujson.Value =
    val obj = ujson.Obj("version" -> ujson.Num(Version))
    obj("root") = s.root.fold[ujson.Value](ujson.Null)(encodeNode)
    obj("floating") = ujson.Arr.from(s.floating.map(encodeFloating))
    obj("maximized") = s.maximized.fold[ujson.Value](ujson.Null)(id => ujson.Str(id.value))
    obj("minimized") = ujson.Arr.from(s.minimized.map(_.value).toVector.sorted)
    obj("focused") = s.focused.fold[ujson.Value](ujson.Null)(id => ujson.Str(id.value))
    obj

  private def encodeFloating(f: Floating): ujson.Value =
    ujson.Obj(
      "window" -> ujson.Str(f.window.value),
      "bounds" -> encodeRect(f.bounds),
      "home" -> f.home.fold[ujson.Value](ujson.Null): a =>
        ujson.Obj(
          "sibling"     -> ujson.Str(a.sibling.value),
          "edge"        -> ujson.Str(edgeString(a.edge)),
          "size"        -> ujson.Str(sizeString(a.size)),
          "siblingSize" -> ujson.Str(sizeString(a.siblingSize)),
          "minPx"       -> ujson.Num(a.minPx)
        ),
      "root" -> encodeNode(f.root)
    )

  private def encodeRect(r: Rect): ujson.Value =
    ujson.Obj("x" -> r.x, "y" -> r.y, "width" -> r.width, "height" -> r.height)

  private def encodeNode(n: Node): ujson.Value = n match
    case Node.Split(id, axis, cells) =>
      ujson.Obj("split" -> ujson.Obj(
        "id"   -> ujson.Str(id.value),
        "axis" -> ujson.Str(if axis == Axis.Horizontal then "row" else "column"),
        "cells" -> ujson.Arr.from(cells.map: c =>
          val cell = ujson.Obj("size" -> ujson.Str(sizeString(c.size)))
          if c.minPx > 0 then cell("minPx") = ujson.Num(c.minPx)
          cell("node") = encodeNode(c.node)
          cell)
      ))
    case Node.Group(id, tabs, active, header) =>
      ujson.Obj("group" -> ujson.Obj(
        "id"     -> ujson.Str(id.value),
        "active" -> ujson.Str(active.value),
        "header" -> encodeHeader(header),
        "tabs"   -> ujson.Arr.from(tabs.map(encodePane))
      ))

  private def encodePane(p: Pane): ujson.Value =
    val (tpe, state) =
      if p.content.tpe eq PaneType.Unresolved then
        // an unresolved pane's state IS its original {type, state} record: replay it verbatim
        val raw = p.content.encoded
        (raw("type").str, raw("state"))
      else (p.content.tpe.name, p.content.encoded)
    ujson.Obj(
      "id"       -> ujson.Str(p.id.value),
      "title"    -> ujson.Str(p.title),
      "closable" -> ujson.Bool(p.closable),
      "type"     -> ujson.Str(tpe),
      "state"    -> state
    )

  private def encodeHeader(h: Header): ujson.Value = h match
    case Header.Inherit => ujson.Str("inherit")
    case Header.Hidden  => ujson.Str("hidden")
    case Header.Shown(b) =>
      ujson.Obj(
        "close"    -> b.close,
        "maximize" -> b.maximize,
        "popOut"   -> b.popOut,
        "minimize" -> b.minimize
      )

  private def sizeString(s: Size): String =
    // plain decimal notation always: BigDecimal never emits the 1.0E-4 form the parser rejects
    def num(v: Double): String =
      if !v.isFinite then "0"
      else if v == v.floor && v.abs < 1e15 then v.toLong.toString
      else BigDecimal(v).bigDecimal.toPlainString
    s match
      case Size.Fr(w)  => s"${num(w)}fr"
      case Size.Pct(v) => s"${num(v)}%"
      case Size.Px(v)  => s"${num(v)}px"

  private def edgeString(e: Edge): String = e match
    case Edge.Left   => "left"
    case Edge.Right  => "right"
    case Edge.Top    => "top"
    case Edge.Bottom => "bottom"

  private def parseEdge(s: String): Edge = s match
    case "left"   => Edge.Left
    case "right"  => Edge.Right
    case "top"    => Edge.Top
    case "bottom" => Edge.Bottom
    case other    => throw IllegalArgumentException(s"unparseable edge: '$other'")

  // -- decoding ----------------------------------------------------------------------------

  /** A JSON value that is not `null`. ujson's AST has no `CanEqual`, so null is tested by shape. */
  private def present(v: ujson.Value): Option[ujson.Value] = if v.isNull then None else Some(v)

  def decode(v: ujson.Value, types: PaneTypes): Either[LoadError, LayoutState] =
    try
      val version = v("version").num.toInt
      if version > Version then
        throw IllegalArgumentException(s"layout format version $version is newer than $Version")
      val root = present(v("root")).map(decodeNode(_, types))
      val floating = v.obj.get("floating").map(_.arr.toVector).getOrElse(Vector.empty).map: f =>
        Floating(
          WindowId(f("window").str),
          decodeRect(f("bounds")),
          decodeNode(f("root"), types),
          present(f("home")).map: h =>
            Anchor(
              NodeId(h("sibling").str),
              parseEdge(h("edge").str),
              parseSize(h("size").str),
              parseSize(h("siblingSize").str),
              h.obj.get("minPx").map(_.num).getOrElse(0.0)
            )
        )
      val maximized = v.obj.get("maximized").flatMap(present).map(m => NodeId(m.str))
      val minimized = v.obj
        .get("minimized")
        .map(_.arr.iterator.map(m => NodeId(m.str)).toSet)
        .getOrElse(Set.empty)
      val focused = v.obj.get("focused").flatMap(present).map(f => PaneId(f.str))
      val state =
        edit.canonical(LayoutState(root, floating, maximized, minimized, focused))
      requireUniqueIds(state)
      Right(state)
    catch
      case e: Exception =>
        Left(LoadError(Option(e.getMessage).getOrElse(e.toString)))

  private def requireUniqueIds(s: LayoutState): Unit =
    val paneIds = s.panes.map(_.id.value)
    val nodeIds = s.roots.flatMap(nodeIdsOf)
    def firstDup(ids: Vector[String]): Option[String] =
      ids.groupBy(identity).collectFirst { case (id, group) if group.length > 1 => id }
    firstDup(paneIds).foreach(id => throw IllegalArgumentException(s"duplicate pane id: $id"))
    firstDup(nodeIds).foreach(id => throw IllegalArgumentException(s"duplicate node id: $id"))

  private def nodeIdsOf(n: Node): Vector[String] = n match
    case g: Node.Group => Vector(g.id.value)
    case sp @ Node.Split(_, _, cells) =>
      sp.id.value +: cells.flatMap(c => nodeIdsOf(c.node))

  private def decodeRect(v: ujson.Value): Rect =
    Rect(v("x").num, v("y").num, v("width").num, v("height").num)

  private def decodeNode(v: ujson.Value, types: PaneTypes): Node =
    v.obj.get("split") match
      case Some(sp) =>
        Node.Split(
          NodeId(sp("id").str),
          if sp("axis").str == "row" then Axis.Horizontal else Axis.Vertical,
          sp("cells").arr.toVector.map: c =>
            Cell(
              decodeNode(c("node"), types),
              parseSize(c("size").str),
              c.obj.get("minPx").map(_.num).getOrElse(0.0)
            )
        )
      case None =>
        val g = v("group")
        Node.Group(
          NodeId(g("id").str),
          g("tabs").arr.toVector.map(decodePane(_, types)),
          PaneId(g("active").str),
          decodeHeader(g("header"))
        )

  private def decodePane(v: ujson.Value, types: PaneTypes): Pane =
    val name  = v("type").str
    val state = v("state")
    val content = types.get(name) match
      case Some(t) => decodeWith(t, state).getOrElse(unresolved(name, state))
      case None    => unresolved(name, state)
    Pane(PaneId(v("id").str), content, v("title").str, v("closable").bool)

  /** The existential opens here: capture conversion ties the codec's `S` to the content it builds,
    * so even the load path is cast-free.
    */
  private def decodeWith[A](t: PaneType[A], raw: ujson.Value): Option[PaneContent] =
    t.codec.decode(raw).toOption.map(s => PaneContent(t, s))

  private def unresolved(name: String, state: ujson.Value): PaneContent =
    PaneContent(PaneType.Unresolved, ujson.Obj("type" -> name, "state" -> state))

  private def decodeHeader(v: ujson.Value): Header = v match
    case ujson.Str("inherit") => Header.Inherit
    case ujson.Str("hidden")  => Header.Hidden
    case obj =>
      Header.Shown(
        HeaderButtons(
          obj("close").bool,
          obj("maximize").bool,
          obj("popOut").bool,
          obj.obj.get("minimize").fold(true)(_.bool) // absent in pre-minimize documents
        )
      )

  // e-notation accepted defensively on input; output is always plain decimal
  private val SizePattern = """^(-?[\d.]+(?:[eE][+-]?\d+)?)(fr|%|px)$""".r

  private def parseSize(s: String): Size = s match
    case SizePattern(v, "fr") => Size.Fr(v.toDouble)
    case SizePattern(v, "%")  => Size.Pct(v.toDouble)
    case SizePattern(v, "px") => Size.Px(v.toDouble)
    case other                => throw IllegalArgumentException(s"unparseable size: '$other'")

end LayoutCodec
