# tvOS search UI evidence and section navigation

OBSERVED on 2026-09-04 with Xcode 26.6.0, tvOS Simulator 26.5, and the disposable
Navidrome fixture at `http://127.0.0.1:4533`:

`DulcetTVUITests/DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack`
types a query into the production tvOS Search field, checks the field's own
value, observes the rendered Track buttons, focuses one with the remote and
presses Select, and observes the activated title, `Playing from Search`, and
advancing media time in Now Playing.

REVISED 2026-09-05: the control previously drove a query matching exactly one
row. With a single result, "rank zero" and "the only row" are the same
assertion, and so are "activate the row that was focused" and "activate the
first result", so the rank threaded through the result view was unobservable.
The query now matches four rows and the canary sits at rank two. Every
observation below that names a single result or rank zero describes that earlier
control and is retained as history, not as a description of the current one.

## Product gaps and the exact setup boundary

**CORRECTED 2026-09-05. The navigation gap described here is closed.** The
paragraph below described the tvOS root as it stood before this repository grew
a section bar, and is retained as the statement of the problem the bar solves,
not as a description of the shipped shell. See "Section navigation" below.

*Superseded:* OBSERVED, ordinary tvOS root navigation supplied no control that
selected Search. Source inspection of `DulcetRootView.swift` showed that tvOS
rendered only `DulcetStateSurface` inside a `NavigationStack`; the sidebar is
excluded on tvOS. The Search surface required `.search` to have already been
selected. The test's unseeded launch recorded a Connection screen with server,
username, password, local-HTTP toggle, and Connect controls, without a Search
navigation control.

**OBSERVED: the original DulcetTV entry point had no account launch hook.**
DEBUG-only `-dulcet-debug-connect-account` setup uses the live production
connector and Keychain store, restricted to the disposable loopback URL. It
supplies an account and nothing else: no destination, no query, no ranked
results, no activation, no queue, no playback state. It exists because typing
credentials raises a system save-password dialog no app-side query can reach,
which is a different thing from skipping the behaviour under test. Release
builds contain no such hook. Interactive account entry and production-signed
Keychain attributes remain outside this proof.

**REMOVED 2026-09-05: the `-dulcet-debug-open-search` destination hook.** It
existed only because the root had no way to reach Search. Leaving it in place
alongside a working control would let the control's own evidence pass whether or
not the control worked, because the app would already be on Search at launch, so
it is deleted rather than merely unused by the test.

OBSERVED: rendering results originally crashed the production tvOS root with:

> Fatal error: No Observable object of type DulcetPresentationStore found.

The simulator's application log reported that assertion after typed input, and
the crash report recorded `EXC_BREAKPOINT` / `SIGTRAP` in SwiftUI's environment
lookup. `DulcetArtworkView` reads the store from its environment, while the tvOS
root omitted the `.environment(store)` supplied by the other Apple roots. The
one-line production fix adds that environment value. The same activation probe
then passed. The account-only tvOS rendering tests had not exercised this path.

OBSERVED: building the initial probe with `CODE_SIGNING_ALLOWED=NO` reached a
live server success but rendered `The account could not be saved` and
`The server accepted the account, but the system Keychain did not save it.`
Rebuilding with normal simulator signing enabled allowed account setup and typed
entry. The control therefore requires simulator signing; it does not substitute
an in-memory credential store or silently skip a failed connection.

## Observed text entry, activation, and playback

The signed entry probe observed the field's exact `UI Playback Canary` value
after remote Select and `typeText`. Therefore a negative claim that tvOS text
entry cannot be automated is disproved by this run. Remote-only character entry
was not needed. The final control does use remote navigation to focus the system
keyboard's `done` button, then presses Select and checks that the keyboard closes
without changing the app field's value.

OBSERVED final green build: `FINAL_BUILD_EXIT=0`, `** TEST BUILD SUCCEEDED **`.
OBSERVED final green execution: `GREEN_EXIT=0`, `** TEST EXECUTE SUCCEEDED **`,
`Executed 1 test, with 0 failures (0 unexpected) in 19.970 (19.972) seconds`.

The test emitted:

