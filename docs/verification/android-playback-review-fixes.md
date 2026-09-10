# Android playback review repairs — 2026-09-09

The source-installation-only limitation recorded below was subsequently closed by
[encoded-envelope and real-source-consumption controls](android-playback-encoded-source.md).
That follow-up also states the supported encoding boundary and remaining blind spots.

Base: `de69354`. Implementation/test commits: `6c57470`, `fcc700e`, `457cdee`,
`27fcdc1`. No feature status is promoted by this work. No emulator evidence is claimed.

## Product repairs and reproduced failures

All five review findings were accepted. The queue and both binary defects were reproduced
with tests against the pre-fix product before changing their implementations:

- Queue restart: `1 test completed, 1 failed`. Stop followed by Play rebuilt the restored
  queue as one entry. Android now invokes `PlaybackQueueController.restartCurrent`, which
  checks account ownership, reads the persisted selection and starts a new session without
  replacing entries or changing their identities/order. The obsolete cached selected-song
  fallback is removed. The regression restores A/B/C, checks complete persisted state at
  A, advances to B, and repeats Stop/Play while checking B actually prepares and plays.
- Binary classification: `2 tests completed, 2 failed`. An XML declaration before an
  oversized ranged error body was accepted; binary beginning with `{` was rejected.
  Incomplete XML prologs now retain uncertainty. JSON decoding failures are classified by
  a prefix grammar instead of assuming every parse exception means incomplete JSON.
  Impossible JSON can therefore be audio at a witnessed seek offset, while incomplete
  candidate envelopes remain blocked. The existing 16 MiB uncertainty bound remains.

Independent review found two further XML cases within the same defect class: DOCTYPE
prologs and processing instructions inside their internal subsets. Both were repaired.
The ranged XML table includes declarations, comments, a short DTD, and oversized DTD
comments/PIs with misleading `] >` delimiters and quoted entity values. The receiver sees
`InvalidCredentials` and no delivered bytes for these error envelopes.

## Production wiring controls and their limits

Every new/changed test below is in an existing required `core-build` task. The UI controls
run in both phone flavors and TV. No workflow modification was needed.

| Test | Product path retained and observed | What could still be removed while this test passes? |
| --- | --- | --- |
| `stopThenPlayPreservesRestoredQueueAndRestartsTheCurrentSelection` | Real controller, engine, reducer and persistent queue; A/B/C IDs/order/selection survive; B prepares after advancement; a fresh session requests Play. | Decoder/source installation and network delivery are substituted. This is queue/transport evidence, not decoding or server evidence. |
| `xmlPrologBeforeOversizedRangedEnvelopeRemainsUnclassifiedUntilTheRoot` | Real data-source factory first establishes a WAV witness, then rejects consistent octet-stream 206 XML errors with zero delivered bytes and the authentication error. | Successful byte delivery could be deleted and this rejection-only test would pass; the positive binary-range control below exercises delivery. HTTP itself is an in-memory response boundary here. |
| `impossibleJsonAtAWitnessedSeekOffsetDeliversIdenticalBinaryWithoutReadingToEof` | Real witnessed range validation and reads deliver identical bytes for NUL, invalid member/value syntax and invalid escape prefixes; open reads exactly 8192 bytes instead of EOF. | Envelope rejection could be deleted while this positive-only test passes; negative XML/oversized JSON tests guard it separately. No decoder is exercised. |
| `productionSearchDetailOpensPlaybackWithOpaqueIdentity` in phone and TV | Launches the actual `SearchDetailActivity` using its production intent, clicks its Play node, and observes the activity destination and exact opaque extras. No test-owned `setContent` renders the entry. | The destination activity/service/engine implementation could be removed while navigation assertions pass. This proves the detail entry and routing, not downstream execution or TV remote input. |
| `productionPreparationInstallsAnAttemptScopedMediaSourceOnTheRealPlayer` | Retains production ExoPlayer creation and `setMediaSource`; observes item count, attempt ID, title, opaque URI and play intent. | A different source installing identical item metadata while bypassing the validating factory could pass. This proves source installation, not that full loader wiring, decoding or acoustic output works. |
| `progressionSendsSubmittedScrobbleThroughTheLiveConsumerWorkerAndSender` | Real consumer channel, outbox, worker, sender and HTTP stack; a loopback receiving socket observes `/rest/scrobble.view` with both now-playing and `submission=true`, correct item/time, then outbox acknowledgment. Only metadata/plan and Player progression are substituted. | Real decoding could be removed while this passes. The fixture returns success without storing server play counts, so this does not prove server-side counting. |

The older persist-before-handoff test intentionally retains its substituted delivery boundary;
it only establishes persistence ordering. The new socket test independently guards the live edge.
The fixture's initial empty-outbox drain completes during construction on its unconfined main
executor, before progression; it cannot accidentally deliver the later submission.

