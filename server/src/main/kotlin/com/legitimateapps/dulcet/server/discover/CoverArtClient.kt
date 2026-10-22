package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.v1.WebhookHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class DiscoverImage(val bytes:ByteArray,val contentType:String,val age:CacheAge,val placeholder:Boolean)
class CoverArtClient(private val http:PoliteHttp,private val cache:DiscoverCache,private val settings:DiscoverSettings) {
    companion object {
        const val MAX_BYTES=5*1024*1024
        fun imageType(bytes:ByteArray):String {
            fun starts(vararg prefix:Int)=bytes.size>=prefix.size && prefix.indices.all { bytes[it].toInt() and 255==prefix[it] }
            return when {
                starts(255,216,255)->"image/jpeg"
                starts(137,80,78,71,13,10,26,10)->"image/png"
                bytes.size>=6 && bytes.copyOfRange(0,6).decodeToString() in listOf("GIF87a","GIF89a")->"image/gif"
                bytes.size>=12 && bytes.copyOfRange(0,4).decodeToString()=="RIFF" && bytes.copyOfRange(8,12).decodeToString()=="WEBP"->"image/webp"
                else->throw UpstreamUnavailable()
            }
        }
        fun publicAddress(address:InetAddress)=WebhookHttpTransport().allowed(address)
        private val placeholder="""<svg xmlns="http://www.w3.org/2000/svg" width="250" height="250" viewBox="0 0 250 250"><rect width="250" height="250" fill="#303036"/><circle cx="125" cy="125" r="64" fill="#62626a"/><circle cx="125" cy="125" r="14" fill="#303036"/></svg>""".toByteArray()
    }
    private fun sameOrigin(a:URI,b:URI):Boolean {
        fun port(uri:URI)=if(uri.port>=0)uri.port else if(uri.scheme=="https")443 else 80
        return a.scheme==b.scheme && a.host.equals(b.host,true) && port(a)==port(b)
    }
    fun validateRedirect(from:URI,location:String):URI {
        val to=runCatching { from.resolve(location) }.getOrNull() ?: throw UpstreamUnavailable()
        if(to.toString().length>8192 || to.host==null || to.userInfo!=null || to.fragment!=null || to.port==0 || to.port>65535 || to.scheme !in listOf("http","https") || (from.scheme=="https" && to.scheme!="https")) throw UpstreamUnavailable()
        return to
    }
    private suspend fun fetch(path:String):UpstreamResponse {
        val configured=URI(settings.coverArtArchiveUrl)
        var uri=URI(settings.coverArtArchiveUrl.trimEnd('/')+path)
        val seen=HashSet<URI>()
        repeat(6) { hop ->
            if(!seen.add(uri)) throw UpstreamUnavailable()
            val target=uri
            val addresses=withContext(Dispatchers.IO) {
                try { CompletableFuture.supplyAsync { InetAddress.getAllByName(target.host) }.get(5,TimeUnit.SECONDS) }
                catch(_:Exception) { throw UpstreamUnavailable() }
            }
            if(addresses.isEmpty() || (!(settings.coverArtArchiveUrl.trimEnd('/')!="https://coverartarchive.org" && sameOrigin(configured,uri)) && addresses.any { !publicAddress(it) })) throw UpstreamUnavailable()
            val response=http.request(uri,MAX_BYTES,addresses.first())
            if(response.status !in listOf(301,302,303,307,308)) return response
            if(hop==5) throw UpstreamUnavailable()
            uri=validateRedirect(uri,response.headers["location"]?.singleOrNull() ?: throw UpstreamUnavailable())
        }
        throw UpstreamUnavailable()
    }
    suspend fun thumbnail(groupMbid:String):DiscoverImage {
        val path="/release-group/${canonicalMbid(groupMbid)}/front-250"
        try {
            val cached=cache.read(CacheKey(settings.coverArtArchiveUrl,path,emptyMap()),30*DiscoverCache.DAY,{ bytes ->
                if(bytes.size>MAX_BYTES)throw UpstreamUnavailable(); imageType(bytes);bytes
            }) { if(!settings.enabled)throw UpstreamUnavailable();fetch(path) }
            return DiscoverImage(cached.value,imageType(cached.value),cached.age,false)
        } catch(_:UpstreamNotFound) { return placeholderImage() }
          catch(e:UpstreamUnavailable) { if(!settings.enabled)return placeholderImage();throw e }
    }
    private fun placeholderImage()=DiscoverImage(placeholder,"image/svg+xml",CacheAge(System.currentTimeMillis(),0,false),true)
}

/** Durable opaque ids; devices cannot turn the artwork endpoint into an arbitrary URL proxy. */
class DiscoverIdentity(private val db:com.legitimateapps.dulcet.server.db.Db) {
    init { db.write { execute("CREATE TABLE IF NOT EXISTS discover_identity(kind TEXT NOT NULL,mbid TEXT NOT NULL,id TEXT NOT NULL UNIQUE,artwork_id TEXT UNIQUE,PRIMARY KEY(kind,mbid))") } }
    fun register(item:DiscoverItem):DiscoverItem {
        val mbid=item.externalIds[item.kind.registryKey] ?: return item.copy(wishable=false)
        return db.write {
            execute("INSERT OR IGNORE INTO discover_identity(kind,mbid,id,artwork_id) VALUES(?,?,?,?)",item.kind.wire,canonicalMbid(mbid),"di_"+java.util.UUID.randomUUID(),if(item.kind==DiscoverKind.Album) "da_"+java.util.UUID.randomUUID() else null)
            single("SELECT id,artwork_id FROM discover_identity WHERE kind=? AND mbid=?",item.kind.wire,canonicalMbid(mbid)) { item.copy(id=it.string("id"),artworkId=item.artworkId ?: it.stringOrNull("artwork_id")) }!!
        }
    }
    fun item(id:String):Pair<DiscoverKind,String>?=db.read { single("SELECT kind,mbid FROM discover_identity WHERE id=?",id) { DiscoverKind.parse(it.string("kind")) to it.string("mbid") } }
    fun artwork(id:String):String?=db.read { string("SELECT mbid FROM discover_identity WHERE artwork_id=? AND kind='album'",id) }
}
