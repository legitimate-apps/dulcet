package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.db.Db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One of an artist's recordings as an outside popularity source ranks it, most popular first. */
data class PopularRecording(val recordingMbid: String?, val title: String, val listens: Long)

/**
 * Where an artist's popular songs come from. Kept this small so the source can move behind the
 * Connections layer as a capability without touching the ranking.
 *
 * [cachedTopRecordings] answers from what is already stored and never waits on the network: a
 * request's latency must not reveal whether another account opened the artist recently, and a
 * page load must not wait on an outside service. Null means no stored answer; the caller ranks as
 * if popularity did not exist. Only the artist's MusicBrainz id ever goes upstream.
 */
interface ArtistPopularity {
    suspend fun cachedTopRecordings(artistMbid: String): List<PopularRecording>?
    /** Asks for these artists to be fetched in the background; returns at once. */
    fun refresh(artistMbids: Collection<String>) {}
}

/**
 * ListenBrainz popularity, stored in the Discover cache. A request reads only the cache; a miss
 * (or an answer past its week) queues the artist for one background worker, which goes through
 * the shared polite queue one artist at a time. Every scan also queues each tagged artist, so a
 * library's artists are usually warm before anyone opens them.
 */
class ListenBrainzArtistPopularity(
    private val client: ListenBrainzClient,
    private val db: Db? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ArtistPopularity, AutoCloseable {
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val work = Channel<String>(Channel.UNLIMITED)
    /** Test hook: called after each background lookup finishes, whatever its outcome. */
    @Volatile var afterRefresh: ((String) -> Unit)? = null

    init {
        scope.launch {
            for (id in work) {
                try { client.topRecordingsForArtist(id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                finally { queued.remove(id); afterRefresh?.invoke(id) }
            }
        }
    }

    override suspend fun cachedTopRecordings(artistMbid: String): List<PopularRecording>? {
        val id = mbidOrNull(artistMbid) ?: return null
        val cached = try { client.cachedTopRecordingsForArtist(id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (cached == null || cached.age.stale) refresh(listOf(id))
        return cached?.value
    }

    override fun refresh(artistMbids: Collection<String>) {
        if (!client.popularityConfigured) return
        artistMbids.mapNotNull(::mbidOrNull).forEach { if (queued.add(it)) work.trySend(it) }
    }

    /** Queues every artist the library tags with a MusicBrainz id; called after a scan commits. */
    fun refreshLibrary() {
        if (!client.popularityConfigured || db == null) return
        // Off the scanner's thread: the read waits its turn rather than holding up the scan.
        scope.launch {
            refresh(db.read { query("SELECT DISTINCT lower(mbz_artist_id) AS mbid FROM artist WHERE mbz_artist_id IS NOT NULL AND mbz_artist_id != ''") { it.string("mbid") } })
        }
    }

    override fun close() { work.close(); scope.cancel() }
}
