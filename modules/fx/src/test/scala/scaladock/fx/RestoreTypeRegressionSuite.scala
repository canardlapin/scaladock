package scaladock.fx

import javafx.scene.Scene
import javafx.scene.control.Label
import munit.FunSuite
import scaladock.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Type-safe live/retained binding reuse and lossless inactive-perspective snapshots. */
final class RestoreTypeRegressionSuite extends FunSuite:
  final case class Txt(text: String) derives ReadWriter
  private val known       = PaneType[Txt]("eyesstudio.probe.known")
  private val pane        = known(Txt("original")).titled("probe").toPane
  private val original    = LayoutState.of(Node.solo(pane))
  private val unknownName = "future.studio.pane"
  private val payload     = ujson.Obj("keep" -> 42)

  private def unknownLayout: LayoutState =
    val json = LayoutCodec.encode(original)
    def change(v: ujson.Value): Unit = v match
      case obj: ujson.Obj =>
        if obj.obj.get("id").contains(ujson.Str(pane.id.value)) then
          obj("type") = unknownName
          obj("state") = payload
        else obj.obj.values.foreach(change)
      case arr: ujson.Arr => arr.arr.foreach(change)
      case _              => ()
    change(json)
    LayoutCodec.decode(json, PaneTypes.empty).toOption.get

  private def newDock(): Dock =
    val factories = PaneFactories.empty.register(known): s =>
      new PaneView[Txt]:
        private val label   = new Label(s.text)
        def node            = label
        def snapshot(): Txt = Txt(label.getText)
    val dock = Dock(factories, initial = original)
    val _    = new Scene(dock.view, 800, 600)
    dock

  private def assertUnknown(layout: ujson.Value): Unit =
    val text = ujson.write(layout)
    assert(text.contains(unknownName), s"Unknown type lost: $text")
    assert(text.contains("\"keep\":42"), s"Unknown payload lost: $text")

  test("live same-ID type replacement must create placeholder and preserve payload"):
    onFx:
      val dock = newDock()
      try
        assertEquals(dock.load(LayoutCodec.encode(unknownLayout), retainPanes = true), Right(()))
        assert(
          !dock.view.lookupAll(".dock-unresolved").isEmpty,
          "Expected unresolved placeholder; old live view was reused"
        )
        assertUnknown(dock.save())
      finally dock.dispose()

  test("inactive unknown layout must not be overwritten by active known same-ID pane"):
    onFx:
      val dock = newDock()
      try
        val p = Perspectives(dock)
        p.define("Known", original)
        p.define("Future", unknownLayout)
        p.show("Known")
        assertUnknown(p.save()("perspectives")("Future"))
      finally dock.dispose()

  test("control: detaching before restore preserves placeholder and payload"):
    onFx:
      val dock = newDock()
      try
        dock.switchTo(LayoutState.empty)
        assertEquals(dock.load(LayoutCodec.encode(unknownLayout), retainPanes = true), Right(()))
        assert(!dock.view.lookupAll(".dock-unresolved").isEmpty)
        assertUnknown(dock.save())
      finally dock.dispose()

  test("control: per-layout capture preserves inactive unknown payload"):
    onFx:
      val dock = newDock()
      try
        val p = Perspectives(dock)
        p.define("Known", original)
        p.define("Future", unknownLayout)
        p.show("Known")
        assertUnknown(LayoutCodec.encode(p.layoutOf("Future").get))
      finally dock.dispose()

  test("same-ID replacement between registered types disposes the old view once"):
    onFx:
      val other    = PaneType[Txt]("restore.other")
      var disposed = 0
      val factories = PaneFactories.empty.register(known): s =>
        new PaneView[Txt]:
          val node                     = new Label(s.text)
          def snapshot(): Txt          = s
          override def dispose(): Unit = disposed += 1
      val both = factories.register(other): s =>
        new PaneView[Txt]:
          val node            = new Label("other:" + s.text)
          def snapshot(): Txt = s
      val dock = Dock(both, initial = original)
      try
        val old = dock.nodeOf(pane.id).get
        val next = original.copy(root =
          Some(Node.solo(pane.copy(content = PaneContent(other, Txt("next")))))
        )
        dock.switchTo(next)
        assert(dock.nodeOf(pane.id).get ne old)
        assertEquals(disposed, 1)
        assertEquals(dock.snapshot.findPane(pane.id).get.content, PaneContent(other, Txt("next")))
        dock.focus(pane.id)
        assertEquals(disposed, 1)
      finally dock.dispose()

  for detached <- Vector(false, true) do
    test(s"unregistered same-name type never reuses a registered view; detached=$detached"):
      onFx:
        val dock = newDock()
        try
          val other = PaneType[Int](known.name)
          if detached then dock.switchTo(LayoutState.empty)
          val next = LayoutState.of(Node.solo(pane.copy(content = PaneContent(other, 42))))
          dock.switchTo(next)
          assert(!dock.view.lookupAll(".dock-unresolved").isEmpty)
          val saved = LayoutCodec.decode(dock.save(), PaneTypes.empty).toOption.get
          assertEquals(saved.findPane(pane.id).get.content.encoded("state"), ujson.Num(42))
        finally dock.dispose()

    for changeType <- Vector(false, true) do
      test(
        s"unknown replacement preserves its requested raw payload; detached=$detached changeType=$changeType"
      ):
        onFx:
          val dock = newDock()
          try
            dock.switchTo(LayoutState.empty)
            dock.switchTo(unknownLayout)
            if detached then dock.switchTo(LayoutState.empty)
            val raw = ujson.Obj(
              "type"  -> (if changeType then "another.unknown" else unknownName),
              "state" -> ujson.Obj("keep" -> 99)
            )
            val next =
              LayoutState.of(Node.solo(pane.copy(content = PaneContent(PaneType.Unresolved, raw))))
            dock.switchTo(next)
            assertEquals(dock.snapshot.findPane(pane.id).get.content.encoded, raw)
          finally dock.dispose()

  test("inactive unknown payload stays separate from another unknown binding of the same ID"):
    onFx:
      val dock = newDock()
      try
        val raw = ujson.Obj("type" -> "another.unknown", "state" -> ujson.Obj("keep" -> 99))
        val another =
          LayoutState.of(Node.solo(pane.copy(content = PaneContent(PaneType.Unresolved, raw))))
        val p = Perspectives(dock)
        p.define("Future", unknownLayout)
        p.define("Another", another)
        dock.switchTo(LayoutState.empty)
        p.show("Another")
        assertUnknown(p.save()("perspectives")("Future"))
      finally dock.dispose()

  test("same-type live edits remain authoritative and keep the same node"):
    onFx:
      val dock = newDock()
      try
        val node = dock.nodeOf(pane.id).get.asInstanceOf[Label]
        node.setText("edited")
        val p = Perspectives(dock)
        p.define("One", original)
        p.define("Two", original)
        p.show("One")
        p.show("Two")
        val saved = p.save()
        for name <- Vector("One", "Two") do
          val state = LayoutCodec.decode(saved("perspectives")(name), dock.paneTypes).toOption.get
          assertEquals(state.findPane(pane.id).get.content.encoded, ujson.Obj("text" -> "edited"))
        assert(dock.nodeOf(pane.id).get eq node)
      finally dock.dispose()
end RestoreTypeRegressionSuite
