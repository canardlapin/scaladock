# Styling scaladock

scaladock renders through JavaFX CSS. The **class and pseudo-class names below are public
API** — breaking one is a breaking change under semver. Everything else (node structure inside
the chrome) is not.

## Two-layer stylesheet model

- `scaladock/dock.css` (always loaded) defines the structure and the **variables**.
- A *theme* is a stylesheet that redefines only the variables. Built-ins: `DockTheme.Dark`
  (the base defaults) and `DockTheme.Light`; supply your own with `DockTheme.Custom(url)` and
  `dock.setTheme(...)`, which restyles every window including popouts.

## Appearance metadata and native windows

A stylesheet URL does not establish whether a theme is dark. Use
`DockTheme.CustomWithScheme(url, dark = true)` or
`DockTheme.CustomWithScheme(url, dark = false)` when that information is known.
`theme.isDark` returns `Some(true)`, `Some(false)`, or `None` for legacy
`Custom(url)`. Names, URL contents and comparisons with built-in values are never
used to infer appearance. Both custom variants load the same base-then-custom
stylesheet sequence.

Dock-owned floating scenes receive the current scheme and CSS before their first
show, including windows restored from a saved layout. Live changes update existing
floating scenes. A legacy custom theme clears a previous scene scheme override and
follows the platform preference. This fallback concerns native decorations; CSS
still comes from the supplied URL. Appearance is presentation state and is not
written into the saved layout or pane model.

The host owns the main scene. After creating that scene, and again when changing
themes, explicitly call:

```scala
dock.setTheme(theme)
val supported = theme.applyWindowSchemeOrPlatform(mainScene)
```

This hook applies known metadata or clears the override for unknown metadata.
The older `applyWindowScheme(mainScene)` method keeps its existing behavior:
unknown metadata is a no-op and returns false. Both methods return false when the
runtime cannot apply the public scene preference API. JavaFX 24 remains supported;
per-scene native scheme overrides require JavaFX 25 or later and the platform's
decoration support. JavaFX 27 EXTENDED floating windows use the same public hook.
No application-wide user-agent stylesheet is installed by Dock.

### Source and binary compatibility

`Dark`, `Light`, and the one-argument `Custom(url)` constructor/extractor retain
their original shape and ordinals 0, 1 and 2. `CustomWithScheme` is appended at
ordinal 3. A retained client compiled against the previous JAR runs against the
candidate for those original values, including the old constructor and extractor.

Adding an enum case changes exhaustive-match source obligations. An old three-case
match fails recompilation under `-Werror`; add
`case DockTheme.CustomWithScheme(url, dark) => ...` before using the new variant.
An old binary's exhaustive match must not receive the new case without migration.
This is a bounded compatibility result, not a blanket source/binary compatibility
claim. See [S1 evidence](evidence/meridian-s1/README.md) for the unchanged compiled
client, retained expected compiler failure and updated-source check.

## Live density and effective metrics

Construct a dock with `Dock(..., settings = LayoutSettings(...))`. On the JavaFX
application thread, update presentation in place:

```scala
dock.setSettings(dock.requestedSettings.copy(headerPx = 28))
dock.setSettings(dock.requestedSettings.copy(headerPx = 32))
```

`requestedSettings` holds the requested density; `settings` is the current effective,
immutable snapshot shared by all renderers, minimized strips, recursive geometry,
divider contexts and pane-drag hit tests. Header height is a floor: natural CSS text,
icon, overflow and control minimum/preferred dimensions can raise it globally.
After editing CSS, call `dock.setSettings(dock.requestedSettings)` to settle all
windows immediately. Normal CSS/layout pulses also recompute effective minima.
The existing `Dock.settings` getter remains available; it now reflects live metrics.

`LayoutSettings` keeps its original four fields and constructor/extractor shape.
All fields are logical pixels and must be finite and non-negative; NaN, either
infinity and negative values throw `IllegalArgumentException` during construction
or copy. Zero is allowed. Null settings and off-thread mutation are rejected before
gesture cancellation or presentation changes.

