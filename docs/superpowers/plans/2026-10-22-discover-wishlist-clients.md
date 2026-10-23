# Discover and Wishlist Clients Implementation Plan

> **For agentic workers:** Test first; commit each completed task. The owning session assigns the three builder parts and arranges review. Steps use checkbox (`- [ ]`) syntax; app-specific execution sub-skills do not apply.

**Goal:** Deliver design §7 step 2: core, then Search Everywhere, artist/album pages, wish add/remove and Library › Wishlist on iPhone, iPad, Mac and Android phone.

**Architecture:** Protocol-neutral transports feed an account-scoped reader and durable wish outbox. This step implements only the OpenSubsonic extensions, through existing reader admission, credentials, reconnect and retirement. Native shells consume snapshots and commands; matching and fulfilment remain server decisions.

**Tech Stack:** Existing Kotlin Multiplatform, coroutines, Ktor, kotlinx.serialization, SQLDelight, Objective-C framework interop, SwiftUI and Android Compose. No new runtime dependency.

**Spec:** `docs/superpowers/specs/2026-10-22-discover-and-wishlist-design.md` §§5–8, 11; main spec `docs/superpowers/specs/2026-08-18-dulcet-design.md` §§28, 29.9, 30.3; `docs/dulcet-server/dulcet-discover-extension.md`, `docs/dulcet-server/dulcet-wishlist-extension.md`; `CORPUS.md` §4.8; `docs/TRAPS.md` 4–5, 17–19, 29–32.

## Global Constraints

- **Step 2 uses `/rest` only, with no OAuth**, as the approved design says (§5: the apps use the `/rest` extensions today and need no new auth path; main spec §30.3). Whole-app Dulcet API adoption, authentication and change-feed migration belong to one later lane; keep transport interfaces neutral for that move. Existing scan-clock brackets remain in use.
- Detect `dulcetDiscover` v1 and `dulcetWishlist` v1 independently through existing `getOpenSubsonicExtensions` on any server, including Dulcet Server. Neither advertised means no new feature request or UI. Product names and API discovery do not grant these capabilities.
- “Every outside lookup is made by the server.” Use server artwork ids; open safe listen/buy links only on explicit action. No previews, acquisition, upstream accounts, shelves, playlist pages or TV UI in this step; no fifth Discover tab yet.
- Use existing account `/rest` credentials, salt, formPost and query-redaction rules. Validate artwork in the actual engine request: error-envelope detection before positive image signature, per TRAPS 4–5. No image preflight or upstream image URL loading.
- “Ids are opaque.” Local refs are scoped by `providerInstanceId`; external MBIDs never become catalog ids. Unknown output enums remain visible and neutral. `status=held|partlyHeld|notHeld`; `completeness=known|unknown`.
- States/labels: `wanted`/Wanted, `requested`/Requested, `searching`/Searching, `unavailable`/Not found, `fulfilled`/Arrived. Default Arrived retention is 30 days; history is not deletion. Clients do not edit integration state or notes.
- Persist immutable UUID/payload/actionTime before optimistic success; retries reuse them. Wall clocks provide persisted timestamps; monotonic clocks govern debounce/backoff. Failures, unsettled reads and zero executed tests are never empty-success evidence.
- Swift receives fixed Objective-C DTOs copied into handwritten structs: no raw Flow, SQL entities, arbitrary Kotlin collections, suspend API or Kotlin exception crossing. Completions are on main with synchronously returned cancellation handles.
- Automated tests use configurable disposable servers and generated artwork only. PROD has no preconfigured server; no physical-device automation. Observe build capacity/serialization, native run, platform evidence and Linux Android soak rules.

## Review Focus

1. Retirement during a suspended read/send: no old request, acknowledgement, cache write or successful publication escapes its generation (Tasks 6–8, 10, 16).
2. Lost add receipt → remove → fulfilment/restart → re-add: old generation stays removed; new generation survives old replies (Tasks 6, 9, 12–15, 18–19).
3. Delayed query, 429, stale data or expired cursor: retain useful data/age, suppress obsolete results and never show false empty success (Tasks 2, 4–5, 11, 13–15, 17, 19).
4. Capability withdrawal or identity/principal change with queued wishes: preserve pending on selection, block wrong-account replay and expose permanent refusal (Tasks 1, 6–7, 10, 16).
5. Versioned titles, 8-of-12 editions or unknown metadata: route only explicit scoped refs; keep wish, correction, favourite and My Library actions distinct (Tasks 2, 5, 11–15, 17–19).

---

## Base, decisions and builder ownership

Base: local merge `7317e7b6`, including #349 `4a946a68` and reviewed #353 fixes. The owning session reported server 253/253 and API conformance 61/61; these are base results, not client evidence. Before implementation read `git diff 179db184..4a946a68 -- core`, main-spec §28 entries dated 2026-10-21/22 and `docs/evidence/server-identity.md`. Reconcile actual main after #349/#353 merge. #349's review found that identity rejection was checked only at a point in time, so its final head adds a lasting rejection fence: `canSend` is false while rejected, and `LibraryReader.issue()` re-checks after the await, so a reply that crosses a rejection is never cached or acknowledged. Rebase onto that head before Task 7. Discover reads and wish sends must pass through that fence, never a parallel one. Follow My Library in #316 (`b8f14bea`) and #326 (`cc5b43b4`).

**Binding:** Random local `providerInstanceId` is the namespace, distinct from authenticated lineage `(lowercase product, opaque instanceId)` and canonical endpoint plus exact confirmed username. `ServerIdentityStore` survives cache eviction; `ServerIdentityGate.validate()` precedes reconnect reads/outbox replay. Reuse #349's consent, retirement, shared database dispatcher and media ownership; no feature-owned identity adoption.

**Search/cache:** Debounce 350 ms; trim edges, preserve interior text/case; accept 1..500 Unicode code points, empty clears without a request. Fetch artist → album → song, 20 each, publishing incrementally; groups page independently. Keep last-good groups on error. Show original fetchedAt/server age/stale plus device elapsed age; offline says Cached/Offline, stale says Cached answer with age. Unknown age is not zero. HTTP429 honors Retry-After delta/date, fallback 60 s, shared monotonic server deadline; keep rows, show “Server is busy. Try again in …”, no retry before deadline. Wall rollback cannot make data fresh or bypass backoff. Disposable JSON cache 16 MiB/account, thumbnail LRU 32 MiB/account; only visited reads, revalidate on open, no background catalog sweep.

**Wish decisions:** Pending says “Waiting to sync”, not a confirmed integration request. Add/remove are separate durable intents, never `MutationField.InLibrary` booleans. Account selection preserves intents; sign-out/account removal deletes that account's feature rows through the existing destructive-account flow. Clear Cache retains outbox/accepted lineage. Accepted replacement or real binding change purges the old feature namespace after retirement and before admission; canonical-equivalent endpoint/confirmed-username spelling does not. Failed counts are unknown and block destructive consent, never zero.

**Actions:** Include “This isn't it” for an explicit matched pair/Arrived wish; rejection may reopen that wish but retains My Library membership. Adds require both extensions and a wishable canonical target; read/remove require Wishlist independently. Discover-off retains Wishlist read/remove. Definitive refusal rolls back only its failed overlay and says “Not saved”; transient failure retains pending. Individual 70/404 does not revoke a capability.

| Builder | Tasks | Ownership and handoff |
|---|---|---|
| Core + fixture | 1–9 | `core/`, conformance and shared fixture tools; delivers locked interfaces, production facades and finite fixture. |
| Apple | 10–15 | `apple/`, Apple runner modes; owns XcodeGen and generated project. Models/harness can start against contract doubles; real proofs use Tasks 8–9. |
| Android phone | 16–19 | `android/`, Android runner mode; consumes the same contract/fixture. No TV feature wiring. |
| Integrator | 20 | Evidence, FEATURES and workflow integration; only this part promotes cells. |

Core owns schema numbering/counts/purges. Interface changes require an explicit amendment before another builder consumes them. To keep native work test-first, prepare/register Tasks 13–15's UI tests after Task 10, record missing-feature reds, then implement Tasks 11–12 and finish proof commits. Android prepares Task 19 after Task 16, before Tasks 17–18. Harness registration is test setup; keep pre-UI red bundles and stage each task's paths only.

## Locked core interface block

New names are in `com.legitimateapps.dulcet.core`. Public common declarations below use `@HiddenFromObjC` and `@file:OptIn(ExperimentalObjCRefinement::class)` (precedent: `Lyrics.kt`); Android can consume them without exporting collections/generics to Swift. Explicitly internal declarations, stores and implementations stay internal. No transport accepts a shell-selected URL or user id.

