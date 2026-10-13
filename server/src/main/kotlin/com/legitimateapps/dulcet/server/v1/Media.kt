package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.PasswordVault
import com.legitimateapps.dulcet.server.media.Artwork
import com.legitimateapps.dulcet.server.musicFile
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

class ApiMedia(private val ctx:ServerContext) {
    private val key=PasswordVault.keyFromDataFolder(ctx.config.dataFolder)
    private val artRoot=ctx.config.dataFolder.resolve("cache/api-artwork")
    init {
        Files.createDirectories(artRoot)
        ctx.db.write {
            execute("CREATE TABLE IF NOT EXISTS api_artwork(id TEXT PRIMARY KEY,mime TEXT NOT NULL)")
            execute("CREATE TABLE IF NOT EXISTS api_artwork_grant(parent TEXT NOT NULL,user_id TEXT NOT NULL,id TEXT NOT NULL,size INTEGER NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(parent,user_id,id,size))")
        }
    }
    fun artworkRef(id:String,p:Principal):JsonObject? {
        val image=ctx.artwork.image(id,null,p.user) ?: return null
        if(!image.contentType.startsWith("image/")) return null
        val artId="img_${hash(image.bytes)}"
        val path=artRoot.resolve(artId)
        if(!Files.exists(path)) {
            val tmp=Files.createTempFile(artRoot,"art-",".tmp")
            try { Files.write(tmp,image.bytes);Files.move(tmp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE) } finally { Files.deleteIfExists(tmp) }
            ctx.db.write { execute("INSERT OR IGNORE INTO api_artwork VALUES(?,?)",artId,image.contentType) }
        }
        return j("id" to artId)
    }
    private fun sign(bytes:ByteArray)=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256"));doFinal(bytes) }
    private fun grant(p:Principal,kind:String,id:String,size:Int=0):Pair<String,Long> {
        val now=System.currentTimeMillis()
        val maximum=ctx.db.read { single("WITH RECURSIVE parents(id,parent_id,expires) AS (SELECT id,parent_id,expires FROM api_grant WHERE id=? UNION ALL SELECT g.id,g.parent_id,g.expires FROM api_grant g JOIN parents p ON g.id=p.parent_id) SELECT MIN(expires) AS deadline FROM parents",p.tokenId) { it.longOrNull("deadline") } } ?: Long.MAX_VALUE
        val expires=if(kind=="artwork") ctx.db.write {
            val saved=long("SELECT expires FROM api_artwork_grant WHERE parent=? AND user_id=? AND id=? AND size=?",p.tokenId,p.user.id,id,size)
            if(saved>now) saved else minOf(now+ctx.testing.artworkTtlSeconds*1000,maximum).also { execute("INSERT OR REPLACE INTO api_artwork_grant VALUES(?,?,?,?,?)",p.tokenId,p.user.id,id,size,it) }
        } else minOf(now+ctx.testing.mediaTtlSeconds*1000,maximum)
        val payload=j("instance" to ctx.journal.instanceId,"parent" to p.tokenId,"user" to p.user.id,"kind" to kind,"id" to id,"size" to size,"expires" to expires)
        val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        return "$encoded.${Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded.toByteArray()))}" to expires
    }
    private fun decode(grant:String):JsonObject {
        try {
            val parts=grant.split('.')
            if(parts.size!=2 || !MessageDigest.isEqual(sign(parts[0].toByteArray()),Base64.getUrlDecoder().decode(parts[1]))) throw ApiProblem(403,"media-grant-expired")
            val value=Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[0]).decodeToString()).jsonObject
            if(value["instance"]!!.jsonPrimitive.content!=ctx.journal.instanceId || value["expires"]!!.jsonPrimitive.long<=System.currentTimeMillis() || !ctx.apiAuth.isActive(value["parent"]!!.jsonPrimitive.content)) throw ApiProblem(403,"media-grant-expired")
            return value
        } catch(e:ApiProblem) {throw e} catch(_:Exception) {throw ApiProblem(403,"media-grant-expired")}
    }
    private fun source(id:String,userId:String):Triple<Path,String,JsonObject> = ctx.db.read {
        if("1" !in ctx.apiUsers.sourceIds(userId)) throw ApiProblem(404,"not-found")
        single("SELECT * FROM track WHERE id=?",id) { row -> Triple(row.string("path"),row.string("content_type"),j("codec" to row.string("codec"),"container" to container(row.string("suffix")),"bitrate" to row.long("bitrate")*1000,"size" to row.long("size"),"duration" to (row.double("duration")*1000).toLong(),"sampleRate" to row.intOrNull("sample_rate"),"bitDepth" to row.intOrNull("bit_depth"),"channels" to row.intOrNull("channels"))) }
    }?.let { Triple(musicFile(ctx.config.musicFolder,ctx.config.musicFolder.resolve(it.first)) ?: throw ApiProblem(404,"not-found"),it.second,it.third) } ?: throw ApiProblem(404,"not-found")
    fun install(route:Route) {
        route.get("/artwork/{artworkId}") {
            val p=call.principal(ctx,"catalog:read")
            if("1" !in ctx.apiUsers.sourceIds(p.user.id)) throw ApiProblem(404,"not-found")
            val id=call.parameters["artworkId"]!!
            val size=call.request.queryParameters["size"]?.let { it.toIntOrNull() ?: throw ApiProblem(400,"invalid-request","Invalid artwork size") } ?: 0
            if(size!=0 && size !in 16..3000) throw ApiProblem(400,"invalid-request","Invalid artwork size")
            serveArt(call,id,size)
        }
        route.post("/media/urls") {
            val p=call.principal(ctx);val requests=call.body()["requests"] as? JsonArray ?: throw ApiProblem(400,"invalid-request")
            if(requests.size !in 1..500) throw ApiProblem(400,"invalid-request")
            val urls=requests.map { request ->
                val obj=request.jsonObject;val kind=obj["kind"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
                val id=obj["id"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
                val scope=when(kind) {"original"->"media:read";"artwork"->"catalog:read";else->throw ApiProblem(400,"invalid-request")}
                if(scope !in p.scopes) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to listOf(scope)))
                val size=obj["size"]?.jsonPrimitive?.intOrNull ?: 0
                if(size!=0 && size !in 16..3000) throw ApiProblem(400,"invalid-request")
                if("1" !in ctx.apiUsers.sourceIds(p.user.id)) throw ApiProblem(404,"not-found")
                if(kind=="original") { if(!ctx.apiUsers.download(p.user.id)) throw ApiProblem(403,"permission-denied");source(id,p.user.id) } else if(ctx.db.read { long("SELECT COUNT(*) FROM api_artwork WHERE id=?",id) }==0L) throw ApiProblem(404,"not-found")
                val (token,expires)=grant(p,kind,id,size)
                j("kind" to kind,"id" to id,"url" to "${call.origin()}/dulcet/v1/media/$token","expiresAt" to iso(expires))
            }
            call.response.headers.append(HttpHeaders.CacheControl,"no-store");call.send(j("urls" to urls))
        }
        route.post("/playback/decisions") {
            val p=call.principal(ctx,"media:read");val body=call.body()
            if(body["purpose"]?.jsonPrimitive?.contentOrNull=="download" && !ctx.apiUsers.download(p.user.id)) throw ApiProblem(403,"permission-denied")
            val id=body["trackId"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
            val profile=body["profile"] as? JsonObject ?: throw ApiProblem(400,"invalid-request")
            val start=body["startMs"]?.let { it.jsonPrimitive.longOrNull ?: throw ApiProblem(400,"invalid-request") } ?: 0
            if(start<0) throw ApiProblem(400,"invalid-request")
            val direct=profile["directPlay"] as? JsonArray ?: throw ApiProblem(400,"invalid-request")
            val source=source(id,p.user.id);val audio=source.third
            val compatible=direct.any { raw -> val fmt=raw.jsonObject;fmt["container"]?.jsonPrimitive?.content==audio["container"]!!.jsonPrimitive.content && fmt["codec"]?.jsonPrimitive?.content==audio["codec"]!!.jsonPrimitive.content && listOf("maxSampleRate" to "sampleRate","maxBitDepth" to "bitDepth","maxChannels" to "channels").all { (max,source) -> val cap=fmt[max]?.jsonPrimitive?.intOrNull;val value=audio[source]?.jsonPrimitive?.intOrNull;cap==null || value==null || value<=cap } }
            val cap=profile["maxBitrate"]?.jsonPrimitive?.longOrNull
            if(!compatible || (cap!=null && audio["bitrate"]!!.jsonPrimitive.long>cap) || start>0) throw ApiProblem(422,"no-compatible-format","No compatible direct-play format; transcoding is not enabled for this API profile")
            val (token,expires)=grant(p,"original",id)
            call.response.headers.append(HttpHeaders.CacheControl,"no-store")
            call.send(j("id" to UUID.randomUUID().toString(),"trackId" to id,"mode" to "direct","url" to "${call.origin()}/dulcet/v1/media/$token","expiresAt" to iso(expires),"mimeType" to source.second,"container" to audio["container"],"codec" to audio["codec"],"bitrate" to audio["bitrate"],"offsetMs" to 0,"durationMs" to audio["duration"],"streamDurationMs" to audio["duration"],"contentLength" to Files.size(source.first),"lengthExact" to true,"byteRanges" to true))
        }
        route.get("/media/{grant}") { serveGrant(call) }
        route.head("/media/{grant}") { serveGrant(call) }
    }
    private suspend fun serveGrant(call:ApplicationCall) {
        val value=decode(call.parameters["grant"]!!)
        if("1" !in ctx.apiUsers.sourceIds(value["user"]!!.jsonPrimitive.content)) throw ApiProblem(403,"media-grant-expired")
        if(value["kind"]!!.jsonPrimitive.content=="artwork") serveArt(call,value["id"]!!.jsonPrimitive.content,value["size"]!!.jsonPrimitive.int)
        else { val source=source(value["id"]!!.jsonPrimitive.content,value["user"]!!.jsonPrimitive.content);serveFile(call,source.first,ContentType.parse(source.second)) }
    }
    private suspend fun serveArt(call:ApplicationCall,id:String,size:Int) {
        val mime=ctx.db.read { string("SELECT mime FROM api_artwork WHERE id=?",id) } ?: throw ApiProblem(404,"not-found")
        val source=artRoot.resolve(id)
        if(!Files.isRegularFile(source)) throw ApiProblem(404,"not-found")
        var bytes=Files.readAllBytes(source)
        var type=mime
        if(size>0) {
            val original=ImageIO.read(ByteArrayInputStream(bytes)) ?: throw ApiProblem(500,"server-error")
            if(original.width>size||original.height>size) { bytes=Artwork.encodeJpeg(Artwork.scale(original,size));type="image/jpeg" }
        }
        val tag="\"${ctx.journal.instanceId}.${hash(bytes)}\""
        call.response.headers.append(HttpHeaders.ETag,tag);call.response.headers.append(HttpHeaders.Vary,"Authorization")
        if(call.request.headers[HttpHeaders.IfNoneMatch]==tag) {
            call.response.headers.append(HttpHeaders.CacheControl,"private, max-age=31536000, immutable")
            call.respond(HttpStatusCode.NotModified)
        } else {
            val variant=if(size==0) source else artRoot.resolve("${id}_${size}_${hash(bytes)}")
            if(!Files.exists(variant)) Files.write(variant,bytes)
            serveFile(call,variant,ContentType.parse(type),"private, max-age=31536000, immutable")
        }
    }
}
private suspend fun serveFile(call:ApplicationCall,path:Path,type:ContentType,cache:String="private, no-store") {
    val length=Files.size(path)
    val range=call.request.headers[HttpHeaders.Range]?.let { com.legitimateapps.dulcet.server.api.parseRange(it,length) }
    call.response.headers.append(HttpHeaders.AcceptRanges,"bytes")
    call.response.headers.append(HttpHeaders.CacheControl,cache)
    if(range!=null && (range.first<0 || range.first>=length || range.last<range.first)) {
        call.response.headers.append(HttpHeaders.ContentRange,"bytes */$length")
        throw ApiProblem(416,"range-not-satisfiable","Unsatisfiable byte range")
    }
    val start=range?.first ?: 0
    val count=range?.let { it.last-it.first+1 } ?: length
    if(range!=null) call.response.headers.append(HttpHeaders.ContentRange,"bytes ${range.first}-${range.last}/$length")
    if(call.request.httpMethod==HttpMethod.Head) {
        call.respond(object:OutgoingContent.NoContent() {
            override val contentType=type
            override val contentLength=count
            override val status=if(range!=null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        })
        return
    }
    call.respond(object:OutgoingContent.WriteChannelContent() {
        override val contentType=type
        override val contentLength=count
        override val status=if(range!=null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        override suspend fun writeTo(channel:ByteWriteChannel) = withContext(Dispatchers.IO) {
            RandomAccessFile(path.toFile(),"r").use { file ->
                file.seek(start);var left=count;val buffer=ByteArray(65536)
                while(left>0) {val n=file.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());if(n<0)break;channel.writeFully(buffer,0,n);left-=n}
            }
        }
    })
}
