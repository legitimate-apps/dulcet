package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.random.Random

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

internal enum class QueueInsertionMode { PlayNext, Append }

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
        val stored = state.entries.mapIndexed { index, entry ->
            StoredQueueEntry(entry, index.toLong(), index.toLong())
        }
        rewriteQueue(
            serverId = state.serverId,
            stored = stored,
            currentEntryId = state.currentIndex?.let(state.entries::get)?.queueEntryId,
            repeatMode = state.repeatMode,
            shuffleState = QueueShuffleState.Disabled,
        )
    }

    fun enableShuffle(serverId: ServerId, random: Random = Random.Default): QueueState {
        val state = load(serverId)
        if (state.shuffleState == QueueShuffleState.Enabled) return state
        val currentEntryId = state.currentIndex?.let(state.entries::get)?.queueEntryId
        val fixedPrefixSize = state.currentIndex?.plus(1) ?: 0
        val playbackOrder =
            state.entries.take(fixedPrefixSize) + state.entries.drop(fixedPrefixSize).shuffled(random)
        val playbackPositions = playbackOrder.mapIndexed { index, entry ->
            entry.queueEntryId to index.toLong()
        }.toMap()
        val stored = selectByOriginalPosition(serverId).map { row ->
            row.copy(playbackPosition = checkNotNull(playbackPositions[row.entry.queueEntryId]))
        }
        rewriteQueue(
            serverId,
            stored,
            currentEntryId,
            state.repeatMode,
            QueueShuffleState.Enabled,
        )
        return load(serverId)
    }

    fun disableShuffle(serverId: ServerId): QueueState {
        val state = load(serverId)
        if (state.shuffleState == QueueShuffleState.Disabled) return state
        val currentEntryId = state.currentIndex?.let(state.entries::get)?.queueEntryId
        val restored = selectByOriginalPosition(serverId).map { row ->
            row.copy(playbackPosition = row.originalPosition)
        }
        rewriteQueue(
            serverId,
            restored,
            currentEntryId,
            state.repeatMode,
            QueueShuffleState.Disabled,
        )
        return load(serverId)
    }

    fun insert(
        serverId: ServerId,
        entry: QueueEntry,
        mode: QueueInsertionMode,
    ): QueueState {
        require(entry.providerItemId.providerInstanceId == serverId.value)
        val state = load(serverId)
        require(state.entries.none { it.queueEntryId == entry.queueEntryId })
        val currentEntryId = state.currentIndex?.let(state.entries::get)?.queueEntryId
        val original = selectByOriginalPosition(serverId)
        val insertedOriginal = if (state.shuffleState == QueueShuffleState.Disabled) {
            val insertionIndex = insertionIndex(state.currentIndex, original.size, mode)
            original.toMutableList().apply {
                add(insertionIndex, StoredQueueEntry(entry, 0, 0))
            }.mapIndexed { index, row ->
                row.copy(originalPosition = index.toLong(), playbackPosition = index.toLong())
            }
        } else {
            original + StoredQueueEntry(
                entry = entry,
                originalPosition = original.size.toLong(),
                playbackPosition = -1,
            )
        }
        val rewritten = if (state.shuffleState == QueueShuffleState.Disabled) {
            insertedOriginal
        } else {
            val playback = selectByPlaybackPosition(serverId).toMutableList()
            playback.add(
                insertionIndex(state.currentIndex, playback.size, mode),
                insertedOriginal.last(),
            )
            val playbackPositions = playback.mapIndexed { index, row ->
                row.entry.queueEntryId to index.toLong()
            }.toMap()
            insertedOriginal.map { row ->
                row.copy(playbackPosition = checkNotNull(playbackPositions[row.entry.queueEntryId]))
            }
        }
        rewriteQueue(
            serverId,
            rewritten,
            currentEntryId,
            state.repeatMode,
            state.shuffleState,
        )
        return load(serverId)
    }

    fun remove(serverId: ServerId, queueEntryId: QueueEntryId): QueueState {
        val state = load(serverId)
        val active = if (state.shuffleState == QueueShuffleState.Enabled) {
            selectByPlaybackPosition(serverId)
        } else {
            selectByOriginalPosition(serverId)
        }
        val removeIndex = active.indexOfFirst { it.entry.queueEntryId == queueEntryId }
        if (removeIndex < 0) return state
        val oldCurrentEntryId = state.currentIndex?.let(state.entries::get)?.queueEntryId
        val remainingActive = active.filterNot { it.entry.queueEntryId == queueEntryId }
        val currentEntryId = if (oldCurrentEntryId == queueEntryId) {
            remainingActive.getOrNull(removeIndex)?.entry?.queueEntryId
                ?: remainingActive.lastOrNull()?.entry?.queueEntryId
        } else {
            oldCurrentEntryId
        }
        val original = selectByOriginalPosition(serverId)
            .filterNot { it.entry.queueEntryId == queueEntryId }
            .mapIndexed { index, row -> row.copy(originalPosition = index.toLong()) }
        val playbackPositions = remainingActive.mapIndexed { index, row ->
            row.entry.queueEntryId to index.toLong()
        }.toMap()
        val rewritten = original.map { row ->
            row.copy(playbackPosition = checkNotNull(playbackPositions[row.entry.queueEntryId]))
        }
        rewriteQueue(
            serverId,
            rewritten,
            currentEntryId,
            state.repeatMode,
            state.shuffleState,
        )
        return load(serverId)
    }

    fun setCurrentIndex(serverId: ServerId, currentIndex: Int?): QueueState {
        val updated = load(serverId).copy(currentIndex = currentIndex)
        database.transaction {
            database.queueQueries.insertQueueStateIfMissing(serverId.value)
            updateState(updated)
        }
        return load(serverId)
    }

    fun setRepeatMode(serverId: ServerId, repeatMode: QueueRepeatMode): QueueState {
        val updated = load(serverId).copy(repeatMode = repeatMode)
        database.transaction {
            database.queueQueries.insertQueueStateIfMissing(serverId.value)
            updateState(updated)
        }
        return load(serverId)
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

    private fun rewriteQueue(
        serverId: ServerId,
        stored: List<StoredQueueEntry>,
        currentEntryId: QueueEntryId?,
        repeatMode: QueueRepeatMode,
        shuffleState: QueueShuffleState,
    ) {
        val active = if (shuffleState == QueueShuffleState.Enabled) {
            stored.sortedBy(StoredQueueEntry::playbackPosition)
        } else {
            stored.sortedBy(StoredQueueEntry::originalPosition)
        }
        val currentIndex = currentEntryId?.let { id ->
            active.indexOfFirst { it.entry.queueEntryId == id }
                .takeIf { it >= 0 }
        }
        database.transaction {
            database.queueQueries.insertQueueStateIfMissing(serverId.value)
            database.queueQueries.deleteQueueEntries(serverId.value)
            stored.sortedBy(StoredQueueEntry::originalPosition).forEach { row ->
                insertEntry(
                    serverId,
                    row.entry,
                    row.originalPosition,
                    row.playbackPosition,
                )
            }
            updateState(
                QueueState(
                    serverId = serverId,
                    entries = active.map(StoredQueueEntry::entry),
                    currentIndex = currentIndex,
                    repeatMode = repeatMode,
                    shuffleState = shuffleState,
                ),
            )
        }
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

private fun insertionIndex(
    currentIndex: Int?,
    queueSize: Int,
    mode: QueueInsertionMode,
): Int = when (mode) {
    QueueInsertionMode.PlayNext -> currentIndex?.plus(1) ?: queueSize
    QueueInsertionMode.Append -> queueSize
}
