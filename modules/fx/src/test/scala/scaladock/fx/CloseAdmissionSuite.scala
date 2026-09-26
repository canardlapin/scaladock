package scaladock.fx

import javafx.scene.control.Label
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import FxFixture.onFx

final class CloseAdmissionSuite extends FunSuite:
  final case class Text(value: String) derives ReadWriter
  private val kind = PaneType[Text]("close.text")

  test("default admission retains immediate close semantics") {
    onFx {
      var disposed = 0
      val factories = PaneFactories.empty.register(kind)(state =>
        new PaneView[Text]:
          val node                     = new Label(state.value)
          def snapshot(): Text         = state
          override def dispose(): Unit = disposed += 1
      )
      val dock = Dock(factories, initial = LayoutState.of(group(kind(Text("one")))))
      val id   = dock.state.panes.head.id
      dock.close(id)
      assert(dock.state.findPane(id).isEmpty)
      assertEquals(disposed, 1)
      dock.dispose()
    }
  }

  test(
    "delayed duplicate close shares admission; veto and failure preserve the pane; retry disposes once"
  ) {
    var gate      = Promise[Boolean]()
    var prepared  = 0
    var cancelled = 0
    var disposed  = 0
    val (dock, id, pending) = onFx {
      val factories = PaneFactories.empty.register(kind)(state =>
        new PaneView[Text]:
          val node             = new Label(state.value)
          def snapshot(): Text = state
          override def prepareClose(): Future[Boolean] =
            prepared += 1; gate.future
          override def closeCancelled(): Unit = cancelled += 1
          override def dispose(): Unit        = disposed += 1
      )
      val dock    = Dock(factories, initial = LayoutState.of(group(kind(Text("one")))))
      val id      = dock.state.panes.head.id
      val pending = dock.requestClose(id)
      assert(dock.requestClose(id) eq pending)
      assert(dock.state.findPane(id).nonEmpty)
      (dock, id, pending)
    }
    gate.success(false)
    assert(!Await.result(pending, 5.seconds))
    val failure = onFx {
      assertEquals(prepared, 1)
      assertEquals(cancelled, 1)
      assertEquals(disposed, 0)
      gate = Promise[Boolean]()
      dock.requestClose(id)
    }
    gate.failure(IllegalStateException("save failed"))
    assert(!Await.result(failure, 5.seconds))
    val success = onFx {
      assertEquals(cancelled, 2)
      gate = Promise[Boolean]()
      dock.requestClose(id)
    }
    gate.success(true)
    assert(Await.result(success, 5.seconds))
    onFx {
      assertEquals(disposed, 1)
      assert(dock.state.findPane(id).isEmpty)
      dock.dispose()
    }
  }

  test("whole-dock admission is atomic and rejects a new pane arriving during preparation") {
    var gate      = Promise[Boolean]()
    var cancelled = 0
    var disposed  = 0
    val (dock, vetoed) = onFx {
      val factories = PaneFactories.empty.register(kind)(state =>
        new PaneView[Text]:
          val node             = new Label(state.value)
          def snapshot(): Text = state
          override def prepareClose(): Future[Boolean] =
            if state.value == "slow" then gate.future else Future.successful(true)
          override def closeCancelled(): Unit = cancelled += 1
          override def dispose(): Unit        = disposed += 1
      )
      val dock =
        Dock(factories, initial = LayoutState.of(group(kind(Text("fast")), kind(Text("slow")))))
      (dock, dock.requestCloseAll())
    }
    gate.success(false)
    assert(!Await.result(vetoed, 5.seconds))
    val stale = onFx {
      assertEquals(disposed, 0)
      assertEquals(cancelled, 2)
      assertEquals(dock.state.panes.size, 2)
      gate = Promise[Boolean]()
      val pending = dock.requestCloseAll()
      val _       = dock.open(kind(Text("new")))
      pending
    }
    gate.success(true)
    assert(!Await.result(stale, 5.seconds))
    onFx {
      assertEquals(dock.state.panes.size, 3)
      assertEquals(disposed, 0)
      assertEquals(cancelled, 4)
      gate = Promise.successful(true)
      assertEquals(dock.requestCloseAll().value, Some(scala.util.Success(true)))
      assertEquals(disposed, 3)
      dock.dispose()
    }
  }

  test("tab, group and pane-context close paths honor admission") {
    onFx {
      var prepared  = 0
      var cancelled = 0
      val contexts  = scala.collection.mutable.Map.empty[String, () => Unit]
      val factories = PaneFactories.empty.register(kind) { state =>
        val context = summon[PaneContext[Text]]
        contexts(state.value) = () => context.close()
        new PaneView[Text]:
          val node             = new Label(state.value)
          def snapshot(): Text = state
          override def prepareClose(): Future[Boolean] =
            prepared += 1; Future.successful(false)
          override def closeCancelled(): Unit = cancelled += 1
      }
      val dock =
        Dock(factories, initial = LayoutState.of(group(kind(Text("one")), kind(Text("two")))))
      val view = dock.groupViewFor(dock.state.root.get.id).get
      view.onTabClosed(dock.state.panes.head.id)
      assertEquals(prepared, 1)
      contexts("one")()
      assertEquals(prepared, 2)
      view.onGroupClosed()
      assertEquals(prepared, 4)
      assertEquals(cancelled, 4)
      assertEquals(dock.state.panes.size, 2)
      dock.dispose()
    }
  }

  test("a non-closable pane is refused without preparation; a forced edit still removes it") {
    onFx {
      var prepared = 0
      val factories = PaneFactories.empty.register(kind)(state =>
        new PaneView[Text]:
          val node             = new Label(state.value)
          def snapshot(): Text = state
          override def prepareClose(): Future[Boolean] =
            prepared += 1
            Future.successful(true)
      )
      val dock = Dock(factories, initial = LayoutState.of(group(kind(Text("chrome")).fixed)))
      val id   = dock.state.panes.head.id
      assertEquals(dock.requestClose(id).value, Some(scala.util.Success(false)))
      dock.close(id)
      assertEquals(prepared, 0)
      assert(dock.state.findPane(id).nonEmpty)
      dock.update(edit.close(_, id))
      assert(dock.state.findPane(id).isEmpty)
      dock.dispose()
    }
  }
end CloseAdmissionSuite
