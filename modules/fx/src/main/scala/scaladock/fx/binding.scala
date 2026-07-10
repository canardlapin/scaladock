package scaladock.fx

import scaladock.*

/** What a pane author implements: the JavaFX node plus a typed snapshot of current state.
  *
  * The node is created once and reparented for the pane's whole life — across tab switches, splits,
  * drags, and pop-outs it is never rebuilt.
  */
trait PaneView[S]:
  def node: javafx.scene.Node

  /** The pane's current state, pulled at save time (golden-layout's stateRequestEvent, typed). */
  def snapshot(): S

  /** Release resources when the pane leaves the layout for good. */
  def dispose(): Unit = ()

/** What the layout hands a pane author at construction: identity, chrome control, lifecycle. */
trait PaneContext[S]:
  def paneId: PaneId
  def setTitle(title: String): Unit
  def focus(): Unit
  def close(): Unit
  def signals: Events[PaneSignal]

/** The registry pairing each [[PaneType]] with its view factory. The same `S` flows from codec
  * through factory to view, so state is typed end-to-end.
  */
final class PaneFactories private (
    private[fx] val entries: Map[String, PaneFactories.Entry[?]]
):
  def register[S](tpe: PaneType[S])(make: PaneContext[S] ?=> S => PaneView[S]): PaneFactories =
    PaneFactories(entries.updated(
      tpe.name,
      PaneFactories.Entry(tpe, ctx => s => make(using ctx)(s))
    ))

  def paneTypes: PaneTypes =
    PaneTypes(entries.values.map(_.tpe).toSeq*)

  private[fx] def bind(content: PaneContent, ctx: UntypedPaneContext): Option[BoundPane[?]] =
    entries.get(content.tpe.name).map(e => bindWith(e, content, ctx))

  /** The single point where the existential opens on the render path: the entry was found by the
    * content's own type name, so the codec that produced `content.state` is the entry's.
    */
  private def bindWith[S](
      e: PaneFactories.Entry[S],
      content: PaneContent,
      ctx: UntypedPaneContext
  ): BoundPane[S] =
    val state                    = content.state.asInstanceOf[S]
    val typedCtx: PaneContext[S] = ctx.typed[S]
    BoundPane(e.tpe, e.make(typedCtx)(state), typedCtx)

object PaneFactories:
  val empty: PaneFactories = new PaneFactories(Map.empty)

  private[fx] final case class Entry[S](
      tpe: PaneType[S],
      make: PaneContext[S] => S => PaneView[S]
  )

/** A live pane: its type, view, and context, with `S` tied across all three so snapshotting back
  * into a [[PaneContent]] is cast-free.
  */
private[fx] final class BoundPane[S](
    val tpe: PaneType[S],
    val view: PaneView[S],
    val context: PaneContext[S]
):
  def node: javafx.scene.Node      = view.node
  def snapshotContent: PaneContent = PaneContent(tpe, view.snapshot())
  def dispose(): Unit              = view.dispose()

/** Context implementation shared across all state types (the operations don't touch `S`). */
private[fx] final class UntypedPaneContext(
    val id: PaneId,
    dock: Dock
):
  val topic: Events.Topic[PaneSignal] = Events.Topic()

  def typed[S]: PaneContext[S] = new PaneContext[S]:
    def paneId: PaneId                = id
    def setTitle(title: String): Unit = dock.update(edit.retitle(_, id, title))
    def focus(): Unit                 = dock.update(edit.focus(_, id))
    def close(): Unit                 = dock.update(edit.close(_, id))
    def signals: Events[PaneSignal]   = topic
