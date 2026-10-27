package com.legitimateapps.dulcet

import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.CoreDiscoverWishlistSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real production reader/source and Android storage; only HTTP is a disposable loopback fixture.
 * SQL seeding represents a previous process's committed offline intent, never a fake shell source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoverWishlistAccountIntegrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer()
    private val a = "provider:discover-a-${UUID.randomUUID()}"
    private val b = "provider:discover-b-${UUID.randomUUID()}"
    private val sessions = mutableListOf<DiscoverWishlistSession>()
    private val controllers = mutableListOf<AndroidDownloadController>()

    @After fun clean() {
        sessions.forEach { it.close() }
        controllers.forEach { it.close() }
        closeReader()
        server.close()
        context.deleteDatabase("dulcet.db")
        listOf(a, b).forEach { artwork(it).deleteRecursively() }
    }

    @Test fun sameUsernameDifferentProviderKeepsOutboxesSeparate() {
        initializeSchema()
        seed(a, "op-a", "Album A")
        seed(b, "op-b", "Album B")
        val first = open(a)
        await("A's actual durable pending wish") { first.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-a" }
        assertEquals(a, first.publication.value?.providerInstanceId)
        first.close()
        val second = open(b)
        await("B's actual durable pending wish") { second.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-b" }
        assertEquals(b, second.publication.value?.providerInstanceId)
        assertNull(first.publication.value, "A cannot publish into the replacement shell")
        assertEquals(listOf("op-a"), pending(a))
        assertEquals(listOf("op-b"), pending(b))
        second.close()
        val returned = open(a)
        await("switching back restores A rather than B") { returned.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-a" }
    }

    @Test fun clearCacheDoesNotLoseIntent() {
        initializeSchema()
        seed(a, "op-a", "Album A")
        val session = open(a)
        await("known positive pending intention") { session.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-a" }
        session.close()
        closeReader()
        val tasks = object : AndroidDownloadTasks {
            override suspend fun outstanding() = emptyList<String>()
            override fun start(downloadId: String) = error("No download was requested")
            override fun cancel(downloadId: String) {}
            override fun wakeAt(wallClockMilliseconds: Long) {}
        }
        val controller = AndroidDownloadController(context,
            PlaybackEndpointAccount(a, server.url, "same-user", "p", true), tasks)
        controllers += controller
        val cachedArt = File(artwork(a), "disposable.image")
        cachedArt.parentFile!!.mkdirs()
        cachedArt.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(cachedArt.isFile, "positive cache control before Clear Cache")
        runBlocking {
            assertTrue(controller.awaitReconciled(), "production storage must be reconciled before the clear")
            controller.clearStreamCache()
        }
        assertFalse(cachedArt.exists(), "the actual Clear Cache action removed an expendable cache file")
        controller.close()
        assertEquals(listOf("op-a"), pending(a), "durable intention survives Clear Cache")
        val relaunched = open(a)
        await("shell close and reader relaunch retain the committed pending wish") {
            relaunched.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-a"
        }
    }

    @Test fun signOutRemovesFeatureRows() {
        initializeSchema()
        seed(a, "op-a", "Album A")
        seed(b, "op-b", "Album B")
        val session = open(a)
        await("A's known-positive durable wish before sign-out") { session.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-a" }
        session.close()
        closeReader()
        assertEquals(1, count("wish_intent", a))
        assertEquals(1, count("discover_read", a))
        assertEquals(1, count("wish_clock", a))
        assertEquals(1, count("wish_receipt", a))
        assertEquals(1, count("wishlist_snapshot", a))
        // The public removal reports reader termination on main. Keep Robolectric's main looper
        // moving as the real activity does while its coroutine is suspended.
        val removal = java.util.concurrent.CompletableFuture.runAsync {
            runBlocking { AndroidAccountData(context, a).removeAccountData() }
        }
        await("production account removal completes") { removal.isDone }
        removal.get()
        for (table in listOf("wish_intent", "wish_clock", "wish_receipt", "wishlist_snapshot", "discover_read")) {
            assertEquals(0, count(table, a), "$table removed by production account deletion")
        }
        assertEquals(listOf("op-b"), pending(b), "sign-out must preserve the other saved account's intent")
        assertEquals(1, count("discover_read", b), "sign-out must preserve the other account's capabilities")
        val other = open(b)
        await("the surviving account still publishes its intention") { other.publication.value?.wishes?.singleOrNull()?.pendingAdd?.operationId == "op-b" }
    }

    private fun initializeSchema() {
        val session = open(a)
        await("production driver schema and publication") { session.publication.value != null }
        session.close()
        closeReader()
    }
    private fun open(provider: String): DiscoverWishlistSession {
        val reader = AndroidLibraryReader.forAccount(context,
            AndroidLibraryReaderAccount(provider, server.url, "same-user", "p", true), false)
        reader.setOnline(false)
        return DiscoverWishlistSession(CoreDiscoverWishlistSource(reader)).also { sessions += it }
    }
    private fun seed(provider: String, operation: String, title: String) = database { db ->
        val target = JSONObject().put("kind", "Album").put("mbid", "11111111-1111-4111-8111-111111111111")
        val payload = JSONObject().put("type", "com.legitimateapps.dulcet.core.WishMutation.Add")
            .put("operationId", operation).put("actionTimeMillis", 1L).put("target", target)
            .put("snapshot", JSONObject().put("title", title).put("artist", JSONObject.NULL)
                .put("year", JSONObject.NULL).put("artworkId", JSONObject.NULL))
            .put("source", JSONObject().put("type", "search").put("id", JSONObject.NULL))
        db.execSQL("INSERT INTO wish_intent VALUES (?, ?, ?, ?, ?, 'pending', NULL, 1)",
            arrayOf(provider, operation, "add:$operation", target.toString(), payload.toString()))
        db.execSQL("INSERT OR REPLACE INTO wish_clock VALUES (?, 1, 1)", arrayOf(provider))
        db.execSQL("INSERT INTO wish_receipt VALUES (?, 'historical-receipt', NULL, ?, 1)",
            arrayOf(provider, "{\"applied\":true,\"wish\":null,\"removed\":false}"))
        db.execSQL("INSERT INTO wishlist_snapshot VALUES (?, '{}', ?, 1)",
            arrayOf(provider, "{\"items\":[],\"stamp\":null}"))
        db.execSQL("INSERT OR REPLACE INTO discover_read VALUES (?, 'capabilities', ?, 0, 1, 1)",
            arrayOf(provider, "{\"dulcetDiscover\":[1],\"dulcetWishlist\":[1]}".toByteArray()))
    }
    private fun pending(provider: String): List<String> = database { db ->
        db.rawQuery("SELECT operation_id FROM wish_intent WHERE server_id = ? AND status = 'pending' ORDER BY ordinal", arrayOf(provider)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }
    private fun count(table: String, provider: String): Int = database { db ->
        db.rawQuery("SELECT count(*) FROM $table WHERE server_id = ?", arrayOf(provider)).use { it.moveToFirst(); it.getInt(0) }
    }
    private fun <T> database(work: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(
        context.getDatabasePath("dulcet.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(work)
    private fun artwork(provider: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(provider.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "artwork/$digest")
    }
    private fun closeReader() {
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        await("the previous reader must terminate before storage changes") { closed }
    }
    private fun await(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(predicate(), reason)
    }
}
