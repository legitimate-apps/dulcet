package com.legitimateapps.dulcet.core

import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.test.*
import org.junit.Assume.assumeTrue

/** Explicit fixture-only profile; native callers execute the identical scenario on their drivers. */
class ConnectionReaderContinuityFixtureTest {
    @Test fun readerCacheAndQueuedValuesUseOneOwnerAcrossThreeRealHttpsListeners() = runBlocking {
        val path = System.getenv("DULCET_MULTIPATH_MANIFEST")
        assumeTrue("Requires the owned multipath fixture profile", path != null)
        val manifest = Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        assertTrue(manifest.getValue("disposable").jsonPrimitive.boolean)
        assertEquals(2, manifest.getValue("phase").jsonPrimitive.int)
        fun field(key: String) = manifest.getValue(key).jsonPrimitive.content
        val base = field("routeA")
        assertEquals("localhost", Url(base).host)
        assertEquals("https", Url(base).protocol.name)
        val anchor = OwnershipAnchor("reader:${UUID.randomUUID()}", base, field("username"), field("serverIdentity"))
        val file = File.createTempFile("connection-reader-", ".db").also { it.delete() }
        val store = DulcetDriverFactory(file.path).openDulcetDatabase()
        val control = createAccountHttpClient(AccountClientTransport.Default()) { expectSuccess = false; followRedirects = false }
        try {
            ServerIdentityStore(store.database).accept(anchor.accountId, anchor.serverIdentity)
            assertIs<ConnectionEnrollmentResult.Enrolled>(ConnectionEnrollmentService(store.database).enroll(
                ConnectionEnrollmentRequest(anchor, AccountConnectionRequest(base, anchor.username, field("password")))))
            connectionReaderContinuityProof(store, anchor, field("password"), field("albumId")) { command, method ->
                val target = field("controlBase")
                assertEquals("localhost", Url(target).host)
                val response = control.request("$target/$command") {
                    this.method = HttpMethod.parse(method)
                    headers.append("X-Dulcet-Fixture", field("nonce"))
                }
                assertEquals(200, response.status.value)
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
        } finally {
            control.close(); ConnectionRouteRegistry.forget(anchor.accountId); store.close()
            for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete()
        }
    }
}
