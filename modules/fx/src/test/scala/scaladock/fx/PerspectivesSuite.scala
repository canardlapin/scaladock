package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Retained panes and perspectives: a pane that leaves the layout in a retaining switch stays one
  * live view — never rebuilt, never disposed — until explicitly released.
  */
final class PerspectivesSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.perspective.txt")

  /** Counts constructions and disposals: a rebuilt or disposed view is visible in the numbers. */
  private final class Counters:
    var built    = 0
    var disposed = 0

  private def dockWith(state: LayoutState, c: Counters): Dock =
    val factories = PaneFactories.empty.register(TxtPane): s =>
      c.built += 1
      new PaneView[Txt]:
        private val label            = new Label(s.text)
        def node                     = label
        def snapshot(): Txt          = Txt(label.getText)
        override def dispose(): Unit = c.disposed += 1
    val dock  = Dock(factories, initial = state)
    val scene = new Scene(dock.view, 1000, 700)
    assert(scene != null)
    dock

  test("a retaining switch keeps leaving panes alive and gives the same view back"):
    onFx:
      val c       = Counters()
      val shared  = TxtPane(Txt("shared")).titled("shared").toPane
      val onlyA   = TxtPane(Txt("a")).titled("a").toPane
      val layoutA = LayoutState.of(row(Node.solo(shared), Node.solo(onlyA)))
      val dock    = dockWith(layoutA, c)
      val aNode   = dock.nodeOf(onlyA.id).get
      val events  = collection.mutable.Buffer.empty[DockEvent]
      val _       = dock.events.subscribe(e => events += e)
      val layoutB = LayoutState.of(Node.solo(shared))

      dock.switchTo(layoutB)
      assertEquals(dock.detachedPanes, Vector(onlyA.id))
      assertEquals(c.disposed, 0, "a retained view is not disposed")
      assert(events.contains(DockEvent.PaneDetached(onlyA.id)))
      assert(!events.contains(DockEvent.PaneClosed(onlyA.id)), "retained is not closed")

      dock.switchTo(layoutA)
      assert(dock.nodeOf(onlyA.id).get eq aNode, "the same live view came back")
      assertEquals(c.built, 2, "nothing was rebuilt")
      assertEquals(dock.detachedPanes, Vector.empty)
      assert(events.contains(DockEvent.PaneReattached(onlyA.id)))

  test("an ordinary update still disposes panes that leave (retention is opt-in)"):
    onFx:
      val c    = Counters()
      val a    = TxtPane(Txt("a")).titled("a").toPane
      val b    = TxtPane(Txt("b")).titled("b").toPane
      val dock = dockWith(LayoutState.of(row(Node.solo(a), Node.solo(b))), c)
      dock.update(_ => LayoutState.of(Node.solo(a)))
      assertEquals(c.disposed, 1)
      assertEquals(dock.detachedPanes, Vector.empty)

  test("releasing a retained pane goes through close admission and disposes it"):
    onFx:
      val c      = Counters()
      val a      = TxtPane(Txt("a")).titled("a").toPane
      val b      = TxtPane(Txt("b")).titled("b").toPane
      val dock   = dockWith(LayoutState.of(row(Node.solo(a), Node.solo(b))), c)
      val events = collection.mutable.Buffer.empty[DockEvent]
      val _      = dock.events.subscribe(e => events += e)
      dock.switchTo(LayoutState.of(Node.solo(a)))
      val released = dock.requestClose(b.id)
      assertEquals(released.value.flatMap(_.toOption), Some(true))
      assertEquals(c.disposed, 1)
      assertEquals(dock.detachedPanes, Vector.empty)
      assert(events.contains(DockEvent.PaneClosed(b.id)))

  test("load with retainPanes keeps views alive; save captures live state of retained panes"):
    onFx:
      val c     = Counters()
      val a     = TxtPane(Txt("a")).titled("a").toPane
      val b     = TxtPane(Txt("b")).titled("b").toPane
      val full  = LayoutState.of(row(Node.solo(a), Node.solo(b)))
      val dock  = dockWith(full, c)
      val saved = dock.save()
      dock.switchTo(LayoutState.of(Node.solo(a)))
      dock.nodeOf(b.id).get.asInstanceOf[Label].setText("edited while retained")
      assertEquals(dock.load(saved, retainPanes = true), Right(()))
      assertEquals(c.built, 2)
      assertEquals(
        dock.snapshot.findPane(b.id).map(_.content.encoded),
        Some(ujson.Obj("text" -> "edited while retained"))
      )

  test("perspectives: shared panes are one live view; each remembers its last arrangement"):
    onFx:
      val c        = Counters()
      val shared   = TxtPane(Txt("shared")).titled("shared").toPane
      val extra    = TxtPane(Txt("extra")).titled("extra").toPane
      val analysis = LayoutState.of(row(Node.solo(shared), Node.solo(extra)))
      val review   = LayoutState.of(Node.solo(shared))
      val dock     = dockWith(LayoutState.empty, c)
      val p        = Perspectives(dock)
      p.define("Analysis", analysis)
      p.define("Review", review)

      p.show("Analysis")
      val sharedNode = dock.nodeOf(shared.id).get
      val extraGroup = dock.state.groupOf(extra.id).get.id
      dock.maximize(extraGroup) // the user rearranges Analysis

      p.show("Review")
      assert(dock.nodeOf(shared.id).get eq sharedNode, "the shared pane moved, not rebuilt")
      p.show("Analysis")
      assertEquals(dock.state.maximized, Some(extraGroup), "Analysis came back as it was left")
      assert(dock.nodeOf(shared.id).get eq sharedNode)
      assertEquals(c.built, 2)
      assertEquals(c.disposed, 0)

  test("perspectives: a floating window closes on leaving and returns at its bounds"):
    onFx:
      val c    = Counters()
      val a    = TxtPane(Txt("a")).titled("a").toPane
      val b    = TxtPane(Txt("b")).titled("b").toPane
      val dock = dockWith(LayoutState.empty, c)
      val p    = Perspectives(dock)
      p.define("One", LayoutState.of(row(Node.solo(a), Node.solo(b))))
      p.define("Two", LayoutState.of(Node.solo(a)))
      p.show("One")
      dock.popOut(dock.state.groupOf(b.id).get.id)
      val bounds = dock.state.floating.head.bounds
      p.show("Two")
      assertEquals(dock.state.floating, Vector.empty)
      assertEquals(dock.detachedPanes, Vector(b.id))
      p.show("One")
      assertEquals(dock.state.floating.map(_.bounds), Vector(bounds))
      assertEquals(c.built, 2)
      dock.dispose()

  test("perspectives: reset restores the default and releases views no perspective uses"):
    onFx:
      val c    = Counters()
      val a    = TxtPane(Txt("a")).titled("a").toPane
      val dock = dockWith(LayoutState.empty, c)
      val p    = Perspectives(dock)
      p.define("Main", LayoutState.of(Node.solo(a)))
      p.show("Main")
      val added = dock.open(TxtPane(Txt("later")).titled("later"))
      p.define("Other", LayoutState.of(Node.solo(a)))
      p.show("Other") // "later" is retained
      assertEquals(dock.detachedPanes, Vector(added))
      val done = p.reset("Main")
      assertEquals(done.value.flatMap(_.toOption), Some(true))
      assertEquals(dock.detachedPanes, Vector.empty, "no perspective uses it any more")
      assertEquals(p.layoutOf("Main").map(_.panes.map(_.id)), Some(Vector(a.id)))

  test("perspectives: save and load round-trip every arrangement and the active one"):
    onFx:
      val c    = Counters()
      val a    = TxtPane(Txt("a")).titled("a").toPane
      val b    = TxtPane(Txt("b")).titled("b").toPane
      val dock = dockWith(LayoutState.empty, c)
      val p    = Perspectives(dock)
      p.define("One", LayoutState.of(Node.solo(a)))
      p.define("Two", LayoutState.of(row(Node.solo(a), Node.solo(b))))
      p.show("Two")
      val saved = p.save()
      p.show("One")
      assertEquals(p.load(saved), Right(()))
      assertEquals(p.active, Some("Two"))
      assertEquals(dock.state.panes.map(_.id).toSet, Set(a.id, b.id))
  test("releaseUnused also releases non-closable retained panes"):
    onFx:
      val c      = Counters()
      val a      = TxtPane(Txt("a")).titled("a").toPane
      val chrome = TxtPane(Txt("chrome")).titled("chrome").toPane.copy(closable = false)
      val dock   = dockWith(LayoutState.empty, c)
      val p      = Perspectives(dock)
      p.define("One", LayoutState.of(row(Node.solo(a), Node.solo(chrome))))
      p.define("Two", LayoutState.of(Node.solo(a)))
      p.show("One")
      p.show("Two")
      p.define("One", LayoutState.of(Node.solo(a))) // new default no longer has the chrome pane
      val done = p.reset("One")
      assertEquals(done.value.flatMap(_.toOption), Some(true))
      assertEquals(dock.detachedPanes, Vector.empty)

  test("perspectives: a malformed file is rejected, not half-applied"):
    onFx:
      val c    = Counters()
      val dock = dockWith(LayoutState.empty, c)
      val p    = Perspectives(dock)
      p.define("One", LayoutState.of(Node.solo(TxtPane(Txt("a")).titled("a").toPane)))
      assert(p.load(ujson.Str("nonsense")).isLeft)
      assert(p.load(ujson.Obj("active" -> "One")).isLeft)
      assertEquals(p.active, None)

  test("a retained view is rebuilt if the pane comes back as another type"):
    onFx:
      val c     = Counters()
      val Other = PaneType[Txt]("test.perspective.other")
      val a     = TxtPane(Txt("a")).titled("a").toPane
      val b     = TxtPane(Txt("b")).titled("b").toPane
      val dock  = dockWith(LayoutState.of(row(Node.solo(a), Node.solo(b))), c)
      dock.switchTo(LayoutState.of(Node.solo(a)))
      val retype = b.copy(content = PaneContent(Other, Txt("b")))
      dock.switchTo(LayoutState.of(row(Node.solo(a), Node.solo(retype))))
      assertEquals(c.disposed, 1, "the stale view of the old type was disposed")
      assertEquals(dock.detachedPanes, Vector.empty)

end PerspectivesSuite
