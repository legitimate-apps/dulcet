package com.legitimateapps.dulcet.core

import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
) {
    override fun toString(): String = "ConnectionDirectory(revision=$revision, routes=${routes.size})"
}

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
    val attemptGeneration: Long = 0,
) {
    override fun toString(): String = "RouteLease(routeId=$routeId, revision=$revision, generation=$generation)"
}

internal data class RouteSelectionSnapshot(val enrolled: Boolean, val lease: RouteLease?)

/** Immutable atomic publications let native media workers consult the same route at dispatch. */
@OptIn(ExperimentalAtomicApi::class)
internal object ConnectionRouteRegistry {
    private data class Configuration(val candidates: List<RouteCandidate>)
    private data class Authority(val routeId: String, val baseUrl: String, val instanceId: String,
        val firstGeneration: Long, val lastGeneration: Long)
    private fun RouteLease.authority() = Authority(routeId, baseUrl, instanceId, generation, generation)
    private data class Entry(
        val accountId: String, val generation: Long, val lease: RouteLease?,
        val authorized: Set<RouteLease> = setOfNotNull(lease),
        val authority: Set<Authority> = setOfNotNull(lease?.authority()),
        val configuration: Configuration? = null,
        val coordinator: ServerRouteCoordinator? = null,
        val scope: CoroutineScope? = null,
    )
    internal val changes = MutableStateFlow(0L)
    private fun changed() { changes.update { it + 1 } }
    fun displayed(snapshot: ConnectionSnapshot): ConnectionSnapshot {
        val entry = entries.load()[key(snapshot.anchor.normalizedBaseUrl, snapshot.anchor.username)]
        return if (entry?.accountId == snapshot.anchor.accountId && snapshot.enrolled)
            snapshot.copy(selectedRouteId = entry.lease?.routeId) else snapshot
    }
    private val entries = AtomicReference<Map<Pair<String, String>, Entry>>(emptyMap())
    private fun key(base: String, user: String): Pair<String, String> =
        (try { canonicalConnectionBase(base, allowHttp = true) } catch (_: ConnectionEnrollmentFailure) { base }) to user
    fun selectionFor(anchorBaseUrl: String, username: String): RouteSelectionSnapshot {
        val entry = entries.load()[key(anchorBaseUrl, username)]
        return RouteSelectionSnapshot(entry != null, entry?.lease)
    }
    fun isEnrolled(anchorBaseUrl: String, username: String): Boolean = selectionFor(anchorBaseUrl, username).enrolled
    fun leaseFor(anchorBaseUrl: String, username: String): RouteLease? = selectionFor(anchorBaseUrl, username).lease
    fun publish(anchor: OwnershipAnchor, lease: RouteLease?, generation: Long? = lease?.generation): Boolean {
        val key = key(anchor.normalizedBaseUrl, anchor.username)
        while (true) {
            val before = entries.load()
            val current = before[key]?.takeIf { it.accountId == anchor.accountId }
            val epoch = generation ?: ((current?.generation ?: 0) + 1)
            require(lease == null || lease.generation == epoch)
            if (current != null && (epoch < current.generation ||
                    epoch == current.generation && current.lease == null && lease != null)) return false
            // Conflicting publications at one generation cannot confer credential authority.
            val accepted = if (current != null && epoch == current.generation &&
                current.lease != null && lease != null && current.lease != lease) null else lease
            val after = before + (key to Entry(anchor.accountId, epoch, accepted))
            if (entries.compareAndSet(before, after)) {
                changed()
                before[key]?.scope?.cancel()
                return accepted == lease
            }
        }
    }
    /** Publish one consistent database snapshot; delayed restores cannot resurrect revoked routes. */
    fun publishConfiguration(anchor: OwnershipAnchor, selected: RouteLease?, generation: Long,
        candidates: List<RouteCandidate>, probe: ConnectionCandidateProbe = TlsConnectionCandidateProbe(),
    ): Boolean {
        val key = key(anchor.normalizedBaseUrl, anchor.username)
        val configuration = Configuration(candidates)
        while (true) {
            val before = entries.load()
            val current = before[key]?.takeIf { it.accountId == anchor.accountId }
            if (current != null && generation < current.generation) return false
            if (current != null && generation == current.generation) {
                // Reopening a database handle is not a topology event or a preference override.
                if (current.configuration == configuration) return true
                return false
            }
            val live = candidates.filter { !it.route.removed }
            val retained = current?.authority.orEmpty().filter { old ->
                live.any { it.lease.routeId == old.routeId && it.lease.baseUrl == old.baseUrl &&
                    it.lease.instanceId == old.instanceId }
            }.mapTo(mutableSetOf()) { it.copy(lastGeneration = generation) }
            selected?.let { admitted ->
                if (retained.none { it.routeId == admitted.routeId && it.baseUrl == admitted.baseUrl })
                    retained.add(admitted.authority())
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val coordinator = ServerRouteCoordinator(live, scope, probe,
                { lease -> selectAdmitted(anchor, generation, lease) },
                topologyVersion = { ConnectionNetworkEvents.generation() },
                quarantine = { lease -> quarantineRoute(anchor, generation, lease) })
            val after = before + (key to Entry(anchor.accountId, generation, selected, setOfNotNull(selected), retained,
                configuration, coordinator, scope))
            if (entries.compareAndSet(before, after)) {
                changed()
                before[key]?.scope?.cancel()
                return true
            }
            scope.cancel()
        }
    }

    private fun quarantineRoute(anchor: OwnershipAnchor, generation: Long, lease: RouteLease) {
        val key = key(anchor.normalizedBaseUrl, anchor.username)
        while (true) {
            val before = entries.load()
            val current = before[key] ?: return
            if (current.accountId != anchor.accountId || current.generation != generation) return
            val after = before + (key to current.copy(
                lease = current.lease?.takeUnless { it.routeId == lease.routeId },
                authorized = current.authorized.filterTo(mutableSetOf()) { it.routeId != lease.routeId },
                authority = current.authority.filterTo(mutableSetOf()) { it.routeId != lease.routeId }))
            if (entries.compareAndSet(before, after)) { changed(); return }
        }
    }

    private fun selectAdmitted(anchor: OwnershipAnchor, generation: Long, lease: RouteLease): Boolean {
        val key = key(anchor.normalizedBaseUrl, anchor.username)
        while (true) {
            val before = entries.load()
            val current = before[key] ?: return false
            if (current.accountId != anchor.accountId || current.generation != generation ||
                current.configuration?.candidates?.none {
                    it.lease == lease.copy(attemptGeneration = 0) && !it.route.removed
                } != false) return false
            val authority = current.authority.takeIf { values ->
                values.any { it.routeId == lease.routeId && it.baseUrl == lease.baseUrl }
            } ?: (current.authority + lease.authority())
            val after = before + (key to current.copy(lease = lease, authority = authority,
                authorized = current.authorized.filterTo(mutableSetOf()) { it.routeId != lease.routeId } + lease))
            if (entries.compareAndSet(before, after)) { changed(); return true }
        }
    }

    suspend fun acquireSelection(anchorBaseUrl: String, username: String): RouteSelectionSnapshot {
        // A configuration replacement may cancel a suspended flight; consult the newer owner once.
        repeat(2) {
            val entry = entries.load()[key(anchorBaseUrl, username)] ?: return RouteSelectionSnapshot(false, null)
            val coordinator = entry.coordinator ?: return RouteSelectionSnapshot(true, entry.lease)
            val acquired = coordinator.acquire()
            if (entries.load()[key(anchorBaseUrl, username)]?.coordinator === coordinator) {
                if (acquired == null) clearFailedSelection(anchorBaseUrl, username, coordinator, entry.lease)
                return RouteSelectionSnapshot(true, acquired)
            }
        }
        return RouteSelectionSnapshot(true, null)
    }

    fun authorizes(anchorBaseUrl: String, username: String, routeBaseUrl: String, generation: Long): Boolean =
        entries.load()[key(anchorBaseUrl, username)]?.authority?.any {
            it.baseUrl == routeBaseUrl && generation in it.firstGeneration..it.lastGeneration
        } == true

    private fun clearFailedSelection(anchorBaseUrl: String, username: String, coordinator: ServerRouteCoordinator,
        lease: RouteLease?,
    ) {
        val key = key(anchorBaseUrl, username)
        while (true) {
            val before = entries.load(); val entry = before[key] ?: return
            if (entry.coordinator !== coordinator || entry.lease == null || entry.lease != lease) return
            if (entries.compareAndSet(before, before + (key to entry.copy(lease = null)))) { changed(); return }
        }
    }

    suspend fun transportFailed(anchorBaseUrl: String, username: String, lease: RouteLease) {
        val coordinator = entries.load()[key(anchorBaseUrl, username)]?.coordinator ?: return
        coordinator.transportFailed(lease)
        clearFailedSelection(anchorBaseUrl, username, coordinator, lease)
    }

    suspend fun reportCapturedFailure(anchorBaseUrl: String, username: String, routeBaseUrl: String,
        generation: Long, attemptGeneration: Long,
    ) {
        val entry = entries.load()[key(anchorBaseUrl, username)] ?: return
        val lease = entry.authorized.singleOrNull { it.baseUrl == routeBaseUrl &&
            it.generation == generation && it.attemptGeneration == attemptGeneration } ?: return
        transportFailed(anchorBaseUrl, username, lease)
    }

    suspend fun topologyChanged(anchorBaseUrl: String, username: String) {
        entries.load()[key(anchorBaseUrl, username)]?.coordinator?.topologyChanged()
    }

    suspend fun recheck(anchorBaseUrl: String, username: String) {
        entries.load()[key(anchorBaseUrl, username)]?.coordinator?.recheck()
    }

    fun forget(accountId: String) {
        while (true) {
            val before = entries.load()
            val after = before.filterValues { it.accountId != accountId }
            if (entries.compareAndSet(before, after)) {
                changed()
                before.values.filter { it.accountId == accountId }.forEach { it.scope?.cancel() }
                return
            }
        }
    }
}

/** Strict exact-base canonicalization, shared by directory, legacy attestation and registry keys. */
internal fun canonicalConnectionBase(value: String, allowHttp: Boolean = false): String {
    if (value.length > 2048 || value.isBlank() || value.any { it.isWhitespace() || it.isISOControl() } ||
        '\\' in value || '?' in value || '#' in value
    ) throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
    val url = try { Url(value) } catch (_: Exception) { throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope) }
    if (url.protocol.name !in (if (allowHttp) setOf("http", "https") else setOf("https")) ||
        !value.startsWith("${url.protocol.name}://", ignoreCase = true) || url.host.isBlank() ||
        url.host.any { it.code > 127 } || url.user != null || url.password != null || url.port !in 1..65535 ||
        url.encodedPath.split('/').any { segment ->
            val dots = segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".")
            dots == "." || dots == ".."
        } || "//" in url.encodedPath || Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath)
    ) throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
    return MediaBindingIdentity.canonicalServerUrl(value)
        ?: throw ConnectionEnrollmentFailure(DomainError.Protocol.MalformedEnvelope)
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
        if ((value["version"] as? JsonPrimitive)?.intOrNull != 1) bad()
        val revision = (value["revision"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 1 } ?: bad()
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
