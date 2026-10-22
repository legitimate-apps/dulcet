# Server identity and saved accounts — 2026-10-22

The implementation distinguishes a saved provider namespace from server lineage: the former is a
local random account ID, the latter is authenticated product type and, for an advertised Dulcet
server, its origin's discovery instance ID. Software versions do not enter lineage. See spec §28
for trust, migration and plain OpenSubsonic limits.

## Android removal and queued retirement

OBSERVED in core-ci run `39818140263`, TV job `119961936427`: CONF-52 had already
advanced local playback to 1,520 ms from bytes identical to the explicit download, while the
relay refused connections and the server received no playback request. Queued controller close
then failed in `refreshProtection` because account removal revoked its media binding between
ownership checks. The uncaught exception also contaminated the following UI tests.

OBSERVED fail-before: `AndroidDownloadControllerTest/closingAfterBindingRevocationReleasesItsStoreWithoutUncaughtMaintenanceFailure`
reproduces the same real policy stack and fails one test. The real SQL-driver checkpoint permits
safe closure without requiring the retired ownership read. The two `AndroidAccountDataTest`
controls, `removalWaitsForQueuedDownloadDatabaseWorkBeforeRevokingItsBinding` and
`sweepWaitsForQueuedDownloadDatabaseWorkBeforeRevokingItsBinding`, enter the real deletion APIs
while the shared download executor is positively held. Both fail because real media is already
deleted before that executor is released. They also require the rows and binding to remain until
release, followed by complete removal.

Account removal and the sweep now share the controllers' database executor. Protection refresh
excludes the closed controller's contribution while updating live peers; nested `finally` blocks
release offline writers and the database store. No revoked binding is reacquired and no exception
is caught to hide the race. These host regressions establish the ordering boundary; final device
CONF-52 remains a separate observation.

OBSERVED after the fix: the three unchanged regressions pass with zero failures, errors or skips.
The close control executes once in 0.406 seconds; removal and sweep execute twice in 11.903 seconds
in total, including first-use setup. Both executor-held controls and the post-release deletion
assertions pass. Broader class results and Linux soak evidence are recorded separately.
The complete adjacent suites also pass: 29 `AndroidDownloadControllerTest` cases and 19
`AndroidAccountDataTest` cases, 48 executions with zero failures, errors or skips.

## Playlist capability discovery and account retirement

OBSERVED fail-before on the merged playlist-context and server-identity implementation: the JVM
sender, real Android controller and native Apple queue facade each send the old song and playlist
after a held extension response returns despite sender retirement or known identity rejection.
The isolated JVM, Android-host and macosArm64 regressions each execute one test and fail its
no-send assertion. A fourth native Swift adapter regression executes one test and fails because
identity rejection does not retire network delivery; its saved-queue assertions already pass.

The sender now checks logical retirement and account authorization around suspended discovery
and before sending. Apple rejection installs persistence-only delivery. The native queue control
also reconnects a fresh sender and verifies that it delivers the original song and playlist once.
These boundaries preserve the durable event without allowing a retired sender to disclose its IDs.
The sender, Android controller and emitted Swift aggregate are named in the
`account.server_identity` cells. The additional native facade test is
`ApplePlaybackQueueFacadeTest/offlineConfigurationRetiresASenderSuspendedInPlaylistDiscoveryWithoutDeletingThePlay`.
These observations do not establish a rendered server-swap tour on each platform.

OBSERVED after the guards on the merged source: 1,199 JVM and 1,444 core Android-host tests
pass, together with phone DEV/PROD 239 each and TV 94. Native macosArm64 executes 1,285 tests
across 95 suites with zero failures, errors or skips. This includes existing positive and negative
capability-reconnect controls and the unchanged held-discovery regressions. Fresh Mac, iOS and
tvOS frameworks and iOS common-test compilation pass; build success is separate from the test
observations above.

OBSERVED on the merged source: all 14 native Swift download/playback adapter controls pass
(zero failures, 5.960 seconds), including the rejection-to-persistence-only regression. The
standalone playback-restoration compiler reports zero failures. Mac, iOS, iOS download integration
and tvOS targets all build for testing against the fresh frameworks.

