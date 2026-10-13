package com.legitimateapps.dulcet.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CredentialSafetyTest {
    @Test fun keyCreationRefusesFilesystemsWithoutOwnerOnlyPermissions() {
        val root = createTempDirectory("key-permissions")
        val archive = java.net.URI.create("jar:" + root.resolve("data.zip").toUri())
        java.nio.file.FileSystems.newFileSystem(archive, mapOf("create" to "true")).use { fs ->
            assertFailsWith<UnsupportedOperationException> {
                com.legitimateapps.dulcet.server.auth.PasswordVault.keyFromDataFolder(fs.getPath("/data"))
            }
            assertFalse(java.nio.file.Files.exists(fs.getPath("/data/secret.key")))
        }
        val key = com.legitimateapps.dulcet.server.auth.PasswordVault.keyFromDataFolder(root)
        assertEquals(32, key.size)
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(root.resolve("secret.key")))
    }

    @Test fun jsonpRejectsExecutableCallbacks() = testApplication {
        val ctx = Fixtures.context(createTempDirectory("jsonp"))
        application { dulcetModule(ctx) }
        val bad = client.get("/rest/getOpenSubsonicExtensions?f=jsonp&callback=alert%281%29%3B%2F%2F").bodyAsText()
        assertFalse(bad.startsWith("alert(1);//"), bad)
        val good = client.get("/rest/getOpenSubsonicExtensions?f=jsonp&callback=app.callbacks.done").bodyAsText()
        assertTrue(good.startsWith("app.callbacks.done("), good)
    }
    @Test fun repeatedFailedLoginsThrottleEvenCorrectCredentials() = testApplication {
        val ctx = Fixtures.context(createTempDirectory("throttle"))
        ctx.users.create("reader", "pw", false)
        application { dulcetModule(ctx) }
        repeat(12) { client.rest("ping", "reader", "wrong") }
        assertTrue(client.rest("ping", "reader", "pw")["error"] != null)
    }
}
