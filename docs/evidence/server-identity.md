# Server identity and saved accounts — 2026-10-23

The implementation distinguishes a saved provider namespace from server lineage: the former is a
local random account ID, the latter is authenticated product type and, for an advertised Dulcet
server, its origin's discovery instance ID. Software versions do not enter lineage. See spec §28
for trust, migration and plain OpenSubsonic limits.

## Android saved-account reconnect recovery (#355, 2026-10-23)

OBSERVED baseline: production code at `26655b9b`, with the tests-only commit `c18d6207`,
reproduces recovery failure through `LibrarySession.connectSavedAccount`, the handler installed on
both Android Reconnect controls. `SavedIdentityReconnectTest` and `TvSavedIdentityReconnectTest`
each execute two cases: the original-server recovery case fails with `Failed` instead of `Online`,
and the replacement-still-present case passes. Both XML reports contain two cases, one failure,
zero errors and zero skips. The initial saved server authenticates before each case. The server
then changes product at the same loopback URL, the real reader rejects it, and a favourite remains
in the durable outbox while the platform still reports a network. The recovery failure occurs
after the fixture returns the saved product and the test calls the Reconnect handler again.

The negative replacement controls already pass on main. They are regression controls, not claimed
as red-before evidence. These host tests call the actual shared shell action and use production
HTTP negotiation, reader and SQLite outbox. They do not drive rendered Compose controls or claim
an emulator proof. Only the loopback server is a fixture; no LIVE fixture changes are involved.

OBSERVED Linux baseline `39956617865`: `AndroidServerIdentityRaceTest` executes six cases,
with four failures, zero errors and zero skips. Both newly added recovery controls fail before
recovery can complete: `reconnectRecoveryCannotAdoptAReadStartedBeforeRejection` receives
`ReaderConnectionOutcome.Failed` instead of `Read`, and
`olderNegotiationsCannotRevokeOrReopenTheRecoveredAuthorization` receives
`ServerIdentityChanged` instead of null. Two existing cases now require the specified fresh
matching negotiation to succeed; their earlier assertions still reject older replies, but their
new recovery assertions fail on main. The other two original controls pass.

The fix clears rejection only in the existing current-generation successful-validation branch.
Every production caller supplies `ServerIdentityGate.validate`, which authenticates and compares
the durable saved lineage. Clearing rejection advances the authorization epoch. The epoch retired
on rejection is never restored; a held reader request still fails its captured generation/token
check after transport. Older matching and mismatching identity negotiations return `Cancelled`
after recovery without changing verified contact or its token. An ordinary matching background
probe does not advance the token. The held-reader and held-negotiation cases exercise those
boundaries with the actual Android authorization object, production shared reader, and controlled
transport. The negative replacement integration controls retain the old pending favourite and
send no `star` while a new negotiation continues to reject the replacement.

### Rejection notification ordering review

OBSERVED baseline `d0a3b58b`, with only a narrow pre-notification pause seam and the new tests
added in `ad68b219`: Linux run `39962068663` executes four cases, two failures, zero errors and
zero skips. Both `aDelayedRejectionCannotCloseANewerReconnectOrStrandItsChanges` and
`aDelayedRejectionCannotCloseANewerConsentOrStrandItsChanges` fail with
"A stale rejection notification cannot close the reopened account". P1 has committed rejection,
a favourite is durable and unsent, and the newer reconnect has reopened contact and flushed that
favourite before P1's old notification is delivered. The opposite-order controls already pass on
the baseline. These tests use the registered Android reader, real session and SQLite mutation
outbox with a controlled transport and authenticated-identity verdicts supplied at the gate seam;
they do not claim HTTP negotiation, rendered consent controls or device execution.

