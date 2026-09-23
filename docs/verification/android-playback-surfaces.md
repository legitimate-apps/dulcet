# Android playback surfaces — verification record (2026-09-22)

What this change adds on top of the Media3 implementation (`android-playback-leg1.md`), what was
driven for real, and what remains assumed. **No `FEATURES.yml` cell is promoted by this record**:
everything below the host tests is a local emulator run, and a local run is not a CI identity.

## What changed

| area | change |
|---|---|
| queue | `playQueue` plays an album or artist in order, with shuffle; `playSong` is a one-song queue. `jumpTo` starts an Up Next entry by `QueueEntryId`. Previous restarts a song after three seconds. |
| session | Title, artist, album, duration and cover art on the media session. Skip commands advertised from the core queue (spec §8). |
| artwork | `AndroidArtworkRepository`: validated `getCoverArt` bytes through the core fetcher, a bounded LRU disk cache with digest paths, "no artwork" remembered per process. |
| phone | Material 3 with dynamic color; Library and Search tabs; album grid; album and artist pages; mini-player; Now Playing with scrubber, shuffle and repeat; Up Next sheet. |
| TV | A lean-back Now Playing built from `androidx.tv:tv-material` components, D-pad transport, media keys, Up Next. |
| entry | Package-scoped play intents that resolve only to non-exported components; the exported phone launcher honours a play request only through its private alias. |

The library keeps its existing session and committed read. Album and artist pages are a stateless
index over those rows (`LibraryIndex`), so a different library source can replace the read without
touching the screens.

## Review findings fixed in the Media3 implementation

1. **A refused transport verb painted a playback failure.** Any rejected engine command set the
   error, so a seek with nothing prepared, or a lock-screen button pressed between songs, replaced the
   player with "Playback failed". Only a failed or unpreparable source does now.
2. **A finished queue reported the last song's position and duration** with no session, and Now
   Playing read "Loading…" indefinitely. Found on the emulator; the state now carries no position
   without a session and the screen says "Nothing is playing".
3. **Media3's one-item timeline hid next/previous** from system controllers; see spec §8.
4. **Restoration invented a title** ("Saved playback"). The song's own metadata supplies it.
5. **The session carried only a title**: no artist, album, duration or artwork.

## Host evidence

Counts are read from the JUnit XML of one forced run (`--rerun-tasks`, 201 tasks executed):

| task | suites | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| `:core:jvmTest` | 29 | 224 | 0 | 0 | 0 |
| `:core:testAndroidHostTest` | 33 | 316 | 0 | 0 | 0 |
| `:android:app:testDevDebugUnitTest` | 6 | 12 | 0 | 0 | 0 |
| `:android:app:testProdDebugUnitTest` | 6 | 12 | 0 | 0 | 0 |
| `:android:tv:testDebugUnitTest` | 2 | 2 | 0 | 0 | 0 |

### Every new test was made to fail

Each mutation compiled, and each failed exactly its target test; sources were restored afterwards.

| mutation | failing test |
|---|---|
| any refused command sets the error | `refusedTransportVerbIsNotReportedAsAPlaybackFailure` |
| skip commands taken from the one-item timeline | `sessionAdvertisesQueueSkipsThatTheOneItemTimelineCannot` |
| jump resolves the first entry of the same song | `jumpToStartsTheNamedEntryOfADuplicatedSongAndKeepsEveryIdentity` |
| Previous never restarts | `previousRestartsASongPastThreeSecondsAndOtherwiseMovesBack` |
| foreign-account guard removed | `foreignAccountTrackIsRefusedBeforeAnyRequest` |
| ended queue keeps the stale position | `anEndedQueueReportsNoSessionAndNoStalePosition` |
| missing artwork not remembered | `missingArtworkIsRememberedForTheProcess` |
| eviction removes the newest image | `theBudgetEvictsTheLeastRecentlyUsedImage` |
| an error body is cached | `onlyValidatedImagesAreCachedAndACachedImageIsNotRefetched` |
| alias guard removed | `onlyThePrivateAliasCanRequestPlayback` |
| artist matched by name when ids differ | `eachAlbumOwnsTheTracksThatFollowItAndArtistsMatchByIdBeforeName` |
| play intent not package-scoped | `productionSearchDetailOpensPlaybackWithOpaqueIdentity` |

## Emulator run — phone

Android 14 (API 34) emulator, the DEV debug build, a disposable Navidrome 0.63.2 on the host seeded
with the conformance corpus and reached from the emulator. The app was driven through its own UI
with `adb`; no test harness was involved.

