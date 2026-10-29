package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Stable provenance. A transport address is never an account or media ownership identity. */
public data class OwnershipAnchor(
    val accountId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val serverIdentity: String,
) {
    override fun toString(): String = "OwnershipAnchor(<redacted>)"
}

public data class ConnectionRoute(
    val id: String,
    val baseUrl: String,
    val label: String?,
    val role: String,
    val priority: Int,
    val enabled: Boolean = false,
    val removed: Boolean = false,
) {
    override fun toString(): String = "ConnectionRoute(id=$id, enabled=$enabled, removed=$removed)"
}

public data class ConnectionAuthAlias(val routeId: String, val issuer: String, val tokenEndpoint: String) {
    override fun toString(): String = "ConnectionAuthAlias(routeId=$routeId)"
}

internal data class ConnectionDirectory(
    val revision: Long,
    val instanceId: String,
    val principalId: String,
    val username: String,
    val routes: List<ConnectionRoute>,
    val legacyOwnershipAnchors: List<String>,
    val authAliases: List<ConnectionAuthAlias>,
    val json: String,
)

public data class ConnectionSnapshot(
    val anchor: OwnershipAnchor,
    val routes: List<ConnectionRoute>,
    val selectedRouteId: String?,
    val revision: Long,
    val enrolled: Boolean,
)

internal data class RouteLease(
    val baseUrl: String,
    val routeId: String,
    val instanceId: String,
    val revision: Long,
    val generation: Long,
) {
    override fun toString(): String = "RouteLease(routeId=$routeId, revision=$revision, generation=$generation)"
}

/** Immutable atomic publications let native media workers consult the same route at dispatch. */
@OptIn(ExperimentalAtomicApi::class)
internal object ConnectionRouteRegistry {
    private data class Entry(val accountId: String, val lease: RouteLease?)
    private val entries = AtomicReference<Map<Pair<String, String>, Entry>>(emptyMap())
    private fun key(base: String, user: String): Pair<String, String> =
        (try { canonicalConnectionBase(base, allowHttp = true) } catch (_: ConnectionEnrollmentFailure) { base }) to user
    fun isEnrolled(anchorBaseUrl: String, username: String): Boolean = entries.load().containsKey(key(anchorBaseUrl, username))
    fun leaseFor(anchorBaseUrl: String, username: String): RouteLease? = entries.load()[key(anchorBaseUrl, username)]?.lease
    fun publish(anchor: OwnershipAnchor, lease: RouteLease?) {
        change { it + (key(anchor.normalizedBaseUrl, anchor.username) to Entry(anchor.accountId, lease)) }
    }
    fun forget(accountId: String) { change { it.filterValues { entry -> entry.accountId != accountId } } }
    private fun change(block: (Map<Pair<String, String>, Entry>) -> Map<Pair<String, String>, Entry>) {
        while (true) { val before = entries.load(); if (entries.compareAndSet(before, block(before))) return }
    }
}

/** Strict exact-base canonicalization, shared by directory, legacy attestation and registry keys. */
internal fun canonicalConnectionBase(value: String, allowHttp: Boolean = false): String {
    if (value.length > 2048 || value.isBlank() || value.any { it.isWhitespace() || it.isISOControl() } ||
        '\\' in value || '?' in value || '#' in value || '@' in value || '%' in value
    ) throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
    val url = try { Url(value) } catch (_: IllegalArgumentException) { throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope) }
    if (url.protocol.name !in (if (allowHttp) setOf("http", "https") else setOf("https")) ||
        !value.startsWith("${url.protocol.name}://", ignoreCase = true) || url.host.isBlank() ||
        url.host.any { it.code > 127 } || url.user != null || url.password != null || url.port !in 1..65535 ||
        url.encodedPath.split('/').any { it == "." || it == ".." } || "//" in url.encodedPath
    ) throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
    val host = if (':' in url.host) "[${url.host.lowercase()}]" else url.host.lowercase()
    val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
    return "${url.protocol.name}://$host$port${url.encodedPath.trimEnd('/')}"
}

internal class ConnectionEnrollmentFailure(val error: DomainError) : Exception("Connection enrollment rejected")

internal object ConnectionDirectoryParser {
    const val MAX_BYTES = 65_536
    fun parse(body: ByteArray, expectedInstance: String, expectedUsername: String): ConnectionDirectory {
        if (body.size > MAX_BYTES) throw ConnectionEnrollmentFailure(DomainError.Protocol.TooLarge)
        val payload = try {
            LibraryEndpointResponse(200, body.decodeToString(throwOnInvalidSequence = true), "").okPayload()
        } catch (failure: LibraryRequestFailure) { throw ConnectionEnrollmentFailure(failure.error) }
        catch (_: Exception) { throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope) }
        val value = payload["dulcetConnections"] as? JsonObject ?: bad()
        if (value["version"]?.jsonPrimitive?.intOrNull != 1) bad()
        val revision = (value["revision"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: bad()
        val instance = value.text("instanceId")
        val principal = value["principal"] as? JsonObject ?: bad()
        val username = principal.text("username")
        if (instance != expectedInstance || username != expectedUsername || expectedInstance.isEmpty()) {
            throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
        }
        val rawRoutes = value["routes"] as? JsonArray ?: bad()
        if (rawRoutes.isEmpty() || rawRoutes.size > 8) bad()
        val ids = mutableSetOf<String>(); val bases = mutableSetOf<String>()
        val routes = rawRoutes.mapNotNull { element ->
            val route = element as? JsonObject ?: bad()
            val id = route.text("id"); if (!ids.add(id)) bad()
            val rawBase = route.text("baseUrl")
            val base = canonicalConnectionBase(rawBase, allowHttp = true)
            if (!bases.add(base)) bad()
            val role = route.text("role").takeIf { it in setOf("local", "private", "public") } ?: bad()
            val priority = (route["priority"] as? JsonPrimitive)?.intOrNull ?: bad()
            val label = route["label"]?.let { if (it is JsonNull) null else route.text("label") }
            // Even explicit consent cannot turn advertised HTTP into a routable alias.
            if (base.startsWith("http:")) null else ConnectionRoute(id, base, label, role, priority)
        }
        if (routes.isEmpty()) bad()
        val legacy = value.optionalArray("legacyOwnershipAnchors").map {
            canonicalConnectionBase((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: bad(), allowHttp = true)
        }
        if (legacy.size > 8 || legacy.distinct().size != legacy.size || legacy.any { !it.startsWith("http:") }) bad()
        val aliases = value.optionalArray("authAliases").map {
            val alias = it as? JsonObject ?: bad()
            val routeId = alias.text("routeId")
            val route = routes.singleOrNull { r -> r.id == routeId } ?: bad()
            val issuer = canonicalConnectionBase(alias.text("issuer"))
            val token = alias.text("tokenEndpoint")
            if (issuer != route.baseUrl || token != "${route.baseUrl}/oauth/token") bad()
            ConnectionAuthAlias(routeId, issuer, token)
        }
        if (aliases.size > 8 || aliases.map { it.routeId }.distinct().size != aliases.size) bad()
        return ConnectionDirectory(revision, instance, principal.text("id"), username, routes, legacy, aliases, value.toString())
    }
    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.takeIf {
        it.isString && it.content.isNotBlank() && it.content.length <= 2048 && it.content.none(Char::isISOControl)
    }?.content ?: bad()
    private fun JsonObject.optionalArray(name: String): JsonArray = if (name !in this) JsonArray(emptyList()) else this[name] as? JsonArray ?: bad()
    private fun bad(): Nothing = throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
}