The preceding full Apple run's tvOS pending-add failure occurred before membership actions:
its accessibility dump contains a counted replacement dialog for a saved direct-server account
when the test launches its fault-proxy endpoint. Six debug-credential launch sites now use
per-test/per-endpoint fixture UUIDs; three saved-only relaunches retain that UUID without injected
credentials. The intentional unseeded launch and real-Keychain proofs remain unchanged. This
fixture correction compiles locally; runtime confirmation belongs to the final full Apple run.

## Shared core

- OBSERVED on parent revision `271dd44e`: four JVM `ServerIdentityNegotiationTest` cases fail.
  Successful negotiation has no lineage to distinguish replacements or preserve over upgrades.
- OBSERVED in the new implementation: the negotiation tests connect to a disposable loopback
  server, change Navidrome → Dulcet → Navidrome at the same address, change only software version,
  reconnect without changing lineage, rotate a Dulcet instance ID, and reject redirected discovery
  without sending credentials or following the redirect.
- `SavedServerIdentityTest` checks canonical-principal selection, both replacement directions,
  cache-independent accepted identity, the read-before-write reconnect barrier, and the independent
  scrobble retry barrier. Detection preserves old identity and pending writes until account consent.
- OBSERVED: `PlaybackQueueAccountSwitchTest` (4 cases), plus existing queue/store regression
  controls, execute 57 JVM tests with zero failures/errors/skips. A → B → A restores order, current
  entry, repeat/shuffle state and separate resume positions even for identical opaque song IDs;
  stale outgoing player/preload/AutoPlay events cannot change B.
- `SavedServerMediaIsolationTest` checks that selecting B preserves A's partial and completed
  media while neither account can resolve the other's identical raw ID, and that retiring an old
  account deletes its independent lineage and preference rows while retaining the other account.

- OBSERVED: the focused core acceptance set executes 78 JVM cases with zero failures/errors/skips
  (identity negotiation, reader/outbox barriers, media binding, download policy and isolation).
  Thirteen Android-host cases also pass: queue selection, shared identity/media isolation and
  the independent contact-probe race controls.

## Android

OBSERVED fail-before at the production connect-and-save boundary: the baseline implementation with
an API-only optional-parameter shim fails three new acceptance cases. Both product-swap directions
skip the seven-download consent, and saved `alice` / typed `Alice` / confirmed `alice` rotates its
provider ID. The encrypted store, connect operation and new tests are otherwise the production path.

The phone and TV tests exercise Compose on the Android host runtime, with Keystore cryptography
replaced at its existing test seam. These are host app observations, not emulator or physical-device
execution. The local focused app run passes 17 phone cases and eight TV cases with no failures:
account conformance, canonical persistence, sign-out and the new switcher/consent flows. CI and
soak results are recorded in the PR, tied to their run IDs and revisions.

## Apple

OBSERVED: the exact #347 canonical case already passes on the parent Apple implementation; it is
not claimed as fail-before. New shared Kit acceptance exercises both product directions with a
seven-download offer, cancellation before mutation, retirement before removal, canonical retention,
unchanged lineage across upgrades/restarts, and A → B → A provider selection.

OBSERVED on a Mac host: the shipping `DulcetRootView` renders the Account entry; native accessibility
activation opens its saved-server list and activates server B. Native facades and persistent data
have separate integration tests; a fixture datasource alone does not establish live-server I/O.

OBSERVED native adapter tests also pass locally: the real AVPlayer current/preload rebind proof
keeps installed playback and files through Cancel, then observes retirement before the purge
callback after confirmation. The storage roundtrip prints
`DULCET SAVED SERVER STORAGE PASS selection=A->B->A media=distinct-bytes queue=restored library=restored deleted=0`.
It checks provider-scoped SQLite seen-library rows, persistent queue state and different local
media bytes. Both proofs are invoked by the existing download aggregate selected by Apple CI on
Mac, iPhone and iPad. tvOS has the shared account/chooser proof and shared-core store tests; this
native AVPlayer adapter aggregate is not claimed there.

