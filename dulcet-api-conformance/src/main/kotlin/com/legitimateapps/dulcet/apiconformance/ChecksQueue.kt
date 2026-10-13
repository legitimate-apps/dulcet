package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.legitimateapps.dulcet.apiconformance.Profile.Events
import com.legitimateapps.dulcet.apiconformance.Profile.Queue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture

// ---- queue helpers -------------------------------------------------------------------------------

/** A device id unique to this run and check. */
fun Ctx.deviceId(label: String) = "conf-${run.runId}-${check.id.lowercase()}-$label"

/** Registers [deviceId] for user A (README "Queue"); it is forgotten when the run ends. */
fun Ctx.registerDevice(deviceId: String, canPlay: Boolean = true, token: String = tokenA): JsonNode {
    val ex = api("PUT", "registerDevice", "/me/devices/${id(deviceId)}", token = token, headers = mapOf("Dulcet-Device-Id" to deviceId),
        body = mapOf("name" to "Conformance ${deviceId.takeLast(12)}", "kind" to "computer", "canPlay" to canPlay, "client" to "dulcet-api-conformance $SUITE_VERSION"))
    expectStatus(ex, 200, what = "registering device $deviceId")
    val owner = token
    run.cleanups += "forget device $deviceId" to { run.http.send("forgetDevice", "DELETE", url("/me/devices/${id(deviceId)}"), mapOf("Authorization" to "Bearer $owner")); Unit }
    return body(ex)
}

/** Registers a device that can play and holds an event stream, so it is online (openapi listDevices). */
fun Ctx.onlineDevice(label: String): Pair<String, EventStream> {
    val d = deviceId(label)
    registerDevice(d)
    return d to openEvents(deviceId = d, label = label)
}

fun Ctx.queueRead(deviceId: String? = null, token: String = tokenA): Exchange =
    api("GET", "getQueue", "/me/queue", token = token, headers = deviceId?.let { mapOf("Dulcet-Device-Id" to it) } ?: emptyMap())

fun Ctx.queue(deviceId: String? = null, token: String = tokenA): JsonNode {
    val ex = queueRead(deviceId, token)
    expectStatus(ex, 200, what = "GET /me/queue")
    return body(ex)
}

fun Ctx.revisionOf(q: JsonNode): String = q.str("revision") ?: fail("the queue has no revision")
fun quoted(revision: String) = "\"$revision\""

fun Ctx.replaceQueue(deviceId: String, ifMatch: String?, items: List<String>, play: Boolean = false, currentIndex: Int = 0): Exchange {
    val headers = LinkedHashMap<String, String>()
    headers["Dulcet-Device-Id"] = deviceId
    ifMatch?.let { headers["If-Match"] = quoted(it) }
    return api("PUT", "replaceQueue", "/me/queue", headers = headers,
        body = mapOf("itemIds" to items, "currentIndex" to currentIndex, "positionMs" to 0, "shuffle" to false, "repeat" to "off", "play" to play))
}

fun Ctx.patchQueue(deviceId: String, ifMatch: String?, patch: Map<String, Any?>): Exchange {
    val headers = LinkedHashMap<String, String>()
    headers["Dulcet-Device-Id"] = deviceId
    ifMatch?.let { headers["If-Match"] = quoted(it) }
    return api("PATCH", "updateQueue", "/me/queue", headers = headers, body = patch, contentType = "application/merge-patch+json")
}

fun Ctx.insertEntries(deviceId: String, items: List<String>, at: String = "end", ifMatch: String? = null): Exchange {
    val headers = LinkedHashMap<String, String>()
    headers["Dulcet-Device-Id"] = deviceId
    ifMatch?.let { headers["If-Match"] = quoted(it) }
    return api("POST", "insertQueueEntries", "/me/queue/entries", headers = headers, body = mapOf("itemIds" to items, "at" to at))
}

fun Ctx.transfer(callerDevice: String, target: String, play: Boolean = true, ifMatch: String? = null): Exchange {
    val headers = LinkedHashMap<String, String>()
    headers["Dulcet-Device-Id"] = callerDevice
    ifMatch?.let { headers["If-Match"] = quoted(it) }
    return api("POST", "transferPlayback", "/me/queue/transfer", headers = headers, body = mapOf("deviceId" to target, "play" to play))
}