```kotlin
enum class DiscoverKind { Artist, Album, Song } // wire artist, album, song
data class DiscoverTarget(val kind: DiscoverKind, val mbid: String)
data class DiscoverLibraryRef(val providerInstanceId: String, val type: String, val id: String)
data class DiscoverLink(val relation: String, val url: String)
data class DiscoverAge(val fetchedAtMillis: Long?, val sourceAgeSeconds: Long?,
    val receivedAtMillis: Long, val stale: Boolean, val fromDeviceCache: Boolean)
data class DiscoverAvailability(val status: String, val completeness: String,
    val matchedBy: String?, val matchedTrackCount: Int?, val totalTrackCount: Int?,
    val editionReleaseId: String?, val libraryItems: List<DiscoverLibraryRef>)
data class DiscoverItem(val id: String, val kind: String, val target: DiscoverTarget?,
    val title: String, val artist: String?, val year: Int?, val releaseDate: String?,
    val artworkId: String?, val disc: Int?, val position: Int?, val listenCount: Long?,
    val primaryType: String?, val links: List<DiscoverLink>, val wishable: Boolean,
    val availability: DiscoverAvailability)
data class DiscoverPage(val items: List<DiscoverItem>, val nextCursor: String?, val age: DiscoverAge)
data class DiscoverEdition(val releaseId: String, val releaseDate: String?,
    val trackCount: Int?, val links: List<DiscoverLink>)
data class DiscoverArtist(val item: DiscoverItem, val albums: DiscoverPage)
data class DiscoverAlbum(val item: DiscoverItem, val editions: List<DiscoverEdition>,
    val selectedReleaseId: String?, val tracks: DiscoverPage)
data class DiscoverFailure(val code: String, val retryAtMillis: Long?, val retryable: Boolean)
// code: offline, cancelled, unauthorized, forbidden, unsupported, identity-changed,
// rate-limited, upstream-unavailable, cursor-expired, invalid-response, missing-art, conflict, storage.
// Preserve unknown wire state separately; never put raw URLs or server exception text in code.
sealed interface DiscoverResult<out T> {
    data class Value<T>(val value: T) : DiscoverResult<T>
    data class Failed(val error: DiscoverFailure) : DiscoverResult<Nothing>
}
data class WishSnapshot(val title: String, val artist: String?, val year: Int?, val artworkId: String?)
data class WishSource(val type: String, val id: String?) // search/artist/album now; shelf/playlist later
data class DiscoverWish(val id: String, val userId: String, val target: DiscoverTarget?,
    val kind: String, val snapshot: WishSnapshot, val source: WishSource,
    val addedAtMillis: Long, val updatedAtMillis: Long, val state: String,
    val note: String?, val noteAtMillis: Long?, val fulfilledAtMillis: Long?,
    val libraryItem: DiscoverLibraryRef?, val availability: DiscoverAvailability, val revision: String)
data class WishFilter(val kind: String? = null, val state: String? = null,
    val includeFulfilledHistory: Boolean = false)
internal data class WishListSnapshot(val items: List<DiscoverWish>, val stamp: String?)
sealed interface WishMutation {
    val operationId: String
    data class Add(override val operationId: String, val actionTimeMillis: Long,
        val target: DiscoverTarget, val snapshot: WishSnapshot, val source: WishSource) : WishMutation
    data class Remove(override val operationId: String, val actionTimeMillis: Long,
        val wishId: String?, val addOperationId: String?) : WishMutation // exactly one target
    data class Reject(override val operationId: String, val target: DiscoverTarget,
        val libraryItem: DiscoverLibraryRef, val wishId: String?) : WishMutation
}
internal data class WishReceipt(val applied: Boolean, val wish: DiscoverWish?, val removed: Boolean)
internal interface DiscoverTransport {
    suspend fun search(kind: DiscoverKind, query: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun artist(mbid: String): DiscoverResult<DiscoverArtist>
    suspend fun artistAlbums(mbid: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun album(mbid: String, releaseId: String?): DiscoverResult<DiscoverAlbum>
    suspend fun albumTracks(mbid: String, releaseId: String, cursor: String?, limit: Int = 20): DiscoverResult<DiscoverPage>
    suspend fun artwork(artworkId: String): DiscoverResult<ByteArray>
}
internal interface WishlistTransport {
    suspend fun snapshot(filter: WishFilter): DiscoverResult<WishListSnapshot>
    suspend fun wish(id: String): DiscoverResult<DiscoverWish>
    suspend fun apply(mutation: WishMutation): DiscoverResult<WishReceipt>
}
data class DiscoverFeatureAccess(val detection: String,
    val error: DiscoverFailure?, val discoverAvailable: Boolean,
    val wishlistAvailable: Boolean, val canSearch: Boolean,
    val canReadWishlist: Boolean, val canAddWish: Boolean, val canRemoveWish: Boolean,
    val canRejectMatch: Boolean)
// detection: known/unresolved. A failed probe is not a confirmed absence.
data class DiscoverGroup(val kind: String, val page: DiscoverPage?, val loading: Boolean,
    val error: DiscoverFailure?)
data class WishRow(val key: String, val wish: DiscoverWish?, val pendingAdd: WishMutation.Add?,
    val pending: Boolean, val error: DiscoverFailure?) // key stable across acknowledgement
data class DiscoverPublication(val providerInstanceId: String, val generation: Long,
    val access: DiscoverFeatureAccess, val connection: String, val query: String,
    val groups: List<DiscoverGroup>, // connection: online/offline/validating
    val wishes: List<WishRow>, val wishlistLoading: Boolean, val wishlistError: DiscoverFailure?,
    val wishlistSettled: Boolean, val pendingChangeCount: Long?)
data class WishEnqueued(val operationId: String, val rowKey: String)
interface DiscoverRegistration { fun close() }
interface DiscoverOperation { fun cancel() }
interface DiscoverClient {
    fun observe(listener: (DiscoverPublication) -> Unit): DiscoverRegistration
    fun setQuery(query: String)
    fun loadMore(kind: String)
    fun artist(mbid: String, completion: (DiscoverResult<DiscoverArtist>) -> Unit): DiscoverOperation
    fun artistAlbums(mbid: String, cursor: String?, completion: (DiscoverResult<DiscoverPage>) -> Unit): DiscoverOperation
    fun album(mbid: String, releaseId: String?, completion: (DiscoverResult<DiscoverAlbum>) -> Unit): DiscoverOperation
    fun albumTracks(mbid: String, releaseId: String, cursor: String?, completion: (DiscoverResult<DiscoverPage>) -> Unit): DiscoverOperation
    fun artwork(artworkId: String, completion: (DiscoverResult<ByteArray>) -> Unit): DiscoverOperation
    fun refreshWishlist(filter: WishFilter)
    fun add(item: DiscoverItem, source: WishSource, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun remove(rowKey: String, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun reject(item: DiscoverItem, libraryItem: DiscoverLibraryRef, wishId: String?, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation
    fun close()
}
```

Production entries: `LibraryReaderSession.openDiscoverClient():DiscoverClient`, `AndroidDiscoverClient(reader:AndroidLibraryReader)` implementing the same commands, and `AppleDiscoverClient(reader:AppleLibraryReaderClient)`. Construction is lazy with no network; reuse parent extension detection. One client owns one Search query; account store/outbox is shared. Visible Wishlist/foreground refresh triggers reads. Include wish intents once in `pendingChangeCount()`.

Commands run on the reader owner; callbacks/publications reach the UI dispatcher. Observe immediately publishes current state. Cancellation before durable commit prevents enqueue; afterwards it cancels observation, not the intent. Close revokes successful deliveries synchronously, completes outstanding operations once as cancelled and joins through parent close completion. Recheck account/query generation after suspensions and before send, store/ack and main delivery. Page completion fires once: cached fallback keeps original age; a displayed prior value can remain alongside a later typed failure.

**Apple DTO mapping:** Each public common data class maps to a public class prefixed `Apple`, with identical field names/scalars, enum→wire String, nullable numbers preserved as nullable numeric boxes, nested objects recursively mapped. `WishRow.pendingAdd` uses `ApplePendingWishAdd(operationId:String,actionTimeMillis:Long,target:AppleDiscoverTarget,snapshot:AppleWishSnapshot,source:AppleWishSource)`. No mutation/sealed result is exported.

