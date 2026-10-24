package com.legitimateapps.dulcet.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One lazy Discover child of an existing account reader; all core access stays on its owner. */
public class AndroidDiscoverClient(private val reader: AndroidLibraryReader) : DiscoverClient {
    private val closed=AtomicBoolean(false)
    private val operations=ConcurrentHashMap.newKeySet<Operation<*>>()
    private val registrations=ConcurrentHashMap.newKeySet<Registration>()
    // Reader-thread state only. Constructor and registration do not compose a session or send I/O.
    private var client:DiscoverClient?=null
    private var generation=0L

    init { if (!reader.registerDiscover(this)) closed.set(true) }

    private fun core():DiscoverClient? {
        if (closed.get() || reader.isClosed) return null
        return client ?: reader.composed()?.session?.openDiscoverClient()?.also { client=it }
    }

    override fun observe(listener:(DiscoverPublication)->Unit):DiscoverRegistration {
        val registration=Registration(listener)
        if (closed.get() || reader.isClosed) { registration.close();return registration }
        registrations+=registration
        if (closed.get() || reader.isClosed) { registration.close();return registration }
        reader.onReader(onDropped={registration.close()}) {
            if (!registration.open.get() || closed.get()) return@onReader
            val session=try {reader.composed()?.session} catch (_:Exception){null}
            if (session==null) { unavailable(registration);return@onReader }
            val target=try {core()} catch (_:Exception){null}
            if (target==null) { unavailable(registration);return@onReader }
            val attached=target.observe { publication ->
                val access=publication.access
                val rejected=access.error?.code=="identity-changed" && !access.canSearch && !access.canReadWishlist &&
                    !access.canAddWish && !access.canRemoveWish && !access.canRejectMatch
                val authorized=session.reader.capturePublicationAuthorization(rejected)
                val copy=publication.copy(generation=++generation)
                proof { "publication generation=${copy.generation} detection=${access.detection} " +
                    "remove=${access.canRemoveWish} connection=${copy.connection} " +
                    "rows=${copy.wishes.take(3).map { "${it.key}:${it.pending}:${it.error?.code}" }}" }
                reader.onMain {
                    if (!closed.get() && registration.open.get() && authorized()) listener(copy)
                }
            }
            registration.attached.set(attached)
            if (!registration.open.get() || closed.get()) registration.detachOnReader()
        }
        return registration
    }

    private fun unavailable(registration:Registration) {
        val failure=DiscoverFailure("storage",null,true)
        val publication=DiscoverPublication(reader.account?.providerInstanceId.orEmpty(),++generation,
            DiscoverFeatureAccess("unresolved",failure,false,false,false,false,false,false,false),
            "offline","",emptyList(),emptyList(),false,failure,false,null)
        reader.onMain {
            if (!closed.get() && registration.open.get()) registration.listener(publication)
        }
    }

    private inner class Registration(val listener:(DiscoverPublication)->Unit):DiscoverRegistration {
        val open=AtomicBoolean(true)
        val attached=AtomicReference<DiscoverRegistration?>(null)
        override fun close() {
            if (!open.compareAndSet(true,false)) return
            registrations-=this
            reader.onReader { detachOnReader() }
        }
        fun detachOnReader() { attached.getAndSet(null)?.close() }
    }

    override fun setQuery(query:String)=command { it.setQuery(query) }
    override fun loadMore(kind:String)=command { it.loadMore(kind) }
    override fun refreshWishlist(filter:WishFilter)=command { it.refreshWishlist(filter) }
    private fun command(work:(DiscoverClient)->Unit) {
        if (closed.get() || reader.isClosed) return
        reader.onReader {
            if (!closed.get()) try { core()?.let(work) } catch (_:Exception) { /* No exception crosses the facade. */ }
        }
    }

