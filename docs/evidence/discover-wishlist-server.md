# Discover and Wishlist server evidence

OBSERVED 2026-10-22. This implements step 1 of the
[approved design](../superpowers/specs/2026-10-22-discover-and-wishlist-design.md), on
`feat/discover-server`. The first sixteen task commits end at `5c32ad1b`; the final task adds the
real HTTP integration, the timestamp-retry correction and this evidence. The PR head records the
complete source, final main integration and CI runs. No core or application support is inferred
from server tests.

## Test environment and boundaries

Every behavioral upstream is `FixtureDiscoverUpstream` on loopback. Tests exercising outside clients set MusicBrainz,
Cover Art Archive, ListenBrainz and labs bases explicitly; the real HTTP integration also obtains
its disposable server base from the bound CIO connector. Unknown fixture requests fail rather than
fall through to the internet. Metadata fixture provenance is in
[the fixture README](../../server/src/test/resources/discover/README.md): recorded CC0 responses
are labelled separately from synthetic edge cases. Cover images from live services are never
committed; test image bytes are generated signatures/placeholders. No automated test calls a
public music service. Tool/package resolution is not a behavioral upstream.

The full build runs under the build lease. Focused tests use per-task `--rerun`; the full run uses
`--rerun-tasks`. Counts below come from JUnit XML, not console test estimates. Logs and intermediate
red/green receipts are retained by the lane owner; the public repository contains fixtures and
executable tests, not machine-specific paths.

## Focused evidence

| Area | Test class | Tests in its task's final focused run | Observation |
|---|---|---:|---|
| Settings | DiscoverConfigTest | 3 | Independent switches/defaults, four bases, redacted optional token, public MB gap floor |
| Polite transport | PoliteHttpTest | 5 | Serialized origin queue, 1-second gap, bounded waiting/body, Retry-After and LB reset/backoff |
| Cache | DiscoverCacheTest | 4 | Shared raw metadata, single-flight, stale age, malformed response safety, TTL/LRU/negative cache |
| MusicBrainz | MusicBrainzClientTest | 4 | All search kinds, safe relations, browse paging and complete edition slots |
| ListenBrainz and shelves | ListenBrainzClientTest, DiscoverShelvesTest | 4 + 3 | Observed response shapes, week/fresh/JSPF/labs parsing, duplicate slots, private seed assembly |
| Artwork | DiscoverArtworkTest | 4 | Pass-through signatures and 5 MiB cap, five redirects, checked addresses, downgrade/header policy |
| Scanner identity | DiscoverScanTagsTest, ScannerIdentityRegressionTest | 3 + 2 | Additive release-group tag storage and existing identity regression controls |
| Matching | DiscoverMatcherTest | 5 | MBID/name/version priority, visibility, one-to-one edition slots, partial/unknown completeness |
| Durable wishes | WishlistStoreTest | 7 | Canonical dedupe, latest-action/remove ties, receipt/cancellation restart and rollback, 30-day boundary |
| State and rejection | WishlistStateTest | 5 | All sixteen open-state transitions, live admin/revision/note rules, pair persistence/reopen/membership |
| Post-scan work | WishFulfilmentTest | 8 | Song/album/artist membership, 8/12, candidate-only lookups, outage/restart, latched removal, rollback, CLI drain |
| Discover API | ApiDiscoverTest | 6 | All paths/scopes/switches, private ETags, caller/query/generation cursors, bounded paging and outages |
| Wishlist API and feed | ApiWishlistTest, WishlistSyncTest | 4 + 3 | Ownership/scopes, cached removed-add replay, history/redaction/ETags, own tombstones, old filters, disabled feed |
| Webhooks | WishlistWebhooksTest | 4 | Admin broadcast/owner isolation, dedupe and re-add, rollback, retry exact bytes/signature, authority loss |
| REST | RestDiscoverWishlistTest | 6 | Independent advertisements, JSON/XML/JSONP arrays/escaping, actual-change clock, switches/errors and restart cancellation |
| MCP | WishlistMcpTest | 4 | Capability and scope gates, bounded schemas/open states, fixture search, shared durable add across API/REST/MCP |
| Existing MCP profile | McpCapabilityTest in dulcet-api-conformance | 1 | Optional tools require advertised version 1 AND scope; absent/unknown versions cannot become mandatory |
| Real server integration | DiscoverWishlistIntegrationTest | 4 | Production HTTP/scanner/worker/webhook chain and restart cases below |

Existing regression classes included in the focused task receipts are ApiSyncVisibilitySecurityTest
(3), ApiWebhooksTest (12), RestAuthorizationSecurityTest (2), ApiMcpTest (5) and the identity tests
above. Focused task totals overlap intentionally and must not be added as a unique full-suite total.

## Real HTTP chains

