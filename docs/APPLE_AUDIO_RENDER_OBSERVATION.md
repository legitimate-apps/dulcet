# Apple decoded PCM observation

**OBSERVED 2026-09-06:** Dulcet's own `DulcetAVPlayerEngine` delivered non-zero decoded
PCM into its AVFoundation post-effects audio processing callback on macOS 26.6.2
(25G83, arm64) and iOS Simulator 26.5 (23F77, iPhone 17 Pro), built with Xcode 26.6
(17F113). This is a bounded progressive-MP3 measurement, not a claim about every
container, physical iOS hardware, or acoustic output.

## 1. Instrument and measurement

**OBSERVED:** `installCurrentItemAudioRenderObserverForTesting` installs an
`MTAudioProcessingTap` on the current engine item's audio track, after prepare and
before play. The engine still obtains its bytes through the production core plan,
resource bridge and custom-scheme loader against a loopback HTTP range fixture.
The observer calls `MTAudioProcessingTapGetSourceAudio` and returns its buffers,
frame count and flags unchanged. It records the normalized Float32 peak, decoded
frames, nonempty processing buffers, actual scalar samples, prepare callbacks and
errors. Unsupported formats and non-finite samples fail the test.

**OBSERVED (Apple API contract):** a post-effects tap runs after the effects in
`AVAudioMixInputParameters`; source audio is retrieved inside the processing
callback. See [Apple QA1783](https://developer.apple.com/library/archive/qa/qa1783/_index.html)
and [GetSourceAudio](https://developer.apple.com/documentation/mediatoolbox/mtaudioprocessingtapgetsourceaudio(_:_:_:_:_:_:)).

**ASSUMED / not instrumented:** subsequent delivery from that callback to the OS
HAL and from the HAL to a speaker. Readiness and media-time progress remain
transport observations. No host-output capture is used.

**OBSERVED:** these final commands built and executed the named test. The iOS
UUID identifies the measurement destination; select an available iPhone simulator
when reproducing the command. Buffer totals and peaks can vary with the callback
boundary of the two-second observation window.

**OBSERVED — macOS:**

```sh
xcodebuild test -project apple/Dulcet.xcodeproj -scheme DulcetPlaybackIntegration -destination platform=macOS,arch=arm64 -derivedDataPath /tmp/dd-dulcet-audio-render -resultBundlePath /tmp/dulcet-audio-observation-mac.xcresult -only-testing:DulcetPlaybackIntegrationTests/DulcetPlaybackIntegrationTests/testProductionEngineRendersNonZeroPCMAndDistinguishesSilence -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

```text
AUDIO_SOURCE platform=macOS source=source-music peak=0.5238342 frames=132300 buffers=33 samples=264600 invalid=0
AUDIO_SOURCE platform=macOS source=source-silence peak=0.0 frames=132300 buffers=33 samples=264600 invalid=0
AUDIO_RENDER platform=macOS source=engine-music peak=0.4928589 frames=113370 buffers=30 samples=226740 prepares=1 errors=0 unsupported=0 invalid=0
AUDIO_RENDER platform=macOS source=engine-silence peak=0.0 frames=109591 buffers=29 samples=219182 prepares=1 errors=0 unsupported=0 invalid=0
Test Case '-[DulcetPlaybackIntegrationTests.DulcetPlaybackIntegrationTests testProductionEngineRendersNonZeroPCMAndDistinguishesSilence]' passed (5.879 seconds).
Executed 1 test, with 0 failures (0 unexpected) in 5.879 (5.880) seconds
EXIT_CODE=0
```

**OBSERVED — iOS Simulator:**

```sh
xcodebuild test -project apple/Dulcet.xcodeproj -scheme DulcetiOSPlaybackIntegration -destination 'platform=iOS Simulator,id=6B513943-4ED6-4C70-A64B-EB9AA2AD4558' -derivedDataPath /tmp/dd-dulcet-audio-render -resultBundlePath /tmp/dulcet-audio-observation-ios-isolated.xcresult -only-testing:DulcetiOSPlaybackIntegrationTests/DulcetPlaybackIntegrationTests/testProductionEngineRendersNonZeroPCMAndDistinguishesSilence -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO
```

```text
AUDIO_SOURCE platform=iOS Simulator source=source-music peak=0.5238342 frames=132300 buffers=33 samples=264600 invalid=0
AUDIO_SOURCE platform=iOS Simulator source=source-silence peak=0.0 frames=132300 buffers=33 samples=264600 invalid=0
AUDIO_RENDER platform=iOS Simulator source=engine-music peak=0.4636536 frames=109591 buffers=29 samples=219182 prepares=1 errors=0 unsupported=0 invalid=0
AUDIO_RENDER platform=iOS Simulator source=engine-silence peak=0.0 frames=113370 buffers=30 samples=226740 prepares=1 errors=0 unsupported=0 invalid=0
Test Case '-[DulcetiOSPlaybackIntegrationTests.DulcetPlaybackIntegrationTests testProductionEngineRendersNonZeroPCMAndDistinguishesSilence]' passed (5.373 seconds).
Executed 1 test, with 0 failures (0 unexpected) in 5.373 (5.376) seconds
EXIT_CODE=0
```

## 2. Controls and execution checks

**OBSERVED:** `testProductionEngineRendersNonZeroPCMAndDistinguishesSilence` first
decodes the same source bytes with `AVAudioFile`, independently of Dulcet's engine
and tap. Each source decode must produce 132,300 stereo frames (264,600 scalar
samples), more than one buffer and no non-finite samples. Music must exceed
`0.001`; generated MP3 silence must stay at or below `0.000001`.

**OBSERVED:** the test then sends each source through a fresh production engine and
the same observer implementation. Both observations must include a prepare callback,
more than 44,100 decoded frames, more than one processing buffer, and exactly two
scalar samples per frame. Consequently a tap that never fires, an empty decode,
missing sample data, or an unsupported format cannot satisfy the silent control.
The source and engine each assert the corresponding music/silence threshold.

**OBSERVED:** the original
`testProductionCoreResourceBecomesReadyAndProgressesAgainstNavidromeRanges` remains
present and passed alongside the new test on both destinations (two tests, zero
failures). It runs without installing the new observer.

**OBSERVED:** CI configuration adds one named-test invocation on each platform to
`apple-ci`, followed by the existing exact-execution checker. Local result-bundle
checks used:

```sh
python3 tools/verify-xcode-test-execution /tmp/dulcet-audio-observation-mac.xcresult DulcetPlaybackIntegrationTests.DulcetPlaybackIntegrationTests testProductionEngineRendersNonZeroPCMAndDistinguishesSilence
python3 tools/verify-xcode-test-execution /tmp/dulcet-audio-observation-ios-isolated.xcresult DulcetiOSPlaybackIntegrationTests.DulcetPlaybackIntegrationTests testProductionEngineRendersNonZeroPCMAndDistinguishesSilence
```

```text
xcode test execution valid: test=DulcetPlaybackIntegrationTests.DulcetPlaybackIntegrationTests/testProductionEngineRendersNonZeroPCMAndDistinguishesSilence terminal=Passed individual-results=1
xcode test execution valid: test=DulcetiOSPlaybackIntegrationTests.DulcetPlaybackIntegrationTests/testProductionEngineRendersNonZeroPCMAndDistinguishesSilence terminal=Passed individual-results=1
```

**ASSUMED / not yet observed:** execution on hosted CI. The measurements above are
local platform runs; adding a workflow is not evidence that a hosted run passed.

## 3. Mutation gates

**OBSERVED:** two independent edits were tested on each platform:

- Music: `minimumMusicPeak` changed from `0.001` to `2`.
- Silence: `maximumSilencePeak` changed from `0.000001` to `-1`.

**OBSERVED:** each mutation used `xcodebuild build-for-testing` with the same project,
scheme, destination, DerivedData and test selector as above, then checked the build
process exit code was **0 before invoking** `xcodebuild test-without-building` with
those arguments (without a result-bundle argument). No failed build was allowed to
run a stale test binary. Each red run executed one test and failed exactly the two
expected threshold assertions, with test exit code 65. The original thresholds were
restored before the final green `xcodebuild test` runs above.

**OBSERVED — mutation-music-mac:**

```text
BUILD_EXIT_CODE=0
XCTAssertGreaterThan failed: ("0.5238342") is not greater than ("2.0") - source music must be non-silent
XCTAssertGreaterThan failed: ("0.4636536") is not greater than ("2.0") - engine must deliver non-zero PCM into its processing callback
Executed 1 test, with 2 failures (0 unexpected) in 7.256 (7.256) seconds
TEST_EXIT_CODE=65
```

**OBSERVED — mutation-music-ios:**

```text
BUILD_EXIT_CODE=0
XCTAssertGreaterThan failed: ("0.5238342") is not greater than ("2.0") - source music must be non-silent
XCTAssertGreaterThan failed: ("0.4928589") is not greater than ("2.0") - engine must deliver non-zero PCM into its processing callback
Executed 1 test, with 2 failures (0 unexpected) in 5.764 (5.765) seconds
TEST_EXIT_CODE=65
```

**OBSERVED — mutation-silence-mac:**

```text
BUILD_EXIT_CODE=0
XCTAssertLessThanOrEqual failed: ("0.0") is greater than ("-1.0") - source silence must be silent
XCTAssertLessThanOrEqual failed: ("0.0") is greater than ("-1.0") - the same observer must distinguish silence from music
Executed 1 test, with 2 failures (0 unexpected) in 6.482 (6.482) seconds
TEST_EXIT_CODE=65
```

**OBSERVED — mutation-silence-ios:**

```text
BUILD_EXIT_CODE=0
XCTAssertLessThanOrEqual failed: ("0.0") is greater than ("-1.0") - source silence must be silent
XCTAssertLessThanOrEqual failed: ("0.0") is greater than ("-1.0") - the same observer must distinguish silence from music
Executed 1 test, with 2 failures (0 unexpected) in 5.537 (5.538) seconds
TEST_EXIT_CODE=65
```

## 4. Claim boundaries and Release exclusion

| Assertion / link | Status and exact scope |
| --- | --- |
| Loopback server → bytes | **OBSERVED:** HTTP range requests and loader response/lifecycle traces for the production resource; requests are nonempty and ranged, with zero loader failures and no remaining active requests after release. |
| Bytes → decoder → PCM | **OBSERVED:** the engine item's post-effects tap returns actual finite Float32 PCM; music exceeds the non-zero threshold. |
| PCM → AVFoundation processing callback | **OBSERVED:** nonempty source buffers, frame/sample totals and prepare callbacks; the buffers are passed back unchanged. |
| Silent bytes → decoder → PCM | **OBSERVED:** independent decoding produces zero-valued samples and the same engine observer produces real zero-valued buffers. |
| Processing callback → OS audio HAL | **ASSUMED:** no downstream HAL instrumentation. |
| OS audio HAL → speaker | **ASSUMED:** no physical-output observation. |

**OBSERVED:** the internal seam and entire observer implementation are under
`#if DEBUG`. No shipping call site installs a tap. This command also compiled the
macOS Release library:

```sh
swift build --package-path apple/DulcetKit --configuration release --target DulcetKit --scratch-path /tmp/dd-dulcet-audio-release
```

```text
Build of target: 'DulcetKit' complete! (15.47s)
EXIT_CODE=0
```

**OBSERVED:** `nm` plus `swift-demangle` on the Release engine and observer object
files found the engine's `execute` implementation and zero observer or seam symbols.
This symbol inspection covered macOS Release; a separate iOS Release build was not
performed. No feature-matrix promotion is included in this change.
