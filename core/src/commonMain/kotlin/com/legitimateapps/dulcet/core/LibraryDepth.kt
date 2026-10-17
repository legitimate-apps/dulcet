package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Device-only state for one bound account. Indexed access keeps the Apple boundary primitive. */
public class LibraryDepthState internal constructor(
    private val categories: Map<String, Pair<String, String>> = emptyMap(),
    private val pins: List<Pair<String, String>> = emptyList(),
    private val searches: List<String> = emptyList(),
    public val errorKind: String? = null,
) {
    public fun sort(category: String): String = categories[category.lowercase()]?.first ?: "title"
    public fun filter(category: String): String = categories[category.lowercase()]?.second ?: "all"
    public val pinCount: Int get() = pins.size
    public fun pinKind(index: Int): String = pins.getOrNull(index)?.first ?: ""
    public fun pinRawId(index: Int): String = pins.getOrNull(index)?.second ?: ""
    public val recentSearchCount: Int get() = searches.size
    public fun recentSearch(index: Int): String = searches.getOrNull(index) ?: ""
}

internal fun BoundSeenCache.libraryDepthState(): LibraryDepthState {
    val entries = depthQueries.selectUserEntries(serverId).executeAsList()
    return LibraryDepthState(
        categories = entries.filter { it.category == "category" }.associate {
            val value = LIBRARY_JSON.parseToJsonElement(it.value_).jsonArray
            it.raw_id.lowercase() to (value[0].jsonPrimitive.content to value[1].jsonPrimitive.content)
        },
        pins = entries.filter { it.category.startsWith("pin.") }.map { it.category.removePrefix("pin.") to it.raw_id },
        searches = entries.filter { it.category == "search" }.take(12).map { it.value_ },
    )
}

internal fun BoundSeenCache.setLibraryCategory(category: String, sort: String, filter: String) {
    require(category.isNotBlank() && category.length <= 80 && sort.length <= 80 && filter.length <= 512)
    depthPut("category", category.lowercase(), JsonArray(listOf(JsonPrimitive(sort), JsonPrimitive(filter))).toString())
}

internal fun BoundSeenCache.setLibraryPin(kind: String, rawId: String, pinned: Boolean) {
    val canonical = if (kind == "song") "track" else kind
    require(canonical in setOf("album", "artist", "track", "playlist") && rawId.isNotBlank())
    if (pinned) depthPut("pin.$canonical", rawId, "") else depthQueries.deleteEntry(serverId, "pin.$canonical", rawId)
}

internal fun BoundSeenCache.recordSearch(text: String) {
    val clean = text.trim().take(512)
    if (clean.isEmpty()) return
    depthPut("search", normalizeSearchText(clean), clean)
    depthQueries.selectUserEntries(serverId).executeAsList().filter { it.category == "search" }.drop(12).forEach {
        depthQueries.deleteEntry(serverId, "search", it.raw_id)
    }
}

internal fun BoundSeenCache.clearRecentSearches() = depthQueries.deleteCategory(serverId, "search")

internal fun encodeAlbumMetadata(album: CacheAlbumRecord): String = JsonObject(mapOf(
    "types" to JsonArray(album.releaseTypes.map(::JsonPrimitive)),
    "date" to JsonPrimitive(album.originalReleaseDate),
)).toString()

internal fun decodeAlbumMetadata(encoded: String?): Pair<List<String>, String?> {
    if (encoded == null) return emptyList<String>() to null
    val value = LIBRARY_JSON.parseToJsonElement(encoded).jsonObject
    return value.getValue("types").jsonArray.map { it.jsonPrimitive.content } to value["date"]?.jsonPrimitive?.contentOrNull
}

internal fun discographyGroup(types: List<String>): String {
    val normalized = types.map { it.lowercase().replace("-", "").replace(" ", "") }
    return when {
        normalized.any { it in setOf("appearson", "appears_on") } -> "appearsOn"
        "compilation" in normalized -> "compilations"
        normalized.any { it in setOf("ep", "single") } -> "epsSingles"
        else -> "albums"
    }
}

internal fun newestDiscography(items: List<LibraryItem>, artistRawId: String? = null): List<LibraryItem> = items.map { item ->
    if (item is LibraryItem.Album && artistRawId != null && item.artistRawId != null && item.artistRawId != artistRawId) item.copy(discographyGroup = "appearsOn") else item
}.sortedWith(
    compareByDescending<LibraryItem> { (it as? LibraryItem.Album)?.originalReleaseDate ?: (it as? LibraryItem.Album)?.year?.toString().orEmpty() }
        .thenBy { (it as? LibraryItem.Album)?.title?.lowercase().orEmpty() }.thenBy { it.rawId },
)

