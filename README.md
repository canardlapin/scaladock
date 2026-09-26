# scaladock

A docking framework for JavaFX in pure, typed Scala 3 — tabbed groups, splits, drag-and-drop
rearrangement, floating windows, and layout persistence, modeled conceptually on
[golden-layout](https://github.com/golden-layout/golden-layout).

**Status: 0.1.0 in development.** Single- and multi-window docking, drag & drop, and
persistence all work; API may still move before 1.0.

## Design in one paragraph

The layout is **one immutable value** (`LayoutState`): a tree of `Split`s and tab `Group`s
whose leaves are `Pane`s carrying *typed* state. Every gesture — a drop, a divider drag, a
close, a pop-out — is a pure function `LayoutState => LayoutState` in `scaladock.edit`,
property-tested against the tree's laws (no empty groups, no single-child splits, no same-axis
nesting; a drop can never lose a pane). The JavaFX layer holds the single mutable cell: it
canonicalizes each transition, reconciles long-lived views keyed by stable ids — **your pane's
node is created once and only ever reparented**, surviving tab switches, splits, drags, and
pops into other OS windows — and derives typed `DockEvent`s by diffing states. Floating
windows are plain data in the state, so multi-window layouts save and load like everything
else.

## A VS Code-style layout in one expression

```scala
import scaladock.*, scaladock.dsl.*
import scaladock.fx.*
import upickle.default.ReadWriter

// 1. typed pane state, tagged for persistence
case class ViewerState(path: String, zoom: Double) derives ReadWriter
case class ShellState(history: List[String]) derives ReadWriter
val Viewer = PaneType[ViewerState]("app.viewer")
val Shell  = PaneType[ShellState]("app.shell")

// 2. views: a JavaFX node plus a typed snapshot for saving
val factories = PaneFactories.empty
  .register(Viewer)(s => ImageViewerPane(s))   // gets a PaneContext[ViewerState]
  .register(Shell)(s => ShellPane(s))

// 3. the layout reads like a picture of itself
val layout = LayoutState.of(
  row(
    Shell(ShellState(Nil)).titled("Explorer") sized 260.px,
    column(
      row(
        Viewer(ViewerState("scan-01.nii", 1.0)).titled("scan-01"),
        Viewer(ViewerState("scan-02.nii", 2.5)).titled("scan-02")
      )                                        sized 1.fr,
      Shell(ShellState(Nil)).titled("Console") sized 220.px
    )                                          sized 1.fr
  )
)

// 4. one dock, one scene
val dock = Dock(factories, initial = layout)
stage.setScene(new Scene(dock.view, 1400, 900))

// persistence: typed both ways; unknown pane types survive untouched
val saved: ujson.Value = dock.save()
dock.load(saved)
```

Everything else is gestures: drag tabs to rearrange (headers insert, edges split, window
borders dock), drag dividers to resize, `□` to maximize, `↗` to float a group in its own OS
window (drag tabs freely between windows; closing a floating window docks it back home).

## Modules

| Module | Contents |
|---|---|
| `scaladock-core` | JavaFX-free layout model: tree, transitions, sizing math, geometry, typed pane state, JSON codec, events, DSL. Depends only on uPickle. |
| `scaladock-fx`   | JavaFX/ScalaFX rendering and interaction: `Dock`, drag & drop, floating windows, theming |
| `demo`           | VS Code-style imaging mock (`sbt demo/run`) — also the manual acceptance harness |

`scaladock-fx` declares JavaFX as `provided`: add your platform's JavaFX natives alongside it
(see `fxClassifier` in `build.sbt` for the standard pattern).

## Documentation

- [Styling & theming](docs/styling.md) — the CSS contract
- [Asynchronous close admission](docs/close-admission.md) — letting panes veto or defer a close
- [SemanticDB output](docs/semanticdb-output.md) — keeping compiler metadata out of source trees
- [Manual release checks](docs/manual-checks.md)
- The demo source (`modules/demo`) is the living tutorial

## Development

```
sbt test            # core: pure property tests; fx: headless toolkit tests
sbt demo/run        # the imaging demo
sbt scalafmtAll     # format
```

Licensed under Apache-2.0.
