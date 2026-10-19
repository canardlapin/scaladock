# Independent combined provider review

Reviewer: `/root/s4_review` (`lean_expert`), separate from the implementing
parent. Received 2026-10-19. Parent-recorded transcription of the
reviewer-authored verdict; the read-only reviewer did not mutate Mote.

> **S6 accepted for local provider qualification at `87f900e9bdb1cce08872b363d4fe20a04c458b29`.** No remaining implementation blocker found within the reviewed scope and the user-accepted S4 limitation.
>
> I independently inspected source, retained logs, runtime manifests, API comparisons, and native action/state evidence. **I ran no builds or tests.**
>
> - Both coherent named-module lanes passed formatting, compilation, and **173 tests each**: JDK22/JavaFX24.0.1 and JDK25/JavaFX27. Current also compiled the demo and exercised the actual JavaFX27 titlebar.
> - tools/qualify_runtime.py and FxFixture.scala establish the executing JDK and all three JavaFX modules. Independently checked jar hashes and the source manifest: all 85 recorded files match the candidate; no untracked source inputs.
> - Combined API/selector review found no unintended removal. Existing Dock signatures remain, reviewed library classes retain major version 66, and persistence/schema remain unchanged. Enum additions require updated exhaustive source matches; the API receipt correctly limits its claim to JVM shape.
> - Final native probes passed **nine actions with expected state transitions on each lane**, with normal fixture/harness exits. S5 evidence also covers real drag continuity across motion-policy changes and animation cleanup on hiding/disposal.
>
> **Retained limitation:** macOS AXEnabled=false remains for eight floor and nine current probe controls, including the stock JavaFX Button, despite correct JavaFX disabled state and successful AX actions. This is the explicitly accepted upstream limitation; it is not resolved or converted into full screen-reader qualification.
>
> The acceptance criterion is satisfied for this frozen source and these local runtimes. Hosted CI, other platforms, external consumer acceptance B0, and blanket binary/source compatibility remain outside this verdict. This review grants no landing or publication authorization.