/** Local sorts never claim to change the server's order or expand a loaded window's coverage. */
internal fun sortLibraryItems(items: List<LibraryItem>, sort: String, filter: String): List<LibraryItem> {
    fun title(item: LibraryItem): String = when (item) {
        is LibraryItem.Album -> item.title
        is LibraryItem.Artist -> item.name
        is LibraryItem.Track -> item.title.orEmpty()
        is LibraryItem.Playlist -> item.name
        is LibraryItem.Genre -> item.rawId
    }
    fun artist(item: LibraryItem): String = when (item) {
        is LibraryItem.Album -> item.artistName.orEmpty()
        is LibraryItem.Track -> item.artistName.orEmpty()
        else -> title(item)
    }
    val scoped = when (filter.lowercase()) {
        "favorites", "favourites", "starred" -> items.filter { when (it) {
            is LibraryItem.Album -> it.starred == true
            is LibraryItem.Artist -> it.starred == true
            is LibraryItem.Track -> it.starred == true
            else -> false
        } }
        "downloaded" -> items.filter { it is LibraryItem.Track && it.playability == LibraryPlayability.Downloaded }
        else -> items
    }
    val needle = if (filter.lowercase() in setOf("all", "favorites", "favourites", "starred", "downloaded")) "" else normalizeSearchText(filter.removePrefix("text:").trim())
    val filtered = if (needle.isEmpty()) scoped else scoped.filter { needle in normalizeSearchText(title(it) + " " + artist(it)) }
    val comparator = when (sort.lowercase()) {
        "titledescending" -> compareByDescending<LibraryItem> { title(it).lowercase() }
        "artist", "alphabeticalbyartist" -> compareBy<LibraryItem> { artist(it).lowercase() }.thenBy { title(it).lowercase() }
        "mostplayed", "frequent" -> compareByDescending<LibraryItem> { when (it) { is LibraryItem.Track -> it.playCount ?: 0; is LibraryItem.Album -> it.playCount ?: 0; else -> 0 } }
        "year", "releasedate", "byyear" -> compareByDescending<LibraryItem> { (it as? LibraryItem.Album)?.originalReleaseDate ?: (it as? LibraryItem.Album)?.year?.toString().orEmpty() }
        else -> compareBy<LibraryItem> { title(it).lowercase() }
    }
    return if (sort.lowercase() == "recentlyadded") filtered else filtered.sortedWith(comparator.thenBy { it.rawId })
}

