# Android playback: host regressions and bounded device evidence

No feature status is promoted by this evidence. The host tests execute in the Android host-test
runtime; the repair section separately records an Android 14 emulator lifecycle test. Each
observation names the production path exercised and the substitutions that bound its meaning.

## Tests and their actual observations

- `AndroidHttpPlaybackResourceTest.sameOriginRedirectPreservesTheSignedQueryOnTheReceivingSocket`
  constructs the production `AuthenticatedEndpointClient`, `AndroidHttpPlaybackResource` and data
  source. A real loopback HTTP socket returns a same-origin redirect containing the original signed
  query. The destination socket receives that exact query, and the consumer receives the audio bytes.
  This does not claim that a redirect omitting the original query recreates it.
- `AndroidHttpPlaybackResourceTest.crossOriginRedirectStripsAllCredentialCanariesOnTheReceivingSocket`
  uses two real loopback origins. The second receives the request, with metadata intact and credential
  canaries absent. The fixture also supplies a legacy password, an uppercase username key and a
  percent-encoded token key in the redirect. An exact inventory classifies every key emitted by the
  production authorizer; any new unclassified key fails the test. Credential identities are also
  exhaustively mapped from `AuthenticationParameter` in the Android redirect policy.
- `AndroidHttpPlaybackResourceTest.httpsDowngradeIsRejectedAfterTheTlsServerActuallyReturnsItsRedirect`
  uses a generated fixture certificate with a strict trust store and normal hostname verification.
  The HTTPS socket must receive the signed request, return a downgrade redirect, and leave the HTTP
  destination with zero requests. An earlier TLS failure cannot satisfy the test.
- `AndroidHttpPlaybackResourceTest.realErrorAndTruncatedResponsesFailWithoutSurfacingTheirSignedUrl`
  encounters real HTTP-200 error-envelope, HTTP-403 and truncated-body responses through the same
  production HTTP loader. Consumption fails with the closed exception type and no URL, credential
  canary or nested cause in the surfaced exception.
- `AndroidMedia3EngineTest.seamCommandsChangeTheRealMedia3Player` calls `PlaybackEngine` with a real
  ExoPlayer and observes volume 0.25 and speed 1.5. It rejects invalid play/volume and post-release
  play. It does not prepare or decode a real media stream.
- `AndroidMedia3EngineTest.periodicSamplerDrivesCoreThresholdAndExcludesPauseBufferingAndSeek`
  uses a Player probe and the real adapter handler and core state machine. It encounters pause and
  buffering, then calls `PlaybackCommand.Seek`. The probe records the `seekTo` call and fires the
  Media3 discontinuity callback. The test asserts `SeekCompleted` attempt, old position and new
  position, including a subsequent `SEEK_ADJUSTMENT`. After position advances again, the core
  discards the forward discontinuity and ultimately emits one submitted-play effect. The probe
  supplies positions; neither a decoder nor a server submission is observed.
- `AndroidMedia3EngineTest.replacementKeepsSessionAndRejectsMismatchedAttemptAndOtherSession`
  passes replacement events into the real reducer and asserts preserved queue/session identity
  with a changed attempt, plus rejection of mismatched attempts and another session.
- `AndroidMedia3EngineTest.platformErrorAndNestedCredentialCanariesNeverReachTheCoreEvent`
  injects a credential-bearing PlaybackException into the listener and asserts a typed, content-free
  failure event. It does not inventory every diagnostic the Android OS might emit.
- `AndroidPlaybackDataSourceTest` supplies in-memory responses to test identical byte delivery,
  fragmented signatures, malformed audio/envelopes, exact-length truncation, incorrect ranges,
  nonzero-offset loading without a signature witness, and estimated-length EOF. These tests alone
  provide no HTTP, redirect or TLS evidence; the separate HTTP-loader tests above provide that.
- `AndroidPlaybackControllerTest.lateMetadataAfterNewSelectionCannotReplaceTheNewQueue` returns a
  cancelled metadata operation after a new selection has prepared. It asserts that the old operation
  really returned and cannot replace the new queue or prepare the old item.
- `AndroidPlaybackControllerTest.latePlanAfterStopCannotPrepareOrPlay` returns a cancelled plan
  resolution after stop. It asserts that the resolution really returned and neither prepared nor
  requested playback. These two bounded races do not prove every possible scheduling interleaving.
