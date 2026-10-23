package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.WishFilter
import com.legitimateapps.dulcet.library.CoreDiscoverWishlistSource
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.library.toReaderAccount
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.DiscoverWishlistFixture
import com.legitimateapps.dulcet.search.conformance.DiscoverWishlistFixture.Companion.mbid
import com.legitimateapps.dulcet.search.conformance.closeProcessReader
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opt-in real-server proof, selected by -Pdulcet.discoverWishlistConformance under the disposable
 * fixture wrapper. Compose consumes the production reader, source and SQL outbox. Only the screen
 * host is test-owned; no fabricated publication, wish, membership or successful mutation is injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneWishlistIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var fixture: DiscoverWishlistFixture
    private lateinit var account: SearchAccount
    private lateinit var reader: AndroidLibraryReader
    private lateinit var session: DiscoverWishlistSession
    private var activity: ActivityScenario<ComponentActivity>? = null
    private val wishlist = mutableStateOf(false)
    private var opened: DiscoverLibraryRef? = null

    @Before fun freshDisposableAccount() {
        fixture = DiscoverWishlistFixture() // Missing wrapper or unreachable fixture is a failure, never a skip.
        closeProcessReader()
        context.deleteDatabase("dulcet.db")
        account = fixture.reset()
        open()
    }

    @After fun close() {
        activity?.close()
        if (::session.isInitialized) session.close()
        closeProcessReader()
        context.deleteDatabase("dulcet.db")
    }

    private fun open(offline: Boolean = false) {
        val detectionSince = fixture.cursor()
        reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), foreground = true)
        if (offline) reader.setOnline(false)
        session = DiscoverWishlistSession(CoreDiscoverWishlistSource(reader))
        session.refreshWishlist(WishFilter())
        activity = ActivityScenario.launch(ComponentActivity::class.java).also { scenario ->
            scenario.onActivity { host -> host.setContent { MaterialTheme {
                if (wishlist.value) PhoneWishlist(session, openLibrary = { opened = it }, back = { wishlist.value = false })
                else PhoneDiscoverSearch(session, openLibrary = { opened = it }, openArtist = {}, openAlbum = {})
            } } }
        }
        await("capability detected from the real extension") { session.publication.value?.access?.wishlistAvailable == true }
        if (!offline) await("positive production extension and wishlist requests") {
            val endpoints = fixture.requests(detectionSince).map { it.getString("endpoint") }
            "getOpenSubsonicExtensions" in endpoints && "getDulcetWishlist" in endpoints
        }
    }

    @Test fun offlineAddRelaunchAndRemoveUsesDurableOperation() {
        search("arrival")
        val offline = fixture.arm("offline")
        click("discover.wish.song.0")
        await("the socket fault actually fired") { fixture.effect(offline, "addToDulcetWishlist") }
        reader.setOnline(false)
        showWishlist()
        await("durable pending row") { rowText("Fixture Song 01").contains("Waiting to sync") }
        val before = checkNotNull(session.publication.value?.wishes?.firstOrNull { it.pendingAdd?.target?.mbid == mbid(1) })
        val operation = checkNotNull(before.pendingAdd).operationId
        assertTrue(before.pending)

        // The activity and actual production reader terminate; the same account's SQL store remains.
        activity!!.close(); activity = null
        session.close(); closeProcessReader()
        open(offline = true)
        await("the same SQL intent after reader relaunch") {
            session.publication.value?.wishes?.any { it.key == before.key && it.pendingAdd?.operationId == operation && it.pending } == true
        }
        assertTrue(rowText("Fixture Song 01").contains("Waiting to sync"))
        fixture.arm("normal"); reconnect()
        await("the server acknowledged the durable add") { fixture.wish(mbid(1)) != null }
        await("pending clears only after acknowledgement") { session.publication.value?.wishes?.any { it.key == before.key && !it.pending } == true }
        clickRowAction("Fixture Song 01", "wishlist.remove.")
        await("removal sent") { fixture.wish(mbid(1)) == null }
        await("removed row leaves the display") { session.publication.value?.wishes?.none { it.wish?.target?.mbid == mbid(1) || it.pendingAdd?.target?.mbid == mbid(1) } == true }
    }

    @Test fun lostReceiptRemovalAndReaddCannotResurrectOldWish() {
        search("lost receipt")
        val lost = fixture.arm("lost-add-receipt")
        click("discover.wish.song.1")
        await("successful add delivered before its receipt was lost") { fixture.effect(lost, "addToDulcetWishlist", delivered = true) }
        val old = checkNotNull(fixture.wish(mbid(2))).getString("id")
        showWishlist()
        await("optimistic lost-receipt row") { rowText("Fixture Song 02").contains("Waiting to sync") }
        val removeSince = fixture.cursor()
        clickRowAction("Fixture Song 02", "wishlist.remove.")
        fixture.arm("normal"); reconnect()
        await("generation removal crossed the wire") { fixture.requests(removeSince).any { it.getString("endpoint") == "removeFromDulcetWishlist" } }
        await("previous positively witnessed wish absent") { fixture.wish(mbid(2)) == null }
        showSearch(); search("re-add")
        click("discover.wish.song.1")
        await("fresh add after cancellation") { fixture.wish(mbid(2))?.getString("id")?.let { it != old } == true }
        val fresh = checkNotNull(fixture.wish(mbid(2))).getString("id")
        assertNotEquals(old, fresh)
        reconnect()
        assertEquals(fresh, checkNotNull(fixture.wish(mbid(2))).getString("id"), "reconnect cannot revive the removed generation")
        assertEquals(1, fixture.wishes().count { it.getJSONObject("externalIds").optString("musicbrainzRecordingId") == mbid(2) })
    }

    @Test fun fulfilledArrivalRoutesLocalItemWithoutClientAdd() {
        search("arrival")
        click("discover.wish.song.0")
        await("wish exists before the independent scan") { fixture.wish(mbid(1)) != null }
        val beforeScan = fixture.cursor()
        val scan = fixture.arm("scan-arrival")
        assertTrue(scan.getJSONObject("scan").getBoolean("completed"))
        assertTrue(fixture.effect(scan, "startScan"), "positive revision-bound real scan acknowledgement")
        await("server fulfilment") { fixture.wish(mbid(1))?.getString("state") == "fulfilled" }
        val local = checkNotNull(fixture.wish(mbid(1))).getJSONObject("libraryItem")
        val id = local.getString("id")
        assertTrue(fixture.libraryContains(id), "the server scan created actual My Library membership")
        session.refreshWishlist(WishFilter())
        showWishlist()
        await("Arrived is rendered from the fulfilled server state") { rowText("Fixture Song 01").contains("Arrived") }
        clickRowAction("Fixture Song 01", "wishlist.arrival.")
        assertEquals(account.providerInstanceId, opened?.providerInstanceId)
        assertEquals(id, opened?.id)
        assertEquals(local.getString("type"), opened?.type)
        assertFalse(fixture.requests(beforeScan).any { it.getString("endpoint") == "addToDulcetLibrary" }, "scan fulfilment, never a client membership add")
        val beforeCorrection = fixture.cursor()
        clickRowAction("Fixture Song 01", "wishlist.reject.")
        await("correction crossed the wire") { fixture.requests(beforeCorrection).any { it.getString("endpoint") == "rejectDulcetDiscoverMatch" } }
        await("corrected wish returns to Wanted") { fixture.wish(mbid(1))?.getString("state") == "wanted" }
        assertTrue(fixture.libraryContains(id), "correction retains the independently verified My Library member")
        clickRowAction("Fixture Song 01", "wishlist.remove.")
        await("fulfilled wish removed") { fixture.wish(mbid(1)) == null }
        assertTrue(fixture.libraryContains(id), "wish removal retains the independently verified My Library member")
    }

    private fun reconnect() {
        var completed = false
        reader.setOnline(true)
        reader.reconnect { completed = true }
        await("production reconnect settled") { completed }
    }
    private fun showWishlist() { compose.runOnIdle { wishlist.value = true }; await("Wishlist screen") { exists("wishlist.surface") } }
    private fun showSearch() { compose.runOnIdle { wishlist.value = false }; await("Search screen") { exists("discover.search.field") } }
    private fun search(value: String) {
        await("Search field") { exists("discover.search.field") }
        compose.onNodeWithTag("discover.search.field").performTextReplacement(value)
        await("real server search results") {
            session.publication.value?.let { publication ->
                publication.query == value && publication.groups.any { it.kind == "song" && !it.loading && it.page?.items?.isNotEmpty() == true }
            } == true
        }
        compose.onNodeWithTag("discover.list").performScrollToNode(hasTestTag("discover.wish.song.1"))
        await("real server wish action") { exists("discover.wish.song.1") }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) {
        if (!exists(tag)) for (container in listOf("discover.list", "wishlist.list")) {
            if (exists(container)) runCatching { compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag)) }
            if (exists(tag)) break
        }
        await("click target $tag") { exists(tag) }
        compose.onNodeWithTag(tag).performClick(); compose.waitForIdle()
    }
    private fun row(title: String): Int {
        compose.onNodeWithTag("wishlist.list").performScrollToNode(hasText(title, substring = true))
        val rows = compose.onAllNodes(SemanticsMatcher("Wishlist rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("wishlist.row.") == true
        }, useUnmergedTree = true).fetchSemanticsNodes()
        return checkNotNull(rows.firstOrNull { text(it).contains(title) }).config[SemanticsProperties.TestTag].substringAfterLast('.').toInt()
    }
    private fun openRowActions(title: String): Int {
        val index = row(title)
        compose.onNodeWithTag("wishlist.row.menu.$index").performClick()
        compose.waitForIdle()
        return index
    }
    private fun clickRowAction(title: String, tagPrefix: String) {
        click(tagPrefix + openRowActions(title))
    }
    private fun rowText(title: String): String = runCatching {
        text(compose.onNodeWithTag("wishlist.row.${row(title)}", useUnmergedTree = true).fetchSemanticsNode())
    }.getOrDefault("")
    private fun text(node: androidx.compose.ui.semantics.SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty() + node.children.map(::text)).joinToString(" ")
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (!runCatching(condition).getOrDefault(false)) {
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
            check(System.nanoTime() < deadline) { "Did not observe $what" }
            Thread.sleep(10)
        }
    }
}
