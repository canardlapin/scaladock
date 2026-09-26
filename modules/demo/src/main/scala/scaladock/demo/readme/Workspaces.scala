package scaladock.demo.readme

// The snippets of docs/workspaces.md, compiled with the demo so the guide cannot rot.

import javafx.scene.control.MenuItem
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import scaladock.*
import scaladock.fx.*

object WorkspacesGuide:

  // -- perspectives ----------------------------------------------------------------------------
  def perspectives(dock: Dock, analysis: LayoutState, review: LayoutState): Perspectives =
    val perspectives = Perspectives(dock)
    perspectives.define("Analysis", analysis)
    perspectives.define("Review", review)
    perspectives.show("Analysis") // later: perspectives.show("Review") — shared panes move, live
    perspectives

  def persist(perspectives: Perspectives): Unit =
    val saved: ujson.Value = perspectives.save() // every arrangement, plus the active one
    val _                  = perspectives.load(saved)

  // -- retained panes, without the helper ------------------------------------------------------
  def retained(dock: Dock, next: LayoutState): Unit =
    dock.switchTo(next) // panes that leave stay alive, detached
    val _ = dock.events.subscribe:
      case DockEvent.PaneDetached(id)   => println(s"pause work in $id")
      case DockEvent.PaneReattached(id) => println(s"resume work in $id")
      case _                            => ()
    val _ = dock.releaseDetached() // when they are no longer wanted (close admission applies)

  // -- keyboard -------------------------------------------------------------------------------
  def keyboard(dock: Dock): Unit =
    // the host's keymap owns the keys: keep F6, drop Ctrl+Tab, add Cmd+Shift+Enter to maximise
    dock.setKeyBindings(
      Map(
        KeyCodeCombination(KeyCode.F6)                            -> DockAction.FocusNextGroup,
        KeyCodeCombination(KeyCode.F6, KeyCombination.SHIFT_DOWN) -> DockAction.FocusPreviousGroup,
        KeyCodeCombination(
          KeyCode.ENTER,
          KeyCombination.SHORTCUT_DOWN,
          KeyCombination.SHIFT_DOWN
        ) ->
          DockAction.ToggleMaximize
      )
    )
    dock.perform(DockAction.NextTab) // or from a menu or command palette

  // -- tab context menu -----------------------------------------------------------------------
  def tabMenu(dock: Dock): Unit =
    dock.setTabMenu: (ctx, defaults) =>
      val showAsTable = new MenuItem("Show as table")
      showAsTable.setOnAction(_ => println(s"table for ${ctx.pane} in group ${ctx.group}"))
      showAsTable +: defaults
end WorkspacesGuide