| List field | Exported count/accessor on its owner |
|---|---|
| libraryItems | `libraryItemCount:Int`, `libraryItemAt(index:Int):AppleDiscoverLibraryRef?` |
| links | `linkCount:Int`, `linkAt(index:Int):AppleDiscoverLink?` |
| items | `itemCount:Int`, `itemAt(index:Int):AppleDiscoverItem?` |
| editions | `editionCount:Int`, `editionAt(index:Int):AppleDiscoverEdition?` |
| groups | `groupCount:Int`, `groupAt(index:Int):AppleDiscoverGroup?` |
| wishes | `wishCount:Int`, `wishAt(index:Int):AppleWishRow?` |

Bounds return null, never throw. Public results: `AppleDiscoverArtistResult`, `AppleDiscoverAlbumResult`, `AppleDiscoverPageResult`, `AppleWishEnqueueResult`, each `(value:<corresponding Apple type>?,error:AppleDiscoverFailure?)`, exactly one populated; artwork is `AppleDiscoverArtworkResult(bytes:platform.Foundation.NSData?,error:AppleDiscoverFailure?)`. `AppleDiscoverListener.onPublication(publication:AppleDiscoverPublication)` and `AppleDiscoverSubscription.close()` replace observe closure/registration. Apple client commands match common signatures with Apple arguments/results, synchronously returning existing `AppleLibraryReaderOperation`; observe returns AppleDiscoverSubscription.

Swift copies these to `Dulcet`-prefixed structs, arrays, raw strings, Date for required wall times/Date? for nullable ones, Int64 for Long, Int for Int, Data for bytes; pendingAdd is `DulcetPendingWishAdd`. Only `DulcetCoreDiscover` imports core; no view sees Kotlin. Subscription cancel closes the bridge; cancelled continuations resume rather than leak. Fixed Swift protocol:

```swift
@MainActor public protocol DulcetDiscoverReading: AnyObject {
    func observe(_ listener: @escaping @MainActor (DulcetDiscoverPublication) -> Void) -> any DulcetLibraryReaderCancellable
    func setQuery(_ query: String)
    func loadMore(kind: String)
    func artist(mbid: String, completion: @escaping @MainActor (Result<DulcetDiscoverArtist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func artistAlbums(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func album(mbid: String, releaseId: String?, completion: @escaping @MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func albumTracks(mbid: String, releaseId: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func artwork(artworkId: String, completion: @escaping @MainActor (Result<Data, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func refreshWishlist(_ filter: DulcetWishFilter)
    func add(item: DulcetDiscoverItem, source: DulcetWishSource, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func remove(rowKey: String, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func reject(item: DulcetDiscoverItem, libraryItem: DulcetDiscoverLibraryRef, wishId: String?, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func close()
}
```

## REST mapping and execution conventions

Use the existing extension read, not a per-screen probe or new `/.well-known/dulcet`/API capability request. Valid absent v1 hides/disables only that feature; failed/malformed detection retains last-good metadata with unresolved/error and never becomes a valid empty extension set. Parent validation still governs sends.

| Transport call | `/rest` method / response child |
|---|---|
| search(kind,query,cursor,limit) | `searchDulcetDiscover` / `dulcetDiscover.item[]` |
| artist / artistAlbums | `getDulcetDiscoverArtist` / `dulcetDiscoverArtist`; `getDulcetDiscoverArtistAlbums` / `dulcetDiscover.album[]` |
| album(mbid,releaseId) / albumTracks | `getDulcetDiscoverAlbum` / `dulcetDiscoverAlbum`; `getDulcetDiscoverAlbumTracks` / `dulcetDiscover.song[]` |
| artwork(artworkId) | `getDulcetDiscoverCoverArt(id=artworkId)` / binary or checked failure |
| snapshot(filter) / wish(id) | `getDulcetWishlist` / `dulcetWishlist.wish[]`; `getDulcetWish` / `dulcetWish` |
| apply(Add) | `addToDulcetWishlist(kind,mbid,title,sourceType,operationId,artist?,year?,artworkId?,sourceId?,actionTime)` / `dulcetWishMutation` |
| apply(Remove) | `removeFromDulcetWishlist(operationId,actionTime,exactly one of id/addOperationId)` / same |
| apply(Reject) | `rejectDulcetDiscoverMatch(kind,mbid,libraryType,libraryId,operationId,wishId?)` / same |

All list limits map to `size` (default 20, 1..100); no offset/since. `f=json`; standard checked envelopes, including HTTP200 failure. Map artist/album/song canonical ids from externalIds' musicbrainzArtistId/musicbrainzReleaseGroupId/musicbrainzRecordingId; local song refs stay song. Album edition selection never changes wish target. Preserve optional fields, arrays and opaque cursors. Encode parameters; use existing credentials/salt/formPost, redact whole queries/errors and strip credentials across origins; canonical origins use scheme/lowercase host/default port, no HTTPS downgrade.

`snapshot` probes size 1, reads all pages with unchanged filters, verifies equal opaque `changed` before/through/after, then publishes atomically. Absent changed before the first mutation is valid null, separate from unsettled. On cursor-expired/change restart at most 3 times; continued churn retains prior settled cache with an error. Parse reason before numeric code (expiry is documented under 0 or 70); unknown/foreign cursors fail, not empty. Do not add API ETags/change-feed logic. Add/remove carry stable UUID plus RFC3339 actionTime; cancellation targets old generation, never MBID deletion.

Tests use local fixture only, positive request/health controls and isolated XML/xcresult per run. Red must fail for the named behavior; zero tests or dead fixture is infrastructure failure. Count executed/failure/error/skip from reports, not task summaries. Acquire/release build capacity around each invocation.

```sh
core_test() { ./gradlew :core:jvmTest --rerun --tests "com.legitimateapps.dulcet.core.$1"; }
android_test() { ./gradlew :android:app:testProdDebugUnitTest --rerun --tests "com.legitimateapps.dulcet.$1"; }
kit_test() { swift test --package-path apple/DulcetKit --filter "$1"; }
commit_part() {
  message=$1; shift
  git add -- "$@"
  git -c user.name='legitimate-apps' \
    -c user.email='309192374+legitimate-apps@users.noreply.github.com' \
    commit -m "$message"
}
```

Each Step 5 means commit_part with its message and exact Files paths. Adjacent existing suites and full native/platform checks are specified below; no build is required for this plan revision.

### Task 1: Models and independent extension detection

**Files:** Create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverModels.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverCapabilities.kt`; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DiscoverCapabilitiesTest.kt`. Use parent detection composition modified in Task 7; no AccountConnection/profile change.

**Interfaces:** Produce locked models and `DiscoverCapabilityResolver.resolve(extensions:Map<String,Set<Int>>?,catalogAllowed:Boolean):DiscoverFeatureAccess`; null means unresolved, valid empty means absent. Version 1 support is independent of server product. Parent account policy/admission combines with these gates.

- [ ] **Step 1: Write red tests.** `discoverAndWishlistAreIndependent`: Discover-off keeps read/remove, Wishlist-off keeps search; `detectsExtensionsOnAnyServer`: `assertTrue(access.canSearch)` for a non-Dulcet product advertising v1, and for Dulcet Server. `neitherExtensionMeansNoFeatureTraffic`: `assertEquals(baselinePaths,actualPaths)` after positive connection/catalog control. `rejectionWorksWithEitherExtension`: `assertTrue(access.canRejectMatch)` for each extension alone; Wishlist reads remain gated independently. `failedDetectionIsNotAnEmptyExtensionSet`: `assertEquals("unresolved",access.detection)` with last-good metadata retained.
- [ ] **Step 2: Verify red.** `core_test DiscoverCapabilitiesTest`.
- [ ] **Step 3: Implement.** Reuse My Library’s checked getOpenSubsonicExtensions snapshot; no API/profile/auth additions. Permission failure and absent version are distinct.
- [ ] **Step 4: Verify green.** Same command; include `core_test SavedServerIdentityTest` to preserve #349 behavior.
- [ ] **Step 5: Commit.** `feat(core): define Discover models and extension gates`.

### Task 2: Checked REST adapters

