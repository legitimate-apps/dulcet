# Discover client core handoff

Step 2 uses the existing authenticated `/rest` reader and detects `dulcetDiscover` / `dulcetWishlist` v1 from the shared `getOpenSubsonicExtensions` response. See the approved [client plan](../superpowers/plans/2026-10-22-discover-wishlist-clients.md) for all locked read models, transports and wire mappings. No public signature was amended.

Android constructs `AndroidDiscoverClient(reader: AndroidLibraryReader)`. It implements the following actual Kotlin interface, using the public common DTOs in `DiscoverModels.kt`:

```kotlin
public class AndroidDiscoverClient(reader: AndroidLibraryReader) : DiscoverClient

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

Apple constructs `AppleDiscoverClient(reader: AppleLibraryReaderClient)`. The [generated Objective-C declarations](discover-client-objc-signatures.h.txt) include the client, listener, subscription, every DTO and every result. They were extracted from a successful `:core:linkDebugFrameworkMacosArm64` build, rather than translated by hand. The full framework header is the compiler input. Nullable Kotlin `Int?` / `Long?` appear as nullable `DulcetCoreInt *` / `DulcetCoreLong *`; artwork uses `NSData *`. Collection fields expose `count` / safe nullable `At(index:)` accessors. No common sealed result, collection, Flow or SQL type occurs in these exported declarations.

Each facade is a child of the existing production reader. Construction sends nothing. Calls enter the reader owner; observer callbacks and completions reach the UI dispatcher. A registration immediately publishes the current state on that dispatcher. Closing synchronously revokes queued successes and publications. Operations complete once; cancellation before enqueue prevents the intent, and cancellation after enqueue leaves it durable. Parent close cancels children and drains the shared reader before store release.

Request and delivery admission use the reader's existing identity generation and external account epoch. A rejected epoch cannot send or acknowledge a wish. A success queued across rejection becomes `identity-changed`. A sanitized rejection snapshot may reach the UI only while that captured generation, epoch and rejection state remain unchanged. Failed composition publishes the correct account namespace, unresolved/storage, disabled gates and a null pending count.

The Linux soak after a PR push must include `AndroidDiscoverTest`, `AndroidAccountDataTest`, and the changed common tests `DiscoverAccountFenceTest` and `RestDiscoverTransportTest`. It has not been run as part of this local handoff.

`tools/test-discover-wishlist-fixture` no longer writes bytecode when loading extensionless helpers. The policy check after its self-test correctly requires a direct blocking workflow invocation for that real control. Wiring it belongs to Task 20.

Observed verification:

| Check | XML tests | Failures / errors / skips |
|---|---:|---|
| Initial rebased `:core:jvmTest --rerun` at `528b4b18` | 1274 | 0 / 0 / 0 |
| Initial rebased `:core:macosArm64Test --rerun` at `528b4b18` | 1363 | 0 / 0 / 0 |
| Final `:core:jvmTest --rerun` | 1288 | 0 / 0 / 0 |
| Final `:core:macosArm64Test --rerun` | 1393 | 0 / 0 / 0 |
| Final `:core:testAndroidHostTest --rerun` | 1561 | 0 / 0 / 0 |
| Disposable `DiscoverWishlistConformanceTest` | 3 | 0 / 0 / 0 |
| Disposable `DiscoverWishlistTransportComponentsTest` | 2 | 0 / 0 / 0 |
| Fixture self-test after bytecode fix | 8 | 0 / 0 / 0 |

The rebase compiled without repair. Focused Task 7 verification ran `DiscoverAccountFenceTest`, `MyLibraryTest`, `MutationOutboxReviewTest` and `OfflineMediaBindingSafetyTest`: JVM 102 and native 111. Android account-data verification ran 21 tests. Focused Task 8 verification ran 10 Apple facade tests, 6 Apple DTO tests and 9 Android facade tests. Counts come from each task's JUnit XML, not console totals.

The build checks `:core:compileTestKotlinIosSimulatorArm64`, `:core:bundleAndroidMainAar`, `:core:allMetadataJar` and `:core:linkDebugFrameworkMacosArm64` passed. Compilation is the evidence for iOS here; device UI evidence belongs to the shell tasks. The conformance wrapper reaped all five owned PIDs and deleted its root. Automated requests used only its disposable servers and generated media.
