package scaladock

/** Serialization contract for pane state. Core speaks ujson's AST; any codec can sit behind it. */
trait PaneCodec[S]:
  def encode(s: S): ujson.Value
  def decode(v: ujson.Value): Either[String, S]

object PaneCodec:

  /** Free derivation for any state type with a uPickle ReadWriter (e.g. `derives ReadWriter`). */
  given fromReadWriter[S](using rw: upickle.default.ReadWriter[S]): PaneCodec[S] with
    def encode(s: S): ujson.Value = upickle.default.writeJs(s)
    def decode(v: ujson.Value): Either[String, S] =
      try Right(upickle.default.read[S](v))
      catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.toString))

  val json: PaneCodec[ujson.Value] = new PaneCodec[ujson.Value]:
    def encode(s: ujson.Value): ujson.Value                 = s
    def decode(v: ujson.Value): Either[String, ujson.Value] = Right(v)

/** A typed pane kind: the identity of a kind of content (register once, share the value).
  *
  * The `name` is the wire tag used in persisted layouts; the codec carries the state's
  * serialization. `Viewer(state)` is the entry point for putting a pane in a layout.
  */
final class PaneType[S](val name: String)(using val codec: PaneCodec[S]):
  def apply(state: S): PaneDef  = PaneDef(PaneContent(this, state))
  override def toString: String = s"PaneType($name)"

object PaneType:
  /** Carrier for panes whose type was not registered at load time: preserves the raw JSON (original
    * wire tag and state) so that saving again never destroys information.
    */
  val Unresolved: PaneType[ujson.Value] =
    PaneType[ujson.Value]("scaladock.unresolved")(using PaneCodec.json)

/** A typed pane state paired with its type — the member type ties them so that snapshotting and
  * re-encoding a live pane needs no casts anywhere in user code.
  */
sealed abstract class PaneContent:
  type S
  val tpe: PaneType[S]
  val state: S

  def encoded: ujson.Value = tpe.codec.encode(state)

  override def equals(other: Any): Boolean = other match
    case that: PaneContent =>
      (that.tpe eq tpe) && java.util.Objects.equals(
        state.asInstanceOf[AnyRef],
        that.state.asInstanceOf[AnyRef]
      )
    case _ => false
  override def hashCode: Int    = tpe.name.hashCode * 31 + state.##
  override def toString: String = s"PaneContent(${tpe.name})"

object PaneContent:
  given CanEqual[PaneContent, PaneContent] = CanEqual.derived

  def apply[A](t: PaneType[A], s: A): PaneContent { type S = A } =
    new PaneContent:
      type S = A
      val tpe: PaneType[A] = t
      val state: A         = s

/** What a user hands to the layout to create a pane: content plus chrome attributes. A `Pane` (with
  * its stable id) is minted from this at insertion time.
  */
final case class PaneDef(content: PaneContent, title: String = "", closable: Boolean = true)
    derives CanEqual:
  def titled(t: String): PaneDef = copy(title = t)
  def fixed: PaneDef             = copy(closable = false)
  def toPane: Pane               = Pane(PaneId.fresh(), content, title, closable)
