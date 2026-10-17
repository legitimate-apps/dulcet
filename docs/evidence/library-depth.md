# Library depth

## Implementation and evidence status

The latest native iPhone proof passed **3 tests with zero failures** at `6d3ab4b1`,
including the corrected compact artist header, per-group search count and offline pin
relaunch. The corrected hosted iPad My Library proof at `128b9d39` passed **5 tests
with zero failures in 490.508 seconds**, with all five exact methods confirmed by its
execution guard. Its full conformance job passed. The corrected Mac and hosted iPhone
legs have not yet supplied final results.

At the final Android runtime source `dcc2ff38`, **required core CI and parity passed**,
including Linux production search, both native emulator jobs, and both Downloaded
completed-file proofs. A newer guard-only run at `44c18c74` failed a 660-second core-test
watchdog before the required aggregator could consume the new Downloaded identities.
The test coordination correction at `a076bbf3` subsequently passed all required
core jobs and the aggregate method-identity guard. The final TV native grouped-search D-pad and ordinary-Library
fallback cases also passed. The final TV production soak passed **20 repetitions,
560 test executions, zero failures and zero skips**. Its separate regular host-test
soak passed **20 repetitions, 240 test executions, zero failures and zero skips**.
The earlier TV 19/1 production soak is historical.

In `FEATURES.yml`, Downloaded is **shipped on Android phone and Android TV**, following
the required native proofs and aggregate method-identity guard. Downloaded on Apple TV
is **n/a** under the existing tvOS policy. The other 33 new platform cells remain
**partial**; their category, item-kind and platform gaps are stated per cell. Source implementation
and shared-core tests alone do not establish native behavior. Historical failures,
causal findings and corrected-revision results are retained below.
The existing Library, My Library and Search rows retain their earlier evidence and statuses.

The native surfaces cover category controls, Downloaded, pins, shuffle entry points,
artist Top Songs and discography, and grouped search with recent queries. The phone,
tablet, desktop and TV shells share account-scoped policy in the Kotlin core.
The My Library/Browse split appears only when the server advertises it; Browse is the
server catalog, while My Library contains the account's saved albums, artists and songs.
Adding an album also adds its songs. Pins and favorites are independent of membership.

## Sort and filter scope

Album ordering uses `getAlbumList2`: title, artist, newest additions, most played and
descending `byYear`. Year ordering is the server's order; an original release date does
not turn that endpoint into a guaranteed day-by-day global sort. Servers can omit years,
release dates, play counts or favorites. The client cannot infer those missing values.

Songs use offset-paged `search3` with an empty query. Some servers do not enumerate songs
for that query. An error, or an empty answer when cached tracks are known, can expose
seen tracks as a cached local view with incomplete coverage. It is not a whole-library
mirror or a claim that cached tracks still exist on the server.

Local sorting and filtering act on the loaded window or known collection. Paging retains
server offsets, and local ordering is labeled as a local view. Loading more can change
the relative order. My Library sorting acts on its saved membership snapshot; playlist
filtering matches names. Category choices persist on this device for the bound account.

## Downloaded

Downloaded reads completed local download identities and retained track metadata, without
requiring a server request. It excludes tracks that are merely cached or streamable.
Removing a local download removes it from the view. Downloaded filters for albums and
artists use the known relationships of completed tracks; they do not require a previously
opened album detail. This does not mean every song in a matching album is downloaded.

Apple TV has no durable user-managed media download contract in v1. Its production
reader does not read downloads, and the new Downloaded link and filter are hidden there.
The shared TV proof requires the positive pinned-item control, then checks that Downloaded
is absent; it does not claim downloaded-media reachability or playback. Android TV
supports Downloaded and retains its separate completed-download proof.

The shared Apple proof below currently checks that the destination is reachable; it does
not download a track or establish offline playback. The Android phone proof
`AndroidEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`
is designed to use the existing real download fixture, cut its relay and open/play the
Downloaded row. The equivalent TV method is
`AndroidTvEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`.
The first phone attempt reached the offline Downloaded canary row, then failed at the
emulator screenshot API before playback. A later TV run at `5c37ea58` captured that row
and activated it, but its independent playback sample advanced only 708 ms in 2.5 seconds,
below the required 1,500 ms, so the complete proof failed. These observations establish
row reachability only for those earlier artifacts. The final phone run at `70bcc4e0`
passed **one test in 112.489 seconds** (`android-phone-runtime3.log`). After the relay
was cut and the control request refused, the production Downloaded row started playback;
the independent observer required at least 1,500 ms of advancing media time, reported
`playing-download=true` at media position 2,264 ms, observed zero server requests during
playback, and rechecked identical file bytes. Sign-out left zero files and zero unfinished
tasks. The captured Downloaded surface shows the canary, All/Favorites controls and local
offline copy, without network retry or a redundant Downloaded filter.

The final TV run of the equivalent method passed **one test in 61.967 seconds**
(`android-tv-runtime3.log`) with the phone emulator stopped. TV focus and D-pad activation
started the offline row. The same independent observer required at least 1,500 ms of
advancing media time, reported `playing-download=true` at position 1,947 ms, observed
zero server requests during playback and identical file bytes, and confirmed zero files
and unfinished tasks after sign-out. Its saved image shows the focused canary under
Downloaded, local offline copy, All/Favorites, sorting and Shuffle All; network retry and
the redundant Downloaded filter are absent. These results establish this completed-file
path on both Android shells; they do not establish every other library-depth interaction.
Both methods subsequently executed and passed in required core CI at `dcc2ff38`,
as recorded below. This revision adds their explicit required-verifier identities;
the next required run must prove that guard is consumed before their feature cells
are promoted.

The final Android APKs were assembled from source through `70bcc4e0`. Their SHA-256
identifies the runtime artifacts independently of later evidence-only commits:

