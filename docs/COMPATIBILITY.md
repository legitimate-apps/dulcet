# Compatibility

Dulcet targets the Subsonic REST API 1.16.1 baseline and negotiates OpenSubsonic extensions at
runtime. Navidrome 0.63.2 is the pinned reference server for the first conformance suite.

Phase 0 does not claim protocol compatibility beyond establishing this contract and the build
targets. Protocol claims become evidence-backed in Phase 1.

## Apple custom-scheme resource loading

**OBSERVED 2026-08-20:** on the standard GitHub-hosted `macos-26` runner with Xcode 26.4.1,
`AVAssetResourceLoaderDelegate` receives the media data for both delivery shapes measured by the
Phase 1 spike:

- A progressive MP3 at `dulcet-stream://fixture/progressive.mp3` produced delegate data requests with
  explicit offsets and lengths. The delegate, rather than an independent URL loader, supplied the
  bytes used by `AVPlayer`.
- An HLS manifest at `dulcet-stream://fixture/playlist.m3u8` reached the delegate, after which a
  relative packed-AAC media-segment request separately reached the same delegate. This answers the
  routing question: media loading does not escape to an unrelated URL-loading path.

The checked-in spike synthesizes its tone fixtures at runtime; no audio blob is committed. Before the
custom-scheme measurement it plays the identical two-segment HLS playlist through a localhost HTTP
server, observes both HTTP segment requests, and requires playback time to advance, so a malformed
manifest or unusable segment cannot produce a false negative. The custom-scheme leg stops as soon as
the first media-segment delegate callback proves the routing outcome. The workflow requires the
recorded `routed` outcome and errors on drift.

Evidence identity: workflow `resource-loader-spike`, job `resource-loader-spike`, tool
`tools/resource-loader-spike`. This establishes routing behavior only. Multi-segment serving, range
correctness, seeking, redirect handling, authentication headers, cancellation, and inline envelope
validation remain production-loader work with their own Phase 1 evidence.
