# Android listening sessions

The phone and Android TV share the playback service, core queue decisions and Media3 adapter.
The phone puts listening options in Up Next. TV puts a focused Listening options button below
artwork, with scrollable remote choice dialogs; queue actions use Move Up/Down and Remove.

## Host coverage

- `AndroidMedia3ListeningSessionTest`: timed sleep on elapsed time, ten-second gain fade, cancel,
  end-of-track and end-of-queue stop points; AutoPlay answer identity, queue order and switch-off;
  confirmations after application, empty-player behavior; saved crossfade and album boundary;
  controller preload promotion, incoming failure cleanup and stable media-session bridge.
- `AndroidMedia3EngineTest`: genuine simultaneous player intent and fade gains, one natural end at
  handoff, no reporting listener on the outgoing tail, one counted play per progressing session;
  a zero boundary creates no second player and ends through the existing path.
- `PhoneListeningSessionTest`: AutoPlay switch, every sleep choice, cancel, crossfade range,
  AutoPlay section and one-shot queue notices.
- `TvListeningSessionTest`: D-pad choices and scrolling, the integrated long-title/rating/queue
  layout and queue notice delivery.

These tests observe adapter/controller behavior. They do not measure OS mixer output, perceived
loudness or sample continuity; acoustic gaplessness remains unclaimed.

## Phone runtime proof

`AndroidEmulatorListeningSessionProofTest` is opt-in instrumentation against a disposable
Navidrome. It drives Up Next and its AutoPlay switch, observes server candidates after the user
entry and their removal on switch-off, selects 15 minutes, checks the service countdown after Home
with the observer unbound, cancels it, selects End of track and seeks near the end to observe the
next entry selected paused. Its output includes compact accessibility labels and service state.
Runtime results and host repetition counts are recorded after execution.
