package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal object DiscoverRoutePolicy {
    fun libraryTarget(item: DiscoverItem): DiscoverLibraryRef? {
        if (item.availability.status != "held") return null
        if (item.kind !in setOf("artist","album","song")) return null
        if (item.kind == "album" && item.availability.completeness != "known") return null
        return item.availability.libraryItems.singleOrNull()?.takeIf {it.type==item.kind}
    }
    fun safeLink(value: String): Boolean = try {
        val url=Url(value)
        url.protocol.name in setOf("http","https") && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
    } catch (_: Exception) {false}
}

/** Page completions are one-shot. A new edition revokes old edition operations before delivery. */
internal class DiscoverPages(
    private val scope: CoroutineScope,
    private val transport: DiscoverTransport,
    private val store: DiscoverStore,
    private val online: () -> Boolean,
    private val active: () -> Boolean = {true},
    private val backoff: DiscoverBackoff = DiscoverBackoff({Clock.System.now().toEpochMilliseconds()}),
) {
    private var closed=false
    private val operations=mutableSetOf<PageOperation<*>>()
    private val editions=mutableMapOf<String,String?>()
    // Requested identity is separate from the default edition resolved by a response. Two
    // presentations may read the same album while a navigation stack is replaced.
    private val requestedEditions=mutableMapOf<String,String?>()
    private val epochs=mutableMapOf<String,Long>()
    private val albumOperations=mutableMapOf<String,MutableSet<DiscoverOperation>>()

    fun artist(mbid: String, completion: (DiscoverResult<DiscoverArtist>)->Unit): DiscoverOperation = json(
        "artist:$mbid",DiscoverArtist.serializer(),{it.copy(albums=it.albums.asDeviceCached())},completion) {transport.artist(mbid)}
    fun artistAlbums(mbid: String,cursor: String?,completion: (DiscoverResult<DiscoverPage>)->Unit): DiscoverOperation = json(
        "artist-albums:$mbid:$cursor",DiscoverPage.serializer(),{it.asDeviceCached()},completion) {transport.artistAlbums(mbid,cursor)}
    fun album(mbid: String,releaseId: String?,completion: (DiscoverResult<DiscoverAlbum>)->Unit): DiscoverOperation {
        val changed=mbid !in requestedEditions || requestedEditions[mbid]!=releaseId
        val epoch=if(changed) (epochs[mbid] ?: 0)+1 else epochs.getValue(mbid)
        if(changed) {
            epochs[mbid]=epoch;requestedEditions[mbid]=releaseId;editions[mbid]=releaseId
            albumOperations.remove(mbid)?.toList()?.forEach {it.cancel()}
        }
        val op=json("album:$mbid:$releaseId",DiscoverAlbum.serializer(),{it.copy(tracks=it.tracks.asDeviceCached())}, { result ->
            if(result is DiscoverResult.Value && epochs[mbid]==epoch) editions[mbid]=result.value.selectedReleaseId
            completion(result)
        },{epochs[mbid]==epoch}) {transport.album(mbid,releaseId)}
        if(op is PageOperation<*> && !op.done) albumOperations.getOrPut(mbid){mutableSetOf()}.add(op)
        return op
    }
    fun albumTracks(mbid: String,releaseId: String,cursor: String?,completion: (DiscoverResult<DiscoverPage>)->Unit): DiscoverOperation {
        if (mbid !in epochs) {epochs[mbid]=1;editions[mbid]=releaseId;requestedEditions[mbid]=releaseId}
        val epoch=epochs[mbid]
        val op=json("album-tracks:$mbid:$releaseId:$cursor",DiscoverPage.serializer(),{it.asDeviceCached()},completion,
            {epochs[mbid]==epoch && editions[mbid]==releaseId}) {transport.albumTracks(mbid,releaseId,cursor)}
        if(op is PageOperation<*> && !op.done) albumOperations.getOrPut(mbid){mutableSetOf()}.add(op)
        return op
    }
    fun artwork(artworkId: String,completion: (DiscoverResult<ByteArray>)->Unit): DiscoverOperation = request(
        "artwork:$artworkId",true,{bytes -> if(!validDiscoverArtwork(bytes)) invalidDiscover();bytes},
        {bytes -> if(!validDiscoverArtwork(bytes)) invalidDiscover();bytes},{it},completion,{true}) {transport.artwork(artworkId)}
    fun close() {closed=true;operations.toList().forEach {it.cancel()};albumOperations.clear()}

    private fun <T> json(key: String,serializer: KSerializer<T>,cached: (T)->T,completion: (DiscoverResult<T>)->Unit,
        valid: ()->Boolean = {true},fetch: suspend ()->DiscoverResult<T>): DiscoverOperation = request(key,false,
            {Json.decodeFromString(serializer,it.decodeToString())},{Json.encodeToString(serializer,it).encodeToByteArray()},cached,completion,valid,fetch)

    private fun <T> request(key: String,artwork: Boolean,decode: (ByteArray)->T,encode: (T)->ByteArray,cached: (T)->T,
        completion: (DiscoverResult<T>)->Unit,valid: ()->Boolean,fetch: suspend ()->DiscoverResult<T>): DiscoverOperation {
        val op=PageOperation(completion)
        operations.add(op)
        if(closed || !active() || !valid()) {op.cancel();return op}
        op.job=scope.launch {
            try {
                val saved=store.read(key)?.let {runCatching {cached(decode(it))}.getOrNull()}
                val error=if(!online()) DiscoverFailure("offline",null,true) else backoff.error()
                val result=if(error!=null) DiscoverResult.Failed(error) else {
                    if(!active() || !valid() || op.done || closed) {op.cancel();return@launch}
                    val issue=store.nextIssue()
                    val fetched=fetch()
                    if(fetched is DiscoverResult.Failed) backoff.note(fetched.error)
                    if(!active() || !valid() || op.done || closed) {op.cancel();return@launch}
                    if(fetched is DiscoverResult.Value) store.putRead(key,encode(fetched.value),artwork,issue)
                    fetched
                }
                if(!active() || !valid() || closed) op.cancel()
                else op.finish(if(result is DiscoverResult.Failed && saved!=null) DiscoverResult.Value(saved) else result)
            } catch (cancelled: CancellationException) {op.cancel()}
              catch (failure: DiscoverWireFailure) {op.finish(DiscoverResult.Failed(failure.failure))}
              catch (_: Exception) {op.finish(DiscoverResult.Failed(DiscoverFailure("storage",null,true)))}
        }
        return op
    }
    private inner class PageOperation<T>(private val completion: (DiscoverResult<T>)->Unit): DiscoverOperation {
        var job: Job?=null
        var done=false
        fun finish(result: DiscoverResult<T>) {
            if(done) return
            done=true;operations.remove(this);albumOperations.values.forEach {it.remove(this)}
            completion(if(result is DiscoverResult.Value && (!active() || closed)) DiscoverResult.Failed(DiscoverFailure("cancelled",null,false)) else result)
        }
        override fun cancel() {job?.cancel();finish(DiscoverResult.Failed(DiscoverFailure("cancelled",null,false)))}
    }
}
internal fun validDiscoverArtwork(bytes: ByteArray): Boolean = bytes.size<=DISCOVER_ART_LIMIT && (
    bytes.matchesBytes(0,0xff,0xd8,0xff) || bytes.matchesBytes(0,137,80,78,71,13,10,26,10) ||
    bytes.matchesAscii(0,"GIF87a") || bytes.matchesAscii(0,"GIF89a") || (bytes.matchesAscii(0,"RIFF") && bytes.matchesAscii(8,"WEBP")))
