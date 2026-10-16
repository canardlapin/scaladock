package scaladock.fx

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyEvent}
import munit.FunSuite
import scaladock.*
import scaladock.dsl.*
import upickle.default.ReadWriter
import FxFixture.onFx

/** Keyboard navigation: actions are API, keys are remappable, and content keeps its keys. */
final class KeyboardSuite extends FunSuite:

  final case class Txt(text: String) derives ReadWriter
  val TxtPane: PaneType[Txt] = PaneType[Txt]("test.keys.txt")

  private def dockWith(initial: LayoutState): Dock =
    val factories = PaneFactories.empty.register(TxtPane): s =>
      new PaneView[Txt]:
        val node            = new Label(s.text)
        def snapshot(): Txt = s
    val dock  = Dock(factories, initial = initial)
    val scene = new Scene(dock.view, 1000, 700)
    assert(scene != null)
    dock.view.applyCss()
    dock.view.layout()
    dock

  private def dock3(): Dock =
    dockWith(
      LayoutState.of(
        row(
          group(TxtPane(Txt("a1")).titled("a1"), TxtPane(Txt("a2")).titled("a2")),
          TxtPane(Txt("b")).titled("b"),
          TxtPane(Txt("c")).titled("c")
        )
      )
    )

  private def key(code: KeyCode, shift: Boolean = false, ctrl: Boolean = false): KeyEvent =
    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false)

  private def titleOfFocus(d: Dock): String =
    d.state.focused.flatMap(d.state.findPane).map(_.title).getOrElse("-")

  /** Deliver a key as the platform would: to the focused content, bubbling up through the dock. */
  private def press(d: Dock, e: KeyEvent): Unit =
    val target = Option(d.view.getScene.getFocusOwner)
      .orElse(d.state.focused.flatMap(d.nodeOf)).getOrElse(d.view)
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

  for
    horizontal         <- Seq(false, true)
    restoreKey         <- Seq(KeyCode.ENTER, KeyCode.SPACE)
    initiallyMinimized <- Seq(false, true)
  do
    test(
      s"F6 reaches a strip and $restoreKey restores its size (row=$horizontal, saved=$initiallyMinimized)"
    ):
      onFx:
        val cells = Seq(
          TxtPane(Txt("main")).titled("main") sized 1.fr,
          TxtPane(Txt("panel")).titled("panel") sized 240.px
        )
        val initial = LayoutState.of(if horizontal then row(cells*) else column(cells*))
        val panel   = initial.panes.find(_.title == "panel").get.id
        val group   = initial.groupOf(panel).get.id
        val d =
          dockWith(if initiallyMinimized then initial.copy(minimized = Set(group)) else initial)
        try
          val gv   = d.groupViewFor(group).get
          val node = d.nodeOf(panel).get
          if !initiallyMinimized then d.minimize(group)
          d.view.layout()
          assertEqualsDouble(
            if horizontal then gv.getWidth else gv.getHeight,
            sizing.stripPx(d.settings),
            1.0
          )
          d.focus(d.state.panes.find(_.title == "main").get.id)
          d.focusContent(d.state.focused.get)

          press(d, key(KeyCode.F6))
          assertEquals(titleOfFocus(d), "panel")
          assert(d.state.minimized(group), "traversal must not expand a strip")
          val strip = d.view.getScene.getFocusOwner
          assert(strip != null && strip.isVisible && strip.isFocusTraversable)
          assert(strip.getStyleClass.contains(if horizontal then "dock-strip" else "dock-tab"))
          assert(!node.getParent.isVisible, "pane content stays hidden while its strip is focused")
          press(d, key(KeyCode.F6))
          assertEquals(titleOfFocus(d), "main")
          press(d, key(KeyCode.F6, shift = true))
          assertEquals(titleOfFocus(d), "panel")

          press(d, key(restoreKey))
          d.view.layout()
          assert(!d.state.minimized(group))
          assertEqualsDouble(if horizontal then gv.getWidth else gv.getHeight, 240.0, 1.0)
          assert(d.nodeOf(panel).get eq node, "restoration retains the pane node")
          assert(d.view.getScene.getFocusOwner eq node, "restoration focuses the live content")
        finally d.dispose()
  end for

  test("minimise and restore actions focus the strip or content and support a host keymap"):
    onFx:
      val d = dock3()
      try
        val pane  = d.state.panes.head.id
        val group = d.state.groupOf(pane).get.id
        d.focus(pane)
        d.perform(DockAction.ToggleMinimize)
        assert(d.state.minimized(group))
        assert(d.view.getScene.getFocusOwner.getStyleClass.contains("dock-strip"))
        assert(!DockAction.defaultKeys.values.exists(a =>
          a == DockAction.ToggleMinimize || a == DockAction.RestoreMinimized
        ))

        d.perform(DockAction.RestoreMinimized)
        assert(!d.state.minimized(group))
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(pane).get)
        val restored = d.state
        d.perform(DockAction.RestoreMinimized)
        assertEquals(d.state, restored, "restore is idempotent")

        d.setKeyBindings(Map(new KeyCodeCombination(KeyCode.F9) -> DockAction.ToggleMinimize))
        press(d, key(KeyCode.F9))
        assert(d.state.minimized(group))
        press(d, key(KeyCode.F9))
        assertEquals(d.state, restored)
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(pane).get)
      finally d.dispose()

  test("Ctrl+Tab selects minimised tabs without expanding; Enter restores the selected pane"):
    onFx:
      val d = dock3()
      try
        val group = d.state.groups.head.id
        d.focus(d.state.panes.head.id)
        d.perform(DockAction.ToggleMinimize)
        press(d, key(KeyCode.TAB, ctrl = true))
        assertEquals(titleOfFocus(d), "a2")
        assert(d.state.minimized(group))
        press(d, key(KeyCode.TAB, ctrl = true, shift = true))
        assertEquals(titleOfFocus(d), "a1")
        assert(d.state.minimized(group))
        press(d, key(KeyCode.TAB, ctrl = true))
        press(d, key(KeyCode.ENTER, ctrl = true))
        assert(d.state.minimized(group), "modified Enter is available for host keymaps")
        press(d, key(KeyCode.ENTER))
        assert(!d.state.minimized(group))
        assertEquals(titleOfFocus(d), "a2")
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(d.state.focused.get).get)
      finally d.dispose()

  test("maximise still hides sibling strips from group traversal"):
    onFx:
      val d = dock3()
      try
        val first = d.state.panes.head.id
        d.minimize(d.state.groups.last.id)
        d.focus(first)
        d.perform(DockAction.ToggleMaximize)
        press(d, key(KeyCode.F6))
        assertEquals(d.state.focused, Some(first))
        press(d, key(KeyCode.F6, shift = true))
        assertEquals(d.state.focused, Some(first))
      finally d.dispose()

  test("a minimised group with a hidden header still exposes a live keyboard restore target"):
    onFx:
      val hidden = group(TxtPane(Txt("panel")).titled("panel"))
        .asInstanceOf[Node.Group].copy(header = Header.Hidden)
      val initial = LayoutState.of(column(TxtPane(Txt("main")), hidden sized 240.px))
      val d       = dockWith(initial.copy(minimized = Set(hidden.id)))
      try
        d.focus(d.state.panes.head.id)
        d.focusContent(d.state.focused.get)
        press(d, key(KeyCode.F6))
        val gv    = d.groupViewFor(hidden.id).get
        val strip = d.view.getScene.getFocusOwner
        assert(gv.usesHeader && strip.getParent.getParent.getParent.isVisible)
        press(d, key(KeyCode.ENTER))
        assert(!d.state.minimized(hidden.id))
        assert(!gv.usesHeader, "the restored group retains its hidden-header policy")
      finally d.dispose()

  test("strip focus survives native pulses and restoration returns focus to content"):
    val (d, stage, pane, group) = onFx:
      val dock = dock3()
      val st   = new javafx.stage.Stage
      st.setScene(dock.view.getScene)
      st.show()
      val pane = dock.state.panes.head.id
      dock.focus(pane)
      dock.focusContent(pane)
      (dock, st, pane, dock.state.groupOf(pane).get.id)
    def pulse(): Unit =
      Thread.sleep(120); onFx(())
    try
      pulse()
      onFx(d.perform(DockAction.ToggleMinimize))
      pulse(); pulse()
      onFx:
        assert(d.state.minimized(group))
        assert(d.view.getScene.getFocusOwner.getStyleClass.contains("dock-strip"))
        press(d, key(KeyCode.F6))
        assertEquals(titleOfFocus(d), "b")
        press(d, key(KeyCode.F6, shift = true))
        assertEquals(titleOfFocus(d), "a1")
        press(d, key(KeyCode.SPACE))
      pulse(); pulse()
      onFx:
        assert(!d.state.minimized(group))
        assert(d.view.getScene.getFocusOwner eq d.nodeOf(pane).get)
    finally
      onFx:
        d.dispose()
        stage.close()

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
