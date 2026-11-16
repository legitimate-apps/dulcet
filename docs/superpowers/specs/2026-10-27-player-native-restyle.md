# Native player presentation

Status: implemented presentation target, visual review pending, 2026-10-27.
This supplements the Apple native presentation refresh. Playback policy, server writes and the
queue-entry, playback-session and attempt identities retain their existing contracts.

## Sources

DOCUMENTED, retrieved 2026-10-27:

- [Music player controls on iOS 27](https://support.apple.com/guide/iphone/use-the-music-player-controls-iph676daac9b/27/ios/27):
  the MiniPlayer names the current song, offers transport and opens Now Playing. The expanded player
  provides seeking, volume, Favorite, More, lyrics, output selection and queue access.
- [WWDC26: What's new in SwiftUI](https://developer.apple.com/videos/play/wwdc2026/269/),
  Refreshed look and feel at 2:12: standard controls adopt the current appearance; adaptive layout
  and system toolbar behavior belong to the platform.
- [HIG: Materials](https://developer.apple.com/design/human-interface-guidelines/materials) and
  [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass):
  prefer native bars and controls; reserve glass for navigation and floating controls, with
  accessibility-aware fallbacks.
- Xcode 27 bundled guides `SwiftUI-Implementing-Liquid-Glass-Design.md` and
  `SwiftUI-New-Toolbar-Features.md`: standard controls, explicit grouping, adaptive placement and
  availability checks are the implementation reference.
- [Core Audio output volume](https://developer.apple.com/documentation/audiotoolbox/kaudiohardwareservicedeviceproperty_virtualmainvolume):
  macOS output devices can expose an aggregate software-volume property. An output with no writable
  control must not imply that software can change its volume.

## Presentation decisions

- One player background across artwork, lyrics and queue. A small cover sample supplies a smooth
  gradient, with a window-colour scrim for semantic labels. Reduce Transparency uses the opaque
  window surface. Cover shapes are not magnified into the reading area.
- Track title and artist lead. Favorite stays a heart and uses the existing optimistic server-star
  path. Ratings, sleep timer, audio format and track navigation are in More. The bottom row contains
  Lyrics, AirPlay and Queue on iPhone, iPad and Mac; TV has Lyrics and Queue and names its output.
- The native iOS volume view controls the active output. macOS observes the default output's
  aggregate volume through Core Audio and reads back changes, including refused writes. Digital
  outputs without software volume show a disabled control. TV volume belongs to the remote.
- Queue and lyrics retain transport. Compact layouts swap the centre region; regular layouts put
  queue or lyrics in the trailing column. Upcoming rows have native edit/reorder/delete support
  where available, with a visible More menu for equivalent actions on every platform.
- MiniPlayer content keeps its public initializer and accessibility identifiers. Its host owns the
  accessory material and placement. Inline accessory placement drops the subtitle before squeezing
  transport. Buffering keeps the current song visible and names the state.

OBSERVED in `DulcetCorePlaybackController`: Apple Previous is enabled by a preceding queue entry or
repeat-all. The baseline's first-track disabled Previous follows that existing contract. This
presentation change does not adopt Android's restart-by-seek rule or alter playback sessions.

## Evidence still required

Review MiniPlayer, Now Playing, Up Next and Lyrics on iPhone light/dark/largest text, iPad landscape,
Mac light/dark and TV with focus. Also photograph paused, buffering, no-track, long-title and
no-artwork states. Captures and independent review determine visual acceptance; a compile is not
visual approval. Physical-device output volume and AirPlay need their own runtime evidence.