OBSERVED on the iPhone simulator: the hostless shared root/action fixture passes with
`DULCET SAVED SERVER ACTIONS PASS selected=server-b kept=2 native-ui=unproven`.
The app and shared test target compile for iPhone. This is action wiring with injected accounts;
the hostless target has no application scene, so rendered UIKit tab/remote activation remains an
explicit evidence gap on iPhone, iPad and tvOS. The Mac accessibility proof is separate.

## CI

The PR records the final-head Linux soak lines, required checks and dispatched Apple run IDs.
Platform cells remain partial where observations stop at the host UI, injected account transition
or durable core boundary. These tests do not claim a signed-device or multiple-live-server UI tour.

## Background work and learned legacy identity

OBSERVED fail-before: two Android download regressions allowed a scheduled transfer or an in-flight
payload to publish after a known replacement. The corrected worker validates before old-ID requests
and rejects late promotion while preserving partial/local media. The focused download and probe
suite passes; transient HTTP 503 identity failure remains retryable and completes after recovery.
A further real authenticated concurrent-probe regression failed before the fix: a newer same-server
reader probe stranded its superseded download task. The corrected task has a finite wake boundary
and its successor completes the exact bytes; 25 download tests and three probe races pass.
Two additional Android account cases failed to offer consent when the accepted database lineage
was stronger than legacy credentials; the fixed policy reads the database first.

OBSERVED fail-before: two Android playback cases dropped a Play selection during identity probing.
The fix waits for the already chosen connection's probe, preserves latest-selection ordering, and
leaves a rejected probe closed. Separate probe tests cover overlapping probes, cancellation and
exceptions so no waiter remains held by a completed validation.

OBSERVED Apple shared Kit fail-before: detected replacement invoked no download quarantine. The
corrected presentation quarantines before negotiation and Cancel keeps media. New native Kotlin
facade tests reject late promotion, retain existing bytes, reject stale probe success and prevent
saved metadata from downgrading learned lineage. Native adapter execution and exact-head CI results
are recorded in the PR; shared presentation tests do not establish a rendered live-server tour.

OBSERVED fail-before: a real authenticated outbox delivery succeeded after Connect while its
independent probe stranded the restored queue. The regression uses the production shared contact
state and a receiving HTTP socket. Retaining established contact during an ordinary in-flight probe
restores the selected track paused; explicit reconnect and failed, throwing or mismatched probes
still close contact. The existing TV saved-launch oracle exercises the same production path.

OBSERVED fail-before: three production Android stream-cache cases incorrectly allowed beginning a
write, appending in-flight bytes, or promoting an already buffered write after known identity
rejection. Guards at each boundary now pass all three while retaining the exact existing completed
download and cache bytes. The focused download, probe-race, stream-cache and offline-ownership
gate passes 45 tests with zero failures, errors or skips; phone and TV main compilation also passes.
The restored-queue gate passes 67 playback and four identity-race tests; all four TV saved-launch
cases pass. These host observations exercise production adapters, not a live native UI swap tour.


## Playback replies after rejection

OBSERVED fail-before: seven Android production-controller cases failed: late metadata could resolve
and install a selection, a late current plan could install, next-track metadata and resolution could
prepare preload, an installed resource could issue another actual HTTP range request, a late metadata
reply could rename the retained queue, and late AutoPlay could append replacement-server IDs. The
first five fail together; the latter two also fail before their mutation guards. The corrected
controller rechecks contact after each asynchronous boundary and before writes. Its real HTTP
resource refuses a new range after sticky identity rejection even without an optional contact gate.
The focused run executes 74 playback, four identity-race, one credential-refresh and ten listening
session tests: 89 passing, zero failures, errors or skips. These controls preserve ordinary playback,
preload, credential refresh, AutoPlay and local playback; this is Android host evidence.

OBSERVED Apple native fail-before: held current and preload resolves each installed a plan after
rejection, and an installed resource sent a second range to a real HTTP receiver. The three native
tests fail with four assertions before the fix, then pass with three adapter regression controls
(six tests, zero failures, 3.339 seconds). Sticky controller rejection cancels pending remote work,
revokes its wire source and fences authorizers after asynchronous preparation; local and already
installed playback remain available until consent. Ordinary server availability cannot lift that
rejection. These tests join the existing download aggregate on Mac, iPhone and iPad; they are not
a tvOS native adapter or six-surface live swap tour claim.

