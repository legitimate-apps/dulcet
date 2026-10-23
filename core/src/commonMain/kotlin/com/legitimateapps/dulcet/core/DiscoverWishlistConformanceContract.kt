@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.native.HiddenFromObjC
import kotlin.time.Clock

@HiddenFromObjC
public data class DiscoverConformanceRequest(val serverUrl:String,val navidromeUrl:String,val controlUrl:String,
    val username:String,val password:String,val otherUsername:String,val otherPassword:String) {
    override fun toString()="DiscoverConformanceRequest(<redacted>)"
}
@HiddenFromObjC
public data class DiscoverConformanceObservation(val restRequests:List<String>,val navidromeAdditionalRequests:List<String>,
    val scanFulfilled:Boolean,val myLibraryContainsArrival:Boolean,val wishAbsentAfterRemoval:Boolean)
@HiddenFromObjC
public data class DiscoverComponentObservation(val firstPageRows:Int,val secondPageRows:Int,val partialMatchedTracks:Int?,
    val artworkValid:Boolean,val pendingAfterLostReceipt:Long?,val pendingAfterRemoval:Long?,
    val wishAbsentAfterRemoval:Boolean,val otherOwnerUnchanged:Boolean,val restRequests:List<String>)
@HiddenFromObjC
public data class DiscoverNavidromeBaseline(val login:Boolean,val albumCount:Int,val discoverAdvertised:Boolean,
    val wishlistAdvertised:Boolean,val requests:List<String>)

