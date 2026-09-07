package com.legitimateapps.dulcet.search.conformance

import android.util.Base64
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import kotlin.test.*

class ProductionLibraryEnvironment : ExternalResource() {
    override fun before() {
        check(System.getenv("DULCET_CONFORMANCE_DISPOSABLE") == "true" &&
            System.getenv("DULCET_CONFORMANCE_BASE_URL") == "http://127.0.0.1:4533") {
            "Live library tests require the disposable loopback runner"
        }
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app is SearchHostDependencyOwner)
        // A real refusal is a failure, never an assumption/skip.
        try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 4533), 1000) } }
        catch (_: Exception) { error("Disposable library server must be reachable (endpoint redacted)") }
        app.deleteDatabase("dulcet.db")
        AndroidAccountCredentialStore(app).save("Disposable", "http://127.0.0.1:4533",
            "dulcet-admin", "dulcet-ci-canary-password", true)
    }

    fun requireRefusalAndPointSavedAccountOffline() {
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 1), 500) }
            fail("Offline endpoint unexpectedly accepted a connection")
        } catch (_: ConnectException) {
            println("LIBRARY_OFFLINE_CONTROL connection-refused=true")
        }
        val app = RuntimeEnvironment.getApplication()
        val account = assertNotNull(AndroidAccountCredentialStore(app).load())
        // Preserve account identity and its database while changing only the saved endpoint.
        // This seeds the real encrypted record format; no search/library dependency is replaced.
        val bytes = ByteArrayOutputStream().apply {
            DataOutputStream(this).use {
                it.writeInt(1); it.writeUTF(account.serverName); it.writeUTF("http://127.0.0.1:1")
                it.writeUTF(account.username); it.writeUTF(account.password); it.writeBoolean(true)
            }
        }.toByteArray()
        val encrypted = HostCredentialCipher().encrypt(account.id, bytes)
        app.getSharedPreferences(AndroidAccountCredentialStore.PREFERENCES_NAME, 0).edit()
            .putString("account.${account.id}", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
        assertEquals("http://127.0.0.1:1", AndroidAccountCredentialStore(app).load()?.serverUrl)
    }

    override fun after() {
        val app = RuntimeEnvironment.getApplication()
        AndroidAccountCredentialStore(app).delete()
        app.deleteDatabase("dulcet.db")
    }
}
