# Apple local-search review repairs — 2026-09-08

Scope: the four presentation regressions identified in PR #102's adversarial review.
No feature promotion, transport change, core ranking change, or publication is included.

## Observed regression evidence

Tests are in `apple/DulcetKit/Tests/DulcetKitTests/AccountConnectionPresentationTests.swift`.
Run with `swift test --package-path apple/DulcetKit`, optionally adding `--filter <test-name>`.

The first three tests were added and executed together against unchanged production code at
`555031c`, before any repair. They failed with 15 assertion issues in total. Each repair was
then verified individually and committed separately; no existing assertion was weakened.

| Defect | Test | Observed red | Observed green and repair |
| --- | --- | --- | --- |
| One-character Retry | `retryOneCharacterLocalFailure` | Four issues: no second local query, retained error/failure, missing recovered row. | Pass: Retry reopens local search below the server threshold; a recovered adapter supplies the row and zero server requests occur. Commit `1a590d1`. |
| Navigation after local failure | `navigationRecoversLocalFailureAndCancelledServerSearch` | Six issues across cancellation before dispatch and after a request starts: no local reload, no resumed server request, error instead of loading. | Pass: local and server failures are stored separately; only a server failure blocks automatic initial-server recovery. Commit `4efb402`. |
| Stale local rows and ranks | `localRefreshReplacesRemovedRowsAndRanking` | Five issues across changed order, row removal, empty results, and repopulation. | Pass: a successful local query replaces its complete snapshot. Callers are local-only or have cleared rows before an initial server search, so this does not discard accumulated server pages. Commit `d1734bb`. |
| False server-only copy | `localSearchCopyKeysDescribeCache` | Seven missing local key/value assertions before any copy repair (after the three behavioral commits). | Pass: seven local-cache localization keys have exact expected values; existing server empty/error values remain. Included with the copy repair commit. |

`searchCopySelectsExactKeysForLocalAndServerStates` additionally checks actual selected keys for
local idle, empty, error, and populated states, then server empty/error states. It verifies that a
real server failure still requires explicit Retry after navigation. The existing server-only test
also asserts its original summary and idle keys. A negative control restored unconditional server
key selection while retaining the new test seam: the routing test failed with eight issues.
Restoring the fixed selector passed. This control is distinct from the pre-repair localization run;
it is not represented as an untouched-baseline execution.

## Preserved properties and limits

- **OBSERVED:** The existing local/server merge test still passes, including three merged rows with
  a server offset of two, opaque shared-ID replacement in place, and retained local rows on server
  failure. Per-kind server counters and request offset construction are unchanged by these repairs.
  Counts mean server DTO page items, not raw transport response entries.
- **OBSERVED by source comparison:** Merge identity remains the synthesized equality of provider
  instance and opaque raw ID. The late-server `appendOrReplace` implementation is unchanged.
- **OBSERVED by source comparison:** Search transport, normalization, and core ranking files are
  unchanged. The local refresh test observes preservation of the new adapter-supplied order; it
  does not independently re-prove Kotlin ranking.
- **OBSERVED:** The default debounce constant remains 250 ms; the existing threshold/cancellation/
  paging test passes. **OBSERVED by source comparison:** The actual debounce sleep and two-character
  server guards are unchanged. Real elapsed keystroke-to-request timing was not measured.
- **OBSERVED:** Full Swift package suite passes: 89 tests in three suites. These are package tests
  with controlled search adapters, not live database recovery or native UI interaction evidence.
- **ASSUMED / not exercised here:** End-to-end recovery and text layout on iPhone, iPad, TV, and the
  hosted Mac app. No native capture, simulator session, network conformance run, or core test run
  is claimed for this repair.

The pre-existing core within-response deduplication/has-more bug remains deliberately untouched.
So do provider mutation between server offsets, large-library responsiveness, and offline search.
`FEATURES.yml` is unchanged.