The core CI evidence upload now includes its already executed JVM reports alongside Android reports.
The previous aggregate failed because JVM protocol evidence was absent from that artifact despite
all four execution jobs passing. Replaying the actual downloaded artifact fails without JVM reports
and passes with only the missing JVM reports added; no verifier or evidence requirement is weakened.


## Secured delivery versus late download preparation

OBSERVED in full Apple CI: the original process-replacement handoff oracle found two stream requests
for the same download. The replacement first reconciled and promoted the expected exact bytes, then
started and promoted the same requested task again. Async identity validation allowed receipt
promotion and queue preparation to overlap; preparation did not reread the completed row before launch.

OBSERVED fail-before in a deterministic native regression: hold a real core preparation result,
complete the same SQLite download row through a second real facade with exact MP3 bytes, then
release the result. An extra stream request reaches the actual loopback receiver (one versus zero).
The first correction waits for in-flight secured validations and rereads committed state
before launch so Complete or Stale rows cannot be fetched again. The original process-handoff
oracle remains unchanged and must pass in the final full Apple workflow. This native assertion is
called by the existing Mac, iPhone and iPad download aggregates; no tvOS download-owner claim is made.

That focused native gate passed eight tests with zero failures (2.113 seconds), including the
new race, an ordinary successor fetching and promoting after the suppressed duplicate, the three
late-playback guards, download quarantine, persistent A → B → A isolation and preference controls.
The five extracted Swift inbox controls also pass. The harness adds only the model helper needed by
the newly called scheduling helper; no CI workflow, tools/ci file or Kotlin Apple facade is changed.

OBSERVED recurrence in full Apple run `39692066008`: the unchanged Mac killed-process oracle again
counted two streams instead of one (68.159 seconds). Both restored files were already secured before
reconciliation, but a native transfer started while receipt validation was pending, 93 milliseconds
before the original receipt promoted. The completed-row reread covered a promotion that had already
committed; it could not cover this earlier interval. Opening remote eligibility before the asynchronous
task-enumeration callback scanned the inbox also left a scheduling window.

The second correction synchronously registers secured inbox validators when remote access opens,
before yielding to task enumeration. Already prepared native plans wait while any receipt validator
remains. The final validator promotes first, then drains those plans through the existing durable
Complete/Stale check. Generation changes and sticky identity rejection discard deferred plans.

OBSERVED locally on the native Mac test target: two deterministic real-core regressions fail against
the previous scheduler with seven assertions (3.428 seconds), then pass unchanged with zero failures
(1.934 seconds). They use real SQLite rows, the durable secured receipt format with exact MP3 bytes,
authenticated loopback identity validation and actual HTTP stream counts. One holds remote task
enumeration after restoration; the other delivers an already prepared plan during held receipt
validation. Neither may fetch the target before or after promotion. Each must still fetch and promote
an ordinary successor once. A third control holds two receipt validations, completes the unrelated
receipt first, and verifies that the target remains blocked until the final receipt promotes.

The bounded native suite passes 11 tests with zero failures (5.457 seconds), including the previous
eight controls and these three cases. All five extracted Swift inbox controls pass. The shared proof
is called by the existing Mac, iPhone and iPad download aggregates. The original Mac killed-process
oracle remains unchanged and still requires a passing final full Apple run; these deterministic
tests do not replace it. No Kotlin, shared live fixture, Apple CI workflow or tools/ci file changed.

OBSERVED in full Apple run `39705275864`: the original Mac killed-process handoff oracle now passes
unchanged (67.885 seconds) with exactly one target stream. The following open-reader offline proof
then exposes a separate completion boundary: exact local bytes and playback progress pass, but one
unexpected HTTP request follows the switch to device-only mode. A deterministic native regression
identifies that request as `/rest/ping.view`, issued while promoting the already-started native
download. It fails before the correction with one assertion (1.131 seconds); the download itself
still completes with the exact 24,334 bytes and only its original stream.