## Executed mutation evidence

Each mutation was applied alone to product code. The selected test(s) were executed, their fresh
JUnit XML inspected, and the original product file restored in a `finally` block. A nonzero
Gradle exit alone was not accepted: each run had to contain the expected executed test failures,
with zero errors/skips. All mutations compiled. The final full run below used restored sources.

| Product mutation | Actual test output | Discriminating observation |
| --- | --- | --- |
| Restart replaces queue with one selected item | 1 test completed, 1 failed | `Play must retain entries, IDs, order and selection` |
| Restart chooses the first entry instead of current selection | 1 test completed, 1 failed | Expected raw ID B, got A |
| Restore the old XML prolog classifier | 1 test completed, 1 failed | Expected rejection; open incorrectly succeeded with length 9117 |
| Remove DOCTYPE handling | 1 test completed, 1 failed | Expected rejection; open incorrectly succeeded with length 124 |
| Remove processing-instruction skipping inside DTD | 1 test completed, 1 failed | Expected rejection; open incorrectly succeeded with length 9141 |
| Treat every JSON parse failure as Unknown again | 1 test completed, 1 failed | Positive binary range throws `AndroidPlaybackIOException` |
| Treat incomplete JSON as NotEnvelope | 1 test completed, 1 failed | Existing oversized JSON control expects rejection; open succeeds with length 12074 |
| Delete production search-detail `PlaybackEntry` invocation | 3 tests completed, 3 failed | Phone DEV, phone PROD and TV cannot find `playback.open` |
| Delete production `setMediaSource` | 1 test completed, 1 failed | Expected one installed item, got zero |
| Delete live delivery consumer coroutine | 1 test completed, 1 failed | Expected one received submitted scrobble, got zero |
| Delete only `SubmittedPlay -> drain()` | 1 test completed, 1 failed | Expected one received submitted scrobble, got zero |

Total: **11 mutations detected by 13 executed failures**, zero errors/skips. The final harness
output was `ALL 11 MUTATIONS KILLED BY EXECUTED TEST FAILURES; PRODUCT SOURCES RESTORED`.
Each run's mutation diff, Gradle log and JUnit XML was retained with the local execution evidence.

## Final fresh validation

All build directories were routed through a local Gradle init script to a dedicated directory
on the internal disk, verified with `realpath`. The Android SDK mount was retained. The following
tasks ran together with `--rerun-tasks`, `--max-workers=2` and configuration cache disabled:

```sh
./gradlew :core:verifySqlDelightMigration :core:allMetadataJar :core:jvmTest \
  :core:testAndroidHostTest :core:bundleAndroidMainAar :core:licensee \
  :android:app:assembleDevDebug :android:app:assembleProdDebug :android:tv:assembleDebug \
  :android:app:testDevDebugUnitTest :android:app:testProdDebugUnitTest :android:tv:testDebugUnitTest
```

Output: **`BUILD SUCCESSFUL in 1m 1s`; `200 actionable tasks: 200 executed`.**

| Task | Suites | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `:core:jvmTest` | 26 | 185 | 0 | 0 | 0 |
| `:core:testAndroidHostTest` | 29 | 213 | 0 | 0 | 0 |
| `:android:app:testDevDebugUnitTest` | 5 | 10 | 0 | 0 | 0 |
| `:android:app:testProdDebugUnitTest` | 5 | 10 | 0 | 0 | 0 |
| `:android:tv:testDebugUnitTest` | 2 | 2 | 0 | 0 | 0 |
| Total | 67 | 420 | 0 | 0 | 0 |

Metadata JAR, Android AAR, all three APK variants, SQLDelight migration verification and the license
audit completed. Existing Kotlin/test-fixture warnings remain; no warning suppression was added.

Additional gates passed:

- `verify_ci_policy.py`: 6 workflows.
- `parity_gate.py`: 6 feature rows.
- `verify_os_floors.py --configuration-only`: macOS 14.0, iOS/tvOS 17.0 agree.
- `migration_gate.py`: 5 fixture databases, 4 protected table comparisons per fixture,
  download-file reconciliation and 9 destructive negative controls.
- `git diff --check`: clean.

A final fetch still reported main at `de69354`, already an ancestor of the branch, so no further
rebase was required. No push or PR operation was performed. Independent source review found no
remaining blocker after the DTD repairs. Local host success does not claim remote CI or emulator
success, platform background behavior, TV remote interaction, or server play-count evidence.

Two previously identified behaviors are outside this repair: Android still lacks main's
catalog-aware stale-selection restoration, and track advancement can retain the initial display
title even though the repaired transport restarts the correct persisted track. The source-installation
control's validating-factory limitation is stated explicitly in the table above.
