package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.db.Db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

class UpstreamNotFound : RuntimeException("Discovery item not found")
data class Cached<T>(val value:T,val age:CacheAge,val generation:String,val dependencies:Map<String,String> = emptyMap())
data class CacheKey(val baseUrl:String,val path:String,val query:Map<String,String>,val parserVersion:Int=1) {
    internal fun digest():String {
        // Length framing avoids collisions between delimiters inside query values.
        val parts=listOf(baseUrl,path,parserVersion.toString())+query.toSortedMap().flatMap { listOf(it.key,it.value) }
        return MessageDigest.getInstance("SHA-256").digest(parts.joinToString("") { "${it.toByteArray().size}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
class DiscoverCache(private val db:Db,private val clock:DiscoverClock=SystemDiscoverClock,private val byteBudget:Long=256L*1024*1024) {
    companion object {
        const val HOUR=3_600_000L
        const val DAY=24*HOUR
        const val MAX_AGE=90*DAY
    }
    // Fixed stripes bound memory even under arbitrarily many search strings.
    private val locks=Array(64) { Mutex() }
    init {
        require(byteBudget>0)
        db.write {
            execute("CREATE TABLE IF NOT EXISTS discover_cache (key TEXT PRIMARY KEY,status INTEGER NOT NULL,body BLOB NOT NULL,fetched INTEGER NOT NULL,accessed INTEGER NOT NULL,generation TEXT NOT NULL,bytes INTEGER NOT NULL)")
            execute("CREATE INDEX IF NOT EXISTS discover_cache_lru ON discover_cache(accessed)")
        }
    }
    private data class Entry(val status:Int,val body:ByteArray,val fetched:Long,val generation:String)
    suspend fun <T> read(key:CacheKey,ttlMillis:Long,decode:(ByteArray)->T,fetch:suspend ()->UpstreamResponse):Cached<T> {
        require(ttlMillis>0)
        val digest=key.digest()
        return locks[(digest.hashCode() and Int.MAX_VALUE)%locks.size].withLock {
            val now=clock.wallMillis()
            val entry=db.read { single("SELECT * FROM discover_cache WHERE key=?",digest) { Entry(it.int("status"),it.bytes("body")!!,it.long("fetched"),it.string("generation")) } }
                ?.takeIf { (now-it.fetched).coerceAtLeast(0)<=MAX_AGE }
            val age=entry?.let { (now-it.fetched).coerceAtLeast(0) }
            val previous=if(entry?.status==200) runCatching { decode(entry.body) }.getOrNull() else null
            if(entry!=null && age!! < if(entry.status==404) DAY else ttlMillis) {
                if(entry.status==404) throw UpstreamNotFound()
                if(previous!=null) {
                    db.write { execute("UPDATE discover_cache SET accessed=? WHERE key=?",now,digest) }
                    return@withLock Cached(previous,CacheAge(entry.fetched,age/1000,false),entry.generation,mapOf(digest to entry.generation))
                }
            }
            try {
                val response=fetch()
                if(response.status!=200 && response.status!=404) throw UpstreamUnavailable()
                val value=if(response.status==200) try { decode(response.body) } catch(e:CancellationException) { throw e } catch(_:Exception) { throw UpstreamUnavailable() } else null
                val fetched=clock.wallMillis(); val generation=UUID.randomUUID().toString()
                db.write {
                    execute("INSERT OR REPLACE INTO discover_cache(key,status,body,fetched,accessed,generation,bytes) VALUES(?,?,?,?,?,?,?)",digest,response.status,response.body,fetched,fetched,generation,response.body.size)
                    pruneIn(this,fetched)
                }
                if(response.status==404) throw UpstreamNotFound()
                @Suppress("UNCHECKED_CAST")
                Cached(value as T,CacheAge(fetched,0,false),generation,mapOf(digest to generation))
            } catch(e:CancellationException) { throw e }
              catch(e:UpstreamNotFound) { throw e }
              catch(e:Exception) {
                if(previous!=null && entry!=null && clock.wallMillis()-entry.fetched<=MAX_AGE) Cached(previous,CacheAge(entry.fetched,(clock.wallMillis()-entry.fetched).coerceAtLeast(0)/1000,true),entry.generation,mapOf(digest to entry.generation))
                else throw (e as? UpstreamUnavailable ?: UpstreamUnavailable())
            }
        }
    }
    fun contains(dependencies:Map<String,String>):Boolean = db.read {
        dependencies.all { (key,generation) -> long("SELECT count(*) FROM discover_cache WHERE key=? AND generation=? AND fetched>=?",key,generation,clock.wallMillis()-MAX_AGE)==1L }
    }
    fun prune() { db.write { pruneIn(this,clock.wallMillis()) } }
    private fun pruneIn(session:Db.Session,now:Long) = with(session) {
        execute("DELETE FROM discover_cache WHERE fetched<?",now-MAX_AGE)
        var size=long("SELECT coalesce(sum(bytes),0) FROM discover_cache")
        val snapshotBytes=if(long("SELECT count(*) FROM sqlite_master WHERE name='discover_snapshot'")>0)long("SELECT coalesce(sum(length(CAST(data AS BLOB))+length(CAST(dependencies AS BLOB))),0) FROM discover_snapshot") else 0L
        val available=(byteBudget-snapshotBytes).coerceAtLeast(0)
        if(size>available) for((key,bytes) in query("SELECT key,bytes FROM discover_cache ORDER BY accessed,key") { it.string("key") to it.long("bytes") }) {
            execute("DELETE FROM discover_cache WHERE key=?",key); size-=bytes
            if(size<=available) break
        }
    }
}