The correction records process-local task authorization only when starting a native transfer after
accepted identity. Completion consumes that authorization and can promote locally for the same
account generation and owned download without fresh contact. Account retirement and known identity
rejection discard it. Restored task enumeration and durable inbox metadata never manufacture that
authorization; restored receipts continue to perform identity negotiation and use the receipt barrier.

OBSERVED locally: 13 native controls pass with zero failures (5.943 seconds). The live completion
control leaves transport open after promotion and verifies zero additional HTTP before closing it
and checking exact playable bytes. A held actual native delivery followed by identity rejection
cannot promote or initiate contact. Restored receipt controls explicitly observe identity pings.
The previous receipt-ordering, two-validator, ordinary-successor, playback and account controls remain
green. Six account-removal controls, five inbox controls, fifteen handoff diagnostics and static Mac
contracts also pass. The original full-workflow offline-reader oracle remains unchanged and still
requires a passing final full run.

The native HTTP fixture is shared by the Mac and iOS download integration sources; the iOS download
test target includes the existing MP3 resource used by these controls. This small `project.yml` and
generated-project seam supplies test fixtures only. It changes no product target or Apple CI workflow.

OBSERVED in the full runs on `dd7afb0d` (iPhone) and `61268b1a` (iPad): the playlist-drag
helper exhausted a combined ten-second readiness wait after closing the player. Captured
accessibility trees show the dismissed player and visible mini-player; the iPad trace spends
9.25 seconds on the missing Close-element query alone. The helper now waits for Close absence
and then bar hittability in separate bounded phases, up to ten seconds each. Both required states,
the drag action and queue assertions remain. The complete iOS UI-test target builds locally with
this change. Runtime confirmation remains a final-full-run requirement; this is test readiness
evidence, not a production dismissal-performance claim.

OBSERVED in full Apple run `39732043039` on merged source `8078f5d6`: platform,
iPhone and core pass; iPad executes 20 XCTest cases with 19 passes and one account-states
failure. That failure classifies an initial window frame `{{inf, inf}, {0, 0}}` as compact;
the subsequent diagnostic reads report the regular `1032 × 1376` frame. The test previously
resolved the live frame separately for classification, logging and failure. It now waits within
the same 15-second readiness budget for finite, nonzero bounds and uses one captured frame for
all three operations. Any valid size is accepted immediately; the existing 700-point compact
threshold still rejects an incorrect layout. Final-head full Apple execution must establish
runtime confirmation. No production, shared Android fixture, timeout or workflow changed.

OBSERVED 2026-10-22 in full Apple run `39751224292` on `07b45488`, attempts 1
and 2: each iPhone attempt executes 19 XCTest cases with 18 passes and one failure in
`testATrackDraggedOutOfItsContextMenuJoinsUpNextOnIPhone`. Attempt 1 leaves the track's
context menu open after both synthesized drags and adds no queue entry. Its touch coordinates
start inside the enabled track row and end inside the enabled mini-player; the missed lift's
cause remains unresolved. Attempt 2 fails the helper's live bar-hittability wait after Close;
its hierarchy and recording show the dismissed player and visible bar. The Close-existence
query takes 9.30 seconds, followed by another slow live bar query. Unrelated animation
quiescence contributing to this second failure is ASSUMED, not established by these artifacts.

The Up Next helper now uses the existing interaction scope around its complete read and
close sequence on the current application process. The prior body is retained unchanged:
Close absence and actual bar hittability each retain their ten-second bounds, and the drag
must still add exactly one track while preserving the original paused canary. OBSERVED
locally on an iPhone 17 Pro simulator with iOS 26.5 and a disposable Navidrome 0.63.2 fixture:
the unchanged baseline passes one test in 139.078 seconds; the scoped helper passes one test
in 126.322 seconds. Both use one drag, leave no menu open and add exactly one track to empty
Up Next. Both complete iOS UI-test builds pass. These controls establish preserved behavior,
not fail-before evidence or a demonstrated cause of either intermittent CI failure. Final-head
full Apple execution remains required; no product code, gesture, oracle, timeout or workflow
changes here.

