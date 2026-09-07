# Android playback leg 1: bounded host evidence

No feature status is promoted by this evidence. These tests execute in the Android host-test
runtime, not on a device. Each observation below names the production path exercised and the
substitutions that bound its meaning.

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

The adversarial follow-up's executed mutation results are recorded in
`android-playback-mutations.md`. The mutation run restores each production file before continuing;
mutants are not shipped.

## Not evidenced here

Real decoder-driven media progression, platform audio focus/ducking and becoming-noisy behavior,
service/background lifecycle, TV focus/media-key interaction, OS diagnostics, and delivery to an
independently measured server play count remain unobserved. Earlier broad statements about those
behaviors have been removed; host success does not establish them.

No emulator workflow or instrumented server-play proof is included. Automatic source refresh,
extension-transcoding selection, server-offset seeks, preloading/gapless playback, crossfade and
multi-item queue presentation are not claimed by these tests. Acoustic output is not observed.
