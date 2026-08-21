# Compatibility

Dulcet targets the Subsonic REST API 1.16.1 baseline and negotiates OpenSubsonic extensions at
runtime. Navidrome 0.63.2 is the pinned reference server for the first conformance suite.

Phase 0 does not claim protocol compatibility beyond establishing this contract and the build
targets. Protocol claims become evidence-backed in Phase 1.

## Apple custom-scheme resource loading

**OBSERVED 2026-08-21:** on the standard GitHub-hosted `macos-26` runner with Xcode 26.4.1, the
strengthened Phase 1 spike established these deliberately bounded results:

- A progressive MP3 at `dulcet-stream://fixture/progressive.mp3` produced delegate data requests with
  explicit offsets and lengths. The delegate, rather than an independent URL loader, supplied the
  bytes used by `AVPlayer`.
- An HLS manifest at `dulcet-stream://fixture/playlist.m3u8` reached the delegate. Before returning
  it, the delegate resolved two absolute media URIs on different source origins, stored those
  original URLs in loader-owned routing state, and rewrote both to opaque relative route tokens.
  Resolved against the manifest's `dulcet-stream://` URL, every token remains under that custom
  scheme and returns to the same delegate.
  `segment0.aac` and `segment1.aac` then each reached the delegate, and playback advanced beyond the
  first segment boundary without an `AVPlayerItem` failure.

The checked-in spike synthesizes its tone fixtures at runtime; no audio blob is committed. Before the
custom-scheme measurement it plays an equivalent two-segment HLS playlist through a localhost HTTP
server past the first segment boundary. The custom-scheme leg does not stop on the first callback: it
requires both named segment callbacks, playback time of at least 1.20 seconds, and no swallowed
playback error. A fault-injection run kills the second segment after the first routes; `apple-ci`
requires that invocation to fail before it runs the passing measurement.

**Chosen product contract:** Dulcet does not rely on AVFoundation preserving or routing arbitrary
manifest topology. Before a manifest is returned, the production loader resolves and rewrites every
fetchable URI — media segments, variant playlists, encryption keys, and initialization maps — into an
opaque route token that resolves under the manifest's custom scheme and is backed by the loader's
table of original absolute URLs. The spike executes
the absolute, cross-origin media-segment case through `AVPlayer` and separately asserts that the same
rewriter covers URI-bearing key/map tags and nested-playlist lines.

Evidence identity: workflow `apple-ci`, job `apple-ci`, steps `Prove the measurement rejects
first-segment-only routing` and `Measure progressive and rewritten HLS resource routing`, tool
`tools/resource-loader-spike`. Branch protection requires `apple-ci`, so failure blocks merge. This
does **not** establish the behavior of unrewritten HLS URIs, runtime key retrieval/decryption,
`URLSession` range correctness, seeking, redirect policy, authentication headers, cancellation, or
inline envelope validation. Those remain production-loader work with their own Phase 1 evidence.
