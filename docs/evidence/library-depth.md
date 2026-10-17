# Library depth

## Implementation and evidence status

The library-depth feature rows in `FEATURES.yml` remain **partial**, except Downloaded
on Apple TV, which is **n/a** under the existing tvOS download policy.
Source implementation and shared-core tests do not establish that a native screen works.
The Android phone and TV completed-download/offline-playback proofs passed locally.
Other runtime limits and failed attempts are recorded below. Required CI has not executed
on this change yet.
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

Downloaded reads completed local download identities and pinned track metadata, without
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
| iPhone | `apple-conformance-ipad-iphone`, iPhone matrix member | `DulcetMyLibraryIPhoneUITests` | Both methods below | 6 |
| iPad | `apple-conformance-ipad-iphone`, iPad matrix member | `DulcetMyLibraryIPadUITests` | Both methods below | 5 |
| Mac | `apple-conformance-core` | `DulcetMyLibraryMacUITests` | Both methods below | 5 |
| Apple TV | `apple-conformance-core` | `DulcetMyLibraryTVUITests` | Pin/destinations method only | 4 |

- `testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable` pins an album,
  relaunches, checks the pinned metadata and opens Songs before unpinning. On iPhone,
  iPad and Mac it also opens Downloaded; Apple TV instead checks that Downloaded is absent.
- `testLibrarySearchGroupsAndRecentQuerySurviveRelaunch` checks the three group controls,
  expands Artists, submits a query, relaunches and replays the saved query.

The required `apple-ci` aggregate consumes each platform's JUnit. A future matrix evidence
citation uses workflow/job `apple-ci`/`apple-ci` and the platform-specific JUnit class plus
the exact method above. The new tests do not establish sort-menu behavior, completed
offline playback, shuffle eligibility or artist grouping on a native screen. Those claims
remain partial until their own runtime evidence exists. Existing visual-proof attachments
continue to be exported by the same CI driver.

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
  migration verification and Mac/iOS Simulator/tvOS Simulator debug-framework generation
  passed in that gate. Those framework builds preceded the final filtered-empty paging
  and default-category helper changes.
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
  The preceding pin test timed out once after the pin had been stored but before its
  home shortcut appeared. An isolated rerun and the complete final five-case class passed;
  no cause is asserted for that earlier timeout. The required 20-repetition host soak
  remains the stability gate. The final changed filters select **15 phone** and **7 TV**
  tests per repetition; the earlier host counts overlap these cases.
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
The hosted 20-repetition Android host-test soak and required Apple CI await publication. No broad platform status is promoted on these bounded results.
