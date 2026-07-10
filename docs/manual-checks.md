# Manual release checklist

Interactions that headless tests cannot fully exercise. Run `sbt demo/run` and verify each.

## Tabs & chrome
- [ ] Click each tab: content switches, instance counters do NOT increment
- [ ] Middle-click a closable tab closes it; the Explorer (non-closable) has no ✕ and survives middle-click
- [ ] ⋯ menu appears when a group has too many tabs; picking an entry activates it
- [ ] □ maximizes (fills window, dividers hidden); ❐ restores
- [ ] Focused group shows the accent border; clicking panes moves it

## Dividers
- [ ] Cursor changes over dividers; drag ghost moves without live relayout; release commits
- [ ] A divider cannot crush a pane below its minimum
- [ ] Sizes survive save → load

## Drag & drop (all with instance counters unmoved)
- [ ] Drag a tab over another group: left/right/top/bottom quadrants highlight correctly
- [ ] Drop on each edge splits 50/50; drop on same-axis neighbours merges (no nested splits)
- [ ] Drop on a header inserts at the pointed gap; reorder within one header works
- [ ] Window edges (50px bands) dock against the whole layout
- [ ] ESC cancels a drag; release far outside every window returns the pane home
- [ ] Palette drag-in mints a viewer; missing everything mints nothing

## Popouts
- [ ] ↗ opens a floating window in place over the group
- [ ] Dragging tabs between the main window and popouts works both ways
- [ ] Closing a popout docks content back where it came from (also for a 2-cell split)
- [ ] Move/resize a popout, save, load: geometry restored
- [ ] Maximize inside a popout affects only that window

## Persistence & theming
- [ ] Save → modify layout → Load: full restoration incl. active tabs and popouts
- [ ] Theme toggle restyles every open window including popouts
- [ ] A layout saved with a pane type that is no longer registered loads as a placeholder and
      re-saves without losing the original state