**Files:** Create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/RestDiscoverTransport.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/RestWishlistTransport.kt`; modify `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/LibraryReader.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/LibraryBrowse.kt`; test `core/src/jvmTest/kotlin/com/legitimateapps/dulcet/core/LibraryStatusTransportTest.kt`, `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/RestDiscoverTransportTest.kt`, `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/RestWishlistTransportTest.kt`.

**Interfaces:** JSON uses `LibraryReader.send` followed by `SentResponse.requireOk`, inspecting extension failure `reason` and raw Retry-After before standard checking erases that metadata. This is the same issue/admission path as `sendChecked`; its public signature is unchanged. Add internal `LibraryEndpointTransport.requestBinary(endpoint:String,parameters:Map<String,String>,maxBodyBytes:Int):AuthenticatedEndpointResponse` and `LibraryReader.sendBinary` with the same signature. Give JSON-only fakes a default refusal; production returns existing AuthenticatedEndpointClient bytes through the same reader slots, issue sequence, admission and retirement checks. Wishlist snapshot owns the changed-clock bracket; no separate executor or reconnect loop.

- [ ] **Step 1: Write red tests.** `mapsAllRestMethodsAndOpaqueIds` pins the mapping table/size 20; `knownUnknownCompletenessIsNotHoldingStatus`: `assertEquals("partlyHeld",a.status)`, `assertEquals("unknown",a.completeness)`. `restSnapshotChecksChangedAcrossAllPages` uses 21 wishes/20+1 pages and 3 failed restarts. `emptyInitialWishlistHasNullableChanged`: `assertNull(snapshot.stamp)` and a settled empty result after successful probes, distinct from failure. `http200FailureAndCursorReasonNeverBecomeEmpty` tests expiry codes 0/70; `removeLostAddUsesAddOperationId` pins UUID/actionTime. `binaryErrorsAndWrongMagicAreNotArtwork` refuses BOM XML/JSON envelopes, unknown bytes and >5 MiB; accepts JPEG/PNG/GIF/WebP positive signatures. `binaryRequestSharesReaderAdmissionAndPreservesBytes` holds a slot across offline/retirement, asserts zero binary sends, then an admitted request returns identical bytes with a 5,242,880-byte cap and no preflight. Map SVG missing-art response to native placeholder without decoding/rendering SVG. Canary credentials never appear in redirected requests/logs/errors.
- [ ] **Step 2: Verify red.** `./gradlew :core:jvmTest --rerun --tests '*RestDiscoverTransportTest' --tests '*RestWishlistTransportTest'`.
- [ ] **Step 3: Implement.** Validate actual response bytes per TRAPS 4–5, no preflight; return `missing-art` for the server placeholder without a page-level alert. Preserve unknown enums/nulls and do not interpret catalog ids.
- [ ] **Step 4: Verify green.** Repeat both classes, including salt freshness, formPost, canonical-origin stripping and downgrade refusal.
- [ ] **Step 5: Commit.** `feat(core): adapt Discover and Wishlist REST extensions`.

### Task 3: Account cache and protected wish storage

**Files:** Create `core/src/commonMain/sqldelight/com/legitimateapps/dulcet/database/Discover.sq`, `core/src/commonMain/sqldelight/com/legitimateapps/dulcet/database/Wishlist.sq`, `core/src/commonMain/sqldelight/migrations/12.sqm`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverStore.kt`; modify `core/src/commonMain/sqldelight/com/legitimateapps/dulcet/database/SeenCache.sq`, `core/src/commonMain/sqldelight/com/legitimateapps/dulcet/database/ServerData.sq`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/SeenCacheStore.kt`, `tools/generate_migration_fixture.py`, `tools/migration-fixtures/reserved-tables.json`; create `core/src/commonMain/sqldelight/databases/13.db` and `tools/migration-fixtures/v13/database.db` with its generated `files/` through existing tooling; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DiscoverStoreTest.kt`.

**Interfaces:** `DiscoverStore(database,serverId:String)` caches normalized reads; `suspend enqueue(mutation:WishMutation):DiscoverResult<WishEnqueued>` commits protected intent. Disposable reads, wish snapshots and outbox/receipts are distinct tables.

- [ ] **Step 1: Write red tests.** `restartRetainsIntentButClearCacheDoesNotDeleteIt`: `assertEquals(originalIntent,reopened.pending.single())`; `sameMbidAcrossTwoAccountsNeverSharesRows`: `assertTrue(accountB.wishes.isEmpty())`. `bindingPurgePreservesAcceptedIdentityUntilTrueRemoval`; `migration12To13RetainsPendingMyLibraryAndMediaOwnership` keeps prior rows/files and creates empty new tables. Storage failure publishes no optimistic success; 16/32 MiB eviction excludes outbox.
- [ ] **Step 2: Verify red.** `core_test DiscoverStoreTest`; `python3 tools/test-migration-fixture-generator`.
- [ ] **Step 3: Implement.** Atomic intent commit/settled snapshot swap. Add tables to inventory/count/purge queries and generated fixtures. Reserve migration 12→schema 13 on this base; renumber only for a real merged-main collision.
- [ ] **Step 4: Verify green.** Repeat; `python3 tools/migration_gate.py`. Native SQLite reopen is checked in Task 8.
- [ ] **Step 5: Commit.** `feat(core): persist scoped Discover reads and wish intents`.

### Task 4: Incremental Search Everywhere reader

**Files:** Create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverSearch.kt`; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DiscoverSearchTest.kt`.

**Interfaces:** `DiscoverSearch.setQuery(query:String):Unit`, `loadMore(kind:String):Unit`, `close():Unit`; injected clock, per-screen query generation, shared account backoff/store.

- [ ] **Step 1: Write red tests.** `debounces350msAndPublishesArtistsBeforeAlbumsBeforeSongs`: `advanceTimeBy(349); assertEquals(0,sends.size)`, then at 350 ms, first artist request, trace artist→album→song, each publication before next finishes. `lateOldQueryCannotReplaceNewResults`: `assertEquals("new",publication.query)` after old completes. `rateLimitKeepsLastGoodAndHonorsDeadline` tests 120 s delta/date, 60 s fallback and wall jump; `staleAndOfflineNeverMasqueradeAsFreshOrEmpty`, `oneGroupFailureDoesNotEraseOtherGroups`, `expiredCursorReplacesOnlyThatGroup` pin age/group isolation.
- [ ] **Step 2: Verify red.** `core_test DiscoverSearchTest` with virtual time.
- [ ] **Step 3: Implement.** Apply search/cache decisions above; cancellation plus generation checks, not cancellation alone. Deduplicate in-flight cursor; append only within its generation.
- [ ] **Step 4: Verify green.** Repeat, adding close during debounce, empty/identical query and Unicode 500/501 boundaries.
- [ ] **Step 5: Commit.** `feat(core): read debounced incremental Search Everywhere`.

### Task 5: Artist/album readers and held routing

**Files:** Create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverPages.kt`; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DiscoverPagesTest.kt`.

**Interfaces:** Implement page/artwork commands; `DiscoverRoutePolicy.libraryTarget(item:DiscoverItem):DiscoverLibraryRef?` returns only an unambiguous permitted scoped ref. Edition releaseId controls tracks, not wish identity.

- [ ] **Step 1: Write red tests.** `editionChangeCannotAppendPreviousReleaseTracks`; `partial8of12IsNotPlayableAsCompleteAlbum`: counts 8/12 and `assertNull(DiscoverRoutePolicy.libraryTarget(partial))`, explicit held track still routes. `unknownEnumsAndMissingMbidStayVisibleButUnwishable`; `serverVersionMismatchNeverClientRematches` uses live/studio titles; `ambiguousLibraryRefsRequireChoice`; `artworkIsScopedAndNeverLoadsExternalLinks` positively records server-only artwork traffic.
- [ ] **Step 2: Verify red.** `core_test DiscoverPagesTest`.
- [ ] **Step 3: Implement.** Keep server default edition/order/date precision, original cache age and one completion per operation. Use existing library routes/player with returned local refs; safe HTTP(S) listen/buy links only on tap, no userinfo/unsafe schemes.
- [ ] **Step 4: Verify green.** Repeat with delayed selection/close and invalid image cases from Task 2.
- [ ] **Step 5: Commit.** `feat(core): read Discover pages with scoped held routing`.

### Task 6: Durable wish generations and settled wishlist

**Files:** Create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/WishOutbox.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/WishlistReader.kt`; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/WishOutboxTest.kt`, `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/WishlistReaderTest.kt`.

**Interfaces:** `suspend WishOutbox.enqueueAdd(item:DiscoverItem,source:WishSource)`, `enqueueRemove(rowKey:String)`, `enqueueReject(item:DiscoverItem,libraryItem:DiscoverLibraryRef,wishId:String?)` return `DiscoverResult<WishEnqueued>` after commit; `suspend flush():Unit`. `suspend WishlistReader.refresh(filter:WishFilter):Unit` swaps settled snapshot plus ordered pending overlay; count is nullable Long.

