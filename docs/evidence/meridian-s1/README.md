# ScalaDock S1 custom-theme appearance evidence

Accepted locally on 2026-10-14 for Mote S1 bd-01M4Y1AD18TGSE7FQBMXQHBPFY.
This is a bounded provider implementation result; S6 independent review and
Meridian B0 consumption remain separate gates.

Base Git HEAD: 628c46fce92c4d31b0b08459ac0f18777b5ba527.
Local source digest: fbb37b53a59a273e5b0c221b35ecb393a83cd968590860b96ba19f9bd20526f5
([13-file manifest](source-manifest.json), [retained owned source](owned-source.zip),
[tracked changes](owned-tracked.patch)). No commit, push or provider-pin update.
[Verification receipt](verification.json) binds source, retained binaries, exact
command argv/exits and captures. Mote metadata is excluded from the source candidate.

## Acceptance

| Obligation | Evidence and disposition |
|---|---|
| Preserve existing Dark/Light/Custom(url) callers; review pattern matching | Original constructor/extractor and ordinals pass; unchanged old compiled client runs with candidate JARs. The appended enum case requires exhaustive source matches to add a branch. See compatibility below. |
| Known metadata reaches existing/new/restored floats; explicit host ownership | Six ThemeSuite tests and four shown EXTENDED-window phases pass. Main-scene ownership, pane identity and unchanged layout state are checked; themes apply before first show. |
| Unknown scheme has deliberate fallback | Legacy applyWindowScheme remains false/no-op. New applyWindowSchemeOrPlatform clears override; owned floats follow platform. Synthetic URLs deliberately contradict their palettes; no filename inference. |
| Preserve JDK22/FX24 floor; validate FX27 native behavior | Compile/load and 14 focused tests pass on each named-module lane. Candidate JAR classes all have major version66. Four bounded owned-window captures on JDK25/FX27 show native decorations and pass scene scheme assertions. |
| Generic synthetic fixtures and dependency isolation | No Meridian/AtlantaFX dependency or artifact classes. Floor remains JavaFX24.0.1/JDK22; demo remains FX27. |

## Runtime and commands

- [Floor baseline](baseline.log): unchanged Git HEAD, Scala3.7.0/sbt1.11.7,
  JDK22/FX24.0.1; two original ThemeSuite tests and packaged baseline JARs.
- [Floor candidate](floor-final.log): Scala3.7.4/sbt1.12.14, JDK22/FX24.0.1;
  scalafmtAll/scalafmtSbt, both formatting checks, compile, 14 ThemeSuite/
  PopoutSuite/CloseAdmissionSuite tests and fx/core packageBin. Exit0.
- [Current runtime](current.log): JDK25.0.1/FX27, same 14 tests executed on
  named FX27 modules, exit0. Production compilation still targets the FX24 floor.
- [Final probe floor compile](probe-floor-final-02.log): final probe compiles
  on JDK22/FX24, formatting checks pass, exit0.
- [Native probe](native-02.log): four focused EXTENDED floating windows on
  macOS14.3/aarch64, FX27+30, scale2.0, exit0. Scene assertions run before
  bounded Robot captures; an interior sample matches each synthetic scene.
  Only the owned window rectangle is captured. All owned stages close.
- [Separate scene mode](scene-final.log): the same four transitions, explicitly
  scene-only snapshots, exit0; these are not presented as OS decoration captures.

Reproduce from this checkout on the recorded host with
`python3 tools/meridian-s1/run_lane.py floor` or `current`.
For native capture, use `native --output NEW_DIRECTORY`; `scene` runs the explicitly
separate snapshot mode. The helper records exact local JDK/module paths and avoids
mixing FX24 and FX27 runtime jars. Log metadata records argv, status and termination.

The workspace-required producer pins were aligned to Scala3.7.4/sbt1.12.14.
The library JDK22/FX24.0.1 floor and demo FX27 lane are unchanged.
build.sbt also contains formatter-only changes; project/plugins.sbt changed only
formatting, not plugin versions.

## Native visual inspection

Inspected the four actual window PNGs: readable custom chrome and pane text,
dark/light surfaces and native macOS traffic lights. Restored dark matches new
dark, and unknown follows the current light platform scheme.

| Scenario | Scene scheme | Actual owned window |
|---|---|---|
| New custom dark | DARK | [Capture](native-02/custom-dark-new-window.png) |
| Existing custom light | LIGHT | [Capture](native-02/custom-light-existing-window.png) |
| Restored custom dark | DARK | [Capture](native-02/custom-dark-restored-window.png) |
| Unknown custom | platform LIGHT | [Capture](native-02/unknown-platform-window.png) |

Scene PNGs and per-frame runtime properties are retained in [native-02](native-02/).
These generic fixture palettes establish provider behavior, not Meridian visual
fidelity, accessibility, scientific colors or performance.

## Compatibility disposition

[Legacy source](../../../tools/meridian-s1/compat/src/main/scala/LegacyThemeClient.scala)
compiles against the retained baseline under -Werror
([final compile](legacy-compile-final.log)).
Its unchanged [compiled classes](legacy-binary/) run against candidate JARs:
[old binary receipt](legacy-binary.log), exit0, constructor/extractor/ordinal checks.
The candidate fx/core JARs are retained in [candidate-artifacts](candidate-artifacts/);
they were packaged on JDK22 before switching to the newer runtime.

Recompiling the old exhaustive three-case match against candidate JARs produces the
expected missing CustomWithScheme branch warning and fatal -Werror result:
[retained expected failure](legacy-source-candidate.log), exit1.
The explicitly [updated match](../../../tools/meridian-s1/compat/UpdatedThemeClient.scala)
then [compiles](updated-source-candidate.log), exit0.
This does not claim blanket source/binary compatibility. Old exhaustive binaries
must not receive the new enum case before migration. The pre-release API addition
and this migration are documented in [styling.md](../../styling.md); independent
provider-wide API/selector review remains S6.

## Preserved earlier findings and limits

The first [native attempt](native.log) failed its focused-owned-stage guard after
250ms. It captured no unrelated screen. The successful retry waits1500ms before
checking focus and capturing, with the same ownership/sample safeguards.
The first scene-only fixture had unreadable default Modena label text against its
synthetic dark background; fixture CSS now explicitly binds labels to -dock-text.
Earlier images/logs remain adverse evidence rather than release references.

Earlier non-named runtime logs preserve the FX unnamed-module warning. Final named
lanes avoid it. Some earlier zero-focus runs preserve macOS CVDisplayLink -6661;
the successful shown native run has no such diagnostic. Scala3.7.4 emits its
upstream sun.misc.Unsafe terminal-deprecation warning on JDK25. No CSS/parser or
compiler failure is hidden by those diagnostics.

This is local macOS evidence only. Windows/Linux native decorations and hosted CI
are unqualified. No full provider test suite, performance qualification, publication
or application runtime migration is claimed. Existing uncommitted Mote operations
and tool-updated FORMAT metadata are preserved separately. S2 becomes the next
provider task after S1 closes; S6 remains the independent receipt gate before B0.