| APK | SHA-256 |
| --- | --- |
| Phone app | `3151fd4db9f45f9046ede18534d7426b3834438d377b24ea642e5a4b1990aba8` |
| Phone instrumentation | `9accadd21c36fc334f9dc8516a6dfdd3288a4cf88ac0ee43187888a7a40d6123` |
| TV app | `258b139bc874d4bbe4fb6beb3f7ecba3c9c5b9c14cd9f969d0a03cb61de18edb` |
| TV instrumentation | `4a8f363a3e0ea61e5b14b2242c9b2af8699c688a2ff2af0a6acc84b2417283c4` |

## Pins

**Decision: pins live on this device, per server account.** They are not sent to Dulcet
Server and do not sync across devices. This provides the same behavior on ordinary
OpenSubsonic servers and Dulcet Server without introducing a new server mutation or
changing My Library membership. A change of account binding clears the old namespace.

Albums, artists, playlists and songs can appear at the top of Library. Ordinary cache
eviction retains pinned entity metadata. A pin is a shortcut, not a download: its child
list may need another server read, and it does not make audio available offline.

## Shuffle

Online Library/Songs shuffle uses `getRandomSongs` with a requested size of **500**, then
shuffles that returned selection. Each action requests a fresh selection. This lets the
server choose across its eligible catalog without walking every song or restricting the
selection to the visible page. Servers can return fewer tracks or apply their own
eligibility rules; this is a bounded queue, not a guarantee that every library song is
enqueued. Offline, Downloaded and My Library entry points use their available local or
saved collection scope. The UI explains the server-random limit.

## Artist depth

Top Songs requests `getTopSongs` using the artist name. If the server does not answer
with songs, the fallback ranks known tracks for that artist using the account's plays
and favorites. It is limited to metadata seen on the device, not a global popularity list.

Discography is newest-first using `originalReleaseDate`, with year as the fallback.
Albums with neither have an unknown date and follow dated releases. `releaseTypes`
supports Albums, EPs & Singles and Compilations; albums credited to another artist can
appear under Appears On. Missing release types cannot be reconstructed reliably, so the
UI states the limitation and leaves those releases under Albums. The server's artist
response determines which releases are available to group.

## Search groups and recent queries

Search previews group Artists, Albums and Songs, with See All for each group. Expansion
preserves result identity and shows that group's available response; it does not promise
an exhaustive server result set beyond the search contract. Submitted recent queries
persist per account on this device, can be replayed and can be cleared. They do not sync
to a server. The new shared Apple search proof excludes tvOS; TV search runtime evidence
is still required separately.

## Required Apple execution paths

`tools/ci/run-my-library-proofs` adds these exact selectors and requires each selected
method to appear once with Passed status in the individual xcresult records before JUnit
conversion. These are execution requirements, not observed results.

| Platform | Producer in `.github/workflows/apple-ci.yml` | JUnit class | New methods | Expected total My Library cases |
| --- | --- | --- | --- | --- |
| iPhone | `apple-conformance-ipad-iphone`, iPhone matrix member | `DulcetMyLibraryIPhoneUITests` | Both shared methods and iPhone-only method below | 7 |
| iPad | `apple-conformance-ipad-iphone`, iPad matrix member | `DulcetMyLibraryIPadUITests` | Both methods below | 5 |
| Mac | `apple-conformance-core` | `DulcetMyLibraryMacUITests` | Both methods below | 5 |
| Apple TV | `apple-conformance-core` | `DulcetMyLibraryTVUITests` | Pin/destinations method only | 4 |

- `testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable` pins an album,
  relaunches, checks the pinned metadata and opens Songs before unpinning. On iPhone,
  iPad and Mac it also opens Downloaded; Apple TV instead checks that Downloaded is absent.
- `testLibrarySearchGroupsAndRecentQuerySurviveRelaunch` checks the three group controls,
  expands Artists, submits a query, relaunches and replays the saved query.
- `testIPhoneLibrarySortShuffleAndArtistDepth` runs only on iPhone. It chooses Release
  Date, verifies the year-ordering limitation copy and persisted menu selection after
  relaunch, opens an artist and checks Top Songs plus an album in the discography, then
  invokes Songs Shuffle All and requires a mini-player queue surface. It does not assert
  exact discography order, every release group, random-selection eligibility or audible
  playback.

The required `apple-ci` aggregate consumes each platform's JUnit. A future matrix evidence
citation uses workflow/job `apple-ci`/`apple-ci` and the platform-specific JUnit class plus
the exact method above. The selectors alone establish no runtime behavior. Even after
the new iPhone method passes, completed offline playback, exact shuffle eligibility,
discography ordering and release grouping need their own observations; the broad rows
remain partial until the corresponding platform claims have adequate runtime evidence.
Existing visual-proof attachments continue to be exported by the same CI driver.

## Shared policy checks

`LibraryDepthTest` covers account separation and rebind persistence; bounded/deduplicated
recents; release metadata parsing and request ordering; discography ordering/grouping;
downloaded-only publications; user-pin eviction protection; Top Songs fallback; song
paging and incomplete fallback; category policy; absolute positions after local sorting;
My Library notification without network reads; fresh shuffle actions; and download
relationships without album-detail membership. These are core observations only.
The implementation owner records actual execution counts separately; this document
does not convert source inspection into a platform pass.

## Local validation on 2026-10-16

The implementation owners reported these **OBSERVED** local results; the documentation
reviewer did not rerun the suites:

- The serial DulcetKit Swift package run passed **334 tests in 11 suites**, including
  account-generation guards, retained preferences after a storage failure, category
  mutation forwarding and waiting for a fresh shuffle response. This run preceded the
  final local-playlist pin guard and personal/catalog labeling clarifications.
- The full core JVM run passed **1,078 tests**, with zero failures or errors, including
  15 `LibraryDepthTest` cases. Android compilation, iOS Simulator common-test compilation,
  SQLDelight migration-task verification and Mac/iOS Simulator/tvOS Simulator
  debug-framework generation passed in that gate. It did not execute the separate
  Python historical gate, `tools/migration_gate.py`. Those framework builds preceded
  the final filtered-empty paging and default-category helper changes.