- [ ] **Step 1: Write red tests.** `lostAddThenRemoveBeforeReceiptSurvivesRestart`: `assertEquals(addA.operationId,removeR.addOperationId)`, final A absent. `fulfilmentRacingRemovalStaysRemoved` keeps My Library; `oldRemovalCannotDeleteIntentionalReadd` keeps B after late A/R. `accountSwitchStopsAAndBDoesNotDrainA` proves no wrong-user send; `rejectionReopensWishWithoutRemovingMyLibrary`; `permanentRefusalRollsBackOnlyItsOverlay`; `generationChangesDuringPagingNeverPublishPartialAsEmpty` retains prior snapshot after 3 restarts. Include 24 h receipt-cache expiry, clock rollback/equal-time removal-wins, failed count→null.
- [ ] **Step 2: Verify red.** `./gradlew :core:jvmTest --rerun --tests '*WishOutboxTest' --tests '*WishlistReaderTest'`.
- [ ] **Step 3: Implement.** New UUID only for new intent, actionTime=max(wallNow,lastLocalAction+1); server clamps/decides ordering. Send unresolved cancellation before its cancelled add where possible. Never rewrite payload/coalesce generations; apply receipt only to its generation. `applied=false,removed=true` terminally acknowledges that generation. Validate/fence before send and after response. Observed new fulfilment refreshes existing My Library reader, never client-adds membership.
- [ ] **Step 4: Verify green.** Repeat both classes with disk reopen, delayed replies and independent Discover/Wishlist withdrawal/refusal cases.
- [ ] **Step 5: Commit.** `feat(core): replay wish generations without resurrection`.

### Task 7: Reader lifecycle and #349 fence integration

**Files:** Modify `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/MutationOutbox.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/LibraryReader.kt`, `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/SeenCacheStore.kt`, `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidLibraryReader.kt`, `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidAccountData.kt`, `core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/AppleLibraryReaderFacade.kt`, `core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/AppleDownloadFacade.kt`; create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverSession.kt`; test `core/src/commonTest/kotlin/com/legitimateapps/dulcet/core/DiscoverAccountFenceTest.kt`, extend `core/src/androidHostTest/kotlin/com/legitimateapps/dulcet/core/AndroidAccountDataTest.kt`.

**Interfaces:** `LibraryReaderSession.openDiscoverClient()` composes account store/outbox and existing admission/reconnect barrier. Pending/inventory/removal counts include wishes once; failures of one outbox do not skip independent outboxes.

- [ ] **Step 1: Write red tests.** `identityMismatchBeforeReconnectSendsNoFeatureWrite`: `assertEquals("identity-changed",failure.code)` and zero feature writes after positive held-request control. `retiredSessionCannotReopenOnLateOnlineCallback`; `accountRemovalCountsAndDeletesInactiveWishNamespaces`: failed count null, shared database dispatcher retained. `clearCacheRetainsWishIntentAndAcceptedLineage`; `canonicalReconnectKeepsNamespace`; `replacementConsentCancelKeepsEverything`.
- [ ] **Step 2: Verify red.** `core_test DiscoverAccountFenceTest`; `./gradlew :core:testAndroidHostTest --rerun --tests '*AndroidAccountDataTest'`.
- [ ] **Step 3: Implement.** Use #349 as sole identity authority. Recheck generation/admission after suspensions, before socket/store/ack and main delivery. Selection closes A without deleting it; sign-out/accepted rebind follows existing consent/retirement ordering.
- [ ] **Step 4: Verify green.** Repeat plus `OfflineMediaBindingSafetyTest`, `MutationOutboxReviewTest`, `MyLibraryTest`; count exactly pending intents, not overlays/receipts.
- [ ] **Step 5: Commit.** `feat(core): fence Discover sessions with account retirement`.

### Task 8: Production Android and Objective-C facades

**Files:** Create `core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/AppleDiscoverFacade.kt`, `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidDiscover.kt`; modify `core/src/appleMain/kotlin/com/legitimateapps/dulcet/core/AppleLibraryReaderFacade.kt`, `core/src/androidMain/kotlin/com/legitimateapps/dulcet/core/AndroidLibraryReader.kt`; test `core/src/appleTest/kotlin/com/legitimateapps/dulcet/core/AppleDiscoverFacadeTest.kt`, `core/src/androidHostTest/kotlin/com/legitimateapps/dulcet/core/AndroidDiscoverTest.kt`.

**Interfaces:** Produce `AndroidDiscoverClient`, `AppleDiscoverClient`, DTOs and cancellation semantics exactly as locked; both are children of the existing production reader.

- [ ] **Step 1: Write red tests.** `callbackIsMainAndNoThrowableEscapes`: `assertTrue(callbackWasOnMainThread)`, `assertEquals(1,completionCount)` under parser/storage failure. `closeDropsAlreadyQueuedPublications`; `cancelAfterEnqueueDoesNotDeleteDurableIntent`; `indexedAccessIsSafeAtMinusOneAndCount`: null at -1/count; `allNullableFieldsCopyWithoutZeroCoercion`. Android runs equivalent owner-dispatch cases; native reopen retains UUID/actionTime.
- [ ] **Step 2: Verify red.** `./gradlew :core:macosArm64Test --rerun --tests '*AppleDiscoverFacadeTest' :core:testAndroidHostTest --rerun --tests '*AndroidDiscoverTest'`.
- [ ] **Step 3: Implement.** Existing completion barrier and parent-close participation; no alternate HTTP client or credential store. Inspect generated Objective-C header for hidden common collections/Flow/SQL types.
- [ ] **Step 4: Verify green.** Repeat native/Android tests; `./gradlew :core:compileTestKotlinIosSimulatorArm64 :core:bundleAndroidMainAar :core:allMetadataJar`. Preserve generated signatures for shell builders.
- [ ] **Step 5: Commit.** `feat(core): expose native Discover readers and wish commands`.

### Task 9: Finite fixture and production REST conformance

**Files:** Create `tools/discover-wishlist-ui-proxy`, `tools/ci/with-discover-wishlist-fixture`, `tools/ci/run-discover-wishlist-proofs`, `tools/discover-wishlist-fixtures/README.md`, `tools/discover-wishlist-fixtures/scenarios.json`; create `core/src/commonMain/kotlin/com/legitimateapps/dulcet/core/DiscoverWishlistConformanceContract.kt` and `core-conformance/src/jvmTest/kotlin/com/legitimateapps/dulcet/conformance/DiscoverWishlistConformanceTest.kt`, without importing server internals. Create `tools/test-discover-wishlist-fixture`.

**Interfaces:** `tools/ci/with-discover-wishlist-fixture -- <command...>` starts disposable Dulcet Server/upstream/Navidrome, exports `DULCET_DISCOVER_SERVER_URL`, `DULCET_DISCOVER_NAVIDROME_URL`, `DULCET_DISCOVER_CONTROL_URL` and fixture credentials, reaps owned PIDs/data. Control: `POST /control/scenario {name}`, `GET /control/requests`, `GET /control/state` acknowledge scenario/revision/health. Scenarios: delayed-old-query,rate-limit-120,stale-answer,offline,lost-add-receipt,scan-arrival,remove-before-add,identity-replacement. Follow PlaylistConformanceContract: hidden public Kotlin-only `suspend DiscoverWishlistConformanceContract.observe(request:DiscoverConformanceRequest):DiscoverConformanceObservation`. Request fields `(serverUrl:String,navidromeUrl:String,controlUrl:String,username:String,password:String,otherUsername:String,otherPassword:String)` have redacted toString; observation `(restRequests:List<String>,navidromeAdditionalRequests:List<String>,scanFulfilled:Boolean,myLibraryContainsArrival:Boolean,wishAbsentAfterRemoval:Boolean)` contains redacted facts asserted independently by tests.

- [ ] **Step 1: Write red tests.** `productionRestReaderAndOutboxRoundTrip` positively records extension reads/writes. `plainNavidromeGetsOnlyBaselineRequests`: healthy login/catalog/log sentinel then `assertTrue(observation.navidromeAdditionalRequests.isEmpty())`. `fixtureFaultMustBeAcknowledgedBeforeAssertion`; `realScanArrivalAndLostResponseRemoval`: tagged audio→documented scan→server fulfilled/My Library, then A/R retry leaves wish absent and membership present. `fixtureCleansOnlyOwnedPids`.
- [ ] **Step 2: Verify red.** Build `./gradlew :server:installDist` once before wrapper, not nested inside Gradle; then `python3 tools/test-discover-wishlist-fixture` and wrapper→`:core-conformance:jvmTest --rerun --tests '*DiscoverWishlistConformanceTest'`.
- [ ] **Step 3: Implement.** Extend My Library disposable setup; fresh disposable user/catalog per test or acknowledged checkpoint restore. Generated audio/art, recorded upstream JSON, >20 rows and held/partial/unknown/future-state cases. Public REST/test controls only; no server implementation imports, live upstream, OAuth fixture or auto-approval.
- [ ] **Step 4: Verify green.** Repeat self-test/conformance; preserve redacted positive REST/Navidrome traces and acknowledged fault/scan/reset revisions.
- [ ] **Step 5: Commit.** `test(core): prove Discover clients against disposable REST fixtures`.

### Task 10: Swift values and account-bound adapter

**Files:** Create `apple/DulcetKit/Sources/DulcetKit/DulcetDiscover.swift`, `apple/DulcetAppleShared/DulcetCoreDiscover.swift`; modify `apple/DulcetKit/Sources/DulcetKit/DulcetLibraryReading.swift`, `apple/DulcetiOS/DulcetiOSApp.swift`, `apple/DulcetMac/DulcetMacApp.swift`, `apple/project.yml`, regenerated `apple/Dulcet.xcodeproj/project.pbxproj`; test `apple/DulcetKit/Tests/DulcetKitTests/DulcetDiscoverTests.swift`.

**Interfaces:** `DulcetDiscoverReading`/structs and `DulcetCoreDiscover` consume the locked Apple facade, lazily injected into account-specific composition. Reuse existing credentials and #349 lifecycle; no auth UI or secure-store changes.

- [ ] **Step 1: Write red tests.** `copiesEveryFieldIntoSwiftValues`: `#expect(copy.availability.matchedTrackCount == 8)`, total 12, nil numbers, note/time and unknown state preserved. `pendingOperationDoesNotCrossAccountAdapterReplacement` drops delayed A success/navigation after close. `cacheClearAndAccountRemovalKeepTheirExistingSemantics` preserves pending only on switch/clear, not true sign-out.
- [ ] **Step 2: Verify red.** `kit_test DulcetDiscoverTests`; compile adapter against generated facade in both Apple app schemes.
- [ ] **Step 3: Implement.** Copy the complete DTO tree at delivery; map cancellation without leaked continuations or thrown Kotlin errors. TV still compiles without constructing feature UI.
- [ ] **Step 4: Verify green.** Repeat; iOS simulator/Mac builds with fresh framework and `tools/verify_xcodegen_regeneration --self-test`.
- [ ] **Step 5: Commit.** `feat(apple): adapt Discover values and account lifecycle`.

