package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.stage.{Stage, Window}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

final class PopoutSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.popout.txt")

  private def factories = PaneFactories.empty.register(TxtPane): s =>
    new PaneView[Txt]:
      val node            = new Label(s.text)
      def snapshot(): Txt = s

  private def staged(state: LayoutState): (Dock, Stage) =
    val dock  = Dock(factories, initial = state)
    val stage = new Stage
    stage.setScene(new Scene(dock.view, 1200, 800))
    stage.show()
    dock.view.applyCss()
    dock.view.layout()
    (dock, stage)

  private def openWindows: Int =
    Window.getWindows.size

  test("popOut opens a Stage, moves the same pane node into it, and dockBack closes it"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          row(TxtPane(Txt("main")).titled("main"), TxtPane(Txt("float")).titled("float"))
        )
      )
      try
        val floatPane  = dock.state.panes.find(_.title == "float").get
        val floatGroup = dock.state.groupOf(floatPane.id).get
        val nodeBefore = dock.nodeOf(floatPane.id).get
        val before     = openWindows

        dock.popOut(floatGroup.id)
        assertEquals(dock.state.floating.length, 1)
        assertEquals(openWindows, before + 1, "a floating Stage opened")
        val floatNode = dock.nodeOf(floatPane.id).get
        assert(floatNode eq nodeBefore, "the pane node moved windows without being rebuilt")
        assert(floatNode.getScene != null, "the node lives in the floating window's scene")
        assert(floatNode.getScene.getWindow != stage, "…and that scene is not the main window")

        dock.dockBack(dock.state.floating.head.window)
        assertEquals(dock.state.floating.length, 0)
        assertEquals(openWindows, before, "the floating Stage closed")
        assert(dock.nodeOf(floatPane.id).get eq nodeBefore, "identity survives the round trip")
        assertEquals(dock.state.panes.map(_.title).toSet, Set("main", "float"))
      finally stage.close()

  test("multi-window layouts save and load, restoring the floating Stage"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          row(TxtPane(Txt("main")).titled("main"), TxtPane(Txt("float")).titled("float"))
        )
      )
      try
        val floatGroup = dock.state.groupOf(dock.state.panes.find(_.title == "float").get.id).get
        dock.popOut(floatGroup.id)
        val saved = dock.save()

        val (dock2, stage2) = staged(LayoutState.empty)
        try
          val before = openWindows
          assertEquals(dock2.load(saved), Right(()))
          assertEquals(dock2.state.floating.length, 1)
          assertEquals(openWindows, before + 1, "loading re-opened the floating Stage")
          assertEquals(dock2.state, dock.state)
        finally
          stage2.close()
          dock2.dockAllBack()
      finally
        dock.dockAllBack()
        stage.close()

  test("closing a floating window docks its content back at the home anchor"):
    onFx:
      val (dock, stage) = staged(
        LayoutState.of(
          row(TxtPane(Txt("a")).titled("a"), TxtPane(Txt("b")).titled("b"))
        )
      )
      try
        val bGroup = dock.state.groupOf(dock.state.panes.find(_.title == "b").get.id).get
        dock.popOut(bGroup.id)
        assertEquals(dock.state.floating.length, 1)

        dock.dockBack(dock.state.floating.head.window)
        dock.state.root match
          case Some(Node.Split(_, Axis.Horizontal, cells)) =>
            assertEquals(cells.map(_.node.panes.head.title), Vector("a", "b"))
          case other => fail(s"unexpected root after dock back: $other")
      finally stage.close()
end PopoutSuite
