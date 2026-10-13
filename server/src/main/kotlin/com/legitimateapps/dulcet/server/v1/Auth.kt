package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.AuthOutcome
import com.legitimateapps.dulcet.server.auth.PasswordVault
import com.legitimateapps.dulcet.server.auth.SlowHash
import com.legitimateapps.dulcet.server.auth.User
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** tokenId is the stable OAuth grant or personal token id, including after access-token refresh. */
data class Principal(val user: User, val tokenId: String, val parentId: String?, val scopes: Set<String>, val credentialId: String? = null)
fun requireScopes(principal: Principal, vararg scopes: String) {
    if (!principal.scopes.containsAll(scopes.toList())) throw ApiProblem(403, "insufficient-scope", "The token lacks a required scope.", j("requiredScopes" to scopes.toList()))
}
internal class OAuthFailure(val error: String) : RuntimeException(error)

class ApiAuth(private val ctx: ServerContext, private val clock: () -> Long = System::currentTimeMillis) {
    private val db = ctx.db
    private val random = SecureRandom()
    private val vault = PasswordVault.fromDataFolder(ctx.config.dataFolder)
    private val identities = ClientIdentities { ctx.testing.trustedClient(it) }
    private val cursorCodec by lazy { CursorCodec(ctx) }
    private val rate = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val verifiedBearers = ConcurrentHashMap<String, Pair<String, Long>>()
    init {
        db.write {
            execute("""CREATE TABLE IF NOT EXISTS api_grant(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,kind TEXT NOT NULL,name TEXT NOT NULL,client_id TEXT,verified INTEGER NOT NULL,scopes TEXT NOT NULL,parent_id TEXT REFERENCES api_grant(id),created INTEGER NOT NULL,last_used INTEGER,expires INTEGER,revoked INTEGER NOT NULL DEFAULT 0,password_stamp TEXT NOT NULL,auth_time INTEGER NOT NULL,hint TEXT NOT NULL DEFAULT '')""")
            execute("""CREATE TABLE IF NOT EXISTS api_credential(id TEXT PRIMARY KEY,grant_id TEXT NOT NULL REFERENCES api_grant(id) ON DELETE CASCADE,kind TEXT NOT NULL,secret_hash TEXT NOT NULL,expires INTEGER NOT NULL,used_at INTEGER,retry_pair BLOB)""")
            execute("""CREATE TABLE IF NOT EXISTS api_authorization(id TEXT PRIMARY KEY,secret_hash TEXT,client_id TEXT NOT NULL,client_name TEXT NOT NULL,verified INTEGER NOT NULL,redirect_uri TEXT,state TEXT,challenge TEXT,scopes TEXT NOT NULL,user_id TEXT REFERENCES user(id) ON DELETE CASCADE,expires INTEGER NOT NULL,browser_hash TEXT,user_code TEXT UNIQUE,attempts INTEGER NOT NULL DEFAULT 0,poll_at INTEGER NOT NULL DEFAULT 0,interval_sec INTEGER NOT NULL DEFAULT 5,used INTEGER NOT NULL DEFAULT 0)""")
            execute("""CREATE TABLE IF NOT EXISTS api_profile(user_id TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,display_name TEXT,settings TEXT NOT NULL DEFAULT '{}')""")
            for((action,ref,op) in listOf(Triple("INSERT","NEW","upsert"),Triple("UPDATE","NEW","upsert"),Triple("DELETE","OLD","delete"))) {
                execute("CREATE TRIGGER IF NOT EXISTS api_profile_${action.lowercase()} AFTER $action ON api_profile BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('user',$ref.user_id,$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
            }
        }
        maintenance()
    }
    /** Called by server maintenance; also run on all authentication traffic and startup. */
    fun maintenance() { db.write {
        execute("UPDATE api_credential SET retry_pair=NULL WHERE used_at IS NOT NULL AND used_at<=? AND retry_pair IS NOT NULL",clock()-60_000)
        execute("DELETE FROM api_authorization WHERE expires<=?",clock())
    } }
    private fun id() = UUID.randomUUID().toString()
    private fun entropy(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun sha(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun ts(value: Long?) = value?.let(::iso)
    private fun scopes(value: String) = value.split(' ').filter { it.isNotBlank() }.toSet()
    private fun parseScopes(value: String): Set<String> = scopes(value).also { if (it.isEmpty() || !SCOPES.containsAll(it)) throw OAuthFailure("invalid_scope") }
    private fun clientScopes(client:ClientIdentity,value:String):Set<String> = parseScopes(value).let { requested ->
        (if(client.verified) requested else requested-setOf("account","admin")).also { if(it.isEmpty()) throw OAuthFailure("invalid_scope") }
    }
    private fun allowed(user: User, requested: Set<String>) = requested.all { it != "admin" || user.isAdmin }
    private fun grant(session: Db.Session, id: String): Grant? = session.single("SELECT * FROM api_grant WHERE id=?", id) {
        Grant(it.string("id"),it.string("user_id"),it.string("kind"),it.string("name"),it.stringOrNull("client_id"),it.bool("verified"),scopes(it.string("scopes")),it.stringOrNull("parent_id"),it.long("created"),it.longOrNull("last_used"),it.longOrNull("expires"),it.bool("revoked"),it.string("password_stamp"),it.long("auth_time"),it.string("hint"))
    }
    private data class Grant(val id:String,val userId:String,val kind:String,val name:String,val clientId:String?,val verified:Boolean,val scopes:Set<String>,val parent:String?,val created:Long,val lastUsed:Long?,val expires:Long?,val revoked:Boolean,val stamp:String,val authTime:Long,val hint:String)
    private fun active(s: Db.Session, g: Grant, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (!seen.add(g.id) || g.revoked || g.expires?.let { it <= clock() } == true) return false
        val user = ctx.users.byId(g.userId) ?: return false
        if (!allowed(user,g.scopes) || s.string("SELECT password_hash FROM user WHERE id=?",g.userId) != g.stamp) return false
        if (g.kind == "oauth" && "offline_access" in g.scopes && (g.lastUsed ?: g.created) + REFRESH_TTL <= clock()) return false
        return g.parent?.let { parent -> grant(s,parent)?.let { it.scopes.containsAll(g.scopes) && active(s,it,seen) } ?: false } ?: true
    }
    fun isActive(tokenId: String): Boolean = db.read { grant(this,tokenId)?.let { active(this,it) } ?: false }
    /** An open stream must obey the presented credential's expiry as well as grant revocation. */
    fun isActive(p: Principal): Boolean = db.read {
        val g=grant(this,p.tokenId) ?: return@read false
        active(this,g) && (p.credentialId==null || long("SELECT COUNT(*) FROM api_credential WHERE id=? AND grant_id=? AND kind IN ('access','personal') AND expires>?",p.credentialId,p.tokenId,clock())==1L)
    }
    fun authenticate(header: String?): Principal? {
        if (header == null || !header.startsWith("Bearer ", ignoreCase=true)) return null
        val secret = header.substring(7)
        val selector = secret.substringBefore('.').substringAfterLast('_')
        if (!secret.matches(Regex("dulcet_(?:at|pat)_[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}"))) return null
        return db.write {
            execute("UPDATE api_credential SET retry_pair=NULL WHERE used_at IS NOT NULL AND used_at < ? AND retry_pair IS NOT NULL",clock()-60_000)
            val credential = single("SELECT * FROM api_credential WHERE id=? AND kind IN ('access','personal')", selector) { Triple(it.string("grant_id"),it.string("secret_hash"),it.long("expires")) } ?: return@write null
            if (credential.third <= clock()) return@write null
            val fingerprint=sha(secret)
            val cached=verifiedBearers[selector]
            val valid=cached?.let { it.second>clock() && MessageDigest.isEqual(it.first.toByteArray(),fingerprint.toByteArray()) } == true || SlowHash.verify(secret,credential.second)
            if(!valid) return@write null
            if(verifiedBearers.size>=1024) verifiedBearers.entries.removeIf { it.value.second<=clock() }
            if(verifiedBearers.size<1024) verifiedBearers[selector]=fingerprint to clock()+30_000
            val g = grant(this,credential.first) ?: return@write null
            if (!active(this,g)) return@write null
            execute("UPDATE api_grant SET last_used=? WHERE id=?",clock(),g.id)
            Principal(ctx.users.byId(g.userId)!!,g.id,g.parent,g.scopes,selector)
        }
    }
    /** Test restores retain only the calling grant family, never unrelated post-checkpoint tokens. */
    fun withCarriedGrant(p: Principal, restore: () -> Unit) {
        if(!ctx.config.testMode) throw ApiProblem(404,"not-found")
        requireScopes(p,"admin")
        if(!p.user.isAdmin) throw ApiProblem(403,"permission-denied")
        db.exclusive {
            val snapshot=db.read {
            val ancestors=mutableListOf<Grant>()
            var next:String?=p.tokenId
            while(next!=null) {
                if(ancestors.any { it.id==next }) throw ApiProblem(500,"server-error")
                val g=grant(this,next) ?: throw ApiProblem(401,"unauthenticated")
                if(g.userId!=p.user.id || !active(this,g)) throw ApiProblem(401,"unauthenticated")
                ancestors+=g;next=g.parent
            }
            val userRow=single("SELECT * FROM user WHERE id=?",p.user.id) {
                listOf(it.string("id"),it.string("name"),it.bytes("password_enc"),it.bool("is_admin"),it.long("created"),it.string("password_hash"))
            } ?: throw ApiProblem(401,"unauthenticated")
            val credentials=ancestors.flatMap { g -> query("SELECT * FROM api_credential WHERE grant_id=?",g.id) {
                listOf(it.string("id"),it.string("grant_id"),it.string("kind"),it.string("secret_hash"),it.long("expires"),it.longOrNull("used_at"),it.bytes("retry_pair"))
            } }
            Triple(ancestors,userRow,credentials)
            }
            val (ancestors,userRow,credentials)=snapshot
            try {
                restore()
                db.write {
                    if(long("SELECT COUNT(*) FROM user WHERE id=?",p.user.id)==0L) {
                        val carried=userRow.toMutableList()
                        if(long("SELECT COUNT(*) FROM user WHERE name=?",carried[1])!=0L) {
                            // Preserve checkpoint users and the caller's stable identity if its old name was reused.
                            val base="test-admin-${p.user.id}";var name=base;var suffix=0
                            while(long("SELECT COUNT(*) FROM user WHERE name=?",name)!=0L) name="$base-${++suffix}"
                            carried[1]=name
                        }
                        execute("INSERT INTO user(id,name,password_enc,is_admin,created,password_hash) VALUES(?,?,?,?,?,?)",*carried.toTypedArray())
                    }
                    // The hook explicitly preserves the calling test administrator's ability to continue.
                    execute("UPDATE user SET is_admin=1 WHERE id=?",p.user.id)
                    val stamp=string("SELECT password_hash FROM user WHERE id=?",p.user.id)!!
                    for(g in ancestors.asReversed()) execute("""INSERT INTO api_grant(id,user_id,kind,name,client_id,verified,scopes,parent_id,created,last_used,expires,revoked,password_stamp,auth_time,hint) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,kind=excluded.kind,name=excluded.name,client_id=excluded.client_id,verified=excluded.verified,scopes=excluded.scopes,parent_id=excluded.parent_id,created=excluded.created,last_used=excluded.last_used,expires=excluded.expires,revoked=excluded.revoked,password_stamp=excluded.password_stamp,auth_time=excluded.auth_time,hint=excluded.hint""",g.id,g.userId,g.kind,g.name,g.clientId,g.verified,g.scopes.sorted().joinToString(" "),g.parent,g.created,g.lastUsed,g.expires,g.revoked,stamp,g.authTime,g.hint)
                    for(row in credentials) execute("""INSERT INTO api_credential(id,grant_id,kind,secret_hash,expires,used_at,retry_pair) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET grant_id=excluded.grant_id,kind=excluded.kind,secret_hash=excluded.secret_hash,expires=excluded.expires,used_at=excluded.used_at,retry_pair=excluded.retry_pair""",*row.toTypedArray())
                }
            } finally { verifiedBearers.clear() }
        }
    }
    fun bootstrapPersonal(user: User, scopes: Set<String> = SCOPES): String {
        require(SCOPES.containsAll(scopes) && allowed(user,scopes))
        return db.write { createPersonal(this,user,scopes,"Local administrator token",clock()+365L*DAY,null).second }
    }
    private fun createGrant(s:Db.Session,user:User,scope:Set<String>,kind:String,name:String,clientId:String?,verified:Boolean,parent:String?,expires:Long?):String {
        val id = id()
        s.execute("INSERT INTO api_grant(id,user_id,kind,name,client_id,verified,scopes,parent_id,created,expires,password_stamp,auth_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,user.id,kind,name,clientId,verified,scope.sorted().joinToString(" "),parent,clock(),expires,s.string("SELECT password_hash FROM user WHERE id=?",user.id)!!,clock())
        return id
    }
    private fun credential(s:Db.Session,gid:String,kind:String,prefix:String,expires:Long):String {
        val cid=id(); val secret="dulcet_${prefix}_${cid}.${entropy()}"
        s.execute("INSERT INTO api_credential(id,grant_id,kind,secret_hash,expires) VALUES(?,?,?,?,?)",cid,gid,kind,SlowHash.hash(secret),expires)
        s.execute("UPDATE api_grant SET hint=? WHERE id=?",secret.take(14),gid)
        return secret
    }
    private fun createPersonal(s:Db.Session,user:User,scope:Set<String>,name:String,expires:Long,parent:String?):Pair<String,String> {
        val gid=createGrant(s,user,scope,"personal",name,null,true,parent,expires)
        if(parent != null) s.execute("UPDATE api_grant SET auth_time=(SELECT auth_time FROM api_grant WHERE id=?) WHERE id=?",parent,gid)
        return gid to credential(s,gid,"personal","pat",expires)
    }
    private fun pair(s:Db.Session,g:Grant,requested:Set<String> = g.scopes):JsonObject {
        val access=credential(s,g.id,"access","at",clock()+3_600_000)
        val refresh=if ("offline_access" in requested) credential(s,g.id,"refresh","rt",clock()+REFRESH_TTL) else null
        s.execute("UPDATE api_grant SET scopes=?,last_used=? WHERE id=?",requested.sorted().joinToString(" "),clock(),g.id)
        val fields= mutableListOf<Pair<String,Any?>>("access_token" to access,"token_type" to "Bearer","expires_in" to 3600,"scope" to requested.sorted().joinToString(" "))
        if(refresh!=null) fields += "refresh_token" to refresh
        return j(*fields.toTypedArray())
    }
    private fun revoke(s:Db.Session,gid:String) {
        s.execute("WITH RECURSIVE descendants(id) AS (SELECT id FROM api_grant WHERE id=? UNION ALL SELECT g.id FROM api_grant g JOIN descendants d ON g.parent_id=d.id) UPDATE api_grant SET revoked=1 WHERE id IN (SELECT id FROM descendants)",gid)
        s.execute("UPDATE api_credential SET retry_pair=NULL WHERE grant_id IN (SELECT id FROM api_grant WHERE revoked=1)")
    }
    private fun principal(call:ApplicationCall):Principal=authenticate(call.request.headers[HttpHeaders.Authorization]) ?: throw ApiProblem(401,"unauthenticated")
    private fun tokenJson(g:Grant):JsonObject=j("id" to g.id,"kind" to g.kind,"name" to g.name,"clientName" to g.name,"scopes" to g.scopes.sorted(),"createdAt" to ts(g.created),"lastUsedAt" to ts(g.lastUsed),"expiresAt" to ts(g.expires),"hint" to g.hint,"parentId" to g.parent)
    private fun me(p:Principal):JsonObject=db.read {
        val g=grant(this,p.tokenId)!!
        val profile=single("SELECT display_name,settings FROM api_profile WHERE user_id=?",p.user.id) { it.stringOrNull("display_name") to Json.parseToJsonElement(it.string("settings")) }
        j("id" to p.user.id,"username" to p.user.name,"displayName" to (profile?.first ?: p.user.name),"roles" to if(p.user.isAdmin) listOf("admin") else emptyList<String>(),"permissions" to ctx.apiUsers.permissions(p.user.id),"sourceIds" to ctx.apiUsers.sourceIds(p.user.id),"settings" to (profile?.second ?: j("shareNowPlaying" to false)),"createdAt" to ts(long("SELECT created FROM user WHERE id=?",p.user.id)),"token" to j("id" to g.id,"kind" to g.kind,"scopes" to g.scopes.sorted(),"clientName" to g.name,"verified" to g.verified))
    }
    fun install(route:Route) { with(route) {
        get("/me") { call.send(me(principal(call))) }
        patch("/me") {
            val p=principal(call);requireScopes(p,"account");val b=call.body()
            val name=b["displayName"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            if(name!=null && (name.isBlank() || name.length>100)) throw ApiProblem(422,"invalid-request")
            val settings=b["settings"]?.takeUnless { it is JsonNull }?.let { it as? JsonObject ?: throw ApiProblem(400,"invalid-request") }
            if(settings?.get("shareNowPlaying")?.let { it !is JsonPrimitive || it.booleanOrNull == null } == true) throw ApiProblem(422,"invalid-request")
            if(settings?.get("shareNowPlaying")?.jsonPrimitive?.booleanOrNull==true && !ctx.apiUsers.share(p.user.id)) throw ApiProblem(403,"permission-denied","Sharing is disabled for this user.")
            db.write {
                execute("INSERT OR IGNORE INTO api_profile(user_id) VALUES(?)",p.user.id)
                if(b.containsKey("displayName")) execute("UPDATE api_profile SET display_name=? WHERE user_id=?",name,p.user.id)
                if(b.containsKey("settings")) {
                    val old=Json.parseToJsonElement(string("SELECT settings FROM api_profile WHERE user_id=?",p.user.id)!!).jsonObject.toMutableMap()
                    settings?.forEach { (key,value) -> if(value is JsonNull) old.remove(key) else old[key]=value }
                    execute("UPDATE api_profile SET settings=? WHERE user_id=?",JsonObject(old).toString(),p.user.id)
                }
            }
            call.send(me(p))
        }
        post("/me/password") {
            val p=principal(call);requireScopes(p,"account");val b=call.body()
            val current=b["currentPassword"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
            val next=b["newPassword"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
            if(next.length<8) throw ApiProblem(422,"invalid-request")
            limited(call,"password:${p.user.id}")
            if(ctx.users.authenticateDulcet(p.user.name,current) !is AuthOutcome.Ok) throw ApiProblem(403,"permission-denied","Current password is incorrect.")
            val keep=b["keepTokens"]?.jsonPrimitive?.booleanOrNull ?: false
            db.write {
                val stamp=SlowHash.hash(next)
                execute("UPDATE user SET password_enc=?,password_hash=? WHERE id=?",vault.seal(next),stamp,p.user.id)
                execute("UPDATE api_grant SET revoked=1 WHERE user_id=? AND NOT(id=? AND kind='oauth') AND NOT(kind='personal' AND ?)",p.user.id,p.tokenId,keep)
                execute("UPDATE api_grant SET password_stamp=? WHERE user_id=? AND revoked=0",stamp,p.user.id)
                execute("UPDATE api_grant SET auth_time=? WHERE id=? AND revoked=0",clock(),p.tokenId)
                execute("DELETE FROM api_authorization WHERE user_id=?",p.user.id)
            }
            call.send(JsonNull,204)
        }
        get("/me/tokens") {
            val p=principal(call);requireScopes(p,"account")
            val limit=(call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).also { if(it !in 1..500) throw ApiProblem(400,"invalid-request") }
            val cursor=call.request.queryParameters["cursor"]?.let { raw ->
                val value=cursorCodec.decode(raw)
                if(value["kind"]?.jsonPrimitive?.content!="tokens" || value["user"]?.jsonPrimitive?.content!=p.user.id) throw ApiProblem(400,"invalid-request","Cursor belongs to a different request.")
                value["after"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
            } ?: ""
            val values=db.read { query("SELECT id FROM api_grant WHERE user_id=? AND revoked=0 AND id>? ORDER BY id",p.user.id,cursor) { grant(this,it.string("id"))!! }.filter { active(this,it) }.take(limit+1) }
            call.send(j("items" to values.take(limit).map(::tokenJson),"nextCursor" to if(values.size>limit) cursorCodec.encode(j("kind" to "tokens","user" to p.user.id,"after" to values[limit-1].id)) else null))
        }
        post("/me/tokens") {
            val p=principal(call);requireScopes(p,"account");val b=call.body()
            val name=b["name"]?.jsonPrimitive?.content ?: throw ApiProblem(400,"invalid-request")
            if(name.isBlank() || name.length>100) throw ApiProblem(422,"invalid-request")
            val requested=(b["scopes"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: throw ApiProblem(400,"invalid-request")
            if(requested.isEmpty() || !SCOPES.containsAll(requested)) throw ApiProblem(422,"invalid-request")
            if(!p.scopes.containsAll(requested) || !allowed(p.user,requested)) throw ApiProblem(403,"insufficient-scope",extra=j("requiredScopes" to requested.sorted()))
            if(b["noExpiry"]?.jsonPrimitive?.booleanOrNull==true) throw ApiProblem(422,"invalid-request","This server requires an expiry.")
            val days=b["expiresInDays"]?.jsonPrimitive?.intOrNull ?: throw ApiProblem(422,"invalid-request","expiresInDays is required.")
            if(days<1) throw ApiProblem(422,"invalid-request")
            if(days>365) throw ApiProblem(422,"token-lifetime-too-long","The maximum token lifetime is 365 days.")
            val result=db.write {
                val parent=grant(this,p.tokenId)!!
                val expires=minOf(clock()+days*DAY,parent.expires ?: Long.MAX_VALUE)
                val (gid,secret)=createPersonal(this,p.user,requested,name,expires,parent.id)
                j(*(tokenJson(grant(this,gid)!!).map { it.key to it.value } + ("secret" to secret)).toTypedArray())
            }
            call.response.headers.append(HttpHeaders.Location,"${call.request.path()}/${result["id"]!!.jsonPrimitive.content}")
            call.send(result,201)
        }
        delete("/me/tokens/{tokenId}") {
            val p=principal(call);requireScopes(p,"account")
            db.write { grant(this,call.parameters["tokenId"]!!)?.takeIf { it.userId==p.user.id }?.let { revoke(this,it.id) } }
            call.send(JsonNull,204)
        }
        delete("/me") {
            val p=principal(call);requireScopes(p,"account","user:delete")
            db.write {
                val g=grant(this,p.tokenId)!!
                if(g.authTime+600_000 < clock()) throw ApiProblem(403,"reauthentication-required")
                execute("UPDATE api_grant SET parent_id=NULL WHERE user_id=?",p.user.id)
                ctx.apiUsers.remove(p.user.id)
            }
            call.send(JsonNull,204)
        }
    } }
    private suspend fun oauth(call:ApplicationCall, action:suspend () -> Unit) {
        call.response.headers.append(HttpHeaders.CacheControl,"no-store")
        if(call.request.path() in setOf("/oauth/token","/oauth/device_authorization","/oauth/revoke")) {
            call.response.headers.append("Access-Control-Allow-Origin","*")
            call.response.headers.append("Access-Control-Expose-Headers","Retry-After")
        }
        try {
            checkBuckets(call,listOf("ip:${call.request.local.remoteHost}"))
            maintenance()
            if(call.request.queryParameters.names().any { it.lowercase() in setOf("token","access_token","refresh_token","authorization","password","p","t","s","api_key","apikey") }) throw OAuthFailure("invalid_request")
            action()
        }
        catch(e:OAuthFailure) {
            if(call.request.path()=="/oauth/authorize") call.problem(ApiProblem(400,"invalid-request",e.error))
            else call.send(j("error" to e.error),400)
        }
        catch(e:ApiProblem) {
            if(e.status==429) call.send(j("error" to "slow_down"),429) else call.problem(e)
        }
    }
    private fun limited(call:ApplicationCall,client:String) {
        if(client.length>2048) throw OAuthFailure("invalid_client")
        checkBuckets(call,listOf("client:$client"))
    }
    private fun checkBuckets(call:ApplicationCall,keys:List<String>) {
        val now=clock()
        keys.forEach { key ->
            val value=rate.compute(key) { _,old -> if(old==null || old.first+60_000<=now) now to 1 else old.first to old.second+1 }!!
            if(value.second>30) {
                call.response.headers.append(HttpHeaders.RetryAfter,"60")
                throw ApiProblem(429,"rate-limited")
            }
        }
        if(rate.size>4096) rate.entries.removeIf { it.value.first+60_000<now }
    }
    private fun limitedUser(call:ApplicationCall,user:String) {
        val now=clock()
        val key="user:"+sha(user)
        val value=rate.compute(key) { _,old -> if(old==null || old.first+60_000<=now) now to 1 else old.first to old.second+1 }!!
        if(value.second>30) { call.response.headers.append(HttpHeaders.RetryAfter,"60");throw ApiProblem(429,"rate-limited") }
    }
    private fun issuer(call:ApplicationCall):String {
        ctx.config.publicUrl?.let { return it }
        val local=call.request.local
        return "${local.scheme}://${local.serverHost}${if(local.serverPort in setOf(80,443)) "" else ":${local.serverPort}"}"
    }
    private fun cookie(call:ApplicationCall)=call.request.cookies["dulcet_consent"]
    private fun setCookie(call:ApplicationCall,value:String) { call.response.cookies.append(Cookie("dulcet_consent",value,path="/",maxAge=900,httpOnly=true,secure=issuer(call).startsWith("https://"),extensions=mapOf("SameSite" to "Strict"))) }
    private fun html(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun form(client:ClientIdentity,scopes:Set<String>,hidden:Map<String,String>,device:Boolean=false):String="""<!doctype html><html lang="en"><meta charset="utf-8"><title>Dulcet sign in</title><h1>Sign in to Dulcet</h1><p>${html(client.name)}${if(client.verified) " (Verified app)" else " (Unverified app)"}</p><p>Requested scopes: ${html(scopes.sorted().joinToString(" "))}</p>${hidden["redirect"]?.let { "<p>Code will go to ${html(it)}</p>" } ?: ""}<form method="post" action="${if(device) "/device" else "/oauth/authorize"}">${hidden.filterKeys { it!="redirect" }.map { "<input type=\"hidden\" name=\"${html(it.key)}\" value=\"${html(it.value)}\">" }.joinToString("")}<label>Username <input name="username" autocomplete="username" required></label><label>Password <input type="password" name="password" autocomplete="current-password" required></label><button name="approve" value="yes">Sign in and approve</button><button name="approve" value="no">Cancel</button></form></html>"""
    fun installOAuth(route:Route) { with(route) {
        get("/.well-known/oauth-authorization-server") {
            val origin=issuer(call)
            call.send(j("issuer" to origin,"authorization_endpoint" to "$origin/oauth/authorize","token_endpoint" to "$origin/oauth/token","device_authorization_endpoint" to "$origin/oauth/device_authorization","revocation_endpoint" to "$origin/oauth/revoke","grant_types_supported" to listOf("authorization_code","refresh_token","urn:ietf:params:oauth:grant-type:device_code"),"response_types_supported" to listOf("code"),"code_challenge_methods_supported" to listOf("S256"),"token_endpoint_auth_methods_supported" to listOf("none"),"client_id_metadata_document_supported" to true,"authorization_response_iss_parameter_supported" to true,"scopes_supported" to SCOPES.sorted()))
        }
        get("/.well-known/oauth-protected-resource") {
            val origin=issuer(call)
            call.send(j("resource" to "$origin/dulcet/v1","authorization_servers" to listOf(origin),"bearer_methods_supported" to listOf("header"),"scopes_supported" to SCOPES.sorted()))
        }
        for(path in listOf("/oauth/token","/oauth/device_authorization","/oauth/revoke")) options(path) {
            call.response.headers.append("Access-Control-Allow-Origin","*")
            call.response.headers.append("Access-Control-Allow-Methods","POST, OPTIONS")
            call.response.headers.append("Access-Control-Allow-Headers","Authorization, Content-Type")
            call.respond(HttpStatusCode.NoContent)
        }
        get("/oauth/authorize") { oauth(call) {
            val q=call.request.queryParameters;val clientId=q["client_id"] ?: throw OAuthFailure("invalid_request");limited(call,clientId);val client=identities.get(clientId)
            val redirect=q["redirect_uri"] ?: throw OAuthFailure("invalid_request");identities.validateRedirect(client,redirect)
            val scope=clientScopes(client,q["scope"] ?: "")
            if(q["response_type"]!="code" || q["code_challenge_method"]!="S256" || q["code_challenge"]?.matches(Regex("[A-Za-z0-9_-]{43}"))!=true || q["state"].isNullOrBlank()) throw OAuthFailure("invalid_request")
            if(q["resource"]!=null && q["resource"]!=issuer(call)+"/dulcet/v1") throw OAuthFailure("invalid_target")
            ctx.testing.autoUser(client.id)?.let { user ->
                if(!allowed(user,scope)) throw OAuthFailure("invalid_scope")
                limitedUser(call,user.name.lowercase())
                val request=id();val code="$request.${entropy()}"
                db.write { execute("INSERT INTO api_authorization(id,secret_hash,client_id,client_name,verified,redirect_uri,state,challenge,scopes,user_id,expires) VALUES(?,?,?,?,?,?,?,?,?,?,?)",request,SlowHash.hash(code),client.id,client.name,client.verified,redirect,q["state"],q["code_challenge"],scope.sorted().joinToString(" "),user.id,clock()+300_000) }
                val separator=if(redirect.contains('?')) "&" else "?"
                call.respondRedirect("$redirect${separator}code=${encode(code)}&state=${encode(q["state"]!!)}&iss=${encode(issuer(call))}")
                return@oauth
            }
            val request=id();val browser=entropy();setCookie(call,browser)
            db.write { execute("INSERT INTO api_authorization(id,client_id,client_name,verified,redirect_uri,state,challenge,scopes,expires,browser_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",request,client.id,client.name,client.verified,redirect,q["state"],q["code_challenge"],scope.sorted().joinToString(" "),clock()+900_000,sha(browser)) }
            call.response.headers.append("Content-Security-Policy","default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            call.respondText(form(client,scope,mapOf("request" to request,"redirect" to redirect)),ContentType.Text.Html)
        } }
        post("/oauth/authorize") { oauth(call) {
            val f=call.receiveParameters(); val rid=f["request"] ?: throw OAuthFailure("invalid_request")
            val pending=db.read { authorization(this,rid) } ?: throw OAuthFailure("invalid_request")
            limited(call,pending.clientId)
            if(pending.expires<=clock() || pending.used || pending.browserHash!=cookie(call)?.let(::sha)) throw OAuthFailure("invalid_request")
            val suffix="state=${encode(pending.state!!)}&iss=${encode(issuer(call))}"
            if(f["approve"]!="yes") { db.write { execute("DELETE FROM api_authorization WHERE id=?",rid) };call.respondRedirect("${pending.redirect}${if(pending.redirect!!.contains('?')) "&" else "?"}error=access_denied&$suffix");return@oauth }
            limitedUser(call,(f["username"] ?: "").lowercase())
            val user=(ctx.users.authenticateDulcet(f["username"] ?: "",f["password"] ?: "") as? AuthOutcome.Ok)?.user ?: throw OAuthFailure("access_denied")
            if(!allowed(user,pending.scopes)) throw OAuthFailure("invalid_scope")
            val cid=id();val code="${cid}.${entropy()}"
            db.write {
                if(long("SELECT used FROM api_authorization WHERE id=?",rid)!=0L) throw OAuthFailure("invalid_request")
                execute("UPDATE api_authorization SET id=?,secret_hash=?,user_id=?,used=0,expires=?,browser_hash=NULL WHERE id=?",cid,SlowHash.hash(code),user.id,clock()+300_000,rid)
            }
            call.respondRedirect("${pending.redirect}${if(pending.redirect!!.contains('?')) "&" else "?"}code=${encode(code)}&$suffix")
        } }
        post("/oauth/token") { oauth(call) {
            val f=call.receiveParameters();val client=f["client_id"] ?: throw OAuthFailure("invalid_client");limited(call,client)
            if(f["resource"]!=null && f["resource"]!=issuer(call)+"/dulcet/v1") throw OAuthFailure("invalid_target")
            val result=db.write {
                val userId=when(f["grant_type"]) {
                    "authorization_code" -> authorization(this,(f["code"] ?: "").substringBefore('.'))?.userId
                    "refresh_token" -> string("SELECT g.user_id FROM api_credential c JOIN api_grant g ON g.id=c.grant_id WHERE c.id=?",(f["refresh_token"] ?: "").substringBefore('.').substringAfterLast('_'))
                    "urn:ietf:params:oauth:grant-type:device_code" -> authorization(this,(f["device_code"] ?: "").substringBefore('.'))?.userId
                    else -> null
                }
                userId?.let { uid -> ctx.users.byId(uid)?.let { limitedUser(call,it.name.lowercase()) } }
                when(f["grant_type"]) {
                    "authorization_code" -> {
                        val code=f["code"] ?: throw OAuthFailure("invalid_grant")
                        val a=authorization(this,code.substringBefore('.')) ?: throw OAuthFailure("invalid_grant")
                        if(a.userId==null || a.userCode!=null || a.used || a.expires<=clock() || a.clientId!=client || a.redirect!=f["redirect_uri"] || a.hash==null || !SlowHash.verify(code,a.hash)) throw OAuthFailure("invalid_grant")
                        val verifier=f["code_verifier"] ?: throw OAuthFailure("invalid_grant")
                        if(!verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")) || !MessageDigest.isEqual(sha(verifier).toByteArray(),a.challenge!!.toByteArray())) throw OAuthFailure("invalid_grant")
                        execute("UPDATE api_authorization SET used=1 WHERE id=?",a.id)
                        val user=ctx.users.byId(a.userId)!!;if(!allowed(user,a.scopes)) throw OAuthFailure("invalid_scope")
                        val gid=createGrant(this,user,a.scopes,"oauth",a.clientName,client,a.verified,null,null)
                        pair(this,grant(this,gid)!!)
                    }
                    "refresh_token" -> refresh(this,f["refresh_token"] ?: "",client,f["scope"])
                    "urn:ietf:params:oauth:grant-type:device_code" -> deviceToken(this,f["device_code"] ?: "",client)
                    else -> throw OAuthFailure("unsupported_grant_type")
                }
            }
            if(result["error"]!=null) call.send(result,400) else call.send(result)
        } }
        post("/oauth/revoke") { oauth(call) {
            val f=call.receiveParameters();limited(call,f["client_id"] ?: "revoke")
            val token=f["token"] ?: throw OAuthFailure("invalid_request")
            db.write {
                val cid=token.substringBefore('.').substringAfterLast('_')
                single("SELECT grant_id,secret_hash FROM api_credential WHERE id=?",cid) { it.string("grant_id") to it.string("secret_hash") }?.let { (gid,hash) ->
                    val g=grant(this,gid)!!
                    if(SlowHash.verify(token,hash) && (g.clientId==null || g.clientId==f["client_id"])) revoke(this,gid)
                }
            }
            call.send(JsonNull)
        } }
        post("/oauth/device_authorization") { oauth(call) {
            val f=call.receiveParameters();val clientId=f["client_id"] ?: throw OAuthFailure("invalid_client");limited(call,clientId);val client=identities.get(clientId)
            val scope=clientScopes(client,f["scope"] ?: "")
            val autoUser=ctx.testing.autoUser(client.id)
            if(autoUser!=null) {
                if(!allowed(autoUser,scope)) throw OAuthFailure("invalid_scope")
                limitedUser(call,autoUser.name.lowercase())
            }
            val cid=id();val device="$cid.${entropy()}";val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val userCode=(1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("").chunked(4).joinToString("-")
            db.write { execute("INSERT INTO api_authorization(id,secret_hash,client_id,client_name,verified,scopes,expires,user_code,user_id) VALUES(?,?,?,?,?,?,?,?,?)",cid,SlowHash.hash(device),client.id,client.name,client.verified,scope.sorted().joinToString(" "),clock()+900_000,userCode,autoUser?.id) }
            call.send(j("device_code" to device,"user_code" to userCode,"verification_uri" to "${issuer(call)}/device","verification_uri_complete" to "${issuer(call)}/device?user_code=$userCode","expires_in" to 900,"interval" to 5))
        } }
        get("/device") {
            val browser=entropy();setCookie(call,browser)
            val code=call.request.queryParameters["user_code"]
            val pending=code?.let { db.read { single("SELECT id FROM api_authorization WHERE user_code=?",it) { authorization(this,it.string("id"))!! } } }
            call.response.headers.append("Content-Security-Policy","default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            if(pending!=null && pending.expires>clock() && pending.attempts<5 && !pending.used) {
                db.write { execute("UPDATE api_authorization SET browser_hash=? WHERE id=?",sha(browser),pending.id) }
                call.respondText(form(ClientIdentity(pending.clientId,pending.clientName,pending.verified),pending.scopes,mapOf("user_code" to code!!),true),ContentType.Text.Html)
            } else call.respondText("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Dulcet device approval</title><form method=\"get\" action=\"/device\"><label>Device code <input name=\"user_code\" required></label><button>Review app</button></form></html>",ContentType.Text.Html)
        }
        post("/device") { oauth(call) {
            val f=call.receiveParameters();limited(call,"device-approval")
            val code=f["user_code"] ?: throw OAuthFailure("invalid_request")
            val a=db.read { single("SELECT id FROM api_authorization WHERE user_code=?",code) { authorization(this,it.string("id"))!! } } ?: throw OAuthFailure("access_denied")
            if(a.expires<=clock() || a.attempts>=5 || a.used || a.browserHash!=cookie(call)?.let(::sha)) throw OAuthFailure("access_denied")
            limitedUser(call,(f["username"] ?: "").lowercase())
            val user=(ctx.users.authenticateDulcet(f["username"] ?: "",f["password"] ?: "") as? AuthOutcome.Ok)?.user
            if(f["approve"]!="yes") { db.write { execute("UPDATE api_authorization SET attempts=5 WHERE id=?",a.id) };throw OAuthFailure("access_denied") }
            if(user==null) { db.write { execute("UPDATE api_authorization SET attempts=attempts+1 WHERE id=?",a.id) };throw OAuthFailure("access_denied") }
            if(!allowed(user,a.scopes)) throw OAuthFailure("invalid_scope")
            db.write { execute("UPDATE api_authorization SET user_id=?,browser_hash=NULL WHERE id=? AND user_id IS NULL",user.id,a.id) }
            call.respondText("Device approved. You may close this page.",ContentType.Text.Plain)
        } }
    } }
    private data class Authorization(val id:String,val hash:String?,val clientId:String,val clientName:String,val verified:Boolean,val redirect:String?,val state:String?,val challenge:String?,val scopes:Set<String>,val userId:String?,val expires:Long,val browserHash:String?,val userCode:String?,val attempts:Int,val pollAt:Long,val interval:Int,val used:Boolean)
    private fun authorization(s:Db.Session,id:String):Authorization?=s.single("SELECT * FROM api_authorization WHERE id=?",id) {
        Authorization(it.string("id"),it.stringOrNull("secret_hash"),it.string("client_id"),it.string("client_name"),it.bool("verified"),it.stringOrNull("redirect_uri"),it.stringOrNull("state"),it.stringOrNull("challenge"),scopes(it.string("scopes")),it.stringOrNull("user_id"),it.long("expires"),it.stringOrNull("browser_hash"),it.stringOrNull("user_code"),it.int("attempts"),it.long("poll_at"),it.int("interval_sec"),it.bool("used"))
    }
    private fun refresh(s:Db.Session,token:String,client:String,scope:String?):JsonObject {
        val cid=token.substringBefore('.').substringAfterLast('_')
        val c=s.single("SELECT * FROM api_credential WHERE id=? AND kind='refresh'",cid) { listOf(it.string("grant_id"),it.string("secret_hash"),it.long("expires"),it.longOrNull("used_at"),it.bytes("retry_pair")) } ?: throw OAuthFailure("invalid_grant")
        val g=grant(s,c[0] as String) ?: throw OAuthFailure("invalid_grant")
        if(g.clientId!=client || !SlowHash.verify(token,c[1] as String) || !active(s,g) || (c[2] as Long)<=clock()) throw OAuthFailure("invalid_grant")
        val used=c[3] as Long?
        if(used!=null) {
            if(used+60_000>clock() && c[4]!=null) return Json.parseToJsonElement(vault.open(c[4] as ByteArray)).jsonObject
            revoke(s,g.id)
            return j("error" to "invalid_grant") // return, rather than throw, so grant revocation commits
        }
        val requested=scope?.let(::parseScopes) ?: g.scopes
        if(!g.scopes.containsAll(requested)) throw OAuthFailure("invalid_scope")
        val result=pair(s,g,requested)
        s.execute("UPDATE api_credential SET used_at=?,retry_pair=? WHERE id=?",clock(),vault.seal(result.toString()),cid)
        return result
    }
    private fun deviceToken(s:Db.Session,device:String,client:String):JsonObject {
        val a=authorization(s,device.substringBefore('.')) ?: throw OAuthFailure("invalid_grant")
        if(a.userCode==null || a.clientId!=client || a.hash==null || !SlowHash.verify(device,a.hash)) throw OAuthFailure("invalid_grant")
        if(a.expires<=clock()) throw OAuthFailure("expired_token")
        if(a.attempts>=5) throw OAuthFailure("access_denied")
        if(a.used) throw OAuthFailure("invalid_grant")
        if(a.pollAt+a.interval*1000>clock()) {
            s.execute("UPDATE api_authorization SET interval_sec=interval_sec+5,poll_at=? WHERE id=?",clock(),a.id)
            return j("error" to "slow_down")
        }
        s.execute("UPDATE api_authorization SET poll_at=? WHERE id=?",clock(),a.id)
        if(a.userId==null) return j("error" to "authorization_pending")
        val user=ctx.users.byId(a.userId)!!;if(!allowed(user,a.scopes)) throw OAuthFailure("invalid_scope")
        s.execute("UPDATE api_authorization SET used=1 WHERE id=?",a.id)
        val gid=createGrant(s,user,a.scopes,"oauth",a.clientName,client,a.verified,null,null)
        return pair(s,grant(s,gid)!!)
    }
    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8)
    companion object {
        val SCOPES=setOf("catalog:read","media:read","user:read","user:write","user:delete","plays:write","account","admin","offline_access")
        private const val DAY=86_400_000L
        private const val REFRESH_TTL=90L*DAY
    }
}
