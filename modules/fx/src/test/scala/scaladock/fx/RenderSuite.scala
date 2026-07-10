package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class RenderSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def vsCodeIsh: LayoutState =
    LayoutState.of(
      row(
        TxtPane(Txt("explorer")).titled("Explorer") sized 240.px,
        column(
          group(TxtPane(Txt("ed-1")).titled("ed-1"), TxtPane(Txt("ed-2")).titled("ed-2"))
            sized 1.fr,
          TxtPane(Txt("console")).titled("Console") sized 200.px
        ) sized 1.fr
      )
    )

  /** Materialize a dock at a fixed size, off-screen, with CSS and layout applied. */
  private def materialized(state: LayoutState): Dock =
    val dock  = Dock(factories, initial = state)
    val scene = new Scene(dock.view, 1200, 800)
    dock.view.applyCss()
    dock.view.layout()
    assert(scene != null)
    dock

  test("a static layout renders each group exactly where core geometry says"):
    onFx:
      val dock   = materialized(vsCodeIsh)
      val region = dock.view.asInstanceOf[DockRegion]
      val geom   = sizing.geometry(dock.state.root, Rect(0, 0, 1200, 800), dock.settings)

      val groupViews = region.getChildrenUnmodifiable.toArray.collect { case g: GroupView => g }
      assertEquals(groupViews.length, 3)
      groupViews.foreach: gv =>
        val expected = geom.groups(gv.nodeId).bounds
        assertEqualsDouble(gv.getLayoutX, expected.x, 0.5)
        assertEqualsDouble(gv.getLayoutY, expected.y, 0.5)
        assertEqualsDouble(gv.getWidth, expected.width, 0.5)
        assertEqualsDouble(gv.getHeight, expected.height, 0.5)

      // the explorer column takes its 240px
      val explorerGroup = dock.state.groupOf(paneTitled(dock, "Explorer")).get
      assertEqualsDouble(geom.groups(explorerGroup.id).bounds.width, 240.0, 0.01)

  test("switching tabs reparents the same node instance — never recreates it"):
    onFx:
      val dock  = materialized(vsCodeIsh)
      val ed1   = paneTitled(dock, "ed-1")
      val ed2   = paneTitled(dock, "ed-2")
      val group = dock.state.groupOf(ed1).get

      val ed1NodeBefore = paneNode(dock, ed1)
      assert(ed1NodeBefore.getScene != null, "active tab's node is in the scene")
      val ed2NodeBefore = paneNode(dock, ed2)

      dock.focus(ed2)
      dock.view.layout()
      assertEquals(dock.state.findGroup(group.id).get.active, ed2)
      assert(paneNode(dock, ed2) eq ed2NodeBefore, "ed-2's node must be the same instance")
      assert(paneNode(dock, ed2).getScene != null)

      dock.focus(ed1)
      assert(paneNode(dock, ed1) eq ed1NodeBefore, "ed-1's node must survive a round trip")

  test("closing a pane collapses its group and removes the view"):
    onFx:
      val dock    = materialized(vsCodeIsh)
      val console = paneTitled(dock, "Console")
      val before  = dock.state.groups.length

      dock.close(console)
      dock.view.layout()
      assertEquals(dock.state.groups.length, before - 1)
      assertEquals(dock.state.findPane(console), None)
      val region     = dock.view.asInstanceOf[DockRegion]
      val groupViews = region.getChildrenUnmodifiable.toArray.collect { case g: GroupView => g }
      assertEquals(groupViews.length, before - 1)

  test("maximise fills the viewport and restore puts everything back"):
    onFx:
      val dock  = materialized(vsCodeIsh)
      val group = dock.state.groupOf(paneTitled(dock, "ed-1")).get

      dock.maximize(group.id)
      dock.view.layout()
      val region = dock.view.asInstanceOf[DockRegion]
      val gv = region.getChildrenUnmodifiable.toArray
        .collect { case g: GroupView => g }
        .find(_.nodeId == group.id)
        .get
      assertEqualsDouble(gv.getWidth, 1200.0, 0.5)
      assertEqualsDouble(gv.getHeight, 800.0, 0.5)

      dock.restore()
      dock.view.layout()
      assert(gv.getWidth < 1200.0)

  test("save pulls live snapshots and load round-trips"):
    onFx:
      val dock  = materialized(vsCodeIsh)
      val saved = dock.save()
      val dock2 = materialized(LayoutState.empty)
      assertEquals(dock2.load(saved), Right(()))
      assertEquals(dock2.state, dock.state)

  // -- helpers --------------------------------------------------------------------------------

  private def paneTitled(dock: Dock, title: String): PaneId =
    dock.state.panes.find(_.title == title).map(_.id).getOrElse(fail(s"no pane titled $title"))

  private def paneNode(dock: Dock, id: PaneId): javafx.scene.Node =
    dock.nodeOf(id).getOrElse(fail(s"node for $id not found"))
end RenderSuite