```text
DULCET TV QUERY value=UI Playback Canary input=typeText
DULCET TV RANK0 label=UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track
DULCET TV SEARCH PASS query=typed rank0=rendered activation=remote-select source=search title=UI Playback Canary progress=0:02 of 0:31->0:03 of 0:31 setup=debug-account-and-destination
```

OBSERVED named-test guard:

```text
xcode test execution valid: test=DulcetTVUITests.DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack terminal=Passed individual-results=1
```

## Ranked rendering, OBSERVED 2026-09-05

The four-row query passed with build exit 0 and test exit 0. All four ranked rows render on
the tvOS layout, each under its own identifier, and the control activated the canary at
rank two:

```text
DULCET TV SEARCH PASS query=typed ranks=["Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track", "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track", "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track", "Threshold Boundary, Dulcet Fixtures, Album"] activated-rank=2 activation=remote-select source=search title=UI Playback Canary progress=0:02 of 0:31->0:04 of 0:31 setup=debug-account-and-destination
Executed 1 test, with 0 failures (0 unexpected) in 24.173 (24.174) seconds
xcode test execution valid: test=DulcetTVUITests.DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack terminal=Passed individual-results=1
```

**FINDING, OBSERVED: the rendered order is the app's, not the server's.** For this query the
server returns one album and three songs and lists the album ahead of every track. The app
re-ranks by match quality, then by kind with tracks ahead of albums, then by the order the
server returned them, so the album that arrives first renders last. The control asserts the
whole order rather than assuming the server's.

The rank-two row is a 31-second track and so is rank zero, so the duration in the progress
receipt cannot distinguish them. The title assertion is what does, which is why Now Playing
is checked against the canary's title and not only against advancing media time.

## Identifier mutation control, OBSERVED 2026-09-05

The row identifier shared by the tvOS and touch layouts was temporarily replaced with the
constant `dulcet.search.result.0`, so every row claimed rank zero. No test assertion changed.
`build-for-testing` returned exit 0; the mutation run was gated on that recorded zero exit
before `test-without-building` was invoked, because a mutant that does not compile re-runs the
previous binary and reports a pass. The run then returned exit 65, failing at
`Rank 1 must render its own identifier`.

A second mutation changed the row action from activating the pressed row to activating the
first result. `build-for-testing` returned exit 0 and the run returned exit 65:

```text
XCTAssertEqual failed: ("Thirty One Seconds") is not equal to ("UI Playback Canary") - Now Playing must show the row that was activated, not the first result
```

Both mutations were exercised on the touch layout, which shares this source with tvOS; the
production source was restored and verified byte-identical afterwards. Under the previous
single-result query neither mutation could fail any of these controls, which is the coverage
gap they were written to close.

## Section navigation, OBSERVED 2026-09-05

tvOS now renders a section bar across the top of the root, above the state
surface, offering Library, Search, Now Playing and Connection. Its controls carry
`dulcet.tab.<destination>` identifiers, naming the platform role this bar fills. Each control asks
the store to change destination through the same reducer path the sidebar uses on
the other platforms, so the per-destination work that reducer does -- cancelling a
library browse, cancelling an in-flight search request, re-deriving the playback
presentation -- happens exactly as it does elsewhere.

The control now starts where a person starts and reaches Search by remote:

```text
DULCET TV LAUNCH section=Connection focus=dulcet.account-connect.server-address search-present=false
DULCET TV REACHED-SEARCH via=section-bar control=dulcet.tab.search
DULCET TV SEARCH PASS query=typed ranks=[...] activated-rank=2 activation=remote-select source=search title=UI Playback Canary progress=0:02 of 0:31->0:03 of 0:31 reached-search=section-bar returned-to=library setup=debug-account-only
Executed 1 test, with 0 failures (0 unexpected) in 35.818 (35.820) seconds
xcode test execution valid: test=DulcetTVUITests.DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack terminal=Passed individual-results=1
```

Green build exit 0 and test exit 0. The same run exercised the return leg: after
playback moved the app to Now Playing on its own, the bar took it back to Library,
which is the half a person needs after playing one track.