An update cancels armed/active pane, title-chip, external-source and divider
gestures before publishing new metrics. Ghosts, drop previews, insertion slides,
resize previews and borrowed cursors are cleared without committing the old
gesture. A stale release cannot move a pane or commit a ratio. A new press captures
the new geometry. Use normal gesture completion before changing presentation if a
drop is intended to commit.

Existing, new and restored floating windows use the current snapshot. A native
client title bar retains platform/CSS minima (34px in the built-in theme); it can
be taller than a 28px dock tab header. Its text/control preferred minima are
respected. Where that bar replaces a lone group's header, geometry and drop areas
both give the group header zero height.

When space cannot meet the requested minima, available extents remain finite and
non-negative. Dividers are clipped to the viewport; minimized strips share the
space left after dividers, live content may reach zero, and headers are capped by
their allocated group height. Chrome/content clips prevent child minima from
painting into neighbors. Restoring space restores presentation without changing
stored sizes. Window-edge hover bands remain inside even tiny viewports; the
established smallest-containing-area rule still resolves overlapping targets.

Metrics changes preserve pane Nodes, selected IDs, split ownership and application
state. They do not emit LayoutChanged or alter the saved-layout schema. Content
resize signals still describe actual view size changes. The existing public
`sizing.commitDivider` signature and allocation contract remain; UI edits use the
constrained layout allocation, including minimized siblings.

See [S2 acceptance evidence](evidence/meridian-s2/README.md) for actual bounds,
enlargement/shrinkage, identity counters and cancellation checks on both runtimes.
Meridian integration and independent provider qualification remain separate gates.

## Popup policy and presentation ownership

A changed theme, requested density or effective CSS-driven geometry dismisses owned
context/overflow menus and tooltips and cancels any armed/active drag. Reopen the
overlay, or start a new gesture, to use the current appearance. Ghosts are
single-gesture snapshots and are destroyed on cancellation. There is no automatic
resumption or replay of the old action.

Theme application resolves owner CSS before a popup can reopen in the same FX
turn. Tab context menus are built fresh; the standard MenuButton skin still owns
overflow behavior. Tooltip roots acquire current CSS only while visible. The
independent ghost root and floating/header roots carry the `dock` marker and
public theme resources. These paths use public JavaFX APIs and ordinary controls.

Host-created dialogs, popups and inspectors need explicit registration when they
are outside the dock's root. Bind a specific root, then cancel its lease in the
host's existing close/disposal path:

```scala
val styles: Subscription = dock.bindStylesheets(dialog.getDialogPane)
// In the host's existing dialog-close handler:
styles.cancel()
```

For a host PopupControl, register `popup.getScene.getRoot` before showing it and
cancel after it hides. Re-register when reopening. Compose this with existing
lifecycle handlers rather than replacing them blindly. Choose one appearance
owner for a root. Repeated leases from the same dock share one binding; the last
cancellation removes only stylesheet/marker additions made by that binding.
Pre-existing and unrelated styles, class order and owner event handlers remain
under host ownership. Theme changes update bound hidden roots too.

`bindStylesheets` manages CSS resources only. It does not install an application
user-agent stylesheet, register arbitrary windows by scanning the scene graph,
apply main-scene native scheme preferences or choose geometry for host controls.
Use the existing `DockTheme.applyWindowSchemeOrPlatform(scene)` hook for native
scheme ownership. Host dialogs/popup content keep their own sizing policy.

All presentation mutation, root binding and lease cancellation belong on the FX
thread. `setTheme` rejects null themes and disposed docks. `Dock.dispose()` is
idempotent and closes transient UI, cancels managed event/theme subscriptions and
external drag-source handlers, detaches scene/window/focus/input observers,
uninstalls tooltips, retires skins/animations and releases pane views.

A temporary owner hide dismisses overlays and closes floating presentation while
preserving the layout and live application panes. Showing the owner again restores
floats using those same pane instances and current preferences. The main dock's
bounded lifecycle bindings remain for that reuse; terminal app/window shutdown
must call `Dock.dispose()` after its close-admission flow. Scene/window transfer
detaches old owners, so hiding an old window cannot close the current owner's
floats. This preserves hide/show semantics instead of force-disposing scientific
views when an owner is temporarily hidden.