### Task 11: Apple Search Everywhere and metadata pages

**Files:** Create `apple/DulcetKit/Sources/DulcetKit/DulcetDiscoverViews.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetDiscoverPresentation.swift`; modify `apple/DulcetKit/Sources/DulcetKit/DulcetSearchView.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetLibraryReadingViews.swift`; test `apple/DulcetKit/Tests/DulcetKitTests/DulcetDiscoverPresentationTests.swift`.

**Interfaces:** `DulcetDiscoverSearchView(reader:any DulcetDiscoverReading,openLibrary:(DulcetDiscoverLibraryRef)->Void)`; `DulcetDiscoverDestination.artist(mbid:String)` / `.album(mbid:String,releaseId:String?)`. Scope Your server | Everywhere only with Discover v1.

- [ ] **Step 1: Write red tests.** `scopeControlsOnlyTheSelectedSearchReader` records local vs Discover calls. `busyAndStaleHaveVisibleAgeAndRetry`: `#expect(presentation.canRetry == false)` before deadline. `heldPartialUnknownAndMissingMbidRenderDistinctActions`: literal 8 of 12, neutral unknown, no missing-MBID wish. `editionSelectionCancelsOldPage` shows selected release only.
- [ ] **Step 2: Verify red.** `kit_test DulcetDiscoverPresentationTests`; native missing-feature reds are prepared in Tasks 13–15 before implementation.
- [ ] **Step 3: Implement.** iPhone push; iPad/Mac native split detail. Literal “Not in your library”, wish distinct from heart/My Library, credit “Data from MusicBrainz and ListenBrainz”. Server-byte artwork, explicit safe links. IDs: discover.scope/group.artist|album|song/item.<id>/age/retry/edition/wish/openLibrary.
- [ ] **Step 4: Verify green.** Repeat Kit tests and Apple builds; real navigation/text-size/focus proofs follow.
- [ ] **Step 5: Commit.** `feat(apple): add Search Everywhere and metadata pages`.

### Task 12: Apple Library Wishlist and wish actions

**Files:** Create `apple/DulcetKit/Sources/DulcetKit/DulcetWishlistViews.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetWishlistPresentation.swift`; modify `apple/DulcetKit/Sources/DulcetKit/DulcetRootView.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetMyLibraryViews.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetDiscoverViews.swift`; test `apple/DulcetKit/Tests/DulcetKitTests/DulcetWishlistPresentationTests.swift`.

**Interfaces:** `DulcetWishlistView(reader:any DulcetDiscoverReading,openLibrary:(DulcetDiscoverLibraryRef)->Void)` beside existing Library destinations, not a new tab. Add uses source search/artist/album; remove uses rowKey; reject uses explicit pair.

- [ ] **Step 1: Write red tests.** `wishActionIsNotFavoriteOrMyLibrary`: `#expect(source.myLibraryMembershipMutationCount == 0)`. `pendingRemoveNeverReturnsFromLateArrival` hides A then shows deliberate B; `arrivalUsesServerLibraryItemAndRefreshesMyLibrary`; `statesNotesAndThirtyDayHistoryUseServerTruth`: five labels, unknown neutral, escaped note/time and history filter. `discoverOffKeepsWishlistAndRemove`; `permanentSaveFailureIsVisibleAndRollsBackIcon`: Not saved vs Waiting to sync.
- [ ] **Step 2: Verify red.** `kit_test DulcetWishlistPresentationTests`.
- [ ] **Step 3: Implement.** Wait for durable enqueue before optimistic state. Explicit accessible remove plus native swipe/context actions; state/note/time/pending/error visible. IDs wishlist.destination/row.<key>/state/pending/remove/arrival/reject. No membership deletion on removal.
- [ ] **Step 4: Verify green.** Repeat and app builds; preserve account-keyed navigation/sign-out consent.
- [ ] **Step 5: Commit.** `feat(apple): add Library Wishlist and durable wish actions`.

### Task 13: iPhone real-trigger UI proof

**Files:** Create `apple/DulcetDiscoverUITestSupport/DulcetDiscoverWishlistUITests.swift`; modify `apple/project.yml`, regenerated `apple/Dulcet.xcodeproj/project.pbxproj`, `tools/ci/run-discover-wishlist-proofs` (Apple modes only).

**Interfaces:** Suite `DulcetDiscoverWishlistUITests` on iOS/Mac targets only; runner `iphone` selects real iPhone app, fresh fixture namespace, explicit expected identities and independent xcresult/JUnit.

- [ ] **Step 1: Write red tests.** `testEverywhereQueryPagesAndHeldRouting`: type old/new, observe final artist→album→song, artist/album/edition/held-track→scoped library/player. `testOfflineWishRelaunchRemoveAndScanArrival`: `XCTAssertTrue(app.staticTexts["Waiting to sync"].exists)`, kill/relaunch/reconnect→one wish; lost A receipt/remove/scan→wish absent with My Library retained; B/scan/refresh→Arrived→local item. `testBusyStaleAndNavidromeControl` arms 429/stale, checks notice/age/rows, then positive Navidrome browse and zero extra feature calls. `testAccountSwitchAndIdentityReplacementFence` holds A across B/replacement consent cancel; no wrong send/navigation. `testIPhoneLargestTextAndVoiceOverLabels` checks light/dark/largest text/hittable labeled actions.
- [ ] **Step 2: Verify red.** `tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs iphone`; register harness first and record missing-UI red on pre-UI Task 10 app before Tasks 11–12.
- [ ] **Step 3: Implement.** Wire real app login/reader/control through existing UI-test-only setup; no fake successful data. Capture device/idiom, request/scan acknowledgements, scoped held id and process restart.
- [ ] **Step 4: Verify green.** Same runner on current app; preserve xcresult/named counts and compressed Search/album/pending/Arrived/Wishlist captures. Only ios cells.
- [ ] **Step 5: Commit.** `test(ios): prove native Discover and Wishlist`.

### Task 14: iPad split-view proof

**Files:** Extend `apple/DulcetDiscoverUITestSupport/DulcetDiscoverWishlistUITests.swift`, `tools/ci/run-discover-wishlist-proofs` (ipad mode); modify `apple/DulcetKit/Sources/DulcetKit/DulcetDiscoverViews.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetWishlistViews.swift` only for observed iPad fixes.

**Interfaces:** Same semantic suite, independent `ipad` destination/result bundle and DulcetDiscoverWishlistIPadUITests evidence identity; run the four semantic tests from Task 13, not just layout.

