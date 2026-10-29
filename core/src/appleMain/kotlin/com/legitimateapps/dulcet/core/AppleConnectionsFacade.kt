package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** Route metadata is copied at the Objective-C boundary; ownership stays in saved accounts. */
public class AppleConnectionRouteDto internal constructor(
    public val id: String,
    public val baseUrl: String,
    public val label: String?,
    public val enabled: Boolean,
    public val removed: Boolean,
    public val selected: Boolean,
)

public class AppleConnectionsOutcomeDto internal constructor(
    public val routes: List<AppleConnectionRouteDto>,
    public val enrolled: Boolean,
    public val errorKind: String?,
)

/** Main-thread facade. SQLite state and the registry restore before any network consumer starts. */
public class AppleConnectionsClient(private val databaseName: String) {
    private val scope: CoroutineScope = MainScope()

    public fun restore(accountId: String, anchorUrl: String, username: String, serverIdentity: String): AppleConnectionsOutcomeDto =
        withStore { service -> service.restore(OwnershipAnchor(accountId, anchorUrl, username, serverIdentity)).toDto() }

    public fun remove(accountId: String, anchorUrl: String, username: String, serverIdentity: String, routeId: String): AppleConnectionsOutcomeDto =
        withStore { service -> service.remove(OwnershipAnchor(accountId, anchorUrl, username, serverIdentity), routeId).toDto() }

    public fun startEnroll(
        accountId: String, anchorUrl: String, username: String, serverIdentity: String,
        bootstrapUrl: String, password: String,
        completion: (AppleConnectionsOutcomeDto) -> Unit,
    ): ApplePlaybackWireOperation {
        val job = scope.launch {
            val store = try { DulcetDriverFactory(databaseName).openDulcetDatabase() }
            catch (_: Throwable) { completion(AppleConnectionsOutcomeDto(emptyList(), false, "persistence")); return@launch }
            val result = try {
                when (val enrolled = service(store).enroll(ConnectionEnrollmentRequest(
                    OwnershipAnchor(accountId, anchorUrl, username, serverIdentity),
                    AccountConnectionRequest(bootstrapUrl, username, password, false),
                ))) {
                    is ConnectionEnrollmentResult.Enrolled -> enrolled.snapshot.toDto()
                    is ConnectionEnrollmentResult.Failed -> AppleConnectionsOutcomeDto(emptyList(), false, enrolled.error.applePlaybackErrorKind())
                }
            } catch (_: Throwable) { AppleConnectionsOutcomeDto(emptyList(), false, "persistence") }
            finally { store.close() }
            completion(result)
        }
        return object : ApplePlaybackWireOperation { override fun cancel() { job.cancel() } }
    }

    private fun service(store: DulcetDatabaseStore) = ConnectionEnrollmentService(store.database,
        nowEpochMilliseconds = { (NSDate().timeIntervalSince1970 * 1000).toLong() })

    private fun withStore(block: (ConnectionEnrollmentService) -> AppleConnectionsOutcomeDto): AppleConnectionsOutcomeDto = try {
        val store = DulcetDriverFactory(databaseName).openDulcetDatabase()
        try { block(service(store)) } finally { store.close() }
    } catch (failure: ConnectionEnrollmentFailure) {
        AppleConnectionsOutcomeDto(emptyList(), false, failure.error.applePlaybackErrorKind())
    } catch (_: Throwable) { AppleConnectionsOutcomeDto(emptyList(), false, "persistence") }
}

private fun ConnectionSnapshot.toDto() = AppleConnectionsOutcomeDto(routes.map {
    AppleConnectionRouteDto(it.id, it.baseUrl, it.label, it.enabled, it.removed, it.id == selectedRouteId)
}, enrolled, null)
