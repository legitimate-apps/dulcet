package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

val json: ObjectMapper = ObjectMapper()

/** One request and its response, as the suite saw them. */
class Exchange(
    val operationId: String?,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun headerValues(name: String): List<String> = headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }

    val contentType: String? get() = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()

    val json: JsonNode? by lazy {
        val type = contentType
        if (body.isEmpty() || type == null || !Contract.isJson(type)) null
        else runCatching { com.legitimateapps.dulcet.apiconformance.json.readTree(body) }.getOrNull()
    }

    fun text(): String = String(body, StandardCharsets.UTF_8)

    /** For failure messages: method, path and status only. The query is never printed. */
    fun describe(): String = "$method ${URI(url).rawPath} -> $status"
}

enum class SecretKind { TOKEN, GRANT, CANARY, PASSWORD }

/**
 * Every secret the suite holds or learns (tokens, refresh tokens, personal-token secrets, media
 * grants, canaries). The report, the console and failure messages are passed through [redact], and
 * the hygiene checks look for these values where they must never appear.
 */
class Secrets {
    private val values = LinkedHashMap<String, SecretKind>()

    @Synchronized
    fun add(value: String?, kind: SecretKind) {
        if (value != null && value.length >= 8) values[value] = kind
    }

    @Synchronized
    fun all(): Map<String, SecretKind> = LinkedHashMap(values)

    fun redact(text: String): String {
        var out = text
        for ((value, kind) in all().entries.sortedByDescending { it.key.length }) {
            out = out.replace(value, "[redacted-${kind.name.lowercase()}]")
        }
        return out
    }
}

/** A thin HTTP layer: no redirects followed, every response recorded and checked against the contract. */
class Http(private val onExchange: (Exchange) -> Unit) {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun send(
        operationId: String?,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutSeconds: Long = 60,
    ): Exchange {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeoutSeconds))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)
        builder.method(method, publisher)
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: java.io.IOException) {
            throw CheckFailure("$method ${URI(url).rawPath}: connection failed (${e.javaClass.simpleName}: ${e.message})")
        }
        val exchange = Exchange(operationId, method, url, headers, response.statusCode(), response.headers().map(), response.body() ?: ByteArray(0))
        onExchange(exchange)
        return exchange
    }

    /** A response whose body may stay open (Server-Sent Events): [stream] is set when it is a 200 event stream. */
    class Opened(val exchange: Exchange, val stream: java.io.InputStream?)

    /**
     * GETs [url] and, when the answer is a 200 `text/event-stream`, hands back the open body instead of
     * reading it; any other answer is read whole. Either way the exchange is recorded (with an empty
     * body for a stream) so the contract checks its status and media type.
     */
    fun openStream(operationId: String?, url: String, headers: Map<String, String>, timeoutSeconds: Long = 15): Opened {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(timeoutSeconds)).GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: java.io.IOException) {
            throw CheckFailure("GET ${URI(url).rawPath}: connection failed (${e.javaClass.simpleName}: ${e.message})")
        }
        val type = response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase()
        val live = response.statusCode() == 200 && type == "text/event-stream"
        val body = if (live) ByteArray(0) else response.body().use { it.readAllBytes() }
        val exchange = Exchange(operationId, "GET", url, headers, response.statusCode(), response.headers().map(), body)
        onExchange(exchange)
        return Opened(exchange, if (live) response.body() else null)
    }

    companion object {
        fun encodePath(segment: String): String =
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

        fun query(params: List<Pair<String, String?>>): String =
            params.filter { it.second != null }.joinToString("&") {
                URLEncoder.encode(it.first, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.second!!, StandardCharsets.UTF_8)
            }

        fun form(params: List<Pair<String, String?>>): ByteArray = query(params).toByteArray(StandardCharsets.UTF_8)
    }
}