- [ ] **Step 1: Write red tests.** `testIPadSidebarSelectionSurvivesWishlistDetailAndResize`: native Wishlist sidebar→pending row→album; regular/compact resize preserves account/item/back route. `XCTAssertEqual(observedIdiom,"pad")`; `XCTAssertTrue(sidebar.frame.maxX <= detail.frame.minX)` at regular width. Keyboard, light/dark and largest text keep actions reachable.
- [ ] **Step 2: Verify red.** `tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs ipad`; record missing sidebar/detail red on pre-UI Task 10 app before Tasks 11–12.
- [ ] **Step 3: Implement.** Only observed iPad layout/selection fixes; no alternate data source or reuse of iPhone evidence.
- [ ] **Step 4: Verify green.** Repeat current app with full semantics/Navidrome; independent counts, request/scan chain and regular/compact captures. Only ipados cells.
- [ ] **Step 5: Commit.** `test(ipados): prove Discover and Wishlist split navigation`.

### Task 15: Mac native proof

**Files:** Extend `apple/DulcetDiscoverUITestSupport/DulcetDiscoverWishlistUITests.swift`, `tools/ci/run-discover-wishlist-proofs` (macos mode); modify `apple/DulcetKit/Sources/DulcetKit/DulcetDiscoverViews.swift`, `apple/DulcetKit/Sources/DulcetKit/DulcetWishlistViews.swift` only for observed Mac fixes.

**Interfaces:** Runner `macos` uses real app host, `xcodebuild test -scheme DulcetMac -destination platform=macOS`, serialized; independent bundle/DulcetDiscoverWishlistMacUITests identity. Run Task 13 semantic suite.

- [ ] **Step 1: Write red tests.** `testMacKeyboardSearchSidebarAndContextRemove`: focus/type/select/keyboard-activate/context Remove wish→persisted server absence after positive add; `XCTAssertTrue(app.menuItems["Remove wish"].isEnabled)`. `testMacWindowClosureDropsPendingNavigation` closes delayed detail, asserts no reopened window/wrong account. Light/dark, large text, minimum window and accessibility actions.
- [ ] **Step 2: Verify red.** `tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs macos`; pre-UI Task 10 build records missing-feature red before Tasks 11–12.
- [ ] **Step 3: Implement.** Native focus/menu/window fixes only; do not use older fake My Library host for evidence.
- [ ] **Step 4: Verify green.** Repeat current app/full semantics/Navidrome; native test identities/counts, traces and compressed captures. Only macos cells.
- [ ] **Step 5: Commit.** `test(macos): prove native Discover and Wishlist`.

### Task 16: Android source and account lifecycle

**Files:** Create `android/shared/src/main/kotlin/com/legitimateapps/dulcet/library/DiscoverWishlist.kt`; modify `android/shared/src/main/kotlin/com/legitimateapps/dulcet/library/LibrarySession.kt`; test `android/app/src/test/kotlin/com/legitimateapps/dulcet/DiscoverWishlistSessionTest.kt`.

**Interfaces:** `DiscoverWishlistSource` mirrors locked client; `CoreDiscoverWishlistSource(reader:AndroidLibraryReader)` wraps production facade; `DiscoverWishlistSession(source:DiscoverWishlistSource):AutoCloseable` exposes immutable StateFlow, lazy/account-keyed in LibrarySession.

- [ ] **Step 1: Write red tests.** `sessionSwitchClosesOldSourceBeforePublishingB`: `assertTrue(sourceA.closed)`, `assertEquals(providerB,session.publication.value.providerInstanceId)`. `sameUsernameDifferentProviderKeepsOutboxesSeparate`; `clearCacheDoesNotLoseIntent`; `signOutRemovesFeatureRows`; `tvCompositionDoesNotInstantiateFeature`. Delayed A callback cannot update B.
- [ ] **Step 2: Verify red.** `android_test DiscoverWishlistSessionTest` with two real account namespaces.
- [ ] **Step 3: Implement.** Follow CoreMyLibrarySource owner dispatch and #349 retirement. Existing credential/account handling only; no browser/manifest/callback/token-store work.
- [ ] **Step 4: Verify green.** Repeat plus applicable `:android:shared:testDebugUnitTest` account tests; compile phone DEV/PROD and TV compatibility.
- [ ] **Step 5: Commit.** `feat(android): compose account-bound Discover sessions`.

### Task 17: Android phone Search Everywhere and pages

**Files:** Create `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneDiscover.kt`, `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneDiscoverPages.kt`; modify `android/app/src/main/kotlin/com/legitimateapps/dulcet/MobileSearchScreen.kt`, `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneApp.kt`; test `android/app/src/test/kotlin/com/legitimateapps/dulcet/PhoneDiscoverTest.kt`.

**Interfaces:** `PhoneDiscoverSearch(session:DiscoverWishlistSession,openLibrary:(DiscoverLibraryRef)->Unit)`, typed artist/album destinations keyed by provider id. Consume source, not facade/network calls from Compose.

- [ ] **Step 1: Write red tests.** `scopeSwitchKeepsYourServerSearchIntact`; `incrementalGroupsAndLateQueryUseCorePublication`; `busyStaleAndOfflineKeepRowsVisible`; `editionSelectionShowsOnlySelectedTracks`; `partialUnknownAndHeldActionsAreDistinct`; `missingCanonicalMbidHasNoWishAction`. `compose.onNodeWithText("Everywhere").assertIsDisplayed()`, literal 8 of 12 and `assertEquals(expectedProvider,openedLibraryRef.providerInstanceId)`.
- [ ] **Step 2: Verify red.** `android_test PhoneDiscoverTest`.
- [ ] **Step 3: Implement.** Native search/results/detail, source credit, byte-backed artwork, “Not in your library”, explicit safe-link actions and core load-more. Existing scoped library/player route; dispose screen operations without deleting pending account work.
- [ ] **Step 4: Verify green.** Repeat; DEV/PROD assembly and no TV feature initialization.
- [ ] **Step 5: Commit.** `feat(android): add phone Search Everywhere and metadata pages`.

### Task 18: Android Wishlist and production outbox integration

**Files:** Create `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneWishlist.kt`; modify `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneLibrary.kt`, `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneApp.kt`, `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneDiscover.kt`, `android/app/src/main/kotlin/com/legitimateapps/dulcet/PhoneDiscoverPages.kt`; test `android/app/src/test/kotlin/com/legitimateapps/dulcet/PhoneWishlistTest.kt`, `android/app/src/test/kotlin/com/legitimateapps/dulcet/PhoneWishlistIntegrationTest.kt`; create `android/search-conformance/DiscoverWishlistFixture.kt` beside existing loopback production fixtures.

**Interfaces:** Library Wishlist uses session/rowKey; integration tests create actual AndroidLibraryReader, SQL driver and CoreDiscoverWishlistSource with fixture URLs.

- [ ] **Step 1: Write red tests.** `libraryWishlistIsCapabilityGatedAndDistinctFromMyLibrary`; `offlineAddRelaunchAndRemoveUsesDurableOperation`; `lostReceiptRemovalAndReaddCannotResurrectOldWish`; `fulfilledArrivalRoutesLocalItemWithoutClientAdd`; `discoverOffStillReadsAndRemoves`; `futureStateAndPlainNoteRemainVisible`; `permissionFailureRollsBackOnlyThatWish`. `assertEquals("Arrived",rendered.stateTitle)` and Waiting to sync, all five labels, history/30-day server filtering, reject while My Library retained; positive production extension requests.
- [ ] **Step 2: Verify red.** `./gradlew :android:app:testProdDebugUnitTest --rerun --tests '*PhoneWishlistTest' --tests '*PhoneWishlistIntegrationTest'` under fixture wrapper.
- [ ] **Step 3: Implement.** Native state/note/time/pending/error list, accessible remove and correction; stable rowKey through acknowledgement. Account teardown stays in LibrarySession; no client fulfilment/note editing.
- [ ] **Step 4: Verify green.** Repeat DEV/PROD variants; server list/My Library observations must support labels, not fake DTOs.
- [ ] **Step 5: Commit.** `feat(android): add phone Wishlist with durable mutations`.

### Task 19: Android emulator real-trigger proof

**Files:** Create `android/app/src/androidTest/kotlin/com/legitimateapps/dulcet/AndroidEmulatorDiscoverWishlistProofTest.kt`; extend `tools/ci/run-discover-wishlist-proofs` with android mode; modify production Android phone paths from Tasks 16–18 only for reproduced emulator findings.

**Interfaces:** Runner `android` installs real DEV phone app on leased emulator, fresh disposable account; explicit instrumentation filter and positive connected-test XML.

