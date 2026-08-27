package com.legitimateapps.dulcet.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

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

    private fun entry(serverId: ServerId, rawId: String): QueueEntry = QueueEntry(
        queueEntryId = QueueEntryId("queue:$rawId"),
        providerItemId = ProviderItemId(serverId.value, rawId),
        sourceContext = QueueSourceContext(
            kind = QueueSourceKind.Album,
            sourceId = ProviderItemId(serverId.value, "album:$rawId"),
            displayName = "Album for $rawId",
        ),
        addedBy = QueueAddedBy.PlayNow,
    )
}
