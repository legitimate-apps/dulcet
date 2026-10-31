# Compact-shell player-bar query under runner pressure

## Failed execution

**OBSERVED:** [apple-ci run 41192958497, attempt 1](https://github.com/legitimate-apps/dulcet/actions/runs/41192958497/attempts/1), source `9042809c`, failed
`testCompactShellKeepsTheAlbumAndOpensNowPlayingFromTheBar` at line 326.
The test had tapped Search and found its search field. The failure happened inside
`bar.waitForExistence(timeout: 5)`, before the hittability helper was called.
The player-button hittability policy is therefore not implicated by this failure.

The bar query was `app.buttons["dulcet.mini-player.open"].firstMatch`.
The xcresult's query attachment records a Button descendant search, the predicate
`"dulcet.mini-player.open" IN identifiers`, and a First Match transformer.
Its incomplete on-demand snapshot has the expected identifier and track label;
its infinite/zero frame is not proof that the actual bar was off screen. The final
recording frames show Search results and the player bar, with no covering dialog.

The five-second existence waiter interrupted its first query. Xcode subsequently
made three remote UI queries that each reached their thirty-second deadline.
The xcresult's spindump at 12:04:34–39 UTC contains 472 samples over 4.98 seconds.
Every main-thread sample is inside injected `XCTAutomationSupport` query evaluation:
`matchingSnapshotsInSnapshotTree`, `_firstMatchingSnapshotForInput`,
`XCTFilteringTransformerIterator`, `XCTElementSnapshotDescendantsEnumerator`, and
live accessibility attribute reads (`children`, `elementType`, `identifiers`,
`label`, `value`, `placeholderValue`). Main-thread CPU was only 0.071 seconds.
No Dulcet tab-switch business operation appears in this sampled path.

The host-pressure artifact has eleven samples during the Search/query failure
window, with load1 312.075–512.625 on the three-CPU runner. The app received 2.07
seconds of CPU over 190.8 seconds in that window. This is an accessibility query
running on the app's main thread under severe runner pressure; the stack does
not show a Dulcet operation blocking the tab switch. A rerun passing alone would
not establish that distinction.

## Change and retained assertions

Resolve the unique bar using an explicit `identifier ==` predicate and `.element`.
This removes the lazy First Match transformer and the identifier-or-label fallback
from this lookup. `.element` also requires an unambiguous result. The test still
requires the bar to be absent before playback, name the played track, remain
visible and hittable across the tab change, open the full player, survive closing
it, and preserve the Library navigation stack. Timeouts, gestures and the
player-button hittability helper are unchanged.

This is a targeted change to the query that failed. It does not claim that any
XCUITest hierarchy operation can be guaranteed to finish on an arbitrarily
starved host, or that all first-match queries are slower than full matching.

## Validation

`tools/typecheck-xcuitest-sources`: all five covered UI-test directories, 21 Swift
sources, pass. Native execution and hosted proof are recorded with the PR.