/** Local collections and optional artist recommendations; never a whole-library mirror. */
internal class LibraryDepthWindow(reader: LibraryReader, query: LibraryQuery, listener: (LibraryPublication) -> Unit) : ReaderHandle(reader, query, listener) {
    private var serverTopSongs: List<String>? = null
    private var readAt: Long? = null
    private var attempted = false
    override fun needsRevalidation(): Boolean = query is LibraryQuery.ArtistTopSongs && !attempted
    override suspend fun performRevalidate(requested: RevalidateCause) {
        if (query !is LibraryQuery.ArtistTopSongs || !reader.online) { emitSnapshot(); return }
        attempted = true
        val name = cache.artist(query.rawId)?.record?.name
        if (name == null) { emitSnapshot(); return }
        try {
            val parameters = mapOf("artist" to name, "count" to "20")
            val sent = reader.send("getTopSongs", parameters).requireOk("getTopSongs", parameters)
            val tracks = parseReaderTopSongs(sent.response.body)
            cache.writeEntities(CacheWriteStamp(sent.issueSeq, cache.now(), reader.sessionEpoch?.key), CacheEntitySource.ListPage, CacheEntities(tracks = tracks))
            serverTopSongs = tracks.map { it.rawId }.takeIf { it.isNotEmpty() }
            readAt = cache.now()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (thrown: Throwable) { readFailed(thrown) }
        emitSnapshot()
    }
    override fun snapshot(): LibraryPublication {
        val rows = when (val current = query) {
            LibraryQuery.Downloaded -> reader.downloads.downloadedTrackRawIds(cache.serverId).sorted().mapNotNull { id ->
                cache.track(id)?.let { CachedListRow(0, CacheItemKind.Track, track = it) }
            }
            LibraryQuery.Pinned -> cache.depthQueries.selectUserEntries(cache.serverId).executeAsList().filter { it.category.startsWith("pin.") }.mapNotNull { pin ->
                when (pin.category) {
                    "pin.album" -> cache.album(pin.raw_id)?.let { CachedListRow(0, CacheItemKind.Album, album = it) }
                    "pin.artist" -> cache.artist(pin.raw_id)?.let { CachedListRow(0, CacheItemKind.Artist, artist = it) }
                    "pin.track" -> cache.track(pin.raw_id)?.let { CachedListRow(0, CacheItemKind.Track, track = it) }
                    "pin.playlist" -> cache.playlist(pin.raw_id)?.let { CachedListRow(0, CacheItemKind.Playlist, playlist = it) }
                    else -> null
                }
            }
            is LibraryQuery.ArtistTopSongs -> {
                val name = cache.artist(current.rawId)?.record?.name
                val tracks = serverTopSongs?.mapNotNull(cache::track) ?: cache.localTracks().filter { t ->
                    t.record?.credits?.any { it.artistRawId == current.rawId || (it.artistRawId == null && it.name == name) } == true &&
                        ((t.userState.playCount ?: 0) > 0 || t.userState.starred == true)
                }.sortedWith(compareByDescending<CachedTrack> { it.userState.playCount ?: 0 }.thenByDescending { it.userState.starred == true }.thenBy { it.record?.title }).take(20)
                tracks.map { CachedListRow(0, CacheItemKind.Track, track = it) }
            }
            else -> emptyList()
        }.mapIndexed { index, row -> row.copy(position = index) }
        val readItems = itemsOf(rows, true).first
        val items = if (query == LibraryQuery.Downloaded) reader.applyCategory(readItems, "downloaded", "title") else readItems
        return LibraryPublication(query, 0,
            if (query is LibraryQuery.ArtistTopSongs) cachedFreshness(readAt, serverTopSongs != null) else LibraryFreshness.Cached(null, if (reader.online) LibraryCachedReason.Stale else offlineReason()),
            if (query is LibraryQuery.ArtistTopSongs) LibraryCoverage.Open else LibraryCoverage.Complete,
            items.size, null, items, LibraryItemsState.Present, LibraryItemsOrder.LocalView)
    }
    override fun mentionsAny(rawIds: Set<String>): Boolean = true
    override fun loadMore() { reader.checkConfined() }
    override fun loadBefore() { reader.checkConfined() }
    override fun setViewport(firstIndex: Int, lastIndex: Int) { reader.checkConfined() }
}

internal fun LibraryReader.applyCategory(items: List<LibraryItem>, category: String, fallbackSort: String? = null, fallbackFilter: String = "all"): List<LibraryItem> {
    val saved = cache.depthValue("category", category.lowercase())
    if (saved == null && fallbackSort == null && fallbackFilter in setOf("", "all")) return items
    val state = cache.libraryDepthState()
    val sort = if (saved != null) state.sort(category) else fallbackSort ?: "recentlyAdded"
    val filter = if (saved != null) state.filter(category) else fallbackFilter
    // Album server sorting is already in its request; a loaded window must retain that order.
    val effectiveSort = if (category == "albums") "recentlyAdded" else sort
    val filtered = sortLibraryItems(items, effectiveSort, if (filter == "downloaded") "all" else filter)
    val sorted = if (sort.lowercase() in setOf("year", "releasedate", "byyear") && category != "albums") {
        val albums = cache.localAlbums().associate { it.record.rawId to (it.record.originalReleaseDate ?: it.record.year?.toString().orEmpty()) }
        filtered.sortedWith(compareByDescending<LibraryItem> { when (it) {
            is LibraryItem.Album -> it.originalReleaseDate ?: it.year?.toString().orEmpty()
            is LibraryItem.Track -> albums[it.albumRawId].orEmpty()
            else -> ""
        } }.thenBy { it.rawId })
    } else filtered
    if (filter != "downloaded") return sorted
    val downloaded = downloads.downloadedTrackRawIds(cache.serverId)
    val downloadedRecords = downloaded.mapNotNull { cache.track(it)?.record }
    val downloadedAlbums = downloadedRecords.mapNotNull { it.albumRawId }.toSet()
    val downloadedArtists = downloadedRecords.flatMap { it.credits }.mapNotNull { it.artistRawId }.toSet()
    return sorted.filter { item -> when (item) {
        is LibraryItem.Track -> item.rawId in downloaded
        is LibraryItem.Album -> item.rawId in downloadedAlbums
        is LibraryItem.Artist -> item.rawId in downloadedArtists
        else -> false
    } }
}

internal fun <T> LibraryReader.applyMyLibraryCategory(items: List<T>, category: String, id: (T) -> String, rank: (T) -> Long, fallbackSort: String = "title", item: (T) -> LibraryItem): List<T> {
    val state = cache.libraryDepthState()
    val base = if (state.sort(category) == "recentlyAdded") items.sortedByDescending(rank) else items
    val byId = base.associateBy(id)
    return applyCategory(base.map(item), category, fallbackSort = fallbackSort).mapNotNull { byId[it.rawId] }
}
