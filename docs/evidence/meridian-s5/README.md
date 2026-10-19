# S5 reduced-motion receipt

Candidate: `b928a4468eeaf774729c96aa02111b0d71e9bcd8`.
Issue: `bd-01M4Y1AD7SHRQSGD38RTJ86517`. Recorded 2026-10-19.

`DockMotion.System` follows the JavaFX 24 platform reduced-motion property;
`Full` and `Reduced` are live host overrides. Reduced motion settles indicator
glides/fades and tab slides to their existing targets and removes divider hover
delay. Ancestor/window hiding, detachment and disposal stop owned animation work.
Escape now cancels active divider resizing and restores its borrowed cursor.
No persisted-layout format or CSS selector changed. See
[the public motion contract](../../styling.md#motion-preferences).

Both named-module macOS/aarch64 lanes passed formatting and all **67 focused
tests**, exit 0:

- `scaladock-s5-floor-01.log`: JDK 22 / JavaFX 24.0.1+4.
- `scaladock-s5-current-01.log`: JDK 25.0.1 / JavaFX 27+30.

Exact commands, timestamps and exits are in adjacent `.meta.json` files.
`run_recorded_lanes.py` retains the runner as executed. Runtime locations in
that historical script are local to this machine. Full-suite reusable
qualification follows in S6.

`MOTION_TRACE` records come from live native Stages and real JavaFX pulses;
the tests never invoke AnimationTimer.handle manually. They retain intermediate
full-motion positions, final timer/slide shutdown, immediate reduced targets,
policy switches, and hidden/detached/disposed windows. A real tab drag switches
Full → Reduced → Full, continues moving and completes the intended drop with
the same pane node. A live divider drag switches policy and then cancels with
Escape, preserving layout and restoring the original cursor. Theme/density
changes and a floating owner closing are covered.

`scaladock-s5-check-01.log` retains the initial Escape/cursor failure (18 passed,
1 failed); the fix is exercised in both final lanes. `check-02` is an intermediate
38-test pass. No successful result replaces the retained adverse evidence.

The suite reads the actual OS preference and tests explicit overrides and
returning to System. It does not change the user's OS accessibility setting;
end-to-end OS setting notification delivery is not claimed. Full VoiceOver,
Windows/Linux native behavior and hosted CI are outside these local results.
The S4 macOS AXEnabled defect remains the user-accepted upstream limitation.