### Reachability mutation controls

Both mutations were gated on a recorded zero build exit before the run, because a
mutant that does not compile re-runs the previous binary and reports a pass.

| mutation | build exit | test exit | failed at |
|---|---|---|---|
| section bar removed from the tvOS root | 0 | 65 | `The unseeded root must offer the library section` |
| section control renders but selects nothing | 0 | 65 | `Selecting Search in the section bar must present the search surface` |

The second is the one that matters. A bar that exists but drives nothing still
satisfies every assertion about the bar itself, and the control still fails,
because reaching Search is what it asserts. The production source was restored
afterwards and verified identical by digest; the restored build then reported
build exit 0 and test exit 0, with 69 tvOS rendering tests in 3 suites passing in
the same run.

### FINDING, OBSERVED: `TabView` does not hold a section the app chooses for itself

The first implementation expressed the bar as a `TabView` with its selection bound
to the store, which is the obvious shape for this. Activating a search result
moved the app to Now Playing, which rendered -- and was then replaced by the
section the person had been on, roughly a second later, because the tab view
restored its own selection over the one the store had published:

```text
post-select 0 navBar=Now Playing results=false
post-select 1 navBar=Now Playing nowPlayingTitle=true
post-select 3 navBar=Search results=true
```

Two different bindings were tried -- reading the store inside the getter, and
reading it during body evaluation so observation registers the dependency -- and
both behaved identically, so this is not a missing dependency. The shipped bar
owns no selection of its own: the destination the reducer publishes is the only
one, and the bar renders it.

### FINDING, OBSERVED: an always-installed exit handler removes the way back

The account surface installed `onExitCommand` unconditionally and acted only while
a connection was in flight. On tvOS an installed handler consumes the exit press
whether or not it does anything, so the press that takes a person from a surface
back to the section bar was swallowed on that surface. The handler is now supplied
only while it has work to do; a nil action leaves the press to the system.

### Focus behaviour

- OBSERVED: launch places remote focus on a named control inside the section --
  the Connection surface's own field -- rather than on the bar or on nothing.
- OBSERVED: from the Search surface, one Up press returns focus to the bar; from
  the bar, one Down press reaches the search field.
- OBSERVED: from the album grid on the Library surface, Up does not reach the bar
  within 10 presses. The exit press does, and the shipped root handles it by
  returning focus to the bar directly rather than leaving the focus engine to find
  a control several scroll views away. From the bar the exit press stays unhandled,
  because there the platform's own meaning is to leave the app.

## Reproduction and CI

Generate the project from `apple/project.yml` with `xcodegen generate` in `apple/`.
Supply an Android SDK through `ANDROID_HOME` and `ANDROID_SDK_ROOT`, because the
Xcode build invokes Gradle through a run-script phase. Choose a DerivedData
directory on the same physical volume as the system and resolve symlinks before
trusting the path: a test whose DerivedData resolves onto an external volume can
launch its host and then execute nothing, which reports as a pass rather than as
an error. Run against a single tvOS simulator with `-parallel-testing-enabled NO`,
because `xcodebuild test` otherwise clones the destination and the clone failure
reads as a test failure. Preserve simulator signing.

With `DERIVED_ROOT` and `TVOS_UDID` supplied, and the disposable fixture running:

```sh
export TEST_RUNNER_DULCET_UI_TEST_SERVER_URL=http://127.0.0.1:4533
export TEST_RUNNER_DULCET_UI_TEST_USERNAME=dulcet-admin
export TEST_RUNNER_DULCET_UI_TEST_PASSWORD=dulcet-ci-canary-password
xcodebuild build-for-testing \
  -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
  -destination 'generic/platform=tvOS Simulator' \
  -derivedDataPath "$DERIVED_ROOT" -parallel-testing-enabled NO \
  -only-testing:DulcetTVUITests || exit "$?"
xcodebuild test-without-building \
  -project apple/Dulcet.xcodeproj -scheme DulcetTV -configuration Debug \
  -destination "platform=tvOS Simulator,id=$TVOS_UDID" \
  -derivedDataPath "$DERIVED_ROOT" -parallel-testing-enabled NO \
  -resultBundlePath "$RESULT_BUNDLE" \
  -only-testing:DulcetTVUITests/DulcetTVUITests/testSimulatorSearchQueryRanksAndActivatesTrack \
  || exit "$?"
python3 tools/verify-xcode-test-execution "$RESULT_BUNDLE" \
  DulcetTVUITests.DulcetTVUITests testSimulatorSearchQueryRanksAndActivatesTrack
```

