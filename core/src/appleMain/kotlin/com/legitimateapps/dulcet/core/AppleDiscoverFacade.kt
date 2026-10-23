package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine

/** Native commands over the existing account reader; no account, auth or request path is created. */
@OptIn(ExperimentalAtomicApi::class)
public class AppleDiscoverClient(private val reader:AppleLibraryReaderClient) {
    private val closed=AtomicBoolean(false)
    private val pending=AtomicReference<Set<Pending>>(emptySet())
    private val subscriptions=AtomicReference<Set<AppleDiscoverSubscription>>(emptySet())
    // Reader owner only.
    private var client:DiscoverClient?=null
    private var generation=0L
    init { if(!reader.registerDiscover(this)) closed.store(true) }

    private fun core(session:LibraryReaderSession):DiscoverClient =
        client ?: session.openDiscoverClient().also { client=it }

    public fun observe(listener:AppleDiscoverListener):AppleDiscoverSubscription {
        val subscription=AppleDiscoverSubscription(reader,this,listener)
        if(closed.load() || reader.isClosed){subscription.close();return subscription}
        subscriptions.update {it+subscription}
        if(closed.load() || reader.isClosed){subscription.close();return subscription}
        if(!reader.onSession(onUnavailable={unavailable(subscription)}) {session ->
            if(closed.load() || !subscription.active) return@onSession
            val attached=core(session).observe {publication ->
                val access=publication.access
                val rejected=access.error?.code=="identity-changed" && !access.canSearch && !access.canReadWishlist &&
                    !access.canAddWish && !access.canRemoveWish && !access.canRejectMatch
                val authorized=session.reader.capturePublicationAuthorization(rejected)
                val copy=publication.copy(generation=++generation).appleDiscover()
                reader.onMain {
                    if(!closed.load() && !reader.isClosed && authorized()) subscription.emit(copy)
                }
            }
            subscription.attach(attached)
        }) subscription.close()
        return subscription
    }

    private fun unavailable(subscription:AppleDiscoverSubscription) {
        val failure=DiscoverFailure("storage",null,true)
        val publication=DiscoverPublication(reader.discoverProviderInstanceId,++generation,
            DiscoverFeatureAccess("unresolved",failure,false,false,false,false,false,false,false),
            "offline","",emptyList(),emptyList(),false,failure,false,null).appleDiscover()
        reader.onMain {if(!closed.load() && !reader.isClosed)subscription.emit(publication)}
    }

    public fun setQuery(query:String)=command {it.setQuery(query)}
    public fun loadMore(kind:String)=command {it.loadMore(kind)}
    public fun refreshWishlist(filter:AppleWishFilter)=command {it.refreshWishlist(filter.common())}
    private fun command(work:(DiscoverClient)->Unit) {
        if(closed.load() || reader.isClosed)return
        reader.onSession {session ->
            if(!closed.load()) try {work(core(session))} catch(_:Exception){/* Never crosses Objective-C. */}
        }
    }

    public fun artist(mbid:String,completion:(AppleDiscoverArtistResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleDiscoverArtistResult()}) {target,done ->target.artist(mbid,done)}
    public fun artistAlbums(mbid:String,cursor:String?,completion:(AppleDiscoverPageResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleDiscoverPageResult()}) {target,done ->target.artistAlbums(mbid,cursor,done)}
    public fun album(mbid:String,releaseId:String?,completion:(AppleDiscoverAlbumResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleDiscoverAlbumResult()}) {target,done ->target.album(mbid,releaseId,done)}
    public fun albumTracks(mbid:String,releaseId:String,cursor:String?,completion:(AppleDiscoverPageResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleDiscoverPageResult()}) {target,done ->target.albumTracks(mbid,releaseId,cursor,done)}
    public fun artwork(artworkId:String,completion:(AppleDiscoverArtworkResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleDiscoverArtworkResult()}) {target,done ->target.artwork(artworkId,done)}
    public fun add(item:AppleDiscoverItem,source:AppleWishSource,completion:(AppleWishEnqueueResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleWishEnqueueResult()}) {target,done ->target.add(item.common(),source.common(),done)}
    public fun remove(rowKey:String,completion:(AppleWishEnqueueResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleWishEnqueueResult()}) {target,done ->target.remove(rowKey,done)}
    public fun reject(item:AppleDiscoverItem,libraryItem:AppleDiscoverLibraryRef,wishId:String?,completion:(AppleWishEnqueueResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleWishEnqueueResult()}) {target,done ->target.reject(item.common(),libraryItem.common(),wishId,done)}

