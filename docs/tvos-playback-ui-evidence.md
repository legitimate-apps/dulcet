# tvOS playback threshold and server attribution

OBSERVED 2026-09-06: the production Debug app on Apple TV 4K (3rd generation),
tvOS Simulator 26.5, Xcode 26.6 (17F113), passed
`DulcetTVUITests/DulcetTVUITests/testTVSimulatorPlaybackAdvancesPastScrobbleThreshold`.
The local result is not CI evidence; `FEATURES.yml` is unchanged.

## Experiment and observed chain

- OBSERVED: the test required a UUID-valued `SIMULATOR_UDID` and UIKit's runtime
  `userInterfaceIdiom == .tv`. The idiom distinguishes Apple TV from both iPhone
  and iPad without relying on overlapping window dimensions.
- OBSERVED: the existing DEBUG account-only launch hook connected to the disposable
  loopback fixture. The production connector and Keychain stored the account.
- OBSERVED: real remote events reached Search through the section bar, typed
  `Threshold`, dismissed the platform keyboard, and activated the focused canary
  at rank two. No destination or playback state was injected.
- OBSERVED: Now Playing identified `UI Playback Canary`, reported `Playing from
  Search`, and its progress indicator increased from `0:02 of 0:31` to
  `0:16 of 0:31`. The eligible track's §15.2 threshold is 15.5 seconds. The test
  neither seeks nor submits a scrobble.
- OBSERVED: a separate fresh Navidrome 0.63.2 data directory, used only for this
  playback experiment, passed the repository's health/corpus/ffmpeg preconditions.
  The reader asserted count zero before running the named test and count one
  afterwards. The count belonged to the same unique song and disposable user.
- OBSERVED: the xcresult execution guard required exactly one individual passing
  result for the named test, and passed. The server exited and the simulator lease
  was disposed; no simulator remained booted.
- ASSUMED / not measured here: the hosted runner will reproduce this local result.
  CI must execute before adding platform evidence rows. Acoustic output is outside
  this experiment and no claim is made about it.

## Invocation and receipts

The local shell exported `JAVA_HOME` for JDK 17, the Android SDK environment
required by the KMP build, and the disposable fixture environment. With the new
server healthy and the count-zero assertion complete, it executed:

```sh
TEST_RUNNER_DULCET_UI_TEST_SERVER_URL=http://127.0.0.1:4533 \
  TEST_RUNNER_DULCET_UI_TEST_USERNAME=dulcet-admin \
  TEST_RUNNER_DULCET_UI_TEST_PASSWORD=dulcet-ci-canary-password \
  xcodebuild test \
    -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
    -destination 'platform=tvOS Simulator,id=C64E7E95-F800-4CAD-9666-FEEA8C5D36E8' \
    -derivedDataPath /tmp/dulcet-tvos-proof/DulcetTVDerivedData \
    -resultBundlePath /tmp/dulcet-tvos-proof/dulcet-tvos-playback-2.xcresult \
    -parallel-testing-enabled NO \
    -only-testing:DulcetTVUITests/DulcetTVUITests/testTVSimulatorPlaybackAdvancesPastScrobbleThreshold
```

The named simulator was leased for this run and has since been disposed. A repeat
must supply its own tvOS simulator UDID and fresh result bundle/server directory.
The credentials above are the repository's published disposable fixture values.

```text
Executed 1 test, with 0 failures (0 unexpected) in 38.181 (38.182) seconds
DULCET TV PLAYBACK PASS simulator=C64E7E95-F800-4CAD-9666-FEEA8C5D36E8 idiom=tv title=UI Playback Canary initial=0:02 of 0:31 final=0:16 of 0:31 threshold=15.5
PLAY COUNT title='UI Playback Canary' id=cCukIwSlOZT7n1dh1H42O7 observed=0 expected=0 attempts=1 played=None
PLAY COUNT title='UI Playback Canary' id=cCukIwSlOZT7n1dh1H42O7 observed=1 expected=1 attempts=1 played='2026-09-06T23:35:35.728Z'
xcode test execution valid: test=DulcetTVUITests.DulcetTVUITests/testTVSimulatorPlaybackAdvancesPastScrobbleThreshold terminal=Passed individual-results=1
```

OBSERVED: the independent reader used `tools/read-play-count --expect 0` before,
and `--expect 1 --await-seconds 15` after. Both succeeded on their first attempt.
A subsequent read-only inspection of the stopped disposable database corroborated
an annotation for user `dulcet-admin`, item `cCukIwSlOZT7n1dh1H42O7`, count `1`,
play date `2026-09-06 23:35:35.728+00:00`. Attribution rests on the fresh database,
unique canary, sole app playback actor, and before/after assertions together;
it does not reuse an iPad, iPhone, or earlier tvOS search experiment's play.

## CI boundary and deliberate exclusions

The new apple-ci step creates its own data/cache directory and fails if it already
exists. It uses the pinned native Navidrome, asserts preconditions and count zero,
runs only the named tvOS playback control, asserts count one, and verifies actual
test execution before exporting JUnit. Its 30-minute timeout is a hang guard well
above comparable roughly 11-minute steps, not a cap inferred from this local run.
Existing tests, assertions, timeouts, and feature evidence are unchanged.

OBSERVED: CI policy, string-catalog policy, parity gate, promotion-condition
resolvability, execution-guard regression controls, and diff whitespace checks
passed locally. No push, pull request operation, CI dispatch, feature promotion,
production/personal server access, or acoustic-output measurement was performed.
