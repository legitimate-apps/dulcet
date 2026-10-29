package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.time.Clock

public data class ConnectionEnrollmentRequest(
    val anchor: OwnershipAnchor,
    val bootstrap: AccountConnectionRequest,
    /** Only a person's Add/re-add action may clear the chosen route's local removal. */
    val explicitAdd: Boolean = false,
) {
    override fun toString(): String = "ConnectionEnrollmentRequest(<redacted>)"
}

public sealed interface ConnectionEnrollmentResult {
    public data class Enrolled(val snapshot: ConnectionSnapshot) : ConnectionEnrollmentResult
    public data class Failed(val error: DomainError) : ConnectionEnrollmentResult
}

/**
 * Storage and publication are additive. This class has no handle to bindMedia, cache bind,
 * purgeNamespace, secure credentials or any owned-row mutation.
 * Restore snapshots routes and their generation in one SQL statement. Atomic generation-fenced
 * publication also rejects delayed publishers from other handles or dispatchers.
 */
internal class ConnectionEnrollmentStore(private val database: DulcetDatabase) {
    fun snapshot(anchor: OwnershipAnchor): ConnectionSnapshot {
        val row = database.connectionsQueries.selectEnrollment(anchor.accountId).executeAsOneOrNull()
        if (row == null) return ConnectionSnapshot(anchor, emptyList(), null, 0, false)
        if (row.ownership_anchor != canonicalConnectionBase(anchor.normalizedBaseUrl, true) ||
            row.username != anchor.username || row.server_identity != anchor.serverIdentity
        ) throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
        return ConnectionSnapshot(anchor, database.connectionsQueries.selectRoutes(anchor.accountId).executeAsList().map {
            ConnectionRoute(it.route_id, it.normalized_base_url, it.label, it.role, it.priority.toInt(), it.enabled == 1L, it.tombstoned == 1L)
        }, row.selected_route_id, row.directory_revision, true)
    }

    fun restore(anchor: OwnershipAnchor): ConnectionSnapshot {
        val rows = database.connectionsQueries.selectPublication(anchor.accountId).executeAsList()
        val row = rows.firstOrNull() ?: return ConnectionSnapshot(anchor, emptyList(), null, 0, false)
        if (row.ownership_anchor != canonicalConnectionBase(anchor.normalizedBaseUrl, true) ||
            row.username != anchor.username || row.server_identity != anchor.serverIdentity) {
            ConnectionRouteRegistry.publish(anchor, null, row.generation)
            throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
        }
        val routes = rows.mapNotNull { item -> item.route_id?.let { id ->
            ConnectionRoute(id, requireNotNull(item.normalized_base_url), item.label,
                requireNotNull(item.role), requireNotNull(item.priority).toInt(), item.enabled == 1L, item.tombstoned == 1L)
        } }
        val result = ConnectionSnapshot(anchor, routes, row.selected_route_id, row.directory_revision, true)
        val route = routes.singleOrNull { it.id == result.selectedRouteId && it.enabled && !it.removed }
        val published = ConnectionRouteRegistry.publish(anchor, route?.let {
            RouteLease(it.baseUrl, it.id, lineage(anchor.serverIdentity), result.revision, row.generation)
        }, row.generation)
        // A newer publication won while this statement was being consumed. Refresh the UI result
        // without giving this older publisher another opportunity to replace its authorization.
        return if (published) result else snapshot(anchor)
    }

