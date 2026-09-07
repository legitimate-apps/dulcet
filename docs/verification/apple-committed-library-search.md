# Apple committed-library search (§18.1)

Evidence recorded 2026-09-07. This change wires the existing core local index into the shared
Apple production composition. It does not change search3 transport, normalization, ranking,
the 250 ms server debounce, or the two-character server threshold.

## Implementation boundary

OBSERVED — `DulcetCoreLibraryBrowser` implements `DulcetLocalSearching` using
`AppleLibrarySyncClient.searchCommitted`. That method opens the same database as library sync
and calls `LocalLibrarySearch.search`, whose existing transaction reads `committedGeneration`
and scopes every query to the provider instance. It returns existing search DTOs and catches
failures, including close failures, before returning across Objective-C. This local read is
synchronous, like the client's existing `readCommitted`; it introduces no asynchronous ABI.

OBSERVED — the presentation queries the local adapter for nonblank input, before scheduling
the existing server debounce. Initial server results now use the existing append-or-replace
helper, keyed by provider instance plus opaque raw ID. Server offsets count server page items,
not the merged list: local-only matches must not skip server pages. A separate initial-page
flag lets navigation resume a cancelled request even when local rows are already displayed.

OBSERVED — generated iOS framework header comparison against the pre-change library-sync
build (worktree revision `639e312de0514ff3ce5fb7826e9fb341dd65a424`) adds only this method to
`AppleLibrarySyncClient`; existing exports are unchanged:

```objc
- (DulcetCoreAppleSearchOutcome *)searchCommittedProviderInstanceId:(NSString *)providerInstanceId
    query:(NSString *)query
    __attribute__((swift_name("searchCommitted(providerInstanceId:query:)")));
```

## Platform UI evidence

All live application runs used only the existing disposable `http://127.0.0.1:4533`.
Each check loaded a committed library before entering Search. Tests type into the actual
platform field and assert a rendered result accessibility node. No test supplies local rows
or sets the search presentation state. The simulator setup hook supplies account details only.

| Platform and destination | OBSERVED trigger and rendered effect | Test execution |
| --- | --- | --- |
| macOS hosted production root | Native `NSApp.sendEvent` types `t`; 324 rows; first title `Twenty Nine Seconds`; server adapter rejects every invocation and none occurred | `testLocalCacheSearchFromFirstCharacterThroughHostedAppUI`: 1 test, 0 failures, 0.860 s on final hosted run |
| iOS, iPhone 17 Pro | Field `typeText("t")`; width 402; row 0 label `Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track` | `testSimulatorLocalCacheSearchFromFirstCharacter`: 1 test, 0 failures, 22.701 s |
| iPadOS, iPad Pro 13-inch (M4) | Field `typeText("t")`; width 1032; same row 0 label | Same selector on its own iPad destination: 1 test, 0 failures, 20.391 s |
| tvOS, Apple TV 4K (3rd generation) | Load library, terminate/relaunch, reach Search by remote, type `t`, dismiss keyboard with remote Done; same row 0 label | `testSimulatorLocalCacheSearchFromFirstCharacter`: 1 test, 0 failures, 22.316 s |

OBSERVED — the Mac hosted check also queries a different account and receives zero rows,
and calls the production adapter with an invalid database name and receives a closed failure
rather than a process-terminating Kotlin exception.

OBSERVED — the first TV attempt failed before entering a query: Menu followed by the existing
four-Up helper did not reach the section bar from Library. The final test explicitly relaunches
and reaches Search from Connection instead, exercising persisted local data. Library-to-bar
focus recovery remains outside this change; this test does not claim to verify it.

## Shared merging and server regression evidence

Command:

```sh
swift test --package-path apple/DulcetKit \
  --scratch-path /private/tmp/dulcet-local-search-swift
```

OBSERVED — 84 Swift Testing tests in 3 suites passed. After the final small presentation/test
updates, the two affected tests were rerun with:

```sh
swift test --package-path apple/DulcetKit \
  --scratch-path /private/tmp/dulcet-local-search-swift \
  --filter 'localSearchStartsImmediatelyAndServerReplacesWithoutMovingRows|serverSearchDebouncesCancelsAndPagesEachResultTypeIndependently'
```

OBSERVED — 2 Swift Testing tests passed, with trigger receipts:

```text
LOCAL SEARCH query=a rows=opaque:local-only,opaque:shared server-requests=0
LOCAL MERGE shared-id=replaced-in-place rows=3 server-offset=2
```

The first test checks immediate rows before yielding, zero server requests for one character,
server replacement of the shared opaque ID at the same position, appended server-only IDs,
retention of local-only matches, paging offset independent of local rows, navigation cancellation
recovery, local rows retained on server failure, and clearing on blank input. The second is the
existing server debounce/cancellation/per-type paging regression test.

OBSERVED — this verifies stable item order in the shared model. ASSUMED — native scroll offset
and focus stay anchored during late merged updates; these runs do not scroll during an in-flight
request. No platform scrolling claim is added. The local matcher/ranker remains the existing
core implementation; these platform tests do not re-prove its normalization or ranking tiers.

## Reproduction and gate result

Native tests used `xcodebuild test` with `-parallel-testing-enabled NO` and explicit
`-derivedDataPath /private/tmp/dulcet-local-search-<platform>`; the iPad used
`test-without-building` with the iPhone build products. Schemes/selectors:

```text
DulcetMac  -only-testing:DulcetMacTests/DulcetMacAccountConnectAppTest/testLocalCacheSearchFromFirstCharacterThroughHostedAppUI
DulcetiOS  -only-testing:DulcetiOSUITests/DulcetiOSUITests/testSimulatorLocalCacheSearchFromFirstCharacter
DulcetTV   -only-testing:DulcetTVUITests/DulcetTVUITests/testSimulatorLocalCacheSearchFromFirstCharacter
```

Build environments set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `/Volumes/AndroidSDK/sdk` and
`TZ=UTC`. Mac scheme settings supplied `DULCET_TEST_CONFORMANCE_BASE_URL` and
`DULCET_TEST_CONFORMANCE_DISPOSABLE`. Simulator runner inputs were in xcodebuild's environment,
using `TEST_RUNNER_DULCET_UI_TEST_SERVER_URL`, `TEST_RUNNER_DULCET_UI_TEST_USERNAME`, and
`TEST_RUNNER_DULCET_UI_TEST_PASSWORD`, with the published disposable fixture credentials.

OBSERVED — `./gradlew :core:linkDebugFrameworkMacosArm64` completed successfully in 53 s.
The Xcode runs also built the iOS and tvOS simulator frameworks and production Swift adapters.

Command:

```sh
GITHUB_BASE_REF=main tools/run-local-gates parity-gate
```

OBSERVED — against `origin/main` baseline `9f1817a32089`: **21 passed, 0 failed,
1 environment fault, 0 NOT covered**. The environment fault was
`parity-gate.yml:54`, `python3 tools/test-transcode-probe-timeout-diagnostic`.
The runner's final label was `INCOMPLETE OR FAILED`, not an unconditional pass; this is the
single expected environment exception described in the task brief.

OBSERVED — no CI steps, project configuration, generated Xcode project, or FEATURES evidence
rows changed. These new UI selectors were run locally; this document does not claim CI executes
them. No new server/local matching equivalence is asserted, and no existing evidence is retracted.
