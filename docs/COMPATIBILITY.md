# Compatibility

Dulcet targets the Subsonic REST API 1.16.1 baseline and negotiates OpenSubsonic extensions at
runtime. Navidrome 0.63.2 is the pinned reference server for the first conformance suite.

Phase 0 does not claim protocol compatibility beyond establishing this contract and the build
targets. Protocol claims become evidence-backed in Phase 1.

## Navidrome playback and scrobble behavior

**OBSERVED 2026-08-28 by CONF-11 through CONF-15, CONF-22, and CONF-23 against Navidrome 0.63.2:**

- A successful raw FLAC stream returned HTTP 200, `audio/flac`, and the `fLaC` signature. A bad id
  on legacy `stream` returned a JSON Subsonic error envelope at HTTP 200; a bad id on
  `getTranscodeStream` returned bare `text/plain` at HTTP 404.
- Raw and extension-transcoded `bytes=0-63` requests both returned HTTP 206 with exact content ranges.
- With pinned Linux ffmpeg 6.1.1, the generated 31-second FLAC fixture produced a 248,455-byte legacy
  MP3 at offset zero and a 128,501-byte MP3 at `timeOffset=15`. Path A defines no offset parameter;
  Navidrome ignored an extra legacy `timeOffset=1` and returned a byte-identical 49,090-byte stream.
- POSTing the conformance `ClientInfo` returned `canTranscode=true` and a 241-character opaque
  `transcodeParams`; forwarding that exact string unmodified returned HTTP 200 MP3 audio.
- `scrobble submission=false` left relative play count at 0 and `submission=true` advanced it to 1.
  Repeating a submitted scrobble with the same item and `time` advanced another fixture from 0 to 1
  to 2. Navidrome 0.63.2 therefore does **not** deduplicate that at-least-once retry shape. This is a
  reference-server observation, not a claim about every Subsonic-compatible server.

## Navidrome library enumeration and paging

**OBSERVED 2026-09-11 against Navidrome 0.63.2**, on two disposable local instances: the CI fixture
corpus (8 albums / 314 tracks / 4 artists) and a generated one (2,500 albums / 5,000 tracks / 100
artists). These are reference-server observations, not claims about every Subsonic-compatible server.

- **`getAlbumList2` silently clamps `size` to 500.** `size=499` returned 499, `size=500` returned
  500, and `size=501`, `1000` and `5000` each returned exactly **500** rows with `status="ok"` and
  nothing in the response marking the truncation. A pager that ends on "fewer rows than I asked for"
  is therefore correct only while it asks for exactly 500.
- **`search3` does not clamp its counts.** `albumCount=501` returned 501, `1000` returned 1,000, and
  `5000` returned the whole 2,500-album library. 500 remains the ceiling Dulcet asks for, because it
  is the documented Subsonic maximum and other servers do clamp there.
- **An empty query enumerates the whole library**, and the four spellings `""` (two literal quote
  characters), the bare empty value, `" "` and `"*"` all behaved identically. A zero count suppresses
  that entity entirely: `artistCount=0&songCount=0` returned a response with no `artist` or `song`
  key at all, so a one-entity page costs one query rather than three.
- **`search3` rows carry everything `getAlbum` carries.** Comparing the two transports over the whole
  of both corpora — 60,000 track field values and 15,048 album field values, parsed by the same
  rules — produced **zero differences**, and every song carried an `albumId` that resolved to a known
  album. Album rows always carried a `duration`, so the sum-of-songs fallback never fired.
- **Ordering under mutation.** Paging the album list at 100 and mutating after page 5, with each run
  asserting the mutation actually happened: under `type=alphabeticalByName` an insertion that sorts
  first produced **one duplicate**, and a deletion before the cursor produced **one skipped album**
  that existed both before and after and was returned by no page. Under empty-query `search3` the
  same insertion produced **zero duplicates and zero skips** — its enumeration is in rowid order, so
  a new row appends — while a deletion before the cursor still skipped one row.
- **`X-Total-Count` is returned as an HTTP header on `getAlbumList2`** (`8` and `2498` against the two
  libraries) and is absent from the response body and from `search3`.
- **The `coverArt` id carries a version suffix**, `al-<album id>_<hex>`, whose hex decodes to a recent
  unix timestamp.

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
