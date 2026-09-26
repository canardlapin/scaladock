package scaladock.fx

import scala.concurrent.Future
import scaladock.*

/** Named layouts over one [[Dock]] — "Analysis", "Review" — each remembering the user's last
  * arrangement. Switching snapshots the current perspective (live pane state included), then shows
  * the target with [[Dock.switchTo]], so panes that leave stay alive: a pane present in several
  * perspectives is ONE live view that moves between them, and a perspective's floating windows
  * close on leaving and reopen, at their saved bounds, on return.
  *
  * {{{
  * val perspectives = Perspectives(dock)
  * perspectives.define("Analysis", analysisLayout)
  * perspectives.define("Review", reviewLayout)
  * perspectives.show("Analysis")
  * }}}
  */
final class Perspectives(dock: Dock):
  private var order: Vector[String]              = Vector.empty
  private var defaults: Map[String, LayoutState] = Map.empty
  private var latest: Map[String, LayoutState]   = Map.empty
  private var shown: Option[String]              = None

  /** Add a perspective (or redefine its default). Its current arrangement starts as the default. */
  def define(name: String, default: LayoutState): Unit =
    if !order.contains(name) then order = order :+ name
    defaults = defaults.updated(name, default)
    if !latest.contains(name) then latest = latest.updated(name, default)

  def names: Vector[String]  = order
  def active: Option[String] = shown

  /** The perspective's current arrangement (live for the active one). */
  def layoutOf(name: String): Option[LayoutState] =
    if shown.contains(name) then Some(dock.snapshot) else latest.get(name)

  /** Show a perspective, remembering how the current one was left. */
  def show(name: String): Unit =
    require(latest.contains(name), s"no perspective named '$name'")
    if !shown.contains(name) then
      capture()
      shown = Some(name)
      dock.switchTo(latest(name))

  /** Return a perspective to its default arrangement. Retained views that no perspective uses any
    * more are then released (through close admission, so they may still save or veto).
    */
  def reset(name: String): Future[Boolean] =
    require(defaults.contains(name), s"no perspective named '$name'")
    latest = latest.updated(name, defaults(name))
    if shown.contains(name) then dock.switchTo(defaults(name))
    releaseUnused()

  /** Release every retained view that appears in no perspective. */
  def releaseUnused(): Future[Boolean] =
    if dock.isUpdating then
      // called from a dock event handler: let the pending switch land before judging what is idle
      val done = scala.concurrent.Promise[Boolean]()
      javafx.application.Platform.runLater(() => done.completeWith(releaseUnused()))
      done.future
    else
      val used = (latest.values ++ shown.map(_ => dock.state)).flatMap(_.panes.map(_.id)).toSet
      dock.releaseDetached(dock.detachedPanes.filterNot(used).toSet)

  /** Every perspective's arrangement (with live pane state) and which one is active. */
  def save(): ujson.Value =
    capture()
    ujson.Obj(
      "active" -> shown.fold(ujson.Null: ujson.Value)(ujson.Str(_)),
      "perspectives" -> ujson.Obj.from(order.map: name =>
        name -> LayoutCodec.encode(dock.withLiveStateOf(latest(name))))
    )

  /** Restore arrangements saved by [[save]] for the perspectives defined here (unknown names are
    * ignored, so a saved file from an older app version still loads), then show the saved active
    * one. Nothing changes if any arrangement fails to decode.
    */
  def load(v: ujson.Value): Either[LoadError, Unit] =
    v.objOpt.flatMap(_.get("perspectives")).flatMap(_.objOpt) match
      case None => Left(LoadError("not a saved set of perspectives (no \"perspectives\" object)"))
      case Some(saved) => loadFrom(v, saved)

  private def loadFrom(
      v: ujson.Value,
      saved: collection.Map[String, ujson.Value]
  ): Either[LoadError, Unit] =
    val types = dock.paneTypes
    val decoded = saved.toVector.collect:
      case (name, json) if defaults.contains(name) => LayoutCodec.decode(json, types).map(name -> _)
    decoded.collectFirst { case Left(err) => err } match
      case Some(err) => Left(err)
      case None =>
        decoded.collect { case Right(entry) => entry }.foreach((name, layout) =>
          latest = latest.updated(name, layout)
        )
        val target = v.objOpt.flatMap(_.get("active")).flatMap(_.strOpt).filter(latest.contains)
        // force a fresh switch even when the saved active perspective is already showing
        shown = None
        target.orElse(order.headOption).foreach(show)
        Right(())

  private def capture(): Unit =
    shown.foreach(name => latest = latest.updated(name, dock.snapshot))
end Perspectives
