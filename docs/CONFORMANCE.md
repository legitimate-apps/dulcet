# Conformance registry

This registry reserves stable identifiers for the tests required by the design. An identifier remains
stable when its test moves from planned to executable; the design's representative-test table carries
the detailed assertion.

**This registry includes protocol, sync-consistency, presentation, and platform-security contracts.**
For example, CONF-09b/09c cover render-state inventory and actionable errors, and CONF-10a/10e cover
secure storage and observed Keychain attributes. A `conformance` row cites one of these registered
contracts. An `observes` row carries a supplementary claim for which no matching id is registered;
its distinction is the absence of a matching registry id, not an exclusion of platform or UI behavior
from this registry (design spec §19.2).
Citing a test under either shape proves that one named test executed and passed — it is not a status
promotion, and it does not by itself justify moving a cell to `shipped`. When a cell strengthens from
`planned` through `blocked`, `partial`, and `shipped`, the changed document must add at least one
complete evidence row that was absent from that cell in the base document. Whitespace runs and edges
in `observes` are normalized for novelty; prose words, case, punctuation and identifiers remain exact. Reordering
a row's keys, or editing `reason` or `promotion_condition`, does not meet that requirement. A missing `evidence`
value and `evidence: null` are both the empty set; `n/a` is outside the ordered statuses. A future
promotion that cannot structurally gain CI evidence must be declared in the initially empty
`accepted_promotions` list with the cell id, platform, a non-empty reason, and a `#<number>` PR.
Promotion exceptions and `accepted_regressions` are deliberately independent. The base document is
`origin/$GITHUB_BASE_REF:FEATURES.yml` when requested, otherwise `HEAD^:FEATURES.yml`. A missing
base is an error naming that document; the gate never substitutes a different base or skips comparison.

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
| CONF-70 | a scanned deletion between two window pages changes the post-page `getScanStatus`; the race finds zero violations under the bracketed check |
| CONF-71 | `getScanStatus` readable by a non-admin user and unmoved by effective user-state writes, with a no-op scan as the positive control |
| CONF-72 | per-user state in catalog payloads reflects the reading user only |
| CONF-73 | `/rest` JSON reads carry no HTTP validators and a conditional request returns a full `200`, with a header positive control |
| CONF-74 | album removal behaviour under both `PurgeMissing` settings |
| CONF-75 | `X-Total-Count` presence per `getAlbumList2` type, as a total rather than a page length, and its absence on `search3` |
| CONF-76 | a cached open publishes before any request or loading state; a never-opened album offline is `unavailable` |
| CONF-77 | reconnect performs only its declared reads, by counted requests |
| CONF-78 | seen-cache eviction order, with pinned metadata surviving every ceiling |
| CONF-79 | search scope is published correctly in each of its four cases |
| CONF-80 | a seen-cache namespace bound to a different account is purged before any row is served |
| CONF-81 | reader migrations preserve protected data, pin downloads and queue entries, and seed the verified mirror |
| CONF-82 | production reader window tearing, rebasing, scanning mode and anchor keeping; no-epoch and unknown-total handling |
| CONF-83 | production reader gone-ness under both server settings |
| CONF-84 | optimistic favourite and rating overlay |
| CONF-85 | download enqueue pins metadata atomically; downloaded albums rechecked after an epoch change |
| CONF-86 | multi-list screen rows publish independently |
| CONF-87 | bounded detail look-ahead |

## Account-connect evidence boundary (CONF-09b)

CONF-09b is an explicit gap on all four Apple `account.connect` cells and on both Android cells.

**Apple.** The shared `accountPresentationTransitionsGivenConnectorOutcomes` test submits through the
production `DulcetPresentationStore` into `DulcetAccountDataSource` and reads real snapshots, but it
injects the connector's completed outcomes and controls the credential store's load and save. It
checks each step against the one state that step must produce — idle, connecting, connected, a saved
account reconstructed from the credentials the successful submission actually saved, the state for
each injected failure kind from a table written out in the test, and the save-failure path — so two
outcomes exchanging their states fail it. It does not prove that production can originate each
outcome or forward it through the Apple adapter. It runs in the macOS package run
(`DulcetKitTests`), on the iPhone and the iPad (`DulcetKitIOSTests`) and on Apple TV
(`DulcetKitTVOSTests`), and each cell cites it as a bounded `observes` row, never as CONF-09b.

