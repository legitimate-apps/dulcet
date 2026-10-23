package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import com.legitimateapps.dulcet.server.discover.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class WishlistMcpTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private fun input()=j("kind" to "song","externalIds" to j("musicbrainzRecordingId" to id),"snapshot" to j("title" to "Song"),"source" to j("type" to "api"),"operationId" to UUID.randomUUID().toString())
    @Test fun toolsExposeOnlyEnabledCapabilitiesAndCorrectScopes() {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-mcp-tools"))
        try {
            val user=ctx.users.create("user","password",false)
            fun tools(scopes:Set<String>)=ctx.apiMcp.tools(Principal(user,"",null,scopes))
            fun names(scopes:Set<String>)=tools(scopes).map {it["name"]!!.jsonPrimitive.content}
            assertFalse("search_discover" in names(setOf("catalog:read")))
            assertTrue("list_wishes" in names(setOf("user:read")));assertTrue("add_wish" in names(setOf("user:write")))
            assertFalse("list_wishes" in names(setOf("user:write")))
            val add=tools(setOf("user:write")).single{it["name"]==JsonPrimitive("add_wish")}
            assertEquals(JsonPrimitive(false),add["annotations"]!!.jsonObject["readOnlyHint"]);assertEquals(JsonPrimitive(false),add["annotations"]!!.jsonObject["destructiveHint"]);assertEquals(JsonPrimitive(true),add["annotations"]!!.jsonObject["idempotentHint"])
            assertTrue(names(API_SCOPES).none {it.contains("wish") && it !in setOf("list_wishes","add_wish")})
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun sameResourcesAndDurableAddAcrossProtocols() = testApplication {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-mcp-add"))
        try {
            val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("user:write","user:read"));application{dulcetModule(ctx)}
            suspend fun rpc(method:String,params:JsonObject=j(),session:String?=null)=client.post("/dulcet/v1/mcp") {
                bearerAuth(token);contentType(ContentType.Application.Json);header("Accept","application/json, text/event-stream");session?.let{header("Mcp-Session-Id",it)}
                setBody(j("jsonrpc" to "2.0","id" to 1,"method" to method,"params" to params).toString())
            }
            val session=rpc("initialize").headers["Mcp-Session-Id"]!!;val input=input()
            suspend fun call(name:String,args:JsonObject)=Json.parseToJsonElement(rpc("tools/call",j("name" to name,"arguments" to args),session).bodyAsText()).jsonObject["result"]!!.jsonObject
            val first=call("add_wish",input);assertEquals(JsonPrimitive(false),first["isError"],first.toString())
            val wish=first["structuredContent"]!!.jsonObject["wish"]!!.jsonObject;val wishId=wish["id"]!!.jsonPrimitive.content
            assertEquals(wishId,call("add_wish",input)["structuredContent"]!!.jsonObject["wish"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val api=client.get("/dulcet/v1/me/wishes/$wishId"){bearerAuth(token)};assertEquals(wishId,Json.parseToJsonElement(api.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content)
            val rest=client.get("/rest/getDulcetWish?u=user&p=password&v=1.16.1&c=test&f=json&id=$wishId");assertTrue(rest.bodyAsText().contains(wishId))
            ctx.db.write{execute("UPDATE wishlist_wish SET state='future-state' WHERE id=?",wishId)}
            assertEquals(JsonPrimitive(false),call("list_wishes",j())["isError"])
            val invalid=Json.parseToJsonElement(rpc("tools/call",j("name" to "list_wishes","arguments" to j("limit" to 101)),session).bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(-32602),invalid["error"]!!.jsonObject["code"])
            assertEquals(1,ctx.db.read{long("SELECT count(*) FROM wishlist_wish")})
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun inputAndOutputSchemasAreBoundedAndStateIsOpen() {
        val ctx=Fixtures.context(Files.createTempDirectory("wish-mcp-schema"))
        try {
            val defs=ctx.apiMcp.definitions.associateBy{it["name"]!!.jsonPrimitive.content}
            val add=defs.getValue("add_wish")["inputSchema"]!!.jsonObject
            assertTrue(ctx.apiMcp.valid(input(),add));assertFalse(ctx.apiMcp.valid(JsonObject(input()-"operationId"),add))
            assertFalse(ctx.apiMcp.valid(JsonObject(input()+("operationId" to JsonPrimitive("bad"))),add))
            val search=defs.getValue("search_discover")["inputSchema"]!!.jsonObject
            assertFalse(ctx.apiMcp.valid(j("query" to "x","kind" to "artist","limit" to 101),search))
            assertTrue(ctx.apiMcp.valid(j("query" to "🎵".repeat(500),"kind" to "artist"),search))
        } finally {ctx.close();ctx.db.close()}
    }
    @Test fun enabledSearchUsesFixtureAndDisabledCallIsUnavailable() = testApplication {
        FixtureDiscoverUpstream().use { f ->
            val template=Fixtures.context(Files.createTempDirectory("wish-mcp-search"));val config=template.config.copy(discover=DiscoverSettings(musicBrainzUrl=f.baseUrl,musicBrainzMinGapMillis=0,coverArtArchiveUrl=f.baseUrl,listenBrainzUrl=f.baseUrl,listenBrainzLabsUrl=f.baseUrl,wishlistEnabled=false));template.close();template.db.close()
            val ctx=ServerContext(config,com.legitimateapps.dulcet.server.db.Db.inMemory())
            try {
                val user=ctx.users.create("user","password",false);val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:write"));application{dulcetModule(ctx)}
                f.json("/ws/2/artist","""{"count":1,"artists":[{"id":"$id","name":"Artist"}]}""")
                assertTrue(ctx.apiMcp.tools(Principal(user,"",null,setOf("catalog:read"))).any{it["name"]==JsonPrimitive("search_discover")})
                assertFalse(ctx.apiMcp.tools(Principal(user,"",null,setOf("user:write"))).any{it["name"]==JsonPrimitive("add_wish")})
                suspend fun rpc(method:String,params:JsonObject=j(),session:String?=null)=client.post("/dulcet/v1/mcp") {
                    bearerAuth(token);contentType(ContentType.Application.Json);session?.let{header("Mcp-Session-Id",it)};setBody(j("jsonrpc" to "2.0","id" to 1,"method" to method,"params" to params).toString())
                }
                val session=rpc("initialize").headers["Mcp-Session-Id"]!!
                val result=Json.parseToJsonElement(rpc("tools/call",j("name" to "search_discover","arguments" to j("kind" to "artist","query" to "a")),session).bodyAsText()).jsonObject["result"]!!.jsonObject
                assertEquals(JsonPrimitive(false),result["isError"]);assertEquals(1,result["structuredContent"]!!.jsonObject["items"]!!.jsonArray.size);assertEquals(1,f.requests.size)
                val disabled=Json.parseToJsonElement(rpc("tools/call",j("name" to "add_wish","arguments" to input()),session).bodyAsText()).jsonObject
                assertEquals(JsonPrimitive(-32602),disabled["error"]!!.jsonObject["code"])
            } finally {ctx.close();ctx.db.close()}
        }
    }

}
