package com.legitimateapps.dulcet.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidConnectionInitializationTest {
    @Test fun freshPlatformInitializationLetsTheStrictServiceAuthenticateBeforeAnyMediaControllerExists() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val anchor = OwnershipAnchor("first-connection-${UUID.randomUUID()}", "https://localhost:9/music", "listener",
            ServerIdentityPolicy.identity("dulcet", "fresh-fixture"))
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try {
            val database = store.database
            ServerIdentityStore(database).accept(anchor.accountId, anchor.serverIdentity)
            assertNull(database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull())
            assertTrue(AndroidConnections(context).initializeFreshOwnership(anchor))
            val epoch = database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne()
            var connected = 0
            val result = ConnectionEnrollmentService(database, connect = {
                connected++
                AccountConnectionResult.Failed(DomainError.Transport.Unreachable)
            }).enroll(ConnectionEnrollmentRequest(anchor, AccountConnectionRequest(anchor.normalizedBaseUrl, anchor.username, "fixture", false)))
            assertEquals(1, connected, "The real Android initializer must satisfy the unchanged ownership fence")
            assertEquals(DomainError.Transport.Unreachable, assertIs<ConnectionEnrollmentResult.Failed>(result).error)
            assertFalse(AndroidConnections(context).initializeFreshOwnership(anchor))
            assertEquals(epoch, database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOne())
        } finally {
            store.database.serverDataQueries.deleteSeenCacheForServer(anchor.accountId)
            ConnectionRouteRegistry.forget(anchor.accountId)
            store.close()
        }
    }
}
