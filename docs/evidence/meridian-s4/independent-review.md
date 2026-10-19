# Independent S4 implementation review

Reviewer: `/root/s4_review` (`lean_expert`), separate from the implementing
parent. Received 2026-10-19. The following is the reviewer's final verdict,
transcribed by the parent without changing its scope.

> **Implementation-only approval bound to `3cf16329dd4e304ad374aa10539c3153ac03e1d9`**, branch `codex/scaladock-s4-accessibility`.
>
> I independently reviewed the nine-file scope and confirmed the reviewed files match that commit. The identified implementation findings are addressed: floating titlebar routing and focus recovery, multiwindow focus recovery, invisible minimized close targets, and badge selector compatibility.
>
> Inspected evidence confirms:
>
> - JDK22/JavaFX24.0.1: formatting, 54 focused tests, and native fixture completed successfully; exit 0. Nine AX actions passed.
> - JDK25/JavaFX27: 54 focused tests passed, including actual titlebar focus, menu routing, F6, and Space-triggered dock-back. Nine AX actions passed.
>
> **S4 acceptance remains unresolved.** Native `AXEnabled=false` persists for eight tested targets on the floor lane and all nine on the current lane—including the enabled stock JavaFX Button control. This supports the diagnosed upstream bridge limitation; successful actions do not establish correct native enabled-state reporting.
>
> The implementation is approved within the reviewed scope. Closing S4 requires the user's explicit acceptance of that bounded limitation. This verdict does not grant S6 acceptance or qualify complete screen-reader workflows.

The reviewer inspected the source, diff and retained logs/AX JSON. They did not
independently build or execute the tests. This receipt is neither authorization
to land the candidate nor a publication record.