/** Makes [deviceId] the active device and returns the queue after the transfer. */
fun Ctx.makeActive(deviceId: String): JsonNode {
    val ex = transfer(deviceId, deviceId)
    expectStatus(ex, 200, what = "transferring playback to $deviceId")
    val q = body(ex)
    ensure(q.str("activeDeviceId") == deviceId) { "after a transfer to $deviceId the queue's activeDeviceId is ${q.str("activeDeviceId")}" }
    return q
}

fun Ctx.reportPlayback(deviceId: String, entryId: String?, positionMs: Long, state: String = "playing"): Exchange =
    api("PUT", "reportQueuePlayback", "/me/queue/playback", headers = mapOf("Dulcet-Device-Id" to deviceId),
        body = mapOf("deviceId" to deviceId, "entryId" to entryId, "state" to state, "positionMs" to positionMs,
            "positionAt" to Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))

fun Ctx.playback(): JsonNode {
    val ex = api("GET", "getQueuePlayback", "/me/queue/playback")
    expectStatus(ex, 200, what = "GET /me/queue/playback")
    return body(ex)
}

fun Ctx.queueEntries(): List<JsonNode> = walk("/me/queue/entries", "listQueueEntries", query = listOf("limit" to "100"))

/** A queue with [n] fixture tracks, made by [deviceId]; returns the queue. */
fun Ctx.seedQueue(deviceId: String, n: Int = 3, play: Boolean = false): JsonNode {
    val rev = revisionOf(queue(deviceId))
    val ex = replaceQueue(deviceId, rev, (0 until n).map { fixture.track(it) }, play = play)
    expectStatus(ex, 200, what = "replacing the queue")
    return body(ex)
}

fun Ctx.sendCommand(fromDevice: String, command: String, extra: Map<String, Any?> = emptyMap()): Pair<String, Exchange> {
    val cid = UUID.randomUUID().toString()
    val ex = api("POST", "sendPlaybackCommand", "/me/queue/commands", headers = mapOf("Dulcet-Device-Id" to fromDevice),
        body = mapOf("id" to cid, "command" to command) + extra)
    return cid to ex
}

private fun Ctx.expectNotActive(ex: Exchange, activeDevice: String, what: String) {
    expectProblem(ex, 409, "not-active-device", what = what)
    val current = body(ex).path("current")
    ensure(current.isObject) { "$what: the 409 carries no current queue" }
    ensure(current.str("activeDeviceId") == activeDevice) { "$what: current.activeDeviceId is ${current.str("activeDeviceId")}, not $activeDevice" }
}

private fun Ctx.expectStale(ex: Exchange, currentRevision: String, what: String) {
    expectProblem(ex, 412, "revision-mismatch", what = what)
    val b = body(ex)
    ensure(b.path("current").isObject) { "$what: the 412 carries no current queue" }
    ensure(b.path("current").str("revision") == currentRevision) { "$what: current.revision is ${b.path("current").str("revision")}, not $currentRevision" }
}

// ---- Queue profile (README §11, CONFORMANCE.md "Other profiles") ----------------------------------