Rejection delivery now carries its rejected authorization epoch. The gate checks that epoch and
applies the reader-owner rejection under the same lock used by successful validation and consent
acceptance. The registry lookup happens outside that lock, and the guarded write happens after
dispatch to the reader owner. Acceptance before delivery makes the old notification a no-op.
Delivery first retires the reader; the subsequent validated reconnect clears its local rejection
and flushes normally. The notification sender also rechecks negotiation generation before returning
its result. Acceptance never restores the old authorization token. All four ordering cases hold a
read from before rejection until after recovery and require `ReaderSendRefused`, then verify that
a new favourite flushes after the old notification has drained.

## Lasting rejection and replies already in flight

OBSERVED on published revision `4a946a68`: three review regressions fail before production changes.
`SavedServerIdentityTest/rejectedReachableReaderKeepsFavouritePendingWithoutSending` observes an
actual `star` request with the old album ID after rejection while `reachable` stays true.
`rejectedReaderDiscardsAnUnpagedReplyBeforeItCanEnterTheOldCache` observes the replacement artist
persisted in the old account's SQLite cache. `scrobbleReplyAcrossRejectionRetainsTheDurablePlay`
observes one delivered play instead of zero after a held successful reply crosses rejection.
The initial XML contains nine cases, three failures, zero errors and zero skips. Two additional
controls also fail before the fix: accepting a validated reconnect revives an older held read, and
a late favourite acknowledgement removes the pending mutation. That XML contains eleven cases,
five failures, zero errors and zero skips.

The shared reader now keeps sticky rejection separate from reachability. Every reader request
captures a rejection generation and account authorization token and checks them after transport,
before downstream readers or outboxes can cache, adopt or acknowledge the reply. Other account
clients can revoke authorization synchronously before queued reader publication. The scrobble sender
checks the same operation lifetime around capability discovery and after the scrobble response.
Rejecting and later accepting an account cannot revive an older response. Same-identity background
probes leave the accepted token stable. The original eleven shared tests pass unchanged after the
first fix. The expanded `SavedServerIdentityTest` XML executes fourteen cases with zero failures,
errors or skips, including external-rejection and reject/accept controls for the shared token seam.
Final complete-suite XML counts and platform sweep results are recorded with the final revision.

These are real SQLite, production reader/outbox and held transport observations. They establish
request and persistence boundaries, not a rendered native server-swap tour. The earlier statement
that rejection was fenced was incomplete for these suspended replies; the new tests exercise those
missing paths explicitly.

## Replies outside the reader (2026-10-23)

The sweep follows the actual await-to-write boundaries. Shared reader sends, including held-slot and
repeated playlist requests, converge on `issue`; this also fences playlist capability discovery and
reader-backed metadata. Scrobble extension discovery and the final acknowledgement check the captured
account lifetime. Platform clients that detect a replacement revoke that lifetime before asynchronous
reader or UI publication. Reaccepting the account does not authorize replies from its prior lifetime.

OBSERVED: `LibrarySyncTest/rejectedLegacySyncCannotPersistAHeldTrackPageOrCommitItsGeneration`
fails before its new authorization wrapper because the held track page enters SQLite even though
its generation is not committed. The unchanged test passes with the wrapper. The legacy engine checks
all nine source operations before and after suspension and checks admission, stage transitions and
commit. This is an exported fallback boundary; the shipping live-library route uses the reader.

OBSERVED: `ServerIdentityGateConcurrencyTest/lateProbeCannotOverwriteAnExplicitlyAcceptedReplacement`
and `lateProbeCannotRecreateRemovedAcceptedLineage` fail before the transactional post-authentication
comparison: the old probe respectively replaces newly accepted lineage and recreates removed lineage.
`matchingConcurrentLearningDoesNotReportASwap` passes before and after. All three pass with the guard.
These additional cases were established against the corresponding pre-fix seam during this round;
they are not represented as the three review regressions executed on unchanged `4a946a68`.

The full common-code XML after these changes contains **1,211 JVM tests in 94 suites** and
**1,301 macOS native tests in 96 suites**, each with zero failures, errors and skips. Mac, iOS Simulator
and tvOS Simulator debug frameworks link, and iOS Simulator common tests compile. Native controls include synchronous cross-client reader rejection, download-first sender revocation,
and matching/different explicit acceptance. Platform integration counts are reported separately below.

