package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.SERVER_VERSION
import com.legitimateapps.dulcet.server.auth.AuthOutcome
import com.legitimateapps.dulcet.server.auth.User
import com.legitimateapps.dulcet.server.auth.Users
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.request.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.http.HttpMethod
import org.slf4j.LoggerFactory

/** A Subsonic error: answered as a `status="failed"` envelope with this code. */
class SubsonicError(val code: Int, override val message: String) : Exception(message) {
    companion object {
        fun missing(name: String) = SubsonicError(10, "Required parameter is missing: $name")
        fun notFound(what: String = "Data") = SubsonicError(70, "$what not found")
        fun notAuthorized() = SubsonicError(50, "User is not authorized for the given operation")
        val wrongCredentials = SubsonicError(40, "Wrong username or password")
    }
}

enum class Format { XML, JSON, JSONP }

/** One `/rest` request, authenticated, with its parameters merged from the query and a form body. */
class Call(
    val ktor: ApplicationCall,
    val method: String,
    private val parameters: Map<String, List<String>>,
    val format: Format,
    val callback: String?,
    val user: User,
    val body: String?,
) {
    fun param(name: String): String? = parameters[name]?.firstOrNull()
    fun params(name: String): List<String> = parameters[name].orEmpty()
    fun required(name: String): String = param(name)?.takeIf(String::isNotEmpty) ?: throw SubsonicError.missing(name)

    fun int(name: String, default: Int): Int = param(name)?.toIntOrNull() ?: default
    fun intOrNull(name: String): Int? = param(name)?.toIntOrNull()
    fun long(name: String): Long? = param(name)?.toLongOrNull()
    fun bool(name: String, default: Boolean): Boolean = param(name)?.let { it.equals("true", true) || it == "1" } ?: default

    /** `size`-style parameter clamped to [0, max]. */
    fun size(name: String, default: Int, max: Int = 500): Int = int(name, default).coerceIn(0, max)
}

sealed interface Reply {
    class Envelope(val node: Node) : Reply
    /** The handler already answered (binary bodies). */
    data object Handled : Reply
}

typealias Handler = suspend (Call) -> Reply

object Envelope {
    val JSONP_CALLBACK = Regex("[A-Za-z_$][A-Za-z0-9_$.]*")
    const val API_VERSION = "1.16.1"
    const val NAMESPACE = "http://subsonic.org/restapi"

    fun root(status: String): Node = Node("subsonic-response").apply {
        this["status"] = status
        this["version"] = API_VERSION
        this["type"] = "dulcet"
        this["serverVersion"] = SERVER_VERSION
        this["openSubsonic"] = true
    }

    fun error(code: Int, message: String): Node = root("failed").apply {
        child("error") {
            this["code"] = code
            this["message"] = message
        }
    }

    fun render(node: Node, format: Format, callback: String?): TextContent = when (format) {
        Format.XML -> {
            val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            node.writeXml(out, NAMESPACE)
            TextContent(out.toString(), ContentType.parse("application/xml; charset=utf-8"))
        }
        Format.JSON -> TextContent(
            kotlinx.serialization.json.JsonObject(mapOf("subsonic-response" to node.toJson())).toString(),
            ContentType.parse("application/json; charset=utf-8"),
        )
        Format.JSONP -> TextContent(
            "${callback?.takeIf { JSONP_CALLBACK.matches(it) } ?: "callback"}(" + kotlinx.serialization.json.JsonObject(mapOf("subsonic-response" to node.toJson())) + ");",
            ContentType.parse("application/javascript; charset=utf-8"),
        )
    }
}

/**
 * The `/rest` dispatcher. Every endpoint answers at `/rest/<name>` and `/rest/<name>.view`, by
 * GET or by POST with a form body (the `formPost` extension). Authentication is checked here once;
 * only `getOpenSubsonicExtensions` is open, as the OpenSubsonic spec requires.
 */