- A later focused `LibraryDepthTest` run passed **16 tests**, including explicit Load More
  progression through an empty filtered page. The final focused run then passed **18
  tests with zero failures or errors**, covering default category ordering and retained
  write failures until recovery. Android and iOS Kotlin compilation also passed in that
  final gate. The earlier 1,078-test full run preceded these small policy/error-helper changes.
- Android follow-up host checks at `5c37ea58` passed **7 tests with zero failures**:
  five phone tests and two TV tests. The phone app, TV app and both instrumentation APKs
  compiled, **four APKs** in total. These are compilation and host-test observations;
  the separate emulator runtime proofs were still pending.
- The final Android source at `70bcc4e0` passed **5 `PhoneLibraryDepthTest` cases**,
  with zero failures or errors, including the initial asynchronous preference snapshot,
  rapid persisted category/pin intents, Downloaded local-only controls, pin/unpin and
  server-wide shuffle selection. Both production apps and both instrumented APKs rebuilt.
  A preceding pin test timed out after the pin had been stored but before its home
  shortcut appeared. The initial isolated rerun and complete five-case class passed;
  the later soak exposed the repeated failure and led to the causal regression and
  correction recorded below. The earlier host counts overlap the soak-selected cases.
- Native Mac production integration compiled successfully, including the final Swift
  bridge and latest Kotlin framework (`mac-integration-final.log`, observed by the owning
  implementation agent). The Mac UI attempt was blocked before test execution: all
  887 sampled main-thread stacks remained in `dyld` → `libSystem_initializer` →
  `_libsecinit_appsandbox` → XPC `mach_msg`, before application main. No test case began
  and no result was emitted. The owner canceled only its fixture and runner; this is a
  prelaunch host blocker, not an application-test failure.
- `tools/typecheck-xcuitest-sources --self-test` passed for all five configured UI-test
  directories, including the four iOS and four tvOS source files. This checks standalone
  source types with the tool's XCTest assertion shims; it does not link or execute tests.
  `tools/verify_xcodegen_regeneration --self-test` also passed.
- After the bounded Apple source review, the focused library-depth regression run passed
  **3 tests with zero failures in 0.060 seconds** at `ebad7fae`, after the final Downloaded
  status and shuffle-header changes (reported by the Apple implementation owner). It
  includes the rapid category-change, reversed-read-callback and double-pin regression.
  The method name was corrected from `libraryDepthRapidChangesKeepConfirmedWritesAndIgnoreOlderReads` to
  `libraryDepthRapidChangesKeepAcceptedWritesAndIgnoreOlderReads`; that rename changes no
  behavior. These are package tests, not native UI execution.

The review's local-state finding is resolved in source and the focused regression:
accepted category/pin writes update session state immediately, and refresh callbacks
must match both the account generation and latest read token. The new TV discography
also restores each grid's full-width focus section; its remote behavior remains unverified.

The new iPhone UI proof attempt failed **before compilation**: Xcode could not resolve
the selected booted simulator destination. A subsequent 20-second `simctl` device-types
probe produced no output and exceeded its deadline; the simulator device store remained
mounted. This is a host execution blocker, not a diagnosis of an application failure.
**No simulator behavior was verified by that attempt.** Native Mac production integration
compilation subsequently passed; the Mac UI runner was blocked before application main,
as recorded above. The final Android phone and TV completed-download proofs passed.
The hosted Android host-test soak results and the incomplete Apple CI observations are
recorded below. No broad platform status is promoted on these bounded results.

## Hosted validation

