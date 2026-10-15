# S3 — live popups and presentation binding lifetime

Accepted locally on 2026-10-15. Mote: `bd-01M4Y1AD4DZVMZNHBXTA4VC80V`.

Candidate source digest: `e7687fa49aa218e9b62207937763e0d60163c39d5d86702a2f78e6789e3208d3`. Base Git HEAD: `628c46fce92c4d31b0b08459ac0f18777b5ba527`. At verification this was **uncommitted local source**, layered on the verified S1/S2 candidate; the subsequent local commit is recorded in Mote. It is not a released or independently qualified provider. Meridian's dependency pin remains at the base HEAD.

## Result and acceptance

| Criterion | Implementation and passing evidence |
|---|---|
| Open ghost/menu/tooltip policy | Theme or requested/effective density changes dismiss owned menus/tooltips and cancel gestures/ghosts. Reopening or a new press resolves current CSS and geometry. Context and overflow menus, Tooltip and drag ghost assertions pass. |
| Public independent roots and foreign styles | `Dock.bindStylesheets(Parent): Subscription` supplies current styles and the `.dock` marker. Reference-counted root leases and floating/header/ghost bindings preserve pre-existing stylesheet URLs, ordering and classes. |
| Appearance/density across creation and restore | Computed raised paints are dark `#30405a` and light `#f5eee2`. Same-turn reopen, density changes, new floats and restored floats pass; owner CSS is applied before publishing a theme change to avoid cached popup lookup colors. |
| Resource lifetime | Twenty bind/unbind/subscription cycles, scene/owner transfer, three hide/show cycles, retired keyboard input and idempotent disposal pass. Final owned root/binding counts are zero and exactly four panes dispose once. |
| Host registration contract | [Styling guide](../../styling.md#popup-policy-and-presentation-ownership) documents explicit public root registration, release and host ownership of native preferences/geometry. |

Temporary owner hide closes transient UI and retires floating presentation while preserving live pane nodes and the model. Showing that owner reconstructs presentation from the same nodes. Main lifecycle observers remain for this reuse. Applications must call `Dock.dispose()` after terminal close is admitted; this cancels all owned observers/subscriptions and disposes panes. The tests count logical binding scopes and exercise detached inputs/owners; they do not establish heap collectability or inspect all JavaFX internal listeners.

## Executed checks

| Lane | Runtime | Result |
|---|---|---|
| Floor | JDK 22 / JavaFX 24.0.1 named modules | Formatting, compile, 70 core + 72 FX tests, package: exit 0 |
| Current | JDK 25.0.1 / JavaFX 27+30 named modules | 70 core + 72 FX tests: exit 0 |
| Native timed specimen | JDK 25.0.1 / JavaFX 27+30, macOS arm64 | Four phases, 14 PNG scene captures, zero bindings after disposal, all owned windows closed: exit 0 |

Seven new `PresentationSuite` tests cover the S3 contract. Existing suites remain passing. [Raw commands, exits and hashes](verification.json) retain all runs, including adverse iterations. The final floor run is [floor-01.log](floor-01.log); other final logs are [current.log](current.log) and [native.log](native.log). JDK 25 emits an upstream Scala 3.7.4 Unsafe terminal-deprecation warning; it remains in the logs. Final compiler/CSS checks report no new warnings.

The timed phases are dark comfortable, light compact, dark compact with a newly detached window, and light comfortable with a restored floating window. Each waits before opening and capturing a real ContextMenu and Tooltip, asserts computed paints and preserved pane identities, and verifies dismissal on the following change. Outputs are **owned JavaFX scene snapshots**, not OS screen captures or title-bar/decorations evidence. Four representative images were visually inspected for readable text and clipping: [dark menu](native/dark-comfortable-menu.png), [light menu](native/light-compact-menu.png), [dark tooltip](native/dark-compact-floating-tooltip.png), and [restored float](native/light-comfortable-restored-floating.png).

## Reproduction and custody

From the ScalaDock root, use `python3 tools/meridian-s3/run_lane.py floor`, then `current`. For a native run use `python3 tools/meridian-s3/run_lane.py native --output /absolute/new/output-directory`. The helper records/resolves named-module JavaFX jars; its JDK paths are this machine's installations. `--focus` restricts either test lane to PresentationSuite. Run through the lean-logs wrapper to retain actual exits and full output.

The [source manifest](source-manifest.json) hashes 27 candidate files (nine S3-owned paths). All inherited S2 files outside S3 ownership were checked unchanged. [Candidate source](candidate-source.zip), [S3-only delta](s3-only.patch) and [pre-S3 files](before-s3.zip) distinguish this task from existing dirty work. Tracker metadata and unrelated changes are excluded.

`candidate-artifacts/` holds floor-built core/FX JARs copied before the current-runtime run. All class files target JDK 22 (major 66); no JavaFX, AtlantaFX or Meridian classes are bundled. [API shape receipt](api-shape.json) finds no removed public Dock JVM signatures and records the additive root-binding hook. This shape check does not replace S6 compatibility review.

The first focus run caught fixture syntax; the second exposed an automatic JavaFX root-class baseline mismatch and stale same-turn popup colors. The baseline assertion and owner CSS ordering were corrected, then the focused, full floor/current and timed native runs passed. Initial safe-initialization warnings were corrected before final runs. Earlier failing logs remain as adverse evidence.

No hosted CI, immutable Git candidate, cross-platform rendering, assistive-technology, heap/GC, performance, scientific or publishing qualification is claimed. S6 remains the independent provider gate and B0 still requires a reviewed candidate. **Next: S4 keyboard and accessible actions.**
