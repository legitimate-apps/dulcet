# Connection ownership and native request audit

2026-10-28 · phase 1 · source audit at `64f0ed5a` (before route integration).

The saved account address is an ownership anchor. A selected transport route never
changes a saved account, media binding, cache binding, provider ID or media epoch.
The strict equality guards below remain in force. A factory route change does not
call a binding method, account replacement, account removal or namespace purge.

`tools/verify_connection_ownership.py --self-test` checks a reviewed expression
inventory (`tools/connection_ownership_inventory.json`) against production Kotlin
and Swift sources. The inventory contains **28 ownership constructions, 22
transport snapshots and 16 equality call sites (the original 15 plus the enrollment ownership fence)**. It scans declarations,
constructor arguments, named Swift/Kotlin arguments and URL-changing `copy` calls;
comments and string literals are excluded. The one reviewed connected-result
URL copy restores `request.serverUrl`, the original ownership anchor, after
authenticating a selected transport route; it does not write a saved account. Five negative fixtures substitute a
route or an intermediate address into account/cache/media constructors and a URL
copy. Each is rejected; the anchor positive control passes. A new expression or a
missing reviewed expression fails closed and requires a fresh source trace.

This is a source regression guard, not a language type checker or complete taint
analysis. A field with an unchanged spelling can still acquire a wrong value
upstream. The source traces and native persistence/executor proofs below are the
complementary checks; the manifest is never regenerated automatically in CI.

## All 15 ownership equality calls

| Source | Calls | Ownership input and effect |
| --- | ---: | --- |
| `apple/DulcetAppleShared/DulcetCorePlayback.swift` | 2 | Retained playback binding versus anchored account; authenticated continuation keeps that same origin. |
| `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidDownloads.kt` | 3 | Accepted anchored account eligibility/deduplication and the in-flight request closure. |
| `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidPlaybackController.kt` | 2 | Resource closure and expected-account lifetime use the unchanged anchored account. |
| `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidMediaBindingIdentity.kt` | 1 | Fast account equality before stored-media continuity fallback. |
| `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/ServerIdentity.kt` | 1 | Saved-principal continuity retains strict saved endpoint matching. |
| `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DownloadPolicy.kt` | 2 | Authenticated adoption and stored media-binding account equality. |
| `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/AccountConnection.kt` | 1 | Canonical saved-endpoint candidate equality, without alias equivalence. |
| `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/SeenCacheStore.kt` | 1 | Stored namespace versus anchor; mismatch still reaches counted purge only for genuine rebind. |
| `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DownloadMediaBinding.kt` | 2 | Shared binding equality and persisted binding comparison. |

## Complete construction and snapshot trace

The machine-readable inventory records every call separately, including repeated
calls in one file. These groups describe the provenance of each URL expression:

| Boundary | Reviewed source |
| --- | --- |
| Android account DTO → playback account (5 calls) | Credential-store `StoredAccount.serverUrl`, sign-out's stored account, downloads' stored account, playback service's stored account and artwork's reader account `normalizedBaseUrl`. |
| Apple account DTO → playback account (4 calls) | Download controller's `DulcetPlaybackAccount.normalizedServerURL` (mapping and configure), playback controller's confirmed saved-principal continuation and presentation account. |
| Android cache/media bindings (3 calls) | Reader and download controller `account.normalizedBaseUrl`; download media initialization uses the same immutable account. |
| Apple cache/media bindings (4 calls) | Reader and download client anchor; download initialization and confirmed principal continuation take anchor-based account values. |
| Core stored binding comparisons (5 calls) | Existing persisted URL, requested `CacheBinding` URL, and previous/first/second anchored media accounts. |
| Conformance-only namespace construction (6 calls) | Lyrics, playlist, rating, star and two Discover fixture request URLs. These are explicit fixture ownership inputs, not selected route values. |
| Common endpoint credential snapshots (7 calls) | Artwork, library browse, playback, scrobble delivery and search retain their request/account anchor; the factory applies the route later. The enrollment-only bootstrap credential snapshot uses the explicitly chosen HTTPS `base` and is never a binding source. |
| Android endpoint/playback/artwork snapshots (10 calls) | Player's initial/refreshed wire and resource/scrobble clients; download initial/refreshed wire/resource clients; artwork request. All retain account anchor. |
| Apple endpoint/playback/artwork snapshots (5 calls) | Player wire/resource clients, download wire/resource clients and artwork request retain anchor. |

`DulcetPlaybackAccount.normalizedServerURL` and Android `StoredAccount.serverUrl`
are populated by the existing saved-account sign-in/restore/continuation flow.
Enrollment adds route records under that account rather than rewriting either
field. Canonical-principal refresh remains strict; a separately typed address
continues to mean separate-account or counted replacement outside preserving
HTTPS enrollment. Reader binds (`AndroidLibraryReader`, `AppleLibraryReaderFacade`)
receive only `account.normalizedBaseUrl`. The factory's credential snapshot keeps
that anchor even after enrollment; route substitution occurs when a request is
prepared or dispatched.

## Native resource paths and MP-10

Every post-upgrade no-HTTP assertion must name and exercise these native paths;
common-core URL tests alone do not establish native executor behavior:

| Platform path | Request and redirect boundary |
| --- | --- |
| Apple AVPlayer / resource loader | `ApplePlaybackWireClient.startPrepareRequest` → `DulcetCorePlaybackResourceContext.authorize`; retain the prepared request's route base for each engine redirect. |
| Apple URLSession downloads | `AppleDownloadClient` preparation → `DulcetCoreDownloadController`; retain task-specific scope, including framing verification and background reconciliation. |
| Apple artwork | `AppleArtworkFetchClient` → shared `ArtworkFetcher` / `AuthenticatedEndpointClient`; factory resolves the enrolled HTTPS route on each actual request. |
| Android player | `AndroidPlaybackController` resource closure → `HttpPlaybackResource` / shared endpoint client; ownership guard still compares anchor accounts. |
| Android download worker | `AndroidDownloadController` transfer closure → shared endpoint client; ownership and DownloadId stay unchanged. |
| Android artwork | `AndroidArtworkRepository` → shared `ArtworkFetcher` / endpoint client; cache key remains provider/artwork/size. |

After preserving HTTPS enrollment the saved HTTP URL remains provenance. No
request path, resume path, redirect or relaunch may fall back to it when every
HTTPS route is unavailable. Legacy accounts without enrollment retain their
existing explicit local-HTTP behavior. Runtime evidence is recorded separately;
this audit by itself is not MP-02 or native MP-10 completion evidence.

## Enrollment fence additions

The phase-1 enrollment commit adds one `DownloadMediaBinding` construction from
`anchor.normalizedBaseUrl` and one strict `sameEndpoint` comparison of the stored
cache URL with that same frozen anchor. These validate the durable owner captured
before authentication/probing, then recheck it inside the commit transaction. They
never take the bootstrap or selected route URL. The post-integration inventory
therefore includes these additions alongside the original 15 equality guards; the
original guards remain strict. The explicit-bootstrap endpoint credential snapshot
is transport only and is not used to construct a binding or account.
