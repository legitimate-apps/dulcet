# Library depth

## Implementation and evidence status

The library-depth feature rows in `FEATURES.yml` remain **partial**, except Downloaded
on Apple TV, which is **n/a** under the existing tvOS download policy.
Source implementation and shared-core tests do not establish that a native screen works.
The Android phone and TV completed-download/offline-playback proofs passed locally.
Other runtime limits and failed attempts are recorded below. The final Android phone
and TV host-test soaks passed. The first executed Apple CI run reported a TV compile
failure and an iPad pin-relaunch failure. A replacement run for the resulting fixes
is pending; no Apple pass is claimed from those fixes yet.
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
result does not establish that the native pinned row renders. No result from the
replacement Apple run is claimed yet.

The hosted Mac My Library recipe targets the disposable `DulcetMyLibraryMacHost`, built
from the production Mac and shared Swift sources. `tools/ci/run-my-library-proofs`
sets `CODE_SIGN_STYLE=Manual`, `CODE_SIGN_IDENTITY=-` and an empty
`CODE_SIGN_ENTITLEMENTS` for that UI run. This ad-hoc, entitlements-cleared proof is
separate from the earlier local launch blocked during sandbox initialization. A passing
hosted UI result would establish the tested app paths under that proof host; it would
not by itself establish signed production sandbox behavior.

## Android host-soak correction and final results

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
(20 × 7). The phone correction changed only phone sources; the TV and shared sources
are unchanged from that TV run. GitHub reports both final runs successful. These are
hosted Android host-test observations, separate from the earlier APK-identified emulator
completed-download proofs and from native Apple runtime behavior.

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
was not a failed migration assertion. A separate Gradle verification run is still pending.

The implementation owner subsequently reported the repaired Python historical gate
passed: **10 fixtures** upgraded to the fresh **70 schema objects**, with **5 protected
tables**, file reconciliation and **21 destructive negative controls**, including three
for the new Library state. Version-10 byte regeneration, the version-7-through-10
portable semantic checks, the generator's positive control and **7 negative controls**
also passed. The generated snapshot had no difference from the declared SQL schema;
SQLite integrity and foreign-key checks were clean. These are local migration checks,
not a rerun of the required core CI job or proof of native Library behavior.
