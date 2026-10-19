# S4 keyboard and accessibility evidence

Source candidate: `3cf16329dd4e304ad374aa10539c3153ac03e1d9` on
`codex/scaladock-s4-accessibility`, based on
`948e0005ce26b85cb7d787d18006bc0e900643ba`.
Issue: `bd-01M4Y1AD636FK3M3DJNGTB6W9Y`. Recorded 2026-10-19.

The provider implementation has independent approval within its reviewed
scope. S4 acceptance remains in review because the native macOS enabled state
is incorrect in the tested JavaFX runtimes, including for a stock JavaFX Button.
No S5/S6 acceptance, source publication, or complete screen-reader qualification
is implied by this receipt.

## Implemented behavior

- Custom tab, close, header, minimized-strip, restore-badge and floating-titlebar
  controls have keyboard and assistive actions, stable names and appropriate
  JavaFX roles. Tab selection notifications and close admission remain intact.
- Arrow/Home/End tab navigation, Enter/Space activation, Shift+F10/ContextMenu
  menus, and Escape dismissal join the existing F6 and Ctrl+Tab routing.
  Content-consumed events stay consumed.
- Focus exposes otherwise hidden chrome. Focus returns to live content or a
  minimized strip after close, perspective, minimize, pop-out and dock-back.
  Invisible minimized close controls are excluded from traversal.
- `ShowTabMenu`, `TogglePopOut` and `ReopenClosed` are appended DockAction cases.
  Reopening restores the last admitted user-close batch from live snapshots;
  it is not general undo or restoration of disposed node identity.

## Verification

All runtime tests used coherent macOS/aarch64 JavaFX named modules, with older
JavaFX classpath jars removed. The lane runner is
[`tools/meridian-s4/run_probe.py`](../../../tools/meridian-s4/run_probe.py).

| Lane | Evidence | Result |
|---|---|---|
| JDK 22 / JavaFX 24.0.1+4 | `scaladock-s4-floor-final.log` and metadata | `scalafmtCheckAll`; 54 focused tests; native fixture; exit 0 |
| JDK 25.0.1 / JavaFX 27+30 | `scaladock-s4-current-02.log` and metadata | 54 focused tests; exit 0 |
| JDK 25.0.1 / JavaFX 27+30 | `scaladock-s4-native-current-01.log` and metadata | Native fixture; exit 0 |
| Native AX, floor | `floor-final/actions.json` | Nine actions and resulting state checks passed |
| Native AX, current | `current-01/actions.json` | Nine actions and resulting state checks passed |

The focused suites are Accessibility, Keyboard, Chrome, TabMenu, CloseAdmission,
Presentation and MinimizeFx. The current lane exercised the actual JavaFX 27
floating titlebar, including native focus, menu routing, F6 and Space dock-back.
The floor test asserts that the 27 titlebar is absent.

Commands are recorded as argv arrays with timestamps and actual exits in the
log metadata. The current test invocation predates the runner's addition of
`scalafmtCheckAll` to `--tests`; its log establishes the 54 tests, while the
final floor invocation establishes the formatting check. The Scala and CSS
implementation is the committed candidate. `git diff --check` and Python
syntax checks passed. The reviewer inspected code and retained results and
did not independently execute the tests.

`scaladock-s4-check-02.log` retains the initial 48-pass/3-fail result. Its focus,
menu dismissal and perspective findings were fixed. The 51-test and 54-test
intermediate logs and `floor-02` probe remain as historical evidence; the final
lane artifacts above supersede them.

## Native result and unresolved acceptance

The AX harness activated only the owned synthetic application and traversed its
windows. It invoked a stock JavaFX button, tab selection, maximize, restore
layout, minimize, restore pane, close pane, pop-out and dock-back. Each action
was checked against application state, not just the AX API return code. Both
owned fixtures exited normally after a finish marker; no native probe remains
running.

Despite successful actions, native `AXEnabled` was false for eight of the nine
floor targets and all nine current targets. This includes an enabled stock
JavaFX Button. The fixture recorded `isDisabled=false` and JavaFX accessible
`DISABLED=false` for custom buttons. The floor tab uses a legacy peer and
reports enabled; JavaFX 27's tab uses the newer native peer and reports disabled.

Inspection of the pinned upstream native implementation supports a JavaFX
bridge defect: the new accessibility base/button peers do not forward enabled
state. Native libraries extracted from the corresponding Maven artifacts match
the loaded cache libraries (SHA-256 recorded in `verification.json`). Sources:

- [JavaFX 24.0.1+4 AccessibleBase](https://github.com/openjdk/jfx24u/blob/24.0.1%2B4/modules/javafx.graphics/src/main/native-glass/mac/a11y/AccessibleBase.m)
- [JavaFX 24.0.1+4 JFXButtonAccessibility](https://github.com/openjdk/jfx24u/blob/24.0.1%2B4/modules/javafx.graphics/src/main/native-glass/mac/a11y/JFXButtonAccessibility.m)
- [JavaFX 27+30 AccessibleBase](https://github.com/openjdk/jfx/blob/27%2B30/modules/javafx.graphics/src/main/native-glass/mac/a11y/AccessibleBase.m)

Successful AX actions do not satisfy S4's requirement for correct native state.
Closing S4 needs an explicit acceptance of this bounded upstream limitation or
a runtime repair followed by renewed native checks. The provider retains the
correct JavaFX roles rather than disguising controls as unrelated roles.

This was an AX API probe, not a live VoiceOver workflow:
`Platform.isAccessibilityActive` remained false. Windows/Linux assistive
technology, complete screen-reader workflows and full S6 regression checks
are not qualified here. The inactive-window screenshot is a synthetic layout
record, not proof of active focus indication; focused styling was checked in
the live-Stage automated tests.

## Reproduction

Use a new output directory for each fixture. The runner records the local JDK
locations and pinned cached JavaFX coordinates. Compile the AX helper:

```sh
swiftc -module-cache-path /tmp/scaladock-s4-native/swift-cache \
  -framework ApplicationServices -framework AppKit \
  tools/meridian-s4/ax_probe.swift -o /tmp/scaladock-s4-native/ax_probe
python3 tools/meridian-s4/run_probe.py floor /tmp/scaladock-s4-native/new-floor --qualify
```

While that owned fixture runs, in a second terminal with macOS Accessibility
permission, run:

```sh
python3 tools/meridian-s4/check_ax.py /tmp/scaladock-s4-native/new-floor \
  /tmp/scaladock-s4-native/ax_probe
```

The harness obtains the exact PID from the fixture, asserts its synthetic
window title, saves the AX tree/action results, and signals finish. Repeat with
`current` and a fresh output directory for JavaFX 27. A fixture also has a
five-minute cleanup timeout. For focused tests alone use `--tests`.

## Next gate

Accept the documented upstream enabled-state limitation or retain S4 in review
pending a runtime fix. S5 and S6 remain dependency-blocked until that decision.
The user authorized full checks with the current free disk space on 2026-10-19;
that permission does not itself accept the accessibility limitation. See the
[independent review](independent-review.md).
