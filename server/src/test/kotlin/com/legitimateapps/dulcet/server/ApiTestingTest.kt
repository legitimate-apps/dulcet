package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ApiTestingTest {
    private suspend fun HttpResponse.obj()=Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun context(testMode:Boolean):ServerContext {
        val root=Files.createTempDirectory("api-testing")
        val config=Config(root.resolve("music").also(Files::createDirectories),root.resolve("data").also(Files::createDirectories),0,"127.0.0.1",Fixtures.ffmpeg,Fixtures.ffprobe,null,scanOnStart=false,testMode=testMode)
        return ServerContext(config,Db.open(config.dataFolder.resolve("dulcet.db")))
    }
    @Test fun `normal served data refuses test mode and hides every hook`()=testApplication {
        val ctx=context(false);val admin=ctx.users.create("admin","password",true)
        val token=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
        application { dulcetModule(ctx) }
        for(path in listOf("/admin/test/settings","/admin/test/checkpoints","/admin/test/checkpoints/unknown/restore")) {
            val response=if(path.endsWith("settings")) client.get("/dulcet/v1$path") { bearerAuth(token) } else client.post("/dulcet/v1$path") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NotFound,response.status)
        }
        assertEquals(JsonPrimitive(false),client.get("/.well-known/dulcet").obj()["testMode"])
        assertFailsWith<IllegalArgumentException> { ServerContext(ctx.config.copy(testMode=true),ctx.db) }
        ctx.db.close()
    }
    @Test fun `settings are admin only atomic replacements and reset per token limiter`()=testApplication {
        val ctx=context(true);val admin=ctx.users.create("admin","password",true);val user=ctx.users.create("reader","password",false)
        val ta=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin","account"));val tu=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        application { dulcetModule(ctx) }
        suspend fun replace(body:JsonObject,token:String=ta)=client.put("/dulcet/v1/admin/test/settings") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(body.toString()) }
        assertEquals(HttpStatusCode.Forbidden,replace(j("mediaUrlTtlSeconds" to 1),tu).status)
        assertEquals(HttpStatusCode.Unauthorized,client.get("/dulcet/v1/admin/test/settings").status)
        val override=j("mediaUrlTtlSeconds" to 1,"artworkUrlTtlSeconds" to 2,"syncRetentionSeconds" to 3,"rateLimit" to j("requestsPerWindow" to 1,"windowSeconds" to 60))
        assertEquals(override,replace(override).obj())
        assertEquals(HttpStatusCode.OK,client.get("/dulcet/v1/me") { bearerAuth(ta) }.status)
        val limited=client.get("/dulcet/v1/me") { bearerAuth(ta) }
        assertEquals(HttpStatusCode.TooManyRequests,limited.status);assertTrue(limited.headers["Retry-After"]!!.toInt() in 1..60)
        assertEquals(HttpStatusCode.OK,client.get("/dulcet/v1/me") { bearerAuth(tu) }.status)
        assertEquals(override,client.get("/dulcet/v1/admin/test/settings") { bearerAuth(ta) }.obj())
        assertEquals(HttpStatusCode.BadRequest,replace(j("syncRetentionSeconds" to -1)).status)
        assertEquals(override,ctx.testing.settings())
        assertEquals(j(),replace(j()).obj())
        assertEquals(HttpStatusCode.OK,client.get("/dulcet/v1/me") { bearerAuth(ta) }.status)
        assertEquals(21600L,ctx.testing.mediaTtlSeconds);assertEquals(90L*24*60*60*1000,ctx.journal.retentionMillis)
        // A Testing TTL changes expiry without changing the signed artwork URL on every read.
        Fixtures.track(ctx.config.musicFolder.resolve("artwork/song.flac"))
        ctx.scanner.scan()
        ctx.testing.replace(j("artworkUrlTtlSeconds" to 60))
        val artToken=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        val trackId=ctx.db.read { string("SELECT id FROM track LIMIT 1")!! }
        val artId=ctx.apiMedia.artworkRef(trackId,ctx.apiAuth.authenticate("Bearer $artToken")!!)!!["id"]!!.jsonPrimitive.content
        suspend fun artwork()=client.post("/dulcet/v1/media/urls") {
            bearerAuth(artToken);contentType(ContentType.Application.Json)
            setBody(j("requests" to listOf(j("kind" to "artwork","id" to artId,"size" to 100))).toString())
        }.obj()["urls"]!!.jsonArray.first().jsonObject["url"]
        val artworkUrl=artwork()
        Thread.sleep(10) // Exercise a different issuance millisecond while the saved grant is active.
        assertEquals(artworkUrl,artwork())
        val config=ctx.config;ctx.testing.replace(j("mediaUrlTtlSeconds" to 1));ctx.db.close()
        ServerContext(config,Db.open(config.dataFolder.resolve("dulcet.db"))).let { restarted -> assertEquals(j(),restarted.testing.settings());restarted.db.close() }
    }
    @Test fun `real checkpoint restore invalidates timeline and retains only caller late token`()=testApplication {
        val ctx=context(true);val user=ctx.users.create("reader","password",false);val admin=ctx.users.create("admin","password",true)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read","user:write"));val at=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
        application { dulcetModule(ctx) }
        val before=ctx.journal.instanceId
        val cursor=ctx.journal.cursor(user.id,"track")
        val cp=client.post("/dulcet/v1/admin/test/checkpoints") { bearerAuth(at) }.obj()
        val late=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read"))
        val lateAdmin=ctx.apiAuth.bootstrapPersonal(admin,setOf("admin"))
        val playlist=client.post("/dulcet/v1/playlists") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(j("name" to "After checkpoint").toString()) }.obj()["id"]!!.jsonPrimitive.content
        val restore=client.post("/dulcet/v1/admin/test/checkpoints/${cp["id"]!!.jsonPrimitive.content}/restore") { bearerAuth(lateAdmin) }
        assertEquals(HttpStatusCode.OK,restore.status)
        assertNotEquals(before,ctx.journal.instanceId);assertEquals(ctx.journal.instanceId,restore.obj()["instanceId"]!!.jsonPrimitive.content)
        assertNotNull(ctx.apiAuth.authenticate("Bearer $lateAdmin"));assertNull(ctx.apiAuth.authenticate("Bearer $late"))
        assertEquals(HttpStatusCode.NotFound,client.get("/dulcet/v1/playlists/$playlist") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Gone,client.get("/dulcet/v1/sync/changes?types=track&cursor=$cursor") { bearerAuth(token) }.status)
        ctx.db.close()
    }
    @Test fun `trusted inline clients are verified offline with exact redirects and reset`()=testApplication {
        val ctx=context(true);val user=ctx.users.create("reader","password",false)
        val cid="https://127.0.0.1/offline-client.json";val redirect="https://app.example/callback"
        val document=j("client_id" to cid,"client_name" to "Offline client","redirect_uris" to listOf(redirect),"extra" to true)
        ctx.testing.replace(j("trustedClients" to listOf(document),"autoApprove" to listOf(j("clientId" to cid,"userId" to user.id))))
        application { dulcetModule(ctx) }
        val browser=createClient { followRedirects=false }
        val verifier="a".repeat(43)
        val challenge=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        suspend fun authorize(uri:String)=browser.get("/oauth/authorize") {
            parameter("client_id",cid);parameter("redirect_uri",uri);parameter("scope","account user:read")
            parameter("response_type","code");parameter("state","state");parameter("code_challenge",challenge);parameter("code_challenge_method","S256")
        }
        assertEquals(HttpStatusCode.BadRequest,authorize("https://app.example/other").status)
        val auth=authorize(redirect);assertEquals(HttpStatusCode.Found,auth.status)
        val params=java.net.URI(auth.headers[HttpHeaders.Location]!!).rawQuery.split('&').associate { piece -> val parts=piece.split('=',limit=2);parts[0] to java.net.URLDecoder.decode(parts[1],"UTF-8") }
        assertNotNull(params["iss"])
        val exchange=browser.post("/oauth/token") { setBody(io.ktor.client.request.forms.FormDataContent(Parameters.build {
            append("grant_type","authorization_code");append("client_id",cid);append("redirect_uri",redirect);append("code",params["code"]!!);append("code_verifier",verifier)
        })) }
        assertEquals(HttpStatusCode.OK,exchange.status)
        assertTrue("account" in exchange.obj()["scope"]!!.jsonPrimitive.content.split(' '))
        // Inline trust is never cached as fetched data: removing it restores unverified rules.
        ctx.testing.replace(j("autoApprove" to listOf(j("clientId" to cid,"userId" to user.id))))
        assertEquals(HttpStatusCode.BadRequest,authorize(redirect).status)
        ctx.db.close()
    }
    @Test fun `delivery testing overrides validate atomically and vanish on reset`() {
        val ctx=context(true);ctx.db.use {
            val expected=j("webhooks" to j("allowPrivateTargets" to true,"retryScheduleSeconds" to listOf(0,1,2),"disableAfterFailures" to 3))
            assertEquals(expected,ctx.testing.replace(expected))
            assertTrue(ctx.testing.webhookAllowPrivateTargets)
            assertEquals(listOf(0L,1L,2L),ctx.testing.webhookRetryScheduleSeconds)
            assertEquals(3,ctx.testing.webhookDisableAfterFailures)
            for(bad in listOf(j("allowPrivateTargets" to "true"),j("retryScheduleSeconds" to listOf(-1)),j("disableAfterFailures" to 0))) {
                assertEquals(400,assertFailsWith<ApiProblem> { ctx.testing.replace(j("webhooks" to bad)) }.status)
                assertEquals(expected,ctx.testing.settings())
            }
            assertEquals(400,assertFailsWith<ApiProblem> { ctx.testing.replace(j("trustedClients" to listOf(j("client_id" to "http://127.0.0.1", "client_name" to "Bad", "redirect_uris" to listOf("https://app.example/callback"))))) }.status)
            ctx.testing.replace(j());assertFalse(ctx.testing.webhookAllowPrivateTargets);assertNull(ctx.testing.webhookRetryScheduleSeconds);assertNull(ctx.testing.webhookDisableAfterFailures)
        }
        val normal=context(false);normal.db.use {
            assertEquals(404,assertFailsWith<ApiProblem> { normal.testing.replace(j("webhooks" to j("allowPrivateTargets" to true))) }.status)
            assertFalse(normal.testing.webhookAllowPrivateTargets)
        }
    }

    @Test fun `artwork loopback override requires test mode validates boolean and resets`() {
        val ctx=context(true);ctx.db.use {
            assertFalse(ctx.testing.discoverArtworkAllowLoopback)
            ctx.testing.replace(j("discoverArtworkAllowLoopback" to true))
            assertTrue(ctx.testing.discoverArtworkAllowLoopback)
            for(value in listOf(JsonPrimitive("true"),JsonNull,JsonPrimitive(1))) {
                assertEquals(400,assertFailsWith<ApiProblem> { ctx.testing.replace(j("discoverArtworkAllowLoopback" to value)) }.status)
                assertTrue(ctx.testing.discoverArtworkAllowLoopback)
            }
            ctx.testing.replace(j());assertFalse(ctx.testing.discoverArtworkAllowLoopback)
            ctx.testing.replace(j("discoverArtworkAllowLoopback" to true))
        }
        ServerContext(ctx.config,Db.open(ctx.config.dataFolder.resolve("dulcet.db"))).let {
            assertFalse(it.testing.discoverArtworkAllowLoopback);it.db.close()
        }
        val normal=context(false);normal.db.use {
            assertEquals(404,assertFailsWith<ApiProblem> { normal.testing.replace(j("discoverArtworkAllowLoopback" to true)) }.status)
            assertFalse(normal.testing.discoverArtworkAllowLoopback)
        }
    }

}