- [ ] **Step 1: Write red tests.** `everywherePagesAndHeldRoute`, `offlineWishRelaunchRemoveRaceAndArrival`, `busyStaleAndNavidromeControl`, `accountSwitchIdentityFence`, `largestFontAndTalkBackLabels` drive Task 13 semantic triggers on Android. `assertTrue(control.scanFinishedWithFulfilledWish)` then Arrived→`assertEquals(expectedLibraryId,control.lastOpenedLibraryId)`. Positively prove phone/account, restart, armed fault and known Navidrome browse before absence.
- [ ] **Step 2: Verify red.** `tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs android`; record missing-feature reds on Task 16 pre-UI app before Tasks 17–18. Missing test/dead fixture is infrastructure failure.
- [ ] **Step 3: Implement.** Use existing emulator launch/control path and owned-PID cleanup. Test real process death, Back/origin account, normal/largest font, light/dark and accessible actions; no physical phone.
- [ ] **Step 4: Verify green.** Repeat current app; preserve connected XML counts, device/Android API level, redacted traces and compressed captures. Only android cells.
- [ ] **Step 5: Commit.** `test(android): prove phone Discover and Wishlist on emulator`.

### Task 20: Integration, evidence and step-2 gates

**Files:** Modify `tools/ci/run-discover-wishlist-proofs`, `tools/test-discover-wishlist-fixture`, `FEATURES.yml`, `.github/workflows/core-ci.yml`, `.github/workflows/apple-ci.yml` as needed for finite proof jobs; create `docs/evidence/discover-wishlist-clients.md`; update `docs/superpowers/specs/2026-08-18-dulcet-design.md` §28 implementation entries. No approved-design rewrite.

**Interfaces:** Integrate all builders; evidence records head/command/workflow/job/test, XML/xcresult executed/failure/error/skip counts, device idiom, fixture revision and request/fault/scan chain. Matrix policy below; no counts guessed.

- [ ] **Step 1: Write red tests.** Runner self-test rejects missing expected identity (`assert rejected.first_cause == "expected-test-not-executed"`), zero tests, skips/failures, wrong idiom, unarmed fault or missing positive REST/Navidrome baseline; a valid positive report passes.
- [ ] **Step 2: Verify red.** `python3 tools/test-discover-wishlist-fixture`; `tools/ci/run-discover-wishlist-proofs --self-test`. Confirm named first cause.
- [ ] **Step 3: Implement.** Reconcile actual main/#349/#353, schema/project/reader overlaps. Record per-surface evidence; scrub private paths/credentials. Preserve design §10 PROD conditions. Approved design needs no protocol amendment.
- [ ] **Step 4: Verify green.** Full checks/soak below, then eventual PR/CI readiness with independent review coordinated by owner. No push/PR is authorized by this plan-only revision.
- [ ] **Step 5: Commit.** `test: record Discover client parity and native evidence`.

## Full checks, evidence and later lanes

Task 20 runs once on the final integrated head; repeat only for new changes/failures. Build-for-testing Apple schemes with the same fresh core framework/DerivedData used by native runs. Each native mode validates positive expected identities; failed fixture startup is not a feature red. Core compilation does not replace native execution.

```sh
./gradlew :core:allMetadataJar :core:jvmTest :core:testAndroidHostTest \
  :core:bundleAndroidMainAar :core:licensee :core:compileTestKotlinIosSimulatorArm64 \
  :core:macosArm64Test :android:app:testDevDebugUnitTest \
  :android:app:testProdDebugUnitTest :android:app:assembleDevDebug \
  :android:app:assembleProdDebug :android:tv:assembleDebug --rerun-tasks
tools/ci/with-discover-wishlist-fixture -- ./gradlew \
  :core-conformance:jvmTest --rerun --tests '*DiscoverWishlistConformanceTest'
swift test --package-path apple/DulcetKit
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs iphone
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs ipad
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs macos
tools/ci/with-discover-wishlist-fixture -- tools/ci/run-discover-wishlist-proofs android
python3 tools/parity_gate.py
python3 tools/verify_ci_policy.py
python3 tools/verify_os_floors.py --configuration-only
python3 tools/verify_release_policy.py
tools/verify_xcodegen_regeneration --self-test
tools/typecheck-xcuitest-sources --self-test
```

Before an eventual implementation PR, push its branch and dispatch `android-test-soak.yml` on that head: `task=:android:app:testProdDebugUnitTest`, `tests=*DiscoverWishlistSessionTest,*PhoneDiscoverTest,*PhoneWishlistTest,*PhoneWishlistIntegrationTest`, `repeat=20`, `disposable_conformance=true`. Separately soak `task=:core:testAndroidHostTest`, `tests=*AndroidDiscoverTest,*AndroidAccountDataTest`, repeat 20. Include any other actually changed host classes/modules. Cite each run's per-iteration XML and `SOAK RESULT passed=20 failed=0`; inspect first red cause before fixing/repeating.

Future delivery gates: core-ci, parity-gate and full apple-ci on the final integration head, including existing conformance legs. Add finite jobs `discover-wishlist-iphone`, `discover-wishlist-ipad`, `discover-wishlist-macos` to apple-ci and `discover-wishlist-android` to core-ci, with timeout/concurrency/artifact upload. Existing full core/Apple conformance remains required. If shared server contracts change, rerun `:server:test`/`:dulcet-api-conformance:test`; none are planned here.

Each surface has its own test identity, counts and artifact: Task 13→ios,14→ipados,15→macos,19→android. Preserve xcresult/connected XML, redacted request/scan chain and compressed native captures. Shared logic/Robolectric does not promote native cells.

| FEATURES row | Required native trigger → observed effect, independently on all four surfaces | Step-2 status |
|---|---|---|
| discover.search | Everywhere typing→final incremental groups; delayed old/429/stale→correct rows/notice/age; positive Navidrome browse→no new UI/calls | shipped only with that surface's proof |
| discover.pages | Artist→albums; album→edition/tracks; held action→scoped library/player; partial/unknown has no false complete link | partial until step 3 playlist pages |
| wishlist.add | Offline wish→durable pending→relaunch/reconnect one wish; lost-receipt/remove/scan race leaves A absent; B survives old replies | shipped only with durable native proof |
| wishlist.state | Library Wishlist→five labels/note/time/unknown/pending/error/history; Discover-off still reads/removes | shipped only with native/state-filter proof |
| wishlist.fulfil | Tagged target→actual scan→server fulfilled/My Library→native refresh Arrived→existing local item; removal retains membership | shipped only with full scan-to-UI chain |
| discover.shelves | Deferred step 3 | planned; both TVs remain planned for all client rows |
| wishlist.api | Existing `docs/evidence/discover-wishlist-server.md` | server-contract evidence only; no client-cell promotion or new app obligation |

Concrete #349 overlap: `LibraryReader.kt` admission/reconnect, `MutationOutbox.kt` composition/counts, `SeenCacheStore.kt`/`SeenCache.sq`/`ServerData.sq` cleanup/inventory, AndroidLibraryReader/AndroidAccountData shared dispatcher, AppleLibraryReaderFacade/AppleDownloadFacade retirement and native account composition. `AccountConnection.kt` and `ServerIdentity.kt` remain authoritative; no new discovery profile or auth state is added. Keep their replacement consent/null-count/media-ownership tests green.

Step 3 extends sources/routes for shelves/playlists; step 4 adds TV presentation. Future whole-app API adoption can replace transports/auth/sync together. None require changing canonical wish identity or generation semantics. Design §10 terms, upstream rate values and thumbnail caching rights remain PROD conditions, not findings from client tests.

## Self-review

- **Coverage:** Core seam/read/cache/outbox→1–9; four native shells→10–19; design §8 evidence→9,13–15,19–20. Shelves/playlists/TV deferred explicitly. Independent disable, correction, 30-day history, opacity, stale/unknown data and no acquisition retained.
- **Steps/types:** Twenty tasks, each Files/Interfaces plus five TDD/commit steps; exact model/facade/wire names defined once. Remove uses wishId or addOperationId, never MBID; provider id never substitutes for server instance id. Nullable changed is valid before first mutation, not unsettled. No OAuth/API transport/store/hook remains.
- **Focus:** Retirement→6–8/10/16; lost receipt/remove/fulfil/re-add→6/9 and four native proofs; stale/429/cursor→2/4–5 and native proofs; binding/withdrawal→1/6–7; partial/version/unknown→2/5/11/17 and native proofs.
- **Proportion/independence:** Retained exact interfaces, wire mapping, assertions, ordering and fencing; removed duplicated assertion blocks and implementation narration. Three builders share one locked contract; only integrator promotes cells from observed per-surface evidence.
