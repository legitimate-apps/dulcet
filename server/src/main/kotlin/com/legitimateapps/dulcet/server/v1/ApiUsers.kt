package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.db.Db
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.path
import kotlinx.serialization.json.*

/** Administrative account mutations and live source/permission policy shared by all v1 handlers. */
class ApiUsers(private val ctx: ServerContext) {
    private val db = ctx.db
    private val cursors = CursorCodec(ctx)

    init {
        db.write {
            execute("""CREATE TABLE IF NOT EXISTS api_user_access(
                user_id TEXT PRIMARY KEY REFERENCES user(id) ON DELETE CASCADE,
                source_ids TEXT,
                permissions TEXT NOT NULL DEFAULT '{}')""")
            for ((table, idColumn) in listOf("user" to "id", "api_user_access" to "user_id")) {
                for ((action, ref, op) in listOf(Triple("INSERT", "NEW", "upsert"), Triple("UPDATE", "NEW", "upsert"), Triple("DELETE", "OLD", "delete"))) {
                    execute("CREATE TRIGGER IF NOT EXISTS api_users_${table}_${action.lowercase()} AFTER $action ON $table BEGIN INSERT INTO api_journal(type,id,user_id,op,at) VALUES('user',$ref.$idColumn,$ref.$idColumn,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)); END")
                }
            }
        }
    }

    fun sourceIds(userId: String): List<String> = ctx.access.sourceIds(userId)
    fun permissions(userId: String): JsonObject = ctx.access.permissions(userId)
    fun download(userId: String): Boolean = ctx.access.download(userId)
    fun share(userId: String): Boolean = ctx.access.share(userId)

