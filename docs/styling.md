# Styling scaladock

scaladock renders through JavaFX CSS. The **class and pseudo-class names below are public
API** — breaking one is a breaking change under semver. Everything else (node structure inside
the chrome) is not.

## Two-layer stylesheet model

- `scaladock/dock.css` (always loaded) defines the structure and the **variables**.
- A *theme* is a stylesheet that redefines only the variables. Built-ins: `DockTheme.Dark`
  (the base defaults) and `DockTheme.Light`; supply your own with `DockTheme.Custom(url)` and
  `dock.setTheme(...)`, which restyles every window including popouts.

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
