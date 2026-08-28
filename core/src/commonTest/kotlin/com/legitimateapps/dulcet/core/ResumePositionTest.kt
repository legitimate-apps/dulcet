package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

class ResumePositionTest {
    @Test
    fun resumePositionSurvivesStoreRecreationAndIsScopedByOpaqueAccountAndItemIds() {
        val driver = createTestDriver()
        val accountAItem = ProviderItemId("server:resume-a", "track:same-raw-id")
        val accountBItem = ProviderItemId("server:resume-b", "track:same-raw-id")
        val firstStore = PersistentResumePositionStore(
            DulcetDatabaseStore.open(driver).database,
        )
        firstStore.save(accountAItem, 41.seconds)
        firstStore.save(accountBItem, 73.seconds)

        val reopened = PersistentResumePositionStore(
            DulcetDatabaseStore.open(driver).database,
        )
        assertEquals(41.seconds, reopened.restore(accountAItem))
        assertEquals(73.seconds, reopened.restore(accountBItem))
        driver.close()
    }

    @Test
    fun pauseAndCommandDrivenSessionFinalizationPersistThroughTheSharedEffectHandler() = runTest {
        val fixture = fixture(120)
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT, WALL_CLOCK, 0.seconds),
        ).also { fixture.handler.handle(it.effects) }
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.Paused(ATTEMPT, 12.seconds),
        ).also { fixture.handler.handle(it.effects) }
        assertEquals(12.seconds, fixture.store.restore(ITEM))

        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.Resumed(ATTEMPT, 12.seconds),
        ).also { fixture.handler.handle(it.effects) }
        fixture.machine.recordPlaybackEvent(position(20, 1))
            .also { fixture.handler.handle(it.effects) }
        fixture.machine.clearQueue().also { fixture.handler.handle(it.effects) }

        assertEquals(20.seconds, fixture.store.restore(ITEM))
        fixture.close()
    }

    @Test
    fun progressingPlaybackPersistsOnThirtySecondsOfBoundedMonotonicCadence() = runTest {
        val fixture = fixture(180)
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT, WALL_CLOCK, 0.seconds),
        ).also { fixture.handler.handle(it.effects) }

        (0..14).forEach { tick ->
            fixture.machine.recordPlaybackEvent(position(tick * 2, tick * 2))
                .also { fixture.handler.handle(it.effects) }
        }
        assertNull(fixture.store.restore(ITEM))
        fixture.machine.recordPlaybackEvent(position(30, 30))
            .also { fixture.handler.handle(it.effects) }

        assertEquals(30.seconds, fixture.store.restore(ITEM))
        fixture.close()
    }

    @Test
    fun pauseTimeAndLargeMonotonicGapDoNotAdvanceTheProgressingCadence() = runTest {
        val fixture = fixture(240)
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT, WALL_CLOCK, 0.seconds),
        ).also { fixture.handler.handle(it.effects) }
        (0..10).forEach { tick ->
            fixture.machine.recordPlaybackEvent(position(tick * 2, tick * 2))
                .also { fixture.handler.handle(it.effects) }
        }
        fixture.machine.recordPlaybackEvent(PlaybackEngineEvent.Paused(ATTEMPT, 20.seconds))
            .also { fixture.handler.handle(it.effects) }
        fixture.machine.recordPlaybackEvent(PlaybackEngineEvent.Resumed(ATTEMPT, 20.seconds))
            .also { fixture.handler.handle(it.effects) }
        fixture.machine.recordPlaybackEvent(position(20, 100))
            .also { fixture.handler.handle(it.effects) }
        (1..14).forEach { tick ->
            fixture.machine.recordPlaybackEvent(position(20 + tick * 2, 100 + tick * 2))
                .also { fixture.handler.handle(it.effects) }
        }
        assertEquals(20.seconds, fixture.store.restore(ITEM))

        fixture.machine.recordPlaybackEvent(position(50, 130))
            .also { fixture.handler.handle(it.effects) }
        assertEquals(50.seconds, fixture.store.restore(ITEM))
        fixture.close()
    }

    @Test
    fun naturalEndClearsAndLaterRetirementCannotRecreateTheResumeRow() = runTest {
        val fixture = fixture(100)
        fixture.store.save(ITEM, 55.seconds)
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT, WALL_CLOCK, 55.seconds),
        ).also { fixture.handler.handle(it.effects) }

        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(ATTEMPT, 100.seconds),
        ).also { fixture.handler.handle(it.effects) }
        assertNull(fixture.store.restore(ITEM))
        fixture.machine.clearQueue().also { fixture.handler.handle(it.effects) }

        assertNull(fixture.store.restore(ITEM))
        fixture.close()
    }

    @Test
    fun submittedPlayThatReachesKnownEndClearsResumePosition() = runTest {
        val fixture = fixture(40)
        fixture.store.save(ITEM, 10.seconds)
        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(ATTEMPT, WALL_CLOCK, 0.seconds),
        ).also { fixture.handler.handle(it.effects) }
        (0..11).forEach { tick ->
            fixture.machine.recordPlaybackEvent(position(tick * 2, tick * 2))
                .also { fixture.handler.handle(it.effects) }
        }
        assertTrue(fixture.recorded.any { it is RecordedPlaybackEvent.SubmittedPlay })

        fixture.machine.recordPlaybackEvent(
            PlaybackEngineEvent.Skipped(ATTEMPT, 40.seconds, PlaybackSkipReason.User),
        ).also { fixture.handler.handle(it.effects) }

        assertNull(fixture.store.restore(ITEM))
        fixture.close()
    }

    private fun fixture(durationSeconds: Int): Fixture {
        val driver = createTestDriver()
        val store = PersistentResumePositionStore(DulcetDatabaseStore.open(driver).database)
        val recorded = mutableListOf<RecordedPlaybackEvent>()
        val handler = PlaybackCoreEffectHandler(PlaybackEventRecorder(recorded::add), store)
        val machine = PlaybackCoreStateMachine()
        machine.startPlaying(
            PlaybackSessionStart(
                queueEntryId = QueueEntryId("queue:resume"),
                playbackSessionId = PlaybackSessionId("session:resume"),
                attemptId = ATTEMPT,
                itemId = ITEM,
                initialDuration = durationSeconds.seconds,
            ),
        )
        return Fixture(driver, machine, store, handler, recorded)
    }

    private fun position(mediaSeconds: Int, monotonicSeconds: Int) =
        PlaybackEngineEvent.PositionChanged(
            ATTEMPT,
            mediaSeconds.seconds,
            PlaybackMonotonicTime(monotonicSeconds.seconds),
        )

    private data class Fixture(
        val driver: SqlDriver,
        val machine: PlaybackCoreStateMachine,
        val store: PersistentResumePositionStore,
        val handler: PlaybackCoreEffectHandler,
        val recorded: List<RecordedPlaybackEvent>,
    ) {
        fun close() = driver.close()
    }

    private companion object {
        val ITEM = ProviderItemId("server:resume", "track:opaque")
        val ATTEMPT = AttemptId("attempt:resume")
        val WALL_CLOCK = PlaybackWallClockTime(1_788_000_123_456)
    }
}
