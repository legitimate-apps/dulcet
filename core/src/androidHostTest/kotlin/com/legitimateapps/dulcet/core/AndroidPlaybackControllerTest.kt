package com.legitimateapps.dulcet.core

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPlaybackControllerTest {
    /** The main dispatcher's clock: the controller's delays (the delivery retry timer) run on it. */
    private val mainScheduler = TestCoroutineScheduler()
    @Before fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher(mainScheduler)) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun aPlayChosenDuringIdentityValidationResumesOnlyWhenTheSameServerIsVerified() {
        for (verified in listOf(true, false)) {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var open = false
            val contact = object : AndroidServerContact {
                override fun isOpen() = open
                override suspend fun awaitOpen() { kotlinx.coroutines.awaitCancellation() }
                override suspend fun awaitValidation() { release.await() }
            }
            Fixture(serverContact = contact).use { f ->
                f.controller.playSong(OWNER, "song", "Song")
                assertTrue(f.prepared.isEmpty(), "An unverified connection cannot resolve saved IDs")
                assertFalse(f.probe.requested)
                open = verified
                release.complete(Unit)
                settle(5)
                if (verified) {
                    assertEquals(listOf("song"), f.prepared.map { it.itemId.rawId },
                        "A same-server probe must not discard the person's Play selection")
                    assertTrue(f.probe.requested)
                } else {
                    assertTrue(f.prepared.isEmpty(), "A failed probe must leave remote contact closed")
                    assertTrue(f.controller.state.value.needsReconnect)
                }
            }
        }
    }

    @Test fun aChosenAlbumWaitsVisiblyBetweenFailedAndSuccessfulIdentityProbes() {
        val owner = "provider:reconnecting-album:${java.util.UUID.randomUUID()}"
        kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
        val contact = object : AndroidServerContact {
            override fun isChosen() = true
            override fun isOpen() = AndroidServerIdentity.isVerified(owner)
            override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
            override suspend fun awaitOpen() { AndroidServerIdentity.verified.first { owner in it } }
        }
        val reads = mutableListOf<String>()
        try {
            Fixture(accountOwner = owner, serverContact = contact, localPlans = { null }, loadSong = { reads += it; song(it) }).use { f ->
                assertTrue(contact.isOpen(), "Control: the account starts verified and connected")
                kotlinx.coroutines.runBlocking {
                    assertEquals(DomainError.Transport.Unreachable,
                        AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable })
                }
                AndroidPlaybackController.notifyServerAvailable(owner, false)
                assertTrue(contact.isChosen(), "Control: a transient failure did not withdraw the connection choice")
                assertFalse(contact.isOpen(), "Control: the completed failed probe closed contact before Play")
                val tracks = listOf("first", "second").map { AndroidTrack(owner, it, "Title $it", null, "Album", 40_000) }
                f.controller.playQueue(tracks, 0, AndroidQueueSource.Album, "Album", "album")
                assertEquals(listOf("first", "second"), f.controller.state.value.queue.map { it.track.rawId },
                    "Play between probes must retain the selected album visibly, rather than discard it")
                assertTrue(f.controller.state.value.needsReconnect, "The held selection explains that it needs a connection")
                assertTrue(reads.isEmpty() && f.prepared.isEmpty(), "Closed contact must send nothing")
                kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
                settle(5)
                assertEquals(listOf("first"), f.prepared.map { it.itemId.rawId },
                    "The same server reopening must prepare the latest explicit Play exactly once")
                assertTrue(f.probe.requested, "Recovery honors the person's Play without another press")
                assertFalse(f.controller.state.value.needsReconnect)
            }
        } finally { AndroidServerIdentity.requireValidation(owner) }
    }

    @Test fun aNewRemoteSelectionClearsTheNoticeBeforeTheReopenedHeldWaiterResumes() {
        val owner = "provider:superseded-reconnect:${java.util.UUID.randomUUID()}"
        val releaseOldWaiter = kotlinx.coroutines.CompletableDeferred<Unit>()
        var waiterEntered = false
        var reopenedWaiterParked = false
        var waiterReturned = false
        var replacementReachedBeforeReturn = false
        kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
        val contact = object : AndroidServerContact {
            override fun isChosen() = true
            override fun isOpen() = AndroidServerIdentity.isVerified(owner)
            override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
            override suspend fun awaitOpen() {
                waiterEntered = true
                AndroidServerIdentity.verified.first { owner in it }
                reopenedWaiterParked = true
                releaseOldWaiter.await()
                waiterReturned = true
            }
        }
        try {
            Fixture(accountOwner = owner, serverContact = contact, localPlans = { null }, loadSong = {
                assertEquals("replacement", it, "The held selection cannot read metadata before its waiter returns")
                assertTrue(reopenedWaiterParked && !waiterReturned,
                    "Control: the replacement reaches its read while the old reopened waiter has not returned")
                replacementReachedBeforeReturn = true
                song(it)
            }).use { f ->
                kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                AndroidPlaybackController.notifyServerAvailable(owner, false)
                fun select(id: String) = f.controller.playQueue(listOf(AndroidTrack(owner, id, id)),
                    0, AndroidQueueSource.Album, "Album", "album")
                select("held")
                assertTrue(waiterEntered && !reopenedWaiterParked,
                    "Control: the selected queue is actually waiting for closed contact")
                assertTrue(f.controller.state.value.needsReconnect)
                assertTrue(f.prepared.isEmpty())
                kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
                assertTrue(contact.isOpen() && reopenedWaiterParked && !waiterReturned,
                    "Control: contact reopened, but the held selection's waiter is parked before returning")
                AndroidPlaybackController.notifyServerAvailable(owner, true)
                select("replacement")
                ready(f)
                assertTrue(replacementReachedBeforeReturn && !waiterReturned,
                    "Control: the new selection superseded the held one before its waiter resumed")
                assertEquals(listOf("replacement"), f.prepared.map { it.itemId.rawId })
                assertTrue(f.probe.requested, "The replacement has received Play")
                assertFalse(f.controller.state.value.needsReconnect,
                    "An authorized replacement playing after contact reopens must clear the held selection's reconnect notice")
            }
        } finally { releaseOldWaiter.complete(Unit); AndroidServerIdentity.requireValidation(owner) }
    }

    @Test fun aLocalOfflineSelectionStillFiltersRemoteEntriesAfterAChosenConnectionProbeFails() {
        for (supersedesHeld in listOf(false, true)) {
            val owner = "provider:offline-local:${java.util.UUID.randomUUID()}"
            val file = java.io.File.createTempFile("dulcet-offline-contact", ".wav")
            kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
            val contact = object : AndroidServerContact {
                override fun isChosen() = true
                override fun isOpen() = AndroidServerIdentity.isVerified(owner)
                override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
                override suspend fun awaitOpen() { AndroidServerIdentity.verified.first { owner in it } }
            }
            val reads = mutableListOf<String>()
            try {
                Fixture(accountOwner = owner, serverContact = contact, loadSong = { reads += it; song(it) },
                    localPlans = { rawId -> if (rawId != "down") null else LocalPlaybackPlan(DownloadId("download:down"),
                        DownloadIdentity(owner, "down", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, file.path) }
                ).use { f ->
                    kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                    AndroidPlaybackController.notifyServerAvailable(owner, false)
                    assertFalse(contact.isOpen(), "Control: the chosen account is unverified while the OS network stays available")
                    fun tracks(vararg ids: String) = ids.map { AndroidTrack(owner, it, "Title $it") }
                    if (supersedesHeld) {
                        f.controller.playQueue(tracks("net"), 0, AndroidQueueSource.Library, "Library")
                        assertEquals(listOf("net"), f.controller.state.value.queue.map { it.track.rawId })
                        assertTrue(f.controller.state.value.needsReconnect)
                    }
                    f.controller.playQueue(tracks("down", "net"), 0, AndroidQueueSource.Library, "Offline Library")
                    assertEquals(listOf("down"), f.controller.state.value.queue.map { it.track.rawId },
                        "A locally playable offline selection must not retain unavailable remote entries")
                    assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId })
                    assertFalse(f.controller.state.value.needsReconnect)
                    assertTrue(reads.isEmpty() && f.prepared.isEmpty(), "Offline local playback sends no remote reads")
                }
            } finally { file.delete(); AndroidServerIdentity.requireValidation(owner) }
        }
    }

    @Test fun recoveryDuringLocalAcquisitionEndsOnlyTheHeldQueuesAdmissionException() {
        for (localRecovery in listOf(false, true)) {
            val owner = "provider:acquisition-recovery:${java.util.UUID.randomUUID()}"
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val file = java.io.File.createTempFile("dulcet-held-acquisition", ".wav")
            var acquiring = false
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = false
                override suspend fun localPlan(rawId: String): LocalPlaybackPlan? {
                    acquiring = true
                    release.await()
                    return if (!localRecovery || rawId != "first") null else LocalPlaybackPlan(DownloadId("download:first"),
                        DownloadIdentity(owner, "first", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, file.path)
                }
            }
            kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
            val contact = object : AndroidServerContact {
                override fun isChosen() = true
                override fun isOpen() = AndroidServerIdentity.isVerified(owner)
                override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
                override suspend fun awaitOpen() { AndroidServerIdentity.verified.first { owner in it } }
            }
            try {
                Fixture(accountOwner = owner, serverContact = contact, localPlans = source).use { f ->
                    kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                    AndroidPlaybackController.notifyServerAvailable(owner, false)
                    f.controller.playQueue(listOf("first", "second").map { AndroidTrack(owner, it, "Title $it") },
                        0, AndroidQueueSource.Library, "Library")
                    assertTrue(acquiring && f.controller.state.value.needsReconnect,
                        "Control: the queue is held and its actual local-file acquisition is suspended")
                    assertTrue(f.prepared.isEmpty() && f.preparedLocal.isEmpty())
                    kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
                    release.complete(Unit)
                    settle(5)
                    assertEquals(listOf("first"), (f.prepared.map { it.itemId.rawId } + f.preparedLocal.map { it.itemId.rawId }),
                        "Contact opening before the authorization wait still resumes despite the reader's lagging outage hint")
                    assertFalse(f.controller.state.value.needsReconnect)
                    kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                    AndroidPlaybackController.notifyServerAvailable(owner, false)
                    f.controller.next()
                    assertFalse(f.controller.state.value.needsReconnect,
                        "A later outage cannot reactivate the completed held-selection admission")
                    kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
                    settle(5)
                    assertEquals(listOf("first"), (f.prepared.map { it.itemId.rawId } + f.preparedLocal.map { it.itemId.rawId }),
                        "Ordinary offline continuation cannot revive a remote-only next entry")
                }
            } finally { release.complete(Unit); file.delete(); AndroidServerIdentity.requireValidation(owner) }
        }
    }

    @Test fun contactLossDuringTheSelectionsMetadataReadRetainsAndReauthorizesOnlyThatSelection() {
        val owner = "provider:metadata-reconnect:${java.util.UUID.randomUUID()}"
        kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
        val contact = object : AndroidServerContact {
            override fun isChosen() = true
            override fun isOpen() = AndroidServerIdentity.isVerified(owner)
            override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
            override suspend fun awaitOpen() { AndroidServerIdentity.verified.first { owner in it } }
        }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        try {
            Fixture(accountOwner = owner, serverContact = contact, localPlans = { null }, loadSong = {
                reads++
                if (reads == 1) release.await()
                song(it)
            }).use { f ->
                f.controller.playQueue(listOf(AndroidTrack(owner, "first", "First", null, "Album", 40_000)),
                    0, AndroidQueueSource.Album, "Album", "album")
                assertEquals(1, reads, "Control: the selection's actual metadata read is suspended")
                kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                AndroidPlaybackController.notifyServerAvailable(owner, false)
                assertFalse(contact.isOpen(), "Control: contact closes while that reply is held")
                release.complete(Unit)
                settle(5)
                assertEquals(listOf("first"), f.controller.state.value.queue.map { it.track.rawId })
                assertTrue(f.controller.state.value.needsReconnect)
                assertTrue(f.prepared.isEmpty())
                kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
                settle(5)
                assertEquals(2, reads, "The withdrawn reply is replaced by one fresh authorized read")
                assertEquals(listOf("first"), f.prepared.map { it.itemId.rawId })
                assertTrue(f.probe.requested)
            }
        } finally { release.complete(Unit); AndroidServerIdentity.requireValidation(owner) }
    }

    @Test fun aHeldAlbumHonorsPauseStopNewSelectionAndTheIdentityAuthorizationFence() {
        for (action in listOf("pause", "stop", "new-selection", "next", "jump", "replace-server")) {
            val owner = "provider:held-album-$action:${java.util.UUID.randomUUID()}"
            kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(owner) { null }) }
            val contact = object : AndroidServerContact {
                override fun isChosen() = true
                override fun isOpen() = AndroidServerIdentity.isVerified(owner)
                override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(owner) }
                override suspend fun awaitOpen() { AndroidServerIdentity.verified.first { owner in it } }
            }
            val reads = mutableListOf<String>()
            try {
                Fixture(accountOwner = owner, serverContact = contact, localPlans = { null }, loadSong = { reads += it; song(it) }).use { f ->
                    kotlinx.coroutines.runBlocking { AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable } }
                    AndroidPlaybackController.notifyServerAvailable(owner, false)
                    assertFalse(contact.isOpen(), "Control: $action starts with chosen contact closed")
                    fun select(rawId: String) = f.controller.playQueue(
                        (if (rawId == "first") listOf("first", "second") else listOf(rawId)).map {
                            AndroidTrack(owner, it, it, null, "Album", 40_000)
                        },
                        0, AndroidQueueSource.Album, "Album", "album")
                    select("first")
                    assertTrue(f.controller.state.value.needsReconnect, "Control: $action acts on a held selection")
                    assertEquals(listOf("first", "second"), f.controller.state.value.queue.map { it.track.rawId })
                    assertTrue(reads.isEmpty() && f.prepared.isEmpty())
                    when (action) {
                        "pause" -> f.controller.pause()
                        "stop" -> f.controller.stop()
                        "new-selection" -> select("later")
                        "next" -> f.controller.next()
                        "jump" -> f.controller.jumpTo(f.controller.state.value.queue[1].queueEntryId)
                        "replace-server" -> AndroidServerIdentity.accept(RuntimeEnvironment.getApplication(), owner,
                            ServerIdentityPolicy.identity("fixture", "replacement-instance"))
                    }
                    if (action != "replace-server") kotlinx.coroutines.runBlocking {
                        assertNull(AndroidServerIdentity.validate(owner) { null })
                    }
                    AndroidPlaybackController.notifyServerAvailable(owner, true)
                    settle(5)
                    when (action) {
                        "pause" -> {
                            assertEquals(listOf("first"), f.prepared.map { it.itemId.rawId })
                            assertFalse(f.probe.requested, "Pause must remain paused when contact returns")
                        }
                        "new-selection", "next", "jump" -> {
                            assertEquals(listOf(if (action == "new-selection") "later" else "second"), f.prepared.map { it.itemId.rawId })
                            assertTrue(f.probe.requested)
                        }
                        else -> {
                            assertTrue(f.prepared.isEmpty() && reads.isEmpty(), "$action cannot revive old Play")
                            assertFalse(f.probe.requested)
                        }
                    }
                    if (action == "replace-server") assertEquals(DomainError.Protocol.ServerIdentityChanged,
                        f.controller.state.value.error, "Explicit consent must not reauthorize the held old IDs")
                }
            } finally { AndroidServerIdentity.requireValidation(owner) }
        }
    }

    @Test fun identityRejectionDuringMetadataCannotRenameThePriorQueue() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val contact = ChosenContact().also { it.open.value = true }
        var hold = false
        var metadataStarted = false
        Fixture(serverContact = contact, loadSong = { id ->
            if (hold) {
                metadataStarted = true
                release.await()
                song(id).copy(body = """{"subsonic-response":{"status":"ok","song":{"id":"$id","title":"Wrong server","suffix":"wav","duration":40}}}""".toByteArray())
            } else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "same-id", "Original")
            assertEquals("Original", f.controller.state.value.queue.single().track.title)
            hold = true
            f.controller.playSong(OWNER, "same-id", "Original")
            assertTrue(metadataStarted)
            contact.open.value = false
            release.complete(Unit)
            settle(5)
            assertEquals("Original", f.controller.state.value.queue.single().track.title,
                "A late replacement response cannot change the retained server's metadata")
            assertEquals(1, f.prepared.size)
            assertTrue(f.controller.state.value.needsReconnect)
        }
    }

    @Test fun explicitAcceptanceCannotReviveHeldMetadataFromBeforeRejection() = heldMetadataAuthorization(rejectAndAccept = true)
    @Test fun aTransientProbeStillAllowsHeldMetadataFromTheAcceptedServer() = heldMetadataAuthorization(rejectAndAccept = false)

    private fun heldMetadataAuthorization(rejectAndAccept: Boolean) {
        val owner = "provider:metadata-authorization:${java.util.UUID.randomUUID()}"
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var hold = false
        var entered = false
        Fixture(accountOwner = owner, loadSong = { id ->
            if (hold) {
                entered = true; release.await()
                song(id).copy(body = """{"subsonic-response":{"status":"ok","song":{"id":"$id","title":"Late title","suffix":"wav","duration":40}}}""".toByteArray())
            } else song(id)
        }).use { f ->
            f.controller.playSong(owner, "same-id", "Original")
            assertEquals("Original", f.controller.state.value.queue.single().track.title)
            hold = true
            f.controller.playSong(owner, "same-id", "Original")
            assertTrue(entered, "The actual current-entry metadata operation is suspended")
            try {
                kotlinx.coroutines.runBlocking {
                    if (rejectAndAccept) {
                        AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                        AndroidServerIdentity.accept(RuntimeEnvironment.getApplication(), owner,
                            ServerIdentityPolicy.identity("navidrome", "confirmed-instance"))
                    } else AndroidServerIdentity.validate(owner) { DomainError.Transport.Unreachable }
                }
                release.complete(Unit)
                settle(5)
                assertEquals(if (rejectAndAccept) "Original" else "Late title",
                    f.controller.state.value.queue.single().track.title,
                    "Only a still-current authorization may adopt held metadata")
                assertEquals(if (rejectAndAccept) 1 else 2, f.prepared.size,
                    "Explicit consent cannot prepare the retired selection; a transient probe can")
            } finally { release.complete(Unit) }
        }
    }

    @Test fun identityRejectionDuringAutoplayCannotAppendReplacementServerIds() {
        val release = kotlinx.coroutines.CompletableDeferred<List<AndroidTrack>>()
        val contact = ChosenContact().also { it.open.value = true }
        var candidatesStarted = false
        Fixture(serverContact = contact, candidates = { _, _, _ -> candidatesStarted = true; release.await() }).use { f ->
            f.controller.setAutoplay(true)
            f.controller.playSong(OWNER, "original", "Original")
            assertTrue(candidatesStarted)
            assertEquals(listOf("original"), f.controller.state.value.queue.map { it.track.rawId })
            contact.open.value = false
            release.complete(listOf(AndroidTrack(OWNER, "wrong-server-id", "Wrong server", null, null, 40_000)))
            settle(5)
            assertEquals(listOf("original"), f.controller.state.value.queue.map { it.track.rawId },
                "A late autoplay response cannot persist another server's IDs in the retained queue")
            assertEquals(listOf("original"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun identityRejectionDuringMetadataCannotResolveOrInstallTheRemoteSelection() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val contact = ChosenContact().also { it.open.value = true }
        var metadataStarted = false
        val resolvedIds = mutableListOf<String>()
        Fixture(serverContact = contact, loadSong = { id -> metadataStarted = true; release.await(); song(id) },
            resolve = { request -> resolvedIds += request.itemId.rawId; resolved(request) }).use { f ->
            f.controller.playSong(OWNER, "replacement", "Replacement")
            assertTrue(metadataStarted)
            contact.open.value = false
            release.complete(Unit)
            settle(5)
            assertTrue(resolvedIds.isEmpty(), "A rejected account cannot resolve a late metadata response's stored ID")
            assertTrue(f.prepared.isEmpty(), "A late response cannot install remote playback after rejection")
            assertTrue(f.controller.state.value.needsReconnect)
            assertTrue(f.controller.state.value.queue.isEmpty(), "The prior queue survives rejected selection")
        }
    }

    @Test fun identityRejectionDuringResolutionCannotInstallTheRemotePlan() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val contact = ChosenContact().also { it.open.value = true }
        var resolutionStarted = false
        Fixture(serverContact = contact, resolve = { request ->
            resolutionStarted = true; release.await(); resolved(request)
        }).use { f ->
            f.controller.playSong(OWNER, "replacement", "Replacement")
            assertTrue(resolutionStarted)
            contact.open.value = false
            release.complete(Unit)
            settle(5)
            assertTrue(f.prepared.isEmpty(), "A late plan cannot prepare or play remote bytes after identity rejection")
            assertFalse(f.probe.requested)
            assertTrue(f.controller.state.value.needsReconnect)
        }
    }

    @Test fun aKnownIdentityRejectionCannotOpenAnotherRangeOfAnInstalledRemoteResource() {
        PlaybackResourceReceiver(pcmWave()).use { receiver ->
            val owner = "provider:${java.util.UUID.randomUUID()}"
            Fixture(accountOwner = owner, baseUrl = receiver.url).use { f ->
                f.controller.playSong(owner, "song", "Song")
                val resource = f.controller.httpPlaybackResource(f.prepared.single())
                val first = resource.open(0, 16)
                try { assertEquals(16, first.input.readBytes().size) } finally { first.close() }
                assertEquals(1, receiver.requestCount())
                kotlinx.coroutines.runBlocking {
                    AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                }
                val next = runCatching { resource.open(16, 16) }
                next.getOrNull()?.close?.invoke()
                assertIs<AndroidPlaybackIOException>(next.exceptionOrNull(),
                    "An installed resource cannot sign or send a new range after known replacement")
                assertEquals(1, receiver.requestCount(), "No rejected old-ID range reaches the receiving socket")
            }
        }
    }

    @Test fun headersReleasedAfterIdentityRejectionCannotDeliverRemoteAudio() = rejectedResourceReply("headers")
    @Test fun bodyReleasedAfterIdentityRejectionCannotDeliverRemoteAudio() = rejectedResourceReply("body")

    private fun rejectedResourceReply(phase: String) {
        for (reject in listOf(false, true)) {
            val reached = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val hold = { reached.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); Unit }
            PlaybackResourceReceiver(pcmWave(), beforeHeaders = if (phase == "headers") hold else ({}),
                beforeBody = if (phase == "body") hold else ({}), allowClientDisconnect = true).use { receiver ->
                val owner = "provider:held-resource:${java.util.UUID.randomUUID()}"
                Fixture(accountOwner = owner, baseUrl = receiver.url).use { f ->
                    f.controller.playSong(owner, "song", "Original")
                    val plan = f.prepared.single()
                    val delivered = java.util.concurrent.atomic.AtomicLong()
                    val source = AndroidPlaybackDataSourceFactory(plan, f.controller.httpPlaybackResource(plan),
                        { delivered.addAndGet(it) }).createDataSource()
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val result = executor.submit<Throwable?> {
                        try {
                            source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("dulcet://resource")))
                            source.read(ByteArray(64), 0, 64)
                            null
                        } catch (failure: Throwable) { failure }
                    }
                    try {
                        assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS), "Real HTTP $phase must be held")
                        receiver.assertAuthenticatedSong("song")
                        if (reject) kotlinx.coroutines.runBlocking {
                            AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                        }
                        release.countDown()
                        val failure = result.get(10, java.util.concurrent.TimeUnit.SECONDS)
                        if (reject) {
                            assertEquals(0L, delivered.get(), "Late $phase from a rejected server cannot deliver any audio to Media3")
                            assertIs<AndroidPlaybackIOException>(failure)
                        } else {
                            assertNull(failure, "The same held reply from an accepted server remains playable")
                            assertTrue(delivered.get() > 0, "Positive control must exercise actual validated audio delivery")
                        }
                    } finally {
                        release.countDown(); source.close(); executor.shutdownNow()
                        assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test fun rejectionAfterOpenCannotDeliverTheAlreadyValidatedAudioPrefix() {
        PlaybackResourceReceiver(pcmWave()).use { receiver ->
            val owner = "provider:buffered-rejected:${java.util.UUID.randomUUID()}"
            Fixture(accountOwner = owner, baseUrl = receiver.url).use { f ->
                f.controller.playSong(owner, "song", "Original")
                val plan = assertIs<RemotePlaybackWirePlan>(f.prepared.single())
                val delivered = java.util.concurrent.atomic.AtomicLong()
                val source = AndroidPlaybackDataSourceFactory(plan, f.controller.httpPlaybackResource(plan),
                    { delivered.addAndGet(it) }).createDataSource()
                try {
                    source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("dulcet://resource")))
                    receiver.assertAuthenticatedSong("song")
                    assertEquals(0L, delivered.get(), "open validates and buffers audio without delivering it")
                    kotlinx.coroutines.runBlocking {
                        AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                    }
                    val result = runCatching { source.read(ByteArray(64), 0, 64) }
                    assertEquals(0L, delivered.get(), "The already validated prefix must not bypass rejection")
                    assertIs<AndroidPlaybackIOException>(result.exceptionOrNull())
                } finally { source.close() }
            }
        }
    }

    @Test fun identityRejectionDuringAutoplaySeedPreventsEveryFollowupHttpRequest() {
        AutoplayReceiver().use { receiver ->
            val owner = "provider:autoplay-rejected:${java.util.UUID.randomUUID()}"
            Fixture(accountOwner = owner, baseUrl = receiver.url, candidates = null).use { f ->
                f.controller.setAutoplay(true)
                f.controller.playSong(owner, "seed", "Original")
                try {
                    assertTrue(receiver.seedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                        "The real production autoplay source must send its seed lookup")
                    // Observe only completion through this private job; the oracle is receiving HTTP requests.
                    val field = AndroidPlaybackController::class.java.getDeclaredField("autoplayJob").apply { isAccessible = true }
                    val job = assertNotNull(field.get(f.controller) as? kotlinx.coroutines.Job)
                    kotlinx.coroutines.runBlocking {
                        AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged }
                    }
                    receiver.releaseSeed.countDown()
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                    while (!job.isCompleted && System.nanoTime() < deadline) {
                        mainScheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                    }
                    assertTrue(job.isCompleted, "The seed reply must actually return before checking for forbidden requests")
                    assertEquals(listOf("/rest/getSong.view"), receiver.paths.toList(),
                        "Rejection during the seed reply must prevent similar/top/random fallback requests")
                    assertEquals(listOf("seed"), f.controller.state.value.queue.map { it.track.rawId })
                } finally { receiver.releaseSeed.countDown() }
            }
        }
    }

    @Test fun identityRejectionDuringPreloadMetadataCannotResolveOrPrepareTheNextTrack() = rejectedPreload("metadata")
    @Test fun identityRejectionDuringPreloadResolutionCannotPrepareTheNextTrack() = rejectedPreload("resolve")

    private fun rejectedPreload(phase: String) {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val contact = ChosenContact().also { it.open.value = true }
        val incoming = PlayerProbe()
        var holdNext = false
        var started = false
        val resolvedIds = mutableListOf<String>()
        Fixture(serverContact = contact, overlapPlayer = { incoming.player }, loadSong = { id ->
            if (holdNext && id == "b" && phase == "metadata") { started = true; release.await() }
            song(id)
        }, resolve = { request ->
            resolvedIds += request.itemId.rawId
            if (holdNext && request.itemId.rawId == "b" && phase == "resolve") { started = true; release.await() }
            resolved(request)
        }).use { f ->
            f.controller.setAutoplay(false)
            f.controller.setCrossfade(5)
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Search, "Search")
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
            holdNext = true
            ready(f)
            progress(f, 0, 1_000)
            assertTrue(started, "Setup: the next-track $phase request is held in flight")
            contact.open.value = false
            release.complete(Unit)
            settle(5)
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId },
                "Identity rejection cannot install a late remote preload")
            assertFalse(incoming.requested)
            if (phase == "metadata") assertFalse("b" in resolvedIds,
                "Rejected preload metadata cannot lead to an old-ID resolve request")
        }
    }

    @Test fun aNewSelectionSupersedesAPlayWaitingForIdentityValidation() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var open = false
        val contact = object : AndroidServerContact {
            override fun isOpen() = open
            override suspend fun awaitOpen() { kotlinx.coroutines.awaitCancellation() }
            override suspend fun awaitValidation() { release.await() }
        }
        Fixture(serverContact = contact).use { f ->
            f.controller.playSong(OWNER, "old", "Old")
            f.controller.playSong(OWNER, "new", "New")
            open = true
            release.complete(Unit)
            settle(5)
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            assertTrue(f.probe.requested)
        }
    }

    @Test fun lateMetadataAfterNewSelectionCannotReplaceTheNewQueue() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        var lateReturns = 0
        Fixture(loadSong = { id ->
            if (id == "old") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it }.also { lateReturns++ }
            else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "old", "Old")
            val old = assertNotNull(pending)
            f.controller.playSong(OWNER, "new", "New")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            old.resume(song("old"))
            assertEquals(1, lateReturns, "The cancelled operation must actually return late")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            assertEquals("New", f.controller.state.value.title)
        }
    }

    @Test fun latePlanAfterStopCannotPrepareOrPlay() {
        var pending: Continuation<PlaybackResolutionResult>? = null
        var request: PlaybackResolveRequest? = null
        var lateReturns = 0
        Fixture(resolve = { value ->
            request = value
            suspendCoroutine<PlaybackResolutionResult> { pending = it }.also { lateReturns++ }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            val delayed = assertNotNull(pending)
            f.controller.stop()
            delayed.resume(resolved(assertNotNull(request)))
            assertEquals(1, lateReturns, "The stopped resolution must actually return")
            assertTrue(f.prepared.isEmpty())
            assertFalse(f.probe.requested)
        }
    }

    @Test fun pauseWhileMetadataLoadsAfterStopOverridesThePreviousPlayIntent() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        Fixture(loadSong = { id ->
            if (id == "second") suspendCoroutine { pending = it } else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "first", "First")
            assertTrue(f.probe.requested)
            f.controller.stop()
            f.controller.playSong(OWNER, "second", "Second")
            val delayed = assertNotNull(pending)
            f.controller.pause()
            delayed.resume(song("second"))
            assertEquals(listOf("first", "second"), f.prepared.map { it.itemId.rawId })
            assertFalse(f.probe.requested, "Preparing the second selection must honor Pause")
            f.controller.play()
            assertTrue(f.probe.requested)
        }
    }

    @Test fun submittedPlayIsInTheRealOutboxBeforeDeliveryHandoff() {
        var handoffs = 0
        Fixture(onDelivery = { f, event ->
            if (event is RecordedPlaybackEvent.SubmittedPlay) {
                handoffs++
                val pending = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { 1L }).pending(ServerId(OWNER))
                assertEquals(1, pending.size, "Delivery was scheduled before durable persistence")
                assertEquals(event.itemId.rawId, pending.single().rawId)
                assertEquals(event.sessionStartWallClock, pending.single().sessionStartWallClock)
            }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            assertEquals(1, f.prepared.size)
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(46) {
                f.probe.position += 500
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            }
            assertEquals(1, handoffs, "The controller must cross the threshold and hand off a submitted play")
        }
    }

    @Test fun restoredQueuePreparesPausedOnlyForItsOwningAccount() {
        for (savedOwner in listOf(OWNER, "different-account")) {
            val loaded = mutableListOf<String>()
            Fixture(savedOwner = savedOwner, loadSong = { id -> loaded += id; song(id) }).use { f ->
                if (savedOwner == OWNER) {
                    assertEquals(listOf("saved-song"), loaded)
                    assertEquals(listOf("saved-song"), f.prepared.map { it.itemId.rawId })
                    assertFalse(f.probe.requested, "Restoration must not auto-play")
                    assertEquals(OWNER, f.prepared.single().itemId.providerInstanceId)
                } else {
                    assertTrue(loaded.isEmpty(), "Another account's queue must not even load metadata")
                    assertTrue(f.prepared.isEmpty())
                    assertFalse(f.probe.requested)
                }
            }
        }
    }

    @Test fun stopThenPlayPreservesRestoredQueueAndRestartsTheCurrentSelection() {
        Fixture(savedOwner = OWNER, savedSongs = listOf("A", "B", "C")).use { f ->
            val queues = PersistentQueueStore(f.store.database)
            val original = queues.load(ServerId(OWNER))
            assertEquals(listOf("A", "B", "C"), original.entries.map { it.providerItemId.rawId })
            assertEquals(3, original.entries.map { it.queueEntryId }.toSet().size)
            for (index in 0..1) {
                if (index > 0) f.controller.next()
                val before = queues.load(ServerId(OWNER))
                val session = f.controller.state.value.playbackSessionId
                assertEquals(index, before.currentIndex)
                f.controller.stop()
                assertEquals(before, queues.load(ServerId(OWNER)), "Stop must not edit persistence")
                f.controller.play()
                assertEquals(before, queues.load(ServerId(OWNER)), "Play must retain entries, IDs, order and selection")
                assertEquals(original.entries, queues.load(ServerId(OWNER)).entries)
                assertEquals(before.entries[index].providerItemId, f.prepared.last().itemId)
                assertEquals(before.entries[index].queueEntryId.value, f.controller.state.value.queueEntryId)
                assertNotEquals(session, f.controller.state.value.playbackSessionId, "Restart begins a fresh session")
                assertTrue(f.probe.requested, "Transport Play must actually request playback")
            }
        }
    }

    @Test fun playlistSourceSurvivesPlayShuffleAndMixedInsertionsInPersistentQueue() {
        for (shuffle in listOf(false, true)) Fixture().use { f ->
            val source = QueueSourceContext(QueueSourceKind.Playlist, ProviderItemId(OWNER, "playlist / opaque"), "My Mix")
            f.controller.playQueue(album("a", "b", "c"), 0, AndroidQueueSource.Playlist, "My Mix", "playlist / opaque", shuffle)
            val queues = PersistentQueueStore(f.store.database)
            assertEquals(3, queues.load(ServerId(OWNER)).entries.size)
            assertTrue(queues.load(ServerId(OWNER)).entries.all { it.sourceContext == source })
            assertTrue(f.controller.addToQueue(album("next"), AndroidQueueInsertion.PlayNext,
                AndroidQueueSource.Playlist, "Another", "playlist-next"))
            assertTrue(f.controller.addToQueue(album("later"), AndroidQueueInsertion.AddToQueue,
                AndroidQueueSource.Playlist, "Later", "playlist-later"))
            assertTrue(f.controller.addToQueue(album("search"), AndroidQueueInsertion.AddToQueue,
                AndroidQueueSource.Search, "Search"))
            val stored = queues.load(ServerId(OWNER)).entries
            assertEquals(6, stored.size)
            assertEquals(source, stored.first { it.providerItemId.rawId == "a" }.sourceContext)
            assertEquals(QueueSourceContext(QueueSourceKind.Playlist, ProviderItemId(OWNER, "playlist-next"), "Another"),
                stored.first { it.providerItemId.rawId == "next" }.sourceContext)
            assertEquals(QueueSourceContext(QueueSourceKind.Playlist, ProviderItemId(OWNER, "playlist-later"), "Later"),
                stored.first { it.providerItemId.rawId == "later" }.sourceContext)
            assertEquals(QueueSourceContext(QueueSourceKind.Search, null, "Search"), stored.last().sourceContext)
            f.controller.next()
            assertEquals("next", f.controller.state.value.queue[f.controller.state.value.currentIndex!!].track.rawId)
            assertEquals(stored, queues.load(ServerId(OWNER)).entries, "Advancement must keep each entry's provenance")
        }
    }

    @Test fun albumQueuePublishesUpNextAndJumpStartsTheNamedEntry() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 1, AndroidQueueSource.Album, "Album", "album-id")
            val started = f.controller.state.value
            assertEquals(listOf("t1", "t2", "t3"), started.queue.map { it.track.rawId })
            assertEquals(1, started.currentIndex)
            assertEquals("Title t2", started.title)
            assertEquals("Artist", started.artist)
            assertEquals("cover-t2", started.artworkKey)
            assertTrue(started.playWhenReady)
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId })
            assertTrue(f.probe.requested)

            val third = started.queue[2].queueEntryId
            f.controller.jumpTo(third)
            val jumped = f.controller.state.value
            assertEquals(listOf("t2", "t3"), f.prepared.map { it.itemId.rawId })
            assertEquals(third, jumped.queueEntryId)
            assertEquals(started.queue.map { it.queueEntryId }, jumped.queue.map { it.queueEntryId },
                "A jump must keep every queue entry identity")
            assertNotEquals(started.playbackSessionId, jumped.playbackSessionId)
        }
    }

    /**
     * Play Next lands straight after the playing entry, in the order given; Add to Queue appends.
     * Neither touches the playing session: same entry, session and attempt, nothing prepared again.
     */
    @Test fun playNextInsertsAfterTheCurrentEntryAndAddToQueueAppendsWithoutTouchingTheSession() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val before = f.controller.state.value
            assertTrue(f.controller.addToQueue(album("x", "y"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            assertEquals(listOf("t1", "x", "y", "t2", "t3"), f.controller.state.value.queue.map { it.track.rawId })
            assertTrue(f.controller.addToQueue(album("z"), AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Album, "Other", "other-album"))
            val after = f.controller.state.value
            assertEquals(listOf("t1", "x", "y", "t2", "t3", "z"), after.queue.map { it.track.rawId })
            assertEquals("Title x", after.queue[1].track.title, "an added track is titled from what the caller held")
            assertEquals(0, after.currentIndex)
            assertEquals(before.queueEntryId, after.queueEntryId)
            assertEquals(before.playbackSessionId, after.playbackSessionId, "an edit is not a session boundary")
            assertEquals(before.attemptId, after.attemptId)
            assertEquals(before.phase, after.phase)
            assertTrue(after.playWhenReady)
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "adding starts nothing")
            val stored = PersistentQueueStore(f.store.database).load(ServerId(OWNER)).entries
            assertEquals(listOf(QueueAddedBy.PlayNow, QueueAddedBy.PlayNext, QueueAddedBy.PlayNext, QueueAddedBy.PlayNow,
                QueueAddedBy.PlayNow, QueueAddedBy.AddToQueue), stored.map { it.addedBy })
            assertEquals(QueueSourceContext(QueueSourceKind.Album, ProviderItemId(OWNER, "other-album"), "Other"),
                stored.last().sourceContext, "an added entry says where it came from")
            // Next now reaches the first track played next.
            f.controller.next()
            assertEquals(listOf("t1", "x"), f.prepared.map { it.itemId.rawId })
        }
    }

    /** "Play Next" in an empty player means "play this": there is no queue to add to. */
    @Test fun addingToNoQueuePlaysTheTracksAsANewQueue() {
        for (insertion in AndroidQueueInsertion.entries) {
            Fixture().use { f ->
                assertTrue(f.controller.addToQueue(album("a", "b"), insertion, AndroidQueueSource.Album, "Album", "album-id"))
                val state = f.controller.state.value
                assertEquals(listOf("a", "b"), state.queue.map { it.track.rawId }, "$insertion")
                assertEquals(0, state.currentIndex)
                assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
                assertTrue(f.probe.requested)
            }
        }
    }

    /** Another account's queue is not one to add to; the tracks play as this account's own queue. */
    @Test fun addingOverAnotherAccountsQueuePlaysTheTracksAndLeavesThatQueueAlone() {
        Fixture(savedOwner = "different-account", savedSongs = listOf("x", "y")).use { f ->
            val foreign = PersistentQueueStore(f.store.database).load(ServerId("different-account"))
            assertTrue(f.controller.addToQueue(album("a"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            assertEquals(listOf("a"), f.controller.state.value.queue.map { it.track.rawId })
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
            assertEquals(foreign, PersistentQueueStore(f.store.database).load(ServerId("different-account")))
            assertFalse(f.controller.addToQueue(listOf(AndroidTrack("different-account", "q", "Q")),
                AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Library, "Library"), "a foreign track is refused")
            assertEquals(listOf("a"), f.controller.state.value.queue.map { it.track.rawId })
        }
    }

    /**
     * Play Next and Add to Queue while a new queue is still resolving are accepted, as the Apple
     * shells accept them, held, and applied to that queue the moment it is in place -- in the order
     * they were made, landing exactly where they would have had the queue already started, each with
     * its own source. Applying them starts nothing and leaves the new entry's session alone.
     */
    @Test fun additionsWhileANewQueueResolvesAreHeldAndAppliedInOrderOnceItStarts() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        Fixture(loadSong = { id ->
            if (id == "n1") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it } else song(id)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.playQueue(album("n1", "n2", "n3"), 0, AndroidQueueSource.Album, "New", "new-album")
            val held = assertNotNull(pending, "setup: the new queue must be resolving")
            assertTrue(f.controller.addToQueue(album("x"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            assertTrue(f.controller.addToQueue(album("y"), AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Album, "Other", "other-album"))
            assertTrue(f.controller.addToQueue(album("z"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Search, "Search"))
            assertEquals(listOf("t1", "t2"), f.controller.state.value.queue.map { it.track.rawId },
                "held, not applied to the queue the new one is about to replace")
            held.resume(song("n1"))
            val state = f.controller.state.value
            // The second Play Next lands straight after the playing entry, ahead of the first, as live.
            assertEquals(listOf("n1", "z", "x", "n2", "n3", "y"), state.queue.map { it.track.rawId })
            assertEquals(0, state.currentIndex)
            assertEquals(listOf("t1", "n1"), f.prepared.map { it.itemId.rawId }, "applying the additions starts nothing")
            assertEquals(f.prepared.last().playbackSessionId.value, state.playbackSessionId,
                "the new entry's session is the one that was started")
            val stored = PersistentQueueStore(f.store.database).load(ServerId(OWNER)).entries
            assertEquals(listOf(QueueAddedBy.PlayNow, QueueAddedBy.PlayNext, QueueAddedBy.PlayNext, QueueAddedBy.PlayNow,
                QueueAddedBy.PlayNow, QueueAddedBy.AddToQueue), stored.map { it.addedBy })
            assertEquals(QueueSourceContext(QueueSourceKind.Search, null, "Search"), stored[1].sourceContext)
            assertEquals(QueueSourceContext(QueueSourceKind.Album, ProviderItemId(OWNER, "other-album"), "Other"),
                stored.last().sourceContext, "a held addition keeps its own source")
            assertTrue(f.controller.addToQueue(album("w"), AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Library, "Library"),
                "once the queue has started, adding goes straight in")
            assertEquals(listOf("n1", "z", "x", "n2", "n3", "y", "w"), f.controller.state.value.queue.map { it.track.rawId })
        }
    }

    /**
     * Held additions share the fate of the queue they were made to: one stopped, superseded or failed
     * before it starts takes them with it. They never land on a queue they were not added to.
     */
    @Test fun heldAdditionsGoWithAQueueThatNeverStarts() {
        for (how in listOf("stop", "superseded", "failed")) {
            var pending: Continuation<AuthenticatedEndpointResponse>? = null
            Fixture(loadSong = { id ->
                if (id == "n1") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it } else song(id)
            }).use { f ->
                f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.controller.playQueue(album("n1", "n2"), 0, AndroidQueueSource.Album, "New", "new-album")
                val held = assertNotNull(pending, "$how: setup: the new queue must be resolving")
                assertTrue(f.controller.addToQueue(album("x"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
                when (how) {
                    "stop" -> { f.controller.stop(); held.resume(song("n1")) }
                    "superseded" -> { f.controller.playQueue(album("m1", "m2"), 0, AndroidQueueSource.Album, "M", "m-album"); held.resume(song("n1")) }
                    "failed" -> held.resume(missingSong())
                }
                val expected = if (how == "superseded") listOf("m1", "m2") else listOf("t1", "t2")
                assertEquals(expected, f.controller.state.value.queue.map { it.track.rawId }, "$how: x is not applied")
                // The person is told, at the moment the queue is abandoned -- not at some later play.
                val dropped = assertNotNull(f.controller.state.value.droppedAdditions, "$how: the drop is said")
                assertEquals(1, dropped.trackCount, how)
                // A later queue does not pick up what was held for the abandoned one, and says nothing
                // more: nothing was held for it.
                f.controller.playQueue(album("k1", "k2"), 0, AndroidQueueSource.Album, "K", "k-album")
                assertEquals(listOf("k1", "k2"), f.controller.state.value.queue.map { it.track.rawId }, "$how")
                assertEquals(dropped, f.controller.state.value.droppedAdditions, "$how: no second drop")
                assertTrue(f.controller.dismissDroppedAdditions(dropped.sequence), "$how: the first claim speaks")
                assertNull(f.controller.state.value.droppedAdditions, "$how: dismissed once said")
                // A second surface running the same effect for the same drop must stay silent.
                assertFalse(f.controller.dismissDroppedAdditions(dropped.sequence), "$how: a second claim is refused")
            }
        }
    }

    /**
     * A superseded queue whose read fails only afterwards takes nothing with it: what is held by then
     * is the newer queue's, which still receives it, and no failure is shown for a queue nobody awaits.
     * The fixture's read ignores cancellation, so the superseded resolution does see the failure.
     */
    @Test fun aSupersededQueueFailingLateKeepsTheNewerQueuesAdditions() {
        val pending = mutableMapOf<String, Continuation<AuthenticatedEndpointResponse>>()
        Fixture(loadSong = { id ->
            if (id == "n1" || id == "m1") suspendCoroutine<AuthenticatedEndpointResponse> { pending[id] = it } else song(id)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.playQueue(album("n1", "n2"), 0, AndroidQueueSource.Album, "N", "n-album")
            f.controller.playQueue(album("m1", "m2"), 0, AndroidQueueSource.Album, "M", "m-album")
            assertNotNull(pending["n1"], "setup: the superseded read is still outstanding")
            assertTrue(f.controller.addToQueue(album("x"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            pending.getValue("n1").resume(missingSong())
            assertNull(f.controller.state.value.droppedAdditions, "the newer queue's addition is not dropped")
            assertNull(f.controller.state.value.error, "no failure for a queue nobody awaits")
            pending.getValue("m1").resume(song("m1"))
            assertEquals(listOf("m1", "x", "m2"), f.controller.state.value.queue.map { it.track.rawId })
        }
    }

    /**
     * Next, Previous or a Jump while a new queue loads moves within the queue the core still holds,
     * abandoning the new one: what was held for it is dropped and said at that moment -- never left
     * held, to be said at some later, unrelated play or stop.
     */
    @Test fun nextPreviousOrJumpWhileANewQueueLoadsDropsItsHeldAdditionsAtOnce() {
        for (how in listOf("next", "previous", "jump")) {
            var pending: Continuation<AuthenticatedEndpointResponse>? = null
            Fixture(loadSong = { id ->
                if (id == "n1") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it } else song(id)
            }).use { f ->
                f.controller.playQueue(album("t1", "t2", "t3"), 1, AndroidQueueSource.Album, "Album", "album-id")
                val ids = f.controller.state.value.queue.associate { it.track.rawId to it.queueEntryId }
                f.controller.playQueue(album("n1", "n2"), 0, AndroidQueueSource.Album, "New", "new-album")
                assertNotNull(pending, "$how: setup: the new queue must be resolving")
                assertTrue(f.controller.addToQueue(album("x"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
                assertNull(f.controller.state.value.droppedAdditions, "$how: setup: held, not yet dropped")
                when (how) {
                    "next" -> f.controller.next()
                    "previous" -> f.controller.previous()
                    else -> f.controller.jumpTo(ids.getValue("t3"))
                }
                val dropped = assertNotNull(f.controller.state.value.droppedAdditions, "$how: the drop is said at once")
                assertEquals(1, dropped.trackCount, how)
                val expected = when (how) { "next" -> "t3"; "previous" -> "t1"; else -> "t3" }
                assertEquals(expected, f.controller.state.value.queue.getOrNull(f.controller.state.value.currentIndex ?: -1)?.track?.rawId,
                    "$how: moved within the old queue")
                assertEquals(listOf("t1", "t2", "t3"), f.controller.state.value.queue.map { it.track.rawId }, "$how: x not applied")
                f.controller.dismissDroppedAdditions(dropped.sequence)
                // The next, unrelated play says nothing: nothing was left held.
                f.controller.playQueue(album("k1"), 0, AndroidQueueSource.Album, "K", "k-album")
                f.controller.stop()
                assertNull(f.controller.state.value.droppedAdditions, "$how: no late notice")
            }
        }
    }

    /**
     * A drop counts every track of every addition held, and a queue abandoned with nothing held says
     * nothing. Dismissing an earlier drop never hides a later one.
     */
    @Test fun aDropCountsEveryHeldTrackAndAnEmptyDropSaysNothing() {
        val pending = mutableMapOf<String, Continuation<AuthenticatedEndpointResponse>>()
        Fixture(loadSong = { id ->
            if (id == "n1" || id == "m1") suspendCoroutine<AuthenticatedEndpointResponse> { pending[id] = it } else song(id)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.playQueue(album("n1", "n2"), 0, AndroidQueueSource.Album, "New", "new-album")
            assertNotNull(pending["n1"], "setup: the new queue must be resolving")
            f.controller.stop()
            assertNull(f.controller.state.value.droppedAdditions, "nothing was held, so nothing is said")

            f.controller.playQueue(album("m1", "m2"), 0, AndroidQueueSource.Album, "M", "m-album")
            assertNotNull(pending["m1"], "setup: the next queue must be resolving")
            assertTrue(f.controller.addToQueue(album("x", "y"), AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Album, "X", "x-album"))
            assertTrue(f.controller.addToQueue(album("z"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            pending.getValue("m1").resume(missingSong())
            val first = assertNotNull(f.controller.state.value.droppedAdditions)
            assertEquals(3, first.trackCount, "two additions, three tracks")

            f.controller.playQueue(album("n1", "n2"), 0, AndroidQueueSource.Album, "New", "new-album")
            assertTrue(f.controller.addToQueue(album("w"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            f.controller.stop()
            val second = assertNotNull(f.controller.state.value.droppedAdditions)
            assertTrue(second.sequence > first.sequence)
            f.controller.dismissDroppedAdditions(first.sequence)
            assertEquals(second, f.controller.state.value.droppedAdditions, "dismissing the earlier drop keeps the later one")
        }
    }

    /**
     * Move, Remove and Clear made while a new queue is loading are refused, as a move of the playing
     * entry is: the queue shown is the one about to be replaced, so the edit would change it and then
     * vanish with it. Once the new queue is in place, edits apply to it.
     */
    @Test fun editsWhileANewQueueLoadsAreRefusedAndChangeNothing() {
        for (edit in listOf("move", "remove", "clear")) {
            var pending: Continuation<AuthenticatedEndpointResponse>? = null
            Fixture(loadSong = { id ->
                if (id == "n1") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it } else song(id)
            }).use { f ->
                f.controller.playQueue(album("t1", "t2", "t3", "t4"), 0, AndroidQueueSource.Album, "Album", "album-id")
                val ids = f.controller.state.value.queue.associate { it.track.rawId to it.queueEntryId }
                // The control: each edit is accepted on the queue before a new one is loading.
                val accepted = when (edit) {
                    "move" -> f.controller.moveEntry(ids.getValue("t4"), 1)
                    "remove" -> f.controller.removeEntry(ids.getValue("t4"))
                    else -> f.controller.clearUpcoming()
                }
                assertTrue(accepted, "$edit: the control requires the edit to be accepted while nothing loads")
                f.controller.playQueue(album("t1", "t2", "t3", "t4"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.controller.playQueue(album("n1", "n2", "n3"), 0, AndroidQueueSource.Album, "New", "new-album")
                val held = assertNotNull(pending, "$edit: setup: the new queue must be resolving")
                val shown = f.controller.state.value.queue
                val loadingIds = shown.associate { it.track.rawId to it.queueEntryId }
                val refused = when (edit) {
                    "move" -> f.controller.moveEntry(loadingIds.getValue("t4"), 1)
                    "remove" -> f.controller.removeEntry(loadingIds.getValue("t4"))
                    else -> f.controller.clearUpcoming()
                }
                assertFalse(refused, "$edit: refused while the new queue loads")
                assertEquals(shown, f.controller.state.value.queue, "$edit: the old queue is unchanged")
                held.resume(song("n1"))
                assertEquals(listOf("n1", "n2", "n3"), f.controller.state.value.queue.map { it.track.rawId }, edit)
                val newIds = f.controller.state.value.queue.associate { it.track.rawId to it.queueEntryId }
                assertTrue(f.controller.removeEntry(newIds.getValue("n3")), "$edit: the new queue takes edits")
            }
        }
    }

    /**
     * Move and Remove name entries by identity, the playing session plays on through both, and the
     * current entry cannot be removed (spec §14.1). A refused edit changes nothing and says so.
     */
    @Test fun moveAndRemoveKeepTheSessionAndTheCurrentEntryCannotBeRemoved() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3", "t4"), 1, AndroidQueueSource.Album, "Album", "album-id")
            val before = f.controller.state.value
            val ids = before.queue.associate { it.track.rawId to it.queueEntryId }

            assertTrue(f.controller.moveEntry(ids.getValue("t4"), 2), "Move up")
            assertEquals(listOf("t1", "t2", "t4", "t3"), f.controller.state.value.queue.map { it.track.rawId })
            assertTrue(f.controller.moveEntry(ids.getValue("t4"), 3), "Move down")
            assertEquals(listOf("t1", "t2", "t3", "t4"), f.controller.state.value.queue.map { it.track.rawId })

            assertFalse(f.controller.removeEntry(ids.getValue("t2")), "the playing entry cannot be removed")
            assertFalse(f.controller.removeEntry("no-such-entry"))
            assertFalse(f.controller.moveEntry(ids.getValue("t3"), 9), "a position outside the queue")
            assertFalse(f.controller.moveEntry("no-such-entry", 0))
            assertEquals(listOf("t1", "t2", "t3", "t4"), f.controller.state.value.queue.map { it.track.rawId })

            assertTrue(f.controller.removeEntry(ids.getValue("t3")))
            assertEquals(listOf("t1", "t2", "t4"), f.controller.state.value.queue.map { it.track.rawId })
            val after = f.controller.state.value
            assertEquals(1, after.currentIndex)
            assertEquals(before.queueEntryId, after.queueEntryId)
            assertEquals(before.playbackSessionId, after.playbackSessionId, "an edit is not a session boundary")
            assertEquals(before.attemptId, after.attemptId)
            assertEquals(before.phase, after.phase)
            assertNull(after.error, "a refused edit is not a playback failure")
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId }, "no edit starts anything")
            assertTrue(f.probe.requested)
        }
    }

    /**
     * Only Up Next is reordered (spec §14.1), as on the Apple shells, whose list moves only the rows
     * after the playing one: moving the playing entry, an entry already played, or any entry onto or
     * before the playing entry's position is refused and changes nothing. A move within Up Next is
     * accepted and the session plays on.
     */
    @Test fun movingThePlayingEntryOrAnEntryBeforeItIsRefused() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3", "t4"), 1, AndroidQueueSource.Album, "Album", "album-id")
            val before = f.controller.state.value
            val ids = before.queue.associate { it.track.rawId to it.queueEntryId }
            assertFalse(f.controller.moveEntry(ids.getValue("t2"), 3), "the playing entry is not moved")
            assertFalse(f.controller.moveEntry(ids.getValue("t1"), 3), "an entry already played is not moved")
            assertFalse(f.controller.moveEntry(ids.getValue("t4"), 1), "onto the playing entry's position puts it before it")
            assertFalse(f.controller.moveEntry(ids.getValue("t4"), 0), "before the playing entry")
            assertEquals(listOf("t1", "t2", "t3", "t4"), f.controller.state.value.queue.map { it.track.rawId })
            assertEquals(1, f.controller.state.value.currentIndex)

            assertTrue(f.controller.moveEntry(ids.getValue("t4"), 2), "to the top of Up Next")
            val after = f.controller.state.value
            assertEquals(listOf("t1", "t2", "t4", "t3"), after.queue.map { it.track.rawId })
            assertEquals(1, after.currentIndex)
            assertEquals(before.playbackSessionId, after.playbackSessionId)
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId })
        }
    }

    /**
     * An edit made while the new entry's start is still in flight -- its stream resolving -- is
     * applied and does not cost that start: the entry still reaches the engine, in the session that
     * began it, and the edits all stand. An edit is not a new request (spec §14.1), so it must never
     * invalidate the start in progress the way a new play, a skip or a stop does.
     */
    @Test fun anEditWhileTheStartIsInFlightNeitherLosesTheEditNorTheStart() {
        var pending: Continuation<PlaybackResolutionResult>? = null
        var request: PlaybackResolveRequest? = null
        Fixture(resolve = { r ->
            if (r.itemId.rawId == "t1" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val held = assertNotNull(pending, "setup: t1's start must be in flight")
            assertTrue(f.prepared.isEmpty(), "setup: nothing has reached the engine yet")
            assertTrue(f.controller.addToQueue(album("x"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            val session = assertNotNull(f.controller.state.value.playbackSessionId, "setup: t1's session has begun")
            val ids = f.controller.state.value.queue.associate { it.track.rawId to it.queueEntryId }
            assertEquals(listOf("t1", "x", "t2", "t3"), ids.keys.toList())
            assertTrue(f.controller.moveEntry(ids.getValue("t3"), 1))
            assertTrue(f.controller.removeEntry(ids.getValue("t2")))
            assertEquals(listOf("t1", "t3", "x"), f.controller.state.value.queue.map { it.track.rawId })
            held.resume(resolved(assertNotNull(request)))
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "the start in flight still reaches the engine")
            val state = f.controller.state.value
            assertEquals(session, state.playbackSessionId, "in the session that began it")
            assertEquals(f.prepared.single().playbackSessionId.value, state.playbackSessionId)
            assertEquals(listOf("t1", "t3", "x"), state.queue.map { it.track.rawId }, "every edit stands")
            assertTrue(f.probe.requested, "and it is asked to play")
        }
    }

    /** Clear removes every entry after the current one and nothing before it; the session plays on. */
    @Test fun clearUpcomingKeepsThePlayedEntriesAndTheSession() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3", "t4"), 1, AndroidQueueSource.Album, "Album", "album-id")
            val before = f.controller.state.value
            assertTrue(f.controller.clearUpcoming())
            val after = f.controller.state.value
            assertEquals(listOf("t1", "t2"), after.queue.map { it.track.rawId })
            assertEquals(1, after.currentIndex)
            assertEquals(before.playbackSessionId, after.playbackSessionId)
            assertEquals(before.attemptId, after.attemptId)
            assertEquals(before.phase, after.phase, "the playing entry plays on")
            assertTrue(after.playWhenReady)
            assertFalse(after.canGoNext, "nothing follows the playing entry now")
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun naturalCompletionAdvancesToTheNextAlbumTrack() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId })
            assertEquals(1, f.controller.state.value.currentIndex)
            assertEquals("Title t2", f.controller.state.value.title)
        }
    }

    @Test fun aDownloadedSongPlaysFromItsFileWithoutAskingTheServerAndTheNextStreams() {
        val songReads = mutableListOf<String>()
        val file = java.io.File.createTempFile("dulcet-local", ".wav")
        try {
            Fixture(loadSong = { id -> songReads += id; song(id) }, localPlans = { rawId ->
                if (rawId != "down") null else LocalPlaybackPlan(DownloadId("download:down"),
                    DownloadIdentity(OWNER, "down", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, file.path)
            }).use { f ->
                f.controller.playQueue(album("down", "net"), 0, AndroidQueueSource.Album, "Album", "album-id")
                assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId })
                assertEquals(file.path, f.preparedLocal.single().local.absolutePath)
                assertTrue(f.prepared.isEmpty(), "a downloaded song is never resolved against the server")
                assertTrue(songReads.isEmpty(), "a downloaded song's start reads nothing from the server")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                assertTrue(f.controller.state.value.playingDownload)
                assertEquals("Title down", f.controller.state.value.title)

                f.probe.state = androidx.media3.common.Player.STATE_ENDED
                f.probe.events()
                assertEquals(listOf("net"), f.prepared.map { it.itemId.rawId })
                assertEquals(listOf("net"), songReads)
                assertFalse(f.controller.state.value.playingDownload)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun offlineShuffleSkipsUnavailableFirstInputBeforeMetadataAndExplicitUnavailablePreservesQueue() {
        val songReads = mutableListOf<String>()
        val file = java.io.File.createTempFile("dulcet-offline", ".wav")
        try {
            Fixture(loadSong = { id -> songReads += id; song(id) }, localPlans = { rawId ->
                if (rawId != "down") null else LocalPlaybackPlan(DownloadId("download:down"),
                    DownloadIdentity(OWNER, "down", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, file.path)
            }).use { f ->
                f.controller.setNetworkAvailable(false)
                f.controller.playQueue(album("net", "down"), 0, AndroidQueueSource.Library, "My Library", shuffle = true)
                assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId })
                assertTrue(songReads.isEmpty(), "offline shuffle cannot resolve its unavailable first input")
                assertEquals(listOf("down"), f.controller.state.value.queue.map { it.track.rawId })
                val before = f.controller.state.value
                f.controller.playQueue(album("net"), 0, AndroidQueueSource.Library, "My Library")
                assertEquals(before.queue, f.controller.state.value.queue, "refusing a stale offline selection preserves the old playable queue")
                assertEquals(before.playbackSessionId, f.controller.state.value.playbackSessionId)
                assertEquals(before.playWhenReady, f.controller.state.value.playWhenReady)
                assertTrue(songReads.isEmpty())
            }
        } finally { file.delete() }
    }

    /**
     * Spec §16.13: a restored queue's downloaded entry is named from what its download kept. Offline,
     * the song's own read fails; it is never asked for the downloaded entry, which still has its title,
     * artist and album, while the streamed entry beside it — the control — stays untitled.
     */
    @Test fun aRestoredDownloadedEntryIsNamedFromItsDownloadOfflineWithNoSongRead() {
        val songReads = mutableListOf<String>()
        val file = java.io.File.createTempFile("dulcet-local", ".wav")
        val downloads = object : AndroidLocalPlaybackSource {
            override suspend fun localPlan(rawId: String) = if (rawId != "down") null else LocalPlaybackPlan(
                DownloadId("download:down"), DownloadIdentity(OWNER, "down", DownloadIdentity.ORIGINAL_PROFILE),
                AudioContainer.Wav, 44, file.path)
            override suspend fun localTrack(rawId: String) = if (rawId != "down") null else
                AndroidTrack(OWNER, "down", "Kept title", "Kept artist", "Kept album", 40_000, "cover-kept")
        }
        try {
            Fixture(savedOwner = OWNER, savedSongs = listOf("down", "net"), localPlans = downloads, loadSong = { id ->
                songReads += id
                throw AndroidPlaybackIOException(DomainError.Transport.Unreachable)
            }).use { f ->
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId }, "control: the restored entry plays from its file")
                val state = f.controller.state.value
                assertEquals("Kept title", state.title)
                assertEquals("Kept artist", state.artist)
                assertEquals("Kept album", state.album)
                assertEquals(listOf("Kept title", ""), state.queue.map { it.track.title })
                assertEquals(listOf("net"), songReads, "only the streamed entry's song is read; the downloaded one never is")
            }
        } finally {
            file.delete()
        }
    }

    @Test fun restoredUntitledDownloadsAndRememberedRowsUseTheLocalizedTitle() {
        val context = RuntimeEnvironment.getApplication()
        val title = context.getString(R.string.playback_untitled)
        for (blank in listOf("", "   ")) {
            val file = java.io.File.createTempFile("dulcet-untitled-local", ".wav")
            val downloads = object : AndroidLocalPlaybackSource {
                override suspend fun localPlan(rawId: String) = if (rawId != "down") null else LocalPlaybackPlan(
                    DownloadId("download:down"), DownloadIdentity(OWNER, "down", DownloadIdentity.ORIGINAL_PROFILE),
                    AudioContainer.Wav, 44, file.path)
                override suspend fun localTrack(rawId: String) = if (rawId != "down") null else
                    AndroidTrack(OWNER, "down", blank, "Artist", "Album", 40_000)
            }
            try {
                Fixture(savedOwner = OWNER, savedSongs = listOf("down", "remembered"), localPlans = downloads,
                    loadSong = { throw AndroidPlaybackIOException(DomainError.Transport.Unreachable) }).use { f ->
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId })
                    assertEquals(title, f.controller.state.value.title)
                    assertEquals(title, f.controller.state.value.queue.first().track.title)
                    f.controller.rememberTracks(listOf(AndroidTrack(OWNER, "remembered", blank, durationMilliseconds = 40_000)))
                    assertEquals(listOf(title, title), f.controller.state.value.queue.map { it.track.title })
                }
            } finally { file.delete() }
        }
    }

    @Test fun anEndedQueueReportsNoSessionAndNoStalePosition() {
        Fixture().use { f ->
            f.controller.playQueue(album("only"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 39_000
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            val ended = f.controller.state.value
            assertEquals(null, ended.playbackSessionId, "The control requires the queue to have ended")
            assertEquals(0, ended.positionMilliseconds, "A finished queue must not report the last song's position")
            assertNull(ended.durationMilliseconds)
            assertFalse(ended.playWhenReady)
            assertEquals("", ended.title)
        }
    }

    @Test fun refusedTransportVerbIsNotReportedAsAPlaybackFailure() {
        Fixture().use { f ->
            // Nothing is prepared, so the engine refuses both seeks. That must not paint an error.
            f.controller.seek(10_000)
            f.controller.sessionPlayer.seekTo(5_000)
            assertTrue(f.probe.seekCommands.isEmpty(), "The control requires both seeks to have been refused")
            assertNull(f.controller.state.value.error)
        }
    }

    @Test fun foreignAccountTrackIsRefusedBeforeAnyRequest() {
        val loaded = mutableListOf<String>()
        Fixture(loadSong = { loaded += it; song(it) }).use { f ->
            f.controller.playQueue(listOf(AndroidTrack(OWNER, "a", "A"), AndroidTrack("provider:other", "b", "B")),
                0, AndroidQueueSource.Album, "Album", "album-id")
            assertEquals(DomainError.Auth.Forbidden, f.controller.state.value.error)
            assertTrue(loaded.isEmpty(), "No request may be made for a queue holding another account's song")
            assertTrue(f.prepared.isEmpty())
        }
    }

    @Test fun previousRestartsASongPastThreeSecondsAndOtherwiseMovesBack() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 1, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 10_000
            f.controller.skipToPrevious()
            assertEquals(listOf(0L), f.probe.seekCommands, "Past the threshold, Previous restarts the song")
            assertEquals(listOf("b"), f.prepared.map { it.itemId.rawId })
            f.controller.skipToPrevious()
            assertEquals(listOf("b", "a"), f.prepared.map { it.itemId.rawId }, "At the start, Previous moves back")
        }
    }

    @Test fun sessionAdvertisesQueueSkipsThatTheOneItemTimelineCannot() {
        Fixture().use { f ->
            val session = f.controller.sessionPlayer
            assertTrue(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
            assertTrue(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS))
            assertFalse(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS),
                "System controllers must not edit the core-owned queue")
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            assertTrue(session.hasNextMediaItem())
            session.seekToNext()
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId })
            assertFalse(session.hasNextMediaItem())
        }
    }

    @Test fun previousAtTheFirstSongRestartsItAndNeverClearsTheQueue() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            val before = f.controller.state.value
            assertFalse(before.canGoPrevious, "There is no previous song at the start without repeat-all")
            assertFalse(before.canRestart, "One second in, Previous would do nothing worth a button")
            assertTrue(before.canGoNext)
            f.controller.previous()
            f.controller.sessionPlayer.seekToPreviousMediaItem()
            val after = f.controller.state.value
            assertEquals(0, after.currentIndex, "Previous must not clear the queue")
            assertEquals(before.queue.map { it.queueEntryId }, after.queue.map { it.queueEntryId })
            assertEquals(before.playbackSessionId, after.playbackSessionId)
            assertEquals(listOf(0L, 0L), f.probe.seekCommands, "At the first song, Previous restarts it")
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
            f.controller.cycleRepeatMode() // repeat-all wraps, so Previous now has somewhere to go
            assertTrue(f.controller.state.value.canGoPrevious)
        }
    }

    @Test fun nextAtTheLastSongDoesNothing() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 1, AndroidQueueSource.Album, "Album", "album-id")
            assertFalse(f.controller.state.value.canGoNext)
            val session = f.controller.state.value.playbackSessionId
            f.controller.next()
            f.controller.sessionPlayer.seekToNext()
            assertEquals(1, f.controller.state.value.currentIndex)
            assertEquals(session, f.controller.state.value.playbackSessionId)
            assertEquals(listOf("b"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun aSystemPauseBecomesTheRequestedStateSoNextDoesNotResumeAloud() {
        for (reason in listOf(androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            assertTrue(f.controller.state.value.playWhenReady, "The control requires playback to have been requested")
            f.probe.systemPause(reason)
            assertFalse(f.controller.state.value.playWhenReady, "The UI must offer Play once the system paused")
            f.controller.next()
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId })
            assertFalse(f.probe.requested, "Next after an unplug must not start playing through the speaker")
            f.controller.togglePlayPause()
            assertTrue(f.probe.requested, "The first tap after a system pause must play, not be a no-op")
        }
    }

    @Test fun anUnplayableFormatIsTranscodedAndItsStreamIsNotSeekable() {
        Fixture(loadSong = { id -> song(id, suffix = "wma") }).use { f ->
            f.controller.playSong(OWNER, "wma-song", "Legacy format")
            assertNull(f.controller.state.value.error, "A format outside the direct-play list must not be refused")
            val plan = f.prepared.single()
            assertEquals(AudioContainer.Mp3, plan.expectedContainer)
            assertEquals("mp3", plan.parameters["format"], "The profile's transcoding target must be requested")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            assertFalse(f.controller.state.value.seekable, "A transcode is not seekable until server offsets exist")
            f.controller.seek(10_000)
            assertTrue(f.probe.seekCommands.isEmpty())
        }
        // The control: a direct format through the same path is seekable and requests no format.
        Fixture(loadSong = { id -> song(id, suffix = "flac") }).use { f ->
            f.controller.playSong(OWNER, "flac-song", "Direct format")
            assertNull(f.prepared.single().parameters["format"])
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            assertTrue(f.controller.state.value.seekable)
        }
    }

    @Test fun anotherSavedAccountsQueueStaysUntouchedWhenStartupSelectsAnEmptyOwnQueue() {
        val loaded = mutableListOf<String>()
        Fixture(savedOwner = "different-account", savedSongs = listOf("x", "y", "z"),
            loadSong = { id -> loaded += id; song(id) }).use { f ->
            val foreign = PersistentQueueStore(f.store.database).load(ServerId("different-account"))
            val entry = foreign.entries[1].queueEntryId.value
            f.controller.play(); f.controller.next(); f.controller.previous(); f.controller.skipToPrevious()
            f.controller.jumpTo(entry); f.controller.setShuffle(true); f.controller.cycleRepeatMode()
            assertFalse(f.controller.moveEntry(entry, 0)); assertFalse(f.controller.removeEntry(entry))
            assertTrue(f.controller.clearUpcoming(), "Clearing our selected empty queue is a successful no-op")
            f.controller.sessionPlayer.seekToNext(); f.controller.sessionPlayer.play()
            assertTrue(loaded.isEmpty(), "No request may be made for another account's queue")
            assertTrue(f.prepared.isEmpty())
            assertFalse(f.probe.requested)
            assertEquals(ServerId(OWNER), PersistentQueueStore(f.store.database).activeServerId(),
                "Startup selects the saved account instead of exposing the other saved server's queue")
            assertTrue(f.controller.state.value.queue.isEmpty(), "The selected account has no saved queue")
            assertNull(f.controller.state.value.error)
            assertNull(f.controller.state.value.playbackSessionId, "The foreign queue must not become a session")
            assertEquals(foreign, PersistentQueueStore(f.store.database).load(ServerId("different-account")),
                "The other account's queue must be left exactly as it was")
        }
    }

    @Test fun aClosedControllerIgnoresEveryVerbInsteadOfCrashing() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.close()
            f.controller.play(); f.controller.pause(); f.controller.togglePlayPause(); f.controller.next()
            f.controller.previous(); f.controller.skipToPrevious(); f.controller.seek(1); f.controller.stop()
            f.controller.jumpTo("anything"); f.controller.setShuffle(true); f.controller.cycleRepeatMode()
            f.controller.playSong(OWNER, "a", "A"); f.controller.rememberTracks(album("c"))
            assertFalse(f.controller.addToQueue(album("c"), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Library, "Library"))
            assertFalse(f.controller.moveEntry("anything", 0)); assertFalse(f.controller.removeEntry("anything"))
            assertFalse(f.controller.clearUpcoming())
            f.controller.sessionPlayer.play(); f.controller.sessionPlayer.seekForward(); f.controller.sessionPlayer.setVolume(0.5f)
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun aTrackThatCannotBeDecodedIsSkippedWithANoticeAndLeavesNoFailureLine() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            // Every publication, not only the last: a surface draws each one it is handed, and the
            // one carrying the notice is published before the next entry starts.
            val published = mutableListOf<AndroidPlaybackState>()
            val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                f.controller.state.collect { published += it }
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            watcher.cancel()
            assertTrue(published.any { it.skipNotice != null }, "The control requires the watcher to see the notice published")
            assertEquals(emptyList(), published.filter { it.skipNotice != null && it.error != null }.map { it.error },
                "No publication may show the notice and the failure line together")
            val skipped = f.controller.state.value
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId }, "The queue must move on past the track")
            assertEquals(1, skipped.currentIndex)
            assertEquals("Title t2", skipped.title)
            assertNull(skipped.error, "The failure line must not stay on screen for a track that is not playing")
            val notice = assertNotNull(skipped.skipNotice, "The person must be told which track was skipped")
            assertEquals("Title t1", notice.title)
            assertTrue(skipped.playWhenReady)
            assertTrue(f.probe.requested, "The next entry must be asked to play, not left paused")

            // A second skip is a second notice, even of a different track; the first is replaced.
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val again = assertNotNull(f.controller.state.value.skipNotice)
            assertEquals("Title t2", again.title)
            assertTrue(again.sequence > notice.sequence)
            assertEquals(listOf("t1", "t2", "t3"), f.prepared.map { it.itemId.rawId })

            // Closing the controller withdraws the notice with the queue it names.
            f.controller.close()
            assertNull(f.controller.state.value.skipNotice, "A closed controller must not keep a notice")
        }
    }

    @Test fun aConnectionFailureStillStopsAndPresentsTheFailureLine() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            val stopped = f.controller.state.value
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "Skipping would only repeat the failure")
            assertEquals(0, stopped.currentIndex)
            assertEquals(DomainError.Transport.Unreachable, stopped.error)
            assertNull(stopped.skipNotice)

            // The person moves on, and the line belongs to an attempt that is over (rule 5).
            f.controller.next()
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId }, "The control requires Next to start t2")
            assertNull(f.controller.state.value.error, "The failure line must not stay for a track that is not playing")
        }
    }

    /**
     * A connection failure stops on the entry; the attempt is over. Nothing seeks it, the app and
     * the system both offer Play, and Play -- the app's, or the system's through Media3's
     * play-button handling -- is Try Again (spec §12.1): a further attempt of the same play, in the
     * same session, instead of leaving the player failed.
     */
    @Test fun afterAConnectionFailureNothingSeeksItAndPlayRetriesTheEntry() {
        for (path in listOf("app", "system")) {
            Fixture().use { f ->
                f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                assertTrue(f.controller.state.value.playWhenReady, "$path: the control requires the entry asked to play")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                val failed = f.controller.state.value
                assertEquals(DomainError.Transport.Unreachable, failed.error, "$path: the control requires the failure line")
                assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "$path: the control requires no skip")
                assertFalse(failed.playWhenReady, "$path: the app must offer Play, not Pause, once the attempt has failed")
                assertFalse(failed.canRestart, "$path: a failed attempt has nothing to restart")
                f.controller.seek(1_000)
                f.controller.sessionPlayer.seekTo(2_000)
                f.controller.sessionPlayer.seekToDefaultPosition()
                assertEquals(emptyList(), f.probe.seekCommands, "$path: no seek may reach the failed attempt")
                if (path == "app") f.controller.togglePlayPause()
                else {
                    assertTrue(androidx.media3.common.util.Util.shouldShowPlayButton(f.controller.sessionPlayer),
                        "$path: the control requires the system to offer Play")
                    assertTrue(androidx.media3.common.util.Util.handlePlayButtonAction(f.controller.sessionPlayer),
                        "$path: the control requires Media3 to have acted on Play")
                }
                assertEquals(listOf("t1", "t1"), f.prepared.map { it.itemId.rawId }, "$path: Play begins a new attempt for the entry")
                assertNotEquals(f.prepared[0].attemptId, f.prepared[1].attemptId, "$path: a new attempt, not the failed one")
                assertEquals(f.prepared[0].playbackSessionId, f.prepared[1].playbackSessionId,
                    "$path: Try Again is a further attempt of the same play, in the same session (spec §12.1)")
                val retried = f.controller.state.value
                assertNull(retried.error, "$path: the player must not stay failed")
                assertTrue(retried.playWhenReady, "$path: the retry plays")
                assertEquals(0, retried.currentIndex, "$path: the same entry")
                assertEquals(emptyList(), f.probe.seekCommands, "$path: a failure before any progress saved no position to resume")
            }
        }
    }

    @Test fun retryingTheNowCachedCurrentSongPreservesTheOutageForPreloadNextAndNaturalEnd() {
        for (continuation in listOf("preload", "next", "natural end")) {
            val available = mutableSetOf<String>()
            val reads = mutableListOf<String>()
            val resolves = mutableListOf<String>()
            val file = java.io.File.createTempFile("dulcet-retry-local", ".wav")
            val incoming = PlayerProbe()
            try {
                Fixture(localPlans = { rawId ->
                    if (rawId !in available) null else LocalPlaybackPlan(DownloadId("cache:$rawId"),
                        DownloadIdentity(OWNER, rawId, "stream:wav"), AudioContainer.Wav, 44, file.path)
                }, loadSong = { id -> reads += id; song(id) }, resolve = { request ->
                    resolves += request.itemId.rawId; resolved(request)
                }, overlapPlayer = if (continuation == "preload") ({ incoming.player }) else null).use { f ->
                    f.controller.setAutoplay(false)
                    f.controller.setCrossfade(if (continuation == "preload") 5 else 0)
                    f.controller.playQueue(album("a", "b", "c"), 0, AndroidQueueSource.Search, "Search")
                    assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId }, "$continuation: control starts remotely")
                    val original = f.prepared.single()
                    // Streaming just completed a; c was streamed earlier. Wi-Fi remains online.
                    available += listOf("a", "c")
                    failConnection(f)
                    assertEquals("a", f.controller.state.value.queue[f.controller.state.value.currentIndex!!].track.rawId)
                    assertEquals(DomainError.Transport.Unreachable, f.controller.state.value.error)
                    play(f)
                    val retried = f.preparedLocal.single()
                    assertEquals(original.playbackSessionId, retried.playbackSessionId, "retry remains the same listen")
                    assertNotEquals(original.attemptId, retried.attemptId, "retry is a new attempt")
                    ready(f)
                    progress(f, 0, 1_000)
                    when (continuation) {
                        "next" -> f.controller.next()
                        "natural end" -> {
                            f.probe.position = 40_000
                            f.probe.state = androidx.media3.common.Player.STATE_ENDED
                            f.probe.events()
                        }
                    }
                    assertEquals(listOf("a", "c"), f.preparedLocal.map { it.itemId.rawId },
                        "$continuation must choose the next local song, including overlap preparation")
                    assertEquals(listOf("a"), reads, "$continuation must never read remote-only b")
                    assertEquals(listOf("a"), resolves, "$continuation must never resolve remote-only b")
                    assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId }, "$continuation must never prepare remote-only b")
                }
            } finally { file.delete() }
        }
    }

    @Test fun aStaleLocalAvailabilityRowAllowsARemoteRetryButOnlyRemoteProgressRestoresContinuation() {
        for (progresses in listOf(false, true)) {
            val reads = mutableListOf<String>()
            val available = mutableSetOf<String>()
            val file = java.io.File.createTempFile("dulcet-retry-fallback", ".wav")
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = rawId in available
                override suspend fun localPlan(rawId: String) = if (rawId != "c" || rawId !in available) null else
                    LocalPlaybackPlan(DownloadId("cache:c"), DownloadIdentity(OWNER, "c", "stream:wav"), AudioContainer.Wav, 44, file.path)
            }
            try {
                Fixture(localPlans = source, loadSong = { id -> reads += id; song(id) }).use { f ->
                    f.controller.setAutoplay(false)
                    f.controller.setCrossfade(0)
                    f.controller.playQueue(album("a", "b", "c"), 0, AndroidQueueSource.Search, "Search")
                    // a still has a published row, but validation finds its file gone.
                    available += listOf("a", "c")
                    failConnection(f)
                    play(f)
                    assertEquals(listOf("a", "a"), f.prepared.map { it.itemId.rawId }, "explicit retry may probe the wire after local validation fails")
                    ready(f)
                    if (progresses) progress(f, 0, 1_000)
                    f.controller.next()
                    if (progresses) {
                        assertEquals(listOf("a", "a", "b"), f.prepared.map { it.itemId.rawId }, "current remote progress establishes recovery")
                        assertEquals(listOf("a", "a", "b"), reads)
                    } else {
                        assertEquals(listOf("c"), f.preparedLocal.map { it.itemId.rawId }, "URL resolution and Ready do not establish recovery")
                        assertEquals(listOf("a", "a"), reads)
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun aStaleLocalRetryCannotProbeTheWireAfterOsLossOrClosedContact() {
        for (guard in listOf("OS offline", "contact closed")) {
          for (staleRow in listOf(false, true)) {
            val available = mutableSetOf<String>()
            val reads = mutableListOf<String>()
            val contact = ChosenContact().apply { open.value = true }
            val source = object : AndroidLocalPlaybackSource {
                override fun isLocallyPlayable(rawId: String) = rawId in available
                override suspend fun localPlan(rawId: String): LocalPlaybackPlan? = null
            }
            Fixture(localPlans = source, serverContact = contact, loadSong = { id -> reads += id; song(id) }).use { f ->
                f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Search, "Search")
                if (staleRow) available += "a"
                failConnection(f)
                val failed = f.controller.state.value
                if (guard == "OS offline") f.controller.setNetworkAvailable(false) else contact.open.value = false
                play(f)
                assertEquals(listOf("a"), reads, "$guard must refuse the remote fallback after local validation fails")
                assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
                assertFalse(f.controller.state.value.playWhenReady)
                if (!staleRow) {
                    assertEquals(failed.playbackSessionId, f.controller.state.value.playbackSessionId)
                    assertEquals(failed.attemptId, f.controller.state.value.attemptId, "a refused retry leaves the failed attempt selected")
                }
            }
          }
        }
    }

    /**
     * A connection failure while the entry is still being resolved, before the engine has it, stops
     * on the entry as an engine's would: the app offers Play, and Play resolves the entry again.
     */
    @Test fun aConnectionFailureBeforeTheEngineHasTheEntryOffersPlayAndPlayRetriesIt() {
        var attempts = 0
        Fixture(resolve = { r ->
            if (r.itemId.rawId == "t1" && attempts++ == 0) PlaybackResolutionResult.Failed(DomainError.Transport.Unreachable)
            else resolved(r)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val failed = f.controller.state.value
            assertEquals(1, attempts, "The control requires the one failed resolution")
            assertEquals(DomainError.Transport.Unreachable, failed.error, "The control requires the failure line")
            assertEquals(emptyList(), f.prepared.map { it.itemId.rawId }, "The control requires nothing prepared and no skip")
            assertEquals(0, failed.currentIndex)
            assertFalse(failed.playWhenReady, "The app must offer Play, not Pause, once the start has failed")
            f.controller.togglePlayPause()
            assertEquals(2, attempts, "Play resolves the entry again")
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "Play begins a new attempt for the same entry")
            assertNull(f.controller.state.value.error, "The player must not stay failed")
            assertTrue(f.controller.state.value.playWhenReady, "The retry plays")
            assertTrue(f.probe.requested, "The engine is told to play the retry")
        }
    }

    /**
     * A connection failure part way through, past the scrobble threshold, and then Play: the retry
     * resumes where the failure saved its position, in the same session, and the listen -- heard
     * to its end across the failure -- is one play, not two (spec §12.1, §28 item 7).
     */
    @Test fun playAfterAFailurePartWayThroughResumesTheSamePlayAndScrobblesItOnce() {
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        Fixture(onDelivery = { _, event -> if (event is RecordedPlaybackEvent.SubmittedPlay) submitted += event }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(25) { f.probe.position += 1_000; shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000)) }
            assertEquals(1, submitted.size, "The control requires 25 s of a 40 s track to have crossed the threshold")
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DomainError.Transport.Unreachable, f.controller.state.value.error, "The control requires the failure line")
            f.controller.togglePlayPause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("t1", "t1"), f.prepared.map { it.itemId.rawId }, "Play begins a further attempt for the entry")
            assertEquals(f.prepared[0].playbackSessionId, f.prepared[1].playbackSessionId, "The retry is the same play, in the same session")
            // The engine loads the retried item from its start; the controller moves it to the saved position.
            f.probe.position = 0
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(25_000L), f.probe.seekCommands, "The retry resumes at the position the failure saved")
            assertTrue(f.probe.requested, "The engine is told to play the retry")
            while (f.probe.position < 40_000) {
                f.probe.position += 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
            }
            assertEquals(listOf("t1"), submitted.map { it.itemId.rawId }, "One listen interrupted by a failure is one play: $submitted")
        }
    }

    /**
     * The test above cannot tell "the accumulator carried across a retry" from "the accumulator
     * reset on retry": after its resume, the media left is enough for a play on its own. This one
     * can. Two connection failures, each after less than the scrobble threshold of progress; each
     * retried with Play. The total heard crosses the threshold only when the pieces are summed, so
     * exactly one play is submitted, in one session; a reset accumulator would submit none.
     */
    @Test fun retryCarriesTheAccumulatorAcrossAFailureSoTwoPiecesBelowTheThresholdCountOnce() {
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        Fixture(onDelivery = { _, event -> if (event is RecordedPlaybackEvent.SubmittedPlay) submitted += event }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val duration = f.controller.state.value.durationMilliseconds
                ?: error("control: the fixture must publish the track's known duration")
            val threshold = (ScrobbleAccumulator.thresholdFor(duration.milliseconds)
                ?: error("control: the fixture track must be long enough to be eligible")).inWholeMilliseconds
            val first = threshold * 3 / 4
            val second = threshold / 2
            assertTrue(first < threshold && second < threshold, "control: each piece must be below the threshold alone")
            assertEquals(0, submitted.size, "control: nothing has played yet")
            ready(f)
            progress(f, 0, first)
            failConnection(f)
            play(f)
            ready(f)
            progress(f, first, first + second)
            failConnection(f)
            play(f)
            ready(f)
            assertEquals(3, f.prepared.size, "two failures, each retried: one attempt plus two retries")
            assertEquals(1, f.prepared.map { it.playbackSessionId }.toSet().size,
                "both retries stay inside the one session (spec §12.1)")
            assertEquals(1, submitted.size,
                "the pieces sum past the threshold only together: one play, not two and not none")
            assertEquals("t1", submitted.single().itemId.rawId)
        }
    }

    private fun ready(f: Fixture) {
        f.probe.position = 0
        f.probe.state = androidx.media3.common.Player.STATE_READY
        f.probe.events()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun progress(f: Fixture, from: Long, to: Long) {
        f.probe.position = from
        while (f.probe.position < to) {
            f.probe.position += 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        }
    }

    private fun failConnection(f: Fixture) {
        f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun play(f: Fixture) {
        f.controller.togglePlayPause()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aResolutionFailureThatIsTheTracksOwnIsSkippedToo() {
        Fixture(resolve = { r ->
            if (r.itemId.rawId == "t1") PlaybackResolutionResult.Failed(DomainError.Server.Known(70)) else resolved(r)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val state = f.controller.state.value
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId }, "The core must hear a failure before the engine had it")
            assertEquals(1, state.currentIndex)
            assertNull(state.error)
            assertEquals("Title t1", state.skipNotice?.title)
        }
    }

    @Test fun aSongTheServerNoLongerHasIsTheTracksOwnFailure() {
        Fixture(loadSong = { id -> if (id == "t2") missingSong() else song(id) }).use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            val state = f.controller.state.value
            assertEquals(listOf("t1", "t3"), f.prepared.map { it.itemId.rawId }, "Code 70 names the song, not the server")
            assertEquals(2, state.currentIndex)
            assertNull(state.error)
            assertEquals("Title t2", state.skipNotice?.title)
        }
    }

    @Test fun anAutomaticSkipGetsOnePassUnlessThePersonPresses() {
        // Play reaches the core on both of its paths: with the engine holding the entry, and after
        // Stop, which leaves the engine nothing, so Play restarts the entry. Both begin a new pass.
        for (press in listOf("nothing", "pause-then-play", "stop-then-play")) Fixture().use { f ->
            val pressesPlay = press != "nothing"
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.cycleRepeatMode()
            assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode)
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "The control requires a skip past a")
            // b plays a moment, which ends the chain but not the pass (spec §12.12 rule 3).
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
            when (press) {
                "pause-then-play" -> { f.controller.pause(); f.controller.play() }
                "stop-then-play" -> {
                    f.controller.stop()
                    f.controller.play()
                    assertEquals(listOf("a", "b", "b"), f.prepared.map { it.itemId.rawId }, "Play after Stop starts b again")
                    f.probe.state = androidx.media3.common.Player.STATE_READY
                    f.probe.events()
                }
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val state = f.controller.state.value
            if (pressesPlay) {
                assertEquals(if (press == "stop-then-play") listOf("a", "b", "b", "a") else listOf("a", "b", "a"),
                    f.prepared.map { it.itemId.rawId }, "The person's Play ($press) begins a new pass, so the skip may reach a again")
                assertNull(state.error)
                assertEquals("Title b", state.skipNotice?.title)
            } else {
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId },
                    "a was skipped past in this pass, so the queue stops on b instead of looping")
                assertEquals(DomainError.Playback.NoPlayableSource, state.error)
                assertEquals("Title a", state.skipNotice?.title, "The earlier notice is not replaced by a stop")
            }
        }
    }

    @Test fun aPlayPressedWhileTheNextEntryIsStillResolvingBeginsANewPass() {
        for (pressesPlay in listOf(false, true)) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.controller.cycleRepeatMode()
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                val held = assertNotNull(pending, "The control requires the skip to b to be resolving")
                assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
                if (pressesPlay) f.controller.play()
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "The control requires b to reach the engine")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.position = 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
                assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                assertEquals(if (pressesPlay) listOf("a", "b", "a") else listOf("a", "b"), f.prepared.map { it.itemId.rawId },
                    "A Play pressed while b was still resolving begins a new pass; without it a stays skipped")
            }
        }
    }

    /**
     * `play()`'s branch for an entry that is still being resolved, with a pass that is not empty.
     * a fails before the engine has it, so the engine holds nothing while the skip resolves b and
     * Play takes that branch. The person's Play there must begin a new pass, or a later failure of
     * b stops the queue instead of reaching a again under repeat-all (spec §12.12 rule 3).
     */
    @Test fun pauseThenPlayWhileTheEntrySkippedToIsResolvingBeginsANewPass() {
        for (pressesPlay in listOf(false, true)) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            val resolvedIds = mutableListOf<String>()
            Fixture(resolve = { r ->
                resolvedIds += r.itemId.rawId
                when {
                    r.itemId.rawId == "a" -> PlaybackResolutionResult.Failed(DomainError.Server.Known(70))
                    r.itemId.rawId == "b" && request == null -> { request = r; suspendCoroutine { pending = it } }
                    else -> resolved(r)
                }
            }).use { f ->
                // Repeat-all is set on an earlier queue, so that setting it does not begin the pass
                // this test needs to be non-empty.
                f.controller.playQueue(album("x"), 0, AndroidQueueSource.Album, "Album", "album-x")
                f.controller.cycleRepeatMode()
                assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode)
                f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
                val held = assertNotNull(pending, "The control requires the skip to b to be resolving")
                assertEquals("Title a", f.controller.state.value.skipNotice?.title, "The control requires a skipped")
                assertEquals(listOf("x"), f.prepared.map { it.itemId.rawId },
                    "The control requires the engine to hold nothing of this queue, so Play takes the resolving branch")
                assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode, "The control requires repeat-all")
                if (pressesPlay) { f.controller.pause(); f.controller.play() }
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("x", "b"), f.prepared.map { it.itemId.rawId }, "The control requires b to reach the engine")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.position = 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
                assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                assertEquals(if (pressesPlay) 2 else 1, resolvedIds.count { it == "a" },
                    "pressesPlay=$pressesPlay: a Play while b resolved begins a new pass, so the skip may reach a again")
            }
        }
    }

    /**
     * Plays a to [position] and ends its attempt [how] -- a skip past its failure, or a natural end
     * -- with b held resolving, so the engine still holds a's attempt, which is over. Returns the
     * continuation that lets b resolve.
     */
    private fun endAWhileBResolves(f: Fixture, how: String, position: Long, held: () -> Continuation<PlaybackResolutionResult>?):
        Continuation<PlaybackResolutionResult> {
        f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
        f.probe.state = androidx.media3.common.Player.STATE_READY
        f.probe.events()
        f.probe.position = position
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertTrue(f.controller.state.value.canRestart, "$how: the control requires a restart offered while a plays")
        when (how) {
            "skip" -> f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            "natural end" -> { f.probe.state = androidx.media3.common.Player.STATE_ENDED; f.probe.events() }
            else -> error(how)
        }
        val pending = assertNotNull(held(), "$how: the control requires b to be resolving")
        assertEquals(1, f.controller.state.value.currentIndex, "$how: the control requires the queue on b")
        assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId }, "$how: the control requires the engine to hold a")
        return pending
    }

    /**
     * Once the core has moved past the engine's attempt -- a skip past its failure, or a natural end
     * -- nothing seeks or restarts that attempt while the next entry resolves: restart is not
     * offered, a seek from the app or the media session reaches nothing, and Previous moves to the
     * entry before instead of seeking a track that is over.
     */
    @Test fun whileTheNextEntryResolvesNothingSeeksOrRestartsTheAttemptThatIsOver() {
        for (how in listOf("skip", "natural end")) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                val held = endAWhileBResolves(f, how, if (how == "skip") 4_000 else 39_000) { pending }
                assertFalse(f.controller.state.value.canRestart, "$how: a is over, so there is nothing to restart")
                f.controller.seek(1_000)
                f.controller.sessionPlayer.seekTo(2_000)
                f.controller.sessionPlayer.seekToDefaultPosition()
                assertEquals(emptyList(), f.probe.seekCommands, "$how: no seek may reach the attempt that is over")
                f.controller.skipToPrevious()
                assertEquals(emptyList(), f.probe.seekCommands, "$how: Previous must not seek the attempt that is over")
                assertEquals(0, f.controller.state.value.currentIndex, "$how: Previous moves to the entry before")
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "a"), f.prepared.map { it.itemId.rawId }, "$how: a starts again, and the abandoned b does not")
            }
        }
    }

    /**
     * Pause while the next entry resolves reaches the engine at once, so the media session stops
     * reporting that it is asked to play, and the entry that resolves then starts paused.
     */
    @Test fun pauseWhileTheNextEntryResolvesTellsTheEngineAtOnce() {
        for (how in listOf("skip", "natural end")) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                f.controller.play()
                val held = endAWhileBResolves(f, how, 4_000) { pending }
                assertTrue(f.probe.requested, "$how: the control requires the engine asked to play")
                f.controller.pause()
                assertFalse(f.probe.requested, "$how: Pause must reach the engine while b resolves")
                assertFalse(f.controller.sessionPlayer.playWhenReady, "$how: the media session must not report playing")
                assertFalse(f.controller.state.value.playWhenReady, "$how: the app must show Play")
                assertNull(f.controller.state.value.error, "$how: a Pause the engine refuses is not a failure")
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "$how: the control requires b prepared")
                assertFalse(f.probe.requested, "$how: b starts paused")
            }
        }
    }

    /**
     * After the queue ends, the system's Play -- the notification's, the lock screen's, a headset's
     * -- goes through Media3's play-button handling: on an ended player it seeks to the default
     * position and then plays. Neither may replay the attempt that is over; Play begins a new one
     * through the core.
     */
    @Test fun theSystemsPlayAfterTheQueueEndsBeginsANewAttemptAndReplaysNothing() {
        Fixture().use { f ->
            f.controller.playQueue(album("only"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 39_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            val first = f.controller.state.value.playbackSessionId
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            assertNull(f.controller.state.value.playbackSessionId, "The control requires the queue to have ended")
            // Media3 resumes a session through its media-item commands only when both are offered;
            // with neither, its Play is the play-button handling below and nothing else.
            val commands = f.controller.sessionPlayer.availableCommands
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM))
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS))
            assertTrue(androidx.media3.common.util.Util.shouldShowPlayButton(f.controller.sessionPlayer),
                "The control requires the system to offer Play")
            assertTrue(androidx.media3.common.util.Util.handlePlayButtonAction(f.controller.sessionPlayer),
                "The control requires Media3 to have acted on Play")
            assertEquals(emptyList(), f.probe.seekCommands, "The ended attempt must not be sought back to its start")
            assertEquals(listOf("only", "only"), f.prepared.map { it.itemId.rawId }, "Play begins a new attempt")
            val second = assertNotNull(f.controller.state.value.playbackSessionId, "Play begins a new session")
            assertNotEquals(first, second)
            assertTrue(f.controller.state.value.playWhenReady)
        }
    }

    /**
     * Previous on the first entry of a stream that cannot seek restarts it as a new session
     * (`restartCurrent`, which begins no pass itself). The person pressed Previous, so a new pass
     * begins there as it does wherever Previous moves (spec §12.12 rule 3).
     */
    @Test fun previousRestartingAnUnseekableFirstEntryBeginsANewPass() {
        for (pressesPrevious in listOf(false, true)) Fixture().use { f ->
            f.controller.playQueue(album("a", "b", "c"), 2, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.controller.previous()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            assertEquals(listOf("c", "b", "a"), f.prepared.map { it.itemId.rawId },
                "The control requires b reached by Previous, then skipped backward to a")
            f.probe.seekable = false
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("Progressing", f.controller.state.value.phase, "The control requires a to have progressed")
            assertFalse(f.controller.state.value.seekable, "The control requires a stream that cannot seek")
            if (pressesPrevious) {
                f.controller.skipToPrevious()
                assertEquals(listOf("c", "b", "a", "a"), f.prepared.map { it.itemId.rawId },
                    "The control requires Previous to restart a as a new session")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val state = f.controller.state.value
            if (pressesPrevious) {
                assertEquals("b", f.prepared.last().itemId.rawId, "Previous began a new pass, so the skip may reach b again")
                assertNull(state.error)
            } else {
                assertEquals("a", f.prepared.last().itemId.rawId, "b was skipped past in this pass, so the queue stops on a")
                assertEquals(DomainError.Playback.NoPlayableSource, state.error)
            }
        }
    }

    /**
     * Previous on the first entry of a stream that cannot seek restarts it from the top, as a seek
     * to zero does on one that can: the new session carries no resume position, and the saved one
     * is cleared after the outgoing session's own write, so a relaunch cannot bring it back
     * (spec §15.5). Play after a stop is the restart that resumes; Previous is not.
     */
    @Test fun previousRestartingAnUnseekableFirstEntryClearsItsSavedPosition() {
        Fixture().use { f ->
            // As ExoPlayer does: the engine's stop clears the player's items, after which it is at
            // 0, and the restarted item's paused preparation reports no play-when-ready change.
            // So nothing of the new session writes a position before the assertion below.
            f.probe.onClearMediaItems = { f.probe.position = 0 }
            f.probe.reportsUnchangedPlayWhenReady = false
            val positions = PersistentResumePositionStore(f.store.database)
            val item = ProviderItemId(OWNER, "a")
            f.controller.playQueue(album("a"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.seekable = false
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 10_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("Progressing", f.controller.state.value.phase, "The control requires a to have progressed")
            assertFalse(f.controller.state.value.seekable, "The control requires a stream that cannot seek")
            positions.save(item, 10.seconds)
            assertEquals(10.seconds, positions.restore(item), "The control requires a saved position to clear")

            f.controller.skipToPrevious()
            assertNull(positions.restore(item), "Previous restarts from the top and keeps no saved position")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("a", "a"), f.prepared.map { it.itemId.rawId },
                "The control requires Previous to restart a as a new session")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList(), f.probe.seekCommands, "Nothing seeks the restarted stream")
            assertTrue(positions.restore(item).let { it == null || it == kotlin.time.Duration.ZERO },
                "Only the new session's own position from its start may be saved: ${positions.restore(item)}")
        }
    }

    @Test fun everySystemSeekVerbGoesThroughTheController() {
        Fixture().use { f ->
            f.controller.playQueue(album("a"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 10_000
            val session = f.controller.sessionPlayer
            session.seekForward()                // 10 s + 15 s
            session.seekBack()                   // 25 s - 5 s
            session.seekToDefaultPosition()      // 0
            session.seekTo(3, 7_000)             // index 3 names nothing in a one-item timeline
            session.seekToDefaultPosition(2)
            assertEquals(listOf(25_000L, 20_000L, 0L), f.probe.seekCommands)
        }
    }

    @Test fun restoredTitlesComeFromLocalRowsAndAreNeverRereadPerPublication() {
        val songs = (1..60).map { "s$it" }
        val loaded = mutableListOf<String>()
        Fixture(savedOwner = OWNER, savedSongs = songs, loadSong = { id -> loaded += id; song(id) }).use { f ->
            val afterRestore = loaded.size
            assertTrue(afterRestore in 2..26, "Restoration reads a bounded window, not the queue: $afterRestore")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)); f.probe.events() }
            assertEquals(afterRestore, loaded.size, "Publications must not issue further metadata requests")
        }
        loaded.clear()
        Fixture(savedOwner = OWNER, savedSongs = songs, loadSong = { id -> loaded += id; song(id) }).use { f ->
            val before = loaded.size
            f.controller.rememberTracks(songs.map { AndroidTrack(OWNER, it, "Local $it") })
            f.controller.next()
            assertEquals("Local s2", f.controller.state.value.queue[1].track.title)
            assertTrue(loaded.size - before <= 1, "Titles already held locally must not be requested again")
        }
    }

    @Test fun productionPlayerConsumesAuthenticatedValidatedBytes() {
        val audio = pcmWave()
        PlaybackResourceReceiver(audio).use { receiver ->
            Fixture(realPlayer = true, baseUrl = receiver.url, resolve = { resolved(it) }).use { f ->
                assertEquals(0, f.controller.sessionPlayer.mediaItemCount)
                f.controller.playSong(OWNER, "source-song", "Source song")
                val player = f.controller.sessionPlayer
                assertEquals(1, player.mediaItemCount)
                val item = player.getMediaItemAt(0)
                assertEquals(f.controller.state.value.attemptId, item.mediaId)
                assertEquals("Source song", item.mediaMetadata.title.toString())
                assertEquals("dulcet://resource", item.localConfiguration?.uri.toString())
                assertTrue(player.playWhenReady)
                awaitPlayer { f.controller.state.value.validatedBytesConsumed >= audio.size }
                assertTrue(f.controller.state.value.validatedBytesConsumed >= audio.size,
                    "The real player must consume the HTTP audio through the validating source and its byte counter")
                receiver.assertAuthenticatedSong("source-song")
                println("PRODUCTION SOURCE OBSERVED authenticated-http=true validated-bytes=${f.controller.state.value.validatedBytesConsumed} fixture-bytes=${audio.size} decoder-progression-not-claimed=true")
            }
        }
    }

    @Test fun productionPlayerRejectsAnHttpEnvelopeBeforeConsumingMediaBytes() {
        val envelope = "<subsonic-response><error code=\"40\"/></subsonic-response>".toByteArray()
        PlaybackResourceReceiver(envelope).use { receiver ->
            Fixture(realPlayer = true, baseUrl = receiver.url, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "rejected-song", "Rejected song")
                awaitPlayer { f.controller.state.value.error != null }
                receiver.assertAuthenticatedSong("rejected-song")
                assertEquals(DomainError.Auth.InvalidCredentials, f.controller.state.value.error,
                    "The production player must surface the validator's envelope error")
                assertEquals(0L, f.controller.state.value.validatedBytesConsumed)
            }
        }
    }

    private fun awaitPlayer(done: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(10)
        }
    }

    private fun pcmWave(): ByteArray = java.nio.ByteBuffer.allocate(44 + 16000)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(8000)
            putShort(1); putShort(8); put("data".toByteArray()); putInt(16000)
            put(ByteArray(16000) { 128.toByte() })
        }.array()

    /** Supplies only the receiving socket. The controller builds its real HTTP resource and validator. */
    private class PlaybackResourceReceiver(private val body: ByteArray,
        private val beforeHeaders: () -> Unit = {}, private val beforeBody: () -> Unit = {},
        private val allowClientDisconnect: Boolean = false) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val requests = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val line = assertNotNull(reader.readLine()).split(' ')
                            val uri = java.net.URI(line[1])
                            assertEquals("GET", line[0])
                            assertEquals("/rest/stream.view", uri.path)
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val query = uri.rawQuery.split('&').associate {
                                val pair = it.split('=', limit = 2)
                                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
                            }
                            requests += query
                            val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter("bytes=")
                            val start = range?.substringBefore('-')?.toInt() ?: 0
                            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                            val bytes = body.copyOfRange(start, end + 1)
                            val response = if (range == null) "200 OK" else "206 Partial Content"
                            val rangeHeader = if (range == null) "" else "Content-Range: bytes $start-$end/${body.size}\r\n"
                            beforeHeaders()
                            client.getOutputStream().apply {
                                write(("HTTP/1.1 $response\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n$rangeHeader" +
                                    "Connection: close\r\n\r\n").toByteArray())
                                flush(); beforeBody()
                                write(bytes); flush()
                            }
                        }
                    } catch (error: Throwable) {
                        if (!socket.isClosed && !(allowClientDisconnect && error is java.io.IOException)) failures += error
                    }
                }
            }
        }
        fun requestCount() = requests.size
        fun assertAuthenticatedSong(id: String) {
            assertTrue(requests.isNotEmpty(), "The production source must reach the receiving HTTP socket")
            for (query in requests) {
                assertEquals(id, query["id"])
                assertEquals("controller-canary", query["u"])
                val salt = assertNotNull(query["s"])
                assertEquals(AccountConnectionContract.saltedToken("controller-password-canary", salt), query["t"])
            }
        }
        override fun close() {
            socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Playback receiving fixture failed: $failures")
        }
    }

    private class AutoplayReceiver : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val seedEntered = java.util.concurrent.CountDownLatch(1)
        val releaseSeed = java.util.concurrent.CountDownLatch(1)
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try { socket.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader()
                        val line = checkNotNull(reader.readLine()).split(' ')
                        val path = java.net.URI(line[1]).path
                        while (!reader.readLine().isNullOrEmpty()) { }
                        paths += path
                        val payload = if (path == "/rest/getSong.view") {
                            seedEntered.countDown(); check(releaseSeed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            """"song":{"id":"seed","artistId":"replacement-artist","artist":"Replacement","genre":"Replacement"}"""
                        } else """"${when { path.contains("SimilarSongs2") -> "similarSongs2"; path.contains("SimilarSongs") -> "similarSongs"; path.contains("TopSongs") -> "topSongs"; else -> "randomSongs" }}":{"song":[]}"""
                        val bytes = """{"subsonic-response":{"status":"ok",$payload}}""".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    } } catch (failure: Throwable) { if (!socket.isClosed) failures += failure }
                }
            }
        }
        override fun close() {
            releaseSeed.countDown(); socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Autoplay receiving fixture failed: $failures")
        }
    }

    /** The person's choice to connect a saved account (spec §13.1), as the app's screens make it. */
    private class ChosenContact : AndroidServerContact {
        val open = kotlinx.coroutines.flow.MutableStateFlow(false)
        override fun isOpen(): Boolean = open.value
        override suspend fun awaitOpen() { open.first { it } }
    }

    /** Virtual time on both clocks the controller reads, and real time for the sockets. */
    private fun settle(seconds: Int = 60) {
        repeat(seconds / 5) {
            mainScheduler.advanceTimeBy(5_000)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5_000))
            Thread.sleep(10)
        }
    }

    /**
     * A relaunch into a saved account the person has not connected in this process (spec §13.1,
     * CONF-10b): the queue the last run left, whose current song is not downloaded, and a play the last
     * run left unsent. Nothing reaches the server -- no song read, no resolve, no scrobble -- for a
     * minute of the controller's own retry clock, nor when the person presses Play, which says the
     * song needs Reconnect instead. Once the person connects, the waiting play is delivered and the
     * restored entry is read, resolved and prepared, still paused. The scrobble server is a real socket.
     */
    @Test fun aSavedAccountNotYetConnectedSendsNothingUntilTheyConnectAndThenDrainsAndPreparesPaused() {
        ScrobbleReceiver().use { receiver ->
            val loaded = java.util.concurrent.CopyOnWriteArrayList<String>()
            val resolvedIds = java.util.concurrent.CopyOnWriteArrayList<String>()
            val contact = ChosenContact()
            Fixture(savedOwner = OWNER, savedSongs = listOf("saved-song", "next-song"), baseUrl = receiver.url,
                onDelivery = null, loadSong = { id -> loaded += id; song(id) },
                resolve = { request -> resolvedIds += request.itemId.rawId; resolved(request) },
                pendingPlays = listOf("played-before"), serverContact = contact).use { f ->
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                settle()
                val restored = f.controller.state.value
                assertEquals(listOf("saved-song", "next-song"), restored.queue.map { it.track.rawId },
                    "the restored queue shows from the device")
                assertEquals(0, restored.currentIndex)
                assertFalse(restored.playWhenReady, "restored paused")
                assertFalse(restored.needsReconnect, "nothing was asked of it yet")
                assertEquals(1, outbox.pending(ServerId(OWNER)).size, "setup: a play waits in the outbox")
                fun tried() = loaded.size + resolvedIds.size + receiver.requests.size
                assertEquals(0, tried(), "nothing is sent before the person connects: $loaded $resolvedIds ${receiver.requests}")
                assertTrue(f.prepared.isEmpty() && f.preparedLocal.isEmpty())

                f.controller.play()
                settle(20)
                assertTrue(f.controller.state.value.needsReconnect, "Play says the song needs Reconnect")
                assertFalse(f.controller.state.value.playWhenReady)
                assertFalse(f.probe.requested, "and the player was never asked to play")
                f.controller.next()
                settle(20)
                assertEquals(0, tried(), "Play and Next send nothing either")

                contact.open.value = true
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while ((receiver.requests.none { it["submission"] == "true" } || outbox.pending(ServerId(OWNER)).isNotEmpty() ||
                        f.prepared.isEmpty()) && System.nanoTime() < deadline) settle(5)
                val submitted = receiver.requests.filter { it["submission"] == "true" }
                assertEquals(listOf("played-before"), submitted.map { it["id"] }, "the waiting play is delivered once connected")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty())
                assertEquals(listOf("next-song"), f.prepared.map { it.itemId.rawId }, "the selected entry is prepared once connected")
                assertTrue("next-song" in loaded && "next-song" in resolvedIds)
                assertFalse(f.probe.requested, "still paused: connecting asked for no sound")
                assertFalse(f.controller.state.value.needsReconnect)
            }
        }
    }

    @Test fun reconnectPreparesTheRestoredQueueWhileTheRealOutboxRevalidatesEstablishedContact() {
        ScrobbleReceiver().use { receiver ->
            AndroidServerIdentity.requireValidation(OWNER)
            val chosen = kotlinx.coroutines.flow.MutableStateFlow(false)
            val contact = object : AndroidServerContact {
                override fun isOpen() = chosen.value && AndroidServerIdentity.isVerified(OWNER)
                override suspend fun awaitOpen() {
                    kotlinx.coroutines.flow.combine(chosen, AndroidServerIdentity.verified) { selected, verified ->
                        selected && OWNER in verified
                    }.first { it }
                }
                override suspend fun awaitValidation() { AndroidServerIdentity.awaitValidation(OWNER) }
            }
            val loaded = java.util.concurrent.CopyOnWriteArrayList<String>()
            Fixture(savedOwner = OWNER, baseUrl = receiver.url, onDelivery = null,
                pendingPlays = listOf("played-before"), serverContact = contact,
                loadSong = { id -> loaded += id; song(id) }).use { f ->
                settle(5)
                assertTrue(f.prepared.isEmpty())
                assertTrue(loaded.isEmpty())
                kotlinx.coroutines.runBlocking { assertNull(AndroidServerIdentity.validate(OWNER) { null }) }
                chosen.value = true
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while ((f.prepared.isEmpty() || receiver.requests.none { it["submission"] == "true" }) &&
                    System.nanoTime() < deadline) settle(5)
                assertEquals(listOf("played-before"), receiver.requests.filter { it["submission"] == "true" }.map { it["id"] })
                assertEquals(listOf("saved-song"), f.prepared.map { it.itemId.rawId },
                    "The outbox's independent identity probe must not strand the restored queue after Connect")
                assertEquals(setOf("saved-song"), loaded.toSet(), "Metadata and playback resolve only this server's restored entry")
                assertFalse(f.probe.requested, "Reconnecting restores paused playback")
                assertFalse(f.controller.state.value.needsReconnect)
            }
            AndroidServerIdentity.requireValidation(OWNER)
        }
    }

    /** Saved and not connected (spec §13.1), a downloaded song plays from its file, and only it does. */
    @Test fun aSavedAccountNotYetConnectedPlaysADownloadAndSaysTheNextSongNeedsReconnect() {
        val songReads = mutableListOf<String>()
        val file = java.io.File.createTempFile("dulcet-local", ".wav")
        try {
            Fixture(loadSong = { id -> songReads += id; song(id) }, serverContact = ChosenContact(), localPlans = { rawId ->
                if (rawId != "down") null else LocalPlaybackPlan(DownloadId("download:down"),
                    DownloadIdentity(OWNER, "down", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, file.path)
            }).use { f ->
                f.controller.playQueue(album("net"), 0, AndroidQueueSource.Album, "Album", "album-id")
                assertTrue(f.controller.state.value.needsReconnect, "a song that needs the server says so")
                assertTrue(f.controller.state.value.queue.isEmpty(), "and replaces nothing")
                f.controller.playQueue(album("down", "net"), 0, AndroidQueueSource.Album, "Album", "album-id")
                assertEquals(listOf("down"), f.preparedLocal.map { it.itemId.rawId })
                assertTrue(f.probe.requested, "the download plays")
                assertFalse(f.controller.state.value.needsReconnect)
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.state = androidx.media3.common.Player.STATE_ENDED
                f.probe.events()
                assertEquals(1, f.controller.state.value.currentIndex, "the queue moves on to the next song")
                assertTrue(f.prepared.isEmpty(), "which is not resolved")
                assertTrue(songReads.isEmpty(), "nor read: $songReads")
                assertTrue(f.controller.state.value.needsReconnect, "and says it needs Reconnect")
            }
        } finally {
            file.delete()
        }
    }

    @Test fun progressionSendsSubmittedScrobbleThroughTheLiveConsumerWorkerAndSender() {
        ScrobbleReceiver().use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "live-song", "Live song")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                repeat(46) {
                    f.probe.position += 500
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                }
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while ((receiver.requests.none { it["submission"] == "true" } || outbox.pending(ServerId(OWNER)).isNotEmpty()) &&
                    System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val submitted = receiver.requests.filter { it["submission"] == "true" }
                assertEquals(1, submitted.size, "The receiving socket must observe a submitted scrobble")
                assertEquals("live-song", submitted.single()["id"])
                assertTrue(assertNotNull(submitted.single()["time"]).toLong() > 0)
                assertTrue(receiver.requests.any { it["submission"] == "false" && it["id"] == "live-song" },
                    "The same live consumer must send now-playing")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty(), "A successful server response must acknowledge the outbox row")
            }
        }
    }

    /**
     * A play the server refuses while the service stays open is sent again with no new play, no
     * foreground and no reachability event to prompt it (spec §15.3). The server here is a real
     * socket that answers the first submission with 503; the controller's own consumer, worker and
     * sender run unreplaced. Time is virtual on both clocks the retry reads: the main dispatcher's
     * scheduler (the timer) and the looper's (`SystemClock`, the worker's gate).
     */
    @Test fun aSubmittedPlayTheServerRefusedIsRetriedWhileTheServiceStaysOpen() {
        ScrobbleReceiver(refuseFirstSubmissions = 1).use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "retry-song", "Retry song")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                repeat(46) {
                    f.probe.position += 500
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                }
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                // Real sockets answer on real time; the retry waits on virtual time, so advance it
                // a tenth of a second per turn until the server has seen the second submission.
                while (receiver.requests.count { it["submission"] == "true" } < 2 && System.nanoTime() < deadline) {
                    mainScheduler.advanceTimeBy(100)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                    Thread.sleep(10)
                }
                while (outbox.pending(ServerId(OWNER)).isNotEmpty() && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val submitted = receiver.requests.filter { it["submission"] == "true" }
                assertEquals(2, submitted.size, "The refused submission is sent again, once")
                assertEquals(submitted[0]["time"], submitted[1]["time"], "The retry is the same play")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty(), "The retry's acknowledgement clears the outbox row")
            }
        }
    }

    /**
     * A play the server says is gone (error 70) is refused at once, a minute later and five minutes
     * after that, and then dropped, but only because the server took another play meanwhile (one
     * seeded after the first refusal): the outbox row goes, nothing more is sent for it, and the
     * controller counts it (spec §15.3). Real socket, the controller's own consumer, worker and
     * sender; virtual time on both clocks the retry reads, as above.
     */
    @Test fun aSubmittedPlayTheServerSaysIsGoneIsDroppedAfterThreeRefusalsWhileAnotherGetsThrough() {
        ScrobbleReceiver(goneId = "gone-song").use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "gone-song", "Gone song")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                repeat(46) {
                    f.probe.position += 500
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                }
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                fun goneSubmissions() = receiver.requests.count { it["submission"] == "true" && it["id"] == "gone-song" }
                // Sockets answer on real time and the waits on virtual time: step the virtual clock
                // five seconds a turn (the longest wait is five minutes) until the condition holds.
                fun step() {
                    mainScheduler.advanceTimeBy(5_000)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5_000))
                    Thread.sleep(10)
                }
                while (goneSubmissions() < 1 && System.nanoTime() < deadline) step()
                assertEquals(1, goneSubmissions(), "The first refusal")
                // Another play arrives after it, and the server will take it.
                outbox.persistSynchronously(RecordedPlaybackEvent.SubmittedPlay(
                    ProviderItemId(OWNER, "live-song"), PlaybackWallClockTime(System.currentTimeMillis() + 1)))
                while (f.controller.refusedPlaysDropped == 0L && System.nanoTime() < deadline) step()
                assertEquals(1L, f.controller.refusedPlaysDropped, "The refused play is dropped and counted")
                assertEquals(3, goneSubmissions(), "Refused three times, then no more")
                assertEquals(1, receiver.requests.count { it["submission"] == "true" && it["id"] == "live-song" },
                    "The other play was taken once")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty(), "Neither play is left in the outbox")
                repeat(120) {
                    mainScheduler.advanceTimeBy(30_000)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30_000))
                }
                Thread.sleep(200)
                assertEquals(3, goneSubmissions(), "An hour later nothing has been sent for the dropped play")
            }
        }
    }

    @Test fun identityRejectionDuringPlaylistDiscoveryCannotSendTheRetainedAccountsSongOrPlaylistIds() {
        ScrobbleReceiver(holdExtensions = true).use { receiver ->
            receiver.advertiseContext = true
            val owner = "provider:${java.util.UUID.randomUUID()}"
            Fixture(accountOwner = owner, baseUrl = receiver.url, onDelivery = null,
                pendingPlays = listOf("retained-song"), pendingPlaylistId = "retained-playlist").use { f ->
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (receiver.extensionEntered.count > 0 && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals(0L, receiver.extensionEntered.count, "Setup: the real controller's authenticated discovery is held")
                assertEquals("retained-playlist", outbox.pending(ServerId(owner)).single().playlistRawId)
                kotlinx.coroutines.runBlocking {
                    assertEquals(DomainError.Protocol.ServerIdentityChanged,
                        AndroidServerIdentity.validate(owner) { DomainError.Protocol.ServerIdentityChanged })
                }
                receiver.releaseExtensions.countDown()
                val completed = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (receiver.requests.isEmpty() && outbox.pending(ServerId(owner)).singleOrNull()?.attemptCount == 0L &&
                    System.nanoTime() < completed) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertTrue(receiver.requests.isEmpty(),
                    "Known rejection must fence the actual HTTP scrobble after a held extension answer")
                val retained = outbox.pending(ServerId(owner)).single()
                assertEquals("retained-song", retained.rawId)
                assertEquals("retained-playlist", retained.playlistRawId)
                assertEquals(1, receiver.extensionRequests.get(), "No rejected rediscovery may follow")
            }
        }
    }

    @Test fun networkReconnectRediscoversPositiveAndNegativePlaylistContextWithoutReplacingPlayback() {
        ScrobbleReceiver().use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                receiver.advertiseContext = true
                f.submitPlaylist(receiver, "positive", "playlist-id")
                assertEquals(1, receiver.extensionRequests.get())
                val playing = f.controller.state.value
                receiver.advertiseContext = false
                f.controller.setNetworkAvailable(false)
                f.controller.setNetworkAvailable(true)
                assertEquals(playing.playbackSessionId, f.controller.state.value.playbackSessionId)
                assertEquals(playing.attemptId, f.controller.state.value.attemptId)
                f.submitPlaylist(receiver, "negative", null)
                assertEquals(2, receiver.extensionRequests.get(), "Reconnect must discard a positive advertisement")
                receiver.advertiseContext = true
                // A foreground/revalidated connection can report true without a preceding outage.
                f.controller.setNetworkAvailable(true)
                f.submitPlaylist(receiver, "renewed", "playlist-id")
                assertEquals(3, receiver.extensionRequests.get(), "Reconnect must discard valid absence too")
                f.submitPlaylist(receiver, "cached", "playlist-id")
                assertEquals(3, receiver.extensionRequests.get(), "Ordinary plays still share the connection's answer")
                assertTrue(receiver.requests.filter { it["submission"] == "false" }.all { it["dulcetContextId"] == null })
            }
        }
    }

    /** A server can reconnect while the OS network stays unchanged. Drive the real reader observer. */
    @Test fun readerReconnectRediscoversPlaylistContextWithoutAnOsNetworkChange() {
        ScrobbleReceiver().use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                val reader = AndroidLibraryReader(
                    account = AndroidLibraryReaderAccount(OWNER, receiver.url,
                        "controller-canary", "controller-password-canary", true),
                    compose = { scope, foreground ->
                        AndroidLibraryReaderComposition(LibraryReaderSession(
                            f.store.database,
                            SeenCacheStore(f.store, ManualWallClock(now = 2_000_000)).bind(
                                CacheBinding(OWNER, receiver.url, "controller-canary")),
                            SessionTestServer(), scope,
                            LibraryReaderConfig(lookAheadMaxPerViewport = 0),
                            formPost = false, foreground = foreground))
                    },
                    readerDispatcher = newLibraryReaderDispatcher(),
                    mainDispatcher = Dispatchers.Main.immediate,
                    initiallyForeground = false,
                )
                try {
                    fun reconnect() {
                        val completed = java.util.concurrent.LinkedBlockingQueue<AndroidLibraryConnection>()
                        reader.reconnect { completed.put(it) }
                        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                        while (completed.isEmpty() && System.nanoTime() < deadline) {
                            mainScheduler.runCurrent()
                            shadowOf(Looper.getMainLooper()).idle()
                            Thread.sleep(10)
                        }
                        assertTrue(assertNotNull(completed.poll(), "The real reader reconnect completed").epochRead,
                            "A successful epoch read must drive ReaderConnectionOutcome.Read")
                    }
                    receiver.advertiseContext = true
                    f.submitPlaylist(receiver, "reader-positive", "playlist-id")
                    assertEquals(1, receiver.extensionRequests.get())
                    // Another account's report must not discard this account's cached answer.
                    AndroidPlaybackController.notifyServerAvailable("other-provider", true)
                    f.submitPlaylist(receiver, "reader-foreign", "playlist-id")
                    assertEquals(1, receiver.extensionRequests.get())
                    val playing = f.controller.state.value
                    receiver.advertiseContext = false
                    reconnect() // No OS loss/arrival and no playback availability setter.
                    assertEquals(playing.playbackSessionId, f.controller.state.value.playbackSessionId)
                    assertEquals(playing.attemptId, f.controller.state.value.attemptId)
                    f.submitPlaylist(receiver, "reader-negative", null)
                    assertEquals(2, receiver.extensionRequests.get(), "Reader Read discards cached support")
                    receiver.advertiseContext = true
                    val beforeReconnect = f.controller.state.value
                    reconnect() // Successful revalidation, even without a preceding failure.
                    assertEquals(beforeReconnect.playbackSessionId, f.controller.state.value.playbackSessionId)
                    assertEquals(beforeReconnect.attemptId, f.controller.state.value.attemptId)
                    f.submitPlaylist(receiver, "reader-renewed", "playlist-id")
                    assertEquals(3, receiver.extensionRequests.get(), "Reader Read discards cached absence")
                    f.submitPlaylist(receiver, "reader-cached", "playlist-id")
                    assertEquals(3, receiver.extensionRequests.get(), "Plays without reconnect reuse the answer")
                    assertTrue(receiver.requests.filter { it["submission"] == "false" }
                        .all { it["dulcetContextId"] == null && it["dulcetContextType"] == null })
                } finally {
                    reader.close()
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(30_000) { reader.awaitTermination() } }
                }
            }
        }
    }

    private fun Fixture.submitPlaylist(receiver: ScrobbleReceiver, rawId: String, expectedContext: String?) {
        probe.position = 0
        controller.playQueue(album(rawId), 0, AndroidQueueSource.Playlist, "Mix", "playlist-id")
        probe.state = androidx.media3.common.Player.STATE_READY
        probe.events()
        repeat(46) {
            probe.position += 500
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        }
        val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { System.currentTimeMillis() })
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while ((receiver.requests.none { it["id"] == rawId && it["submission"] == "true" } ||
                outbox.pending(ServerId(OWNER)).isNotEmpty()) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        val sent = receiver.requests.single { it["id"] == rawId && it["submission"] == "true" }
        assertEquals(expectedContext, sent["dulcetContextId"])
        assertEquals(if (expectedContext == null) null else "playlist", sent["dulcetContextType"])
        assertTrue(outbox.pending(ServerId(OWNER)).isEmpty())
    }

    /** Receives real HTTP from the production sender. No controller handoff or sender is replaced. */
    private class ScrobbleReceiver(
        private val refuseFirstSubmissions: Int = 0,
        private val goneId: String? = null,
        private val holdExtensions: Boolean = false,
    ) : AutoCloseable {
        val extensionEntered = java.util.concurrent.CountDownLatch(1)
        val releaseExtensions = java.util.concurrent.CountDownLatch(1)
        @Volatile var advertiseContext = false
        val extensionRequests = java.util.concurrent.atomic.AtomicInteger()
        private val refused = java.util.concurrent.atomic.AtomicInteger()
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val line = assertNotNull(reader.readLine()).split(' ')
                            val uri = java.net.URI(line[1])
                            assertEquals("GET", line[0])
                            val identityProbe = uri.path == "/rest/ping.view"
                            val extensions = uri.path == "/rest/getOpenSubsonicExtensions.view"
                            assertTrue(identityProbe || extensions || uri.path == "/rest/scrobble.view")
                            while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                            val query = uri.rawQuery.split('&').associate {
                                val pair = it.split('=', limit = 2)
                                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
                            }
                            assertEquals("controller-canary", query["u"])
                            val salt = assertNotNull(query["s"])
                            assertEquals(AccountConnectionContract.saltedToken("controller-password-canary", salt), query["t"])
                            if (extensions) {
                                extensionRequests.incrementAndGet()
                                extensionEntered.countDown()
                                if (holdExtensions) assertTrue(releaseExtensions.await(30, java.util.concurrent.TimeUnit.SECONDS),
                                    "The held extension fixture must be released")
                            } else if (!identityProbe) requests += query
                            val refuse = !identityProbe && !extensions && query["submission"] == "true" && refused.getAndIncrement() < refuseFirstSubmissions
                            val gone = !identityProbe && !extensions && query["submission"] == "true" && query["id"] == goneId
                            val body = (if (identityProbe) """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.60.0"}}"""
                                else if (extensions) """{"subsonic-response":{"status":"ok","openSubsonicExtensions":${if (advertiseContext) """[{"name":"dulcetHome","versions":[1]}]""" else "[]"}}}"""
                                else if (gone) """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Song not found"}}}"""
                                else if (refuse) """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"unavailable"}}}"""
                                else """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").toByteArray()
                            client.getOutputStream().apply {
                                write(("HTTP/1.1 ${if (refuse) "503 Service Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(body); flush()
                            }
                        }
                    } catch (error: Throwable) { if (!socket.isClosed) failures += error }
                }
            }
        }
        override fun close() {
            releaseExtensions.countDown()
            socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Scrobble receiving fixture failed: $failures")
        }
    }

    /** Spec §12.5: the choice and the network reach the next resolve, and nothing already playing restarts. */
    @Test fun theStreamingQualityForTheNetworkReachesTheNextResolveAndNeverRestartsTheCurrentOne() {
        val requests = mutableListOf<PlaybackResolveRequest>()
        Fixture(resolve = { r -> requests += r; resolved(r) }).use { f ->
            f.controller.setStreamingQuality(StreamingQualityPreference(StreamingQuality.Original, StreamingQuality.Kbps128))
            f.controller.setNetworkCostClass(NetworkCostClass.Unmetered)
            f.controller.playSong(OWNER, "wifi", "On Wi-Fi")
            assertEquals(LegacyPlaybackPreference(null, null), requests.single().legacyPreference)
            assertEquals(1_411_200, requests.single().deviceProfile.maxAudioBitrate)

            f.controller.setNetworkCostClass(NetworkCostClass.Metered)
            assertEquals(1, f.prepared.size, "a network change must not restart what is playing")
            assertEquals(1, requests.size, "a network change must not re-resolve what is playing")

            f.controller.playSong(OWNER, "cellular", "On cellular")
            val capped = requests.last()
            assertEquals(LegacyPlaybackPreference(AudioContainer.Mp3, 128, originalWhenItFits = true), capped.legacyPreference)
            assertEquals(128_000, capped.deviceProfile.maxAudioBitrate)
            assertEquals(128_000, capped.deviceProfile.maxTranscodingAudioBitrate)
            assertEquals(listOf("wifi", "cellular"), f.prepared.map { it.itemId.rawId })
        }
    }

    private class Fixture(
        savedOwner: String? = null,
        savedSongs: List<String> = listOf("saved-song"),
        loadSong: suspend (String) -> AuthenticatedEndpointResponse = { song(it) },
        resolve: (suspend (PlaybackResolveRequest) -> PlaybackResolutionResult)? = null,
        onDelivery: ((Fixture, RecordedPlaybackEvent) -> Unit)? = { _, _ -> },
        realPlayer: Boolean = false,
        baseUrl: String = "http://127.0.0.1:4533",
        localPlans: AndroidLocalPlaybackSource? = null,
        /** Plays left in the outbox by an earlier run, by raw id, before the controller exists. */
        pendingPlays: List<String> = emptyList(),
        pendingPlaylistId: String? = null,
        serverContact: AndroidServerContact? = null,
        overlapPlayer: (() -> androidx.media3.common.Player)? = null,
        accountOwner: String = OWNER,
        candidates: (suspend (String, Set<String>, Int) -> List<AndroidTrack>)? = { _, _, _ -> emptyList() },
    ) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val databaseName = "playback-controller-${java.util.UUID.randomUUID()}.db"
        // JDBC's process-wide DriverManager retains sandbox-loaded drivers, which are invisible
        // to ordinary host tests. Use the production Android driver in this Android sandbox.
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        val probe = PlayerProbe()
        val prepared = mutableListOf<RemotePlaybackWirePlan>()
        val preparedLocal = mutableListOf<AndroidLocalPlaybackPlan>()
        val controller: AndroidPlaybackController
        init {
            if (savedOwner != null) {
                PlaybackQueueController(PersistentQueueStore(store.database), PersistentResumePositionStore(store.database),
                    PlaybackIdentitySource { "$it:${java.util.UUID.randomUUID()}" }).replaceAndStart(PlaybackQueueRequest(
                    savedSongs.map { PlaybackQueueItem(ProviderItemId(savedOwner, it), 40.seconds) },
                    QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false))
            }
            val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { System.currentTimeMillis() })
            pendingPlays.forEachIndexed { index, rawId ->
                outbox.persistSynchronously(RecordedPlaybackEvent.SubmittedPlay(
                    ProviderItemId(accountOwner, rawId), PlaybackWallClockTime(System.currentTimeMillis() - 60_000 + index),
                    pendingPlaylistId?.let { ProviderItemId(accountOwner, it) }))
            }
            controller = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
                PlaybackEndpointAccount(accountOwner, baseUrl, "controller-canary", "controller-password-canary", true),
                AndroidPlaybackControllerBoundaries(store, if (realPlayer) null else probe.player,
                    if (realPlayer) null else { plan -> prepared += plan }, loadSong, resolve,
                    onDelivery?.let { callback -> { event -> callback(this, event) } },
                    localPlans = localPlans,
                    prepareLocalSource = if (realPlayer) null else { plan -> preparedLocal += plan },
                    overlapPlayer = overlapPlayer, autoplayCandidates = candidates),
                serverContact = serverContact)
        }
        override fun close() {
            try {
                controller.close()
                assertNull(probe.listener, "Controller shutdown must detach the Media3 listener")
                controller.close() // Service teardown may close the owner more than once.
            } finally {
                store.close()
                assertTrue(context.deleteDatabase(databaseName), "The fixture database must be deleted")
                assertFalse(context.getDatabasePath(databaseName).exists())
            }
        }
    }

    companion object {
        private const val OWNER = "provider:owner"
        private fun album(vararg ids: String) = ids.map {
            AndroidTrack(OWNER, it, "Title $it", "Artist", "Album", 40_000, "cover-$it")
        }
        private fun resolved(r: PlaybackResolveRequest) = PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(
            r.playbackSessionId, r.attemptId, r.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive,
            r.sourceContainer, PlaybackWireTranscodeDecision.LegacyHint(null, null), endpoint = "stream",
            parameters = mapOf("id" to r.itemId.rawId), resolutionRequest = r))
        private fun missingSong() = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"data not found"}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
        private fun song(id: String, suffix: String = "wav") = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","suffix":"$suffix","duration":40}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
