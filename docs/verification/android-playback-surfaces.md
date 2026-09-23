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

Android TV 14 emulator with `leanback_only`. The TV app installs and launches, and its player is not
exported: starting it from the shell is refused with `SecurityException … not exported`
(**OBSERVED**). **Playback on TV could not be driven**: the TV app has no way to connect an account
and shows "Connect an account before searching." That, not the player, is what blocks TV playback
evidence.

## Still assumed or open

- **Lock-screen controls**: the emulator had no secure lock screen. **ASSUMED** from the platform
  session and notification above.
- **Audio focus and becoming-noisy**: Media3 requested focus (**OBSERVED** in the log, usage media,
  content music). Loss of focus to another app and a headset unplug were not exercised.
- **Acoustic output**: never claimed; the corpus is silent and the emulator's audio is virtual.
- **Citable evidence**: no workflow runs an Android emulator, so none of this reaches a required
  check. The four Media3 cells stay `partial` until one does.
- **TV connect**, above.
- **Phone search** still opens the detail screen when a row is activated, because the cited CONF-41
  evidence routes that way; a Play button on track rows plays directly.
- **Library breadth** (songs, playlists, sort) waits for the library reader.
