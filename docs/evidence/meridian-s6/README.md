# ScalaDock combined provider qualification

Reviewed source candidate: **`87f900e9bdb1cce08872b363d4fe20a04c458b29`**.
Local branch: `codex/scaladock-s4-accessibility`. Recorded 2026-10-19.
Provider issue: `bd-01M4Y1AD9D3H9T2PPFKHJYFF3S`.
Consumer handoff: Meridian `bd-01M4Y1ABZ2J47PJP195GP8JSPS` (B0).

This candidate combines S1–S3 with S4 keyboard/assistive actions and S5 runtime
motion policy. S4 implementation was reviewed at
`3cf16329dd4e304ad374aa10539c3153ac03e1d9`; the user explicitly accepted its
documented upstream macOS enabled-state limitation before authorizing S5/S6.
S5 was independently accepted at `b928a4468eeaf774729c96aa02111b0d71e9bcd8`.
The [S5 receipt](../meridian-s5/README.md) retains its original failed cursor
regression as well as both passing focused lanes.

## Full qualification results

| Runtime | Commands after runtime selection | Result |
|---|---|---|
| JDK 22 / JavaFX 24.0.1+4, macOS/aarch64 | `scalafmtCheckAll core/compile fx/compile core/test fx/test` | 70 core + 103 FX = **173 passed**, exit 0 |
| JDK 25.0.1 / JavaFX 27+30, macOS/aarch64 | `scalafmtCheckAll compile test` | Aggregate compilation including demo; 70 core + 103 FX = **173 passed**, exit 0 |
| Native AX, floor | Owned synthetic fixture plus nine AX actions and resulting-state assertions | **9 passed**, both processes exit 0 |
| Native AX, current | Owned synthetic fixture plus nine AX actions and resulting-state assertions | **9 passed**, both processes exit 0 |

The full `scaladock-s6-floor-01.log` and `scaladock-s6-current-01.log` retain
every result. Adjacent `.meta.json` files record launcher commands, timestamps
and exits. `floor-01.json` and `current-01.json` record the exact sbt argv, JDK
locations and versions, JavaFX classifier/jar hashes, candidate SHA and empty
tracked diff at launch. `RUNTIME_PROBE` records the executing JVM and class
locations for all three named modules; fixture assertions reject mismatched
JDK/module versions. JavaFX 27 titlebar focus, menu routing, F6 and dock-back
actually ran in the current lane.

The full suites cover persisted layouts/perspectives, pane identity, asynchronous
close admission, explicit custom color schemes, metrics and hit tests, live
popups and resource ownership, keyboard/focus behavior, and custom motion.
`MOTION_TRACE` lines retain native-pulse interpolation, reduced-motion settling,
and stopped work after hide/detach/dispose. Real drag sequences preserve their
target, pane and cleanup across policy changes.

The 85-file `source-manifest.json` confirms the source, build/configuration,
tools and relevant public documentation match the candidate byte for byte and
that there were no untracked source inputs. The runtime launcher alone is not
an immutable-checkout guarantee: it records starting HEAD and tracked diffs;
this additional source comparison and independent inspection bind this run.

## API and selector compatibility

`api-shape.json` and `dock-api-{s3,candidate}.txt` compare the retained S3 Dock
class with the candidate: no unmangled Dock method signatures were removed.
The added methods expose motion policy and keyboard actions. This is a bounded
JVM-shape check, not a full binary/source compatibility certification; Scala
`private[fx]` members may also appear in javap. All 158 core and 88 FX class files
inspected after the floor run have class major 66 (JDK 22).

`DockMotion` is additive. Existing CSS selectors, constructors, layout state and
persistence schema are preserved. S4 appends DockAction cases, preserving old
ordinals; consumers with exhaustive matches must add the new cases. The
`ReopenClosed` action restores the last admitted user-close batch from live
snapshots; it is not general undo or resurrection of disposed node identity.
See [workspaces](../../workspaces.md) and [motion preferences](../../styling.md#motion-preferences).

## Native evidence and accepted limitation

`native-floor/` and `native-current/` retain AX trees, action/state pairs and
fixture state. Actions cover a stock JavaFX Button, tab selection, maximize,
restore layout, minimize, restore pane, close pane, pop-out and dock-back.
The probes operated only on their owned synthetic windows and exited normally;
owned PIDs 19234 (floor) and 13666 (current) were confirmed absent afterward.

Native `AXEnabled` still reports false for eight of nine floor targets and all
nine current targets, including the enabled stock JavaFX Button. JavaFX-side
disabled state is false and actions succeed. The upstream bridge diagnosis and
sources are retained in the [S4 receipt](../meridian-s4/README.md). The user's
acceptance permits this bounded provider qualification; it does not make the
native state correct. `Platform.isAccessibilityActive` remained false, so this
is AX API evidence, not complete VoiceOver qualification.

The JDK 25 native fixture emits Scala's `sun.misc.Unsafe` deprecation warning;
sbt prefixes its stderr with `[error]`, but the fixture and harness both exit 0.
No runtime workaround or role substitution was introduced.

## Reproduction and scope

Use [`tools/qualify_runtime.py`](../../../tools/qualify_runtime.py) after
`sbt update` on JDK 25. Supply the matching JDK location and a new manifest path:

```sh
python3 tools/qualify_runtime.py floor --java-home /path/to/jdk22 --manifest /tmp/floor-runtime.json
python3 tools/qualify_runtime.py current --java-home /path/to/jdk25 --manifest /tmp/current-runtime.json
```

The library's compile/POM floor remains JDK 22 / JavaFX 24.0.1. A real JavaFX 27
CI job is now configured, but hosted execution is not claimed. Local evidence
is macOS/aarch64 only. Windows/Linux native behavior, changing OS preferences
through system settings, full screen-reader workflows, heap/GC performance,
publication and deployment remain unqualified here.

The [independent review](independent-review.md) is separate from the implementer;
the reviewer inspected source and retained evidence without rerunning builds.
Meridian B0 must explicitly pin this candidate and run its own consumer probe.
This provider receipt does not accept that consumer or change its runtime.
