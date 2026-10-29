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
        var closeCount = 0
        var registrationClosed = false
        val durable = mutableListOf<String>()
        val queued = mutableListOf<() -> Unit>()
        val callbacks = mutableListOf<(DiscoverResult<WishEnqueued>) -> Unit>()
        lateinit var listener: (DiscoverPublication) -> Unit
        var rows = emptyList<WishRow>()
        @Suppress("UNCHECKED_CAST")
        val source = Proxy.newProxyInstance(DiscoverWishlistSource::class.java.classLoader,
            arrayOf(DiscoverWishlistSource::class.java)) { _, method, args ->
            when (method.name) {
                "observe" -> {
                    listener = args!![0] as (DiscoverPublication) -> Unit
                    publish()
                    object : DiscoverRegistration { override fun close() { registrationClosed = true } }
                }
                "add", "remove", "reject" -> {
                    val key = method.name
                    @Suppress("UNCHECKED_CAST")
                    val done = args!!.last() as (DiscoverResult<WishEnqueued>) -> Unit
                    callbacks += done
                    var cancelled = false
                    queued += {
                        if (!closed && !cancelled) {
                            durable += key
                            done(DiscoverResult.Value(WishEnqueued("op-$key", key)))
                        }
                    }
                    object : DiscoverOperation {
                        override fun cancel() { cancelled = true; done(DiscoverResult.Failed(DiscoverFailure("cancelled", null, false))) }
                    }
                }
                "close" -> { closed = true; closeCount++; null }
                else -> null
            }
        } as DiscoverWishlistSource
        fun publish() = listener(DiscoverPublication(provider, 1,
            DiscoverFeatureAccess("known", null, true, true, true, true, true, true, true),
            "online", "", emptyList(), rows, false, null, true, 0))
    }

    /** The source's queue has not enqueued yet when the screen closes. Old close cancels it. */
    @Test fun acceptedAddAndRemoveSurviveCloseBeforeDurableEnqueue() {
        for (removeFirst in listOf(false, true)) {
            val source = Source("a")
            val session = DiscoverWishlistSession(source.source)
            val delivered = mutableListOf<DiscoverResult<WishEnqueued>>()
            val item = DiscoverItem("song", "song", DiscoverTarget(DiscoverKind.Song, "mbid"),
                "Song", null, null, null, null, null, null, null, null, emptyList(), true,
                DiscoverAvailability("notHeld", "unknown", null, null, null, null, emptyList()))
            session.add(item, WishSource("search", null)) { delivered += it }
            session.remove("row") { delivered += it }
            session.close(); session.close()
            assertTrue(source.registrationClosed)
            assertNull(session.publication.value)
            assertFalse(source.closed, "accepted mutations still await the source queue")
            val order = if (removeFirst) source.queued.reversed() else source.queued
            order.first()()
            assertFalse(source.closed, "one mutation still awaits enqueue")
            order.last()()
            assertEquals(setOf("add", "remove"), source.durable.toSet())
            assertTrue(source.closed, "source releases after the final enqueue callback")
            assertEquals(1, source.closeCount)
            assertTrue(delivered.isEmpty(), "closed UI must not consume late callbacks")
            source.publish()
            assertNull(session.publication.value)
        }
    }

    @Test fun lateFailureReleasesClosedSourceWithoutPublishingIt() {
        val source = Source("a")
        val session = DiscoverWishlistSession(source.source)
        var delivered = false
        session.remove("row") { delivered = true }
        session.close()
        source.callbacks.single()(DiscoverResult.Failed(DiscoverFailure("cancelled", null, false)))
        assertTrue(source.closed)
        assertEquals(1, source.closeCount)
        assertFalse(delivered)
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
