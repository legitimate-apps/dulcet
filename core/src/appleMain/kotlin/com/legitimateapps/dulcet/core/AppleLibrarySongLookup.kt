package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One exact account-scoped local song; no title matching or Discover capability is involved. */
public class AppleLibrarySongLookup internal constructor(
    public val item: AppleLibraryReaderItem?,
    public val errorKind: String?,
)

@OptIn(ExperimentalAtomicApi::class)
internal fun lookupScopedLibrarySong(reader: AppleLibraryReaderClient, reference: AppleDiscoverLibraryRef,
    completion: (AppleLibrarySongLookup) -> Unit): AppleLibraryReaderOperation {
    val authorized = AtomicReference<() -> Boolean>({ false })
    return reader.operation(
        completion = { result ->
            val delivered = when {
                result.item != null && reader.isClosed -> AppleLibrarySongLookup(null, "closed")
                result.item != null && !authorized.load().invoke() -> AppleLibrarySongLookup(null, "identity-changed")
                else -> result
            }
            completion(delivered)
        },
        failed = { AppleLibrarySongLookup(null, it) },
    ) { session ->
        val library = session.reader
        if (reference.providerInstanceId != reader.discoverProviderInstanceId || reference.type != "song" || reference.id.isBlank()) {
            return@operation AppleLibrarySongLookup(null, "invalid-reference")
        }
        val valid = library.captureRequestAuthorization()
        authorized.store(valid)
        if (!valid()) return@operation AppleLibrarySongLookup(null, "identity-changed")
        val id = reference.id
        if (library.online) {
            val sent = library.send("getSong", mapOf("id" to id))
            sent.requireOk("getSong", mapOf("id" to id))
            if (!valid()) return@operation AppleLibrarySongLookup(null, "identity-changed")
            val track = parseLookupSong(sent.response.body, id)
                ?: return@operation AppleLibrarySongLookup(null, "not-found")
            // SongLookup can enrich metadata, but is deliberately not evidence of presence and
            // cannot restore a gone item. The cache's existing retention rule owns that decision.
            library.cache.writeEntities(CacheWriteStamp(sent.issueSeq, library.cache.now(), null),
                CacheEntitySource.SongLookup, CacheEntities(tracks = listOf(track)))
        }
        val cached = library.cache.track(id)?.takeUnless { it.row.gone || it.metadataMissing }
            ?: return@operation AppleLibrarySongLookup(null, if (library.online) "not-found" else "offline")
        val member = CacheListMember(CacheItemKind.Track, id)
        val pending = session.outbox.pending(library.cache.serverId, setOf(member))[member]
        val playability = library.trackPlayability(id, library.downloads.localTrackRawIds(library.cache.serverId))
        AppleLibrarySongLookup(cached.toItem(pending, playability).toApple(reference.providerInstanceId), null)
    }
}
