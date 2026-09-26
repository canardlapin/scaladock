package scaladock.fx

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.{KeyCode, KeyEvent}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Keyboard navigation: actions are API, keys are remappable, and content keeps its keys. */
final class KeyboardSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.keys.txt")

  private def dock3(): Dock =
    val factories = PaneFactories.empty.register(TxtPane): s =>
      new PaneView[Txt]:
        val node            = new Label(s.text)
        def snapshot(): Txt = s
    val dock = Dock(
      factories,
      initial = LayoutState.of(
        row(
          group(TxtPane(Txt("a1")).titled("a1"), TxtPane(Txt("a2")).titled("a2")),
          TxtPane(Txt("b")).titled("b"),
          TxtPane(Txt("c")).titled("c")
        )
      )
    )
    val scene = new Scene(dock.view, 1000, 700)
    assert(scene != null)
    dock

  private def key(code: KeyCode, shift: Boolean = false, ctrl: Boolean = false): KeyEvent =
    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false)

  private def titleOfFocus(d: Dock): String =
    d.state.focused.flatMap(d.state.findPane).map(_.title).getOrElse("-")

  /** Deliver a key as the platform would: to the focused content, bubbling up through the dock. */
  private def press(d: Dock, e: KeyEvent): Unit =
    val target = d.state.focused.flatMap(d.nodeOf).getOrElse(d.view)
    Event.fireEvent(target, e)

  private def pressed(d: Dock, e: KeyEvent): KeyEvent =
    press(d, e)
    e

  test("F6 / Shift+F6 cycle focus between groups, wrapping"):
    onFx:
      val d = dock3()
      d.focus(d.state.panes.head.id) // a1
      press(d, key(KeyCode.F6)); assertEquals(titleOfFocus(d), "b")
      press(d, key(KeyCode.F6)); assertEquals(titleOfFocus(d), "c")
      press(d, key(KeyCode.F6)); assertEquals(titleOfFocus(d), "a1")
      press(d, key(KeyCode.F6, shift = true)); assertEquals(titleOfFocus(d), "c")

  test("Ctrl+Tab / Ctrl+Shift+Tab cycle tabs within the focused group"):
    onFx:
      val d = dock3()
      d.focus(d.state.panes.head.id)
      press(d, key(KeyCode.TAB, ctrl = true)); assertEquals(titleOfFocus(d), "a2")
      press(d, key(KeyCode.TAB, ctrl = true)); assertEquals(titleOfFocus(d), "a1")
      press(d, key(KeyCode.TAB, ctrl = true, shift = true)); assertEquals(titleOfFocus(d), "a2")

  test("actions are API: a host keymap can drive them with the dock's own keys switched off"):
    onFx:
      val d = dock3()
      d.focus(d.state.panes.head.id)
      d.setKeyBindings(Map.empty)
      val ev = pressed(d, key(KeyCode.F6))
      assertEquals(titleOfFocus(d), "a1", "unbound: F6 does nothing")
      assert(!ev.isConsumed, "an unbound key is never consumed")
      d.perform(DockAction.FocusNextGroup)
      assertEquals(titleOfFocus(d), "b")
      d.perform(DockAction.ToggleMaximize)
      assertEquals(d.state.maximized, d.state.groupOf(d.state.focused.get).map(_.id))

  test("keys the content handles never reach the dock"):
    onFx:
      val d  = dock3()
      val a1 = d.state.panes.head.id
      d.focus(a1)
      // content that owns F6 (a plot cursor, say) consumes it first
      d.nodeOf(a1).get.addEventHandler(KeyEvent.KEY_PRESSED, (e: KeyEvent) => e.consume())
      press(d, key(KeyCode.F6))
      assertEquals(titleOfFocus(d), "a1")

  test("unrelated keys pass through untouched"):
    onFx:
      val d = dock3()
      d.focus(d.state.panes.head.id)
      val ev = pressed(d, key(KeyCode.LEFT))
      assert(!ev.isConsumed)
  test("focus cleanup never drags the model's focus; a user's move does"):
    // needs real pulses (JavaFX clears a vanished focus owner on the next pulse), so this test
    // steps the FX thread from outside instead of running as one block
    val (dock, stage, fields) = onFx:
      val made = collection.mutable.Map.empty[String, javafx.scene.control.TextField]
      val factories = PaneFactories.empty.register(TxtPane): s =>
        new PaneView[Txt]:
          private val field = new javafx.scene.control.TextField(s.text)
          made(s.text) = field
          def node            = field
          def snapshot(): Txt = s
      val d = Dock(
        factories,
        initial = LayoutState.of(
          row(
            group(TxtPane(Txt("a1")).titled("a1"), TxtPane(Txt("a2")).titled("a2")),
            TxtPane(Txt("b")).titled("b")
          )
        )
      )
      val st = new javafx.stage.Stage
      st.setScene(new Scene(d.view, 900, 600))
      st.show()
      (d, st, made)
    def pulse(): Unit =
      Thread.sleep(120); onFx(())
    try
      onFx(fields("a1").requestFocus()); pulse()
      onFx(fields("b").requestFocus()); pulse() // a user move: a1 -> b
      assertEquals(onFx(titleOfFocus(dock)), "b")

      // switching a1's group to a2 while a1 held keyboard focus: JavaFX's cleanup picks some
      // node — the model must not follow it
      onFx(fields("a1").requestFocus()); pulse()
      assertEquals(onFx(titleOfFocus(dock)), "a1")
      onFx(dock.update(edit.activate(_, dock.state.panes.find(_.title == "b").get.id)))
      onFx(dock.update(edit.activate(_, dock.state.panes.find(_.title == "a2").get.id))); pulse();
      pulse()
      assertEquals(onFx(titleOfFocus(dock)), "a1", "cleanup did not move the model's focus")
    finally onFx(stage.close())

end KeyboardSuite
