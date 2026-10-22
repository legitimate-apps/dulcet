package com.legitimateapps.dulcet.server.wishlist

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.UNKNOWN_ALBUM
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.scan.ScanDelta
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A conflated wakeup owns no data: the candidate set survives process loss in SQLite. */
class WishFulfilment(private val ctx:ServerContext,private val clock:()->Long=System::currentTimeMillis) : AutoCloseable {
    private val wakeups=Channel<Unit>(Channel.CONFLATED)
    private val lock=Mutex()
    private var worker:Job?=null
    private var closed=false
    fun start(scope:CoroutineScope) {
        if(worker!=null || closed)return
        worker=scope.launch(Dispatchers.IO) {
            safeDrain()
            for(signal in wakeups)safeDrain()
        }
    }
    private suspend fun safeDrain() {
        try { drain() } catch(e:CancellationException) { throw e } catch(_:Exception) { org.slf4j.LoggerFactory.getLogger(WishFulfilment::class.java).warn("wish fulfilment deferred; pending work retained") }
    }
    fun enqueue(delta:ScanDelta) { if(!closed)wakeups.trySend(Unit) }
    suspend fun drain() = lock.withLock {
        if(closed || !ctx.config.discover.wishlistEnabled)return@withLock
        val pending=ctx.db.read { single("SELECT * FROM wishlist_scan_pending WHERE next_attempt<=?",clock()) { row ->
            fun ids(column:String)=Json.parseToJsonElement(row.string(column)).jsonArray.map { it.jsonPrimitive.content }.toSet()
            ScanDelta(row.string("scan_id"),ids("track_ids"),ids("album_ids"),ids("artist_ids"))
        } } ?: return@withLock
        val stamp=ctx.db.read { string("SELECT value FROM meta WHERE key='discover_scan_stamp'") } ?: return@withLock
        val wishes=ctx.db.read { query("SELECT * FROM wishlist_wish WHERE state<>'fulfilled' ORDER BY added,id",map=ctx.wishlist.store::row) }
        var retry:Long?=null
        for(wish in wishes) {
            currentCoroutineContext().ensureActive()
            try {
                val availability=evaluate(wish,pending) ?: continue
                val ref=availability.libraryItems.firstOrNull()
                val applied=if(availability.status=="held" && ref!=null)ctx.wishlist.fulfil(wish.userId,wish.id,ref,wish.revision,stamp,availability)
                    else ctx.wishlist.updateAvailability(wish.userId,wish.id,wish.revision,stamp,availability)
                if(!applied && ctx.wishlist.get(wish.userId,wish.id)?.state?.let { it!="fulfilled" }==true)retry=maxOf(retry ?: 0,0)
            } catch(e:CancellationException) { throw e }
              catch(e:UpstreamUnavailable) {
                  val local=ctx.discoverMatcher.match(wish.userId,DiscoverItem(kind=wish.kind,title=wish.snapshot.title,artist=wish.snapshot.artist,externalIds=wish.externalIds))
                  ctx.wishlist.updateAvailability(wish.userId,wish.id,wish.revision,stamp,local)
                  retry=maxOf(retry ?: 0,e.retryAfterMillis ?: 60_000)
              }
              catch(_:UpstreamNotFound) { retry=maxOf(retry ?: 0,DiscoverCache.DAY) }
        }
        ctx.db.write {
            if(retry==null)execute("DELETE FROM wishlist_scan_pending WHERE scan_id=?",pending.scanId)
            else execute("UPDATE wishlist_scan_pending SET next_attempt=? WHERE scan_id=?",clock()+retry!!,pending.scanId)
        }
    }
    private suspend fun evaluate(wish:Wish,delta:ScanDelta):Availability? {
        val item=DiscoverItem(kind=wish.kind,title=wish.snapshot.title,artist=wish.snapshot.artist,year=wish.snapshot.year,externalIds=wish.externalIds)
        val target=wish.externalIds.getValue(wish.kind.registryKey)
        if(wish.kind!=DiscoverKind.Album) {
            val result=ctx.discoverMatcher.match(wish.userId,item)
            val candidates=if(wish.kind==DiscoverKind.Artist)delta.artistIds else delta.trackIds
            if(result.libraryItems.none { it.id in candidates })return null
            if(wish.kind==DiscoverKind.Artist) {
                val eligible=result.libraryItems.filter { ref ->
                    ctx.db.read { query("SELECT a.id FROM album a JOIN album_artist aa ON aa.album_id=a.id WHERE aa.artist_id=? AND a.name<>? AND ${ctx.access.catalogPredicate(wish.userId,"album","a")}",ref.id,UNKNOWN_ALBUM) { it.string("id") }.any { it in delta.albumIds } }
                }
                if(eligible.isEmpty())return null
                return result.copy(libraryItems=eligible)
            }
            return result
        }
        data class Local(val albumId:String,val name:String,val artist:String,val group:String?,val release:String?)
        val local=ctx.db.read { query("SELECT t.album_id,t.album,t.album_artist,t.mbz_release_group_id,t.mbz_album_id FROM track t WHERE ${ctx.access.catalogPredicate(wish.userId,"track","t")}") {
            Local(it.string("album_id"),it.string("album"),it.string("album_artist"),it.stringOrNull("mbz_release_group_id"),it.stringOrNull("mbz_album_id"))
        } }.filter { it.albumId in delta.albumIds }.distinct()
        fun same(a:String,b:String?)=b!=null && ctx.discoverMatcher.normalizeName(a)==ctx.discoverMatcher.normalizeName(b)
        val candidate=local.filter { row ->
            if(!row.group.isNullOrBlank())mbidOrNull(row.group)==target
            else same(row.artist,wish.snapshot.artist) && (same(row.name,wish.snapshot.title) || !row.release.isNullOrBlank())
        }
        if(candidate.isEmpty())return null
        val associated=candidate.filter { row ->
            if(!row.group.isNullOrBlank() || row.release.isNullOrBlank())true
            else ctx.musicBrainz.release(canonicalMbid(row.release)).value.releaseGroupMbid==target
        }
        if(associated.isEmpty())return null
        val editions=ctx.musicBrainz.editions(target).value
        return ctx.discoverMatcher.match(wish.userId,item,editions)
    }
    override fun close() { closed=true;worker?.cancel();wakeups.close() }
}
