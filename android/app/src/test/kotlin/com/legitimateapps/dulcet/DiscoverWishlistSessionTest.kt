package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.library.DiscoverWishlistSource
import java.lang.reflect.Proxy
import kotlin.test.*
import org.junit.Test

class DiscoverWishlistSessionTest {
    private class Source(val provider: String) {
        var closed = false
        lateinit var listener: (DiscoverPublication) -> Unit
        var rows = emptyList<WishRow>()
        @Suppress("UNCHECKED_CAST")
        val source = Proxy.newProxyInstance(DiscoverWishlistSource::class.java.classLoader,
            arrayOf(DiscoverWishlistSource::class.java)) { _, method, args ->
            when (method.name) {
                "observe" -> {
                    listener = args!![0] as (DiscoverPublication) -> Unit
                    publish()
                    object : DiscoverRegistration { override fun close() {} }
                }
                "close" -> { closed = true; null }
                else -> null
            }
        } as DiscoverWishlistSource
        fun publish() = listener(DiscoverPublication(provider, 1,
            DiscoverFeatureAccess("known", null, true, true, true, true, true, true, true),
            "online", "", emptyList(), rows, false, null, true, 0))
    }

    @Test fun sessionSwitchClosesOldSourceBeforePublishingB() {
        val a = Source("a"); val sessionA = DiscoverWishlistSession(a.source)
        sessionA.close()
        val b = Source("b"); val sessionB = DiscoverWishlistSession(b.source)
        assertTrue(a.closed)
        a.publish() // A callback already posted before close must not change the closed publication.
        assertNull(sessionA.publication.value)
        assertEquals("b", sessionB.publication.value?.providerInstanceId)
        sessionB.close()
    }

    @Test fun closingOneSessionDoesNotCloseAnother() {
        val a = Source("provider:one"); val b = Source("provider:two")
        val sa = DiscoverWishlistSession(a.source); val sb = DiscoverWishlistSession(b.source)
        assertNotEquals(sa.publication.value?.providerInstanceId, sb.publication.value?.providerInstanceId)
        sa.close(); assertFalse(b.closed); sb.close()
    }

    @Test fun closingSessionDoesNotDiscardSourceIntent() {
        val source = Source("a")
        val pending = WishMutation.Add("op", 1, DiscoverTarget(DiscoverKind.Album, "mbid"),
            WishSnapshot("Album", null, null, null), WishSource("search", null))
        source.rows = listOf(WishRow("op", null, pending, true, null))
        val first = DiscoverWishlistSession(source.source)
        first.close() // Closing shell state must not issue a mutation or delete the source's outbox.
        val next = DiscoverWishlistSession(source.source)
        assertEquals("op", next.publication.value?.wishes?.single()?.pendingAdd?.operationId)
        next.close()
    }

    @Test fun closedPublicationDoesNotReturnOnLateCallback() {
        val source = Source("a"); val session = DiscoverWishlistSession(source.source)
        session.close(); source.publish()
        assertNull(session.publication.value)
        assertTrue(source.closed)
    }

}
