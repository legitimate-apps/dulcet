package com.legitimateapps.dulcet

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.awaitCurrent
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/** Real phone app, production account/reader and finite fixture. No live upstream or fake UI source. */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorDiscoverWishlistProofTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=instrumentation.targetContext
    private val fixture by lazy { Fixture() }
    private var previousReaderReceipts: String? = null

    @Before fun enableReaderReceipts() {
        previousReaderReceipts=System.getProperty("dulcet.proof.readerReceipts")
        System.setProperty("dulcet.proof.readerReceipts","true")
    }

    @After fun restoreReaderReceipts() {
        previousReaderReceipts?.let {System.setProperty("dulcet.proof.readerReceipts",it)}
            ?: System.clearProperty("dulcet.proof.readerReceipts")
    }

    @Test fun everywherePagesAndHeldRoute() {
        freshAccount()
        launch().use { activity ->
            everywhere("positive")
            requireText("Data from MusicBrainz and ListenBrainz")
            val delayed=fixture.arm("delayed-old-query")
            query("old")
            fixture.effect(delayed,"searchDulcetDiscover",held=true)
            query("new")
            awaitTag("discover.item.artist.0")
            fixture.effect(delayed,"searchDulcetDiscover",responsePayload="new")
            fixture.effect(delayed,"searchDulcetDiscover",responsePayload="old")
            compose.waitForIdle()
            check(text("discover.search.field").contains("new"))
            check(!allVisibleText().contains("Obsolete old query")) {"An acknowledged old response replaced the new query rows"}
            check(text("discover.item.artist.0").contains("Fixture Artist")) {"No positive new-query artist row"}
            val beforeRecreation=fixture.cursor()
            activity.recreate()
            await("restored query and rows after reader replacement") {
                exists("discover.search.field") && text("discover.search.field").contains("new") &&
                    exists("discover.item.artist.0") && text("discover.item.artist.0").contains("Fixture Artist")
            }
            fixture.awaitRequest("searchDulcetDiscover",beforeRecreation)
            click("discover.more.artist")
            awaitTag("discover.item.artist.20")
            click("discover.item.artist.0")
            awaitTag("discover.artist.surface");capture("artist")
            click("discover.item.album.0")
            awaitTag("discover.album.surface")
            requireText("8 of 12")
            capture("album")
            val before=fixture.cursor()
            click("discover.album.edition.1")
            await("second edition selected") {
                nodeConfig("discover.album.edition.1").getOrNull(SemanticsProperties.Selected)==true
            }
            fixture.awaitRequest("getDulcetDiscoverAlbum",before)
            click("discover.back");awaitTag("discover.artist.surface")
            click("discover.back");awaitTag("discover.search.field")
            fixture.arm("normal")
            // Independent positive target, fetched before the app trigger so it cannot satisfy its trace.
            val held=fixture.rest("getDulcetDiscoverAlbum",mapOf("mbid" to mbid(201)))
                .getJSONObject("dulcetDiscoverAlbum").getJSONObject("tracks").getJSONArray("song")
                .getJSONObject(0).getJSONObject("availability").getJSONObject("libraryItems").getJSONArray("item")
            val expected=(0 until held.length()).map(held::getJSONObject).first {it.getString("type")=="song"}.getString("id")
            everywhere("held")
            click("discover.item.album.1");awaitTag("discover.album.surface")
            PlaybackObserver(context,removeAccountDataOnClose=false).use { observer ->
                observer.bind()
                click("discover.openLibrary.song.0")
                observer.awaitCurrent(expected) {compose.mainClock.advanceTimeByFrame()}
                val state=observer.state()
                check(state.queue[state.currentIndex!!].track.rawId==expected) {"Held route did not open the scoped local song"}
            }
            println("DISCOVER PHONE held-route observed=true")
        }
    }

    @Test fun offlineWishRelaunchRemoveRaceAndArrival() {
        when(required("dulcetDiscoverPhase")) {
            "before-death" -> {
                freshAccount()
                launch().use {
                    everywhere("arrival")
                    // Search publishes artist, album and song groups incrementally. Establish
                    // the actual song action before taking the transport offline.
                    ensureDisplayed("discover.wish.song.0")
                    val offline=fixture.arm("offline")
                    click("discover.wish.song.0")
                    requireText("Waiting to sync")
                    fixture.effect(offline,"addToDulcetWishlist")
                    capture("pending")
                    openWishlist();row("Fixture Song 01")
                    requireText("Waiting to sync")
                    check(context.getSharedPreferences(PROOF_PREFS,0).edit()
                        .putInt("beforeDeathPid",Process.myPid()).putBoolean("durablePendingWitnessed",true).commit())
                    File(context.filesDir,"discover-wishlist-process-before.json")
                        .writeText(JSONObject().put("pid",Process.myPid()).put("pending",true).toString())
                    println("DISCOVER PHONE before-death pending=true")
                }
            }
            "after-death" -> {
                val persisted=context.getSharedPreferences(PROOF_PREFS,0)
                check(persisted.getBoolean("durablePendingWitnessed",false)) {"Missing positive pending witness from previous process"}
                check(persisted.getInt("beforeDeathPid",-1)>0 && persisted.getInt("beforeDeathPid",-1)!=Process.myPid()) {"App process did not change"}
                check(AndroidAccountCredentialStore(context).activeAccountId()!=null) {"Process death must preserve the actual saved account"}
                launch().use { activity ->
                    openWishlist();row("Fixture Song 01");requireText("Waiting to sync")
                    val proofDirectory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
                    File(proofDirectory,"process-after.json").writeText(JSONObject()
                        .put("beforePid",persisted.getInt("beforeDeathPid",-1)).put("afterPid",Process.myPid())
                        .put("pendingRestored",true).toString())
                    // Restore the actual Search query before reconnect so its ordinary
                    // revalidation and later held-item action are part of this proof.
                    everywhere("arrival");openWishlist()
                    // Replay the durable A intent, lose its real response, then remove A while
                    // offline. A scan races that durable removal before reconnect sends it.
                    val lost=fixture.arm("lost-add-receipt")
                    reconnect(activity)
                    val addA=fixture.effect(lost,"addToDulcetWishlist",delivered=true).getJSONObject("replay")
                    val generationA=fixture.awaitWish(mbid(1)).getString("id")
                    fixture.arm("offline")
                    openWishlist();row("Fixture Song 01");remove("Fixture Song 01")
                    await("durable removal hides generation A") { !allVisibleText().contains("Fixture Song 01") }
                    val racedScan=fixture.arm("scan-arrival")
                    check(racedScan.getJSONObject("scan").getBoolean("completed"))
                    fixture.effect(racedScan,"startScan")
                    reconnect(activity);fixture.awaitNoWish(mbid(1))
                    fixture.replayRemovedAdd(addA)
                    fixture.awaitNoWish(mbid(1))
                    // Re-add through the canonical Search item after A's removal and scan.
                    // Reconnect may already have refreshed its availability to held.
                    click("search.open");click("discover.scope.everywhere")
                    click("discover.wish.song.0")
                    val generationB=fixture.awaitWish(mbid(1)).getString("id")
                    check(generationA!=generationB) {"Re-add reused removed generation A"}
                    // Independent server scan is acknowledged before claiming an Arrived row or local route.
                    val scan=fixture.arm("scan-arrival")
                    check(scan.getJSONObject("scan").getBoolean("completed")) {"Real fixture scan did not finish"}
                    fixture.effect(scan,"startScan")
                    val wish=fixture.awaitWish(mbid(1),"fulfilled")
                    val expected=wish.getJSONObject("libraryItem").getString("id")
                    check(fixture.libraryContains(expected)) {"Fulfilment did not add the actual server My Library member"}
                    openWishlist();click("wishlist.refresh");row("Fixture Song 01")
                    await("server fulfilment reaches Arrived UI") { rowText("Fixture Song 01").contains("Arrived") }
                    capture("arrived")
                    PlaybackObserver(context,removeAccountDataOnClose=false).use { observer ->
                        observer.bind();val arrivalRow=openWishlistRowActions(row("Fixture Song 01"));click("wishlist.arrival.$arrivalRow")
                        observer.awaitCurrent(expected) {compose.mainClock.advanceTimeByFrame()}
                    }
                    fixture.replayRemovedAdd(addA, survivingGeneration=generationB)
                    val survivors=fixture.wishes().filter {recording(it)==mbid(1)}
                    check(survivors.size==1 && survivors.single().getString("id")==generationB) {"Late generation A replaced or duplicated B"}
                    openWishlist();remove("Fixture Song 01")
                    fixture.awaitNoWish(mbid(1))
                    check(fixture.libraryContains(expected)) {"Removing the wish deleted My Library membership"}
                    // Genuine add/removal overlap, positively held before tapping Remove.
                    everywhere("race")
                    val held=fixture.arm("remove-before-add")
                    click("discover.wish.song.1")
                    fixture.effect(held,"addToDulcetWishlist",held=true)
                    openWishlist();row("Fixture Song 02")
                    fixture.stillHeld(held)
                    remove("Fixture Song 02")
                    // Removal may release the hold before this next observation. The positive
                    // pre-tap hold above proves overlap; completed mutations below prove effect.
                    await("durable remove leaves the optimistic list") { !allVisibleText().contains("Fixture Song 02") }
                    fixture.effect(held,"addToDulcetWishlist",delivered=true)
                    fixture.effect(held,"removeFromDulcetWishlist",delivered=true)
                    fixture.arm("normal");fixture.awaitNoWish(mbid(2))
                    println("DISCOVER PHONE process-death=true removed-A-replayed=true B-survived-late-A=true scan-fulfilled=true exact-library-route=true removal-retained-library=true")
                }
            }
            else -> error("Use the finite runner's before-death and after-death phases")
        }
    }

    @Test fun busyStaleAndNavidromeControl() {
        when(required("dulcetDiscoverPhase")) {
            "busy" -> {
                freshAccount()
                launch().use {
                    everywhere("positive")
                    val ack=fixture.arm("rate-limit-120")
                    query("busy");fixture.effect(ack,"searchDulcetDiscover")
                    awaitTag("discover.notice.busy")
                    check(text("discover.notice.busy").contains("120") || text("discover.notice.busy").contains("2 min")) {"120-second backoff is not explained"}
                    compose.onNodeWithTag("discover.retry").assertIsNotEnabled()
                    val cursor=fixture.cursor();Thread.sleep(800)
                    check(fixture.requests(cursor).none {it.getString("endpoint")=="searchDulcetDiscover"}) {"Retried inside the 120-second deadline"}
                    println("DISCOVER PHONE rate-limit-seconds=120 acknowledged=true")
                }
            }
            "stale" -> {
                freshAccount()
                launch().use {
                    everywhere("positive")
                    val ack=fixture.arm("stale-answer")
                    query("stale");fixture.effect(ack,"searchDulcetDiscover")
                    awaitTag("discover.notice.stale");awaitTag("discover.item.artist.0")
                    val age=text("discover.age")
                    check(listOf("7200","2h","2 hours","120 min").any {age.contains(it)}) {"Original 7200-second age is not visible"}
                    capture("stale")
                    println("DISCOVER PHONE stale-seconds=7200 rows-retained=true")
                }
            }
            "navidrome" -> {
                val baseline=fixture.control("/control/baseline")
                check(baseline.getBoolean("login") && baseline.getInt("albums")>0)
                check(!baseline.getBoolean("discoverAdvertised") && !baseline.getBoolean("wishlistAdvertised"))
                freshAccount(navidrome=true)
                launch().use {
                    val cursor=fixture.cursor()
                    click("library.open");awaitTag("library.home.0.item.0")
                    fixture.awaitRequest("getAlbumList2",cursor,"navidrome")
                    click("search.open");awaitTag("search.query")
                    check(!exists("discover.scope.everywhere")) {"Navidrome exposed Everywhere"}
                    click("library.open")
                    check(!exists("wishlist.open")) {"Navidrome exposed Wishlist"}
                    val requests=fixture.requests(cursor).filter {it.getString("channel")=="navidrome"}
                    check(requests.any {it.getString("endpoint")=="getAlbumList2"}) {"Absence requires actual healthy app browse"}
                    check(requests.none {it.getString("endpoint").contains("DulcetDiscover") || it.getString("endpoint").contains("DulcetWish")}) {"Navidrome received unsupported feature requests"}
                    println("DISCOVER PHONE navidrome-positive-browse=true feature-requests=0")
                }
            }
            else -> error("Use busy, stale and navidrome runner phases")
        }
    }

    @Test fun accountSwitchIdentityFence() {
        freshAccount()
        launch().use { activity ->
            everywhere("owner A")
            val ownerA=checkNotNull(AndroidAccountCredentialStore(context).activeAccountId())
            fixture.arm("offline");click("discover.wish.song.0");requireText("Waiting to sync")
            fixture.arm("normal")
            addAccountThroughUi(fixture.server,fixture.other,fixture.password)
            val ownerB=checkNotNull(AndroidAccountCredentialStore(context).activeAccountId())
            check(ownerA!=ownerB) {"The account switch never happened"}
            openWishlist();row("Other owner only")
            check(!allVisibleText().contains("Fixture Song 01")) {"A pending row leaked into B"}
            check(fixture.wishes(fixture.other).none {recording(it)==mbid(1)}) {"A intent was sent as B"}
            click("account.open");click("account.server.$ownerA")
            openWishlist();row("Fixture Song 01")
            // Prove a newly pending A mutation immediately before the binding is rejected.
            everywhere("owner A")
            val blocked=fixture.arm("offline")
            click("discover.wish.song.1")
            fixture.effect(blocked,"addToDulcetWishlist")
            openWishlist();check(rowText("Fixture Song 02").contains("Waiting to sync"))
            click("library.open") // The existing saved-account notice is hosted on the library root.
            val replacement=fixture.arm("identity-replacement")
            check(replacement.has("identity"))
            reconnect(activity)
            requireText("This address now belongs to a different server")
            beginAccountThroughUi(fixture.server,fixture.owner,fixture.password)
            awaitTag("account.replacement")
            val cursor=fixture.cursor()
            click("account.replacement.cancel")
            await("replacement cancelled") {!exists("account.replacement")}
            check(AndroidAccountCredentialStore(context).activeAccountId()==ownerA) {"Cancellation replaced A's account"}
            click("account.add.cancel");awaitTag("library.open")
            Thread.sleep(1500)
            check(fixture.requests(cursor).none {it.getString("endpoint") in setOf("addToDulcetWishlist","removeFromDulcetWishlist","rejectDulcetDiscoverMatch")}) {"Rejected identity sent a pending mutation"}
            check(!exists("discover.album.surface") && !exists("discover.artist.surface")) {"Old metadata navigated after account rejection"}
            println("DISCOVER PHONE switched=true pending-owner-preserved=true replacement-cancelled=true")
        }
    }

    @Test fun largestFontAndTalkBackLabels() {
        freshAccount()
        val expectedScale=required("dulcetDiscoverFontScale").toFloat()
        check(kotlin.math.abs(context.resources.configuration.fontScale-expectedScale)<.05f) {"Requested system font scale did not reach app"}
        val expectedDark=required("dulcetDiscoverTheme")=="dark"
        check((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)==expectedDark) {"Requested system appearance did not reach app"}
        launch().use {
            everywhere("accessible")
            navigationControlsRemainLabeled()
            accessible("discover.scope.everywhere");accessible("discover.item.artist.0")
            capture("search")
            click("discover.item.artist.0");awaitTag("discover.artist.surface")
            accessible("discover.back");capture("artist")
            clickDisplayed("discover.item.album.0")
            awaitTag("discover.album.surface")
            accessible("discover.album.edition.1");capture("album")
            click("discover.back");click("discover.back")
            fixture.arm("offline");click("discover.wish.song.0");requireText("Waiting to sync");visiblePending("discover.list");capture("pending")
            openWishlist();val index=row("Fixture Song 01")
            requireText("Waiting to sync");visiblePending("wishlist.list");capture("wishlist")
            openWishlistRowActions(index);accessible("wishlist.remove.$index")
            println("DISCOVER PHONE accessible=true theme=${required("dulcetDiscoverTheme")} font-scale=$expectedScale")
        }
    }

    private fun freshAccount(navidrome:Boolean=false) {
        check(context.packageName=="com.legitimateapps.dulcet.dev") {"Fixture proofs require the DEV phone app"}
        check(context.resources.configuration.smallestScreenWidthDp<600) {"This proof requires a phone emulator"}
        check(!context.packageManager.hasSystemFeature("android.hardware.type.television"))
        val store=AndroidAccountCredentialStore(context)
        check(store.activeAccountId()==null) {"Runner must clear only this disposable app before a fresh case"}
        val outcome=runBlocking {connectAndSaveAccount(AccountConnectionRequest(if(navidrome)fixture.navidrome else fixture.server,
            fixture.owner,fixture.password,allowLocalHttp=true),AccountConnector()::connect,store)}
        check(outcome is AccountConnectOutcome.Connected) {"Disposable production account connection failed"}
        check(store.activeAccountId()!=null)
        println("DISCOVER PHONE idiom=phone production-account-connected=true")
    }
    private fun launch()=ActivityScenario.launch<MainActivity>(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
    private fun everywhere(value:String) {
        click("search.open");click("discover.scope.everywhere");query(value)
        awaitTag("discover.item.artist.0");capture("search")
    }
    private fun query(value:String) {awaitTag("discover.search.field");compose.onNodeWithTag("discover.search.field").performTextReplacement(value)}
    private fun openWishlist(){if(exists("player.close"))click("player.close");click("library.open");click("wishlist.open");awaitTag("wishlist.surface")}
    private fun reconnect(activity:ActivityScenario<MainActivity>) {
        activity.moveToState(Lifecycle.State.CREATED);activity.moveToState(Lifecycle.State.RESUMED)
        if(exists("library.reconnect"))click("library.reconnect")
        else if(exists("library.connection.retry"))click("library.connection.retry")
        else if(exists("wishlist.refresh"))click("wishlist.refresh")
    }
    private fun addAccountThroughUi(url:String,user:String,password:String) {
        beginAccountThroughUi(url,user,password)
        awaitTag("library.open")
    }
    private fun beginAccountThroughUi(url:String,user:String,password:String) {
        click("account.open");click("account.add-server")
        awaitTag("account.server");compose.onNodeWithTag("account.server").performTextReplacement(url)
        compose.onNodeWithTag("account.username").performTextReplacement(user)
        compose.onNodeWithTag("account.password").performTextReplacement(password)
        click("account.allow-local-http");click("account.submit")
    }
    private fun remove(title:String){
        val index=openWishlistRowActions(row(title))
        awaitTag("wishlist.remove.$index")
        compose.onNodeWithTag("wishlist.remove.$index").performClick()
    }
    private fun openWishlistRowActions(index:Int):Int{
        awaitTag("wishlist.row.menu.$index")
        compose.onNodeWithTag("wishlist.row.menu.$index").performClick()
        return index
    }
    private fun row(title:String):Int {
        var found:Int?=null
        await("Wishlist row $title") {
            if(exists("wishlist.list"))runCatching {compose.onNodeWithTag("wishlist.list").performScrollToNode(hasText(title,substring=true))}
            val rows=compose.onAllNodes(SemanticsMatcher("Wishlist row") {node ->node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("wishlist.row.")==true},useUnmergedTree=true).fetchSemanticsNodes()
            found=rows.firstOrNull {node ->nodeText(node).contains(title)}?.config?.getOrNull(SemanticsProperties.TestTag)?.substringAfterLast('.')?.toIntOrNull()
            found!=null
        }
        return checkNotNull(found)
    }
    private fun rowText(title:String):String=text("wishlist.row.${row(title)}")
    private fun text(tag:String)=nodeText(compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode())
    private fun nodeText(node:androidx.compose.ui.semantics.SemanticsNode):String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}+
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()+
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()+
            node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()+node.children.map(::nodeText)).joinToString(" ")
    private fun nodeConfig(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().config
    private fun exists(tag:String)=compose.onAllNodes(hasTestTag(tag),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag:String){await("visible $tag"){exists(tag)}}
    private fun click(tag:String) {
        ensureDisplayed(tag)
        compose.onNodeWithTag(tag).performClick();compose.waitForIdle()
    }
    private fun clickDisplayed(tag:String) {
        ensureDisplayed(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }
    private fun visiblePending(container: String) {
        compose.onNodeWithTag(container).performScrollToNode(hasText("Waiting to sync"))
        compose.onNodeWithText("Waiting to sync").assertIsDisplayed()
    }
    private fun navigationControlsRemainLabeled() {
        listOf("library.open","browse.open","search.open","account.open").forEach(::accessible)
    }
    private fun ensureDisplayed(tag:String) {
        await("displayed $tag") {
            if (runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess) return@await true
            for (container in listOf("discover.list","discover.artist.list","discover.album.list","wishlist.list")) {
                if (!exists(container)) continue
                // Incremental groups and album tracks arrive after the surface. Search from
                // a known item on every retry, including after an earlier empty-page scroll.
                val edition = container == "discover.album.list" && tag.startsWith("discover.album.edition.")
                runCatching { compose.onNodeWithTag(container).performScrollToIndex(if (edition) 2 else 0) }
                compose.waitForIdle()
                if (!edition) runCatching { compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag)) }
                // Also bring a chip into its horizontal viewport inside the lazy item.
                runCatching { compose.onNodeWithTag(tag).performScrollTo() }
                if (runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess) return@await true
            }
            false
        }
    }
    private fun accessible(tag:String) {
        ensureDisplayed(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        val config=nodeConfig(tag)
        val name=text(tag).trim()
        check(name.isNotEmpty()) {"TalkBack click target has no label: $tag"}
        check(config.getOrNull(SemanticsActions.OnClick)!=null) {"No accessible action: $tag"}
    }
    private fun allVisibleText()=compose.onAllNodes(SemanticsMatcher("all"){true},useUnmergedTree=true).fetchSemanticsNodes().joinToString(" ",transform=::nodeText)
    private fun requireText(value:String){await("visible copy $value"){allVisibleText().contains(value)}}
    private fun await(what:String,condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(!runCatching(condition).getOrDefault(false)) {
            if (System.nanoTime()>=deadline) {
                val diagnostic="DISCOVER PHONE timeout: $what\n"+runCatching {compose.onRoot(useUnmergedTree=true).printToString()}.getOrDefault("semantics unavailable")
                println(diagnostic)
                val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
                File(directory,"${args.getString("dulcetDiscoverCaptureLabel") ?: "phone"}-failure-semantics.txt").writeText(diagnostic)
                runCatching {capture("failure")}
                error("Did not observe $what")
            }
            Thread.sleep(50)
        }
    }
    private fun capture(screen:String) {
        instrumentation.waitForIdleSync()
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val scale=minOf(1f,800f/maxOf(image.width,image.height))
        val small=if(scale<1f)Bitmap.createScaledBitmap(image,(image.width*scale).toInt(),(image.height*scale).toInt(),true) else image
        val label=args.getString("dulcetDiscoverCaptureLabel")?:"phone"
        val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
        File(directory,"$label-$screen.jpg").outputStream().use {check(small.compress(Bitmap.CompressFormat.JPEG,50,it))}
        if(small!==image)small.recycle();image.recycle()
    }
    private fun required(name:String)=checkNotNull(args.getString(name)){"Missing disposable runner argument: $name"}
    private fun mbid(n:Int)="%08x-1111-4111-8111-111111111111".format(n)
    private fun recording(wish:JSONObject)=wish.getJSONObject("externalIds").optString("musicbrainzRecordingId")

    private inner class Fixture {
        val server=required("dulcetDiscoverServerUrl").trimEnd('/')
        val navidrome=required("dulcetDiscoverNavidromeUrl").trimEnd('/')
        private val control=required("dulcetDiscoverControlUrl").trimEnd('/')
        val owner=required("dulcetDiscoverUsername")
        val other=required("dulcetDiscoverOtherUsername")
        val password=required("dulcetDiscoverPassword")
        init {
            check(required("dulcetDiscoverDisposable")=="true")
            val origin=URI(control)
            check(origin.scheme=="http" && origin.host=="127.0.0.1" && origin.port>0)
            check(server==control && navidrome=="$control/navidrome") {"Only the wrapper-owned reversed loopback proxy is allowed"}
            check(control("/control/state").getBoolean("healthy"))
        }
        fun control(path:String,body:JSONObject?=null):JSONObject=http(control+path,body)
        fun arm(name:String):JSONObject=control("/control/scenario",JSONObject().put("name",name)).also {ack ->
            check(ack.getString("scenario")==name && ack.getBoolean("armed"));check(ack.getLong("revision")>0)
            record(JSONObject().put("type","armed").put("scenario",name).put("revision",ack.getLong("revision"))
                .put("scanCompleted",ack.optJSONObject("scan")?.optBoolean("completed") ?: false))
        }
        private fun record(event: JSONObject) {
            val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
            File(directory,"acknowledgements.jsonl").appendText(event.toString()+"\n")
        }
        fun cursor():Long=control("/control/requests").getLong("next")
        fun requests(since:Long):List<JSONObject> =control("/control/requests?since=$since").getJSONArray("requests").objects()
        fun awaitRequest(endpoint:String,since:Long,channel:String="dulcet") {
            await("observed app request $endpoint"){requests(since).any {it.getString("endpoint")==endpoint && it.getString("channel")==channel}}
        }
        fun stillHeld(ack:JSONObject) {
            val effects=control("/control/state").getJSONArray("effects").objects().filter {it.getLong("revision")==ack.getLong("revision")}
            check(effects.any {it.optBoolean("held")} && effects.none {it.optBoolean("holdReleased")}) {"Add hold ended before durable UI removal overlapped it"}
            record(JSONObject().put("type","holdStillActive").put("revision",ack.getLong("revision")))
        }
        fun effect(ack:JSONObject,endpoint:String,delivered:Boolean=false,held:Boolean=false,responsePayload:String?=null):JSONObject {
            var witnessed: JSONObject?=null
            await("acknowledged ${ack.getString("scenario")} effect on $endpoint") {
                witnessed=control("/control/state").getJSONArray("effects").objects().firstOrNull {effect ->
                    effect.getLong("revision")==ack.getLong("revision") && effect.getString("endpoint")==endpoint &&
                        (!delivered || effect.optBoolean("delivered")) && (!held || effect.optBoolean("held")) &&
                        (responsePayload==null || (effect.optBoolean("response") && effect.optString("payload")==responsePayload))
                }
                witnessed!=null
            }
            record(JSONObject().put("type","effect").put("observed",checkNotNull(witnessed)))
            return checkNotNull(witnessed)
        }
        fun replayRemovedAdd(parameters:JSONObject,survivingGeneration:String?=null) {
            val replay=rest("addToDulcetWishlist",parameters.keys().asSequence().associateWith(parameters::getString))
                .getJSONObject("dulcetWishMutation")
            check(replay.getBoolean("removed") && !replay.getBoolean("applied")) {"Old add replay was not an unapplied removed generation"}
            record(JSONObject().put("type","removedAddReplay").put("operationId",parameters.getString("operationId"))
                .put("replay",replay).put("survivingGeneration",survivingGeneration))
        }
        fun rest(endpoint:String,parameters:Map<String,String> =emptyMap(),user:String=owner):JSONObject {
            val salt="android-discover-proof"
            val token=MessageDigest.getInstance("MD5").digest((password+salt).toByteArray()).joinToString(""){"%02x".format(it)}
            val query=(mapOf("u" to user,"t" to token,"s" to salt,"v" to "1.16.1","c" to "dulcet-discover-android-proof","f" to "json")+parameters)
                .entries.joinToString("&"){"${it.key}=${URLEncoder.encode(it.value,"UTF-8")}"}
            val response=http("$server/rest/$endpoint.view?$query").getJSONObject("subsonic-response")
            check(response.getString("status")=="ok") {"Disposable REST probe refused $endpoint"};return response
        }
        fun wishes(user:String=owner):List<JSONObject> =rest("getDulcetWishlist",mapOf("size" to "100"),user).getJSONObject("dulcetWishlist").optJSONArray("wish")?.objects().orEmpty()
        fun awaitWish(mbid:String,state:String?=null):JSONObject {
            var wish:JSONObject?=null
            await("server wish state ${state?:"present"}"){wish=wishes().firstOrNull {recording(it)==mbid && (state==null || it.getString("state")==state)};wish!=null}
            return checkNotNull(wish)
        }
        fun awaitNoWish(mbid:String){await("server wish absent after positively witnessed add/removal"){wishes().none {recording(it)==mbid}}}
        fun libraryContains(id:String):Boolean=rest("getDulcetLibrary",mapOf("type" to "song","size" to "100"))
            .getJSONObject("dulcetLibrary").optJSONArray("song")?.objects()?.any {it.getString("id")==id}==true
        private fun http(url:String,body:JSONObject?=null):JSONObject {
            val connection=URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=15_000;connection.readTimeout=35_000
            try {
                if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use {it.write(body.toString().toByteArray())}}
                check(connection.responseCode in 200..299) {"Disposable request failed with HTTP ${connection.responseCode}"}
                return JSONObject(connection.inputStream.bufferedReader().use {it.readText()})
            } finally {connection.disconnect()}
        }
        private fun JSONArray.objects()=(0 until length()).map(::getJSONObject)
    }
    companion object {private const val PROOF_PREFS="dulcet.discover.proof"}
}
