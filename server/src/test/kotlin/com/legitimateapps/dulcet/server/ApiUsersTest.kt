package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.v1.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ApiUsersTest {
    @Test fun accessChangesAreAtomicAndInvalidateExistingCursors() {
        val ctx = Fixtures.context(createTempDirectory("dulcet-user-access"))
        ctx.db.use {
            val user = ctx.users.create("listener", "test-password-one", false)
            assertEquals(listOf("1"), ctx.apiUsers.sourceIds(user.id))
            assertTrue(ctx.apiUsers.download(user.id))
            assertTrue(ctx.apiUsers.share(user.id))
            val cursor = ctx.journal.cursor(user.id, "track")
            val head = ctx.journal.head()
            assertFailsWith<IllegalStateException> {
                ctx.db.write { ctx.apiUsers.update(user.id, j("sourceIds" to emptyList<String>())); error("rollback") }
            }
            assertEquals(listOf("1"), ctx.apiUsers.sourceIds(user.id))
            assertEquals(head, ctx.journal.head())
            assertEquals(head, ctx.journal.decodeCursor(user.id, "track", cursor))
            ctx.apiUsers.update(user.id, j("sourceIds" to emptyList<String>(), "permissions" to j("download" to false, "futurePermission" to true)))
            assertEquals(emptyList(), ctx.apiUsers.sourceIds(user.id))
            assertFalse(ctx.apiUsers.download(user.id))
            assertTrue(ctx.apiUsers.share(user.id))
            assertEquals(JsonPrimitive(true), ctx.apiUsers.permissions(user.id)["futurePermission"])
            assertTrue(assertFailsWith<Journal.CursorException> { ctx.journal.decodeCursor(user.id, "track", cursor) }.expired)
            assertEquals("user", ctx.db.read { string("SELECT type FROM api_journal ORDER BY seq DESC LIMIT 1") })
            assertEquals(422, assertFailsWith<ApiProblem> { ctx.apiUsers.update(user.id, j("sourceIds" to listOf("unknown"))) }.status)
            assertEquals(emptyList(), ctx.apiUsers.sourceIds(user.id))
        }
    }

    @Test fun demotionAndPasswordResetRevokeDependentTokensButKeepUnrelatedGrants() {
        val ctx = Fixtures.context(createTempDirectory("dulcet-user-revoke"))
        ctx.db.use {
            ctx.users.create("other-admin", "other-admin-password", true)
            val user = ctx.users.create("changing-admin", "initial-password", true)
            val dependent = ctx.apiAuth.bootstrapPersonal(user, setOf("admin", "account"))
            val unrelated = ctx.apiAuth.bootstrapPersonal(user, setOf("user:read"))
            ctx.apiUsers.update(user.id, j("roles" to emptyList<String>()))
            assertNull(ctx.apiAuth.authenticate("Bearer $dependent"))
            assertNotNull(ctx.apiAuth.authenticate("Bearer $unrelated"))
            assertFalse(ctx.users.byId(user.id)!!.isAdmin)
            ctx.apiUsers.update(user.id, j("roles" to listOf("admin")))
            assertNull(ctx.apiAuth.authenticate("Bearer $dependent"))
            ctx.apiUsers.update(user.id, j("password" to "replacement-password"))
            assertNull(ctx.apiAuth.authenticate("Bearer $unrelated"))
            assertEquals(user.id, (ctx.users.authenticateDulcet(user.name, "replacement-password") as com.legitimateapps.dulcet.server.auth.AuthOutcome.Ok).user.id)
        }
    }

    @Test fun lastAdminCannotBeDeletedOrDemotedAndDeletedAccountsLoseLegacyHeartbeat() {
        val ctx = Fixtures.context(createTempDirectory("dulcet-user-delete"))
        ctx.db.use {
            val admin = ctx.users.create("administrator", "test-admin-password", true)
            assertEquals(409, assertFailsWith<ApiProblem> { ctx.apiUsers.remove(admin.id) }.status)
            assertEquals(409, assertFailsWith<ApiProblem> { ctx.apiUsers.update(admin.id, j("roles" to emptyList<String>())) }.status)
            val listener = ctx.apiUsers.create(j("username" to "listener", "password" to "test-listener-password", "sourceIds" to emptyList<String>()))
            val id = listener["id"]!!.jsonPrimitive.content
            ctx.db.write { execute("INSERT INTO now_playing VALUES(?,?,?,?)", id, "device", "track", 1) }
            ctx.apiUsers.remove(id)
            ctx.apiUsers.remove(id)
            assertNull(ctx.users.byId(id))
            assertEquals(0L, ctx.db.read { long("SELECT COUNT(*) FROM now_playing WHERE user_id=?", id) })
            assertNotNull(ctx.users.byId(admin.id))
        }
    }

    @Test fun administrativeRoutesRespectScopeAndExposeOnlyPublicAccountFields() = testApplication {
        val ctx = Fixtures.context(createTempDirectory("dulcet-admin-http"))
        val admin = ctx.users.create("administrator", "test-admin-password", true)
        val adminToken = ctx.apiAuth.bootstrapPersonal(admin, setOf("admin"))
        val insufficient = ctx.apiAuth.bootstrapPersonal(admin, setOf("user:read"))
        application { dulcetModule(ctx) }
        fun HttpRequestBuilder.auth(token: String = adminToken) { header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json) }
        assertEquals(HttpStatusCode.Forbidden, client.get("/dulcet/v1/users") { auth(insufficient) }.status)
        val created = client.post("/dulcet/v1/users") {
            auth(); setBody(j("username" to "listener", "password" to "test-listener-password", "displayName" to "Listener", "permissions" to j("share" to false)).toString())
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val body = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val id = body["id"]!!.jsonPrimitive.content
        assertEquals("/dulcet/v1/users/$id", created.headers[HttpHeaders.Location])
        assertEquals(JsonPrimitive(false), body["permissions"]!!.jsonObject["share"])
        assertEquals(JsonArray(listOf(JsonPrimitive("1"))), body["sourceIds"])
        assertFalse(body.keys.any { it.contains("password", true) })
        assertEquals(HttpStatusCode.Conflict, client.post("/dulcet/v1/users") { auth(); setBody(j("username" to "LISTENER", "password" to "another-password").toString()) }.status)
        val patched = client.patch("/dulcet/v1/users/$id") { auth(); contentType(ContentType.parse("application/merge-patch+json")); setBody(j("sourceIds" to emptyList<String>()).toString()) }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(patched.bodyAsText()).jsonObject["sourceIds"])
        val deleted = client.delete("/dulcet/v1/users/$id") { auth() }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/dulcet/v1/users/$id") { auth() }.status)
        ctx.db.close()
    }
}
