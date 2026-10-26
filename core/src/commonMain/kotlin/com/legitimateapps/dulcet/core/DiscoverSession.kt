package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One account's durable state and server deadline, owned and retired by LibraryReaderSession. */
internal class DiscoverAccount(
    database:DulcetDatabase,
    private val reader:LibraryReader,
    private val fulfilled:()->Unit,
) {
    val store=DiscoverStore(database,reader.cache.serverId)
    private var lifetime=reader.captureRequestAuthorization()
    private var retired=false
    private val clients=mutableSetOf<SessionDiscoverClient>()
    private val lock=Mutex()
    private var extensionRevision=-1L
    private val backoff=DiscoverBackoff(reader.cache::now,reader.config.monotonic) { wait ->
        if(active() && store.pendingCount()!=0L) reader.retryWishesAfter(wait)
    }
    private var extensions:Map<String,Set<Int>>?=try {
        store.read("capabilities")?.let {bytes -> Json.parseToJsonElement(bytes.decodeToString()).jsonObject.mapValues {(_,v)->v.jsonArray.map {it.jsonPrimitive.int}.toSet()} }
    } catch (_:Exception) {null}
    private var access=DiscoverCapabilityResolver.resolve(extensions,true)
    private fun active()=!retired && lifetime()
    private fun gates():DiscoverFeatureAccess = if(active()) access else access.copy(
        error=DiscoverFailure("identity-changed",null,false),canSearch=false,canReadWishlist=false,canAddWish=false,canRemoveWish=false,canRejectMatch=false)
    private val restWishlist=RestWishlistTransport(reader,reader.cache::now)
    private val restDiscover=RestDiscoverTransport(reader,reader.cache::now)
    private val wishlistTransport=object:WishlistTransport {
        override suspend fun snapshot(filter:WishFilter):DiscoverResult<WishListSnapshot> = gated({it.canReadWishlist}) {restWishlist.snapshot(filter)}
        override suspend fun wish(id:String):DiscoverResult<DiscoverWish> = gated({it.canReadWishlist}) {restWishlist.wish(id)}
        override suspend fun apply(mutation:WishMutation):DiscoverResult<WishReceipt> = gated({when(mutation){
            is WishMutation.Add -> it.canAddWish
            is WishMutation.Remove -> it.canRemoveWish
            is WishMutation.Reject -> it.canRejectMatch
        }}) {restWishlist.apply(mutation)}
    }
    private val discoverTransport=object:DiscoverTransport {
        override suspend fun search(kind:DiscoverKind,query:String,cursor:String?,limit:Int)=gated({it.canSearch}) {restDiscover.search(kind,query,cursor,limit)}
        override suspend fun artist(mbid:String)=gated({it.canSearch}) {restDiscover.artist(mbid)}
        override suspend fun artistAlbums(mbid:String,cursor:String?,limit:Int)=gated({it.canSearch}) {restDiscover.artistAlbums(mbid,cursor,limit)}
        override suspend fun album(mbid:String,releaseId:String?)=gated({it.canSearch}) {restDiscover.album(mbid,releaseId)}
        override suspend fun albumTracks(mbid:String,releaseId:String,cursor:String?,limit:Int)=gated({it.canSearch}) {restDiscover.albumTracks(mbid,releaseId,cursor,limit)}
        override suspend fun artwork(artworkId:String)=gated({it.canSearch}) {restDiscover.artwork(artworkId)}
    }
    val outbox=WishOutbox(store,wishlistTransport,::gates,::active,{reader.canSend},backoff=backoff,changed=::changed,captureAuthorization=reader::captureRequestAuthorization)
    private fun changed(){clients.toList().forEach {it.publish()}}
    private suspend fun detect(force:Boolean=false) = lock.withLock {
        if(!active()) {changed();return@withLock}
        if(!reader.canSend) return@withLock
        if(!force && extensionRevision==reader.extensionRevision && access.detection=="known") return@withLock
        try {
            val result=reader.readOpenSubsonicExtensions()
            if(!active()) return@withLock
            extensions=result;extensionRevision=reader.extensionRevision
            access=DiscoverCapabilityResolver.resolve(result,true)
            val json=buildJsonObject {result.forEach {(key,versions)->put(key,JsonArray(versions.sorted().map(::JsonPrimitive)))}}
            store.putRead("capabilities",json.toString().encodeToByteArray(),false,store.nextIssue())
        } catch(cancelled:CancellationException){throw cancelled}
          catch(failure:LibraryRequestFailure){access=DiscoverCapabilityResolver.resolve(null,false,access).copy(error=if(reader.identityIsRejected) DiscoverFailure("identity-changed",null,false) else discoverFailure(failure.error))}
          catch(_:Exception){access=DiscoverCapabilityResolver.resolve(null,false,access).copy(error=DiscoverFailure("invalid-response",null,true))}
        changed()
    }
    private suspend fun <T> gated(allowed:(DiscoverFeatureAccess)->Boolean,work:suspend ()->DiscoverResult<T>):DiscoverResult<T> {
        val authorized=reader.captureRequestAuthorization()
        detect()
        if(!active() || !authorized()) return failed("identity-changed")
        val gates=gates()
        if(!allowed(gates)) return DiscoverResult.Failed(gates.error ?: DiscoverFailure("unsupported",null,false))
        backoff.error()?.let {return DiscoverResult.Failed(it)}
        val answer=work()
        if(!active() || !authorized()) return failed("identity-changed")
        if(answer is DiscoverResult.Failed) backoff.note(answer.error)
        return answer
    }
    suspend fun flush(){
        if(store.pendingCount()==0L || !active() || !reader.canSend) return
        withContext(OutboxRequests) {detect();outbox.flush()}
        // A read may have imposed this deadline before the first wish was queued.
        if(active() && store.pendingCount()!=0L) backoff.remaining()?.let(reader::retryWishesAfter)
    }
    /** The durable namespace stays bound; only validated reader authority is renewed. */
    fun reauthorize(){
        if(retired || active()) return
        clients.toList().forEach {it.retireRequests()}
        lifetime=reader.captureRequestAuthorization()
        clients.toList().forEach {it.renewRequests()}
        backoff.remaining()?.let {if(store.pendingCount()!=0L)reader.retryWishesAfter(it)}
    }
    fun revalidate(){
        if(retired) return
        changed()
        clients.toList().forEach {it.revalidate()}
    }
    fun open():DiscoverClient {
        reader.checkConfined()
        return SessionDiscoverClient().also {if(!retired)clients+=it else it.close()}
    }
    fun close(){retired=true;reader.cancelWishRetry();clients.toList().forEach {it.close()};outbox.close()}
    private fun <T> failed(code:String):DiscoverResult<T> = DiscoverResult.Failed(DiscoverFailure(code,null,false))

    private inner class SessionDiscoverClient:DiscoverClient {
        private var closed=false
        private val scope=CoroutineScope(reader.scope.coroutineContext+SupervisorJob(reader.scope.coroutineContext[Job]))
        private var generation=0L
        private var query=""
        private var groups=emptyList<DiscoverGroup>()
        private val listeners=mutableSetOf<(DiscoverPublication)->Unit>()
        private val operations=mutableSetOf<CommandOperation<*>>()
        private var visibleWishlist=false
        private var filter=WishFilter()
        private var refreshing:Job?=null
        private fun newWishlist()=WishlistReader(store,wishlistTransport,outbox,::gates,{!closed&&active()},
            onFulfilled={fulfilled()},changed=::publish)
        private fun newSearch()=DiscoverSearch(scope,discoverTransport,store,backoff,{reader.online},{!closed&&active()}) {text,value ->query=text;groups=value;publish()}
        private fun newPages()=DiscoverPages(scope,discoverTransport,store,{reader.online},{!closed&&active()},backoff)
        private var wishlist=newWishlist()
        private var search=newSearch()
        private var pages=newPages()
        private var renewed=false
        fun retireRequests(){
            refreshing?.cancel();operations.toList().forEach {it.cancel()}
            search.close();pages.close();wishlist.close()
        }
        fun renewRequests(){
            wishlist=newWishlist();search=newSearch();pages=newPages();renewed=true
        }
        override fun observe(listener:(DiscoverPublication)->Unit):DiscoverRegistration {
            reader.checkConfined()
            if(!closed){listeners+=listener;deliver(listener)}
            return object:DiscoverRegistration {override fun close(){listeners-=listener}}
        }
        private fun deliver(listener:(DiscoverPublication)->Unit){
            val publication=try {
                DiscoverPublication(reader.cache.serverId,++generation,gates(),
                    if(reader.online && active())"online" else "offline",query,groups,
                    wishlist.rows,wishlist.loading,wishlist.error,wishlist.settled,store.pendingCount())
            } catch(_:Exception){
                val failure=DiscoverFailure("storage",null,true)
                DiscoverPublication(reader.cache.serverId,++generation,
                    gates().copy(error=failure,canSearch=false,canReadWishlist=false,canAddWish=false,canRemoveWish=false,canRejectMatch=false),
                    "offline",query,groups,emptyList(),false,failure,false,null)
            }
            try {listener(publication)} catch(_:Exception){/* Isolate observers from each other and the outbox. */}
        }

        fun publish(){if(!closed)listeners.toList().forEach(::deliver)}
        override fun setQuery(query:String){reader.checkConfined();if(!closed)search.setQuery(query)}
        override fun loadMore(kind:String){reader.checkConfined();if(!closed)search.loadMore(kind)}
        override fun artist(mbid:String,completion:(DiscoverResult<DiscoverArtist>)->Unit)=page(completion){pages.artist(mbid,it)}
        override fun artistAlbums(mbid:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit)=page(completion){pages.artistAlbums(mbid,cursor,it)}
        override fun album(mbid:String,releaseId:String?,completion:(DiscoverResult<DiscoverAlbum>)->Unit)=page(completion){pages.album(mbid,releaseId,it)}
        override fun albumTracks(mbid:String,releaseId:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit)=page(completion){pages.albumTracks(mbid,releaseId,cursor,it)}
        override fun artwork(artworkId:String,completion:(DiscoverResult<ByteArray>)->Unit)=page(completion){pages.artwork(artworkId,it)}
        private fun <T> page(completion:(DiscoverResult<T>)->Unit,start:((DiscoverResult<T>)->Unit)->DiscoverOperation):DiscoverOperation {
            reader.checkConfined()
            if(closed){completion(failed("cancelled"));return object:DiscoverOperation{override fun cancel(){}}}
            if(!active()){completion(failed("identity-changed"));return object:DiscoverOperation{override fun cancel(){}}}
            return start(completion)
        }
        override fun refreshWishlist(filter:WishFilter){
            reader.checkConfined();if(closed)return
            this.filter=filter;visibleWishlist=true
            refreshing?.cancel();refreshing=scope.launch {detect();if(!closed)wishlist.refresh(filter)}
        }
        override fun add(item:DiscoverItem,source:WishSource,completion:(DiscoverResult<WishEnqueued>)->Unit)=command(completion){outbox.enqueueAdd(item,source)}
        override fun remove(rowKey:String,completion:(DiscoverResult<WishEnqueued>)->Unit)=command(completion){outbox.enqueueRemove(rowKey)}
        override fun reject(item:DiscoverItem,libraryItem:DiscoverLibraryRef,wishId:String?,completion:(DiscoverResult<WishEnqueued>)->Unit)=command(completion){outbox.enqueueReject(item,libraryItem,wishId)}
        private fun <T> command(completion:(DiscoverResult<T>)->Unit,work:suspend ()->DiscoverResult<T>):DiscoverOperation {
            reader.checkConfined()
            val operation=CommandOperation(completion);operations+=operation
            if(closed){operation.cancel();return operation}
            val authorized=reader.captureRequestAuthorization()
            operation.job=scope.launch {
                try {
                    detect()
                    operation.finish(if(active() && authorized())work() else failed("identity-changed"))
                    if(active() && authorized()) scope.launch {flush()}
                } catch(cancelled:CancellationException){operation.cancel()}
                  catch(_:Exception){operation.finish(DiscoverResult.Failed(DiscoverFailure("storage",null,true)))}
            }
            return operation
        }
        fun revalidate(){
            if(closed)return
            if(reader.online && active()){
                // A definitive absence is not a visible feature to revalidate. As with My
                // Library, ordinary reconnects on an unsupported server must add no probe.
                // Explicit requests still redetect; unresolved answers still retry here.
                val unresolved=access.detection!="known"
                if(query.isNotEmpty() && (unresolved || access.discoverAvailable)) {
                    if(renewed)search.setQuery(query) else search.revalidate()
                }
                renewed=false
                if(visibleWishlist && (unresolved || access.wishlistAvailable))refreshWishlist(filter)
            }else {
                if(!active()) {
                    retireRequests()
                    groups=groups.map {it.copy(loading=false,error=DiscoverFailure("identity-changed",null,false))}
                }
                publish()
            }
        }
        override fun close(){
            if(closed)return
            closed=true;listeners.clear();operations.toList().forEach {it.cancel()}
            search.close();pages.close();wishlist.close();scope.cancel();clients-=this
        }
        private inner class CommandOperation<T>(private val completion:(DiscoverResult<T>)->Unit):DiscoverOperation {
            var job:Job?=null
            private var done=false
            fun finish(result:DiscoverResult<T>){if(done)return;done=true;operations-=this;completion(result)}
            override fun cancel(){job?.cancel();finish(failed("cancelled"))}
        }
    }
}
