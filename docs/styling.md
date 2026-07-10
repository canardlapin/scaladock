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

| Variable | Role |
|---|---|
| `-dock-base` | window and content background |
| `-dock-surface` | header/chrome background |
| `-dock-surface-veil` | drag-ghost backdrop (surface, slightly translucent) |
| `-dock-outline` | group borders |
| `-dock-accent` | focus highlight, drop-indicator border, ghost border |
| `-dock-accent-soft` | drop-zone highlight fill (accent at ~30% alpha) |
| `-dock-text` | chrome text |
| `-dock-text-strong` | active/selected chrome text |

## Style classes

| Selector | Element |
|---|---|
| `.dock-layout` | the root region of each dock window |
| `.dock-group` | one tab group (`:active` = contains the focused pane) |
| `.dock-header`, `.dock-tabs` | the header bar and its tab strip |
| `.dock-tab` | one tab (`:selected` = the visible tab) |
| `.dock-tab-title`, `.dock-tab-close` | tab label and its close glyph |
| `.dock-header-buttons` | the button cluster (right side) |
| `.dock-header-button` with `.popout` / `.maximize` / `.close` | the header buttons |
| `.dock-tab-overflow` | the ⋯ menu shown when tabs don't fit |
| `.dock-divider` | a split divider (`:vertical` = a vertical bar, `:dragging` while dragged) |
| `.dock-drop-indicator` | the translucent drop-zone highlight |
| `.dock-ghost`, `.dock-ghost-title` | the floating drag ghost |
| `.dock-unresolved` | placeholder for panes with no registered factory |

## Targeting your own pane types

Your pane's node keeps whatever classes you give it — style your content directly. To style
chrome per pane type, use a custom theme sheet with higher-specificity selectors.