See [S3 evidence](evidence/meridian-s3/README.md) for computed popup paints,
dismiss/reopen cases, logical binding-scope counts, old-owner/retired-input checks,
repeated lifecycle cycles and timed native scene captures. These counts describe
owned registrations, not a JVM heap or garbage-collection benchmark.

## Variables (define these in a custom theme)

Define them on the `.dock` selector — the marker class carried by every dock window's root
*and* by the drag ghost (which lives in its own popup window, outside any `.dock-layout`).
Hosts can share them with their own chrome (toolbars, status bars): add the `dock` style class
to that node and the sheets from `DockTheme#stylesheets`.

| Variable | Role |
|---|---|
| `-dock-base` | content background; the selected tab "opens into" it |
| `-dock-surface` | header strip and minimised-strip background |
| `-dock-surface-raised` | menus, tooltips, the drag chip |
| `-dock-surface-veil` | translucent surface (legacy alias) |
| `-dock-outline` | hairlines: dividers and the rule under each tab strip |
| `-dock-accent` | focused tab bar, drop-preview stroke, divider sash |
| `-dock-accent-soft` | drop-preview fill (accent at ~15–20% alpha) |
| `-dock-text` | chrome text and icons |
| `-dock-text-strong` | selected / hovered chrome text and icons |
| `-dock-hover` | hover wash |
| `-dock-pressed` | pressed wash |
| `-dock-shadow` | shadow colour for menus and the drag chip |
| `-dock-selected-bar` | top bar of the selected tab in an unfocused group |
| `-dock-drop-scrim` | neutral veil layered with the drop-preview tint |
| `-dock-slot-fill` | the header-drop slot's light tint |

## Style classes

| Selector | Element |
|---|---|
| `.dock-layout` | the root region of each dock window (`:dragging` while a pane is dragged anywhere, `:inactive` while its window lacks OS focus) |
| `.dock-group` | one tab group (`:active` = contains the focused pane, `:maximized`, `:minimized`, `:floating` = the sole group of a floating window) |
| `.dock-header`, `.dock-tabs` | the header bar and its tab strip (overflowing tabs first shrink, then the strip shows whole tabs around the selected one; the wheel steps through them) |
| `.dock-tab` | one tab (`:selected` = the visible tab, `:dragging` = being dragged) |
| `.dock-tab-icon`, `.dock-tab-title`, `.dock-tab-close` | a tab's icon holder, label and close button |
| `.dock-header-buttons` | the button cluster (right side; shown on the active or hovered group) |
| `.dock-header-button` with `.popout` / `.minimize` / `.maximize` / `.close` | the header buttons |
| `.dock-tab-overflow` | the chevron menu shown when tabs don't fit, labelled with the hidden-tab count (`:quiet` when it holds only folded actions) |
| `.dock-strip`, `.dock-strip-title`, `.dock-strip-icon` | a minimised group's sideways strip |
| `.dock-divider`, `.dock-divider-sash` | a split divider (`:vertical`, `:dragging`, `:hot` = hovered past a short delay) and its accent line |
| `.dock-drop-indicator` | the drop preview (inset, glides between zones; a tab-shaped slot for header drops, while the following tabs slide aside) |
| `.dock-ghost`, `.dock-ghost-chip`, `.dock-ghost-title` | the drag ghost popup root, its chip and label |
| `.dock-empty`, `.dock-empty-title`, `.dock-empty-hint` | the placeholder of an empty window |
| `.dock-unresolved` | placeholder for panes with no registered factory |

## Icons

Chrome glyphs are `.dock-icon` regions whose outline is an SVG path in CSS (`-fx-shape`, unscaled,
centred). Kinds: `.close`, `.minimize`, `.maximize`, `.popout`, `.chevron-down`, `.empty-layout`.
State swaps are CSS-only (`.dock-group:maximized .maximize .dock-icon` shows "restore", and so on),
so a theme can replace any glyph without code. Pane views supply their own tab icon by
overriding `PaneView.icon()`.

## Targeting your own pane types

Your pane's node keeps whatever classes you give it — style your content directly. To style
chrome per pane type, use a custom theme sheet with higher-specificity selectors.