    /** Cold Wishlist correction needs only its copied target and scoped local reference.
     * It uses the same Wishlist-only admission, durable command, cancellation and delivery fence
     * as [reject]; no Discover query or exported item constructor is needed. */
    public fun rejectTarget(target:AppleDiscoverTarget,libraryItem:AppleDiscoverLibraryRef,wishId:String?,
        completion:(AppleWishEnqueueResult)->Unit):AppleLibraryReaderOperation =
        operation(completion,{it.appleWishEnqueueResult()}) {client,done ->
            val item=DiscoverItem(target.mbid,target.kind,target.common(),"",null,null,null,null,null,null,null,null,
                emptyList(),false,DiscoverAvailability("unknown","unknown",null,null,null,null,emptyList()))
            client.reject(item,libraryItem.common(),wishId,done)
        }

    private fun <T,R> operation(completion:(R)->Unit,convert:(DiscoverResult<T>)->R,
        start:(DiscoverClient,(DiscoverResult<T>)->Unit)->DiscoverOperation):AppleLibraryReaderOperation {
        val operation=Operation(completion,convert)
        pending.update {it+operation}
        if(closed.load() || reader.isClosed){operation.cancel();return operation.handle}
        // Existing helper owns cancellation/drain on the reader thread. Its claim happens early,
        // so the independent public handle claims only at actual main delivery below.
        val inner=reader.operation<DiscoverResult<T>>(
            completion=operation::finish,
            failed={kind ->failed(if(kind=="cancelled" || kind=="closed") "cancelled" else "storage")},
        ) {session ->
            if(closed.load() || operation.cancelled.load()) return@operation failed("cancelled")
            operation.authorized.store(session.reader.captureRequestAuthorization())
            suspendCancellableCoroutine {continuation ->
                val attached=start(core(session)) {result ->if(continuation.isActive)continuation.resume(result)}
                continuation.invokeOnCancellation {reader.onReader {attached.cancel()}}
            }
        }
        operation.attached.store(inner)
        if(operation.cancelled.load() || closed.load())inner.cancel()
        return operation.handle
    }

    private interface Pending {fun cancel()}
    private inner class Operation<T,R>(val completion:(R)->Unit,val convert:(DiscoverResult<T>)->R):Pending {
        val handle=AppleLibraryReaderOperation()
        private val cancellation=Job()
        val cancelled=AtomicBoolean(false)
        val attached=AtomicReference<AppleLibraryReaderOperation?>(null)
        val authorized=AtomicReference<()->Boolean>({false})
        init {
            handle.attach(cancellation)
            cancellation.invokeOnCompletion {cause ->if(cause!=null){
                cancelled.store(true)
                attached.load()?.cancel()
                finish(failed("cancelled"))
            }}
        }
        override fun cancel(){handle.cancel()}
        fun finish(result:DiscoverResult<T>) {
            reader.onMain {
                if(!handle.claim())return@onMain
                pending.update {it-this}
                val answer=when {
                    cancelled.load() || closed.load() || reader.isClosed ->failed("cancelled")
                    result is DiscoverResult.Value && !authorized.load().invoke() ->failed("identity-changed")
                    else ->result
                }
                cancellation.complete()
                completion(convert(answer))
            }
        }
    }

    public fun close() {
        if(!closed.compareAndSet(false,true))return
        subscriptions.load().forEach {it.close()}
        pending.load().forEach {it.cancel()}
        reader.unregisterDiscover(this)
        reader.onReader {closeFromReader()}
    }
    internal fun unregister(subscription:AppleDiscoverSubscription){subscriptions.update {it-subscription}}
    /** Called by the parent's owner cleanup, including when its normal dispatch hook is closed. */
    internal fun closeFromReader(){client?.close();client=null}
    private fun failed(code:String):DiscoverResult.Failed =DiscoverResult.Failed(DiscoverFailure(code,null,code=="storage"))
}

public interface AppleDiscoverListener {public fun onPublication(publication:AppleDiscoverPublication)}

@OptIn(ExperimentalAtomicApi::class)
public class AppleDiscoverSubscription internal constructor(
    private val reader:AppleLibraryReaderClient,
    private val owner:AppleDiscoverClient,
    listener:AppleDiscoverListener,
) {
    private val listener=AtomicReference<AppleDiscoverListener?>(listener)
    private val registration=AtomicReference<DiscoverRegistration?>(null)
    internal val active:Boolean get()=listener.load()!=null
    public fun close(){
        if(listener.exchange(null)==null)return
        owner.unregister(this)
        reader.onReader {registration.exchange(null)?.close()}
    }
    internal fun attach(attached:DiscoverRegistration){
        registration.store(attached)
        if(!active)registration.exchange(null)?.close()
    }
    internal fun emit(publication:AppleDiscoverPublication){listener.load()?.onPublication(publication)}
}

@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.update(transform:(T)->T){
    while(true){val before=load();if(compareAndSet(before,transform(before)))return}
}
