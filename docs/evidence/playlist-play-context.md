# Playlist play context

The submitted-play contract is specified in
[playlist-play-context.md](../dulcet-api/playlist-play-context.md). Context belongs to the
playing queue entry and its playback session; it does not come from whichever screen is visible
when the durable outbox drains. A playlist read and a now-playing report create no playlist history.

## Shared behavioral checks

`PlaylistPlayContextTest` drives progressing media observations through the real queue and
scrobble accumulator. It checks playlist provenance after queue edits, non-playlist insertion,
advancement, restored queues and repeat sessions. Its durable-outbox case observes the persisted
context, recreates the store, fails discovery, retains the row, retries and checks the exact
submitted parameters after timestamp clamping. Other cases cover released-schema migration to
null, provider isolation, valid absence, unsupported discovery versions/endpoints, malformed and
authentication failure retry, serialized discovery and reconnect invalidation. Existing scrobble
outbox and delivery suites continue to check the surrounding delivery policy.

OBSERVED 2026-10-21: the focused JVM run passed 8 context, 27 outbox and 5 delivery cases,
with no failures, errors or skips. Removing the queue's context snapshot made the context test
fail with the expected playlist ID versus null. After restoring production code, 83 queue,
session, retry and context cases passed. The migration gate passed all 12 schema fixtures and
29 destructive controls, including erasing a populated playlist context from a pending play.
The existing DownloadPolicy suite passes 18 JVM cases after its positional outbox seed was
changed to explicit columns. The interrupted-migration fixture now includes the actual released
version-2 outbox table; all four driver-factory JVM cases pass, including transactional rollback.

OBSERVED reconnect regression: the Android real-socket controller test and native Apple
installed-sender test both fail before the availability binding is fixed (expected no context
after reconnect, received the stale playlist context). After the fix, all 66 Android controller,
21 Apple facade and 8 native playlist-context cases pass with zero failures, errors or skips.
The regressions change positive advertisement to valid absence and back to positive across
availability reports, including a reachable report without a preceding outage. They also check
that ordinary plays reuse the answer; the Android proof preserves session/attempt identities
and keeps now-playing context-free. These are controller/facade binding observations, separate
from the platform Home UI observations below.

OBSERVED Android reader-reconnect correction: the earlier Android availability regression
exercised only the OS network setter and missed the library reader's independent reconnect.
`AndroidPlaybackControllerTest/readerReconnectRediscoversPlaylistContextWithoutAnOsNetworkChange`
now calls the real `AndroidLibraryReader.reconnect()`, obtains a successful epoch read through
its production reconnect observer, and observes the installed playback sender over a real HTTP
socket. No OS availability setter is called. Before the correction, its one case fails with
`expected:<null> but was:<playlist-id>` after the reader's successful reconnect. After routing the
matching account's notification onto the delivery owner's main dispatcher and invalidating the
sender before applying availability, all 67 Android controller cases pass, with zero failures,
errors or skips. The new case observes positive-to-absent-to-positive negotiation, cache reuse
without another reconnect, no invalidation for another account's notification, preserved session
and attempt identities, and context-free now-playing. The existing OS-setter invalidation remains
covered independently. These are host binding checks with an in-process epoch transport and a
real scrobble socket, not an additional emulator/offline-relaunch claim.

These shared checks establish core behavior. They do not alone establish any platform's UI,
engine playback, process-death behavior or rendered Home result. Platform observations and test
counts are recorded below only when the corresponding runtime has executed.

## Platform coverage

OBSERVED 2026-10-21 against a local disposable Dulcet Server:

| Runtime | Actual trigger and observed effect | Result |
| --- | --- | --- |
| Android phone, API 34 | Playlist-entry activation → progressing Media3 playback → exact submitted playlist context → independently read server Home → refreshed rendered playlist card → same playlist opens. Now-playing carries no context. | Required `ListenNowEmulatorProofTest/homeLaunchAlbumAndCachedRelaunch`: 1/1, 42.281 seconds. |
| Android TV, API 34 | Same playlist-entry-to-rendered-Home chain, using real remote movement. Full-grid More also adds/removes membership (independent server reads), queues the album and restores focus. | Required Home plus `AndroidTvGridActionsEmulatorProofTest`: 2/2, 47.155 seconds. |
| iPhone | Real playlist Play → relay cut → media threshold → no server playlist history → offline app termination/relaunch with cached Home and still no server history → reconnect/relaunch → exact playlist in independently read server Home and rendered card. | `DulcetDesignTourUITests/testListenNowLaunchAlbumAndRelaunch`: 1/1, 101.028 seconds; `serverHistory=true homeTile=true offlineRelaunch=true`. |
| iPad | Real playlist Play → native player elapsed value advances → submitted threshold → exact playlist in independently read server Home → rendered Home playlist card. | `DulcetDesignTourUITests/testListenNowLaunchAlbumAndRelaunch`: 1/1, 100.488 seconds; `serverHistory=true homeTile=true offlineRelaunch=false`. |
| Mac | Actual playlist Play → server playlist history → rendered Home card. Actual per-track Play Next and Add to Queue each submit their own playlist context to server history. | `DulcetMacRatingsAndPlaylistsAppTest/aPlaylistPlaysInItsOwnOrderThroughTheHostedMacApp`: 1/1, 17.784 seconds; `playlistTrackQueueMenusSubmitTheirOwnPlaylistContextThroughTheHostedMacApp`: 1/1, 35.557 seconds. |
| tvOS | Actual playlist Play → media threshold → independently read server Home → rendered exact playlist card. | `DulcetTVUITests/testListenNowLaunchAlbumAndRelaunch`: 1/1, 79.346 seconds; `serverHistory=true homeTile=true offlineRelaunch=false`. |

