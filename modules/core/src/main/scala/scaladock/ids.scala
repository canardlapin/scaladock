package scaladock

/** Stable identities for layout nodes, panes, and floating windows.
  *
  * Identity is the contract between the immutable model and any renderer: a transition that moves a
  * node must preserve its id, so views can be reconciled rather than rebuilt.
  */
object ids:

  opaque type NodeId   = String
  opaque type PaneId   = String
  opaque type WindowId = String

  object NodeId:
    def fresh(): NodeId                      = java.util.UUID.randomUUID().toString
    def apply(value: String): NodeId         = value
    given CanEqual[NodeId, NodeId]           = CanEqual.derived
    extension (id: NodeId) def value: String = id

  object PaneId:
    def fresh(): PaneId                      = java.util.UUID.randomUUID().toString
    def apply(value: String): PaneId         = value
    given CanEqual[PaneId, PaneId]           = CanEqual.derived
    extension (id: PaneId) def value: String = id

  object WindowId:
    def fresh(): WindowId                      = java.util.UUID.randomUUID().toString
    def apply(value: String): WindowId         = value
    given CanEqual[WindowId, WindowId]         = CanEqual.derived
    extension (id: WindowId) def value: String = id

end ids

export ids.{NodeId, PaneId, WindowId}
