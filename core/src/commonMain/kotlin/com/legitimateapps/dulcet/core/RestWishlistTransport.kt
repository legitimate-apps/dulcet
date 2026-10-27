package com.legitimateapps.dulcet.core

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.*

internal class RestWishlistTransport(
    private val reader: LibraryReader,
    wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : WishlistTransport {
    private val wire = DiscoverRestWire(reader, wall)
    override suspend fun snapshot(filter: WishFilter): DiscoverResult<WishListSnapshot> = wire.result {
        val parameters = discoverOptional("kind",filter.kind) + discoverOptional("state",filter.state) +
            mapOf("includeFulfilledHistory" to filter.includeFulfilledHistory.toString())
        repeat(3) {
            try {
                val stamp = list(parameters + ("size" to "1")).discoverText("changed")
                val wishes = mutableListOf<DiscoverWish>()
                val cursors = mutableSetOf<String>()
                var cursor: String? = null
                var stable = true
                do {
                    val body = list(parameters + mapOf("size" to "20") + discoverOptional("cursor",cursor))
                    stable = stable && body.discoverText("changed") == stamp
                    wishes += body.discoverRows("wish").map(::parseWish)
                    cursor = body.discoverText("nextCursor")
                    if (cursor != null && !cursors.add(cursor)) invalidDiscover()
                } while (cursor != null && stable)
                val after = list(parameters + ("size" to "1")).discoverText("changed")
                stable = stable && after == stamp
                if (stable) return@result WishListSnapshot(wishes, stamp)
            } catch (failure: DiscoverWireFailure) {
                if (failure.failure.code != "cursor-expired") throw failure
            }
        }
        throw DiscoverWireFailure(DiscoverFailure("conflict",null,true))
    }
    private suspend fun list(parameters: Map<String,String>) = wire.read("getDulcetWishlist",parameters).discoverObj("dulcetWishlist")
    override suspend fun wish(id: String) = wire.result { parseWish(wire.read("getDulcetWish",mapOf("id" to id)).discoverObj("dulcetWish")) }
    override suspend fun apply(mutation: WishMutation) = wire.result {
        val parameters = mutableMapOf("operationId" to mutation.operationId)
        val endpoint = when (mutation) {
            is WishMutation.Add -> {
                parameters += mapOf("kind" to mutation.target.kind.wire,"mbid" to mutation.target.mbid,"title" to mutation.snapshot.title,
                    "sourceType" to mutation.source.type,"actionTime" to Instant.fromEpochMilliseconds(mutation.actionTimeMillis).toString())
                parameters += discoverOptional("artist",mutation.snapshot.artist) + discoverOptional("year",mutation.snapshot.year?.toString()) +
                    discoverOptional("artworkId",mutation.snapshot.artworkId) + discoverOptional("sourceId",mutation.source.id)
                "addToDulcetWishlist"
            }
            is WishMutation.Remove -> {
                if ((mutation.wishId == null) == (mutation.addOperationId == null)) invalidDiscover()
                parameters += discoverOptional("id",mutation.wishId) + discoverOptional("addOperationId",mutation.addOperationId) +
                    ("actionTime" to Instant.fromEpochMilliseconds(mutation.actionTimeMillis).toString())
                "removeFromDulcetWishlist"
            }
            is WishMutation.Reject -> {
                if (mutation.libraryItem.providerInstanceId != reader.cache.serverId) invalidDiscover()
                parameters += mapOf("kind" to mutation.target.kind.wire,"mbid" to mutation.target.mbid,"libraryType" to mutation.libraryItem.type,"libraryId" to mutation.libraryItem.id) + discoverOptional("wishId",mutation.wishId)
                "rejectDulcetDiscoverMatch"
            }
        }
        val body = wire.read(endpoint,parameters).discoverObj("dulcetWishMutation")
        WishReceipt(body.discoverFlag("applied") ?: invalidDiscover(), (body["wish"] as? JsonObject)?.let(::parseWish), body.discoverFlag("removed") ?: invalidDiscover())
    }
    private fun parseWish(body: JsonObject): DiscoverWish {
        val snapshot = body.discoverObj("snapshot")
        val source = body.discoverObj("source")
        val kind = body.discoverRequired("kind")
        return DiscoverWish(body.discoverRequired("id"),body.discoverRequired("userId"),parseDiscoverTarget(body,kind),kind,
            WishSnapshot(snapshot.discoverRequired("title"),snapshot.discoverText("artist"),snapshot.discoverInteger("year"),snapshot.discoverText("artworkId")),
            WishSource(source.discoverRequired("type"),source.discoverText("id")),body.discoverTime("addedAt") ?: invalidDiscover(),body.discoverTime("updatedAt") ?: invalidDiscover(),
            body.discoverRequired("state"),body.discoverText("note"),body.discoverTime("noteAt"),body.discoverTime("fulfilledAt"),(body["libraryItem"] as? JsonObject)?.let { parseDiscoverRef(it,reader.cache.serverId) },
            parseDiscoverAvailability(body.discoverObj("availability"),reader.cache.serverId),body.discoverRequired("revision"))
    }
}
