package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase

internal data class ServerId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

internal enum class QueueSourceKind(internal val storageValue: String) {
    Album("album"),
    Playlist("playlist"),
    Search("search"),
    Artist("artist"),
}

internal data class QueueSourceContext(
    val kind: QueueSourceKind,
    val sourceId: ProviderItemId?,
    val displayName: String,
) {
    init {
        require(displayName.isNotBlank())
        if (kind == QueueSourceKind.Search) {
            require(sourceId == null)
        } else {
            requireNotNull(sourceId)
        }
    }
}

internal enum class QueueAddedBy(internal val storageValue: String) {
    PlayNow("play_now"),
    PlayNext("play_next"),
    AddToQueue("add_to_queue"),
    Autoplay("autoplay"),
}

internal data class QueueEntry(
    val queueEntryId: QueueEntryId,
    val providerItemId: ProviderItemId,
    val sourceContext: QueueSourceContext,
    val addedBy: QueueAddedBy,
) {
    init {
        val sourceId = sourceContext.sourceId
        require(sourceId == null || sourceId.providerInstanceId == providerItemId.providerInstanceId)
    }
}

internal enum class QueueRepeatMode(internal val storageValue: String) {
    Off("off"),
    All("all"),
    One("one"),
}

internal enum class QueueShuffleState { Disabled, Enabled }

internal data class QueueState(
    val serverId: ServerId,
    val entries: List<QueueEntry>,
    val currentIndex: Int?,
    val repeatMode: QueueRepeatMode,
    val shuffleState: QueueShuffleState,
) {
    init {
        require(currentIndex == null || currentIndex in entries.indices)
        require(entries.map(QueueEntry::queueEntryId).distinct().size == entries.size)
        require(entries.all { it.providerItemId.providerInstanceId == serverId.value })
    }

    internal companion object {
        fun empty(serverId: ServerId): QueueState = QueueState(
            serverId = serverId,
            entries = emptyList(),
            currentIndex = null,
            repeatMode = QueueRepeatMode.Off,
            shuffleState = QueueShuffleState.Disabled,
        )
    }
}

private data class StoredQueueEntry(
    val entry: QueueEntry,
    val originalPosition: Long,
    val playbackPosition: Long,
)

internal interface ActiveQueuePersistence {
    fun activeServerId(): ServerId?
    fun activate(serverId: ServerId)
    fun load(serverId: ServerId): QueueState
}