OBSERVED in the iPad leg of full Apple run `39779874095` on `a7f5aac4`: 17 XCTest
executions produce 16 passes and one failure. The album-tile drag case fails before navigation
or any drag, at the initial global Sign Out query in `awaitLiveAccountConnection`
(116.208 seconds). The recording shows Connection still connecting at 18 seconds and Listen
Now online at 50 seconds. The query spans that automatic navigation, retries at 52.37 and
83.40 seconds, then times out evaluating matching snapshots. Initial Library traversal and an
animation-quiescence cause are not established by this recording.

The helper removes only that preliminary five-second global query. Its existing bounded loop
opens Connection before querying Sign Out, matching the account-state proof's route. The
35-second loop, ten-second connected-state check, two retries limited to a server-request timeout,
actual Sign Out oracle and ordinary event waits remain unchanged. OBSERVED locally on an iPad
Pro 13-inch (M5) simulator with iOS 26.5 and disposable Navidrome 0.63.2: the exact album-tile case
passes one test with zero failures in 76.476 seconds. It taps Connection at 7.59 seconds before
querying Sign Out at 10.20 seconds, then one drag adds all three expected album tracks to empty
Up Next with no menu left open. The complete iOS UI-test target builds. This establishes the
corrected query ordering and preserved queue assertions locally; final-head full CI remains required.

OBSERVED in the core leg of full Apple run `39799345915` on `70caed4d`: 39 XCTest
executions produce 38 passes and one failure. The iPad CONF-86 independent-home-rows case
fails in 1.841 seconds: favourites has published its server failure, but the last polled proxy
ledger still marks `getStarred2` unanswered. The test's wait checked that row failure and the
held most-played request's presence, then asserted a 500 in that earlier ledger snapshot. The
proxy sends the 500 before recording its acknowledgement, and a control poll can also capture
the ledger before that write; the row and ledger therefore need an explicit observation barrier.

The existing 30-second wait now also requires an answered `getStarred2` with status 500.
Its original assertions remain: recently added and recently played are live, favourites is
failed, most played has asked but remains unanswered and loading, and releasing that held
request makes only most played live while favourites stays failed. No production or proxy
behavior changes. OBSERVED locally on an iPad Pro 13-inch (M5) simulator with iOS 26.5 and
a disposable Navidrome 0.63.2 plus its fault proxy: the exact native AppHost case passes one
test with zero failures in 0.944 seconds, with the expected independent-row publications.
The complete iOS AppHost/UI-test target builds. The CI failure establishes the stale-snapshot
interleaving; this local pass checks the corrected barrier and retained assertions. Final-head
full CI remains required.

### Native quality-menu targeting (2026-10-22)

OBSERVED in the phone leg of full Apple run `39799345915` on `70caed4d`: 32 XCTest
executions, 31 passes and one failure. The streaming-quality case reached the first
restore-to-Original menu after its capped-playback assertions, then failed in XCTest's
normal tap with an infinite-origin, zero-size activation target. Its recording shows the
Original option visibly inside the open native menu; all three failure accessibility
snapshots show that sole Original button at the same finite 250-by-42-point bounds. Both
background picker rows still show their capped choices. An offscreen picker, a duplicate
Original match, and an option merely awaiting layout were not established by this evidence;
the discrepancy between event targeting and the snapshots remains unexplained.

The test helper now queries the native menu collection's exact-label button, excludes both
background picker identifiers, and requires existence, finite positive bounds and live
hittability within its existing single five-second option budget. It still performs the
ordinary tap and reads the chosen picker value back. No production behavior, deadline or
stream assertion changes. OBSERVED locally on an iPhone 17 Pro simulator with iOS 26.5
and disposable Navidrome 0.63.2: the exact streaming-quality case passes one test with zero
failures in 143.360 seconds. It selects cellular 128 kbps and Wi-Fi 96 kbps, proves capped
playback against the server's 96 kbps transcoded stream from the higher-bitrate source,
and restores both choices to Original. The complete iOS UI-test target builds. This checks
the stronger target preconditions and retained end-to-end behavior; it does not establish
the underlying activation-point cause. Final-head full CI remains required.