    fun commit(anchor: OwnershipAnchor, directory: ConnectionDirectory, bootstrapBase: String,
        verifiedRouteIds: Set<String>, verifiedWall: Long, expectedOwnership: ConnectionOwnershipFence? = null,
        explicitReAddBase: String? = null,
        expectedTransport: ConnectionTransportFence? = null,
    ): ConnectionSnapshot {
        database.transaction {
            expectedOwnership?.checkCurrent(database, anchor)
            expectedTransport?.checkCurrent(database, anchor)
            val before = snapshot(anchor)
            val stored = database.connectionsQueries.selectEnrollment(anchor.accountId).executeAsOneOrNull()
            if (directory.revision < before.revision || (stored != null &&
                    (stored.principal_id != directory.principalId ||
                        directory.revision == stored.directory_revision && directory.json != stored.directory_json))) {
                throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            }
            val normalizedAnchor = canonicalConnectionBase(anchor.normalizedBaseUrl, true)
            if (directory.username != anchor.username || directory.instanceId != lineage(anchor.serverIdentity) ||
                normalizedAnchor.startsWith("http:") && normalizedAnchor !in directory.legacyOwnershipAnchors
            ) throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            val source = directory.routes.singleOrNull { it.baseUrl == bootstrapBase && it.id in verifiedRouteIds }
                ?: throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            val explicitTarget = explicitReAddBase?.let { base ->
                directory.routes.singleOrNull { it.baseUrl == base && it.id in verifiedRouteIds }
                    ?: throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            }
            val previous = before.routes.associateBy { it.id }
            if (previous[source.id]?.removed == true && explicitTarget?.id != source.id) {
                throw ConnectionEnrollmentFailure(DomainError.Transport.Unreachable)
            }
            database.connectionsQueries.disableRoutes(anchor.accountId)
            directory.routes.forEach { route ->
                val old = previous[route.id]
                // A changed address needs a new successful probe; tombstones are persistent.
                val verified = route.id in verifiedRouteIds
                val removed = old?.removed == true && route.id != explicitTarget?.id
                database.connectionsQueries.putRoute(anchor.accountId, route.id, route.baseUrl, route.label,
                    route.role, route.priority.toLong(), if (verified && !removed) 1L else 0L,
                    if (removed) 1L else 0L, directory.revision, if (verified) verifiedWall else null)
            }
            val selected = explicitTarget?.id ?: before.selectedRouteId?.takeIf { id ->
                directory.routes.any { it.id == id && id in verifiedRouteIds && previous[id]?.removed != true }
            } ?: source.id.takeIf { !before.enrolled }
            database.connectionsQueries.putEnrollment(anchor.accountId, normalizedAnchor, anchor.username,
                anchor.serverIdentity, directory.principalId, directory.revision,
                stored?.bootstrap_base ?: bootstrapBase, selected, (stored?.generation ?: 0) + 1, directory.json)
        }
        return restore(anchor)
    }

    fun remove(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot {
        database.transaction {
            val before = snapshot(anchor)
            if (!before.enrolled) return@transaction
            database.connectionsQueries.removeRoute(anchor.accountId, routeId)
            // Phase 1 has no automatic selector, including no bootstrap fallback.
            // Every removal advances transport generation so already prepared work is fenced.
            database.connectionsQueries.selectRoute(before.selectedRouteId.takeUnless { it == routeId }, anchor.accountId)
        }
        return restore(anchor)
    }

    /** Internal test seam; shipping account controls do not expose force-use or automatic switching. */
    fun selectForFixture(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot {
        val route = snapshot(anchor).routes.singleOrNull { it.id == routeId && it.enabled && !it.removed }
            ?: throw ConnectionEnrollmentFailure(DomainError.Transport.Unreachable)
        database.connectionsQueries.selectRoute(route.id, anchor.accountId)
        return restore(anchor)
    }

    companion object {
        fun lineage(identity: String): String = try {
            val array = Json.parseToJsonElement(identity) as? JsonArray
            (array?.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        } catch (_: Exception) { "" }
    }
}

/** A refresh source is transport provenance, never the saved ownership anchor. */
public object ConnectionEnrollmentSources {
    public fun verification(snapshot: ConnectionSnapshot): String? {
        val admitted = snapshot.routes.filter { it.enabled && !it.removed && it.baseUrl.startsWith("https://") }
        return (admitted.firstOrNull { it.id == snapshot.selectedRouteId } ?: admitted.firstOrNull())?.baseUrl
    }
}

/** New UUID accounts only: establish a nonce without binding, replacing or deleting any owned data. */
internal fun initializeFreshConnectionOwnership(database: DulcetDatabase, anchor: OwnershipAnchor): Boolean {
    var initialized = false
    database.transaction {
        if (!MediaBindingIdentity.isValidBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username) ||
            ServerIdentityStore(database).read(anchor.accountId) != anchor.serverIdentity || anchor.serverIdentity.isBlank() ||
            database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull() != null ||
            database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOneOrNull() != null ||
            database.connectionsQueries.selectEnrollment(anchor.accountId).executeAsOneOrNull() != null ||
            database.downloadsQueries.selectDownloadsForServer(anchor.accountId).executeAsList().isNotEmpty() ||
            database.serverDataQueries.countRowsForServer(anchor.accountId).executeAsOne().sum != 1L
        ) return@transaction
        database.libraryDepthQueries.putEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY,
            encodeMediaBinding(DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username)), 0)
        initialized = true
    }
    return initialized
}

