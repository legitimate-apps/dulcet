# Android playback adapter: leg 1

The production implementation is in `core/androidMain`. `AndroidMedia3Engine` implements the
existing internal `PlaybackEngine` contract; `AndroidPlaybackController` composes it with the existing
queue controller, SQLDelight queue/resume stores, scrobble accumulator and durable outbox.
`android/shared` supplies a `MediaSessionService`, a playback activity and the production entry used
by the mobile and TV search detail screens. No feature cell is promoted by this change.

## Implementation boundaries

- ExoPlayer owns audio focus with media/music audio attributes and `handleAudioFocus=true`, including
  platform ducking policy. Becoming-noisy events pause playback. The activity does not own the engine;
  service teardown releases it. Media-session commands use the same command path as app controls.
- The adapter's 500 ms handler sampler reads `SystemClock.elapsedRealtime`. It runs only while
  playback is unsuppressed and reports progression only after media position advances. Wall time is
  used for the first progression timestamp and outbox retention, never for accrual or retry delay.
- The inline data source reads the response it will deliver, applies the existing core envelope,
  content-type and audio-signature validator, and serves those same bytes to Media3. Media3 receives
  an opaque `dulcet://resource` URI. The loader owns signed requests, redirects, range checks and
  exact-length truncation checks. It accumulates fragmented reads before classifying a signature.
  Signature buffering is bounded to 16 MiB; a larger ID3 block is rejected before delivery.
- Platform I/O exceptions lose their message and cause before reaching Media3. Engine exceptions
  become typed domain failures before reaching presentation or the core. Credential canaries test
  those boundaries. No query-bearing URL is deliberately given to ExoPlayer.
- Queue entry, session and attempt identities remain distinct. Superseded async resolutions cannot
  start after a newer selection or stop. A restored queue is prepared paused for its owning account.
- Submitted plays are synchronously persisted before network delivery is scheduled. The existing
  outbox worker supplies bounded backoff and wall-clock retention; failed now-playing is ephemeral.
  Delivery remains at-least-once, not network-idempotent.

## Observed local proof

OBSERVED with the mounted Android SDK:

```sh
./gradlew :android:app:assembleDevDebug :android:tv:assembleDebug \
  :android:app:testDevDebugUnitTest :android:app:testProdDebugUnitTest \
  :android:tv:testDebugUnitTest :core:testAndroidHostTest :core:jvmTest :core:licensee
```

OBSERVED final output: `BUILD SUCCESSFUL in 14s`. JUnit reports 187 Android core tests,
181 JVM core tests, 9 mobile DEV tests, 9 mobile PROD tests and 2 TV tests, with zero failures
and zero skips. Unchanged checks may be up-to-date in the final invocation; the preceding full
invocation also passed, including the dependency licence audit.

OBSERVED `GITHUB_BASE_REF=main tools/run-local-gates parity-gate`: 21 passed, 0 failed,
1 environment fault, 0 uncovered. The environment fault was in
`test-transcode-probe-timeout-diagnostic`; this is not represented as a passing overall gate.

The test reports contain these concrete observations:

- `AndroidMedia3EngineTest.seamCommandsChangeTheRealMedia3Player`: calls the `PlaybackEngine` seam
  against a real ExoPlayer instance and observes volume 0.25 and rate 1.5; invalid play and volume
  commands are rejected. No decoder runs in this test.
- `AndroidMedia3EngineTest.periodicSamplerDrivesCoreThresholdAndExcludesPauseBufferingAndSeek`:
  the production periodic handler receives simulated Player positions, forwards events to the real
  core state machine and produces exactly one submitted-play effect. The test first proves that
  playing state without position advancement does not start progression, and encounters pause,
  buffering and a discarded forward discontinuity before crossing the threshold.
- `AndroidMedia3EngineTest.replacementKeepsSessionAndRejectsMismatchedAttemptAndOtherSession`:
  replacement reaches the core, preserves queue entry and session identities, and changes only the
  attempt. Mismatched attempts and another session are rejected.
- `AndroidPlaybackDataSourceTest.validatesTheActualResponseAndDeliversThoseSameBytesWithoutAPreflight`:
  one injected response, 40,012 bytes delivered, byte-for-byte equality, no second request.
  Additional controls encounter fragmented reads, HTTP-200 error envelopes, invalid audio,
  truncation, wrong ranges, seek-without-signature and estimated-length EOF.
- `AndroidPlaybackEntryTest` and `AndroidTvPlaybackEntryTest`: click the shared production entry
  in each app module and observe the playback-activity intent with exactly provider id, song id and
  title. They do not launch a decoder or prove TV remote behavior.

## Explicit limits

ASSUMED until leg 2: the real Android decoder advancing position, real audio-focus transitions,
background-service behavior on a device, real TV focus/media-key interaction, and delivery from the
app to exactly one independently measured server play. The host tests do not promote these claims.
No instrumented tests, emulator workflow or server play-count proof are included in leg 1.

The current user entry plays one selected search track through the legacy direct-stream path.
Extension transcoding selection, server-offset seeks, automatic source refresh/re-resolution and
multi-item queue presentation are not exposed by this entry. The adapter returns an explicit
`Unsupported` outcome for `PreloadNext`; gapless/preloading and crossfade are outside this slice.
These are implementation limits, not claims that the equivalent behavior is proven by host tests.
Media3 Compose state holders were evaluated; the screen uses core state so transport state cannot
be mistaken for the progression evidence or core policy. Acoustic output is not observed here.
