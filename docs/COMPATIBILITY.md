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
  original URLs in loader-owned routing state, and rewrote both to opaque relative route tokens that
  resolve under the manifest's custom scheme. `segment0.aac` reached the delegate. Playback then
  failed with `CoreMediaErrorDomain -12881` before `segment1.aac` reached the delegate and before the
  player crossed the first segment boundary.

The checked-in spike synthesizes its tone fixtures at runtime; no audio blob is committed. Before the
custom-scheme measurement it plays an equivalent two-segment HLS playlist through a localhost HTTP
server past the first segment boundary. The custom-scheme assertion does not stop on the first
callback: success requires both named segment callbacks, playback time of at least 1.20 seconds, and
no playback error. The observed playback error is surfaced and classified, not discarded. In
`apple-ci`, an `all-routed` invocation must reject the observed first-only state before a second
invocation records that exact state as the expected compatibility result.

**Chosen product contract:** Apple refuses playback plans with `protocol: "hls"`; Path A must request
a progressive container. The manifest rewriter proved it can normalize media, nested-playlist, key,
and initialization-map references, but the `AVPlayer` result did not prove complete HLS playback
routing, so Dulcet does not make that mechanism a production dependency. Progressive custom-scheme
loading remains supported by the observed MP3 byte-range result.

Evidence identity: workflow `apple-ci`, job `apple-ci`, steps `Prove the measurement rejects the
observed first-only state` and `Record progressive routing and Apple HLS fallback`, tool
`tools/resource-loader-spike`. Branch protection requires `apple-ci`, so failure blocks merge. This
does **not** establish that `segment1.aac` escaped to another loader; playback failed before the
second request. It also does not establish unrewritten HLS behavior, runtime key retrieval/decryption,
`URLSession` range correctness, seeking, redirect policy, authentication headers, cancellation, or
inline envelope validation. None of those unknowns weaken the Apple progressive-only decision.
