# ScalaDock S2 atomic presentation metrics

Accepted locally on 2026-10-14 for Mote S2 bd-01M4Y1AD2TV1YDNQ20NKE2PF6M.
Independent provider qualification remains S6; Meridian's default provider pin is unchanged.

Base Git HEAD: 628c46fce92c4d31b0b08459ac0f18777b5ba527.
Local source candidate: d0798d09347bcd9d4d8a23e7dad4275b5feb5d928f5b34ade64f414de91bb1a9
([23-file manifest](source-manifest.json), [candidate source](candidate-source.zip)).
This candidate includes the inherited S1 work and 12 S2-owned source paths.
[S2-only patch](s2-only.patch) and [pre-S2 archive](before-s2.zip) distinguish this
task from the preserved dirty checkout. No commit, push, merge, release or publication.
[Verification](verification.json) records exact commands/exits, artifact and capture
hashes, runtime lanes, provenance and limitations.

## Acceptance criteria

| Obligation | Evidence and result |
|---|---|
| One public FX-thread update path and current snapshot | setSettings/requestedSettings/settings; all windows read one effective immutable LayoutSettings. Nine MetricsSuite cases verify actual header/content bounds and model hover rectangles. Pass. |
| Invalid metrics and infeasible-space contract | Four fields reject negative/NaN/infinite values; zero is allowed. Independent core cases cover zero/tiny space and huge recursive minima. FX zero-space test verifies chrome/content clips. Pass. |
| Cancel armed/active gestures before changing metrics | Tab, title-chip route, external source and divider cleanup share cancellation; tests exercise armed/active tabs/sources, ghost/preview/slot cleanup, cursor restoration, stale releases and subsequent gestures with new geometry. Pass. |
| 32 -> 28 -> 32 and enlargement across floats | Existing/new/restored floats run the matrix. Timed JavaFX27 probe covers 32/28/restored32/64/font42/reset28 after native events settle, with matching actual header/content geometry. Pass. |
| Preserve nodes, selected IDs, split ownership, application state and codec | Same Nodes, construction/disposal counters, exact LayoutState/save equality, no LayoutChanged, model-root identity across restoration. Existing full codec/perspective/close/popout suites pass. Pass. |
| Respect dynamic preferred minima | Font42 alone raises effective header to52; an inline58px control raises it further. Both propagate across windows and shrink back to28 after CSS reset. Pass. |

The title-chip route uses the same DragController as tabs. No separate title-chip
drag event script or assistive-technology claim is made. The scientific/application
state assertion uses synthetic mutable pane content; this is not scientific validation.

## Executed checks

| Lane | Evidence | Result |
|---|---|---|
| JDK22 / JavaFX24.0.1, named modules | [Floor final](floor-final-03.log) | Formatting checks, compile, 70 core tests, 65 FX tests and fx/core packaging; exit0. |
| JDK25.0.1 / JavaFX27, named modules | [Current](current.log) | 70 core and65 FX tests, including all nine new FX metrics cases; exit0. |
| JavaFX27 timed native fixture | [Native](native.log) | Six phases, 12 scene captures, four pane instances, zero rebuilds/disposals before cleanup, exactly four disposals on cleanup; exit0. |
| Public JVM API shape | [API receipt](api-shapes.json) | Previous Dock, LayoutSettings and companion public signatures retained. Additive setSettings/requestedSettings; shape check only, not independent binary qualification. |

Scala3.7.4/sbt1.12.14 and JDK22/FX24.0.1 library floor remain unchanged from S1.
Candidate [fx/core JARs](candidate-artifacts/) were packaged on JDK22 and copied
before the JDK25 run; all classes have major version66. No Meridian/AtlantaFX
dependency or bundled classes were added.

Reproduce with `python3 tools/meridian-s2/run_lane.py floor` or `current`.
`--focus` runs MetricsSuite during diagnosis. `--output NEW_DIRECTORY` retains
synchronous test snapshots. For settled native evidence use
`python3 tools/meridian-s2/run_lane.py native --output NEW_DIRECTORY`.
The host-specific helper pins coherent module paths; log metadata records argv,
status, duration and termination reason.

## Rendered evidence

macOS 14.3/aarch64, OpenJDK25.0.1, JavaFX27+30.
The native probe waits for real window events/pulses, checks actual laid-out bounds,
then takes JavaFX scene snapshots of the owned synthetic windows. These images do
not include OS decorations. Four representative compact/enlarged/font-only main
and floating images were visually inspected for clipping and overlap; every phase
has bounds, instance and state assertions.

| Phase | Requested/effective header | Main | Floating |
|---|---|---|---|
| Comfortable | 32/32 | [Scene](native/comfortable-32-main.png) | [Scene](native/comfortable-32-floating.png) |
| Compact | 28/28 | [Scene](native/compact-28-main.png) | [Scene](native/compact-28-floating.png) |
| Restored | 32/32 | [Scene](native/restored-comfortable-32-main.png) | [Scene](native/restored-comfortable-32-floating.png) |
| Enlarged | 64/64 | [Scene](native/enlarged-64-main.png) | [Scene](native/enlarged-64-floating.png) |
| Font42 | 28/52 | [Scene](native/css-font-42-main.png) | [Scene](native/css-font-42-floating.png) |
| Reset | 28/28 | [Scene](native/css-reset-28-main.png) | [Scene](native/css-reset-28-floating.png) |

Per-phase TXT files retain requested/effective metrics, header/content measurements
and pane counters. The native HeaderBar retains its platform/CSS minimum; it need
not be28px when the dock tab strip is28px. Where it replaces a lone group header,
the actual group header and model hit geometry both have zero height.

Synchronous [floor](floor-captures-03/) and [current](current-captures/) test images
are supplementary. An intra-turn Scene.snapshot can observe transient window
dimensions before native resize events arrive; these are not the settled visual
reference. The timed native directory above is the release-facing local specimen
for this bounded task.

## Preserved findings and compatibility

Earlier logs and images remain:

- [First compile/core run](compile-01.log) caught safe-initialization warnings and
  a regression in the existing public raw-allocation divider property. Callbacks
  now attach after Dock construction; the public helper retains its previous
  contract. UI edits use the same constrained/minimized allocation as layout.
- Subsequent floor runs exposed allocated-height feedback in preferred measurement.
  Only natural child dimensions and explicit header minima now raise density.
- The overflow chevron's decorative8px border insets imposed a32px minimum.
  Reducing them to4px permits compact28 without suppressing preferred minima.
- Early fixture compilation and helper syntax failures are retained. Tests now
  use explicit synthetic nodes and required model conversions.
- One synchronous snapshot exposed a small transient float where a window-edge
  hit area legitimately outranks a header under the established smallest-area
  rule. The test now verifies actual/model header rectangles and valid composite
  hits; full drag tests independently prove new geometry is used. Timed native
  evidence checks fully settled, sufficiently sized windows.

The final floor/current logs have no compiler or CSS/parser warning. JDK25 emits
Scala3.7.4's upstream sun.misc.Unsafe terminal-deprecation diagnostic, retained in
the current/native logs. No warning is suppressed to obtain acceptance.

The settings getter retains its JVM descriptor but now exposes live effective
metrics. Invalid LayoutSettings are deliberately rejected. The case-class shape
and existing public divider signature remain. Full independent source/API/selector
and runtime qualification remains S6. Popup/theme/disposal propagation is S3.
This result is local macOS evidence, not hosted CI, cross-platform accessibility,
performance, scientific correctness or application adoption.