[Apple CI run 38993971381](https://github.com/legitimate-apps/dulcet/actions/runs/38993971381)
was dispatched for `705a2dc0`, including the iPhone-only library-depth selector and
execution guard. The earlier run for `14de7a41` was canceled while all four Apple
platform/conformance legs were still queued; it supplied no native runtime result.
The iPad My Library suite executed **5 tests with 1 failure in 452.068 seconds**.
`testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable` failed at
the offline-relaunch album-row assertion. The failure accessibility hierarchy contained
the Pinned heading, followed directly by the device-storage caption and Songs link,
with no album row anywhere in the hierarchy. This establishes an empty rendered pinned
region after a saved pin; it does not establish the underlying metadata or publication
cause. The later `testLibrarySearchGroupsAndRecentQuerySurviveRelaunch` passed in
**59.758 seconds**, a bounded iPad runtime observation from this otherwise failed suite.
The CI execution guard and JUnit conversion did not run after the failing suite.

The [Mac/core conformance job](https://github.com/legitimate-apps/dulcet/actions/runs/38993971381/job/117282299260)
failed while building DulcetTV: `DulcetLibraryDepth.swift:207` used the `.roundedBorder`
text-field style, which is unavailable on tvOS. It stopped before the Mac UI proof.
The [iPad conformance job](https://github.com/legitimate-apps/dulcet/actions/runs/38993971381/job/117282299242)
failed its proof step as described above. The remaining iPhone leg was canceled while
still compiling its shell; the platform leg had not started. Neither supplied a native
result. These are actual build/test failures, separate from the local host blockers.

An initial replacement Apple run was dispatched for `bea646f3`, then canceled while
all four Mac-hosted legs were still queued: the PR boundary gate had exposed missing
machine-readable promotion conditions in the new partial feature cells. Commit
`7b2675e7` added **35 explicit blocked promotion conditions**, preserving every feature
status and stating the missing platform/native evidence for each claim. The local
boundary, promotion-condition and parity checks passed, followed by the
[required PR parity gate](https://github.com/legitimate-apps/dulcet/actions/runs/38996397557/job/117289810238).

[Current Apple CI run 38996405838](https://github.com/legitimate-apps/dulcet/actions/runs/38996405838)
was dispatched for `7b2675e7`, which includes the same Swift and core fixes plus the
corrected promotion conditions. The TV fix selects a tvOS-supported text-field style.
The pinned region now provides a stable view while its reader window loads; failure
to mount the initial empty reader content is a suspected cause, pending native
verification. A new core offline-reopen pinned-publication regression passed **1 test
with zero failures in 7 seconds**, reported by the implementation owner. That core
result does not establish that the native pinned row renders.

The replacement [Mac/core job](https://github.com/legitimate-apps/dulcet/actions/runs/38996405838/job/117289872668)
passed the TV compile that had previously failed, then executed the Mac My Library
suite: **5 tests with 2 failures in 223.746 seconds**. The offline pin case failed at
its Pinned-heading query in **38.484 seconds**. Its failure accessibility hierarchy and
recording nevertheless show the Pinned heading and the `Threshold Boundary` album row
after offline relaunch. That is positive evidence that the pin metadata rendered in this
run, and counterevidence to diagnosing this Mac assertion as lost pin data. The reason
the query failed remains unresolved; the captured accessibility tree marked the app and
window disabled, while the recording showed no modal or system prompt. The resulting
source correction gives the heading a stable accessibility identifier, retains the same
20-second positive wait and adds an offline pinned-region capture. Its runtime effect
has not yet been verified.

The Mac grouped-search case failed in **60.467 seconds** after the application crashed.
The exported application console supplies the concrete fatal message:
`No Observable object of type DulcetPresentationStore found.` It occurred while the
search result table was being displayed. The crash stack passes through SwiftUI's
required environment lookup. These observations establish a missing store environment;
they do not independently identify which table cell or auxiliary hosted view lost it.
The resulting source correction explicitly injects the store into Mac table cells and
menu content. A new native AppKit hosting regression at `c40a6847`,
`MacGroupedSearchHostingTests.readerResultsRenderThroughTheRootAndNativeTable`, passed
**2 parameterized cases with zero issues in 1.388 seconds**, after an **8.46-second**
compile, reported by the implementation owner. It hosts the actual `DulcetRootView`,
uses 15 native two-column preview rows, and checks the first artist and scrolled last
song artwork consumers. The test host adds no store environment; the root supplies it.
This is a native component-hosting result, not the failed end-to-end search flow. No
without-injection baseline experiment was performed, so it does not independently
prove the specific source of the earlier missing environment. A hosted UI rerun is
still required to establish that the observed crash is resolved. The subsequent execution guards and JUnit conversions did not run after the failed
suite. This is an application runtime failure under the hosted proof recipe, separate
from the earlier local pre-main sandbox blocker.

The same run's [iPhone job](https://github.com/legitimate-apps/dulcet/actions/runs/38996405838/job/117289872787)
executed **7 tests with 2 failures in 541.441 seconds**. The three new library-depth
cases passed: sort/artist/shuffle in **83.359 seconds**, pin/offline destinations in
**75.033 seconds**, and grouped search/recents in **37.933 seconds**. The suite still
failed: the existing Browse/add/remove/offline-replay case timed out waiting for an
album after its final Library navigation, and the existing appearance case timed out
waiting for a catalog album in the largest-text variant.

Both failure hierarchies show the expected destination. The offline Library case has
its Recently Added heading at y=900 on a 402×874 viewport, with no album tile composed;
its final activities navigate to Library and wait 20 seconds without scrolling. The
largest-text Browse case has its Recently Added section at y=2274; its final activities
navigate to Browse and wait 30 seconds without scrolling. Compressed recording frames
show those same pages without a modal: the Library category links fill the first view,
and the enlarged Server Catalog explanation fills the Browse view. These observations
support a missing test scroll/reveal step; they do not independently establish the
album data below the viewport. The suite stopped before its execution guard and JUnit
conversion, so the three bounded method passes are not an overall proof-bundle pass.

After that iPhone proof step terminated, the old run was canceled while its platform
job was testing tvOS and the iPad job had only reached simulator first boot. No iPad
native result is claimed from that run. The corrected-source
[Apple run 39002109762](https://github.com/legitimate-apps/dulcet/actions/runs/39002109762)
was dispatched at `f134d55b`, then canceled while all four native jobs were still
queued. The next change adds a phone-only test helper that performs up to eight real
upward touch swipes until the album tile exists and is hittable before those exact two
assertions. It retains the original 20- and 30-second existence checks and does not
change the Mac, iPad or TV tests. Standalone UI-source typechecking passed all five
directories. This source correction has not yet supplied an after-scroll native pass.
[Apple run 39002314622](https://github.com/legitimate-apps/dulcet/actions/runs/39002314622)
was dispatched at `128b9d39` for that correction. Its corrected iPad result is below;
the corrected hosted iPhone and Mac results remain pending.

The corrected [iPad conformance job](https://github.com/legitimate-apps/dulcet/actions/runs/39002314622/job/117307676830)
at `128b9d39` completed **successfully**. My Library executed **5 tests with zero
failures in 490.508 seconds**: offline replay **204.585 seconds**, appearance
**91.847 seconds**, pin/offline destinations **104.146 seconds**, grouped search/recents
**67.886 seconds**, and ordinary-server navigation **22.045 seconds**. The guard
reported five individual terminal `Passed` results. This supplies the previously missing
iPad observation of the pinned album after offline relaunch; the earlier failed revision
remains historical evidence. The broader job emitted 17 successful native execution
guards totaling **21 individual terminal `Passed` results**, including My Library's five.
That count excludes other package/conformance checks and does not establish Apple
completed-file playback through Downloaded. Other corrected Apple jobs remain pending.

The hosted Mac My Library recipe targets the disposable `DulcetMyLibraryMacHost`, built
from the production Mac and shared Swift sources. `tools/ci/run-my-library-proofs`
sets `CODE_SIGN_STYLE=Manual`, `CODE_SIGN_IDENTITY=-` and an empty
`CODE_SIGN_ENTITLEMENTS` for that UI run. This ad-hoc, entitlements-cleared proof is
separate from the earlier local launch blocked during sandbox initialization. A passing
hosted UI result would establish the tested app paths under that proof host; it would
not by itself establish signed production sandbox behavior.

## Bounded iPhone runtime observation

The implementation owner reported a separate native iPhone run at `3f8256f6`, with
**3 tests passed, zero failures in 151.013 seconds**. The per-test execution guard
required three terminal `Passed` results. The production Swift sources matched the
pinned-loading and TV-style fix used by the replacement hosted run.

- `testIPhoneLibrarySortShuffleAndArtistDepth` passed in **62.536 seconds**: selecting
  release-date sorting survived relaunch, the artist Top Songs and discography appeared,
  and Shuffle All populated the actual playback queue.
- `testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable` passed in
  **57.851 seconds**: a real HTTP cut and saved-account relaunch retained the pinned
  heading and album metadata, Songs and Downloaded destinations opened, and restoring
  connectivity allowed unpinning.
- `testLibrarySearchGroupsAndRecentQuerySurviveRelaunch` passed: Artists, Albums and
  Songs groups, the See All path and recent-query persistence were exercised.

These are implementation-owner runtime observations. They do not establish iPad behavior
after its earlier failure, Apple completed-file offline audio, or all library-depth
interactions on any platform. Review of the captured compact iPhone screens also found
an artist name squeezed into a narrow header column and a selected Artists count that
included results from other categories. The owner corrected the compact header and selected-group count, added a count
assertion and a full-viewport discography capture, and repeated all three native cases.

The final local iPhone run at **`6d3ab4b1` passed 3 tests with zero failures in
172.904 seconds**. The destination was a leased **iPhone 17 Pro simulator on iOS
26.5 (23F77)**, UDID `E8450F93-B092-4861-A7E5-C0FA6BB26037`. The owner ran
`xcodebuild test -destination 'id=E8450F93-B092-4861-A7E5-C0FA6BB26037' -parallel-testing-enabled NO`
against a disposable local server, selecting exactly the three cases listed above.
The individual-result guard required three terminal `Passed` results in the result
bundle. The owner checked the build/test log, result bundle and exported captures;
this was a simulator run, with no physical-device proof. The sort/artist/shuffle case passed in **81.008 seconds**, the pin/offline
case in **63.599 seconds**, and grouped search/recents in **28.296 seconds**. The pin
case used the new stable Pinned-heading identifier. Downloaded opened an empty local
destination; no Apple completed-file offline-audio result is claimed.

The owner reviewed the final compressed captures and observed the full `Alpha Ensemble`
artist name, an unobscured Albums section with its 2026 release, the offline pinned
album and explanatory caption, and an Artists-only search count of one. These images
are from that final run:

| Native state | Capture |
| --- | --- |
| Release-date sort | [Release sort](library-depth-iphone/library-depth-release-sort.jpg) |
| Artist Top Songs and full compact header | [Artist Top Songs](library-depth-iphone/library-depth-artist-top-songs.jpg) |
| Full-viewport discography | [Artist discography](library-depth-iphone/library-depth-artist-discography.jpg) |
| Pinned album after offline relaunch | [Offline pin](library-depth-iphone/library-depth-pinned-offline.jpg) |
| Empty Downloaded destination | [Downloaded](library-depth-iphone/library-depth-downloaded.jpg) |
| Songs controls | [Songs](library-depth-iphone/library-depth-songs.jpg) |
| Shuffle queue | [Shuffle All](library-depth-iphone/library-depth-shuffle-all.jpg) |
| Artists-only grouped search | [Search groups](library-depth-iphone/library-depth-search-groups.jpg) |

The broad feature rows remain partial: the other platform paths, other pin kinds,
additional category/filter combinations, release-type/date fallback cases and completed
offline downloads have not been established by this three-case iPhone run.

## Android host-soak correction and bounded results

The [initial phone soak](https://github.com/legitimate-apps/dulcet/actions/runs/38993719454)
at `14de7a41` executed **300 tests across 20 repetitions**: 10 repetitions passed and
10 failed at the pinned-album home assertion. A stored pin had already been observed
before that assertion. The implementation owner then added a regression that reproduced
the failure before the fix (**1 test, 1 failure**), distinguishing durable pin state from
a pinned header not composed by the phone's lazy layout. Commit `5a9b3a3c` corrected
that phone layout and added the regression. Its local follow-up passed **16 tests with
zero failures**: six library-depth, eight category-sort and two search tests; both phone
APKs compiled. These regression and local-check counts are implementation-owner reports.

The [replacement phone soak](https://github.com/legitimate-apps/dulcet/actions/runs/38994985757)
at `5a9b3a3c` completed **20 passed repetitions, zero failed**, with **320 test
executions and zero skips** (20 × 16). The existing
[TV soak](https://github.com/legitimate-apps/dulcet/actions/runs/38993723283) at
`14de7a41` completed **20 passed repetitions, zero failed**, with **140 test executions**
(20 × 7). GitHub reports both of those runs successful at their stated revisions. The TV run is
now historical: subsequent required-CI failures led the implementation owner to correct
TV production query-reset and freshness-reflow behavior. Those later TV sources require
a fresh soak; the earlier 20-pass result does not verify them. These are hosted Android
host-test observations, separate from the earlier APK-identified emulator completed-download
proofs and from native Apple runtime behavior.

A separate [phone search conformance soak](https://github.com/legitimate-apps/dulcet/actions/runs/39001870740)
at `f134d55b` passed **20 repetitions, zero failures**, each executing
`AndroidSearchAppConformanceTest`'s selected conformance method: **20 test executions
with zero skips**. The owning implementation agent checked the result line and JSON.
This is focused phone host-test evidence for that search path, separate from the earlier
320-execution depth/category soak. The separate [TV production soak](https://github.com/legitimate-apps/dulcet/actions/runs/39001872018)
completed **19 passed repetitions and 1 failed**, with **560 test executions, 1 failure
and zero skips**. Its changed Library/Search class selection was 28 tests per repetition.
Iteration one failed `aPlaylistIsDeletedWithTheRemoteOnlyAfterItAsksAndCancelKeepsIt`;
iterations 2 through 20 passed. The later TV search focus correction is not covered by
that earlier source. The final-source replacement result is recorded below; the
historical TV success and focused phone results do not verify later TV source changes.

The [real-server phone production-search soak](https://github.com/legitimate-apps/dulcet/actions/runs/39004170545)
at `06ce9f7f` separately passed **20 repetitions, zero failures**, with **20 test
executions and zero skips**. It selects
`AndroidProductionSearchAppConformanceTest.conf41ProductionQueryMergesAndRoutesOpaqueId`
against the disposable server, distinct from the earlier non-production search class.
The owner also reported that method passed once in each local prod and dev channel,
with zero failures, errors or skips. These results cover the corrected search proof;
they do not turn the earlier TV playlist failure into a pass or establish all required CI.

At the final TV source `dcc2ff38`, the implementation owner reported **2 native
emulator cases passed**. The My Library fallback proof with `myLibrarySplit=false`
passed in **6.428 seconds**, exercising the ordinary server Library path. The new
`AndroidTvEmulatorGroupedSearchProofTest.theRemoteExpandsAlbumsWalksPastThePreviewAndReturnsToTheBar`
passed in **10.437 seconds**. That case uses actual D-pad Up/Down/Center actions to
reach See All, expand Albums, move beyond the preview to the fifth album, activate its
opaque identity, and return to the query/bar without overlap. The owner reviewed
compressed captures of See All focus, the long-title results and the album detail's
Play control. This supplies the missing bounded remote-navigation observation; it does
not establish all grouped-search, pin, sort or artist paths.

The final TV native proof used these artifact identities, distinct from the earlier
completed-download APKs:

| APK at `dcc2ff38` | SHA-256 |
| --- | --- |
| TV app | `1312ac5c21fa0109d39972d55e1453f28d14ad16bd55db9cde7cf734b2cea56f` |
| TV instrumentation | `c6db5ff45611c0cf498a88e5c30804d9ca970feeccf8540900b703c58bfe1738` |

The owner also reported final local TV production conformance **34/34 passed**, regular
host tests **88/88 passed**, both APKs compiled, and the final search-signature guard
**1/1 passed in 8.505 seconds**. The replacement
[TV production soak](https://github.com/legitimate-apps/dulcet/actions/runs/39005155830)
at `dcc2ff38` passed **20 repetitions, zero failures**, with **560 test executions
and zero skips**: all 20 repetitions executed exactly 28 methods. The owner checked
the normalized result artifact and strict repetition/count gate. The separate
[regular TV host-test soak](https://github.com/legitimate-apps/dulcet/actions/runs/39007740186)
at `44c18c74` also passed **20 repetitions, zero failures**, with **240 test
executions and zero skips** (20 × 12). The intervening revision changed evidence
guards, not Android production. The owner checked its result line and normalized
artifact. The earlier 19/1 soak and required-CI focus failure remain historical
results at their original revisions.

The final repeated host-test results are distinct selections:

| Selection | Run / source revision | Repetitions passed / failed | Test executions | Failures / skips |
| --- | --- | ---: | ---: | ---: |
| Phone depth/category | [38994985757](https://github.com/legitimate-apps/dulcet/actions/runs/38994985757) / `5a9b3a3c` | 20 / 0 | 320 (20 × 16) | 0 / 0 |
| Phone search UI conformance | [39001870740](https://github.com/legitimate-apps/dulcet/actions/runs/39001870740) / `f134d55b` | 20 / 0 | 20 (20 × 1) | 0 / 0 |
| Phone real-server production search | [39004170545](https://github.com/legitimate-apps/dulcet/actions/runs/39004170545) / `06ce9f7f` | 20 / 0 | 20 (20 × 1) | 0 / 0 |
| TV production Library/Search | [39005155830](https://github.com/legitimate-apps/dulcet/actions/runs/39005155830) / `dcc2ff38` | 20 / 0 | 560 (20 × 28) | 0 / 0 |
| TV regular changed classes | [39007740186](https://github.com/legitimate-apps/dulcet/actions/runs/39007740186) / `44c18c74` | 20 / 0 | 240 (20 × 12) | 0 / 0 |

These are hosted host-test observations. Each result is bounded to its stated source
and selection; neither repetition nor an earlier source proves other native paths.

## Broader required CI and migration repair

The successful focused Android soaks do not establish that all required CI passed.
In [PR core CI run 38996397552](https://github.com/legitimate-apps/dulcet/actions/runs/38996397552),
the phone emulator job passed in **10 minutes 29 seconds**, while the TV emulator job
failed in **8 minutes 50 seconds** when the My Library split proof could not reach its
catalog-album control. Linux conformance executed **34 tests with 2 failures** in
**9 minutes 6 seconds**: the TV host `conf82` retry control disappeared, and `conf41`
one-character local rows timed out after five seconds. These are implementation-owner
reports from the required run; their causes and corrections require separate evidence.
They do not erase the bounded completed-download and focused-soak observations above,
and those successes do not resolve these failures.

The [required core-build job](https://github.com/legitimate-apps/dulcet/actions/runs/38996397552/job/117290103045)
stopped before Gradle tests because
`tools/migration_gate.py` found no `library_depth_entry` table in the previous latest
schema snapshot. The released `9.db` remains unchanged. The repair adds the genuine
SQLDelight-generated **`10.db`**, its version-10 fixture, registry entry and invariants.
The snapshot-generation task passed. A composite generation-plus-verification command
then exited on a Gradle 9.5 implicit task-dependency error; that orchestration failure
was not a failed migration assertion. A subsequent standalone verification returned
`UP-TO-DATE`, which did not establish fresh execution. The owner then ran
`:core:verifyCommonMainDulcetDatabaseMigration --rerun-tasks` alone: it passed in
**22 seconds**, with **one actionable task executed** and exit status zero. This is
the fresh SQLDelight migration verification result, separate from the Python gate below.

The implementation owner subsequently reported the repaired Python historical gate
passed: **10 fixtures** upgraded to the fresh **70 schema objects**, with **5 protected
tables**, file reconciliation and **21 destructive negative controls**, including three
for the new Library state. Version-10 byte regeneration, the version-7-through-10
portable semantic checks, the generator's positive control and **7 negative controls**
also passed. The generated snapshot had no difference from the declared SQL schema;
SQLite integrity and foreign-key checks were clean. These are local migration checks,
not a rerun of the required core CI job or proof of native Library behavior.

The subsequent [required core CI run at `3f8256f6`](https://github.com/legitimate-apps/dulcet/actions/runs/38999252126)
completed with an overall failure. Its protected-data migration semantics and core
build/test/license stages passed. The core-build job later failed at Android app tests;
the Linux conformance and TV emulator jobs also failed, while the phone emulator job
passed. The final aggregator therefore failed. This confirms the repaired migration
gate was exercised successfully in required CI, while Android corrections still require
their own new results. No overall CI pass is claimed.

At `128b9d39`, the [required parity gate](https://github.com/legitimate-apps/dulcet/actions/runs/39002296862)
passed. The [required core run](https://github.com/legitimate-apps/dulcet/actions/runs/39002296930)
completed with both phone and TV emulator jobs successful, while core-build, Linux
conformance and the aggregator failed. Core-build's TV suite executed **88 tests with
2 failures**: the account-entry remote walk did not reach the fourth result, and the
album-sort remote walk encountered the Load More control before its expected sort
control. Linux conformance stopped in the phone production-search class: **32 tests
with 1 failure**, waiting for the `Dulcet local only` result at `search.result.1`.
These newer failures are distinct from the historical `3f8256f6` failures. Their final
corrections and fresh required results remain pending; passing focused phone search or
emulator paths does not establish those failed host interactions.

At the newer `06ce9f7f` revision, [required parity](https://github.com/legitimate-apps/dulcet/actions/runs/39004173339)
passed and [required core CI](https://github.com/legitimate-apps/dulcet/actions/runs/39004173308)
remained failed. This time Linux conformance and both phone and TV emulator jobs passed;
the previous production-search failure did not recur. Core-build failed its TV suite
with **88 tests and 1 failure**, the account-entry remote walk's fourth-result assertion.
The aggregator therefore failed. Later local TV corrections reportedly passed the full
34-case production conformance selection and 88 regular tests. Fresh hosted results
for those final sources were still pending at that snapshot; the final result follows. The earlier required failures remain
historical observations at their stated revisions.

The final-source [required core CI run at `dcc2ff38`](https://github.com/legitimate-apps/dulcet/actions/runs/39005156175)
completed **successfully**. Core-build, Linux conformance, both phone and TV emulator
jobs, and the required aggregator all passed. The accompanying
[required parity gate](https://github.com/legitimate-apps/dulcet/actions/runs/39005156174)
also passed. These results superseded the earlier failed revisions for that runtime
scope. The later guard-only attempt failed as recorded below, so this earlier green
run does not establish the new Downloaded identity guard. The separate TV production
and regular host-test soaks subsequently passed at their stated revisions.

The final required artifacts were inspected by the documentation reviewer, with these
JUnit totals. Shared core results duplicated in the Android app artifact are not added
a second time:

| Required artifact scope at `dcc2ff38` | Tests | Failures/errors | Skips |
| --- | ---: | ---: | ---: |
| Core JVM | 1,082 | 0 | 0 |
| Core Android host | 1,272 | 0 | 0 |
| Regular phone prod + dev | 424 (212 each) | 0 | 0 |
| Regular TV | 88 | 0 | 0 |
| Linux phone production app | 32 | 0 | 0 |
| Linux TV production app | 34 | 0 | 0 |
| Linux core conformance JVM / Android host | 71 / 65 | 0 | 0 |
| Phone emulator app main / My Library split rerun / core conformance | 15 / 1 / 13 | 0 | 1 / 0 / 0 |
| TV emulator app main / My Library split rerun / core conformance | 16 / 1 / 13 | 0 | 1 / 0 / 0 |

Each emulator's one skipped case is the existing AutoSkip proof. Both Downloaded methods
and the TV grouped-search remote case are present as passing, non-skipped testcases.
The phone and TV Downloaded test identities are respectively
`AndroidEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`
and
`AndroidTvEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`.
They ran once each in the required `core-ci` workflow's emulator jobs, whose immutable
artifacts the required `core-ci` aggregator downloaded.

That passing run did not yet explicitly require these two method identities in
`tools/verify-parity-evidence`: their partial feature cells had no formal evidence entries,
and the separate selected-method guard covered only My Library split. This revision
adds both `core-ci/core-ci` evidence entries while keeping the cells partial. Replaying
the verifier against the actual final artifacts passed with **208 required identities
and 216 XML reports**. Two independent negative controls, removing only the phone or
only the TV Downloaded method, were each rejected with the exact missing method name.
This verifies the proposed guard against the observed artifacts; promotion waits for
the next required CI run to consume it successfully. The recorded remaining condition
is that guard, without adding new feature criteria after the runtime proof passed.

The [guarded required run at `44c18c74`](https://github.com/legitimate-apps/dulcet/actions/runs/39006584706)
completed with both emulator jobs and Linux conformance successful, but core-build
failed on its **660-second watchdog**, exit status 124. Its final test-worker stack was
waiting in `HostResolutionTest.saturationRejectsWithoutQueueingAndRecoversAfterWorkersReturn`.
No test assertion failure was emitted. That stack identifies the stalled test context;
the cause is not established by the watchdog alone. The required aggregator failed at
its dependency-result check and did not consume the new Downloaded evidence identities.
The two Downloaded cells therefore remain partial, despite their passing native methods
and the earlier local verifier/negative-control results. No guarded-run pass or feature
promotion is claimed from this attempt.

The core owner traced that hang to test coordination: the first two 80 ms deadlines
could expire before worker dispatch, while the test awaited an unconditional start
signal. Production intentionally skips an inactive continuation. A deterministic
200 ms delayed-dispatch control reproduced the old admission failure: **1 test,
1 failure, zero errors in 5.034 seconds**, at the bounded `started.await` timeout.
The test-only correction at `a076bbf3` uses an unbounded deadline only for capacity
admission, retains the existing 80 ms timeout exercises, bounds coordination to five
seconds, and releases blocked workers in cleanup. The owner observed **10 HostResolution
tests with zero failures or errors in 3.600 seconds**, passing iOS common-test
compilation, and a final strengthened no-queue assertion passing **1 test in 0.253
seconds**. No production source changed. These local results did not turn the earlier
failed required run into a pass.

The [replacement required run at `a076bbf3`](https://github.com/legitimate-apps/dulcet/actions/runs/39007768787)
subsequently completed **successfully**: core-build, Linux conformance, both native
emulator jobs and the required aggregate passed. Its [aggregate evidence guard](https://github.com/legitimate-apps/dulcet/actions/runs/39007768787/job/117325718307)
reported `executed parity evidence valid: workflow=core-ci job=core-ci tests=208 reports=216`.
The committed required identities include both
`AndroidEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`
and `AndroidTvEmulatorLibraryDepthProofTest/downloadedViewBrowsesAndPlaysCompletedMusicWithTheServerUnreachable`.
The guard rejects an absent, failed or skipped method, as the earlier independent
missing-method controls demonstrated. This passing required consumption fulfills
the two recorded Android Downloaded promotion conditions. Only those two cells are
now shipped; the remaining 33 new cells stay partial, and Apple TV Downloaded stays n/a.

## Apple conformance repair after #336 (2026-10-17)

The [original full Apple run at `128b9d39`](https://github.com/legitimate-apps/dulcet/actions/runs/39002314622)
already contains the three reported Library failures, before the listening-session merges.
Its Mac Library bundle ran five tests with two failures; its iPhone Library bundle ran
seven tests with one failure. The failure diagnostics were exported and examined before
changing the tests.

- **OBSERVED — iPhone offline replay:** the failure hierarchy has Library selected and
  Recently Added at y900.3, below the 874-point viewport and the tab bar at y791. The lazy
  album grid has no instantiated album tile. The test already scrolled before its first
  offline assertion, but omitted that action after relaunch. The correction requires the
  section control to exist, the tile to be initially absent, at least one touch scroll,
  and a hittable tile. It retains the direct-server negative membership checks both before
  and after relaunch, and the eventual positive server membership after reconnect. The
  fixture must also acknowledge each requested online/offline transition. A missing lazy
  accessibility element alone did not establish loss of the durable pending add. In the
  diagnostic run, this method passes in 132.609 seconds and logs
  `MY LIBRARY OFFLINE RELAUNCH lazy-grid-revealed scrolls=1`. All seven iPhone Library
  methods pass the named terminal-result guard.
- **OBSERVED — Mac pin lookup:** the original failure hierarchy contains the identified
  `dulcet.library.pinned.title` StaticText with label `Pinned`, plus the pinned album. In
  the [diagnostic run at `24b656e3`](https://github.com/legitimate-apps/dulcet/actions/runs/39044137148),
  the same live typed query reports `firstMatch=false`, `indexed=true`, `count=1`. The pin
  method then passes, including the exact heading text, persisted album and offline
  destinations. This establishes a false negative in the first-match lookup; it does not
  establish an internal XCTest implementation cause. The final lookup uses the same type
  and identifier with `element(boundBy: 0)`. The diagnostic enumeration before the assertion
  is removed so the final validation exercises that lookup without warming its query.
- **OBSERVED — Mac grouped count:** both recordings show Artists, `1 result` and one
  artist row. The diagnostic reports `exists=true`, an empty `label`, and `value=1 result`.
  Mac must assert the native StaticText value. The correction waits for that exact value;
  iOS retains the exact label check. The initial diagnostic revision also exposed an error
  in a new, stronger row assertion: querying TableRow returns zero because SwiftUI's native
  `SwiftUIOutlineTableView` has `AXOutline` role, as the same run's native accessibility
  probe records. The corrected assertion scopes the results outline by its identified
  rendered result and waits for exactly one OutlineRow, excluding the sidebar outline.

The old `7b` search crash report records missing `DulcetPresentationStore` environment
resolution through native table hosts. Production commit `eb112755` already fixes that
on main by supplying the root store to the cell and context-menu hosts. This repair
preserves it. In the diagnostic platform job, both argument cases of
`MacGroupedSearchHostingTests.readerResultsRenderThroughTheRootAndNativeTable` pass;
the Library search recordings reach the Artists table without a crash.

Local `tools/typecheck-xcuitest-sources` passes all five covered UI-test directories for
the corrected queries. The check's negative controls passed; its actual type-check caught
an introduced invalid row API, corrected before the replacement dispatch. A type-check is compile evidence,
not UI execution evidence.

The iPhone job subsequently reaches a latent lyrics-test assumption. Its search hierarchy
contains exactly one result, `Ogg Probe`, while Now Playing has already advanced into
`Paging Track 008` and later another paging track. **OBSERVED:** one own search result no
longer guarantees that the one-second fixture remains current. **ASSUMED from the
production default and queue continuation path:** enabled AutoPlay is the source of that
continuation; the old test never controlled or read the setting. The test correction
chooses AutoPlay off through the actual Up Next switch, reads it back, verifies that choice
again after the plain phase's relaunch, and restores the previous choice through the UI.
Setup pauses and resumes the synced fixture with positive transport-state checks. Every
existing lyrics-line, empty-state, failed-read receipt and retry assertion remains, with an
additional exact `Ogg Probe` title assertion after its plain lyrics are shown. The production
AutoPlay default remains on. This interaction fix still needs runtime validation.

Validation is still in progress. The diagnostic full run is **red**: Mac Library passed
four of five methods, with the added TableRow assertion failing; the platform job failed
in existing crossfade tests; and the iPhone job failed in the later lyrics proof after
all seven Library methods passed. The corrected indexed/Outline queries and explicit
lyrics setting require runtime validation. The iPad result and the subsequent all-green
full-run link must be recorded before this repair is handed off. No feature cell is
promoted by this test repair.
