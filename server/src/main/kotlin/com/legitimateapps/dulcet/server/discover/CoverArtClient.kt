package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.v1.WebhookHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class DiscoverImage(val bytes:ByteArray,val contentType:String,val age:CacheAge,val placeholder:Boolean)
class CoverArtClient(private val http:PoliteHttp,private val cache:DiscoverCache,private val settings:DiscoverSettings,
    private val testingOverride:()->Boolean={false},
    private val resolver:(String)->Array<InetAddress> = InetAddress::getAllByName,
) {
    companion object {
        const val MAX_BYTES=5*1024*1024
        fun imageType(bytes:ByteArray):String {
            fun starts(vararg prefix:Int)=bytes.size>=prefix.size && prefix.indices.all { bytes[it].toInt() and 255==prefix[it] }
            return when {
                starts(255,216,255) && bytes.size>=5 && bytes[bytes.size-2]==255.toByte() && bytes.last()==217.toByte()->"image/jpeg"
                starts(137,80,78,71,13,10,26,10) && wholePng(bytes)->"image/png"
                bytes.size>=14 && (asciiAt(bytes,0,"GIF87a") || asciiAt(bytes,0,"GIF89a")) && bytes.last()==59.toByte()->"image/gif"
                bytes.size>=20 && asciiAt(bytes,0,"RIFF") && asciiAt(bytes,8,"WEBP") && wholeWebp(bytes)->"image/webp"
                else->throw UpstreamUnavailable()
            }
        }
        private fun asciiAt(bytes:ByteArray,offset:Int,text:String)=
            offset>=0 && offset<=bytes.size-text.length && text.indices.all { bytes[offset+it].toInt()==text[it].code }
        private fun uint32(bytes:ByteArray,offset:Int,littleEndian:Boolean=false):Long {
            var value=0L
            for(i in 0..3) value=(value shl 8) or (bytes[offset+if(littleEndian)3-i else i].toLong() and 255)
            return value
        }
        // Walk bounded chunk framing, including CRC space, without decompressing image data.
        private fun wholePng(bytes:ByteArray):Boolean {
            var offset=8
            var imageData=false
            while(offset<=bytes.size-12) {
                val length=uint32(bytes,offset)
                if(length>bytes.size-offset-12L) return false
                val end=offset+12+length.toInt()
                if(offset==8 && (!asciiAt(bytes,offset+4,"IHDR") || length!=13L)) return false
                if(offset!=8 && asciiAt(bytes,offset+4,"IHDR")) return false
                if(asciiAt(bytes,offset+4,"IDAT") && length>0) imageData=true
                if(asciiAt(bytes,offset+4,"IEND")) return length==0L && end==bytes.size && imageData
                offset=end
            }
            return false
        }
        private fun wholeWebp(bytes:ByteArray):Boolean {
            if(uint32(bytes,4,true)!=bytes.size-8L) return false
            var offset=12
            var imageData=false
            while(offset<=bytes.size-8) {
                val length=uint32(bytes,offset+4,true)
                val paddedLength=length+(length and 1L)
                if(paddedLength>bytes.size-offset-8L) return false
                val end=offset+8+paddedLength.toInt()
                if(length and 1L!=0L && bytes[end-1]!=0.toByte()) return false
                if(length>0 && (asciiAt(bytes,offset,"VP8 ") || asciiAt(bytes,offset,"VP8L") || asciiAt(bytes,offset,"ANMF"))) imageData=true
                offset=end
            }
            return offset==bytes.size && imageData
        }
        fun publicAddress(address:InetAddress)=WebhookHttpTransport().allowed(address)
        private val placeholder="""<svg xmlns="http://www.w3.org/2000/svg" width="250" height="250" viewBox="0 0 250 250"><rect width="250" height="250" fill="#303036"/><circle cx="125" cy="125" r="64" fill="#62626a"/><circle cx="125" cy="125" r="14" fill="#303036"/></svg>""".toByteArray()
    }
    fun validateRedirect(from:URI,location:String):URI {
        val to=runCatching { from.resolve(location) }.getOrNull() ?: throw UpstreamUnavailable()
        if(to.toString().length>8192 || to.host==null || to.userInfo!=null || to.fragment!=null || to.port==0 || to.port>65535 || to.scheme.lowercase() !in listOf("http","https") || (from.scheme.equals("https",true) && !to.scheme.equals("https",true))) throw UpstreamUnavailable()
        return to
    }
    private suspend fun fetch(path:String):UpstreamResponse {
        var uri=URI(settings.coverArtArchiveUrl.trimEnd('/')+path)
        val seen=HashSet<URI>()
        repeat(6) { hop ->
            if(!seen.add(uri)) throw UpstreamUnavailable()
            val target=uri
            val addresses=withContext(Dispatchers.IO) {
                try { CompletableFuture.supplyAsync { resolver(target.host) }.get(5,TimeUnit.SECONDS) }
                catch(e:kotlinx.coroutines.CancellationException) { throw e }
                catch(e:Exception) {
                    val cause=generateSequence(e as Throwable) { it.cause }.take(8).last()
                    throw UpstreamUnavailable(reason=if(cause is java.util.concurrent.TimeoutException)UpstreamFailure.DnsTimeout else UpstreamFailure.DnsTransport)
                }
            }
            // Every DNS answer is checked before selecting the pinned socket address, including
            // the configured origin. Only explicit fixture injection can admit loopback.
            if(addresses.isEmpty() || addresses.any { !publicAddress(it) && !(testingOverride() && it.isLoopbackAddress) }) throw UpstreamUnavailable()
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