- `AndroidPlaybackControllerTest.submittedPlayIsInTheRealOutboxBeforeDeliveryHandoff` runs simulated
  Player progression through the production controller, engine and core accumulator. Its external
  delivery boundary checks that the submitted play is already in the real SQLDelight outbox at the
  instant the controller schedules delivery. It observes exactly one handoff, not a network send.
- `AndroidPlaybackControllerTest.restoredQueuePreparesPausedOnlyForItsOwningAccount` seeds a real
  persisted queue and constructs the controller for its owner, then for another account. Only the
  owner loads metadata and prepares, with no play request. Metadata and Player are substituted;
  the controller, engine and persistence code are real.
- `AndroidPlaybackEntryTest` and `AndroidTvPlaybackEntryTest` click the production entry in their
  respective app-module harnesses. They assert the activity destination and exact provider, song
  and title values, with no additional intent extras. They do not test TV remote keys or decoding.

## Validation commands

```sh
./gradlew :core:testAndroidHostTest \
  :android:app:assembleDevDebug :android:tv:assembleDebug \
  :android:app:testDevDebugUnitTest :android:app:testProdDebugUnitTest \
  :android:tv:testDebugUnitTest :core:jvmTest :core:licensee
GITHUB_BASE_REF=main tools/run-local-gates parity-gate
```

## Controller fixture isolation

OBSERVED: the full host suite at `c78cd7c`, invoked with
`./gradlew --no-daemon :core:testAndroidHostTest --rerun-tasks`, reported
`195 tests completed, 1 failed` and `BUILD FAILED in 21s`.
`DownloadPolicyTest.credentialGenerationChangeCancelsAndRequeuesOutstandingTask` failed while
constructing its database, with `java.sql.SQLException: No suitable driver found for jdbc:sqlite:`.

OBSERVED (source inspection): the controller fixture called the JDBC `createTestDriver()` from
inside Robolectric. Xerial JDBC's static initializer registers a driver with the process-wide
`java.sql.DriverManager`. OpenJDK 17's `DriverManager` initializes service providers once and
filters registered drivers by the caller's class loader. Its `isDriverAllowed` loads the driver's
class in the caller's loader while iterating a snapshot of the registrations. A driver newly
registered by that class initialization is unavailable to that connection attempt. The shared
registration can therefore break the first connection from the other loader; closing a JDBC
connection does not unregister its driver.

OBSERVED: the controller fixture now uses `DulcetDriverFactory` with a unique database name, exercising the
production Android SQLite driver without registering a sandbox JDBC driver. Each fixture closes
the controller and database and asserts that the Media3 listener is detached and the database is
deleted. The fixture also calls `close()` twice to exercise idempotent service-owner teardown.
Production shutdown code, test ordering, test concurrency and gates are unchanged.

OBSERVED after the fixture change in `9042aff`: three consecutive executions of
`./gradlew --no-daemon :core:testAndroidHostTest --rerun-tasks` produced:

| Run | Gradle output | JUnit cases | Failures | Errors | Skipped | Controller cases |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | `BUILD SUCCESSFUL in 20s` | 195 | 0 | 0 | 0 | 4 |
| 2 | `BUILD SUCCESSFUL in 23s` | 195 | 0 | 0 | 0 | 4 |
| 3 | `BUILD SUCCESSFUL in 22s` | 195 | 0 | 0 | 0 | 4 |

Counts were read from the newly written `core/build/test-results/testAndroidHostTest/TEST-*.xml`
after each run, before the next run replaced them. Each run reported `21 actionable tasks:
21 executed`. This observes repeated execution of the full suite, including controller teardown;
it does not assert that every possible test interleaving has been explored.

OBSERVED: the subsequent complete validation command was:

```sh
./gradlew --no-daemon \
  :android:app:assembleDevDebug :android:tv:assembleDebug \
  :android:app:testDevDebugUnitTest :android:tv:testDebugUnitTest \
  :core:testAndroidHostTest :core:jvmTest :core:licensee --rerun-tasks
```

Output: `BUILD SUCCESSFUL in 55s`, `143 actionable tasks: 143 executed`.
Fresh JUnit reports contained 9 app cases, 2 TV cases, 195 Android host cases and 181 JVM cases;
each task had zero failures, errors and skips. Both assembly tasks and `:core:licensee` completed.

## PR #99 repair evidence

OBSERVED on the reviewed `d39da7c` production sources: the newly added host regressions failed
with the following assertions before their corresponding fixes. Tests were added before modifying
production code. The seek regression ran later against the still-unchanged common accumulator.

