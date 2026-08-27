package com.legitimateapps.dulcet.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

class PersistentQueueStoreTest {
    @Test
    fun queueStateSurvivesDatabaseReopenAndRemainsAccountScoped() {
        val file = Files.createTempFile("dulcet-queue-", ".db")
        val url = "jdbc:sqlite:$file"
        try {
            val firstDriver = JdbcSqliteDriver(url)
            DulcetDatabase.Schema.create(firstDriver)
            val firstDatabase = DulcetDatabaseStore.open(firstDriver).database
            val firstStore = PersistentQueueStore(firstDatabase)
            val accountA = ServerId("server:opaque-a")
            val accountB = ServerId("server:opaque-b")
            val queueA = queueState(accountA, listOf("track:a1", "track:a2"), currentIndex = 1)
            val queueB = queueState(accountB, listOf("track:b1"), currentIndex = 0)

            firstStore.replaceQueue(queueA)
            firstStore.replaceQueue(queueB)
            firstStore.activate(accountA)
            firstDriver.close()

            val reopenedDriver = JdbcSqliteDriver(url)
            val reopenedStore = PersistentQueueStore(DulcetDatabaseStore.open(reopenedDriver).database)
            assertEquals(queueA, reopenedStore.load(accountA))
            assertEquals(queueB, reopenedStore.load(accountB))
            assertEquals(accountA, reopenedStore.activeServerId())
            reopenedDriver.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun accountSwitchStopsThenFinalizesBeforeThePersistedQueueSwap() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DulcetDatabase.Schema.create(driver)
        val store = PersistentQueueStore(DulcetDatabaseStore.open(driver).database)
        val accountA = ServerId("server:switch-a")
        val accountB = ServerId("server:switch-b")
        val expectedB = queueState(accountB, listOf("track:b"), currentIndex = 0)
        store.replaceQueue(queueState(accountA, listOf("track:a"), currentIndex = 0))
        store.replaceQueue(expectedB)
        store.activate(accountA)
        val events = mutableListOf<String>()
        val coordinator = ActiveQueueCoordinator(
            store,
            object : AccountSwitchPlaybackBoundary {
                override suspend fun stopPlayback() {
                    assertEquals(accountA, store.activeServerId())
                    events += "stop"
                }

                override suspend fun finalizeCurrentScrobbleSession() {
                    assertEquals(accountA, store.activeServerId())
                    events += "finalize"
                }
            },
        )

        val loaded = coordinator.switchTo(accountB)

        assertEquals(listOf("stop", "finalize"), events)
        assertEquals(accountB, store.activeServerId())
        assertEquals(expectedB, loaded)
        driver.close()
    }

    @Test
    fun queueRejectsEntriesFromAnotherAccount() {
        assertFailsWith<IllegalArgumentException> {
            QueueState(
                serverId = ServerId("server:one"),
                entries = listOf(entry(ServerId("server:two"), "track")),
                currentIndex = 0,
                repeatMode = QueueRepeatMode.Off,
                shuffleState = QueueShuffleState.Disabled,
            )
        }
    }

    @Test
    fun shuffledOrderAndPlayNextInsertionSurviveReopenThenDisableToExactOriginalOrder() {
        val file = Files.createTempFile("dulcet-shuffle-", ".db")
        val url = "jdbc:sqlite:$file"
        val serverId = ServerId("server:shuffle")
        try {
            val firstDriver = JdbcSqliteDriver(url)
            DulcetDatabase.Schema.create(firstDriver)
            val firstStore = PersistentQueueStore(DulcetDatabaseStore.open(firstDriver).database)
            val original = listOf("a", "b", "c", "d", "e")
            firstStore.replaceQueue(queueState(serverId, original, currentIndex = 1))

            val shuffled = firstStore.enableShuffle(serverId, Random(20260827))
            assertEquals(listOf("a", "b"), shuffled.entries.take(2).map(::rawId))
            assertNotEquals(original, shuffled.entries.map(::rawId))
            val inserted = firstStore.insert(
                serverId,
                entry(serverId, "play-next", QueueAddedBy.PlayNext),
                QueueInsertionMode.PlayNext,
            )
            assertEquals("b", rawId(inserted.entries[inserted.currentIndex!!]))
            assertEquals("play-next", rawId(inserted.entries[inserted.currentIndex + 1]))
            val persistedShuffledOrder = inserted.entries.map(::rawId)
            firstDriver.close()

            val reopenedDriver = JdbcSqliteDriver(url)
            val reopenedStore = PersistentQueueStore(DulcetDatabaseStore.open(reopenedDriver).database)
            assertEquals(persistedShuffledOrder, reopenedStore.load(serverId).entries.map(::rawId))

            val restored = reopenedStore.disableShuffle(serverId)
            assertEquals(original + "play-next", restored.entries.map(::rawId))
            assertEquals("b", rawId(restored.entries[restored.currentIndex!!]))
            assertEquals(QueueShuffleState.Disabled, restored.shuffleState)
            reopenedDriver.close()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun removalClosesBothPersistedPositionGapsWithoutReshufflingPlayedEntries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DulcetDatabase.Schema.create(driver)
        val database = DulcetDatabaseStore.open(driver).database
        val store = PersistentQueueStore(database)
        val serverId = ServerId("server:remove")
        store.replaceQueue(queueState(serverId, listOf("a", "b", "c", "d", "e"), 1))
        val shuffled = store.enableShuffle(serverId, Random(71))
        val playedPrefix = shuffled.entries.take(2).map(::rawId)
        val removedId = shuffled.entries.last().queueEntryId

        val afterRemoval = store.remove(serverId, removedId)

        assertEquals(playedPrefix, afterRemoval.entries.take(2).map(::rawId))
        assertEquals("b", rawId(afterRemoval.entries[afterRemoval.currentIndex!!]))
        val originalPositions = database.queueQueries
            .selectQueueEntriesByOriginalPosition(serverId.value) { _, _, _, _, _, _, original, _ -> original }
            .executeAsList()
        val playbackPositions = database.queueQueries
            .selectQueueEntriesByPlaybackPosition(serverId.value) { _, _, _, _, _, _, _, playback -> playback }
            .executeAsList()
        assertEquals(afterRemoval.entries.indices.map(Int::toLong), originalPositions)
        assertEquals(afterRemoval.entries.indices.map(Int::toLong), playbackPositions)
        driver.close()
    }

    @Test
    fun playNextWithoutCurrentAndNormalAppendBothUseTheEndOfShuffledOrder() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DulcetDatabase.Schema.create(driver)
        val store = PersistentQueueStore(DulcetDatabaseStore.open(driver).database)
        val serverId = ServerId("server:no-current")
        store.replaceQueue(queueState(serverId, listOf("a", "b", "c"), null))
        store.enableShuffle(serverId, Random(9))

        val playNext = store.insert(
            serverId,
            entry(serverId, "next", QueueAddedBy.PlayNext),
            QueueInsertionMode.PlayNext,
        )
        assertEquals("next", rawId(playNext.entries.last()))
        val appended = store.insert(
            serverId,
            entry(serverId, "append", QueueAddedBy.AddToQueue),
            QueueInsertionMode.Append,
        )
        assertEquals(listOf("next", "append"), appended.entries.takeLast(2).map(::rawId))
        driver.close()
    }

    private fun queueState(
        serverId: ServerId,
        rawIds: List<String>,
        currentIndex: Int?,
    ): QueueState = QueueState(
        serverId = serverId,
        entries = rawIds.map { entry(serverId, it) },
        currentIndex = currentIndex,
        repeatMode = QueueRepeatMode.All,
        shuffleState = QueueShuffleState.Disabled,
    )

    private fun entry(
        serverId: ServerId,
        rawId: String,
        addedBy: QueueAddedBy = QueueAddedBy.PlayNow,
    ): QueueEntry = QueueEntry(
        queueEntryId = QueueEntryId("queue:$rawId"),
        providerItemId = ProviderItemId(serverId.value, rawId),
        sourceContext = QueueSourceContext(
            kind = QueueSourceKind.Album,
            sourceId = ProviderItemId(serverId.value, "album:$rawId"),
            displayName = "Album for $rawId",
        ),
        addedBy = addedBy,
    )

    private fun rawId(entry: QueueEntry): String = entry.providerItemId.rawId
}
