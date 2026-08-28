package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal interface ResumePositionStore {
    fun save(itemId: ProviderItemId, position: Duration)
    fun restore(itemId: ProviderItemId): Duration?
    fun clear(itemId: ProviderItemId)
}

internal class PersistentResumePositionStore(
    private val database: DulcetDatabase,
) : ResumePositionStore {
    override fun save(itemId: ProviderItemId, position: Duration) {
        require(!position.isNegative() && position.isFinite())
        database.resumePositionQueries.save(
            server_id = itemId.providerInstanceId,
            raw_id = itemId.rawId,
            position_milliseconds = position.inWholeMilliseconds,
        )
    }

    override fun restore(itemId: ProviderItemId): Duration? =
        database.resumePositionQueries.selectPosition(
            itemId.providerInstanceId,
            itemId.rawId,
        ).executeAsOneOrNull()?.milliseconds

    override fun clear(itemId: ProviderItemId) {
        database.resumePositionQueries.clear(itemId.providerInstanceId, itemId.rawId)
    }
}

/** One ordered effect dispatcher for playback delivery and protected resume-position writes. */
internal class PlaybackCoreEffectHandler(
    private val playbackEventRecorder: PlaybackEventRecorder,
    private val resumePositions: ResumePositionStore,
) {
    suspend fun handle(effects: List<PlaybackCoreEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is PlaybackCoreEffect.RecordPlaybackEvent ->
                    playbackEventRecorder.recordPlaybackEvent(effect.event)
                is PlaybackCoreEffect.PersistResumePosition ->
                    resumePositions.save(effect.itemId, effect.position)
                is PlaybackCoreEffect.ClearResumePosition ->
                    resumePositions.clear(effect.itemId)
                is PlaybackCoreEffect.AccumulatorDiagnostic -> Unit
            }
        }
    }
}
