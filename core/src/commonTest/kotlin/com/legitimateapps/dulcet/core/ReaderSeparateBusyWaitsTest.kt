package com.legitimateapps.dulcet.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §16.14: the reconnect and the outboxes keep separate busy waits, each honouring the `Retry-After`
 * of the 429 it met. The round-8 review's probes B6–B8, as tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSeparateBusyWaitsTest {
    /** B6. An outbox's 429 in the reconnect's flush sets the outboxes' wait and does not delay the reconnect's epoch read. */
    @Test
    fun b6_anOutbox429DoesNotDelayTheReconnectsEpochRead() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        runCurrent()
        session.setOnline(false); runCurrent()
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        runCurrent()
        env.server.failWithStatus["star"] = 429
        env.server.retryAfter = "60"
        val folders = env.server.count("getMusicFolders")
        val stars = env.server.count("star")
        session.setOnline(true)
        runCurrent()
        val busy = session.reader.busyFor()
        env.server.failWithStatus.clear(); env.server.retryAfter = null
        assertEquals(1, env.server.count("star") - stars, "fixture: the reconnect's flush met the 429")
        assertTrue(busy != null, "fixture: the outboxes' wait is set")
        assertEquals(1, env.server.count("getMusicFolders") - folders, "the outbox's 429 delayed the reconnect's epoch read")
        assertTrue(session.reader.online, "the reconnect did not complete")
    }

    /** B7. A 429 on the reconnect's epoch read does not set the outboxes' wait: an outbox flush sends at once. */
    @Test
    fun b7_aReconnect429DoesNotDelayAnOutboxFlush() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        runCurrent()
        session.setOnline(false); runCurrent()
        env.server.failWithStatus["getScanStatus"] = 429
        env.server.failWithStatus["getMusicFolders"] = 429
        env.server.retryAfter = "60"
        session.setOnline(true)
        runCurrent()
        val offline = !session.reader.online
        env.server.failWithStatus.clear(); env.server.retryAfter = null
        val stars = env.server.count("star")
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        runCurrent()
        session.favourites.flush()
        runCurrent()
        assertTrue(offline, "fixture: the reconnect's epoch read met the 429")
        assertEquals(null, session.reader.busyFor(), "the reconnect's 429 set the outboxes' wait")
        assertEquals(1, env.server.count("star") - stars, "the reconnect's 429 delayed the outbox's flush")
    }

    /** B8. A reconnect meeting an outbox still waiting sends none of its changes, and goes on to read the epoch. */
    @Test
    fun b8_aReconnectMeetingAWaitingOutboxSendsNothingAndReadsTheEpoch() = cappedSessionTest { env ->
        val session = env.session()
        session.reader.connect()
        runCurrent()
        session.setOnline(false); runCurrent()
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        runCurrent()
        env.server.failWithStatus["star"] = 429
        env.server.retryAfter = "60"
        session.setOnline(true)
        runCurrent()
        env.server.failWithStatus.clear(); env.server.retryAfter = null
        assertTrue(session.reader.busyFor() != null, "fixture: the outboxes wait")
        session.setOnline(false); runCurrent()
        val stars = env.server.count("star")
        val folders = env.server.count("getMusicFolders")
        session.setOnline(true)
        runCurrent()
        assertEquals(0, env.server.count("star") - stars, "a reconnect sent a change while the outboxes were waiting")
        assertEquals(1, env.server.count("getMusicFolders") - folders, "the reconnect did not go on to read the epoch")
        assertTrue(session.reader.online)
    }
}