fun queueChecks(): List<Check> = listOf(
    Check("DA-Q-1", Queue, "one queue per user, seen identically by every device; its ETag is the quoted revision", "README §11 Queue; openapi getQueue") {
        requireFixture()
        val d1 = deviceId("one"); val d2 = deviceId("two")
        registerDevice(d1); registerDevice(d2)
        val listed = body(api("GET", "listDevices", "/me/devices").also { expectStatus(it, 200, what = "GET /me/devices") }).path("items").map { it.str("id") }
        ensure(d1 in listed && d2 in listed) { "GET /me/devices lists $listed, missing a registered device" }
        val seed = seedQueue(d1, 2)
        val a = queueRead(d1); val b = queueRead(d2)
        expectStatus(a, 200); expectStatus(b, 200)
        val fields = listOf("revision", "entryCount", "currentEntryId", "shuffle", "repeat", "activeDeviceId")
        for (f in fields) ensure(body(a).path(f) == body(b).path(f)) { "two devices see different queue $f: ${body(a).path(f)} and ${body(b).path(f)}" }
        ensure(body(a).str("revision") == seed.str("revision")) { "GET /me/queue shows revision ${body(a).str("revision")} after the replace answered ${seed.str("revision")}" }
        ensure(a.header("ETag") == quoted(revisionOf(body(a)))) { "the queue's ETag is ${a.header("ETag")}, not the quoted revision" }
        expectStatus(api("GET", "getQueue", "/me/queue", headers = mapOf("If-None-Match" to quoted(revisionOf(body(a))))), 304, what = "GET /me/queue with its own ETag")
    },
    Check("DA-Q-2", Queue, "queue edits need If-Match: a stale one gets 412 with current and changes nothing, none gets 428; inserts need none; every edit changes the revision", "README §11; openapi replaceQueue, updateQueue, insertQueueEntries") {
        requireFixture()
        val d = deviceId("editor"); registerDevice(d)
        val base = revisionOf(queue(d))
        val replaced = replaceQueue(d, base, listOf(fixture.track(0), fixture.track(1)))
        expectStatus(replaced, 200, what = "replace with the current If-Match")
        val r1 = revisionOf(body(replaced))
        ensure(r1 != base) { "replacing the queue left its revision at $base" }
        val entries1 = queueEntries()
        ensure(entries1.map { it.str("itemId") } == listOf(fixture.track(0), fixture.track(1))) { "the entries are ${entries1.map { it.str("itemId") }} after the replace" }
        ensure(entries1.map { it.str("entryId") }.toSet().size == 2) { "entry ids are not unique: ${entries1.map { it.str("entryId") }}" }

        expectStale(replaceQueue(d, base, listOf(fixture.track(2))), r1, "a replace with the stale revision")
        expectStale(patchQueue(d, base, mapOf("repeat" to "all")), r1, "a PATCH with the stale revision")
        val after = queue(d)
        ensure(revisionOf(after) == r1) { "a refused stale write moved the revision from $r1 to ${after.str("revision")}" }
        ensure(after.str("repeat") != "all") { "a refused stale PATCH changed repeat" }
        ensure(queueEntries().map { it.str("entryId") } == entries1.map { it.str("entryId") }) { "a refused stale replace changed the entries" }
        expectProblem(replaceQueue(d, null, listOf(fixture.track(2))), 428, "precondition-required", what = "a replace without If-Match")
        expectProblem(patchQueue(d, null, mapOf("repeat" to "all")), 428, "precondition-required", what = "a PATCH without If-Match")

        val inserted = insertEntries(d, listOf(fixture.track(2)))
        expectStatus(inserted, 200, what = "an insert without If-Match")
        val r2 = revisionOf(body(inserted).path("queue"))
        ensure(r2 != r1) { "an insert did not change the revision" }
        ensure(body(inserted).path("entryIds").size() == 1) { "the insert answered entryIds ${body(inserted).path("entryIds")}" }
        ensure(body(inserted).path("queue").path("entryCount").asInt() == 3) { "after the insert entryCount is ${body(inserted).path("queue").path("entryCount")}" }
        val patched = patchQueue(d, r2, mapOf("repeat" to "all"))
        expectStatus(patched, 200, what = "a PATCH with the current revision")
        ensure(body(patched).str("repeat") == "all" && revisionOf(body(patched)) != r2) { "the PATCH answered repeat ${body(patched).str("repeat")} at revision ${body(patched).str("revision")}" }
        expectStatus(patchQueue(d, revisionOf(body(patched)), mapOf("repeat" to "off")), 200, what = "resetting repeat")
    },
    Check("DA-Q-3", Queue, "only the active device writes playback; another gets 409 not-active-device with current and moves nothing; reports keep the revision", "README §11; openapi reportQueuePlayback") {
        requireFixture()
        val (d1, _) = onlineDevice("player")
        val (d2, _) = onlineDevice("other")
        seedQueue(d1, 2)
        val q = makeActive(d1)
        val rev = revisionOf(q)
        val entry = q.str("currentEntryId")
        expectStatus(reportPlayback(d1, entry, 1000), 200, what = "a report from the active device")
        ensure(revisionOf(queue(d1)) == rev) { "a playback report changed the queue revision" }
        val pb = playback()
        ensure(pb.str("deviceId") == d1 && pb.path("positionMs").asLong() == 1000L) { "GET /me/queue/playback shows ${pb.str("deviceId")} at ${pb.path("positionMs")} ms, not $d1 at 1000" }
        expectNotActive(reportPlayback(d2, entry, 99_000), d1, "a report from a device that is not active")
        val still = playback()
        ensure(still.str("deviceId") == d1 && still.path("positionMs").asLong() == 1000L) {
            "a refused report moved the playback state to ${still.str("deviceId")} at ${still.path("positionMs")} ms"
        }
        ensure(revisionOf(queue(d1)) == rev) { "a refused report changed the queue revision" }
    },
    Check("DA-Q-4", Queue, "a transfer moves the active device and the revision; the old device gets a queue event and a 409 on its next report", "README §11; openapi transferPlayback") {
        requireFixture()
        val (d1, s1) = onlineDevice("from")
        val (d2, _) = onlineDevice("to")
        seedQueue(d1, 2)
        val before = makeActive(d1)
        val mark = s1.mark()
        val moved = transfer(d1, d2)
        expectStatus(moved, 200, what = "transferring to the other device")
        ensure(body(moved).str("activeDeviceId") == d2) { "after the transfer activeDeviceId is ${body(moved).str("activeDeviceId")}" }
        ensure(revisionOf(body(moved)) != revisionOf(before)) { "a transfer did not change the revision" }
        val ev = s1.await("queue", eventTimeoutMs, mark) { it.json?.str("activeDeviceId") == d2 }
            ?: fail("the previous active device's stream got no queue event naming $d2 within ${config.eventTimeoutSeconds} s")
        ensure(ev.json!!.path("changed").any { it.asText() == "activeDevice" }) { "the queue event's changed is ${ev.json!!.path("changed")}, without activeDevice" }
        expectNotActive(reportPlayback(d1, before.str("currentEntryId"), 5000), d2, "the old device's next report")
        val devices = body(api("GET", "listDevices", "/me/devices")).path("items").associateBy { it.str("id") }
        ensure(devices[d2]?.path("active")?.asBoolean() == true && devices[d1]?.path("active")?.asBoolean() == false) { "GET /me/devices does not show $d2 alone as active" }
        ensure(devices[d1]?.path("online")?.asBoolean() == true && devices[d2]?.path("online")?.asBoolean() == true) { "devices holding event streams are not shown online" }
    },
    Check("DA-Q-5", Queue, "transfers apply one at a time: the last wins and two simultaneous ones leave one active device every device agrees on", "README §11; openapi transferPlayback") {
        requireFixture()
        val (d1, _) = onlineDevice("race1")
        val (d2, _) = onlineDevice("race2")
        seedQueue(d1, 2)
        makeActive(d1)
        expectStatus(transfer(d1, d2), 200)
        ensure(queue(d1).str("activeDeviceId") == d2) { "after transfers to $d1 then $d2 the active device is not the last one" }
        val a = CompletableFuture.supplyAsync { transfer(d1, d1) }
        val b = CompletableFuture.supplyAsync { transfer(d2, d2) }
        val ra = a.get(); val rb = b.get()
        expectStatus(ra, 200, what = "the first simultaneous transfer"); expectStatus(rb, 200, what = "the second simultaneous transfer")
        val seen1 = queue(d1).str("activeDeviceId"); val seen2 = queue(d2).str("activeDeviceId")
        ensure(seen1 == seen2) { "the two devices disagree on the active device: $seen1 and $seen2" }
        ensure(seen1 == d1 || seen1 == d2) { "after racing transfers the active device is $seen1" }
        val active = body(api("GET", "listDevices", "/me/devices")).path("items").filter { it.path("active").asBoolean() }.map { it.str("id") }
        ensure(active == listOf(seen1)) { "GET /me/devices marks $active active, not exactly $seen1" }
    },
    Check("DA-Q-6", Queue, "an automatic transfer with a stale If-Match gets 412 and leaves the active device", "README §11; openapi transferPlayback") {
        requireFixture()
        val (d1, _) = onlineDevice("playing")
        val (d2, _) = onlineDevice("resuming")
        seedQueue(d1, 2)
        val seen = makeActive(d1)
        val edited = insertEntries(d1, listOf(fixture.track(3)))
        expectStatus(edited, 200, what = "an edit after the resuming device read the queue")
        val now = revisionOf(body(edited).path("queue"))
        expectStale(transfer(d2, d2, ifMatch = revisionOf(seen)), now, "an automatic resume with the revision it saw before the edit")
        ensure(queue(d1).str("activeDeviceId") == d1) { "a refused automatic transfer changed the active device" }
    },
    Check("DA-Q-7", Queue, "a transfer to a device that cannot play answers 409 device-unavailable", "README §11; openapi transferPlayback") {
        requireFixture()
        val (d1, _) = onlineDevice("player")
        val mute = deviceId("mute")
        registerDevice(mute, canPlay = false)
        openEvents(deviceId = mute, label = "mute")
        seedQueue(d1, 1)
        makeActive(d1)
        expectProblem(transfer(d1, mute), 409, "device-unavailable", what = "a transfer to a device registered with canPlay: false")
        ensure(queue(d1).str("activeDeviceId") == d1) { "a refused transfer changed the active device" }
    },
    Check("DA-Q-8", Queue, "a remote command answers 202 and reaches the active device's stream once, and no other", "README §11; openapi sendPlaybackCommand") {
        requireFixture()
        val (controller, sc) = onlineDevice("remote")
        val (player, sp) = onlineDevice("speaker")
        seedQueue(player, 2)
        makeActive(player)
        val mc = sc.mark(); val mp = sp.mark()
        val (cid, ex) = sendCommand(controller, "seek", mapOf("positionMs" to 120_000))
        expectStatus(ex, 202, what = "a seek command")
        ensure(body(ex).str("id") == cid && body(ex).str("deviceId") == player) { "the 202 says id ${body(ex).str("id")} to ${body(ex).str("deviceId")}, not $cid to $player" }
        val ev = sp.await("command", eventTimeoutMs, mp) { it.json?.str("id") == cid }
            ?: fail("the active device's stream got no command event $cid within ${config.eventTimeoutSeconds} s")
        val d = ev.json!!
        ensure(d.str("command") == "seek" && d.path("positionMs").asLong() == 120_000L && d.str("from") == controller) { "the command event is $d" }
        Thread.sleep(quietMs)
        ensure(sp.since(mp, "command").count { it.json?.str("id") == cid } == 1) { "the active device got command $cid more than once" }
        ensure(sc.since(mc, "command").isEmpty()) { "a device that is not active got a command event" }
    },
    Check("DA-Q-9", Queue, "with no active device a command answers 409 device-unavailable", "README §11; openapi sendPlaybackCommand, forgetDevice") {
        requireFixture()
        val (controller, _) = onlineDevice("remote")
        val (player, _) = onlineDevice("gone")
        seedQueue(player, 1)
        makeActive(player)
        expectStatus(api("DELETE", "forgetDevice", "/me/devices/${id(player)}"), 204, what = "forgetting the active device")
        ensure(queue(controller).path("activeDeviceId").isNull) { "after the active device was forgotten activeDeviceId is ${queue(controller).path("activeDeviceId")}" }
        expectProblem(sendCommand(controller, "pause").second, 409, "device-unavailable", what = "a command with no active device")
    },
    Check("DA-Q-10", Queue, "an offline device reconnecting with a stale base revision gets 412 and the server state stands; an unchanged base applies", "README §11 offline; openapi replaceQueue") {
        requireFixture()
        val offline = deviceId("offline"); val online = deviceId("online")
        registerDevice(offline); registerDevice(online)
        val base = seedQueue(online, 2)
        insertEntries(online, listOf(fixture.track(2))).also { expectStatus(it, 200, what = "an edit while the other device is offline") }
        val server = queue(online)
        val entries = queueEntries().map { it.str("entryId") }
        expectStale(replaceQueue(offline, revisionOf(base), listOf(fixture.track(3))), revisionOf(server), "the offline device's queued replace")
        ensure(revisionOf(queue(online)) == revisionOf(server) && queueEntries().map { it.str("entryId") } == entries) { "a refused offline write changed the queue" }
        val applied = replaceQueue(offline, revisionOf(server), listOf(fixture.track(3)))
        expectStatus(applied, 200, what = "a reconnecting write whose base revision is current")
        ensure(queueEntries().map { it.str("itemId") } == listOf(fixture.track(3))) { "the accepted replace did not take effect" }
    },
    Check("DA-Q-11", Queue, "entries page at the queue's revision in play order; removing the current entry makes the next one current", "openapi listQueueEntries, removeQueueEntry") {
        requireFixture()
        val d = deviceId("lister"); registerDevice(d)
        val q = seedQueue(d, 3)
        val page = api("GET", "listQueueEntries", "/me/queue/entries")
        expectStatus(page, 200)
        ensure(body(page).str("revision") == revisionOf(q)) { "the entry page is at revision ${body(page).str("revision")}, the queue at ${q.str("revision")}" }
        val items = body(page).path("items").toList()
        ensure(items.map { it.path("position").asInt() } == listOf(0, 1, 2)) { "entry positions are ${items.map { it.path("position") }}" }
        ensure(q.str("currentEntryId") == items[0].str("entryId")) { "currentIndex 0 did not make the first entry current" }
        val removed = api("DELETE", "removeQueueEntry", "/me/queue/entries/${id(items[0].str("entryId")!!)}", headers = mapOf("Dulcet-Device-Id" to d))
        expectStatus(removed, 200, 204, what = "removing the current entry")
        val after = queue(d)
        ensure(after.str("currentEntryId") == items[1].str("entryId")) { "after removing the current entry the current one is ${after.str("currentEntryId")}, not the next ${items[1].str("entryId")}" }
        ensure(after.path("entryCount").asInt() == 2) { "entryCount is ${after.path("entryCount")} after a removal" }
    },
    Check("DA-Q-12", Queue, "a device is online for two minutes after a request carrying its Dulcet-Device-Id, registration included, with no event stream", "README §11 Devices; openapi listDevices") {
        requireFixture()
        val (player, _) = onlineDevice("player")
        val fresh = deviceId("fresh")
        registerDevice(fresh)
        val listed = body(api("GET", "listDevices", "/me/devices")).path("items").firstOrNull { it.str("id") == fresh }
            ?: fail("the device just registered is not listed")
        ensure(listed.path("online").asBoolean(false)) { "a device registered a moment ago, with no event stream, is listed online=${listed.path("online")}" }
        seedQueue(player, 1)
        makeActive(player)
        val moved = transfer(player, fresh, play = false)
        expectStatus(moved, 200, what = "a transfer to a device that registered a moment ago")
        ensure(body(moved).str("activeDeviceId") == fresh) { "after the transfer the active device is ${body(moved).str("activeDeviceId")}" }
    },
)

