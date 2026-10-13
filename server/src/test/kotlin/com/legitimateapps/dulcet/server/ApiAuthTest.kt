package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.auth.*
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class ApiAuthTest {
    @Test fun `Dulcet login hashes password and keeps rest compatibility`() {
        val ctx=Fixtures.context(Files.createTempDirectory("auth"))
        ctx.db.use {
            val user=ctx.users.create("alice","password-long",false)
            assertTrue(ctx.db.read { string("SELECT password_hash FROM user WHERE id=?",user.id)!! }.startsWith("bcrypt-sha256$"))
            assertIs<AuthOutcome.Ok>(ctx.users.authenticateDulcet("alice","password-long"))
            assertIs<AuthOutcome.WrongCredentials>(ctx.users.authenticateDulcet("alice","wrong"))
            assertIs<AuthOutcome.WrongCredentials>(ctx.users.authenticateDulcet("unknown","password-long"))
            assertIs<AuthOutcome.Ok>(ctx.users.authenticateToken("alice",Users.md5Hex("password-long"+"salt"),"salt"))
        }
    }
    @Test fun `tokens are hashed revocable and bounded by user role`() {
        val ctx=Fixtures.context(Files.createTempDirectory("auth"))
        ctx.db.use {
            val user=ctx.users.create("alice","password-long",false)
            val auth=ApiAuth(ctx)
            assertFailsWith<IllegalArgumentException> { auth.bootstrapPersonal(user,setOf("admin")) }
            val token=auth.bootstrapPersonal(user,setOf("user:read","account"))
            val p=auth.authenticate("Bearer $token")!!
            assertEquals(setOf("user:read","account"),p.scopes)
            assertFailsWith<ApiProblem> { requireScopes(p,"user:write") }
            assertNull(auth.authenticate("Basic $token"))
            assertNull(auth.authenticate("Bearer $token-corrupt"))
            assertTrue(ctx.db.read { string("SELECT secret_hash FROM api_credential")!! }.startsWith("bcrypt-sha256$"))
            ctx.users.setPassword("alice","new-password-long")
            assertNull(auth.authenticate("Bearer $token"))
            assertFalse(auth.isActive(p.tokenId))
        }
    }
    @Test fun `bcrypt prehash distinguishes long passwords after byte 72`() {
        val base="a".repeat(90);val h=SlowHash.hash(base+"x")
        assertTrue(SlowHash.verify(base+"x",h));assertFalse(SlowHash.verify(base+"y",h))
        assertFalse(SlowHash.verify(base+"x","malformed"))
    }
    @Test fun `PKCE exchange refresh retry and late replay revoke grant`() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("auth-flow"));ctx.users.create("alice","password-long",false)
        var now=System.currentTimeMillis();val auth=ApiAuth(ctx) { now }
        application { routing { auth.installOAuth(this) } }
        val browser=createClient { followRedirects=false }
        val verifier="v".repeat(64)
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val cid="https://invalid.example/client.json";val redirect="com.example.player:/oauth/callback"
        val start=browser.get("/oauth/authorize") { parameter("client_id",cid);parameter("redirect_uri",redirect);parameter("scope","catalog:read offline_access");parameter("response_type","code");parameter("state","client-state");parameter("code_challenge",challenge);parameter("code_challenge_method","S256") }
        assertEquals(HttpStatusCode.OK,start.status)
        assertTrue(start.bodyAsText().contains("Unverified app"))
        assertNull(start.headers["Access-Control-Allow-Origin"])
        val request=Regex("name=\"request\" value=\"([^\"]+)\"").find(start.bodyAsText())!!.groupValues[1]
        val cookie=start.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val rejected=browser.post("/oauth/authorize") { setBody(FormDataContent(Parameters.build { append("request",request);append("username","alice");append("password","password-long");append("approve","yes") })) }
        assertEquals(HttpStatusCode.BadRequest,rejected.status)
        val consent=browser.post("/oauth/authorize") { header(HttpHeaders.Cookie,cookie);setBody(FormDataContent(Parameters.build { append("request",request);append("username","alice");append("password","password-long");append("approve","yes") })) }
        assertEquals(HttpStatusCode.Found,consent.status)
        val location=consent.headers[HttpHeaders.Location]!!
        assertTrue(location.contains("iss="));assertTrue(location.contains("state=client-state"))
        val code=java.net.URLDecoder.decode(location.substringAfter("code=").substringBefore('&'),Charsets.UTF_8)
        suspend fun exchange(v:String)=browser.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("grant_type","authorization_code");append("client_id",cid);append("redirect_uri",redirect);append("code",code);append("code_verifier",v) })) }
        assertEquals(HttpStatusCode.BadRequest,exchange("w".repeat(64)).status)
        val response=exchange(verifier);assertEquals(HttpStatusCode.OK,response.status)
        val tokens=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(HttpStatusCode.BadRequest,exchange(verifier).status)
        val original=tokens["access_token"]!!.jsonPrimitive.content
        assertNotNull(auth.authenticate("Bearer $original"))
        suspend fun refresh()=browser.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("grant_type","refresh_token");append("client_id",cid);append("refresh_token",tokens["refresh_token"]!!.jsonPrimitive.content) })) }
        val next=refresh();assertEquals(HttpStatusCode.OK,next.status)
        assertEquals(next.bodyAsText(),refresh().bodyAsText())
        now+=61_000
        assertEquals(HttpStatusCode.BadRequest,refresh().status)
        assertNull(auth.authenticate("Bearer $original"))
        assertNull(auth.authenticate("Bearer ${Json.parseToJsonElement(next.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content}"))
        ctx.db.close()
    }
    @Test fun `device grant enforces polling interval and approval`() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("device-flow"));ctx.users.create("alice","password-long",false)
        var now=System.currentTimeMillis();val auth=ApiAuth(ctx) { now };application { routing { auth.installOAuth(this) } }
        val cid="https://invalid.example/device-client.json"
        val start=client.post("/oauth/device_authorization") { setBody(FormDataContent(Parameters.build { append("client_id",cid);append("scope","user:read") })) }
        assertEquals("*",start.headers["Access-Control-Allow-Origin"])
        val preflight=client.options("/oauth/token") { header("Origin","https://player.example");header("Access-Control-Request-Method","POST") }
        assertEquals(HttpStatusCode.NoContent,preflight.status)
        assertEquals("*",preflight.headers["Access-Control-Allow-Origin"])
        val codes=Json.parseToJsonElement(start.bodyAsText()).jsonObject
        suspend fun poll()=client.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("client_id",cid);append("grant_type","urn:ietf:params:oauth:grant-type:device_code");append("device_code",codes["device_code"]!!.jsonPrimitive.content) })) }
        assertEquals("authorization_pending",Json.parseToJsonElement(poll().bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals("slow_down",Json.parseToJsonElement(poll().bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
        val code=codes["user_code"]!!.jsonPrimitive.content
        val page=client.get("/device") { parameter("user_code",code) };val cookie=page.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue(page.bodyAsText().contains("Unverified app"))
        val approval=client.post("/device") { header(HttpHeaders.Cookie,cookie);setBody(FormDataContent(Parameters.build { append("user_code",code);append("username","alice");append("password","password-long");append("approve","yes") })) }
        assertEquals(HttpStatusCode.OK,approval.status)
        now+=10_001
        val minted=poll();assertEquals(HttpStatusCode.OK,minted.status)
        val pair=Json.parseToJsonElement(minted.bodyAsText()).jsonObject
        assertFalse(pair.containsKey("refresh_token"))
        val access=pair["access_token"]!!.jsonPrimitive.content
        val principal=auth.authenticate("Bearer $access")!!
        now+=10_001
        assertEquals("invalid_grant",Json.parseToJsonElement(poll().bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
        now+=3_600_001
        assertNull(auth.authenticate("Bearer $access"))
        assertTrue(auth.isActive(principal.tokenId))
        ctx.db.close()
    }
    @Test fun `personal token scopes lifetime and parent revocation are enforced over HTTP`() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("pat-flow"))
        val user=ctx.users.create("alice","password-long",false)
        val secret=ctx.apiAuth.bootstrapPersonal(user,setOf("account","user:read"))
        val parent=ctx.apiAuth.authenticate("Bearer $secret")!!
        val expiration=System.currentTimeMillis()+86_400_000
        ctx.db.write { execute("UPDATE api_grant SET expires=? WHERE id=?",expiration,parent.tokenId) }
        application { dulcetModule(ctx) }
        val updated=client.patch("/dulcet/v1/me") {
            header(HttpHeaders.Authorization,"Bearer $secret");contentType(ContentType.parse("application/merge-patch+json"))
            setBody("""{"displayName":"Alice"}""")
        }
        assertEquals(HttpStatusCode.OK,updated.status)
        assertTrue(ctx.db.read { long("SELECT COUNT(*) FROM api_journal WHERE type='user' AND user_id=?",user.id) } > 0)
        suspend fun mint(scopes:String)=client.post("/dulcet/v1/me/tokens") {
            header(HttpHeaders.Authorization,"Bearer $secret");contentType(ContentType.Application.Json)
            setBody("""{"name":"Integration","scopes":[$scopes],"expiresInDays":365}""")
        }
        assertEquals(HttpStatusCode.Forbidden,mint("\"user:write\"").status)
        val tooLong=client.post("/dulcet/v1/me/tokens") {
            header(HttpHeaders.Authorization,"Bearer $secret");contentType(ContentType.Application.Json)
            setBody("""{"name":"Too long","scopes":["user:read"],"expiresInDays":366}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity,tooLong.status)
        assertEquals("urn:dulcet:problem:token-lifetime-too-long",Json.parseToJsonElement(tooLong.bodyAsText()).jsonObject["type"]!!.jsonPrimitive.content)
        val created=mint("\"user:read\"")
        assertEquals(HttpStatusCode.Created,created.status)
        val child=Json.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals(parent.tokenId,child["parentId"]!!.jsonPrimitive.content)
        assertEquals(iso(expiration),child["expiresAt"]!!.jsonPrimitive.content)
        val childSecret=child["secret"]!!.jsonPrimitive.content
        assertNotNull(ctx.apiAuth.authenticate("Bearer $childSecret"))
        assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me/tokens/${parent.tokenId}") { header(HttpHeaders.Authorization,"Bearer $secret") }.status)
        assertNull(ctx.apiAuth.authenticate("Bearer $childSecret"))
        ctx.db.close()
    }

    @Test fun `metadata guard refuses private connections and unsafe redirects`() {
        val clients=ClientIdentities()
        java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout=100
            val identity=clients.get("https://127.0.0.1:${listener.localPort}/client.json")
            assertFalse(identity.verified)
            assertFailsWith<java.net.SocketTimeoutException> { listener.accept() }
            clients.validateRedirect(identity,"com.example.player:/oauth/callback")
            clients.validateRedirect(identity,"http://127.0.0.1:54321/callback")
            assertFailsWith<OAuthFailure> { clients.validateRedirect(identity,"https://elsewhere.example/callback") }
            assertFailsWith<OAuthFailure> { clients.validateRedirect(identity,"javascript:alert(1)") }
            assertFailsWith<OAuthFailure> { clients.get("https://user:password@example.org/client.json") }
        }
        val verified=ClientIdentity("https://player.example/client.json","Player",true,setOf("https://player.example/callback","http://127.0.0.1/callback"))
        clients.validateRedirect(verified,"https://player.example/callback")
        clients.validateRedirect(verified,"http://127.0.0.1:53453/callback")
        assertFailsWith<OAuthFailure> { clients.validateRedirect(verified,"https://player.example/callback/evil") }
        assertFailsWith<OAuthFailure> { clients.validateRedirect(verified,"https://player.example/callback#secret") }
    }

    @Test fun `OAuth rate limiter returns transport status and retry header`() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("oauth-rate"));val auth=ApiAuth(ctx)
        application { routing { auth.installOAuth(this) } }
        repeat(30) {
            assertEquals(HttpStatusCode.BadRequest,client.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("client_id","client");append("grant_type","invalid") })) }.status)
        }
        val limited=client.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("client_id","client");append("grant_type","invalid") })) }
        assertEquals(HttpStatusCode.TooManyRequests,limited.status)
        assertEquals("60",limited.headers[HttpHeaders.RetryAfter])
        assertEquals("slow_down",Json.parseToJsonElement(limited.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)
        ctx.db.close()
    }

    private fun testContext(root:java.nio.file.Path):ServerContext {
        val config=Config(root.resolve("music").also(Files::createDirectories),root.resolve("data").also(Files::createDirectories),0,"127.0.0.1",Fixtures.ffmpeg,Fixtures.ffprobe,null,scanOnStart=false,publicUrl="https://music.example",testMode=true)
        return ServerContext(config,com.legitimateapps.dulcet.server.db.Db.inMemory())
    }
    @Test fun `testing autoapproval preserves PKCE redirects scopes and client allowlist`() = testApplication {
        val ctx=testContext(Files.createTempDirectory("auto-code"));val user=ctx.users.create("alice","password-long",false)
        val cid="https://invalid.example/client.json";val redirect="com.example.player:/oauth/callback"
        ctx.testing.replace(j("autoApprove" to listOf(j("clientId" to cid,"userId" to user.id))))
        application { routing { ctx.apiAuth.installOAuth(this) } }
        val browser=createClient { followRedirects=false };val verifier="v".repeat(64)
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        suspend fun start(clientId:String=cid,scope:String="catalog:read",method:String="S256",uri:String=redirect,resource:String="https://music.example/dulcet/v1")=browser.get("/oauth/authorize") {
            parameter("client_id",clientId);parameter("scope",scope);parameter("response_type","code");parameter("state","client-state");parameter("redirect_uri",uri);parameter("code_challenge",challenge);parameter("code_challenge_method",method);parameter("resource",resource)
        }
        assertEquals(HttpStatusCode.BadRequest,start(scope="account").status)
        assertEquals(HttpStatusCode.BadRequest,start(method="plain").status)
        assertEquals(HttpStatusCode.BadRequest,start(uri="https://elsewhere.example/callback").status)
        assertEquals(HttpStatusCode.BadRequest,start(resource="https://music.example/api/v1").status)
        assertEquals(HttpStatusCode.OK,start(clientId="https://invalid.example/another-client.json").status)
        val approved=start(scope="catalog:read media:read user:read offline_access account admin");assertEquals(HttpStatusCode.Found,approved.status)
        assertNull(approved.headers[HttpHeaders.SetCookie])
        val location=approved.headers[HttpHeaders.Location]!!
        val query=parseQueryString(location.substringAfter('?'))
        assertEquals("https://music.example",query["iss"]);assertEquals("client-state",query["state"])
        suspend fun exchange(v:String)=browser.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("grant_type","authorization_code");append("client_id",cid);append("redirect_uri",redirect);append("code",query["code"]!!);append("code_verifier",v) })) }
        assertEquals(HttpStatusCode.BadRequest,exchange("x".repeat(64)).status)
        val minted=exchange(verifier);assertEquals(HttpStatusCode.OK,minted.status)
        val pair=Json.parseToJsonElement(minted.bodyAsText()).jsonObject
        val granted=setOf("catalog:read","media:read","user:read","offline_access")
        assertEquals(granted,pair["scope"]!!.jsonPrimitive.content.split(' ').toSet())
        val secret=pair["access_token"]!!.jsonPrimitive.content
        val principal=ctx.apiAuth.authenticate("Bearer $secret")!!
        assertEquals(user.id,principal.user.id);assertEquals(granted,principal.scopes)
        suspend fun refresh(scope:String?=null)=browser.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("grant_type","refresh_token");append("client_id",cid);append("refresh_token",pair["refresh_token"]!!.jsonPrimitive.content);if(scope!=null) append("scope",scope) })) }
        assertEquals(HttpStatusCode.BadRequest,refresh("account").status)
        val rotated=refresh();assertEquals(HttpStatusCode.OK,rotated.status)
        assertEquals(granted,Json.parseToJsonElement(rotated.bodyAsText()).jsonObject["scope"]!!.jsonPrimitive.content.split(' ').toSet())
        assertEquals(HttpStatusCode.BadRequest,exchange(verifier).status)
        ctx.testing.replace(j())
        assertEquals(HttpStatusCode.OK,start().status)
        ctx.db.close()
    }
    @Test fun `testing device autoapproval issues normal single use device grant`() = testApplication {
        val ctx=testContext(Files.createTempDirectory("auto-device"));val user=ctx.users.create("alice","password-long",false)
        val cid="https://invalid.example/device.json"
        ctx.testing.replace(j("autoApprove" to listOf(j("clientId" to cid,"userId" to user.id))))
        application { routing { ctx.apiAuth.installOAuth(this) } }
        suspend fun issue(scope:String)=client.post("/oauth/device_authorization") { setBody(FormDataContent(Parameters.build { append("client_id",cid);append("scope",scope) })) }
        assertEquals(HttpStatusCode.BadRequest,issue("admin").status)
        val device=Json.parseToJsonElement(issue("user:read offline_access account admin").bodyAsText()).jsonObject["device_code"]!!.jsonPrimitive.content
        suspend fun poll()=client.post("/oauth/token") { setBody(FormDataContent(Parameters.build { append("client_id",cid);append("grant_type","urn:ietf:params:oauth:grant-type:device_code");append("device_code",device) })) }
        val minted=poll();assertEquals(HttpStatusCode.OK,minted.status)
        val pair=Json.parseToJsonElement(minted.bodyAsText()).jsonObject
        assertNotNull(pair["refresh_token"])
        assertEquals(setOf("user:read","offline_access"),pair["scope"]!!.jsonPrimitive.content.split(' ').toSet())
        val principal=ctx.apiAuth.authenticate("Bearer ${pair["access_token"]!!.jsonPrimitive.content}")!!
        assertEquals(user.id,principal.user.id);assertEquals(setOf("user:read","offline_access"),principal.scopes)
        assertEquals(HttpStatusCode.BadRequest,poll().status)
        ctx.db.close()
    }
    @Test fun `restore carries only caller admin grant family even when user is absent`() {
        val root=Files.createTempDirectory("carried-grant");val ctx=testContext(root)
        ctx.db.use {
            val checkpoint=root.resolve("checkpoint.db");ctx.db.checkpoint(checkpoint)
            val user=ctx.users.create("admin","password-long",true)
            val caller=ctx.apiAuth.bootstrapPersonal(user,setOf("admin","account"))
            val parent=ctx.apiAuth.bootstrapPersonal(user,setOf("admin","account"))
            val child=ctx.apiAuth.bootstrapPersonal(user,setOf("admin"))
            val unrelated=ctx.apiAuth.bootstrapPersonal(user,setOf("admin"))
            val childPrincipal=ctx.apiAuth.authenticate("Bearer $child")!!
            val parentPrincipal=ctx.apiAuth.authenticate("Bearer $parent")!!
            ctx.db.write { execute("UPDATE api_grant SET parent_id=? WHERE id=?",parentPrincipal.tokenId,childPrincipal.tokenId) }
            val p=ctx.apiAuth.authenticate("Bearer $child")!!
            assertNotNull(ctx.apiAuth.authenticate("Bearer $unrelated"))
            ctx.apiAuth.withCarriedGrant(p) { ctx.db.restore(checkpoint);ctx.journal.rotateInstance() }
            assertEquals(user.id,ctx.apiAuth.authenticate("Bearer $child")!!.user.id)
            assertNotNull(ctx.apiAuth.authenticate("Bearer $parent"))
            assertNull(ctx.apiAuth.authenticate("Bearer $caller"))
            assertNull(ctx.apiAuth.authenticate("Bearer $unrelated"))
            assertTrue(ctx.users.byId(user.id)!!.isAdmin)
        }
    }

    @Test fun `restore preserves caller admin exception and restored password stamp`() {
        val root=Files.createTempDirectory("carried-promoted-admin");val ctx=testContext(root)
        ctx.db.use {
            val original=ctx.users.create("alice","original-password",false)
            val oldToken=ctx.apiAuth.bootstrapPersonal(original,setOf("user:read"))
            val checkpoint=root.resolve("checkpoint.db");ctx.db.checkpoint(checkpoint)
            ctx.users.setPassword(original.name,"new-password-long")
            ctx.db.write { execute("UPDATE user SET is_admin=1 WHERE id=?",original.id) }
            val promoted=ctx.users.byId(original.id)!!
            val caller=ctx.apiAuth.bootstrapPersonal(promoted,setOf("admin"))
            val p=ctx.apiAuth.authenticate("Bearer $caller")!!
            ctx.apiAuth.withCarriedGrant(p) { ctx.db.restore(checkpoint);ctx.journal.rotateInstance() }
            assertTrue(ctx.users.byId(original.id)!!.isAdmin)
            assertNotNull(ctx.apiAuth.authenticate("Bearer $caller"))
            assertNotNull(ctx.apiAuth.authenticate("Bearer $oldToken"))
            assertIs<AuthOutcome.Ok>(ctx.users.authenticateDulcet(original.name,"original-password"))
            assertIs<AuthOutcome.WrongCredentials>(ctx.users.authenticateDulcet(original.name,"new-password-long"))
        }
    }

    @Test fun `restore carries recreated caller without replacing checkpoint user of same name`() {
        val root=Files.createTempDirectory("carried-name-collision");val ctx=testContext(root)
        ctx.db.use {
            val original=ctx.users.create("admin","checkpoint-password",true)
            val checkpointToken=ctx.apiAuth.bootstrapPersonal(original,setOf("admin"))
            val checkpoint=root.resolve("checkpoint.db");ctx.db.checkpoint(checkpoint)
            ctx.db.write { execute("DELETE FROM user WHERE id=?",original.id) }
            val recreated=ctx.users.create("admin","caller-password",true)
            assertNotEquals(original.id,recreated.id)
            val caller=ctx.apiAuth.bootstrapPersonal(recreated,setOf("admin"))
            val p=ctx.apiAuth.authenticate("Bearer $caller")!!
            ctx.apiAuth.withCarriedGrant(p) { ctx.db.restore(checkpoint);ctx.journal.rotateInstance() }
            assertEquals("admin",ctx.users.byId(original.id)!!.name)
            assertEquals(original.id,ctx.apiAuth.authenticate("Bearer $checkpointToken")!!.user.id)
            val carried=ctx.users.byId(recreated.id)!!
            assertEquals("test-admin-${recreated.id}",carried.name);assertTrue(carried.isAdmin)
            assertEquals(recreated.id,ctx.apiAuth.authenticate("Bearer $caller")!!.user.id)
            assertIs<AuthOutcome.Ok>(ctx.users.authenticateDulcet("admin","checkpoint-password"))
            assertIs<AuthOutcome.Ok>(ctx.users.authenticateDulcet(carried.name,"caller-password"))
        }
    }

    @Test fun `account resource honors source and sharing permissions and deletion removes now playing`() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("account-access"))
        val user=ctx.users.create("alice","password-long",false)
        ctx.db.write { execute("INSERT OR IGNORE INTO music_folder(id,name,path) VALUES(1,'Music',?)",ctx.config.musicFolder.toString()) }
        ctx.apiUsers.update(user.id,j("sourceIds" to emptyList<String>(),"permissions" to j("download" to false,"share" to false,"customPermission" to false)))
        val secret=ctx.apiAuth.bootstrapPersonal(user,setOf("account","user:delete"))
        application { dulcetModule(ctx) }
        val me=client.get("/dulcet/v1/me") { header(HttpHeaders.Authorization,"Bearer $secret") }
        val body=Json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertEquals(emptyList(),body["sourceIds"]!!.jsonArray.toList())
        assertEquals(false,body["permissions"]!!.jsonObject["download"]!!.jsonPrimitive.boolean)
        assertEquals(false,body["permissions"]!!.jsonObject["customPermission"]!!.jsonPrimitive.boolean)
        suspend fun sharing(value:Boolean)=client.patch("/dulcet/v1/me") {
            header(HttpHeaders.Authorization,"Bearer $secret");contentType(ContentType.parse("application/merge-patch+json"));setBody("""{"settings":{"shareNowPlaying":$value}}""")
        }
        assertEquals(HttpStatusCode.Forbidden,sharing(true).status)
        assertEquals(HttpStatusCode.OK,sharing(false).status)
        ctx.db.write { execute("INSERT INTO now_playing(user_id,client,track_id,at) VALUES(?,'test','track',?)",user.id,System.currentTimeMillis()) }
        assertEquals(HttpStatusCode.NoContent,client.delete("/dulcet/v1/me") { header(HttpHeaders.Authorization,"Bearer $secret") }.status)
        assertNull(ctx.users.byId(user.id))
        assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM now_playing WHERE user_id=?",user.id) })
        ctx.db.close()
    }

    @Test fun `anonymous OAuth discovery exposes canonical working endpoints and header bearer method`() = testApplication {
        val ctx=testContext(Files.createTempDirectory("oauth-metadata"))
        application { dulcetModule(ctx) }
        val response=client.get("/.well-known/oauth-authorization-server")
        assertEquals(HttpStatusCode.OK,response.status)
        assertEquals("*",response.headers["Access-Control-Allow-Origin"])
        val metadata=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("https://music.example",metadata["issuer"]!!.jsonPrimitive.content)
        assertEquals("https://music.example/oauth/authorize",metadata["authorization_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://music.example/oauth/token",metadata["token_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://music.example/oauth/device_authorization",metadata["device_authorization_endpoint"]!!.jsonPrimitive.content)
        assertEquals("https://music.example/oauth/revoke",metadata["revocation_endpoint"]!!.jsonPrimitive.content)
        assertFalse(metadata.containsKey("registration_endpoint"))
        assertEquals(setOf("authorization_code","refresh_token","urn:ietf:params:oauth:grant-type:device_code"),metadata["grant_types_supported"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(listOf("code"),metadata["response_types_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("S256"),metadata["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("none"),metadata["token_endpoint_auth_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(metadata["client_id_metadata_document_supported"]!!.jsonPrimitive.boolean)
        assertTrue(metadata["authorization_response_iss_parameter_supported"]!!.jsonPrimitive.boolean)
        assertEquals(ApiAuth.SCOPES,metadata["scopes_supported"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val protected=client.get("/.well-known/oauth-protected-resource")
        assertEquals(HttpStatusCode.OK,protected.status)
        val resource=Json.parseToJsonElement(protected.bodyAsText()).jsonObject
        assertEquals("https://music.example/dulcet/v1",resource["resource"]!!.jsonPrimitive.content)
        assertEquals(listOf("https://music.example"),resource["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("header"),resource["bearer_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ApiAuth.SCOPES,resource["scopes_supported"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        ctx.db.close()
    }

}