### Android platform sweep

OBSERVED incrementally before the corresponding platform fixes: sixteen new cases produce twelve
regression failures and four passing controls, with zero errors or skips in each group. They were
not all executed simultaneously on unchanged `4a946a68`. Held artwork enters disk/caller output;
held HTTP headers, body and already buffered prefix each expose 64 audio bytes; a held AutoPlay seed
causes five further fallback requests; late download headers write resume metadata; the public
sign-out delivery path deletes both pending plays; reject/accept revives held replacement metadata;
and queued cache append/finish writes cross into the newly accepted lifetime. Each failure exercises
the actual production path with loopback HTTP, SQLite, files or the held database dispatcher.

The fixes capture a stable account epoch before work begins and check that same epoch after awaits
and when queued persistence actually runs. Actual public sign-out senders and playback senders use
that authorization. A rejected account cannot fetch an artwork cache miss; existing cached artwork
and audio remain available. Ordinary transient probes do not retire accepted work. The sixteen new
regression/control cases retain their original assertions.

OBSERVED after the final Android identity-seeding transaction: the complete Android host XML has
**1,475 tests in 101 suites, zero failures, errors and skips**. The affected twelve-class subset has
261 tests. Phone DEV, phone PROD and TV Kotlin compilation also pass. This is host execution and
compilation evidence; fresh Linux repetition and real app flows have separate receipts and do not
follow merely from these counts.

### Apple platform sweep

OBSERVED before the artwork fix: the paused-player artwork case fails two image assertions while
its playback/session preservation assertions pass. A separate hosted Mac test holds real HTTP artwork
and observes replacement bytes written to disk and delivered to UI after rejection; its matching-
identity control passes. Those tests preserve accepted cached covers. The original fixture decoded
with a PNG CRC warning; only fixture bytes were corrected, and the standalone RED repeated. A second
hosted RED launch stopped before test execution in runner startup, so it is not claimed as another
reproduced hosted failure.

The native account lifetime is shared by database and provider across reader, download, playback
queue delivery, legacy import and AutoPlay. A download client revokes authorization before returning
its replacement failure, independently of queued source or reader notifications. Explicit different
lineage acceptance retires old captures before writing SQL and renews only after successful durable
acceptance. Matching acceptance stays stable. Swift artwork cache-actor insertion and UI completion,
current/preload callbacks, resource access, AutoPlay adoption, and legacy browse/search adoption all
check their captured lifetime. Existing offline covers and audio are retained. The AutoPlay facade
also checks before and after every internal source request, so rejection of a held seed cannot send
similar/top/random fallbacks.

OBSERVED locally after the fix: the complete playback-system suite passes **48 tests with zero
failures**. Three additional adapter cases separately pass: a download-first rejection refuses held
current and preload plans before source notification, and a real matching-identity download probe
preserves held current playback. Two actual HTTP AutoPlay controls pass: rejection after the held
seed permits only `getSong`, while matching identity permits all six source requests and a candidate.
These five cases and the paused-artwork proof are invoked through the existing download aggregate
selected by CI (the paused-artwork proof is Mac-only). No workflow or CI helper edit is needed.
Mac, iOS and tvOS shells compile against the final linked frameworks.

OBSERVED: both hosted Mac artwork cases now pass, **two tests, zero failures**, in 0.765 seconds.
The rejected held reply reaches neither disk cache nor UI; the matching-identity control retains the
accepted cover and reply. The Mac and iOS integration test bundles also build. Local Xcode startup
stalls occurred before test execution and are retained separately; validation uses the same compiled
app/test binaries with a complete temporary local framework layout. No product assertion is changed.

