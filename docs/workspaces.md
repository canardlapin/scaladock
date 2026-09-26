# Workspaces: perspectives, retained panes, keyboard, and tab menus

The snippets below are compiled with the demo
([`readme/Workspaces.scala`](../modules/demo/src/main/scala/scaladock/demo/readme/Workspaces.scala)).

## Perspectives

A perspective is a named layout over one dock — "Analysis", "Review" — that remembers how the
user left it. Switching away snapshots the current arrangement (live pane state included);
switching back restores it.

```scala
val perspectives = Perspectives(dock)
perspectives.define("Analysis", analysis)
perspectives.define("Review", review)
perspectives.show("Analysis")
```

- **Shared panes stay one live view.** If the same `PaneId` appears in two perspectives, the
  pane's JavaFX node moves between them — scroll, zoom, and selection intact. A genuinely separate
  view needs a separate `PaneId`.
- **Floating windows** of a perspective close when you leave it and reopen, at their saved
  bounds, when you return.
- **`reset(name)`** returns a perspective to its default; views no perspective uses any more are
  then released through close admission (so they may still save or veto). `releaseUnused()` does
  just the release.
- **Persistence**: `perspectives.save()` encodes every arrangement plus the active one;
  `perspectives.load(json)` restores them (names not defined in the app are ignored).

```scala
val saved: ujson.Value = perspectives.save()
perspectives.load(saved)
```

## Retained panes, without the helper

`Perspectives` is built on one primitive, `Dock#switchTo(layout)`: replace the layout, but keep
the views of panes that leave alive and detached, ready to be reused by `PaneId`.

```scala
dock.switchTo(next)
dock.events.subscribe:
  case DockEvent.PaneDetached(id)   => println(s"pause work in $id")
  case DockEvent.PaneReattached(id) => println(s"resume work in $id")
  case _                            => ()
dock.releaseDetached()
```

A detached pane publishes `PaneDetached` (not `PaneClosed`) — a good moment to pause expensive
work such as canvas redraws — and `PaneReattached` when it returns. `load(json, retainPanes =
true)` does the same for a saved layout. Retained views stay alive until released:
`requestClose(id)` or `releaseDetached()` (both go through close admission), or `dispose()`.
Ordinary `update` and `load` are unchanged: panes that leave are disposed.

When a retained pane returns, **its live view wins**: the state recorded for that `PaneId` in the
incoming layout is not re-applied (the view already holds the newer state). If the incoming
layout gives the `PaneId` a different pane type, the retained view is disposed and a new one
built. Releasing a retained pane goes through close admission even if it is not closable:
dropping a view the layout no longer shows is not a user close.

## Keyboard

| Key (default) | Action | API |
|---|---|---|
| F6 / Shift+F6 | next / previous group, focus into its content | `focusNextGroup()`, `focusPreviousGroup()` |
| Ctrl+Tab / Ctrl+Shift+Tab | next / previous tab in the focused group | `nextTab()`, `previousTab()` |
| — | maximise or restore the focused group | `toggleMaximizeFocused()` |
| — | close the focused pane (close admission applies) | `closeFocused()` |

Bindings are matched against a key exactly (unspecified modifiers must be up), so Ctrl+Tab and
Ctrl+Shift+Tab never collide; avoid giving two bindings combinations that can both match one key
(`ANY` modifiers), as the choice between them is then unspecified.

Every action is also a `DockAction` for `dock.perform(action)`, so a host's command registry,
menus, and keymap can drive them. Bindings are a plain map — replace or clear it:

```scala
dock.setKeyBindings(
  Map(
    KeyCodeCombination(KeyCode.F6)                            -> DockAction.FocusNextGroup,
    KeyCodeCombination(KeyCode.F6, KeyCombination.SHIFT_DOWN) -> DockAction.FocusPreviousGroup,
    KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN) ->
      DockAction.ToggleMaximize
  )
)
dock.perform(DockAction.NextTab)
```

**Your content keeps its keys.** The dock looks at a key only after the focused content has had
it (bubbling phase): a key the content consumes — arrows in a plot cursor, Enter, Esc — never
reaches the dock, and a key no binding matches is never consumed. The flip side: a control that
consumes Ctrl+Tab itself (a `TextArea` uses it for focus traversal) keeps it. When keyboard focus
moves into a pane by Tab traversal, the dock's focused pane follows.

## Tab context menu

Right-clicking a tab shows Close, Close Others, Close All, Open in New Window (Dock Back in a
floating window), Maximize / Restore Layout, and Minimize — each disabled when the group's header
policy forbids it. A hook receives the pane, its group, and the dock, plus those defaults, and
returns the final list; it runs each time the menu opens:

```scala
dock.setTabMenu: (ctx, defaults) =>
  val showAsTable = new MenuItem("Show as table")
  showAsTable.setOnAction(_ => println(s"table for ${ctx.pane} in group ${ctx.group}"))
  showAsTable +: defaults
```

## Accessibility

Tabs expose `AccessibleRole.TAB_ITEM` with their title; header buttons and tab close buttons
expose `AccessibleRole.BUTTON` with a name that follows their state ("Maximize" / "Restore
layout").
