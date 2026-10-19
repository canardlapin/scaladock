package scaladock.fx

import javafx.beans.value.ChangeListener
import javafx.scene.{Node, Parent}
import scaladock.Subscription

/** Policy for ScalaDock's own animations. System follows JavaFX's platform preference (available on
  * the JavaFX 24 floor); Full and Reduced are explicit host overrides. Pane content and host-owned
  * Scene preferences remain the host's responsibility.
  */
enum DockMotion:
  case System, Full, Reduced

/** Node visibility includes its ancestors and native window, not just Node.isVisible. */
private[fx] object MotionVisibility:
  def isShowing(node: Node): Boolean =
    Option(node.getScene).flatMap(s => Option(s.getWindow)).exists(_.isShowing) &&
      Iterator.iterate(node)(_.getParent).takeWhile(_ != null).forall(_.isVisible)

  def onHidden(node: Node)(hidden: () => Unit): Subscription =
    var ancestors                                  = Vector.empty[Node]
    def check(): Unit                              = if !isShowing(node) then hidden()
    val visible: ChangeListener[java.lang.Boolean] = (_, _, _) => check()
    lazy val parent: ChangeListener[Parent]        = (_, _, _) => refresh()
    def detach(): Unit = ancestors.foreach: n =>
      n.visibleProperty.removeListener(visible)
      n.parentProperty.removeListener(parent)
    def refresh(): Unit =
      detach()
      ancestors = Iterator.iterate(node)(_.getParent).takeWhile(_ != null).toVector
      ancestors.foreach: n =>
        n.visibleProperty.addListener(visible)
        n.parentProperty.addListener(parent)
      check()
    refresh()
    val window = PresentationBindings.window(node): w =>
      w.showingProperty.addListener(visible)
      check()
      () =>
        w.showingProperty.removeListener(visible)
        hidden()
    () =>
      detach()
      ancestors = Vector.empty
      window.cancel()