/** A later removal or refresh supersedes an earlier suspended Add/Verify intent. */
internal data class ConnectionTransportFence(val generation: Long?) {
    fun checkCurrent(database: DulcetDatabase, anchor: OwnershipAnchor) {
        if (capture(database, anchor) != this) throw ConnectionEnrollmentFailure(DomainError.Transport.Cancelled)
    }
    companion object {
        fun capture(database: DulcetDatabase, anchor: OwnershipAnchor): ConnectionTransportFence =
            ConnectionTransportFence(database.connectionsQueries.selectEnrollment(anchor.accountId).executeAsOneOrNull()?.generation)
    }
}

/** Captured before any suspension and compared inside the route commit transaction. */
internal data class ConnectionOwnershipFence(
    val cacheScope: Pair<String, String>?,
    val mediaEpoch: String?,
    val acceptedIdentity: String,
) {
    fun checkCurrent(database: DulcetDatabase, anchor: OwnershipAnchor) {
        if (capture(database, anchor) != this) throw ConnectionEnrollmentFailure(DomainError.Transport.Cancelled)
    }
    companion object {
        fun capture(database: DulcetDatabase, anchor: OwnershipAnchor): ConnectionOwnershipFence {
            val cache = database.seenCacheQueries.selectBinding(anchor.accountId).executeAsOneOrNull()
            val epoch = database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull()
            val identity = ServerIdentityStore(database).read(anchor.accountId)
            if (identity != anchor.serverIdentity || epoch == null ||
                cache != null && (!MediaBindingIdentity.sameEndpoint(cache.normalized_base_url, anchor.normalizedBaseUrl) || cache.username != anchor.username) ||
                epoch != null && !matchesMediaBinding(epoch, DownloadMediaBinding(anchor.accountId, anchor.normalizedBaseUrl, anchor.username))
            ) throw ConnectionEnrollmentFailure(DomainError.Transport.Cancelled)
            return ConnectionOwnershipFence(cache?.let { it.normalized_base_url to it.username }, epoch, identity)
        }
    }
}

/** A candidate check never has a credential argument, inherits no cookies, and follows no redirects. */
internal fun interface ConnectionCandidateProbe {
    suspend fun verify(baseUrl: String, expectedInstance: String): DomainError?
}

