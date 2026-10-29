package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** The review probe's scheduling: retain a completed route read while another handle removes it. */
class ConnectionRestoreRaceTest {
    @Test fun delayedPublishersCannotReplaceNewerOfflineOrSelectedGenerations() {
        val anchor = OwnershipAnchor("publication-race-account", "https://home.example.test/music", "listener", "lineage")
        val old = RouteLease(anchor.normalizedBaseUrl, "home", "lineage", 1, 7)
        try {
            assertTrue(ConnectionRouteRegistry.publish(anchor, old))
            assertTrue(ConnectionRouteRegistry.publish(anchor, null))
            assertFalse(ConnectionRouteRegistry.publish(anchor, old))
            assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
            val current = old.copy(baseUrl = "https://remote.example.test/music", routeId = "remote", revision = 2, generation = 9)
            assertTrue(ConnectionRouteRegistry.publish(anchor, current))
            assertFalse(ConnectionRouteRegistry.publish(anchor, old))
            assertFalse(ConnectionRouteRegistry.publish(anchor, null, 8))
            assertEquals(current, ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
            assertFalse(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, old.baseUrl, old.generation))
            assertTrue(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, current.baseUrl, current.generation))
        } finally { ConnectionRouteRegistry.forget(anchor.accountId) }
    }

    @Test fun delayedRestoreCannotAuthorizeATombstonedRouteAtTheNewerGeneration() {
        val driver = createTestDriver()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val paused = Proxy.newProxyInstance(SqlDriver::class.java.classLoader, arrayOf(SqlDriver::class.java)) { _, method, args ->
            val result = try { method.invoke(driver, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            if (method.name == "executeQuery" && args?.get(1).toString().contains("server_route") &&
                Thread.currentThread().name == "delayed-route-restore" && armed.compareAndSet(true, false)) {
                captured.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Restore query was not released" }
            }
            result
        } as SqlDriver
        val database = DulcetDatabase(driver)
        val anchor = OwnershipAnchor("restore-race-account", "https://home.example.test/music", "listener",
            ServerIdentityPolicy.identity("dulcet", "lineage"))
        val home = ConnectionRoute("home", anchor.normalizedBaseUrl, null, "private", 10)
        val remote = ConnectionRoute("remote", "https://remote.example.test/music", null, "public", 20)
        val directory = ConnectionDirectory(1, "lineage", "principal", "listener", listOf(home, remote), emptyList(), emptyList(), "{}")
        val current = ConnectionEnrollmentStore(database)
        val delayed = ConnectionEnrollmentStore(DulcetDatabase(paused))
        val failure = AtomicReference<Throwable?>()
        var reader: Thread? = null
        try {
            current.commit(anchor, directory, home.baseUrl, setOf("home", "remote"), 1)
            armed.set(true)
            reader = thread(name = "delayed-route-restore") { try { delayed.restore(anchor) } catch (error: Throwable) { failure.set(error) } }
            assertTrue(captured.await(10, TimeUnit.SECONDS), "The old route read must finish before removal")
            val removed = current.remove(anchor, "home")
            assertNull(removed.selectedRouteId)
            assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username))
            release.countDown()
            reader.join(10_000)
            assertFalse(reader.isAlive)
            assertNull(failure.get())
            val row = database.connectionsQueries.selectEnrollment(anchor.accountId).executeAsOne()
            assertEquals(2L, row.generation)
            assertNull(row.selected_route_id)
            assertTrue(current.snapshot(anchor).routes.single { it.id == "home" }.removed)
            assertNull(ConnectionRouteRegistry.leaseFor(anchor.normalizedBaseUrl, anchor.username),
                "An older completed read cannot republish a removed credential destination")
            assertFalse(ConnectionRequestAuthorization.isCurrent(anchor.normalizedBaseUrl, anchor.username, home.baseUrl, row.generation))
        } finally {
            release.countDown()
            reader?.join(10_000)
            ConnectionRouteRegistry.forget(anchor.accountId)
            driver.close()
        }
    }
}