The unchanged hosted artwork helpers are also registered inside the existing CI-selected
`DulcetMacAccountConnectAppTest/connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureState`.
That complete selected method passes locally: **one emitted test, zero failures**, in 0.729 seconds,
with the artwork identity marker and original connection/persistence assertions. This is one aggregate
execution containing the two helper controls, not two additional emitted test cases. The two separate
artwork executions above establish their individual results. All three debug shells and the iOS download
integration test bundle compile. The final exact-head full Apple workflow remains a separate gate.

The full Mac Swift package passes **390 tests in 17 suites** with serial execution. An earlier parallel
run has one existing shared system Now Playing title failure; its serial rerun passes without test
changes. The new held-success session control also passes. These observations distinguish runtime
checks, adapter controls and compilation from an integrated live server-swap UI tour.

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

### Account connection after a timed-out retry (2026-10-22)

OBSERVED in the iPad leg of full Apple run `39818131357` on `169ef6f4`: 17 XCTest
executions produce 16 passes and one failure. The album-tile drag case fails before any drag
in 343.720 seconds. At 232.55 seconds, its hierarchy shows Connection selected with the
server-request timeout and Try Again. The test retries once, then waits globally for Sign Out.
At 337.88 seconds, the hierarchy shows Listen Now selected, the Navidrome account Online and
populated fixture albums. The retry connected successfully and navigated away from Connection,
but the helper returned failure without reopening the destination that offers Sign Out.
The first request timeout's cause remains unproven; slow accessibility queries and host pressure
were also observed, without establishing which caused that request to time out.

The retry retains its existing 45-second budget and the two retries limited to a request timeout.
Within that budget, it revisits Connection before each connected-state poll and caps each poll
at the smaller of ten seconds and the remaining budget. Sign Out still proves connection, and
all album order, exact queue-addition and menu-dismissal assertions remain unchanged. An
extracted-helper host control reproduces the departed-Connection failure for both compact and
regular layouts before the change; those two controls pass afterward. Its persistent-timeout
control still fails connection after two retries, and its already-connected control succeeds
without retrying: four controls, two failures before and zero afterward. These controls establish
the navigation branch; they do not reproduce the initial network timeout or XCTest's delays.

OBSERVED locally on an iPad Pro 13-inch (M5) simulator with iOS 26.5 and a fresh disposable
Navidrome 0.63.2 fixture: the complete iOS UI-test target builds, and the exact album-tile case
passes one test with zero failures in 146.243 seconds. One actual drag adds all three expected
tracks, in album order, to empty Up Next and leaves no menu open. This run connects without a
retry, so it confirms preserved end-to-end behavior; the extracted controls cover the retry
branch. Final-head full Apple execution remains required.

### iPhone tab-selection acknowledgement (2026-10-22)

OBSERVED in the phone leg of full Apple run `39833641465` on `2ba9dc9f`: seven
My Library XCTest executions produce six passes and one failure. The pin-and-offline case
fails in 126.981 seconds on its final return to Browse. Its initial Browse/pin, offline
relaunch with the Pinned heading and album, Downloaded destination, and Songs sort control
all pass first. The event archive records the final Browse tap at `(200, 822)`, inside that
tab's `(163, 795, 74, 54)` bounds; the subsequent hierarchy still shows Library selected.
Independent Opus 5.5 medium review of recording frames at 77 and 86 seconds observes the same
Library Songs screen and an unobstructed Browse tab in both. This supports an unacknowledged
tab activation rather than a hierarchy-only discrepancy; why it was not acknowledged remains
unproven. No host-pressure or production-navigation cause is established.

The phone selector now observes selection in short polls within its existing ten-second
post-activation budget and permits at most two ordinary retaps while the tab is unselected
and live-hittable. It checks the deadline again after that live query before sending another
event. A slow accessibility query may overrun wall time, but cannot authorize a late retap.
The actual selected-state oracle, pin/unpin behavior, offline assertions and fixture routes
are unchanged. The extracted actual phone branch fails an independent ignored-first-tap
control before this change and passes afterward. Four companion controls retain immediate
success, permanent-failure rejection, no retap on an unhittable control, and the deadline with
slow retaps: five controls, one failure before and zero afterward. A separate sixth control
exposes the query-order gap before its correction: a three-second poll plus an eight-second
hittability query sends a late event at eleven seconds. After correction, all six controls
pass and that query sends no extra event.