    fun install(route: Route) = with(route) {
        get("/users") {
            val p = admin(call)
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: invalid() } ?: 100
            if (limit !in 1..500) invalid()
            val result = db.read {
                val after = call.request.queryParameters["cursor"]?.let { raw ->
                    val decoded = cursors.decode(raw)
                    if (decoded["kind"] != JsonPrimitive("users") || decoded["user"] != JsonPrimitive(p.user.id)) invalid()
                    decoded["after"]?.jsonPrimitive?.content ?: invalid()
                } ?: ""
                val rows = query("SELECT id FROM user WHERE id>? ORDER BY id LIMIT ?", after, limit + 1) { it.string("id") }
                j("items" to rows.take(limit).map { resource(it)!! },
                    "nextCursor" to if (rows.size > limit) cursors.encode(j("kind" to "users", "user" to p.user.id, "after" to rows[limit - 1])) else null,
                    "total" to long("SELECT COUNT(*) FROM user"))
            }
            call.send(result)
        }
        get("/users/{userId}") {
            admin(call)
            call.send(resource(call.parameters["userId"]!!) ?: throw ApiProblem(404, "not-found"))
        }
        post("/users") {
            admin(call)
            val result = create(call.body())
            call.response.headers.append("Location", call.apiLocation("/dulcet/v1/users/" + result["id"]!!.jsonPrimitive.content))
            call.send(result, 201)
        }
        patch("/users/{userId}") {
            admin(call)
            call.send(update(call.parameters["userId"]!!, call.body()))
        }
        delete("/users/{userId}") {
            admin(call)
            remove(call.parameters["userId"]!!)
            call.send(JsonNull, 204)
        }
    }

    fun resource(id: String): JsonObject? = db.read {
        single("SELECT u.*,p.display_name,p.settings FROM user u LEFT JOIN api_profile p ON p.user_id=u.id WHERE u.id=?", id) {
            j("id" to id, "username" to it.string("name"), "displayName" to (it.stringOrNull("display_name") ?: it.string("name")),
                "roles" to if (it.bool("is_admin")) listOf("admin") else emptyList<String>(),
                "permissions" to permissions(id), "sourceIds" to sourceIds(id),
                "settings" to (it.stringOrNull("settings")?.let { value -> Json.parseToJsonElement(value) } ?: j("shareNowPlaying" to false)),
                "createdAt" to iso(it.long("created")))
        }
    }

    internal fun create(body: JsonObject): JsonObject = db.write {
        val username = text(body, "username")
        if (username.isBlank() || username.codePointCount(0, username.length) !in 1..64) throw ApiProblem(422, "invalid-request")
        val password = password(body)
        val role = roles(body) ?: false
        val display = body["displayName"]?.let { string(it) }
        val access = body["sourceIds"]?.let(::sources)
        val permission = body["permissions"]?.let(::permissionPatch) ?: JsonObject(emptyMap())
        validateSources(access)
        if (long("SELECT COUNT(*) FROM user WHERE name=?", username) > 0) throw ApiProblem(409, "conflict", "Username already exists")
        val user = ctx.users.create(username, password, role)
        execute("INSERT INTO api_profile(user_id,display_name) VALUES(?,?)", user.id, display)
        execute("INSERT INTO api_user_access VALUES(?,?,?)", user.id, access?.let { jArray(it).toString() }, permission.toString())
        resource(user.id)!!
    }

    internal fun update(id: String, body: JsonObject): JsonObject = db.write {
        val user = ctx.users.byId(id) ?: throw ApiProblem(404, "not-found")
        val newRole = roles(body)
        val newPassword = if ("password" in body) password(body) else null
        val display = body["displayName"]?.let { string(it) }
        val newSources = body["sourceIds"]?.let(::sources)
        val permission = body["permissions"]?.let(::permissionPatch)
        validateSources(newSources)
        if (user.isAdmin && newRole == false) protectLastAdmin(id)
        val oldSources = sourceIds(id).toSet()
        val oldPermissions = permissions(id)
        execute("INSERT OR IGNORE INTO api_user_access(user_id) VALUES(?)", id)
        if (newSources != null) {
            execute("UPDATE api_user_access SET source_ids=? WHERE user_id=?", jArray(newSources).toString(), id)
            if (oldSources != newSources.toSet()) advanceVisibility()
        }
        if (permission != null) {
            val changed = JsonObject(oldPermissions + permission)
            execute("UPDATE api_user_access SET permissions=? WHERE user_id=?", changed.toString(), id)
            if (oldPermissions["share"] == JsonPrimitive(true) && changed["share"] == JsonPrimitive(false)) {
                execute("INSERT OR IGNORE INTO api_profile(user_id) VALUES(?)", id)
                val settings = Json.parseToJsonElement(string("SELECT settings FROM api_profile WHERE user_id=?", id)!!).jsonObject
                execute("UPDATE api_profile SET settings=? WHERE user_id=?", JsonObject(settings + j("shareNowPlaying" to false)).toString(), id)
            }
        }
        if ("displayName" in body) {
            execute("INSERT OR IGNORE INTO api_profile(user_id) VALUES(?)", id)
            execute("UPDATE api_profile SET display_name=? WHERE user_id=?", display, id)
        }
        if (newRole != null && newRole != user.isAdmin) {
            execute("UPDATE user SET is_admin=? WHERE id=?", newRole, id)
            if (!newRole) {
                execute("""WITH RECURSIVE affected(id) AS (
                    SELECT id FROM api_grant WHERE user_id=? AND (' '||scopes||' ') LIKE '% admin %'
                    UNION ALL SELECT g.id FROM api_grant g JOIN affected a ON g.parent_id=a.id)
                    UPDATE api_grant SET revoked=1 WHERE id IN (SELECT id FROM affected)""", id)
                execute("UPDATE api_credential SET retry_pair=NULL WHERE grant_id IN (SELECT id FROM api_grant WHERE user_id=? AND revoked=1)", id)
                execute("DELETE FROM api_authorization WHERE user_id=? AND (' '||scopes||' ') LIKE '% admin %'", id)
            }
        }
        if (newPassword != null) {
            ctx.users.setPassword(user.name, newPassword)
            execute("UPDATE api_credential SET retry_pair=NULL WHERE grant_id IN (SELECT id FROM api_grant WHERE user_id=?)", id)
        }
        resource(id)!!
    }

    internal fun remove(id: String) = db.write {
        val user = ctx.users.byId(id) ?: return@write
        if (user.isAdmin) protectLastAdmin(id)
        execute("DELETE FROM now_playing WHERE user_id=?", id)
        execute("DELETE FROM user WHERE id=?", id)
    }

    private fun Db.Session.protectLastAdmin(id: String) {
        if (long("SELECT COUNT(*) FROM user WHERE is_admin=1 AND id<>?", id) == 0L) throw ApiProblem(409, "conflict", "The last administrator cannot be removed")
    }
    private fun Db.Session.advanceVisibility() {
        execute("INSERT INTO meta(key,value) VALUES('api_visibility_epoch','1') ON CONFLICT(key) DO UPDATE SET value=CAST(CAST(value AS INTEGER)+1 AS TEXT)")
    }
    private fun Db.Session.validateSources(ids: List<String>?) {
        if (ids != null && ids.any { long("SELECT COUNT(*) FROM music_folder WHERE CAST(id AS TEXT)=?", it) == 0L }) throw ApiProblem(422, "invalid-request", "Unknown source")
    }
    private fun admin(call: ApplicationCall): Principal = call.principal(ctx, "admin").also {
        if (!it.user.isAdmin) throw ApiProblem(403, "permission-denied")
    }
    private fun roles(body: JsonObject): Boolean? = body["roles"]?.let { value ->
        val names = (value as? JsonArray)?.map(::string) ?: invalid()
        if (names.any { it != "admin" }) throw ApiProblem(422, "invalid-request", "Unsupported role")
        "admin" in names
    }
    private fun sources(value: JsonElement): List<String> = ((value as? JsonArray) ?: invalid()).map(::string).distinct().sorted()
    private fun permissionPatch(value: JsonElement): JsonObject = (value as? JsonObject)?.also { fields ->
        if (fields.values.any { it !is JsonPrimitive || it.isString || it.booleanOrNull == null }) invalid()
    } ?: invalid()
    private fun password(body: JsonObject): String = text(body, "password").also {
        if (it.codePointCount(0, it.length) < 8) throw ApiProblem(422, "invalid-request", "Password must have at least eight characters")
    }
    private fun text(body: JsonObject, name: String): String = body[name]?.let(::string) ?: invalid()
    private fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun jArray(value: List<String>): JsonArray = JsonArray(value.map(::JsonPrimitive))
    private fun invalid(): Nothing = throw ApiProblem(400, "invalid-request")
}