class RestApi(private val users: Users, private val handlers: Map<String, Handler>, private val open: Set<String>, private val clientAddresses: com.legitimateapps.dulcet.server.auth.ClientAddresses = com.legitimateapps.dulcet.server.auth.ClientAddresses()) {
    private val log = LoggerFactory.getLogger(RestApi::class.java)
    private val failures = LinkedHashMap<String, Pair<Int, Long>>()
    private fun blocked(peer: String): Boolean = synchronized(failures) {
        val entry = failures[peer] ?: return@synchronized false
        if (System.nanoTime() - entry.second >= 60_000_000_000L) { failures.remove(peer); false } else entry.first >= 10
    }
    private fun recordFailure(peer: String) = synchronized(failures) {
        if (failures.size >= 4096 && peer !in failures) failures.remove(failures.keys.first())
        val prior = failures[peer]?.takeIf { System.nanoTime() - it.second < 60_000_000_000L }
        failures[peer] = ((prior?.first ?: 0) + 1) to (prior?.second ?: System.nanoTime())
    }

    fun install(route: Route) {
        route.route("/rest/{method}") {
            handle { dispatch(call) }
        }
    }

    private suspend fun dispatch(call: ApplicationCall) {
        val method = call.parameters["method"].orEmpty().removeSuffix(".view")
        val merged = LinkedHashMap<String, MutableList<String>>()
        // Parsed here rather than by Ktor, whose parsers keep only the first 1000 parameters: a
        // playlist replace sends one songId per entry.
        fun merge(encoded: String) = encoded.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            fun decode(text: String) = runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            merged.getOrPut(key) { ArrayList() }.add(if (eq < 0) "" else decode(pair.substring(eq + 1)))
        }
        merge(call.request.queryString())
        var body: String? = null
        if (call.request.httpMethod == HttpMethod.Post) {
            val type = call.request.contentType()
            if (type.match(ContentType.Application.FormUrlEncoded)) merge(call.receiveText())
            else body = call.receiveText()
        }
        val format = when (merged["f"]?.firstOrNull()) {
            "json" -> Format.JSON
            "jsonp" -> Format.JSONP
            else -> Format.XML
        }
        val callback = merged["callback"]?.firstOrNull()
        if (format == Format.JSONP && callback != null && !Envelope.JSONP_CALLBACK.matches(callback)) {
            call.respond(Envelope.render(Envelope.error(10, "Invalid JSONP callback"), Format.JSON, null))
            return
        }
        val handler = handlers[method]
        if (handler == null) {
            call.respondText("404 page not found\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return
        }
        val reply: Node = try {
            val user = if (method in open) anonymous else authenticate(merged, clientAddresses.resolve(call))
            when (val result = handler(Call(call, method, merged, format, callback, user, body))) {
                is Reply.Envelope -> result.node
                Reply.Handled -> return
            }
        } catch (error: com.legitimateapps.dulcet.server.media.TranscodeBusyException) {
            call.response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, "1")
            call.respondText("Transcode capacity reached", ContentType.Text.Plain, HttpStatusCode.TooManyRequests)
            return
        } catch (error: SubsonicError) {
            Envelope.error(error.code, error.message)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // Never echo the request (its query carries credentials); the method name is enough.
            log.error("{} failed: {}", method, error.toString())
            Envelope.error(0, "Internal server error")
        }
        call.respond(Envelope.render(reply, format, callback))
    }

    private fun authenticate(parameters: Map<String, List<String>>, peer: String): User {
        fun one(name: String) = parameters[name]?.firstOrNull()
        if (one("apiKey") != null) throw SubsonicError(42, "Authentication mechanism not supported")
        val name = one("u")?.takeIf(String::isNotEmpty) ?: throw SubsonicError.missing("u")
        if (one("v").isNullOrEmpty()) throw SubsonicError.missing("v")
        if (one("c").isNullOrEmpty()) throw SubsonicError.missing("c")
        val token = one("t")
        val salt = one("s")
        val password = one("p")
        if (blocked(peer)) throw SubsonicError.wrongCredentials
        val outcome = when {
            token != null && salt != null -> users.authenticateToken(name, token, salt)
            password != null -> users.authenticatePassword(name, password)
            else -> throw SubsonicError(10, "Required parameter is missing: p or t and s")
        }
        return when (outcome) {
            is AuthOutcome.Ok -> outcome.user
            AuthOutcome.WrongCredentials -> { recordFailure(peer); throw SubsonicError.wrongCredentials }
        }
    }

    companion object {
        val anonymous = User(id = "", name = "", isAdmin = false)
    }
}

fun ok(build: Node.() -> Unit = {}): Reply = Reply.Envelope(Envelope.root("ok").apply(build))