The tests these cells cited for CONF-09b before are kept, each as an `observes` row stating the one
thing it shows: `fixtureRendersEveryDeclaredDistinctState` (macOS) that the deterministic fixture
covers every declared state; `accountConnectRootLoadsForIOS` and `accountConnectRootLoadsForTVOS` that
the root loads in one fixture state at the platform's window size; and
`testAccountConnectUsesRegularWidthSplitLayout` (iPadOS) the regular-width split layout. Un-citing
them would leave each cell's reason describing a test that nothing checks still runs.

**Android.** `conf09bEveryDeclaredDistinctRenderStateIsReachable` reaches the view model's six render
states through injected gateway results (`ImmediateGateway`, `CancellableGateway`) and an injected
failing credential store — the same boundary as the Apple test. It is cited as a bounded `observes`
row on the `android` and `androidtv` cells, and CONF-09b is named as a gap there for the same reason:
the production gateway originating each outcome, and a production credential save failing, are not
observed. The capability point below does not arise on Android, which declares one failure render
state for every error.

`unevidenced_conformance` maps a declared CONF id to a nonblank reason, and the parity gate refuses a
`shipped` cell that carries one. When a cell cites at least one conformance row, or is `shipped`, the
gate also requires the evidence and the named gaps to partition the cell's declared ids with no
overlap; a cell citing only `observes` rows may name any subset of its declared ids as gaps. This
keeps the universal CONF-09b requirement visible instead of satisfying it with a narrower test.

### `Capability.Unsupported` has no account-connect origin

OBSERVED from source on 2026-09-25. `DomainError.CapabilityUnsupported` and `CapabilityFeature` are
declared in `core/.../AccountConnection.kt`. Production constructs it only in `LibrarySync.kt`, for a
server that cannot enumerate the whole library and for a walk that will not terminate; every other
production occurrence is a type match in a diagnostic or facade mapping. On the account-connect path,
`AccountConnector.connectNormalized` treats an extension-list 404 or non-envelope as
`legacySubsonic = true` and proceeds after an authenticated `ping`; malformed metadata is
`Protocol.MalformedEnvelope`, an incompatible version is `Protocol.Incompatible`, and parsed server
errors go through `AccountConnectionContract.mapSubsonicError`. None of them yields a capability
error, which agrees with spec §10.3: absent discovery must not fail a baseline login. §10.4's
three-failure circuit breaker has no production implementation in `commonMain`, so it supplies no
origin either. The Apple and Android capability error presentations are therefore reserved
vocabulary for account setup: the Swift conditional test injects it, and the Android CONF-09c
presentation control constructs it directly.

## CONF-51 citations on the Apple download cells are incomplete

CONF-51 requires live exact **and** cold-estimated bodies to validate before atomic promotion, an
exact mismatch never to reach the destination, and duplicate delivery to be idempotent. The macOS,
iOS and iPadOS `downloads.offline` cells cite
`DulcetAppleDownloadIntegrationTest/downloadTriggerPromotesValidatedResponseAtomically[OnIOS|OnIPadOS]`,
which observes one successful promotion through the platform executor: the downloaded state, exactly
one promoted file, no remaining `.partial`, and a stored size equal to the asset's exact length. It
exercises no cold estimate, no exact mismatch and no duplicate delivery. The core conformance control
`conf51LiveDownloadsValidateBeforeAtomicPromotion` covers those three against the reference server,
but through `DownloadPolicyContract`, not the Apple executor. This finding is open: nothing here
changes a citation or a status.

A naming rule — the CONF id must appear in the cited test's name — was considered as a gate for this
class of defect and rejected. In the citation audit that found these two defects it flagged 26
legitimate descriptive citations against 7 weak ones (four CONF-09b, three CONF-51), and a renamed
test that still injects its outcome would pass it; naming does not establish semantic coverage.
