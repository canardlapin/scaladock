package scaladock

/** The canonical-form laws, as assertions — run after every generated transition. */
object Invariants extends munit.Assertions:

  def assertCanonical(s: LayoutState): Unit =
    s.roots.foreach(checkNode)
    s.maximized.foreach(id => assert(s.findGroup(id).isDefined, s"maximized $id dangles"))
    s.focused.foreach(id => assert(s.findPane(id).isDefined, s"focused $id dangles"))

  private def checkNode(n: Node): Unit = n match
    case Node.Group(id, tabs, active, _) =>
      assert(tabs.nonEmpty, s"group $id is empty")
      assert(tabs.exists(_.id == active), s"group $id active tab dangles")
    case Node.Split(id, axis, cells) =>
      assert(cells.length >= 2, s"split $id has ${cells.length} cell(s)")
      cells.foreach: c =>
        c.node match
          case Node.Split(cid, childAxis, _) =>
            assert(childAxis != axis, s"split $cid nests on the same axis as parent $id")
          case _ => ()
        checkNode(c.node)