| Defect | Regression | Observed red | Fix commit |
| --- | --- | --- | --- |
| Local service ownership | `PlaybackServiceOwnershipTest.localBindingRegistersTheProductionSessionWithoutAControllerConnection` | Expected one managed session, observed zero | `0005654` |
| HTTPS query duplication | `AndroidHttpPlaybackResourceTest.httpsSameOriginRedirectPreservesExactlyOneSignedQuery` and `httpsCrossOriginRedirectStripsCredentialsAndPreservesMetadataExactlyOnce` | Duplicated query; corrupted final salt or metadata value | `521eab6` |
| Pause during delayed selection after Stop | `AndroidPlaybackControllerTest.pauseWhileMetadataLoadsAfterStopOverridesThePreviousPlayIntent` | Player still requested playback after Pause and second preparation | `ef99546` |
| Oversized JSON envelope on 206 seek | `AndroidPlaybackDataSourceTest.oversizedEnvelopeOnAValid206SeekNeverReachesThePlayer` | Open accepted the 12,074-byte envelope after an initial audio witness | `0c22a61` |
| Short 206 with an unserved suffix | `AndroidPlaybackDataSourceTest.internallyConsistentShort206CannotAdvertiseAnUnservedSuffix` | Open returned 100,000 for a 10,000-byte response | `dcf23e1` |
| Inherited small forward seek accrual | `ScrobbleAccumulatorTest.explicitSmallForwardSeekCreditsOnlyProgressAfterTheDestination` | Expected 10.5 seconds total, observed 13.5 seconds | `b6329ac` |

OBSERVED: all listed regressions pass after repair. The service explicitly adds/removes its session;
HTTPS queries are replaced exactly once; fresh preparation begins paused and applies current intent.
Incomplete JSON and whitespace-only prefixes remain unknown and are incrementally inspected within
a 16 MiB bound. Initial audio still requires the expected container's positive signature; a nonzero
range still requires that attempt's initial signature witness, with envelope inspection on every
response. No file-header signature is required at an arbitrary offset. Unknown is never audio.
Unsupported short ranges fail explicitly; a separate positive control delivers an exactly satisfied
bounded range. Prefix capacity grows geometrically to avoid repeated full copies per 8 KiB chunk.

OBSERVED: `AndroidMedia3EngineTest.stopAndFreshPreparationClearRealExoPlayerIntentBeforePreparingTheSource`
uses a real ExoPlayer and checks `playWhenReady` at Stop and inside fresh source preparation, then
checks that subsequent Play works. The delayed-metadata test retains the production controller,
engine and stores, with a Player probe at the platform boundary.

OBSERVED: the full verification passed core JVM tests (182), Android host tests (205), app Dev tests
(10), app Prod tests (10) and TV tests (2), with zero failures, errors or skips. App Dev and TV APK
assembly, metadata/AAR generation, the licence audit, parity gate, CI policy and OS-floor configuration
checks passed. The final Android host run includes the real-player, bounded-range and prefix-limit
controls; unchanged JVM outputs were reused from the preceding successful full run.

OBSERVED on an Android 14 ARM64 emulator:
`PlaybackBackgroundTest.localPlaybackRetainsForegroundOwnershipAndProgressAfterHome` passed via
`:android:app:connectedDevDebugAndroidTest`. It saves synthetic credentials through the production
Keystore store, launches the production PlaybackActivity, loads metadata and PCM WAV from a disposable
loopback protocol fixture, and observes decoder-driven position advancement. It removes its own local
observer binding before pressing Home, waits for the activity to reach the stopped lifecycle state,
and observes progression from 1,806 ms to 4,299 ms with a foreground-service notification still active.
No independent MediaController is created. The test then stops playback and removes fixture credentials.
Captured logcat contained zero matches for the username and password canary values; this is a bounded
capture, not a universal claim about OS diagnostics.

The device test and supplementary controls are in `0922dc9` and `8115bc2`. The device proof supplements
the baseline-red host ownership regression; it was not itself run against the baseline revision.

## Not evidenced here

Acoustic output, platform audio focus/ducking and becoming-noisy behavior, TV focus/media-key
interaction, broader OS diagnostics, and delivery to an independently measured Navidrome play count
remain unobserved. The emulator test establishes one PCM WAV background-playback path on Android 14;
it does not establish every format, OS version, device, process-death recovery or TV behavior.

No emulator CI workflow or instrumented server-play proof is included. Automatic source refresh,
extension-transcoding selection, server-offset seeks, preloading/gapless playback, crossfade and
multi-item queue presentation are not claimed by these tests. No feature status is promoted.