`discoverWishWebhookScanArrivedLibraryAndFeed` starts a real CIO listener and persistent SQLite,
searches a fixture recording through HTTP, adds a snapshot wish, probes actual generated/tagged
FLAC through the production scanner, and drains the production post-commit queue. HTTP then shows
fulfilled with the exact local id, one corresponding My Library membership, and a fulfilled feed
resource. A second user sees no membership or wish changes. The production webhook transport sends
to the loopback receiver; an independent HMAC calculation verifies id.timestamp.body. Exactly one
admin requested delivery appears and owner changed data includes arrival.

`partialCachedOutageRejectedPairAndAlternativeArrivalSurviveRestart` scans eight of twelve edition
tracks and observes an open 8/12 wish over HTTP. Cached edition metadata remains usable when the
fixture changes to 503; the remaining tracks fulfil. Rejecting its linked local album reopens
wanted and retains membership. After restarting with Discover off, a different complete local
album fulfils from cache, without another upstream request; the rejected pair persists.

`wishlistDisabledRetainsRowsAndResumesPendingScan` retains the open wish and durable scan candidate
while Wishlist is off, refuses its HTTP resource and fulfils on restart after re-enabling it.
No outside request is made. `futureActionTimeClampDoesNotChangeRetryFingerprint` reproduces and
fixes a future timestamp retry conflict: the original action time remains the receipt fingerprint,
while ordering clamps to server receipt time. Retry returns the original wish rather than 409.

Additional service race evidence is deliberately separate: latched metadata removal and rollback
are WishFulfilmentTest observations, not claims about a device UI or a production deployment.

## Full verification

```sh
./gradlew :server:test :server:licensee :server:installDist :dulcet-api-conformance:test --rerun-tasks
npx --yes @redocly/cli@2.54.3 lint docs/dulcet-api/openapi.yaml --config docs/dulcet-api/redocly.yaml
python3 tools/parity_gate.py
python3 tools/verify_ci_policy.py
git diff --check
```

OBSERVED full run on 2026-10-22: **244 server tests** across 50 suites and **61 API-conformance
self-tests** across 2 suites, each with **0 failures, 0 errors and 0 skips**, read from
`server/build/test-results/test/TEST-*.xml` and
`dulcet-api-conformance/build/test-results/test/TEST-*.xml`. All 15 Gradle tasks executed;
`:server:licensee` and `:server:installDist` passed. Strict OpenAPI lint passed, the parity gate
validated 40 rows, CI policy validated 13 workflows and `git diff --check` passed.
Main integration and remote CI are recorded in the PR.
The MCP conformance self-tests exercise their existing mocked reference server and deliberate
fault controls. They do not execute the planned DA-DISCOVER-1 or DA-WISHLIST-1 profiles.

## Contract and operational decisions

- Search 1 hour; entity/playlist metadata 24 hours; trending/fresh 6 hours; similar artists 7 days;
  thumbnails 30 days; negatives 24 hours. Valid stale answers are usable for at most 90 days. Raw
  cache budget is 256 MiB including bounded cursor snapshots; no user data enters public cache keys.
- Every source/client base is configurable. Public MusicBrainz has a 1000 ms minimum gap; mirrors
  can lower it. Rate headers can delay the whole origin without an automatic retry storm.
- Artwork passes bytes through without ImageIO or dimension parsing, with positive JPEG/PNG/GIF/WebP
  magic and a 5 MiB limit. Five redirects accommodate CAA/archive/storage. Checked DNS is pinned,
  credentials stripped and HTTPS downgrade refused. Failed bytes are never served.
- Album completeness uses one known edition in one local album; versions and ambiguous name matches
  stay open. Unknown edition data delays fulfilment. Only open-wish candidates trigger release-to-group
  lookups. Rejected pairs and receipts last until user deletion, not only the HTTP retry-cache TTL.
- API reads, mutations, feed snapshots and REST projections redact inaccessible local references.
  Visibility changes invalidate wish revisions and ETags. Existing pre-wish sync cursors continue
  their verified filter when types is omitted. List generation changes expire continuation cursors.
- The REST contracts are complete client seams:
  [Discover](../dulcet-server/dulcet-discover-extension.md) and
  [Wishlist](../dulcet-server/dulcet-wishlist-extension.md). All adapters share the same domain logic.

## Remaining lanes and PROD conditions

Core transports, account-scoped readers/cache/outboxes/native boundary, native tests and every app
UI are later lanes. This includes the real Navidrome client control proving zero new requests/UI,
and the design's full disposable-server conformance profile. All seven feature rows keep all six
platform cells planned. Server tests cannot promote them.

Design §10 remains unresolved before PROD: current MetaBrainz terms for independent free-software
servers, current ListenBrainz rate-limit headers/values, and Cover Art Archive thumbnail-cache terms.
If terms disallow outside lookup defaults, default them off; if thumbnail caching is disallowed,
proxy without keeping a copy. The implementation obeys observed header shapes but fixture tests
are not a current legal/terms review. LB Radio/token use, preview audio, acquisition and playlist
wishes remain outside v1. The owning session arranges one independent PR review; this lane does not
request reviewers or merge.
