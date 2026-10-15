package scaladock.fx

import javafx.beans.value.ChangeListener
import javafx.scene.{Node, Parent, Scene}
import javafx.stage.Window
import scaladock.Subscription

/** Small owned-resource scope. Cancellation drops callbacks as well as unregistering them. */
private[fx] final class PresentationScope extends Subscription:
  private val resources = collection.mutable.Set.empty[Subscription]
  private var closed    = false
  def size: Int         = resources.size

  def own(action: () => Unit): Subscription =
    require(!closed, "Presentation scope is closed")
    val resource = new Subscription:
      private var cleanup = Option(action)
      def cancel(): Unit =
        val run = cleanup
        cleanup = None
        resources.remove(this): Unit
        run.foreach(_())
    resources.add(resource): Unit
    resource

  def cancel(): Unit =
    if !closed then
      closed = true
      resources.toVector.foreach(_.cancel())

private[fx] object PresentationBindings:
  def scene(node: Node)(attach: Scene => Subscription): Subscription =
    var child: Subscription = Option(node.getScene).fold[Subscription](() => ())(attach)
    val listener: ChangeListener[Scene] = (_, _, next) =>
      child.cancel()
      child = Option(next).fold[Subscription](() => ())(attach)
    node.sceneProperty.addListener(listener)
    var active = true
    () =>
      if active then
        active = false
        node.sceneProperty.removeListener(listener)
        child.cancel()

  def popup(popup: javafx.scene.control.PopupControl)(style: Parent => Subscription): Subscription =
    var binding: Option[Subscription] = None
    val showing: javafx.event.EventHandler[javafx.stage.WindowEvent] = _ =>
      binding.foreach(_.cancel())
      binding = Some(style(popup.getScene.getRoot))
    val hidden: javafx.event.EventHandler[javafx.stage.WindowEvent] = _ =>
      binding.foreach(_.cancel())
      binding = None
    popup.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, showing)
    popup.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, hidden)
    () =>
      popup.hide()
      binding.foreach(_.cancel())
      binding = None
      popup.removeEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, showing)
      popup.removeEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, hidden)

  def window(node: Node)(attach: Window => Subscription): Subscription =
    scene(node): sc =>
      var child: Subscription = Option(sc.getWindow).fold[Subscription](() => ())(attach)
      val listener: ChangeListener[Window] = (_, _, next) =>
        child.cancel()
        child = Option(next).fold[Subscription](() => ())(attach)
      sc.windowProperty.addListener(listener)
      () =>
        sc.windowProperty.removeListener(listener)
        child.cancel()
end PresentationBindings

/** Own only additions. Never replace a caller's list or remove pre-existing resources. */
private[fx] final class RootStyles(root: Parent) extends Subscription:
  private var owned       = Vector.empty[String]
  private val markerAdded = !root.getStyleClass.contains("dock")
  private var active      = true
  if markerAdded then root.getStyleClass.add("dock"): Unit

  def update(sheets: Vector[String]): Unit =
    require(active, "Style binding is closed")
    val next = sheets.distinct
    owned.filterNot(next.contains).foreach(s => root.getStylesheets.remove(s): Unit)
    owned = owned.filter(next.contains)
    next.foreach: sheet =>
      if !root.getStylesheets.contains(sheet) then
        root.getStylesheets.add(sheet): Unit
        owned = owned :+ sheet

  def cancel(): Unit =
    if active then
      active = false
      owned.foreach(s => root.getStylesheets.remove(s): Unit)
      owned = Vector.empty
      if markerAdded then root.getStyleClass.remove("dock"): Unit