internal class PersistentQueueStore(
    private val database: DulcetDatabase,
) : ActiveQueuePersistence {
    fun replaceQueue(state: QueueState) {
        require(state.shuffleState == QueueShuffleState.Disabled)
        database.transaction {
            database.queueQueries.insertQueueStateIfMissing(state.serverId.value)
            database.queueQueries.deleteQueueEntries(state.serverId.value)
            state.entries.forEachIndexed { index, entry ->
                insertEntry(state.serverId, entry, index.toLong(), index.toLong())
            }
            updateState(state)
        }
    }

    override fun load(serverId: ServerId): QueueState {
        val metadata = database.queueQueries.selectQueueState(serverId.value) { current, repeat, shuffle ->
            Triple(current, repeat, shuffle)
        }.executeAsOneOrNull() ?: return QueueState.empty(serverId)
        val stored = if (metadata.third == 0L) {
            selectByOriginalPosition(serverId)
        } else {
            selectByPlaybackPosition(serverId)
        }
        check(stored.map(StoredQueueEntry::originalPosition).sorted() == stored.indices.map(Int::toLong))
        check(stored.map(StoredQueueEntry::playbackPosition).sorted() == stored.indices.map(Int::toLong))
        return QueueState(
            serverId = serverId,
            entries = stored.map(StoredQueueEntry::entry),
            currentIndex = metadata.first?.toInt(),
            repeatMode = queueRepeatMode(metadata.second),
            shuffleState = if (metadata.third == 0L) {
                QueueShuffleState.Disabled
            } else {
                QueueShuffleState.Enabled
            },
        )
    }

    override fun activeServerId(): ServerId? =
        database.queueQueries.selectActiveServerId().executeAsOneOrNull()?.let(::ServerId)

    override fun activate(serverId: ServerId) {
        database.transaction {
            database.queueQueries.insertQueueStateIfMissing(serverId.value)
            database.queueQueries.activateQueue(serverId.value)
        }
    }

    private fun updateState(state: QueueState) {
        database.queueQueries.updateQueueState(
            current_position = state.currentIndex?.toLong(),
            repeat_mode = state.repeatMode.storageValue,
            shuffle_enabled = if (state.shuffleState == QueueShuffleState.Enabled) 1 else 0,
            server_id = state.serverId.value,
        )
    }

    private fun insertEntry(
        serverId: ServerId,
        entry: QueueEntry,
        originalPosition: Long,
        playbackPosition: Long,
    ) {
        database.queueQueries.insertQueueEntry(
            server_id = serverId.value,
            queue_entry_id = entry.queueEntryId.value,
            raw_id = entry.providerItemId.rawId,
            source_context_kind = entry.sourceContext.kind.storageValue,
            source_context_raw_id = entry.sourceContext.sourceId?.rawId,
            source_context_display_name = entry.sourceContext.displayName,
            added_by = entry.addedBy.storageValue,
            original_position = originalPosition,
            playback_position = playbackPosition,
        )
    }

    private fun selectByOriginalPosition(serverId: ServerId): List<StoredQueueEntry> =
        database.queueQueries.selectQueueEntriesByOriginalPosition(serverId.value, mapper(serverId))
            .executeAsList()

    private fun selectByPlaybackPosition(serverId: ServerId): List<StoredQueueEntry> =
        database.queueQueries.selectQueueEntriesByPlaybackPosition(serverId.value, mapper(serverId))
            .executeAsList()

    private fun mapper(serverId: ServerId) =
        { queueEntryId: String,
            rawId: String,
            sourceKind: String,
            sourceRawId: String?,
            sourceDisplayName: String,
            addedBy: String,
            originalPosition: Long,
            playbackPosition: Long,
        ->
            val kind = queueSourceKind(sourceKind)
            val sourceId = sourceRawId?.let { ProviderItemId(serverId.value, it) }
            StoredQueueEntry(
                entry = QueueEntry(
                    queueEntryId = QueueEntryId(queueEntryId),
                    providerItemId = ProviderItemId(serverId.value, rawId),
                    sourceContext = QueueSourceContext(kind, sourceId, sourceDisplayName),
                    addedBy = queueAddedBy(addedBy),
                ),
                originalPosition = originalPosition,
                playbackPosition = playbackPosition,
            )
        }
}

internal interface AccountSwitchPlaybackBoundary {
    suspend fun stopPlayback()
    suspend fun finalizeCurrentScrobbleSession()
}

internal class ActiveQueueCoordinator(
    private val queues: ActiveQueuePersistence,
    private val playback: AccountSwitchPlaybackBoundary,
) {
    suspend fun switchTo(serverId: ServerId): QueueState {
        if (queues.activeServerId() == serverId) return queues.load(serverId)
        playback.stopPlayback()
        playback.finalizeCurrentScrobbleSession()
        queues.activate(serverId)
        return queues.load(serverId)
    }
}

private fun queueSourceKind(value: String): QueueSourceKind =
    QueueSourceKind.entries.singleOrNull { it.storageValue == value }
        ?: error("Unknown queue source kind")

private fun queueAddedBy(value: String): QueueAddedBy =
    QueueAddedBy.entries.singleOrNull { it.storageValue == value }
        ?: error("Unknown queue added-by value")

private fun queueRepeatMode(value: String): QueueRepeatMode =
    QueueRepeatMode.entries.singleOrNull { it.storageValue == value }
        ?: error("Unknown queue repeat mode")
