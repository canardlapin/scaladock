# PR CI focus-recovery correction

Source candidate: `39fccfeaf920ec9e6522351d9ef12c5d1bd7f98b`.
Follow-up: [PR #3](https://github.com/canardlapin/scaladock/pull/3).
Existing provider issue: `bd-01M4Y1AD9D3H9T2PPFKHJYFF3S` (S6).

The [original PR CI run](https://github.com/canardlapin/scaladock/actions/runs/39341541991)
at `8428056ba70302f90616bf8ddbc40daa6856864b` passed macOS but failed the same
AccessibilitySuite focus-recovery assertion in all three Linux jobs. Each
failed FX lane reported 102 passed and one failed. PR #2 was subsequently
merged; this correction is a separate PR against that main revision.

## Cause and correction

Each Scene retains its focus owner even when another window has native focus.
The recovery decision preferred the native-focused window's retained owner
over the owner associated with the model's focused pane. When native window
activation lagged behind `Dock.focus`, closing the model-focused main pane
left a surviving floating owner as the recovery witness. No recovery ran, and
the model's focused pane became `None`.

`Dock.applyOne` now prefers the owner associated with `prev.focused`. Native
focus remains the fallback, and the existing visibility/liveness and
perspective-change conditions still control whether recovery happens.
This does not change the broader synchronization policy for native window
activation and model focus.

The regression covers both shown and hidden main Stages. A hidden main Stage
cannot gain native focus, but its Scene retains a focus owner: this reproduces
the mismatch locally. Both variants assert the input model and Scene owners,
then require the exact surviving Beta pane as model and Scene focus immediately
after close and after native pulses.

## Local evidence

Retained raw logs and command/exit metadata are in [ci-followup/](ci-followup/).

- Before the production fix: `sbt -batch scalafmtAll 'fx/testOnly scaladock.fx.AccessibilitySuite'`
  on JDK 25.0.1 / JavaFX 24.0.1+4; 10 passed, hidden-main regression failed,
  exit 1. This used the old Dock implementation with the strengthened tests.
- After the fix: `CI=true sbt -batch scalafmtAll scalafmtCheckAll 'fx/testOnly scaladock.fx.AccessibilitySuite scaladock.fx.KeyboardSuite scaladock.fx.PresentationSuite'`;
  formatting and 37 tests passed, exit 0. The compiled source matches the
  source candidate above.
- Both local runs used macOS/aarch64 and unnamed JavaFX modules. Existing
  JavaFX native-access/module warnings and Scala Unsafe deprecation warnings
  are retained; these focused runs do not establish the separate named-module
  current-runtime lane.

## Independent review and hosted evidence

Reviewer `/root/s4_review` independently inspected the two-file source diff,
the failing hosted log, and the local before/after receipts. The reviewer
approved the source at `39fccfeaf920ec9e6522351d9ef12c5d1bd7f98b`, preserving
the native fallback and recovery conditions. The reviewer ran no builds or
tests and made no changes. After independently inspecting the full hosted log
and the GitHub API receipt, the reviewer accepted this narrow correction at the
same source SHA with no remaining blocker in scope. This is a parent-recorded
reviewer verdict; it grants no merge or publication authorization.

[Hosted run 39343080741](https://github.com/canardlapin/scaladock/actions/runs/39343080741)
completed successfully at the source candidate. Each of its four jobs passed
70 core and 104 FX tests, including both shown/hidden focus regressions:

| Job | Actual runtime | Result |
| --- | --- | --- |
| library-floor | Linux, JDK 22.0.2 / JavaFX 24.0.1+4 | 174 passed |
| build (ubuntu-latest) | Linux, JDK 25.0.4.1 / JavaFX 24.0.1+4 | 174 passed |
| build (macos-latest) | macOS/aarch64, JDK 25.0.4.1 / JavaFX 24.0.1+4 | 174 passed |
| current-runtime | Linux, JDK 25.0.4.1 / JavaFX 27+30, three named modules | 174 passed |

`ci-followup/hosted.json` retains job identities and verbatim test/runtime receipt
lines. `hosted-before.log.gz` and `hosted-after.log.gz` preserve the full downloaded
GitHub logs without content edits. `verification.json` hashes all these artifacts
and both changed source files. A subsequent evidence-only PR head is checked
separately; this receipt's source verdict remains bound to the SHA above.

The earlier S4-S6 manifests and receipts remain immutable historical evidence
for their recorded candidates. This follow-up does not extend the accepted
native `AXEnabled` limitation into a full VoiceOver qualification or accept the
Meridian B0 consumer.