| link | result |
|---|---|
| connect through the connect form with the local-HTTP opt-in | **OBSERVED**: the app left the form for the Library/Search shell |
| sync, then Library shows artists, albums and tracks with cover art | **OBSERVED** |
| album page → Play | **OBSERVED**: mini-player with title, artist, cover and Pause |
| media time advances | **OBSERVED**: Now Playing position 0:18 → 0:24 over about seven seconds |
| natural completion advances the queue | **OBSERVED**: track 1 → 2 → 3 without input |
| Home: the app is backgrounded | **OBSERVED**: launcher resumed; playback continued |
| media notification | **OBSERVED**: MediaStyle, flags `0x68` (foreground service, no-clear, alert-once), three actions (previous, pause, next), title, artist, a 126×126 artwork bitmap, a session token |
| platform session | **OBSERVED**: `PLAYING`, metadata title, artist and album |
| system transport | **OBSERVED** via `cmd media_session dispatch`: pause froze the position (14 520 ms, held for three seconds) and flipped the in-app button to Play; play resumed; next advanced the core queue |
| activity destroyed while playing | **OBSERVED**: zero activity records (22 while open, the positive control); the service stayed foreground (`mediaPlayback`) and the session position advanced 17 384 → 23 220 ms across a 6 008 ms update interval |
| server-side plays | **OBSERVED** with `getAlbum` before and after: *Twenty Nine Seconds* 0 → 0 (below the threshold, the in-run negative control), *Thirty One Seconds* 0 → 1, *UI Playback Canary* 0 → 1. The canary's scrobble was recorded while the app was on the launcher. A second run with the activity destroyed took both to 2, and `getNowPlaying` listed the client `Dulcet`. |
| start latency | **OBSERVED**: about two seconds from the Play tap to `PLAYING` |
| credentials in the device log | **OBSERVED**: zero matches for the username, password, or a 32-hex `t=`/`s=` value across 64 496 captured log lines, 3 637 of them from the app; the same pattern matches a synthetic signed URL (positive control) |
| a queue that ends | **OBSERVED** after the fix: Now Playing reads "Nothing is playing" at 0:00 |

## Emulator run — TV

Android TV 14 emulator with `leanback_only`. The TV player is not exported: starting it from the
shell is refused with `SecurityException … not exported` (**OBSERVED**). The first run could not
play because the TV app had no connect flow; it has one now (below).

**Connect by remote, OBSERVED:** the connect screen opens with the server field focused; text was
entered through the on-screen keyboard, whose Next action moved focus field by field to the HTTP
switch; select toggled it; Down then select on Connect connected to the disposable server and
opened TV search. Driving it first exposed a defect: a text field kept Up and Down for its cursor,
so a remote could never leave the first field and every entry landed in it. Up and Down now move
focus (host test below).

**Not driven by hand:** search → detail → Play by remote. The emulator image's Google sign-in
kept taking the foreground, and after I force-stopped Google Play services on it the display
stayed black. The instrumented test below plays through the same production entry.

## Instrumented emulator proofs

`AndroidEmulatorPlaybackConformanceTest` (phone) and `AndroidTvEmulatorPlaybackConformanceTest`
(TV) run against the disposable server named by instrumentation arguments. They fail, never skip,
when it is absent. Each connects through the production connect sequence, starts playback through
the production play entry, requires media time to advance, sends the app Home, requires time to
keep advancing under the foreground service, and requires the server play count to rise by
exactly one and stay there after the queue ends. The TV test also requires the remote's pause key
to stop media time mid-song within five seconds, and its play key to restart it.

| run | result |
|---|---|
| phone, local emulator | **OBSERVED** pass, server 3 → 4 |
| phone, no server argument | fails: `dulcetServerUrl is required` |
| phone, scrobble delivery removed | fails: `Timed out waiting for: server play count 5`; the server stayed at 4 |
| TV, local emulator | **OBSERVED** pass, server 6 → 7; pause held with 0 ms drift |
| TV, `pause()` disabled | fails: `Timed out waiting for: the remote's pause to take effect` |
| TV, emulator audio disabled and software GPU (as on CI) | **OBSERVED** pass, server 7 → 8 |

The TV pause check was first written as "no longer wanted", and the disabled-pause mutation
passed it: the 31-second song simply ended. It now requires the same session, mid-song, within
five seconds. The remote keys reached the app through the media session (**OBSERVED** in the
system log), which is how a remote's media keys arrive.

`core-ci` runs both, in its `android-emulator` job (phone and TV legs), against the same pinned
disposable server as the Linux conformance job. The required `core-ci` job fails unless both legs
pass and verifies their JUnit through `tools/verify-parity-evidence`. The CI emulators are x86 and
x86_64, and the local runs above were arm64.

**First CI run (workflow_dispatch on this branch):** the phone leg passed on the hosted x86_64
emulator: 2 of 2 instrumented tests, with the proof reporting `server-plays=0->1` on a freshly
seeded server (**OBSERVED**). The TV leg failed before any Android step: `test -w /dev/kvm` ran
before udev had applied the permission rule. With the TV leg red, the required `core-ci` job
failed, which confirms that the aggregate gate fails when a leg fails. The KVM step now waits
for udev to settle.

## Still assumed or open

- **Lock-screen controls**: the emulator had no secure lock screen. **ASSUMED** from the platform
  session and notification above.
- **Audio focus and becoming-noisy**: Media3 requested focus (**OBSERVED** in the log, usage media,
  content music). Loss of focus to another app and a headset unplug were not exercised.
- **Acoustic output**: never claimed; the corpus is silent and the emulator's audio is virtual.
- **Citable evidence**: the emulator job exists but no `FEATURES.yml` cell is promoted here; that
  needs a green CI run of it first.
- **Phone search**: a row still opens the detail screen, because the cited CONF-41 evidence routes
  that way; track rows have a Play button that plays directly (`MobileSearchPlayTest`).
- **Library breadth** (songs, playlists, sort) waits for the library reader.
