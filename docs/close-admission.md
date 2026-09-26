# Asynchronous close admission

Override `PaneView.prepareClose(): Future[Boolean]` to save pending work or ask
for a close decision before a pane is removed. It is called on JavaFX. Default
panes return an already-completed true future and retain immediate close behavior.
A false decision, thrown nonfatal exception or failed future keeps panes open.
Override `closeCancelled()` to restore interaction after a veto or invalidated
transaction. Keep editing frozen after successful preparation until either
`dispose()` or `closeCancelled()`; another pane in the transaction may still be
waiting for its decision.

`Dock.requestClose(id)` returns the decision future. It refuses a non-closable
pane with an already-completed false; `update(edit.close(_, id))` still forces
that removal. `Dock.close`, pane-context close, tab close and group close use the
same mechanism. A group prepares all closable panes and removes none unless every
decision permits it. Exact duplicate pending requests share their future. An
overlapping different request is refused without disturbing the existing
transaction.

Hosts can await `requestCloseAll()` before closing their main window. This covers
all current panes, including non-closable chrome. A pane added during that
transaction invalidates it, preserving all panes. Captured live pane identities
must still match at publication. Closing a floating window still docks it back;
it does not destroy panes.

`update(edit...)`, layout replacement and `dispose()` remain explicit force
operations for application-controlled teardown. They do not ask permission.
Hosts must intercept their own window close request and use admitted close-all
before forced teardown. Do not block the JavaFX thread waiting on a future.

The native `CloseAdmissionSuite` covers immediate defaults, delayed decisions,
duplicate requests, veto, failed futures, retry, exactly-once disposal,
all-or-none host closure, arrival of a new pane, and tab/group/context routing.
