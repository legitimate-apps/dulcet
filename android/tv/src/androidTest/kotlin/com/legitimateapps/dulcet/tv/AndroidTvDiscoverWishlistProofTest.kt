package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.AccountConnectOutcome
import com.legitimateapps.dulcet.connectAndSaveAccount
import com.legitimateapps.dulcet.core.AccountConnectionRequest
import com.legitimateapps.dulcet.core.AccountConnector
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
import org.junit.runner.RunWith

/** The real TV account and Discover reader, on a TV image and the finite disposable fixture. */
@RunWith(AndroidJUnit4::class)
class AndroidTvDiscoverWishlistProofTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=instrumentation.targetContext
    private val fixture by lazy { Fixture() }

    @Test fun discoverShelfPlaylistSearchAndWishlistArrival() {
        freshAccount()
        val artist=fixture.rest("getDulcetDiscoverShelf",mapOf("id" to "trendingArtists","size" to "24"))
            .getJSONObject("dulcetDiscover").getJSONArray("item").getJSONObject(1)
        val songs=fixture.rest("getDulcetDiscoverShelf",mapOf("id" to "trendingSongs","size" to "24"))
            .getJSONObject("dulcetDiscover").getJSONArray("item")
        val song=songs.getJSONObject(1)
        val songMbid=song.getJSONObject("externalIds").getString("musicbrainzRecordingId")
        val playlistId=fixture.rest("searchDulcetDiscoverPlaylists",mapOf("size" to "24"))
            .getJSONObject("dulcetDiscoverPlaylists").getJSONArray("playlist").getJSONObject(0).getString("id")
        val playlistEntries=fixture.rest("getDulcetDiscoverPlaylist",mapOf("mbid" to playlistId,"size" to "24"))
            .getJSONObject("dulcetDiscoverPlaylist").getJSONObject("entries").getJSONArray("entry")
        val playlistSong=(0 until playlistEntries.length()).map(playlistEntries::getJSONObject).single {it.getInt("position")==4}.getJSONObject("item")
        val searchSong=fixture.rest("searchDulcetDiscover",mapOf("kind" to "song","query" to "arrival","size" to "20"))
            .getJSONObject("dulcetDiscover").getJSONArray("item").getJSONObject(0)
        launch().use { activity ->
            val cursor=fixture.cursor()
            select("discover.open");awaitTag("discover.home")
            fixture.awaitRequest("getDulcetDiscoverShelf",cursor)
            requireText("Data from MusicBrainz and ListenBrainz")
            requireText("Trending on ListenBrainz")
            capture("home")
            select("discover.shelf.trendingArtists.item.${artist.getString("id")}.open")
            awaitTag("discover.artist.surface");requireText("Fixture Artist");capture("artist")
            back();awaitTag("discover.home")
            select("discover.shelf.trendingAlbums.all")
            select("discover.shelf.more")
            ensureDisplayed("discover.item.23");capture("shelf-page-two")
            select("discover.item.0.open");awaitTag("discover.album.surface")
            requireText("8 of 12");capture("album")
            back();awaitTag("discover.shelf.surface")
            check(focused("discover.item.0.open")) {"Back lost the shelf album focus"}
            back();awaitTag("discover.home")
            select("discover.wish.${song.getString("id")}")
            val shelfWish=fixture.awaitWish(songMbid)
            checkSource(shelfWish,"shelf","trendingSongs")
            select("discover.shelf.publicPlaylists.all");awaitTag("discover.playlists.surface")
            select("discover.playlist.$playlistId");awaitTag("discover.playlist.surface")
            ensureDisplayed("discover.playlist.item.1")
            check(nodeText(compose.onNodeWithTag("discover.playlist.item.1",useUnmergedTree=true).fetchSemanticsNode()).contains("Fixture Song 01"))
            ensureDisplayed("discover.playlist.item.2")
            check(nodeText(compose.onNodeWithTag("discover.playlist.item.2",useUnmergedTree=true).fetchSemanticsNode()).contains("Fixture Song 01"))
            ensureDisplayed("discover.playlist.item.3")
            val missing=(0 until playlistEntries.length()).map(playlistEntries::getJSONObject).single {it.getInt("position")==3}.getJSONObject("item")
            check(!exists("discover.wish.${missing.getString("id")}")) {"Playlist recording without MBID exposed a wish"}
            select("discover.wish.${playlistSong.getString("id")}")
            val playlistWish=fixture.awaitWish(mbid(3))
            check(playlistWish.getJSONObject("source").getString("type")=="playlist")
            check(playlistWish.getJSONObject("source").getString("id")==playlistId)
            select("discover.playlist.more");ensureDisplayed("discover.playlist.item.24")
            capture("playlist")
            select("search.open");select("discover.scope.everywhere")
            select("discover.query")
            await("native TV query keyboard shown"){nativeKeyboardState().optBoolean("inputShown")}
            compose.onNodeWithTag("discover.query").performTextReplacement("arrival")
            compose.waitForIdle()
            dismissShownKeyboard(activity)
            val searchCursor=fixture.cursor()
            select("discover.submit")
            fixture.awaitRequest("searchDulcetDiscover",searchCursor)
            ensureDisplayed("discover.wish.${searchSong.getString("id")}");capture("everywhere")
            select("discover.wish.${searchSong.getString("id")}")
            fixture.awaitWish(mbid(1))
            openWishlist();requireText("Fixture Song 01");capture("wishlist")
            val scan=fixture.arm("scan-arrival")
            check(scan.getJSONObject("scan").getBoolean("completed")) {"Fixture scan did not finish"}
            fixture.effect(scan,"startScan")
            val arrived=fixture.awaitWish(mbid(1),"fulfilled")
            check(fixture.libraryContains(arrived.getJSONObject("libraryItem").getString("id")))
            select("wishlist.refresh");requireText("Arrived");capture("arrival")
            val index=wishRow("Fixture Song 01")
            select("wishlist.remove.$index")
            fixture.awaitNoWish(mbid(1));capture("removed")
            println("DISCOVER TV shelf-page=24 artist-open=true album-open=true shelf-source=true playlist-source=true playlist-page=24 duplicates=true missing-mbid-unwishable=true everywhere=true wishlist=true scan-arrival=true removed=true remote-center=true back-focus=true")
        }
    }

    @Test fun navidromeControl() {
        val baseline=fixture.control("/control/baseline")
        check(baseline.getBoolean("login") && baseline.getInt("albums")>0)
        check(!baseline.getBoolean("discoverAdvertised") && !baseline.getBoolean("wishlistAdvertised"))
        freshAccount(navidrome=true)
        launch().use {
            val cursor=fixture.cursor()
            select("library.open");awaitTag("library.home.0.item.0")
            fixture.awaitRequest("getAlbumList2",cursor,"navidrome")
            select("search.open");awaitTag("search.query")
            check(!exists("discover.scope.everywhere")) {"Navidrome exposed Everywhere"}
            check(!exists("discover.open")) {"Navidrome exposed Discover"}
            select("library.open")
            check(!exists("wishlist.open")) {"Navidrome exposed Wishlist"}
            val requests=fixture.requests(cursor).filter {it.getString("channel")=="navidrome"}
            check(requests.any {it.getString("endpoint")=="getAlbumList2"})
            check(requests.none {it.getString("endpoint").contains("DulcetDiscover") || it.getString("endpoint").contains("DulcetWish")})
            capture("navidrome")
            println("DISCOVER TV navidrome-positive-browse=true feature-requests=0 discover-hidden=true wishlist-hidden=true")
        }
    }

    private fun freshAccount(navidrome:Boolean=false) {
        check(context.packageName=="com.legitimateapps.dulcet.tv")
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {"A TV system image is required"}
        val store=AndroidAccountCredentialStore(context)
        check(store.activeAccountId()==null) {"Runner must clear only the disposable TV app"}
        val outcome=runBlocking {connectAndSaveAccount(AccountConnectionRequest(if(navidrome)fixture.navidrome else fixture.server,
            fixture.owner,fixture.password,allowLocalHttp=true),AccountConnector()::connect,store)}
        check(outcome is AccountConnectOutcome.Connected) {"Production disposable account connection failed"}
    }
    private fun launch()=ActivityScenario.launch<TvSearchActivity>(Intent(context,TvSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun openWishlist(){select("library.open");select("wishlist.open");awaitTag("wishlist.surface")}
    private fun back(){instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);compose.waitForIdle()}
    private fun nativeKeyboardState():JSONObject {
        val raw=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys input_method"))
            .bufferedReader().use {it.readText()}
        fun flag(name:String)=Regex("$name=(true|false)").find(raw)?.groupValues?.get(1)?.toBoolean()
        return JSONObject().put("inputShown",flag("mInputShown") ?: JSONObject.NULL)
            .put("inputViewShown",flag("mIsInputViewShown") ?: JSONObject.NULL)
    }
    private fun dismissShownKeyboard(activity:ActivityScenario<TvSearchActivity>) {
        val before=nativeKeyboardState()
        if(before.optBoolean("inputShown")) activity.onActivity { host ->
            val input=host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow((host.currentFocus ?: host.window.decorView).windowToken,0)
            host.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        await("native keyboard dismissed before remote submit"){!nativeKeyboardState().optBoolean("inputShown")}
        val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
        File(directory,"query-keyboard.json").writeText(JSONObject().put("before",before).put("after",nativeKeyboardState()).toString())
        compose.waitForIdle()
    }

    private fun select(tag:String) {
        ensureDisplayed(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        if(tag=="discover.query" && !focused(tag)) {
            var downs=0
            while(!focused(tag) && downs<4) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                compose.waitForIdle();downs++
            }
            val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
            File(directory,"query-focus.json").writeText(JSONObject().put("downKeys",downs).put("focused",focused(tag)).toString())
        }
        await("focus $tag"){focused(tag)}
        if(tag.startsWith("discover.shelf.trendingArtists.item.") && tag.endsWith(".open")) capture("focused-artist-action")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
    }
    private fun focused(tag:String)=compose.onAllNodes(hasTestTag(tag),useUnmergedTree=true).fetchSemanticsNodes()
        .any {it.config.getOrNull(SemanticsProperties.Focused)==true}
    private fun wishRow(title:String):Int {
        var found:Int?=null
        await("wish row $title") {
            runCatching {compose.onNodeWithTag("wishlist.surface").performScrollToNode(hasText(title,substring=true))}
            found=compose.onAllNodes(SemanticsMatcher("wish row") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("wishlist\\.row\\.[0-9]+"))==true
            },useUnmergedTree=true).fetchSemanticsNodes().firstOrNull {nodeText(it).contains(title)}
                ?.config?.getOrNull(SemanticsProperties.TestTag)?.substringAfterLast('.')?.toIntOrNull()
            found!=null
        }
        return checkNotNull(found)
    }
    private fun checkSource(wish:JSONObject,kind:String,id:String) {
        val source=wish.getJSONObject("source")
        check(source.getString("type")==kind && source.getString("id")==id)
    }
    private fun exists(tag:String)=compose.onAllNodes(hasTestTag(tag),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag:String){await("visible $tag"){exists(tag)}}
    private fun nodeText(node:androidx.compose.ui.semantics.SemanticsNode):String =
        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}+
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()+
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()+
            node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()+node.children.map(::nodeText)).joinToString(" ")
    private fun requireText(value:String){await("copy $value"){compose.onAllNodes(SemanticsMatcher("all"){true},useUnmergedTree=true).fetchSemanticsNodes().any {nodeText(it).contains(value)}}}
    private fun ensureDisplayed(tag:String) {
        await("displayed $tag") {
            if(runCatching {compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess)return@await true
            // A nested LazyRow's performScrollTo targets its horizontal axis only. TV focus
            // requests bring the action into both ancestor viewports, just as remote focus does.
            if(exists(tag)) {
                runCatching {compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)}
                compose.waitForIdle()
                if(runCatching {compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess)return@await true
            }
            val lists=compose.onAllNodes(SemanticsMatcher("scroll container") {node ->
                node.config.getOrNull(SemanticsActions.ScrollToIndex)!=null
            },useUnmergedTree=true).fetchSemanticsNodes().mapNotNull {it.config.getOrNull(SemanticsProperties.TestTag)}
            for(list in lists) {
                runCatching {compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))}
                runCatching {compose.onNodeWithTag(tag).performScrollTo()}
                if(runCatching {compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess)return@await true
            }
            false
        }
    }
    private fun await(what:String,condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(!runCatching(condition).getOrDefault(false)) {
            if(System.nanoTime()>=deadline) {
                val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
                File(directory,"failure-semantics.txt").writeText(runCatching {compose.onRoot(useUnmergedTree=true).printToString()}.getOrDefault("semantics unavailable"))
                runCatching {capture("failure")}
                error("Did not observe $what")
            }
            Thread.sleep(50)
        }
    }
    private fun capture(screen:String) {
        instrumentation.waitForIdleSync()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        var foreground=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        while(foreground!=context.packageName && System.nanoTime()<deadline) {
            Thread.sleep(100)
            foreground=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        }
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val scale=minOf(1f,800f/maxOf(image.width,image.height))
        val small=if(scale<1f)Bitmap.createScaledBitmap(image,(image.width*scale).toInt(),(image.height*scale).toInt(),true) else image
        val label=args.getString("dulcetDiscoverCaptureLabel")?:"tv"
        val directory=File(context.getExternalFilesDir(null),"discover-wishlist-proof").also {it.mkdirs()}
        File(directory,"$label-$screen.jpg").outputStream().use {check(small.compress(Bitmap.CompressFormat.JPEG,50,it))}
        if(small!==image)small.recycle();image.recycle()
        File(directory,"$label-$screen.window.json").writeText(JSONObject()
            .put("foregroundPackage",foreground ?: JSONObject.NULL).put("appPackage",context.packageName).toString())
        check(foreground==context.packageName) {"Proof is obscured by foreground package $foreground"}
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
        fun shelfEffect(ack:JSONObject,id:String):JSONObject {
            var witnessed:JSONObject?=null
            await("revision-bound shelf effect $id") {
                witnessed=control("/control/state").getJSONArray("effects").objects().firstOrNull {
                    it.getLong("revision")==ack.getLong("revision") && it.optString("shelf")==id
                }
                witnessed!=null
            }
            return checkNotNull(witnessed).also {record(JSONObject().put("type","shelfEffect").put("observed",it))}
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
}