OBSERVED locally on an iPhone 17 Pro simulator with iOS 26.5 and fresh disposable Dulcet
Server/Navidrome 0.63.2 fixtures: the complete iOS UI-test target builds, and the exact pin-and-
offline case passes one test with zero failures in 65.478 seconds. It retains the pinned album
across the offline relaunch, reaches Downloaded and Songs, then returns to Browse and unpins
through the original assertions. No retap was needed in this run: it establishes ordinary
end-to-end preservation, while the independent controls exercise unacknowledged activation
and the deadline boundary. Final-head full Apple execution remains required.

### TV initial library navigation (2026-10-22)

OBSERVED: TV LIVE soak 39833691291 on 2ba9dc9f completed 540 executions over 20 repetitions, with 2 failures and 0 skips (`SOAK RESULT passed=18 failed=2`). Both retained XML failures stopped at the first Library section navigation with `UP did not reveal the library sections`, before playlist mutation or network loss; their actual failed focus state was not retained. The two workflows now wait for the same initial home-card focus used by neighboring TV scenarios, and the unchanged three-UP failure includes focus/tag diagnostics. Local Robolectric/real disposable-server validation passed all 3 original tests (remote playlist additions, offline pending/reconnect addition, album-play control), with 0 failures/errors/skips in 45.103 s; exact server playlist order and playback assertions remain intact. The local baseline and an exact-boundary delayed initial-home-response control also passed before the change, so no reproduced RED or proven original timing-cause claim is made. Fresh Linux 20x TV LIVE soak remains required on the final head.
## 2026-10-22 — Ubuntu media-tool installation infrastructure

