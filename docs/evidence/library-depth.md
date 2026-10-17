# Library depth

## Implementation and evidence status

The library-depth feature rows in `FEATURES.yml` remain **partial**, except Downloaded
on Apple TV, which is **n/a** under the existing tvOS download policy.
Source implementation and shared-core tests do not establish that a native screen works.
The Android phone and TV completed-download/offline-playback proofs passed locally.
Other runtime limits and failed attempts are recorded below. The focused Android phone host-test soak passed. The earlier TV soak is historical;
subsequent TV production query-reset and freshness-reflow corrections require a fresh soak. The first executed Apple CI run reported a TV compile
failure and an iPad pin-relaunch failure. The replacement run reached the Mac proof,
which failed at a pin-heading query and a search crash. A separate local iPhone run
passed the three new cases and then repeated them after the resulting visual fixes;
its bounded scope is below.
The iPad fix and the remaining Apple platform paths still require verification.
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
The proofs are not yet wired into required CI.

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
menu content and extends the hosting regression. A native rerun is still required to
establish that the crash is resolved. The subsequent execution guards and JUnit conversions did not run after the failed
suite. This is an application runtime failure under the hosted proof recipe, separate
from the earlier local pre-main sandbox blocker.

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
172.904 seconds**. The individual-result guard required three terminal `Passed`
results. The sort/artist/shuffle case passed in **81.008 seconds**, the pin/offline
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
