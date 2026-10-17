# Listen Now evidence and runtime checks

Listen Now is the first destination for a connected or saved account on iPhone, iPad, Mac,
Apple TV, Android phone and Android TV. First-run connection retains the existing account flow.
The feature remains `partial` in `FEATURES.yml` until its platform runtime checks complete;
a compilation or shared test is not a substitute for that platform's UI execution.

## Shared and server behavior

- `HomeRecommendationsTest`: server integration tests verify imported-style song plays,
  timestamps, stars and ratings; user and source visibility; real playlist play context;
  advertised REST/API equivalence; bounds, authentication, scopes and private ETag revalidation.
  The REST context control verifies submitted member plays, ignored private/unrelated contexts,
  no history from reads/now-playing, monotonic retry timestamps and API/REST deduplication.
- `ListenNowTest`: five shared reader tests verify cached home before relaunch network I/O,
  retaining it on discovery failure, playlist identity, ordinary REST fallback, and optional
  similar-song behavior. Similar-song seeds are representative songs from a recently played
  album; they do not claim song-level recency.
- `SuiteSelfTest`'s Home profile test accepts valid recommendations and rejects a deliberately
  duplicated item. The optional profile is documented as `DA-HOME-1`.
- `TvHomeLandingTest`: four Android TV host tests cover initial destination, Back navigation,
  focus route and Library access.
- `ListenNowTests/homeIsTheFirstDestinationAndQueriesUseTheCoreShelfSeam` checks the Apple
  destination order and ten public shelf queries. The full Apple package passed 332 tests
  serially before the copy-only localization pass.

Targeted commands (run Gradle through `tools/run-gradle-exclusive` on a shared development host):

```sh
tools/run-gradle-exclusive :server:test --tests '*HomeRecommendationsTest'
tools/run-gradle-exclusive :core:jvmTest --tests '*ListenNowTest'
tools/run-gradle-exclusive :dulcet-api-conformance:test --tests '*SuiteSelfTest.home profile*'
tools/run-gradle-exclusive :android:tv:testDebugUnitTest --tests '*TvHomeLandingTest'
swift test --package-path apple/DulcetKit --no-parallel
```

## Production UI controls

`ListenNowEmulatorProofTest/homeLaunchAlbumAndCachedRelaunch` runs the production Android
activity, account connector and library reader against a disposable server. It requires Home
selected at launch, scrolls to New on Your Server, opens a real album, recreates and relaunches
the activity, then reaches Library and returns Home. It runs on both emulator matrix entries
in `core-ci`. Its screenshots are written by `DesignTourShots` under the app's `design-tour`
external-files directory. Playback still uses the existing platform owner; this check does not
claim a new playback-engine contract.

`DulcetDesignTourUITests/testListenNowLaunchAlbumAndRelaunch` runs the corresponding iPhone
and iPad account/album/relaunch/tab control. `DulcetTVUITests`' method of the same name uses
the remote to focus Refresh and an album, selects the album, relaunches and returns through the
section bar. The iOS proof validates the expected device idiom when supplied by the runner.

The Mac design-tour `home` phase connects the production presentation/reader stack in its
hosted production root window, presses an album through accessibility and checks the reader
path, then returns through Library and Home. Its credential store is in memory, as in the
existing Mac design instrument. It does not claim signed Keychain persistence. Window capture
is attempted by the owning shell; any fallback is explicitly logged as a view render.

`tools/ci/run-home-proof` executes these controls inside the existing `apple-ci` disposable
fixture lifetime, after the relevant target has built. Each platform has its own result bundle,
execution guard and JUnit output. A workflow dispatch runs the native conformance legs; the
PR platform leg alone does not execute these fixture controls.

## Server selection and limits

Dulcet Server supplies ten shelves from per-user imported/live history and visible catalog:
recent albums, actual recent playlists, frequent albums, new albums, rediscovery, a daily mix,
favourites, artist and genre stations, and labelled catalog exploration. The shared public
`ListenNowShelf` / `LibraryQuery.ListenNow` seam uses the normal per-account reader cache and
request/offline rules. Apple and Android wrappers expose it to future system surfaces.

Plain OpenSubsonic uses recent/frequent/newest/random album lists, starred items and optional
similar songs. It hides unsupported rediscovery, daily mix, genre-profile and playlist-history
shelves. Discovery/auth/transport errors retain cached answers rather than silently replacing
them with an unpersonalized success. Continue Listening resumes the existing queue; album and
playlist tiles use the existing collection detail and playback actions.

## Run record

Observed local core baseline: JVM 1,068 tests, Android host 1,259, native Mac 1,148, with no
failures, errors or skips. The five Home reader tests executed in each runtime. Shared iOS test
sources and phone/TV instrumented APKs compiled. Apple package tests passed 332/332 serially;
the localized Home package check passed 1/1. These establish shared logic and compilation,
not native UI execution.