internal class TlsConnectionCandidateProbe(
    private val transport: AccountClientTransport = AccountClientTransport.Default(),
) : ConnectionCandidateProbe {
    override suspend fun verify(baseUrl: String, expectedInstance: String): DomainError? {
        val client = createAccountHttpClient(transport) {
            expectSuccess = false; followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 15_000 }
        }
        return try {
            val base = canonicalConnectionBase(baseUrl)
            client.prepareGet("$base/.well-known/dulcet").execute { response ->
                if (response.status.value != 200) return@execute DomainError.Transport.Unreachable
                val limit = ConnectionDirectoryParser.MAX_BYTES
                if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > limit) return@execute DomainError.Protocol.TooLarge
                val bytes = ByteArray(limit + 1); var size = 0
                val channel = response.bodyAsChannel()
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > limit) return@execute DomainError.Protocol.TooLarge
                val doc = Json.parseToJsonElement(bytes.copyOf(size).decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                    ?: return@execute DomainError.Protocol.MalformedEnvelope
                val server = doc["server"] as? JsonObject
                val instance = (server?.get("instanceId") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (doc["standard"] != JsonPrimitive("dulcet") || instance != expectedInstance) {
                    return@execute DomainError.Protocol.ServerIdentityChanged
                }
                // The advertised API base must describe this receiving route, never a global WAN URL.
                val api = (doc["apis"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.singleOrNull { it["version"] == JsonPrimitive("1") }
                val apiBase = (api?.get("baseUrl") as? JsonPrimitive)?.content
                if (apiBase != "$base/dulcet/v1" && apiBase != "$base/dulcet/v1/") {
                    return@execute DomainError.Protocol.MalformedEnvelope
                }
                null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { DomainError.Protocol.MalformedEnvelope }
        catch (failure: Throwable) { mapAccountConnectionFailure(failure) }
        finally { client.close() }
    }
}

internal class ConnectionEnrollmentService(
    private val database: DulcetDatabase,
    private val connect: suspend (AccountConnectionRequest) -> AccountConnectionResult = { AccountConnector().connectBootstrap(it) },
    private val probe: ConnectionCandidateProbe = TlsConnectionCandidateProbe(),
    private val fetch: (suspend (AccountConnectionRequest) -> AuthenticatedEndpointResponse)? = null,
    private val nowEpochMilliseconds: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val store = ConnectionEnrollmentStore(database)
    fun restore(anchor: OwnershipAnchor): ConnectionSnapshot = store.restore(anchor)
    fun snapshot(anchor: OwnershipAnchor): ConnectionSnapshot = store.snapshot(anchor)
    fun remove(anchor: OwnershipAnchor, routeId: String): ConnectionSnapshot = store.remove(anchor, routeId)

    suspend fun enroll(request: ConnectionEnrollmentRequest): ConnectionEnrollmentResult = try {
        val anchor = request.anchor
        val ownership = ConnectionOwnershipFence.capture(database, anchor)
        val transport = ConnectionTransportFence.capture(database, anchor)
        fun checkCurrent() {
            ownership.checkCurrent(database, anchor)
            transport.checkCurrent(database, anchor)
        }
        val before = store.snapshot(anchor)
        val chosen = if (request.explicitAdd || !before.enrolled) canonicalConnectionBase(request.bootstrap.serverUrl) else null
        // Refresh only through an admitted nonremoved route. An explicit Add can recover
        // through its previously listed chosen HTTPS bootstrap if that source is unavailable.
        val chosenAdmitted = chosen?.takeIf { target -> before.routes.any {
            it.baseUrl == target && it.enabled && !it.removed
        } }
        var base = if (!before.enrolled) requireNotNull(chosen) else
            chosenAdmitted ?: ConnectionEnrollmentSources.verification(before) ?: chosen?.takeIf { target ->
                before.routes.any { it.baseUrl == target }
            } ?: throw ConnectionEnrollmentFailure(DomainError.Transport.Unreachable)
        val expectedInstance = ConnectionEnrollmentStore.lineage(anchor.serverIdentity)
        if (expectedInstance.isBlank()) throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
        // Keep the whole source attempt together: public reachability alone does not prove
        // that its authenticated connection and bounded directory can be read.
        suspend fun loadSource(base: String): ConnectionDirectory {
            if (before.enrolled) probe.verify(base, expectedInstance)?.let { throw ConnectionEnrollmentFailure(it) }
            checkCurrent()
            val connected = when (val result = connect(request.bootstrap.copy(serverUrl = base, allowLocalHttp = false))) {
                is AccountConnectionResult.Failed -> throw ConnectionEnrollmentFailure(result.error)
                is AccountConnectionResult.Connected -> result.account
            }
            if (connected.normalizedBaseUrl != base || connected.canonicalUsername != anchor.username ||
                connected.serverIdentity != anchor.serverIdentity || 1 !in connected.capabilities.extensions["dulcetRoutes"].orEmpty()
            ) throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            checkCurrent()
            val response = fetch?.invoke(request.bootstrap.copy(serverUrl = base, allowLocalHttp = false)) ?: run {
                val client = AuthenticatedEndpointClient(AuthenticatedEndpointCredentials(base, request.bootstrap.username,
                    request.bootstrap.password, false), "account.connections", useEnrolledRoute = false)
                try {
                    client.requestRepeated("getDulcetConnections", emptyList(), formPost = true,
                        options = AuthenticatedEndpointRequestOptions(maxBodyBytes = ConnectionDirectoryParser.MAX_BYTES, rejectRedirects = true))
                } finally { client.close() }
            }
            checkCurrent()
            LibraryEndpointResponse(response.statusCode, response.body.decodeToString(), response.redactedUrl,
                retryAfter = response.headers.retryAfter).okPayload()
            if (response.statusCode !in 200..299) throw ConnectionEnrollmentFailure(DomainError.Server.HttpStatus(response.statusCode))
            val directory = ConnectionDirectoryParser.parse(response.body, expectedInstance, anchor.username)
            if (directory.routes.none { it.baseUrl == base })
                throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            // Authenticated continuity and the separate secret-free API/base check are both required.
            probe.verify(base, expectedInstance)?.let { throw ConnectionEnrollmentFailure(it) }
            return directory
        }
        val directory = try { loadSource(base) } catch (failure: Throwable) {
            val error = when (failure) {
                is ConnectionEnrollmentFailure -> failure.error
                is AuthenticatedEndpointFailure -> failure.error
                is LibraryRequestFailure -> failure.error
                else -> null
            }
            val unavailable = error == DomainError.Transport.Unreachable || error == DomainError.Transport.Timeout ||
                error is DomainError.Security.TlsUntrusted
            val recovery = chosen?.takeIf { target ->
                unavailable && before.enrolled && request.explicitAdd && target != base &&
                    before.routes.any { it.baseUrl == target }
            } ?: throw failure
            checkCurrent()
            base = recovery
            // Exactly one explicit known bootstrap attempt, with its own TLS/lineage checks.
            // Target authentication and commit stay outside this source-only recovery boundary.
            loadSource(base)
        }
        val source = directory.routes.singleOrNull { it.baseUrl == base }
            ?: throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
        val verified = mutableSetOf(source.id)
        directory.routes.filter { it.id != source.id }.forEach { route ->
            if (probe.verify(route.baseUrl, expectedInstance) == null) verified += route.id
        }
        if (request.explicitAdd) {
            val target = directory.routes.singleOrNull { it.baseUrl == chosen && it.id in verified }
                ?: throw ConnectionEnrollmentFailure(DomainError.Transport.Unreachable)
            // Directory authorization and TLS/lineage admission precede authentication at Add's target.
            if (target.baseUrl != base) {
                checkCurrent()
                val confirmation = connect(request.bootstrap.copy(serverUrl = target.baseUrl, allowLocalHttp = false))
                val account = (confirmation as? AccountConnectionResult.Connected)?.account
                    ?: throw ConnectionEnrollmentFailure((confirmation as AccountConnectionResult.Failed).error)
                if (account.normalizedBaseUrl != target.baseUrl || account.canonicalUsername != anchor.username ||
                    account.serverIdentity != anchor.serverIdentity)
                    throw ConnectionEnrollmentFailure(DomainError.Protocol.ServerIdentityChanged)
            }
        }
        ConnectionEnrollmentResult.Enrolled(store.commit(anchor, directory, base, verified, nowEpochMilliseconds(), ownership,
            explicitReAddBase = chosen.takeIf { request.explicitAdd }, expectedTransport = transport))
    } catch (failure: ConnectionEnrollmentFailure) { ConnectionEnrollmentResult.Failed(failure.error) }
    catch (failure: LibraryRequestFailure) { ConnectionEnrollmentResult.Failed(failure.error) }
    catch (failure: AuthenticatedEndpointFailure) { ConnectionEnrollmentResult.Failed(failure.error) }
    catch (_: CancellationException) { ConnectionEnrollmentResult.Failed(DomainError.Transport.Cancelled) }
    catch (failure: Throwable) { ConnectionEnrollmentResult.Failed(mapAccountConnectionFailure(failure)) }
}
