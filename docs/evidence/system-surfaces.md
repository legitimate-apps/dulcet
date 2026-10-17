# System surfaces — implementation and evidence boundary

2026-10-16. Source implementation is partial until each platform's actual OS trigger reaches the
existing player. A compile result, a router unit test, and a direct intent invocation prove different
links; none alone proves Siri, WidgetKit, CarPlay, Android Auto or Assistant delivery.

## Ownership and data

Apple entry points use `DulcetSystemActions`, the existing production presentation store and its
account-bound reader. They close request subscriptions, reject account-generation changes, preserve
opaque IDs and surface unavailable/ambiguous results. An explicit action can reconnect saved
credentials while backgrounded. The existing `DulcetSystemMediaControls` remains the Now Playing
publisher and remote-command owner. CarPlay queue actions use existing queue entry IDs.

Widget App Groups contain only display metadata, timestamps and opaque song/provider references.
They have separate DEV/PROD containers and do not share credentials, Keychain access groups or reader
databases. Recently Played is device-local, clears on account change, and survives host relaunch.
Recent song buttons resolve the stored provider/ID through the current reader before playing.
Pins remain the native library feature owned by the library-depth lane, pending a widget projection.

Android uses its existing playback service/controller, promoted to `MediaLibraryService`; trusted
system or same-app controllers receive library commands. Media IDs contain opaque references, never
stream URLs or credentials. Issued-item validation and the active account gate precede `playQueue`.
The phone widget sends explicit PendingIntents to that session and stores only display metadata in
private app preferences. TV keeps the shared session/Now Playing behavior without a home widget or
car launcher declaration.

## Signing and activation

| Platform/channel | Extension ID | App Group | Distribution profile |
|---|---|---|---|
| iOS DEV | com.legitimateapps.dulcet.dev.widgets | group.com.legitimateapps.dulcet.dev | Dulcet CI Dev iOS Widgets App Store |
| iOS PROD | com.legitimateapps.dulcet.widgets | group.com.legitimateapps.dulcet | Dulcet CI iOS Widgets App Store |
| macOS DEV | com.legitimateapps.dulcet.dev.widgets | group.com.legitimateapps.dulcet.dev | Dulcet CI Mac Dev Widgets App Store |
| macOS PROD | com.legitimateapps.dulcet.widgets | group.com.legitimateapps.dulcet | Dulcet CI Mac Widgets App Store |

Register both groups, associate the correct group with each channel's host and extension ID, and
refresh host profiles. CI imports optional base64 profiles from
`DULCET_DEV_IOS_WIDGETS_PROFILE_BASE64`, `DULCET_IOS_WIDGETS_PROFILE_BASE64`,
`DULCET_DEV_MAC_WIDGETS_PROFILE_BASE64`, `DULCET_MAC_WIDGETS_PROFILE_BASE64` using the existing
temporary-keychain lifecycle. The archive/export map signs the embedded extension using its own
profile. Missing extension profiles fail signing; this change does not create portal resources or
pretend current profiles already include the new capability.

CarPlay's managed capability is separate. Apple's [CarPlay Simulator instructions](https://developer.apple.com/documentation/carplay/using-the-carplay-simulator)
say the app needs the appropriate entitlement to appear in the simulated car. Simulator builds
therefore request `com.apple.developer.carplay-audio` in their ad hoc signature. Device archives
use `tools/release/carplay-entitlements`: only a named selected host profile with the expected
team/channel application identifier and `carplay-audio=true` enables the audio key. Before Apple's
grant, the device app omits it; after the grant, refresh both DEV and PROD host profiles and the next
archive activates it. The grant cannot change an already installed signed app. These alternate
entitlements must equal the base channel App Group plus only the audio key, enforced by release
policy. Widget entitlements are fixed and cannot inherit the host selector.

## Verification record

- OBSERVED: focused Swift system-surface tests initially passed 3/3: request closure/cancellation,
  account-change rejection, metadata snapshot round-trip and channel group names. Additional cold
  background reconnect and fresh song-shuffle coverage was added afterward; final results pending.
- OBSERVED: the iOS WidgetKit extension compiled and signed for an iOS simulator. The first full
  host build exposed a renamed CarPlay scene-role constant, corrected in source; final build pending.
- OBSERVED: 4/4 provisioning selector tests passed (`python3 tools/test_system_surfaces.py`).
- OBSERVED: XcodeGen regeneration/self-test matched pinned 2.46.0; release policy passed after strict
  widget settings and alternate-entitlement validation were added.
- OBSERVED: `tools/test-release-channel` passed 367 assertions, rejected all 115 policy mutations,
  and passed 37 bundle validations after the widget/CarPlay policy extensions.
- PENDING: final Apple and Android integrations, full relevant test counts, Android Linux host-test
  soak, simulator/emulator OS triggers, widget hosting, CarPlay display/selection, Siri/Shortcuts and
  Assistant delivery. No physical device, portal capability grant, archive upload or release is claimed.

## Scope limitations

Catalog name matching is exact, case/diacritic insensitive, and rejects duplicates rather than
choosing an arbitrary edition. Names shorter than two characters can match only the device cache,
matching the reader's server-search minimum. Recent replay uses the stored identity even for such
titles. Shortcut discovery provides Spotlight entry points; catalog-wide
Spotlight indexing is deferred. CarPlay has Library, Playlists, Recently Played and Queue tabs;
search by voice uses App Intents. Widgets provide play/pause and recent songs; no separate player is
created. ControlWidget requires iOS18+. Catalog shuffle asks OpenSubsonic for a fresh sample of up to
500 songs; Dulcet Server My Library shuffles the owned-song projection. Large browse lists use
explicit continuation folders rather than relying on Android Auto request pagination.
