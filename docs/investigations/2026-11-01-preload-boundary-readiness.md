# Separate native preload inheritance from decoder preparation

## Observations and history

The inheritance tests use a real AVQueuePlayer, real native current-item KVO and the engine's real promotion path. Their media-time samples come from a manual clock. The policy under test is that promotion copies the outgoing play request: a playing advance reports incoming progress, while a paused advance reports none until explicit Play. The incoming first sample (0.25) is below the outgoing last sample (0.5), so attributing it to the outgoing context cannot satisfy the positive control.

The Xcode 27 pin, [#404](https://github.com/legitimate-apps/dulcet/pull/404), also changed this fixture. A never-completing resource had allowed AVFoundation to retire the incoming item on iOS 27. That change supplied ten minutes of PCM and added a post-advance requirement that the item become readyToPlay within the ordinary 20-second engine-event wait. The PCM is generated in memory: RIFF/WAVE, mono 8 kHz signed 16-bit samples, 9,600,000 payload bytes plus a 44-byte header. The resource serves requested byte ranges directly from that Data; there is no network server or transcoder in this fixture.

A bounded census after #404 found these three failures of the new shared readiness gate:

| Test | Platform | Run / attempt / job | Observed wait |
|---|---|---|---|
| Paused inheritance | iPad | [41245411067 / 1 / 124667719615](https://github.com/legitimate-apps/dulcet/actions/runs/41245411067/job/124667719615) | 20.002 s |
| Paused inheritance | macOS | [41289800644 / 2 / 124838607604](https://github.com/legitimate-apps/dulcet/actions/runs/41289800644/job/124838607604) | 20.080 s |
| Playing inheritance | macOS | [41163176743 / 1 / 124425604256](https://github.com/legitimate-apps/dulcet/actions/runs/41163176743/job/124425604256) | 20.008 s |

All three logged incoming status unknown (0), no item error, and an engine queue responding in 0.000–0.001 seconds. The engine had already emitted the real boundary; the failure preceded the intended inheritance assertions. The playing failure rules out Pause as a sufficient explanation. No audio-overload line was found in the captured iPad failure window; host audio starvation is not established as the cause.

The census's fixed workflow listing was taken November 1 at 12:02:24 UTC, starting with #404's main commit timestamp, October 30 at 03:28:17 UTC. It covers 69 PR/main records and 84 completed attempts, including completed earlier attempts of workflows currently being rerun. All 63 jobs that acquired runners have complete logs; all identify the xcode-27-arm64 image. Each target was positively reached 193 times:

| Platform | Paused pass / reached | Playing pass / reached |
|---|---:|---:|
| macOS | 60 / 61 | 60 / 61 |
| iPhone | 47 / 47 | 47 / 47 |
| iPad | 42 / 43 | 43 / 43 |
| tvOS | 42 / 42 | 42 / 42 |
| Total | 191 / 193 | 192 / 193 |

The seven main-push platform jobs reused proof and skipped actual execution; the positive executions are from PRs. Three passes lack literal start output but explicitly name the passed test; they are positive receipts, with unknown start timestamps. Execution receipts are in [the census TSV](2026-11-01-preload-boundary-census.tsv), deduplicated by test identity and actual execution timestamp. Limits: workflows created before the pin but rerun afterward, 50 dispatch records and the then-active attempt are outside this frame. This is not an all-time rate or proof that Xcode alone caused a behavioral change. The readyToPlay requirement itself entered main with #404.

## Cause and change

The test incorrectly made the engine's manual-clock inheritance contract depend on AVPlayerItem's asynchronous decoder preparation completing within 20 seconds. Promotion gates on the native current-item identity and copies playRequested. Sampling gates on current context and playRequested, never item.status or readyEmitted. No production engine change is warranted by these failures.

Apple defines unknown as unloaded/unprepared media and readiness as completion of preparation; it does not attach this test's 20-second deadline to that transition. Explicit [asynchronous asset loading](https://developer.apple.com/documentation/avfoundation/loading-media-data-asynchronously) and [asset playability](https://developer.apple.com/documentation/avfoundation/avpartialasyncproperty/isplayable-45h5v) let the fixture validate its media separately from the player's decoder status.

Both tests retain every progress-policy assertion, the real queue, actual engine KVO promotion, exact attempt IDs, ordering and the paused test's explicit-Play positive control. The fixture now explicitly loads playability and 600-second duration on the exact queued incoming asset before advancing. This demands and validates its media metadata instead of assuming opportunistic player preparation completed. It checks exact native and engine incoming-item identity at the boundary and after the policy samples and rejects failure/skip events. It no longer requires decoder readiness in the middle of the policy proof. Production engine code is unchanged.

The committed fixture retains immediate delivery of complete PCM. An experimental held-byte parameterization was rejected: although four targeted cases passed, its broader 42-test suite found AVFoundation had retired the suspended incoming item after metadata loading. That violated the required native identity invariant and repeated the unstable setup #404 addressed. It is not a permanent regression fixture or claimed passing suite.

**Limit:** the historic logs do not record which AVFoundation preparation substage was pending or the incoming resource-request trace. They do not establish a resource-loader dropped callback, a blocked engine queue, or a production playback defect. The controlled hold reproduces the false readiness dependency, not an asserted internal AVFoundation cause for every hosted occurrence.

## Reproduction and validation

The private held-byte replay restored the old 20-second status gate. Its held case failed at 20.018 seconds with status 0, one pending resource request, no error, and both native and engine identities still correct. This is bounded evidence that decoder preparation and promotion are different conditions; it does not establish the item's continued stability after that interval. The rejected full-suite held-byte experiment above is retained with the private evidence.

A reproducible diagnostic probe holds the first incoming resource response for 30 seconds, then supplies the complete valid PCM and serves later ranges immediately. The original helper advances before those bytes arrive and applies its 20-second readiness gate. The revised helper explicitly loads playability/duration before advancing, then exercises the unchanged inheritance policy and retained-item checks. This is an injected asset-delivery delay, not a claim that the immediate in-memory completion itself was delayed in the historic hosted failures. [The diagnostic patch](preload-boundary-delayed-asset-probe.patch) adds only that delayed test resource and substitutes it for the incoming in-memory resource. Apply the same patch to main f8b657cd and to this fix; it does not change the engine. The old paused test fails with the original readiness signature after 21.238 seconds (one test, two issues). The revised paused test passes after 31.237 seconds (one test, zero issues), after the logged release of the same PCM. These are controlled fault-injection results, not a repeat-count claim.

For each source version, from apple/DulcetKit:

```sh
xcodebuild test -scheme DulcetKit-Package \
  -destination 'platform=macOS' -parallel-testing-enabled NO \
  -derivedDataPath /tmp/dulcet-preload-proof \
  '-only-testing:DulcetKitTests/AVPlayerEngineTests/aPreloadedAdvanceWhilePausedInheritsNoPlayRequest()' \
  -resultBundlePath /tmp/dulcet-preload-proof.xcresult
```

Use separate empty result-bundle paths for the two runs and remove the diagnostic patch before ordinary validation. The delay is 30 seconds after the first actual resource request, not after test launch. It is finite and supplies the full generated PCM; the committed fixture continues to serve immediately.

The final uninstrumented AVPlayerEngineTests suite passed 42 tests in 10.181 seconds. All local xcodebuild invocations use -parallel-testing-enabled NO. The test runner's initial zero-test filter attempts were invalid evidence and excluded; executed method filters include Swift Testing's parentheses.


The final-source inheritance controls also reject both defects: setting incoming.playRequested unconditionally true fails the paused suppression assertion (one test, one failure, 1.232 seconds); setting it false fails playing incoming progress and ordering (one test, one failure, 1.313 seconds). Neither production mutation is retained. After all diagnostic and mutation changes were restored, the final two native tests passed again with zero failures/skips. Result-bundle summaries confirm all counts; the complete 42-test suite had zero failures/skips. tools/typecheck-xcuitest-sources passed all five directories and 21 sources. No conformance-test source changed.
