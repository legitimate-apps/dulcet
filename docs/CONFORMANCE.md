# Conformance registry

This registry reserves stable identifiers for the tests required by the design. An identifier remains
stable when its test moves from planned to executable; the design's representative-test table carries
the detailed assertion.

**This registry is server-protocol semantics, and nothing else.** Every id below is a wire or
sync-consistency contract (generation-pinned reads, atomic commit, envelope shape, and so on) that this
project has committed to testing. It is not a place to register that a platform actually wires a
capability up — a UI activating, a build launching the production client, a real device rendering a
layout. `FEATURES.yml` evidence has a second row shape for exactly that claim, carrying `observes`
prose instead of a `conformance` id (design spec §19.2). A `conformance` row asserts a contract
against this registry; an `observes` row asserts a platform observation the registry has no id for.
Citing a test under either shape proves that one named test executed and passed — it is not a status
promotion, and it does not by itself justify moving a cell to `shipped`. When a cell strengthens from
`planned` through `blocked`, `partial`, and `shipped`, the changed document must add at least one
complete evidence row that was absent from that cell in the base document. Reordering a row's keys,
or editing `reason` or `promotion_condition`, does not meet that requirement. A missing `evidence`
value and `evidence: null` are both the empty set; `n/a` is outside the ordered statuses. A future
promotion that cannot structurally gain CI evidence must be declared in the initially empty
`accepted_promotions` list with the cell id, platform, a non-empty reason, and a `#<number>` PR.
Promotion exceptions and `accepted_regressions` are deliberately independent.

| id | assertion |
|---|---|
| CONF-01 | unauthenticated extension discovery |
| CONF-02 | reference-server extension set |
| CONF-03 | baseline salted-token authentication and invalid credentials |
| CONF-04 | version compatibility |
| CONF-05 | OpenSubsonic envelope fields |
| CONF-06 | transport reachability and server-error mappings |
| CONF-07 | wire-observed credential channels and credential-free diagnostics |
| CONF-07b | form-POSTed credentials on `stream` |
| CONF-08 | fail-closed request-channel inventory; same-origin preservation and pre-send cross-origin refusal on every account-connect hop |
| CONF-09a | account-connect progress and active-operation cancellation |
| CONF-09b | account-connect render-state inventory |
| CONF-09c | total actionable account-error presentation |
| CONF-10a | unavailable platform-secure credential storage fails closed with a typed reason, no active-account pointer, and no weaker fallback |
| CONF-10b | explicit reconnect after persisted-credential prefill |
| CONF-10c | a 407 proxy-auth challenge rejects ambient credentials |
| CONF-10d | restricted-user permission errors map to `Auth.Forbidden` |
| CONF-10e | a production Apple credential-store save records `AfterFirstUnlockThisDeviceOnly` accessibility and a non-synchronizable item, observed by an unconstrained attribute read-back; this does not claim protection enforcement or device-equivalent simulator semantics |
| CONF-11 | successful stream content type and signature validation |
| CONF-12 | legacy-stream and extension-stream error status and shape |
| CONF-13 | raw and transcoded ranged-stream behavior |
| CONF-14a | legacy `transcodeOffset` seek |
| CONF-14b | `transcoding` extension offset behavior |
| CONF-15 | POST transcode decision and opaque-parameter stream round-trip |
| CONF-16 | transcode concurrency limiter returns 429 with `Retry-After`, mapped to `Server.Busy` |
| CONF-17 | cold legacy-transcode `Content-Length` estimate semantics |
| CONF-21 | whether `playbackReport` increments play count — not a Phase-1 test |
| CONF-22 | `submission=false` versus `submission=true` play-count behavior |
| CONF-23 | repeated same-time scrobble deduplication behavior |
| CONF-31 | generation-pinned reads |
| CONF-32 | atomic sync-generation commit |
| CONF-33 | bounded stability witness |
| CONF-34 | `getIndexes?ifModifiedSince` behavior and granularity |
| CONF-35 | paging past the end returns an empty list, not an error |
| CONF-41 | local and server search merge |
| CONF-42 | `songLyrics` v2 structured response shape |
| CONF-43 | `search3` local-versus-server matching divergence |
| CONF-44 | `getCoverArt` size behavior, content types, error envelopes, and image signatures |
| CONF-51 | live exact and cold-estimated bodies validate before atomic promotion; exact mismatch never reaches destination and duplicate delivery is idempotent |
| CONF-52 | a live item promoted locally yields a `LocalPlaybackPlan` and identical bytes after all conformance network clients close |
| CONF-61 | unknown response fields are preserved and ignored |