These are exact-method results with no failures or skips in the cited methods. The tvOS Home
method passed in a bundle that also contained a failed, discarded native-menu experiment;
that bundle as a whole was not green. The new capability-gated context branch
executes only when the authenticated server advertises `dulcetHome` version 1; the ordinary
OpenSubsonic Home path still executes its existing controls. An optional local fixture proxy
provides the Apple offline phase; ordinary hosted Home execution without that proxy does not
prove offline submitted-play relaunch.

Android Home previously let lazy shelf rows own their reader windows. An empty or offscreen
row could be unregistered when Refresh ran. Home now owns all ten windows for its route
lifetime. `PhoneListenNowWindowLifecycleTest` passes 1/1 (8.158 seconds); restoring the old
ownership makes its missing-offscreen-refresh assertion fail. The TV driver also uses paced
D-pad movement, allowing the real focus system to reveal the row.

Android offline queued-play delivery across process relaunch remains unobserved at runtime.
The Android shipped Home cells claim the observed live chain; shared persistence tests are
separate evidence for durable core behavior. Per-action provenance mapping has host/source
evidence; the Android full runtime chain specifically drives playlist-entry activation.


The iPad offline attempt cut the relay after a fixed two-second delay without establishing
media start. Its outbox was empty and no new now-playing report reached the server. The driver
now observes advancing player elapsed time; the subsequent live iPad chain passed. Offline
queued-play delivery across iPad process relaunch remains unobserved and is not inferred from
the iPhone result.


## Android host stability

Each changed Android host-test group completed all 20 repetitions on the repository's Linux
soak runner. Every linked run ends with the exact line `SOAK RESULT passed=20 failed=0`:

| Filter | Run |
| --- | --- |
| `PlaylistPlayContextTest, AndroidPlaybackControllerTest` | [39633028692](https://github.com/legitimate-apps/dulcet/actions/runs/39633028692) |
| `PlaylistAndLyricsPresentationTest` | [39633033548](https://github.com/legitimate-apps/dulcet/actions/runs/39633033548) |
| `TvCardOptionsTest` | [39633263175](https://github.com/legitimate-apps/dulcet/actions/runs/39633263175) |
| `DownloadPolicyTest` | [39635131913](https://github.com/legitimate-apps/dulcet/actions/runs/39635131913) |
| `PhoneListenNowWindowLifecycleTest` | [39635935469](https://github.com/legitimate-apps/dulcet/actions/runs/39635935469) |


Mac and tvOS offline queued-play delivery across process relaunch remains unobserved. Their
shipped Home cells claim the observed live chain. The Mac proof required temporary internal
staging of runnable products/frameworks after an external-volume dynamic-loader stall before main; build
outputs and caches stayed on the external volume. A later fixture-navigation assertion was
corrected by waiting for the advertised Browse catalog before selecting Playlists.


## TV full-grid actions

OBSERVED tvOS: `DulcetMyLibraryUITests/testFullGridMoreOpensAlbumActionsAndMembershipReachesServer`
passes 1/1 in 31.915 seconds, with zero failures/skips and exact-method validation. Real remote
movement focuses the separate More button; Select opens a native confirmation dialog; Add to
Library reaches independently read server membership; the original tile's primary Select still
opens album detail. The receipt is `remoteSelectMore=true membershipServer=true primarySelect=true`.
The existing required `DulcetMyLibraryTVUITests/testBrowseAddRemoveRelaunchAndOfflineReplay`
calls this same full-grid helper, retaining its further removal/relaunch/offline assertions.
Native Menu focus attempts failed in this grid; this does not establish an API prohibition.
The dialog driver recognizes the focused UIKit alias only at the target's exact label and frame.

Android TV's corresponding local proof observes actual add/remove membership, album queuing
and focus restoration. These claims have their own `home.tv_grid_actions` cells rather than
being inferred from the Home playback results. Other retained card commands are not all claimed
as independent runtime observations.


The required Android TV `AndroidTvMyLibraryProofTest/automaticLibraryRetentionAndSameResponseCachePlayOffline`
invokes the same reusable `AndroidTvGridActionsProof` before its existing offline-library assertions.
The standalone positive wrapper is capability-gated on ordinary servers and is not cited as the
required Dulcet-phase execution. This preserves the existing case count and workflow selection.