    override fun artist(mbid:String,completion:(DiscoverResult<DiscoverArtist>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.artist(mbid,done) }
    override fun artistAlbums(mbid:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.artistAlbums(mbid,cursor,done) }
    override fun album(mbid:String,releaseId:String?,completion:(DiscoverResult<DiscoverAlbum>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.album(mbid,releaseId,done) }
    override fun albumTracks(mbid:String,releaseId:String,cursor:String?,completion:(DiscoverResult<DiscoverPage>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.albumTracks(mbid,releaseId,cursor,done) }
    override fun artwork(artworkId:String,completion:(DiscoverResult<ByteArray>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.artwork(artworkId,done) }
    override fun add(item:DiscoverItem,source:WishSource,completion:(DiscoverResult<WishEnqueued>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.add(item,source,done) }
    override fun remove(rowKey:String,completion:(DiscoverResult<WishEnqueued>)->Unit):DiscoverOperation =
        operation({ result ->
            proof { "remove-result row=$rowKey result=${when(result) {
                is DiscoverResult.Value -> "enqueued:${result.value.rowKey}"
                is DiscoverResult.Failed -> "failed:${result.error.code}:retryable=${result.error.retryable}"
            }}" }
            completion(result)
        }) { target,done ->
            proof { "remove-start row=$rowKey" }
            target.remove(rowKey,done)
        }

    /** Opt-in proof receipts omit account labels, media metadata, URLs and credentials. */
    private inline fun proof(message:()->String) {
        if(System.getProperty("dulcet.proof.readerReceipts")=="true") {
            runCatching { Log.i("DulcetReachability", "proof discover=${System.identityHashCode(this)} ${message()}") }
        }
    }
    override fun reject(item:DiscoverItem,libraryItem:DiscoverLibraryRef,wishId:String?,completion:(DiscoverResult<WishEnqueued>)->Unit):DiscoverOperation =
        operation(completion) { target,done -> target.reject(item,libraryItem,wishId,done) }

    private fun <T> operation(completion:(DiscoverResult<T>)->Unit,start:(DiscoverClient,(DiscoverResult<T>)->Unit)->DiscoverOperation):DiscoverOperation {
        val operation=Operation(completion)
        operations+=operation
        if (closed.get() || reader.isClosed) { operation.cancel();return operation }
        reader.onReader(onDropped={operation.cancel()}) {
            if (operation.cancelled.get() || closed.get()) { operation.cancel();return@onReader }
            try {
                val session=reader.composed()?.session
                val target=core()
                if (session==null || target==null) { operation.finish(failed("storage",true));return@onReader }
                // Existing #349 authority, captured before this operation can send anything.
                val authorized=session.reader.captureRequestAuthorization()
                operation.authorized=authorized
                val attached=start(target,operation::finish)
                operation.attached.set(attached)
                if (operation.cancelled.get() || closed.get()) operation.cancelOnReader()
            } catch (_:Exception) { operation.finish(failed("storage",true)) }
        }
        return operation
    }

    private inner class Operation<T>(val completion:(DiscoverResult<T>)->Unit):DiscoverOperation {
        val cancelled=AtomicBoolean(false)
        val delivered=AtomicBoolean(false)
        val attached=AtomicReference<DiscoverOperation?>(null)
        @Volatile var authorized:()->Boolean={false}
        override fun cancel() {
            cancelled.set(true)
            finish(failed("cancelled"))
            reader.onReader { cancelOnReader() }
        }
        fun cancelOnReader() { attached.getAndSet(null)?.cancel() }
        fun finish(result:DiscoverResult<T>) {
            if (delivered.get()) return
            reader.onMain(checkClosed=false) {
                if (!delivered.compareAndSet(false,true)) return@onMain
                operations-=this
                val answer=when {
                    cancelled.get() || closed.get() || reader.isClosed -> failed("cancelled")
                    result is DiscoverResult.Value && !authorized() -> failed("identity-changed")
                    else -> result
                }
                completion(answer)
            }
        }
    }

    /** Synchronously revokes queued delivery; durable intents are never removed by closing. */
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        registrations.toList().forEach { it.close() }
        operations.toList().forEach { it.cancel() }
        reader.unregisterDiscover(this)
        reader.onReader { closeFromReader() }
    }

    /** Parent invokes after synchronous close, on its owner, before closing the shared store. */
    internal fun closeFromReader() {
        registrations.toList().forEach { it.detachOnReader() }
        operations.toList().forEach { it.cancelOnReader() }
        client?.close();client=null
    }

    private fun failed(code:String,retryable:Boolean=false):DiscoverResult.Failed =
        DiscoverResult.Failed(DiscoverFailure(code,null,retryable))
}
