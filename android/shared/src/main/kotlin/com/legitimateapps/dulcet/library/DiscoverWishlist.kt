package com.legitimateapps.dulcet.library

import com.legitimateapps.dulcet.core.AndroidDiscoverClient
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.DiscoverClient
import com.legitimateapps.dulcet.core.DiscoverPublication
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
    private val mutablePublication = MutableStateFlow<DiscoverPublication?>(null)
    public val publication: StateFlow<DiscoverPublication?> = mutablePublication.asStateFlow()
    private val registration = source.observe { if (!closed) mutablePublication.value = it }

    override fun close() {
        if (closed) return
        closed = true
        registration.close()
        source.close()
        mutablePublication.value = null
    }
}
