package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidDiscoverClient
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.DiscoverClient
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverItem
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverOperation
import com.legitimateapps.dulcet.core.DiscoverPublication
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.core.WishEnqueued
import com.legitimateapps.dulcet.core.WishSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Protocol-neutral shell seam. The production facade owns detection, reads and durable mutations. */
public interface DiscoverWishlistSource : DiscoverClient

public class CoreDiscoverWishlistSource(reader: AndroidLibraryReader) :
    DiscoverWishlistSource, DiscoverClient by AndroidDiscoverClient(reader)

/** One account's UI lifetime. Closing drops callbacks and display state, never durable intentions. */
public class DiscoverWishlistSession(private val source: DiscoverWishlistSource) :
    DiscoverClient by source, AutoCloseable {
    private var closed = false
    private var pendingMutations = 0
    private val mutablePublication = MutableStateFlow<DiscoverPublication?>(null)
    public val publication: StateFlow<DiscoverPublication?> = mutablePublication.asStateFlow()
    private val registration = source.observe { if (!closed) mutablePublication.value = it }

    override fun add(item: DiscoverItem, source: WishSource, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation =
        mutation(completion) { done -> this.source.add(item, source, done) }

    override fun remove(rowKey: String, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation =
        mutation(completion) { done -> source.remove(rowKey, done) }

    override fun reject(item: DiscoverItem, libraryItem: DiscoverLibraryRef, wishId: String?, completion: (DiscoverResult<WishEnqueued>) -> Unit): DiscoverOperation =
        mutation(completion) { done -> source.reject(item, libraryItem, wishId, done) }

    // Calls and source callbacks run on the UI thread. Keep the account-owned facade alive
    // until accepted mutations finish enqueueing; reader retirement still cancels it directly.
    private fun mutation(completion: (DiscoverResult<WishEnqueued>) -> Unit,
        start: ((DiscoverResult<WishEnqueued>) -> Unit) -> DiscoverOperation): DiscoverOperation {
        if (closed) {
            completion(DiscoverResult.Failed(DiscoverFailure("cancelled", null, false)))
            return object : DiscoverOperation { override fun cancel() {} }
        }
        pendingMutations++
        return start { result ->
            pendingMutations--
            if (closed) {
                if (pendingMutations == 0) source.close()
            } else {
                completion(result)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        registration.close()
        if (pendingMutations == 0) source.close()
        mutablePublication.value = null
    }
}
