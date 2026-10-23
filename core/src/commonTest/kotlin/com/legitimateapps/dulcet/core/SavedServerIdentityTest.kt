package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SavedServerIdentityTest {
    @Test fun canonicalConfirmationRetainsTheSavedPrincipalWithoutCaseFolding() {
        val navidrome = ServerIdentityPolicy.identity("Navidrome")
        assertTrue(SavedServerPolicy.canContinue("https://MUSIC.example:443/", "alice", navidrome,
            "https://music.example", "alice", navidrome))
        assertFalse(SavedServerPolicy.canContinue("https://music.example", "alice", navidrome,
            "https://music.example", "Alice", navidrome), "absent canonical confirmation retains exact typed case")
        assertTrue(SavedServerPolicy.samePrincipal("music.example/rest/ping.view", "alice",
            "https://music.example", "alice"), "legacy saved input compares through the connection URL parser")
        assertFalse(SavedServerPolicy.samePrincipal("http://music.example", "alice",
            "https://music.example", "alice"), "an explicit scheme remains binding")
        assertFalse(SavedServerPolicy.samePrincipal("https://music.example/a%2Fb", "alice",
            "https://music.example/a/b", "alice"), "encoded separators remain distinct")
        assertFalse(SavedServerPolicy.samePrincipal("https://music.example", "alice", "https://music.example/other", "alice"))
    }

    @Test fun differentProductsAndChangedDulcetHistoriesAreReplacementsInBothDirections() {
        val navidrome = ServerIdentityPolicy.identity("Navidrome")
        val dulcet = ServerIdentityPolicy.identity("dulcet", "history-1")
        assertTrue(ServerIdentityPolicy.isReplacement(navidrome, dulcet))
        assertTrue(ServerIdentityPolicy.isReplacement(dulcet, navidrome))
        assertTrue(ServerIdentityPolicy.isReplacement(dulcet, ServerIdentityPolicy.identity("dulcet", "history-2")))
        assertFalse(ServerIdentityPolicy.isReplacement(dulcet, dulcet))
        assertFalse(ServerIdentityPolicy.isReplacement("", dulcet), "legacy saved credentials learn identity")
        assertFalse(ServerIdentityPolicy.isReplacement(ServerIdentityPolicy.identity(""), navidrome), "an older server gaining a product field is bootstrap, not proof of replacement")
        assertTrue(ServerIdentityPolicy.isReplacement(dulcet, ServerIdentityPolicy.identity("dulcet")), "lineage cannot silently downgrade")
    }

    @Test fun readerIdentityFailurePrecedesAllWritesAndRetainsTheOldOfflineNamespace() = readerTest { env ->
        val events = mutableListOf<String>()
        val reader = LibraryReader(env.store.bind(ReaderEnv.BINDING), env.server, backgroundScope,
            outboxes = ReconnectOutboxes { events += "flush" },
            validateServerIdentity = { events += "identity"; DomainError.Protocol.ServerIdentityChanged })
        reader.setOnline(false)
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Protocol.ServerIdentityChanged), reader.reconnect())
        assertEquals(listOf("identity"), events)
        assertTrue(env.server.log.isEmpty(), "no epoch read or queued write may reach a replacement")
        assertFalse(reader.online)
    }

    @Test fun rejectedReachableReaderKeepsFavouritePendingWithoutSending() = sessionTest { env ->
        var identityFailure: DomainError? = null
        val session = LibraryReaderSession(env.database.database, env.cache(), env.server, env.scope,
            formPost = false, foreground = false, validateServerIdentity = { identityFailure })
        session.reader.connect()
        identityFailure = DomainError.Protocol.ServerIdentityChanged
        assertEquals(ReaderConnectionOutcome.Failed(identityFailure), session.reader.reconnect())
        assertTrue(session.reader.reachable, "the platform still reports the saved hostname reachable")
        val before = env.server.log.size
        assertEquals(MutationRecord.Pending, session.favourites.setFavourite(
            LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true))
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.log.drop(before), "rejection must block every later outbox request")
        assertEquals(1L, session.favourites.pendingCount(), "the old account's change stays pending")
        identityFailure = null // the saved identity authenticates again, without replacement consent
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(1, env.server.log.drop(before).count { it.endpoint == "star" })
        assertEquals(0L, session.favourites.pendingCount(), "accepted negotiation can resume the retained outbox")
    }

    @Test fun rejectedReaderDiscardsAnUnpagedReplyBeforeItCanEnterTheOldCache() = readerTest { env ->
        var identityFailure: DomainError? = null
        val cache = env.store.bind(ReaderEnv.BINDING)
        val reader = env.reader(validateServerIdentity = { identityFailure })
        reader.connect()
        env.server.artists = listOf("replacement-only" to "Replacement Artist")
        env.server.holdMatching = { it.endpoint == "getArtists" }
        val publications = Publications(env.server)
        val handle = reader.open(LibraryQuery.Artists(), publications)
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "the unpaged reply is in flight")
        identityFailure = DomainError.Protocol.ServerIdentityChanged
        assertEquals(ReaderConnectionOutcome.Failed(identityFailure), reader.reconnect())
        env.server.release()
        advanceUntilIdle()
        assertNull(cache.artist("replacement-only"), "late replacement rows must never enter A's cache")
        assertTrue(publications.last.items.none { it.rawId == "replacement-only" })
        handle.close()
    }

    @Test fun aValidatedReconnectCannotAuthorizeAReplyStartedBeforeRejection() = readerTest { env ->
        var identityFailure: DomainError? = null
        val reader = env.reader(validateServerIdentity = { identityFailure })
        reader.connect()
        env.server.holdMatching = { it.endpoint == "getArtists" }
        val held = async { runCatching { reader.sendChecked("getArtists") } }
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount)
        identityFailure = DomainError.Protocol.ServerIdentityChanged
        assertIs<ReaderConnectionOutcome.Failed>(reader.reconnect())
        identityFailure = null
        assertIs<ReaderConnectionOutcome.Read>(reader.reconnect())
        assertTrue(reader.online, "the saved identity is accepted again")
        env.server.release()
        assertIs<ReaderSendRefused>(held.await().exceptionOrNull(),
            "reaccepting the account cannot revive a response from the rejected generation")
    }

    @Test fun aLateFavouriteAcknowledgementAfterRejectionKeepsTheMutationPending() = sessionTest { env ->
        var identityFailure: DomainError? = null
        val session = LibraryReaderSession(env.database.database, env.cache(), env.server, env.scope,
            formPost = false, foreground = false, validateServerIdentity = { identityFailure })
        session.reader.connect()
        env.server.holdBeforeApply += "star"
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "the star has been sent but not acknowledged")
        identityFailure = DomainError.Protocol.ServerIdentityChanged
        assertIs<ReaderConnectionOutcome.Failed>(session.reader.reconnect())
        env.server.release()
        advanceUntilIdle()
        assertEquals(1L, session.favourites.pendingCount(), "late success cannot acknowledge A's durable mutation")
    }

    @Test fun externalRejectionBlocksReachableOutboxWithoutAReaderReconnect() = sessionTest { env ->
        var authorization: Long? = 0L
        val session = LibraryReaderSession(env.database.database, env.cache(), env.server, env.scope,
            formPost = false, foreground = false, identityAuthorization = { authorization })
        session.reader.connect()
        authorization = null // a download or playback client detected the replacement first
        val before = env.server.log.size
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Album, albumId(4)), true)
        advanceUntilIdle()
        assertFalse(session.reader.canSend)
        assertEquals(emptyList(), env.server.log.drop(before))
        assertEquals(1L, session.favourites.pendingCount())
    }

    @Test fun externalRejectAndAcceptCannotReviveAnOlderUnpagedReply() = readerTest { env ->
        var authorization: Long? = 0L
        val reader = env.reader(identityAuthorization = { authorization })
        reader.connect()
        env.server.holdMatching = { it.endpoint == "getArtists" }
        val held = async { runCatching { reader.sendChecked("getArtists") } }
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount)
        authorization = null
        authorization = 1L // even if the reader never observed the rejected interval
        env.server.release()
        assertIs<ReaderSendRefused>(held.await().exceptionOrNull())
    }

    @Test fun externalRejectAndAcceptCannotAcknowledgeAnOlderSubmittedPlay() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { 100 })
            val play = RecordedPlaybackEvent.SubmittedPlay(ProviderItemId("old-server", "same-opaque-id"), PlaybackWallClockTime(50))
            outbox.persistForAtLeastOnceDelivery(play)
            var authorization: Long? = 0L
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sender = ScrobbleEndpointSender(ScrobbleEndpointTransport {
                entered.complete(Unit)
                release.await()
                AuthenticatedEndpointResponse(200, """{"subsonic-response":{"status":"ok"}}""".encodeToByteArray(),
                    "http://fixture.invalid/rest/scrobble?<redacted>", AuthenticatedEndpointResponseHeaders(null, null, null, null, null),
                    RequestTrace.observed("scrobble", "GET", "http://fixture.invalid/rest/scrobble?<redacted>",
                        AuthenticationLocation.Query, emptySet(), emptySet(), emptySet(), "1.16.1", "fixture"))
            }, identityAuthorization = { authorization })
            val worker = ScrobbleOutboxDeliveryWorker(ServerId("old-server"), outbox, sender,
                OutboxWallClock { 100 }, OutboxMonotonicClock { kotlin.time.Duration.ZERO }, ScrobbleOutboxDiagnosticSink {})
            val drain = async { worker.onForeground() }
            entered.await()
            authorization = null
            authorization = 1L
            release.complete(Unit)
            assertEquals(0, drain.await().deliveredCount)
            assertEquals(play, outbox.pending(ServerId("old-server")).single().toSubmittedPlay())
        } finally { store.close() }
    }

    @Test fun scrobbleReplyAcrossRejectionRetainsTheDurablePlay() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { 100 })
            val play = RecordedPlaybackEvent.SubmittedPlay(ProviderItemId("old-server", "same-opaque-id"), PlaybackWallClockTime(50))
            outbox.persistForAtLeastOnceDelivery(play)
            var rejected = false
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sender = ScrobbleEndpointSender(ScrobbleEndpointTransport {
                entered.complete(Unit)
                release.await()
                AuthenticatedEndpointResponse(200, """{"subsonic-response":{"status":"ok"}}""".encodeToByteArray(),
                    "http://fixture.invalid/rest/scrobble?<redacted>", AuthenticatedEndpointResponseHeaders(null, null, null, null, null),
                    RequestTrace.observed("scrobble", "GET", "http://fixture.invalid/rest/scrobble?<redacted>",
                        AuthenticationLocation.Query, emptySet(), emptySet(), emptySet(), "1.16.1", "fixture"))
            }, authorizeRequest = { if (rejected) DomainError.Protocol.ServerIdentityChanged else null })
            val worker = ScrobbleOutboxDeliveryWorker(ServerId("old-server"), outbox, sender,
                OutboxWallClock { 100 }, OutboxMonotonicClock { kotlin.time.Duration.ZERO }, ScrobbleOutboxDiagnosticSink {})
            val drain = async { worker.onForeground() }
            entered.await()
            rejected = true
            release.complete(Unit)
            assertEquals(0, drain.await().deliveredCount, "a reply across rejection is not an acknowledgement")
            assertEquals(play, outbox.pending(ServerId("old-server")).single().toSubmittedPlay(),
                "the exact durable play must survive a late successful reply")
        } finally { store.close() }
    }

    @Test fun backgroundScrobbleDrainChecksIdentityBeforeAnyAttemptAndNeverDropsOldIds() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val outbox = PersistentScrobbleOutbox(store.database, OutboxWallClock { 100 })
            val play = RecordedPlaybackEvent.SubmittedPlay(ProviderItemId("old-server", "same-opaque-id"), PlaybackWallClockTime(50))
            outbox.persistForAtLeastOnceDelivery(play)
            var requests = 0
            val worker = ScrobbleOutboxDeliveryWorker(ServerId("old-server"), outbox,
                ScrobbleEndpointSender(ScrobbleEndpointTransport { requests++; error("must not send old ids") }),
                OutboxWallClock { 100 }, OutboxMonotonicClock { kotlin.time.Duration.ZERO },
                ScrobbleOutboxDiagnosticSink {},
                validateServerIdentity = { DomainError.Protocol.ServerIdentityChanged })
            assertEquals(0, worker.onForeground().attemptedCount)
            assertEquals(0, worker.onReachable().attemptedCount)
            assertEquals(0, requests)
            assertEquals(0, outbox.pending(ServerId("old-server")).single().attemptCount)
        } finally { store.close() }
    }

    @Test fun acceptedIdentitySurvivesDisposableCacheRebindingAndRequiresExplicitSwapConsent() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val database = store.database
            val identities = ServerIdentityStore(database)
            val old = ServerIdentityPolicy.identity("Navidrome")
            identities.accept("server", old)
            val cache = SeenCacheStore(store, SeenCacheWallClock { 1 })
            cache.bind(CacheBinding("server", "https://music.example", "alice"))
            cache.bind(CacheBinding("server", "https://music.example", "Alice"))
            assertEquals(old, identities.read("server"), "cache reset cannot erase trusted lineage")
            val account = ConnectedAccount("https://music.example", false, "1.16.1", true, "dulcet", "1",
                CapabilitySet(emptyMap(), UserPermissions(false, false, false, false, false), false), emptyList(),
                serverIdentity = ServerIdentityPolicy.identity("dulcet", "new-history"))
            val gate = ServerIdentityGate(database, "server", AccountConnectionRequest("https://music.example", "alice", "fixture")) {
                AccountConnectionResult.Connected(account)
            }
            assertEquals(DomainError.Protocol.ServerIdentityChanged, gate.validate())
            assertEquals(old, identities.read("server"), "detection cannot accept a swap before consent")
        } finally { store.close() }
    }

    @Test fun disposableSeenCacheDeletionPreservesDurableBindingsAndTheOtherAccount() = runTest {
        val store = DulcetDatabaseStore.open(createTestDriver())
        try {
            val database = store.database
            val depth = database.libraryDepthQueries
            val identities = ServerIdentityStore(database)
            val identity = ServerIdentityPolicy.identity("Navidrome")
            for (server in listOf("selected", "other")) {
                identities.accept(server, identity)
                depth.putEntry(server, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY, "media-$server", 0)
                depth.putEntry(server, "albumMetadata", "album", "metadata-$server", 1)
                depth.putEntry(server, "category", "albums", "preference-$server", 2)
            }

            database.serverDataQueries.deleteSeenCacheForServer("selected")

            assertEquals(identity, identities.read("selected"), "cache deletion cannot authorize a replacement")
            assertEquals("media-selected", depth.selectEntry("selected", MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull())
            assertNull(depth.selectEntry("selected", "albumMetadata", "album").executeAsOneOrNull())
            assertNull(depth.selectEntry("selected", "category", "albums").executeAsOneOrNull())
            assertEquals(2, depth.selectEntries("selected").executeAsList().size, "only the independent lineage bindings survive")
            assertEquals(4, depth.selectEntries("other").executeAsList().size, "another saved account stays intact")
            assertEquals(identity, identities.read("other"))
        } finally { store.close() }
    }
}