/** Fixture-only probes of the existing REST components, not a replacement DiscoverClient/session. */
@HiddenFromObjC
public object DiscoverWishlistConformanceContract {
    /** Task 7 supplies the actual session and its fence. Keep its conformance tests red until then. */
    public suspend fun observe(request:DiscoverConformanceRequest):DiscoverConformanceObservation {
        requireFixture(request)
        error("Task 7 required: LibraryReaderSession.openDiscoverClient and its authoritative identity fence are not implemented")
    }
    public suspend fun navidromeBaseline(request:DiscoverConformanceRequest):DiscoverNavidromeBaseline {
        requireFixture(request)
        return withControl(request) {control ->
            val start=control.call("/control/requests").discoverInteger("next")!!
            val body=control.call("/control/baseline")
            val endpoints=control.call("/control/requests?since=$start").discoverRows("requests")
                .filter {it.discoverText("channel")=="navidrome"}.map {it.discoverRequired("endpoint")}
            DiscoverNavidromeBaseline(body.discoverFlag("login")==true,body.discoverInteger("albums")!!,
                body.discoverFlag("discoverAdvertised")==true,body.discoverFlag("wishlistAdvertised")==true,endpoints)
        }
    }
    /** Tasks 2–6 only: real REST adapters and durable wish replay, with no session lifecycle claim. */
    public suspend fun observeComponents(request:DiscoverConformanceRequest):DiscoverComponentObservation {
        requireFixture(request)
        return withControl(request) {control ->
            control.call("/control/reset",JsonObject(emptyMap()))
            val start=control.call("/control/requests").discoverInteger("next")!!
            val dispatcher=newLibraryReaderDispatcher()
            val database=createLibrarySyncControlDatabase()
            val transport=KtorLibraryEndpointTransport(LibraryBrowseRequest("discover-components",request.serverUrl,request.username,request.password,true),null,null,systemHostResolver())
            val other=KtorLibraryEndpointTransport(LibraryBrowseRequest("discover-other",request.serverUrl,request.otherUsername,request.otherPassword,true),null,null,systemHostResolver())
            try {
                withContext(dispatcher) {
                    val scope=CoroutineScope(coroutineContext+SupervisorJob())
                    val seen=SeenCacheStore(database.primary,SeenCacheWallClock {Clock.System.now().toEpochMilliseconds()})
                    val reader=LibraryReader(seen.bind(CacheBinding("discover-components",request.serverUrl,request.username)),transport,scope)
                    try {
                        reader.connect()
                        val extensionReply=reader.sendChecked("getOpenSubsonicExtensions").response
                        val extensions=Json.parseToJsonElement(extensionReply.body).jsonObject
                            .discoverObj("subsonic-response").discoverRows("openSubsonicExtensions").associate { row ->
                                row.discoverRequired("name") to row.getValue("versions").jsonArray.map {it.jsonPrimitive.int}.toSet()
                            }
                        val access=DiscoverCapabilityResolver.resolve(extensions,true)
                        check(access.canSearch && access.canAddWish) {"fixture must advertise Discover and Wishlist v1"}
                        val discover=RestDiscoverTransport(reader);val wishlist=RestWishlistTransport(reader)
                        val first=discover.search(DiscoverKind.Song,"Fixture",null).value()
                        val second=discover.search(DiscoverKind.Song,"Fixture",first.nextCursor ?: error("fixture needs >20 songs")).value()
                        val album=discover.album("000000c8-1111-4111-8111-111111111111",null).value()
                        val art=discover.artwork(album.item.artworkId ?: error("fixture album needs artwork")).value()
                        val store=DiscoverStore(database.primary.database,"discover-components")
                        val outbox=WishOutbox(store,wishlist,{access})
                        val target=first.items.first {it.target?.mbid=="00000001-1111-4111-8111-111111111111"}
                        val beforeOther=other.checkedRequest("getDulcetWishlist",mapOf("size" to "100"))
                        control.scenario("lost-add-receipt")
                        val add=outbox.enqueueAdd(target,WishSource("search",null)).value()
                        outbox.flush();val pendingAfterLost=store.pendingCount()
                        val fault=control.call("/control/state")
                        check(fault.discoverInteger("hits")==1 && fault.discoverRows("effects").single().discoverFlag("delivered")==true) {"lost response was not witnessed"}
                        outbox.enqueueRemove(add.rowKey).value()
                        control.scenario("normal");outbox.flush()
                        val final=wishlist.snapshot(WishFilter()).value()
                        val absent=final.items.none {it.target==target.target}
                        val afterOther=other.checkedRequest("getDulcetWishlist",mapOf("size" to "100"))
                        val endpoints=control.call("/control/requests?since=$start").discoverRows("requests").filter {it.discoverText("channel")=="dulcet"}.map {it.discoverRequired("endpoint")}
                        outbox.close()
                        DiscoverComponentObservation(first.items.size,second.items.size,album.item.availability.matchedTrackCount,
                            validDiscoverArtwork(art),pendingAfterLost,store.pendingCount(),absent,beforeOther==afterOther,endpoints)
                    } finally {scope.cancel()}
                }
            } finally {transport.close();other.close();database.close();dispatcher.close()}
        }
    }
    private fun requireFixture(request:DiscoverConformanceRequest) {
        val base=Url(request.controlUrl)
        require(base.protocol.name=="http" && base.host=="127.0.0.1" && base.encodedPath in setOf("","/") && base.user==null)
        require(request.serverUrl==request.controlUrl && request.navidromeUrl==request.controlUrl+"/navidrome") {"only the fixture proxy is accepted"}
    }
    private suspend fun <T> withControl(request:DiscoverConformanceRequest,block:suspend (Control)->T):T {
        val http=HttpClient()
        return try {
            val control=Control(http,request.controlUrl)
            check(control.call("/control/state").discoverFlag("healthy")==true)
            block(control)
        } finally {http.close()}
    }
    private class Control(val http:HttpClient,val base:String) {
        suspend fun call(path:String,body:JsonObject?=null):JsonObject {
            val response=if(body==null) http.get(base+path) else http.post(base+path) {contentType(ContentType.Application.Json);setBody(body.toString())}
            check(response.status.value==200) {"fixture control failed"}
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        suspend fun scenario(name:String) {
            val ack=call("/control/scenario",buildJsonObject {put("name",name)})
            check(ack.discoverText("scenario")==name && ack.discoverFlag("armed")==true && ack.discoverInteger("revision")!=null)
        }
    }
    private fun <T> DiscoverResult<T>.value():T=when(this) {
        is DiscoverResult.Value -> value
        is DiscoverResult.Failed -> error("Discover component failed: ${error.code}")
    }
}