| Control | Observed result | Evidence |
| --- | --- | --- |
| Android phone Home, plain REST | 1 passed, 0 skipped | [core-ci 38995786940](https://github.com/legitimate-apps/dulcet/actions/runs/38995786940), phone fallback XML |
| Android phone Home, Dulcet Server | 1 passed, 0 skipped | Same run, `listen-now-dulcet/results` XML; whole phone job passed |
| Android TV Home, plain REST | 1 passed, 0 skipped | Same run, TV fallback XML |
| Android TV Home, Dulcet Server | 1 passed, 0 skipped | Same run, `listen-now-dulcet/results` XML |
| Mac hosted production Home | 1 passed, 0 skipped; reached Home, album and return markers | [Apple run 38991471536](https://github.com/legitimate-apps/dulcet/actions/runs/38991471536), Mac Home JUnit and three screen captures |
| iPhone Home | 1 passed, 0 skipped; 35.622 seconds | [Apple run 38991471536](https://github.com/legitimate-apps/dulcet/actions/runs/38991471536), retained Home JUnit/xcresult |
| iPad Home | 1 passed, 0 skipped; 51.905 seconds | Same run, separate iPad Home JUnit/xcresult |
| Apple TV Home and corrected Mac connection controls | Pending | [replacement native run 38995086942](https://github.com/legitimate-apps/dulcet/actions/runs/38995086942) |
| Full core/server/API and Android integration gate | Follow the PR checks; prior full run failed legacy TV setup before server tests | [PR #329 checks](https://github.com/legitimate-apps/dulcet/pull/329/checks) |

Run 38992589271's following TV Library split control still assumed Library was the initial
screen; it failed, and the control now selects Library explicitly. Both Android device jobs subsequently passed in run 38993487470, including both-server Home and Library controls. Its full host matrix found eight further legacy TV genre/sort/play/playlist setup assumptions, now corrected to enter Library explicitly. These failures occurred before server tests. Neither whole workflow is
not a green integration gate. Its initial photo pull also found no files after Gradle uninstalled
the target. The Home proof now copies each of its three photos to a shell-owned disposable directory
before uninstall; the host requires all three. The first copy attempt incorrectly used shell
operators in a directly executed command and retained no files. Final photo-export validation
is tracked by the PR checks.

Host soaks all passed 20/20 repetitions: [production Library](https://github.com/legitimate-apps/dulcet/actions/runs/38988591007)
(two methods, 40 executions), [simple phone Search](https://github.com/legitimate-apps/dulcet/actions/runs/38991408220)
(one method, 20 executions), [production phone Search](https://github.com/legitimate-apps/dulcet/actions/runs/38992586654)
(one method, 20 executions), and [TV Home/Library/Search](https://github.com/legitimate-apps/dulcet/actions/runs/38992589407)
(ten methods, 200 executions). The remaining TV genre/sort/play/playlist controls passed
[20/20 repetitions](https://github.com/legitimate-apps/dulcet/actions/runs/38995783421)
(eight methods, 160 executions, zero skips). The production Search control continues to require device-only
one-character results, unique identities and remote canaries; Home may populate additional valid
cached catalog rows.

Local Android TV Home instrumentation also passed 1/1 in 39.724 seconds against a disposable
Dulcet Server. The committed Android photos are from the final hosted production Home controls
in run 38995786940: both phone and TV Home tests passed on both server types, with all three
photos retained per fixture. The phone header respects the status bar and hidden empty shelves
leave no extra vertical gaps. A photograph may show another shelf's loading placeholder while
the already populated New on Your Server shelf is usable.

![Android phone Listen Now](listen-now/android-phone-home.jpg)
![Android TV Listen Now](listen-now/android-tv-newest.jpg)
![Android TV album opened from Home](listen-now/android-tv-album.jpg)

Local Apple simulator enumeration stopped responding before UI execution; no shared runtime
restart or host reboot was performed. The local phone emulator produced system UI ANRs and was
stopped. Neither attempt counts as a successful UI proof. The first hosted native Home control
correctly failed on a header-only screen: an initially empty SwiftUI shelf never received its
reader appearance callback. Shelves now retain a concrete loading container; the replacement
native run must observe populated content and navigation before platform promotion.

The Mac Home test passed in 9.468 seconds using actual window captures (`capture=screen`). A later
legacy account-connection assertion expected Library and failed, so the whole native run is not
a green gate. Initial/saved/offline account controls now expect Listen Now; the explicit Library
cache/reconnect control still selects Library and requires it to stay there.

Reviewed Mac proof images:

![Mac Listen Now](listen-now/macos-home.jpg)
![Mac album opened from Home](listen-now/macos-album.jpg)

The earlier iPhone and iPad jobs were cancelled during later, unrelated controls. Their retained
Home JUnit files each contain one passing, non-skipped case; the whole workflow is not reported
as green. The screenshots below are from those completed Home controls.

![iPhone Listen Now](listen-now/iphone-home.jpg)
![iPad Listen Now](listen-now/ipad-home.jpg)

## Integration boundary

Server playlist recency accepts actual API play contexts and optional submitted REST scrobble
contexts (`dulcetContextType=playlist`, `dulcetContextId`) advertised by `dulcetHome` v1.
The current player must carry its real playlist source through the durable submitted-play outbox
and transport before these REST contexts can populate from app listens. That player integration
belongs to the separate player-session change. The Home UI does not infer playlist listening
from fetching or editing playlists. Continue Listening uses the existing saved queue independently.
