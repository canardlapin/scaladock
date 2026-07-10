package scaladock

import munit.FunSuite
import TestPanes.*

final class EventsSuite extends FunSuite:

  test("diff derives the full event vocabulary from one transition"):
    val a = doc("a")
    val b = doc("b")
    val s0 = edit.canonical(
      LayoutState.of(Node.Group(NodeId("g"), Vector(a, b), a.id))
    )

    // open
    val c      = doc("c")
    val s1     = edit.drop(s0, c, DropTarget.IntoGroup(NodeId("g"), 2))
    val opened = DockEvent.diff(s0, s1)
    assert(opened.exists {
      case DockEvent.PaneOpened(id, tpe) => id == c.id && tpe == "test.text"
      case _                             => false
    })
    assert(opened.contains(DockEvent.ActiveTabChanged(NodeId("g"), c.id)))
    assert(opened.contains(DockEvent.PaneFocused(c.id, None)))

    // close
    val s2 = edit.close(s1, c.id)
    assert(DockEvent.diff(s1, s2).contains(DockEvent.PaneClosed(c.id)))

    // maximise / restore
    val s3 = edit.maximize(s2, NodeId("g"))
    assertEquals(DockEvent.diff(s2, s3), Vector(DockEvent.GroupMaximized(NodeId("g"))))
    val s4 = edit.unmaximized(s3)
    assertEquals(DockEvent.diff(s3, s4), Vector(DockEvent.GroupRestored(NodeId("g"))))

    // popOut / dockBack
    val s5 = edit.popOut(s4, NodeId("g"), Rect(0, 0, 300, 200))
    val w  = s5.floating.head.window
    assert(DockEvent.diff(s4, s5).contains(DockEvent.WindowOpened(w)))
    val s6 = edit.dockBack(s5, w)
    assert(DockEvent.diff(s5, s6).contains(DockEvent.WindowClosed(w)))

  test("diff reports retitles and cleared focus"):
    val a  = doc("a")
    val s0 = edit.canonical(LayoutState.of(Node.solo(a))).copy(focused = Some(a.id))
    val s1 = edit.retitle(s0, a.id, "renamed")
    assert(DockEvent.diff(s0, s1).contains(DockEvent.PaneRetitled(a.id, "renamed")))
    val s2 = s1.copy(focused = None)
    assertEquals(DockEvent.diff(s1, s2), Vector(DockEvent.FocusCleared(a.id)))

  test("a throwing subscriber does not starve the others"):
    val topic = Events.Topic[Int]()
    var seen  = List.empty[Int]
    topic.subscribe(_ => throw new RuntimeException("boom")): Unit
    topic.subscribe(n => seen = n :: seen): Unit
    val t      = Thread.currentThread
    val before = t.getUncaughtExceptionHandler
    var caught = false
    t.setUncaughtExceptionHandler((_, _) => caught = true)
    try topic.publish(42)
    finally t.setUncaughtExceptionHandler(before)
    assertEquals(seen, List(42))
    assert(caught, "the subscriber's exception was reported, not swallowed")

  test("subscribing the same function twice yields independent subscriptions"):
    val topic          = Events.Topic[Int]()
    var count          = 0
    val f: Int => Unit = _ => count += 1
    val s1             = topic.subscribe(f)
    val s2             = topic.subscribe(f)
    topic.publish(1)
    assertEquals(count, 2)
    s1.cancel()
    topic.publish(1)
    assertEquals(count, 3, "cancelling one subscription leaves the other live")
    s2.cancel()
    topic.publish(1)
    assertEquals(count, 3)

  test("Topic delivers, collect filters, cancel stops"):
    val topic  = Events.Topic[DockEvent]()
    var all    = List.empty[DockEvent]
    var closed = List.empty[PaneId]

    val subAll = topic.subscribe(e => all = e :: all)
    val subClosed = topic
      .collect { case DockEvent.PaneClosed(id) => id }
      .subscribe(id => closed = id :: closed)

    topic.publish(DockEvent.PaneClosed(PaneId("x")))
    topic.publish(DockEvent.GroupRestored(NodeId("g")))
    assertEquals(all.length, 2)
    assertEquals(closed, List(PaneId("x")))

    subAll.cancel()
    subClosed.cancel()
    topic.publish(DockEvent.PaneClosed(PaneId("y")))
    assertEquals(all.length, 2)
    assertEquals(closed, List(PaneId("x")))
end EventsSuite
