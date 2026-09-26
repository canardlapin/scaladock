# Changelog

All notable changes to scaladock. The project is pre-release (`0.1.0-SNAPSHOT`); no stable API or
binary-compatibility promise applies yet, but behaviour changes are recorded here so that
downstream apps pinning a commit can see what moved.

## Unreleased

### Behaviour changes — check these when updating a pin

- **Maven group is now `io.github.canardlapin`** (was `io.github.bbuchsbaum`): update
  `libraryDependencies` / pinned coordinates, e.g.
  `"io.github.canardlapin" %% "scaladock-fx" % "0.1.0-SNAPSHOT"`.
- **`DockEvent` has two new cases**, `PaneDetached` and `PaneReattached` (see Added); an
  exhaustive `match` over `DockEvent` needs them. Nothing emits them unless you use a retaining
  switch — `update` and `load` still dispose panes that leave, exactly as before.
- **The dock now handles a few keys** — F6 / Shift+F6 and Ctrl+Tab / Ctrl+Shift+Tab — after the
  focused content has had its chance; content that consumes a key keeps it. Turn them off with
  `dock.setKeyBindings(Map.empty)`. Keyboard focus moving into a pane (by Tab traversal) now
  also moves the dock's focused pane.
- **`Dock.close` on a non-closable pane is now a no-op**, and `Dock.requestClose` returns a
  completed `false`. Closing goes through asynchronous close admission (see
  [docs/close-admission.md](docs/close-admission.md)); `update(edit…)`, layout replacement and
  `dispose()` remain force operations.
- **Default metrics changed**: `LayoutSettings.default` now has `dividerPx = 1` (was 5) and
  `headerPx = 32` (was 28). Pass `Dock(factories, settings = LayoutSettings(headerPx = 28, …))` to
  keep the old values.
- **Drags no longer change the layout mid-gesture.** The source tab dims and exactly one
  `edit.drop` commits on release (ESC or a miss leaves the layout untouched). Dropping a group's
  only tab back onto its own group or window is a no-op.
- **Layout honours recursive minimums.** When fixed (`Px`/`Pct`) siblings over-claim a split, a
  cell is no longer squeezed below its subtree's minimum (`sizing.cellMin`); previously an `Fr`
  container could collapse to 0 px and its panes vanished.
- **Chrome is CSS-drawn.** Header and tab buttons are `.dock-icon` regions whose outlines are
  `-fx-shape` paths in CSS, replacing text glyphs. Style classes and variables are listed in
  [docs/styling.md](docs/styling.md) (new: `-dock-surface-raised`, `-dock-hover`, `-dock-pressed`,
  `-dock-shadow`, `-dock-selected-bar`, `-dock-drop-scrim`, `-dock-slot-fill`).

### Added

- **Panes that survive a layout change.** `Dock#switchTo` (and `load(json, retainPanes = true)`)
  keeps the views of panes that leave the layout alive and detached; a later layout containing
  the same `PaneId` gets the same live view back. `Dock#detachedPanes`, `Dock#releaseDetached`,
  and `requestClose` on a detached pane (through close admission) release them.
  `Dock#snapshot` returns the layout with live pane state.
- **`Perspectives`**: named layouts over one dock, each remembering its last arrangement; a pane
  shared by several is one live view; a perspective's floating windows close on leaving and
  reopen at their bounds; `reset`, `releaseUnused`, and `save`/`load` of every perspective plus
  the active one.
- **Keyboard actions as API**: `focusNextGroup`, `focusPreviousGroup`, `nextTab`, `previousTab`,
  `toggleMaximizeFocused`, `closeFocused`, `perform(DockAction)`, and remappable
  `keyBindings` / `setKeyBindings`. Keyboard focus lands in the pane's content.
- **Tab context menu**: Close, Close Others, Close All, Open in New Window / Dock Back,
  Maximize / Restore Layout, Minimize; `Dock#setTabMenu` hooks it with a `TabMenuContext`
  (pane, group, dock) and the defaults, returning the final items.
- **Accessibility**: tabs carry `AccessibleRole.TAB_ITEM` and their title; header and tab close
  buttons carry `AccessibleRole.BUTTON` and a name.
- `PaneView.icon()`: a per-pane icon for tabs, minimised strips, menus and the drag chip.
- `Dock#setPlaceholder`, `Dock#themeChanges`, `Dock#theme`, `DockTheme#stylesheets`,
  `DockTheme#isDark`, `DockTheme#applyWindowScheme` (native title bars follow the theme on
  JavaFX 25+, reached reflectively).
- Asynchronous close admission: `PaneView.prepareClose` / `closeCancelled`,
  `Dock#requestClose`, `Dock#requestCloseAll`.
- Floating windows draw a themed title bar on JavaFX 27+ (`HeaderBar`, reflective; older runtimes
  keep native decorations). A single-tab window's bar carries the pane chip, dock-back and close.
- Header-drop slots (following tabs slide aside), exact window-edge landing previews, live divider
  resize, responsive headers (tabs shrink, then whole-tab windows with a hidden-tab count; narrow
  groups fold actions into the chevron menu), double-click a tab to maximise, a "N hidden" badge
  while maximised, `:inactive` styling for background windows, and an empty-window placeholder.
- `sizing.allocateWithMins`.

### Fixed

- A focus change no longer rebuilds the tab strip, so a pressed tab can't leave the scene mid-drag
  (drags could die after their first step).
- Divider drags restore the host's cursor and clear their preview if the gesture is interrupted.

### Build

- Maven Central publishing is set up (signed, via sbt's Central Portal support); pushing a
  `vX.Y.Z` tag releases. One-time account steps are in [docs/releasing.md](docs/releasing.md).
- The published fx POM no longer names a platform classifier on its (provided) JavaFX
  dependencies — it previously carried the build machine's.
- `scaladock-core` and `scaladock-fx` target JDK 22 (`-java-output-version 22`) and are tested in
  CI on JDK 22 with JavaFX 24.0.1, alongside the main JDK 25 jobs on Linux and macOS.
- ScalaFX is now a dependency of the demo only; the library uses plain JavaFX.
- Licensed under Apache-2.0 (`LICENSE`).