Core CI run [39855394273](https://github.com/legitimate-apps/dulcet/actions/runs/39855394273)
on `641eed7b` reached its 25-minute core-job timeout in all three attempts while
downloading packages from `azure.archive.ubuntu.com`. Core tests, Android builds,
and Android host tests had passed first; server and API tests had not run. Those
attempts do not supply passing server/API evidence. The completed logs show
package payloads trickling until cancellation, consistent with the
[runner-image issue](https://github.com/actions/runner-images/issues/14823).
The two pinned media-tool installation steps now remove the Azure entry from the
runner's existing APT mirror list, retaining its official Ubuntu archive and
security fallbacks. The Ubuntu 24.04 check, exact FFmpeg package pin, APT signature
verification, and job timeouts remain unchanged. The filter was checked against
the actual `ubuntu24/20261012.334` runner-image mirror configuration; this local
check establishes mirror selection, not a successful package installation.


### iPad sidebar automation quiescence (2026-10-22)

OBSERVED in full Apple run `39873707703` on `27a560de`: the first My Library
`testBrowseAddRemoveRelaunchAndOfflineReplay` exceeds the fixed 300-second XCTest
allowance. Its batch finishes with five executions, four passes, one failure and
zero skips; the remaining 18 iPad cases are not reached. The previous head's same
case passed in 294.079 seconds. At the timeout, 193 of 279 sampled runner stacks
wait for pre-event application quiescence in the post-relaunch Library tap. The
application samples show SwiftUI graph/preference processing; they do not prove a
product deadlock or server wait. Several earlier launch/navigation delays also
contribute, so the final tap is not established as the sole cause.

The iPad sidebar selector now scopes ordinary native input to the current
application process using the same runtime-guarded interaction options already
used by account and player proofs. Each relaunch receives a new scope. The old
navigation body, explicit destination readiness and selected-state checks, test
body, offline/wire assertions and 300-second cap are unchanged. Other platforms
retain their prior selector path. An unavailable selector or unexpected ABI uses
ordinary waits; no proof is skipped.

OBSERVED by an independent local verifier on an iPad Pro 13-inch (M5), iOS 26.5,
with separate fresh disposable Navidrome fixtures: the unchanged case passes in
107.148 seconds and the changed case passes in 104.806 seconds, each one test,
zero failures and zero skips. Both builds and XCTest commands succeed. Both runs
send nine ordinary sidebar taps (seven Library, two Browse), need no cell retry,
and complete the original durable add/remove, offline playback, pending-write,
relaunch and reconnect assertions. The changed run emits no interaction-helper
fallback. This single sequential pair establishes preserved local behavior; it
supplies no local RED, hosted-timeout reproduction or causal speedup claim.
Final-head full Apple execution remains required.


### Positive empty Up Next readiness (2026-10-22)

OBSERVED in the iPhone leg of full Apple run `39873707703` on `27a560de`:
20 XCTest executions finish with 19 passes and one failure. The album-tile drag
case fails before any drag, while establishing its baseline queue. One queue read
takes approximately 57 seconds, so the 45-second settling loop cannot make its
second matching observation. The optional two-second wait for a queued row
triggers an approximately 31-second native accessibility retry for an absent
row. The retained accessibility hierarchy positively shows the open Up Next
collection, its `dulcet.upNext.empty` / Nothing Up Next marker, the expected
paused current track, and five separately identified AutoPlay suggestions. An
empty queue is valid here; no drag or drop has yet been attempted. The cause of
the native query delay remains unproven.

The test reader now waits positively for the existing empty-state marker or a
queued-row identifier within the same two-second budget. Neither AutoPlay
suggestions nor an unrendered list satisfy that readiness check. The 45-second
two-matching-read settling loop, queued-label snapshot logic, ordinary player
input/dismissal, uncovered-bar checks and all native-menu/drag/count/order
assertions remain unchanged. This removes a known absent-element query from the
valid empty-list path without extending a deadline.

OBSERVED by an independent local verifier on iPhone 17 Pro / iOS 26.5 with fresh
separate disposable Navidrome fixtures: the unchanged case passes in 101.294
seconds and the changed case passes in 99.255 seconds. Each runs one test with
zero failures and zero skips under the fixed 300-second XCTest cap; both test
builds and commands succeed. Each observes two empty baseline queues, then one
positively prepared native-menu drag adds the three expected album tracks in
order and leaves no menu open. These runs preserve the original end-to-end
behavior. Neither reproduces the long accessibility retry, supplies local RED,
or establishes a causal speed improvement. Final-head full Apple execution
remains required.


### TV grouped-search test recomposer confinement (2026-10-23)

OBSERVED in core CI run `39901409397` on `b443e8e4`: the TV grouped-search
remote proof fails. Its retained logcat first reports multithreaded
`SnapshotStateObserver` access while the test recomposer applies UI nodes on
`DefaultDispatcher-worker-10`, through `TestMonotonicFrameClock` and
`ApplyingContinuationInterceptor`. Approximately 26 seconds later, activity
teardown reports the secondary `SlotTable.clear` array-index exception. The
original background state publisher is not identified by that stack.

The failing instrumented proof now imports the v2 `createEmptyComposeRule`. The
resolved Compose 1.11.4 source confirms that its former v1 factory selects
`UnconfinedTestDispatcher`, whereas v2 selects `StandardTestDispatcher`; the
[official migration guide](https://developer.android.com/develop/ui/compose/testing/migrate-v2)
explains the queued composition behavior. This addresses the demonstrated
off-thread test recomposer path. Every remote input, assertion and deadline is
unchanged; no product or host-test source changes.

OBSERVED locally: `:android:tv:compileDebugAndroidTestKotlin` succeeds and both
existing `TvGroupedSearchTest` host cases pass, two tests with zero failures,
errors or skips. The combined build succeeds in 44 seconds. These checks preserve
compilation and host behavior; they do not establish local device RED→GREEN.
The unchanged remote workflow still requires execution in subsequent core CI.