OBSERVED in `.github/workflows/apple-ci.yml`: the new control runs inside the
existing disposable-fixture shell, after the iOS/iPadOS search controls. It has
an exact named-test execution guard and a JUnit conversion; the JUnit directory
is included in parity evidence verification and existing failure-artifact globs.
Missing fixture environment, wrong server URL, missing field, wrong result,
unfocused activation, and non-advancing playback all fail the control.
The complete hosted workflow has not been run by this investigation.

`FEATURES.yml` is unchanged. Its evidence-list schema requires a conformance ID;
this UI control is not CONF-41's local/server merge test, so no misleading
CONF-41 evidence entry is added. Promoting any cell on the strength of the
evidence below is a separate decision and is not taken here.

## Activation mutation control

OBSERVED: the non-macOS result Button action was temporarily changed from
`onActivateResult(result.id)` to an empty closure. No test assertion changed.
`build-for-testing` returned `MUTATION2_BUILD_EXIT=0` and
`** TEST BUILD SUCCEEDED **`. The mutation run was gated on that recorded zero
exit code before invoking `test-without-building` against the same DerivedData.

The mutated binary still emitted the exact typed-query and rank-zero label
receipts. After focus was checked and remote Select was pressed, the test failed
at the activation outcome:

```text
XCTAssertTrue failed - Activating rank zero must present Now Playing
Executed 1 test, with 1 failure (0 unexpected) in 30.663 (30.666) seconds
```

OBSERVED completed mutation execution: `RED_FINAL_EXIT=65` and
`** TEST EXECUTE FAILED **`. The exact named-test guard rejected this bundle:
`xcode test execution invalid: named control terminated as Failed, not Passed`,
with `RED_GUARD_EXIT=1`.

The completed mutation run used `-collect-test-diagnostics never`, which Xcode
help documents as controlling verbose diagnostics such as sysdiagnose. An earlier
run reached the same expected failure but stalled during diagnostic collection;
it was terminated with exit 143 and is not used as completed-result evidence.
The retry rebuilt the same no-op successfully and changed no test assertions.

The production action was restored after that failure. The no-op mutation is not
committed. This is an executed behavioral negative control, not a compilation
failure or a rerun of a previously passing binary.

OBSERVED restored build: `RESTORED_FINAL_BUILD_EXIT=0`,
`** TEST BUILD SUCCEEDED **`. A fresh simulator then ran the restored binary:
`GREEN_RESTORED_EXIT=0`, `** TEST EXECUTE SUCCEEDED **`,
`Executed 1 test, with 0 failures (0 unexpected) in 19.830 (19.832) seconds`.
The exact named-test guard again reported `terminal=Passed individual-results=1`,
and the test again observed `progress=0:02 of 0:31->0:03 of 0:31`.

OBSERVED repository validation after restoring the production action:

- `python3 tools/parity_gate.py`: `parity gate valid: 6 feature rows`.
- `python3 tools/verify_ci_policy.py`: `CI policy valid across 6 workflows`.
- `actionlint .github/workflows/apple-ci.yml`: exit 0, no diagnostics.
- Regenerating in `apple/` and comparing the pbxproj byte-for-byte: `cmp` exit 0.
- `git diff --check`: exit 0, no diagnostics.

The production environment fix is the commit "Stop tvOS search-result artwork from
crashing the app"; the passing control, DEBUG setup boundaries, generated target
and CI wiring are the commit "Exercise tvOS search typing and remote activation
against the disposable server". They are named rather than cited by hash because
this branch is rebased onto `main` before merging under `strict: true`, which
rewrites every hash on it - the previous revision of this line cited two hashes
that no longer existed in any published history.
