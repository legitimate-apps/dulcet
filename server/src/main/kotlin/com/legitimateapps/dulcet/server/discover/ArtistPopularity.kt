package com.legitimateapps.dulcet.server.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One of an artist's recordings as an outside popularity source ranks it, most popular first. */
data class PopularRecording(val recordingMbid: String?, val title: String, val listens: Long)

/**
 * Where an artist's popular songs come from. Kept to this one call so the source can move behind
 * the Connections layer as a capability without touching the ranking.
 *
 * Returns null when no answer is available (the source is off, unconfigured, failing or slow); the
 * caller then ranks as if popularity did not exist. Only the artist's MusicBrainz id goes upstream.
 */
fun interface ArtistPopularity {
    suspend fun topRecordings(artistMbid: String): List<PopularRecording>?
}

/**
 * ListenBrainz popularity, through the shared polite queue and the Discover cache. A cold lookup is
 * waited on for at most [waitMillis]; past that the answer is not used for this request but the
 * lookup runs on, so the cache is warm the next time the artist is opened.
 */
class ListenBrainzArtistPopularity(private val client: ListenBrainzClient, private val waitMillis: Long = 3_000) : ArtistPopularity {
    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun topRecordings(artistMbid: String): List<PopularRecording>? {
        val id = mbidOrNull(artistMbid) ?: return null
        val answer = CompletableDeferred<List<PopularRecording>?>()
        GlobalScope.launch(Dispatchers.IO) {
            answer.complete(
                try { client.topRecordingsForArtist(id)?.value }
                catch (e: CancellationException) { null }
                catch (_: Exception) { null },
            )
        }
        return withTimeoutOrNull(waitMillis) { answer.await() }
    }
}
