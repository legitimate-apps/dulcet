package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.serialization.json.*

/** Origin-authenticated server lineage, not a software release or a server-supplied display name. */
public object ServerIdentityPolicy {
    public fun identity(serverType: String, instanceId: String = ""): String = JsonArray(listOf(
        JsonPrimitive(serverType.trim().lowercase()), JsonPrimitive(instanceId),
    )).toString()

    /** A legacy credential without an identity learns it on its first successful negotiation. */
    public fun isReplacement(previous: String, current: String): Boolean {
        if (previous.isEmpty()) return false
        val old = decode(previous) ?: return true
        val new = decode(current) ?: return true
        // An older client knew only the implementation. Learning its advertised lineage is safe;
        // losing a previously known lineage is not permission to reuse its identifiers.
        return old.first != new.first || (old.second.isNotEmpty() && old.second != new.second)
    }

    internal fun requiresDiscovery(previous: String, serverType: String): Boolean =
        decode(previous)?.let { it.first == serverType.trim().lowercase() && it.second.isNotEmpty() } == true

    private fun decode(value: String): Pair<String, String>? = try {
        val parts = Json.parseToJsonElement(value).jsonArray
        if (parts.size != 2 || parts.any { !it.jsonPrimitive.isString }) null
        else parts[0].jsonPrimitive.content to parts[1].jsonPrimitive.content
    } catch (_: Exception) { null }
}

/** The selection rule is shared by every shell. Authentication, not typed case, names a user. */
public object SavedServerPolicy {
    public fun samePrincipal(savedUrl: String, savedUsername: String,
        connectedUrl: String, confirmedUsername: String): Boolean =
        savedUsername == confirmedUsername && MediaBindingIdentity.sameEndpoint(savedUrl, connectedUrl)

    public fun isServerReplacement(savedServerIdentity: String, connectedServerIdentity: String): Boolean =
        ServerIdentityPolicy.isReplacement(savedServerIdentity, connectedServerIdentity)

    public fun canContinue(savedUrl: String, savedUsername: String, savedServerIdentity: String,
        connectedUrl: String, confirmedUsername: String, connectedServerIdentity: String): Boolean =
        samePrincipal(savedUrl, savedUsername, connectedUrl, confirmedUsername) &&
            !isServerReplacement(savedServerIdentity, connectedServerIdentity)
}

private const val SERVER_IDENTITY_CATEGORY = "account.serverIdentity"
private const val SERVER_IDENTITY_KEY = "accepted"

/** Only accepted account negotiation writes this value; a reader can detect, never adopt a swap. */
internal class ServerIdentityStore(private val database: DulcetDatabase) {
    fun read(serverId: String): String = database.libraryDepthQueries
        .selectEntry(serverId, SERVER_IDENTITY_CATEGORY, SERVER_IDENTITY_KEY).executeAsOneOrNull().orEmpty()

    fun accept(serverId: String, identity: String) {
        require(serverId.isNotBlank() && identity.isNotEmpty())
        database.libraryDepthQueries.putEntry(serverId, SERVER_IDENTITY_CATEGORY, SERVER_IDENTITY_KEY, identity, 0)
    }
}

/** Runs before ANY reconnect outbox or epoch read. No deletion, credential change or alias adoption. */
internal class ServerIdentityGate(
    private val database: DulcetDatabase,
    private val serverId: String,
    private val request: AccountConnectionRequest,
    private val connect: (suspend (AccountConnectionRequest) -> AccountConnectionResult)? = null,
) {
    suspend fun validate(): DomainError? {
        val store = ServerIdentityStore(database)
        val previous = store.read(serverId)
        val result = connect?.invoke(request) ?: AccountConnector().identify(request, previous)
        return when (result) {
            is AccountConnectionResult.Failed -> result.error
            is AccountConnectionResult.Connected -> {
                if (ServerIdentityPolicy.isReplacement(previous, result.account.serverIdentity)) {
                    DomainError.Protocol.ServerIdentityChanged
                } else {
                    // A legacy namespace learns identity only after authenticating the account.
                    store.accept(serverId, result.account.serverIdentity)
                    null
                }
            }
        }
    }
}