// ---- Events profile (README §12, CONFORMANCE.md "Other profiles") ----------------------------------

fun eventChecks(): List<Check> = listOf(
    Check("DA-EV-1", Events, "/events is an event stream for a bearer token; no token is 401 and a type the token cannot read is 403", "README §12 Events; openapi streamEvents") {
        requires("DA-PRE-1")
        openEvents(label = "plain")
        val anonymous = eventsRequest(null)
        expectProblem(anonymous.exchange, 401, "unauthenticated", what = "/events without a token")
        val catalogOnly = mintToken(listOf("catalog:read"))
        val refused = eventsRequest(catalogOnly.secret, types = "queue")
        expectProblem(refused.exchange, 403, what = "/events?types=queue with a catalog:read token")
    },
    Check("DA-EV-2", Events, "a change to the user's data fires a changes event whose data validates", "README §12; openapi ChangesEvent") {
        requireFixture()
        val s = openEvents(label = "changes")
        val mark = s.mark()
        val t = fixture.track(2)
        expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
        run.cleanups += "unfavourite $t" to { api("DELETE", "removeFavorite", "/me/favorites/${id(t)}"); Unit }
        s.await("changes", eventTimeoutMs, mark) ?: fail("no changes event within ${config.eventTimeoutSeconds} s of a favourite")
    },
    Check("DA-EV-3", Events, "queue edits fire queue events and playback reports fire playback events on the other devices", "README §12; openapi QueueEvent, PlaybackEvent") {
        requireFixture()
        val (d1, s1) = onlineDevice("player")
        val (_, s2) = onlineDevice("watcher")
        seedQueue(d1, 2)
        makeActive(d1)
        val m1 = s1.mark(); val m2 = s2.mark()
        val inserted = insertEntries(d1, listOf(fixture.track(3)))
        expectStatus(inserted, 200)
        val rev = revisionOf(body(inserted).path("queue"))
        val qe = s2.await("queue", eventTimeoutMs, m2) { it.json?.str("revision") == rev }
            ?: fail("no queue event with revision $rev within ${config.eventTimeoutSeconds} s of an insert")
        ensure(qe.json!!.path("changed").any { it.asText() == "entries" }) { "the queue event for an insert says changed ${qe.json!!.path("changed")}" }
        expectStatus(reportPlayback(d1, body(inserted).path("queue").str("currentEntryId"), 4321), 200)
        s2.await("playback", eventTimeoutMs, m2) { it.json?.path("positionMs")?.asLong() == 4321L && it.json?.str("deviceId") == d1 }
            ?: fail("the other device got no playback event for the report within ${config.eventTimeoutSeconds} s")
        ensure(s1.since(m1, "command").isEmpty()) { "an edit produced a command event" }
    },
    Check("DA-EV-4", Events, "a now-playing change fires a nowPlaying event", "README §12; openapi NowPlayingEvent") {
        requireFixture()
        val s = openEvents(label = "nowplaying")
        val mark = s.mark()
        val device = deviceId("np")
        val ex = api("PUT", "setNowPlaying", "/me/now-playing", body = mapOf("deviceId" to device, "trackId" to fixture.track(0), "state" to "playing", "positionMs" to 0))
        expectStatus(ex, 200, what = "reporting now playing")
        run.cleanups += "clear now playing $device" to { api("DELETE", "clearNowPlaying", "/me/now-playing", query = listOf("deviceId" to device)); Unit }
        s.await("nowPlaying", eventTimeoutMs, mark) { it.json?.str("deviceId") == device }
            ?: fail("no nowPlaying event for device $device within ${config.eventTimeoutSeconds} s")
    },
    Check("DA-EV-5", Events, "scan events reach admins and no one else", "README §12; openapi ScanEvent") {
        requires("DA-PRE-1")
        val admin = openEvents(token = adminToken, label = "admin")
        val userIsAdmin = me().path("roles").any { it.asText() == "admin" }
        val user = if (userIsAdmin) null else openEvents(label = "user")
        val ma = admin.mark(); val mu = user?.mark() ?: 0
        val scan = api("POST", "startScan", "/admin/scans", token = adminToken, body = mapOf("kind" to "quick"))
        expectStatus(scan, 202, what = "starting a scan")
        val scanId = body(scan).str("id")
        admin.await("scan", eventTimeoutMs, ma) { it.json?.str("scanId") == scanId }
            ?: fail("the admin's stream got no scan event for $scanId within ${config.eventTimeoutSeconds} s")
        if (user != null) {
            Thread.sleep(quietMs)
            ensure(user.since(mu, "scan").isEmpty()) { "a user without the admin role got a scan event" }
        }
    },
    Check("DA-EV-6", Events, "a revoked token's stream gets a revoked event and then closes", "README §12; openapi RevokedEvent") {
        requires("DA-PRE-1")
        val pat = mintToken(listOf("user:read"))
        val s = openEvents(token = pat.secret, label = "revoked")
        expectStatus(api("DELETE", "revokeTokenById", "/me/tokens/${id(pat.id)}"), 204, what = "revoking the stream's token")
        s.await("revoked", eventTimeoutMs) ?: fail("no revoked event within ${config.eventTimeoutSeconds} s of revoking the token")
        ensure(s.awaitEnd(eventTimeoutMs)) { "the stream stayed open after the revoked event" }
    },
    Check("DA-EV-7", Events, "heartbeats arrive at least every 30 seconds", "README §12; openapi streamEvents") {
        requires("DA-PRE-1")
        val s = openEvents(label = "heartbeat")
        val opened = Instant.now()
        val limitMs = 32_000L
        val first = s.await("heartbeat", limitMs) ?: fail("no heartbeat within 30 s of opening the stream")
        val second = s.await("heartbeat", limitMs, s.received.indexOf(first) + 1) ?: fail("no second heartbeat within 30 s of the first")
        val gap = java.time.Duration.between(first.receivedAt, second.receivedAt).toMillis()
        ensure(java.time.Duration.between(opened, first.receivedAt).toMillis() <= limitMs && gap <= limitMs) { "heartbeats came ${gap} ms apart" }
    },
    Check("DA-EV-8", Events, "reconnecting with Last-Event-ID brings what happened while disconnected, replayed or as a changes and a queue event", "README §12; openapi streamEvents") {
        requireFixture()
        val d = deviceId("resume"); registerDevice(d)
        val first = openEvents(deviceId = d, label = "before")
        val t = fixture.track(1)
        expectStatus(api("PUT", "addFavorite", "/me/favorites/${id(t)}"), 200)
        run.cleanups += "unfavourite $t" to { api("DELETE", "removeFavorite", "/me/favorites/${id(t)}"); Unit }
        val seen = first.await("changes", eventTimeoutMs) ?: fail("no changes event before the disconnect")
        val lastId = seen.id ?: fail("the changes event carries no id, so a client cannot resume with Last-Event-ID")
        first.close(); streams.remove(first); violations += first.violations
        expectStatus(api("DELETE", "removeFavorite", "/me/favorites/${id(t)}"), 204, 200, what = "a change while disconnected")
        expectStatus(insertEntries(d, listOf(fixture.track(0))), 200, what = "a queue edit while disconnected")
        val resumed = openEvents(deviceId = d, lastEventId = lastId, label = "after")
        resumed.await("changes", eventTimeoutMs) { it.id != lastId } ?: fail("no changes event after reconnecting with Last-Event-ID")
        resumed.await("queue", eventTimeoutMs) ?: fail("no queue event after reconnecting with Last-Event-ID")
    },
    Check("DA-EV-9", Events, "without types, /events leaves out, silently, the events the token cannot read", "README §12; openapi streamEvents") {
        requireFixture()
        val catalogOnly = mintToken(listOf("catalog:read"))
        val narrow = openEvents(token = catalogOnly.secret, label = "catalog-only")
        val full = openEvents(label = "full")
        val mn = narrow.mark(); val mf = full.mark()
        val device = deviceId("np")
        expectStatus(api("PUT", "setNowPlaying", "/me/now-playing", body = mapOf("deviceId" to device, "trackId" to fixture.track(0), "state" to "playing", "positionMs" to 0)), 200, what = "reporting now playing")
        run.cleanups += "clear now playing $device" to { api("DELETE", "clearNowPlaying", "/me/now-playing", query = listOf("deviceId" to device)); Unit }
        full.await("nowPlaying", eventTimeoutMs, mf) { it.json?.str("deviceId") == device } ?: fail("the user:read stream got no nowPlaying event")
        Thread.sleep(quietMs)
        ensure(narrow.since(mn, "nowPlaying").isEmpty()) { "a catalog:read stream opened without types received a nowPlaying event" }
        ensure(!narrow.ended) { "the catalog:read stream closed" }
    },
)
