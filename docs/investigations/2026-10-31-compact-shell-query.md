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

## Deterministic native control

**OBSERVED:** Xcode 27.0 (27A266a), iPhone 17 Pro simulator, iOS 26.5, serial testing.
The [temporary probe patch](compact-shell-query-probe.patch) adds an unrelated
accessible button to Search. Its label getter holds for 120 seconds only when
its actual stack contains `XCTFilteringTransformerIterator`, the path sampled in
the original failure. The probe does not branch on which query the test chooses.
The test stops after the Search/bar assertion to isolate that operation.

Both executions use the same compiled app and UI-test binaries. The only change
between them is `TEST_RUNNER_DULCET_PRIVATE_OLD_QUERY=1` for the old lookup:

| Lookup | Tests | Failures | Result |
| --- | ---: | ---: | --- |
| Old identifier-or-label `.firstMatch` | 1 | 1 | Exact “Timed out while evaluating UI query” failure; 147.928 s |
| Explicit identifier `.element` | 1 | 0 | Pass; 30.459 s |

The old execution interrupted the five-second existence waiter, then made three
remote queries with thirty-second deadlines. A sample during the hold has 93
main-thread samples through `_firstMatchingSnapshotForInput` and
`XCTFilteringTransformerIterator` into the injected label getter. This reproduces
the hosted failure's query path and error, using a held accessibility read rather
than attempting to recreate arbitrary host starvation.

The new execution's application log requests only element type, base type,
identifier, automation type and traits for this lookup, then captures the hierarchy.
It has zero label-getter stacks containing the lazy iterator and zero injected
holds. Its four unrelated label reads occur during the later diagnostic snapshot;
none enter the delayed path. The earlier eight-second version of the hold was
**not** a failing control: the old lookup tolerated it, so it is not counted as
fail-before evidence.

To reproduce, apply the patch to the fixed source, build for testing once, then
run the compact test with and without the `TEST_RUNNER_DULCET_PRIVATE_OLD_QUERY`
environment value. Use a leased simulator and `-parallel-testing-enabled NO` as
for other local UI runs. Reverse the patch before rebuilding the complete proof.
The patch is an investigation fixture, not compiled product or test code.

## Validation

`tools/typecheck-xcuitest-sources`: all five covered UI-test directories, 21 Swift
sources, pass. Native execution and hosted proof are recorded with the PR.
