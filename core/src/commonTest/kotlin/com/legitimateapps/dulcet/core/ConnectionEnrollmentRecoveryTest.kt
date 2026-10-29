package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectionEnrollmentRecoveryTest {
    @Test fun freshAcceptedAccountInitializesANonceBeforeItsFirstDirectoryWithoutPreseededMedia() = runTest {
        fixture(fresh = true) { f ->
            assertNull(f.epoch())
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request()))
            assertTrue(f.authenticated.isEmpty(), "The strict fence rejects the missing epoch before credentials")
            assertTrue(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            val epoch = assertNotNull(f.epoch())
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            assertEquals(epoch, f.epoch())
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            assertEquals(epoch, f.epoch(), "Initialization never rotates existing ownership")
        }
    }

    @Test fun freshInitializationRefusesExistingOwnedRowsAndMismatchedIdentityWithoutMutation() = runTest {
        fixture(fresh = true) { f ->
            f.store.database.libraryDepthQueries.putEntry(f.anchor.accountId, "pending.intent", "opaque", "keep", 1)
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor))
            assertNull(f.epoch())
            assertEquals("keep", f.store.database.libraryDepthQueries.selectEntry(f.anchor.accountId, "pending.intent", "opaque").executeAsOne())
            assertFalse(initializeFreshConnectionOwnership(f.store.database, f.anchor.copy(serverIdentity = "foreign")))
        }
    }

    @Test fun verifyAfterHttpAdoptionUsesHttpsAndSelectedRemovalRefreshDoesNotSelectAnAlternate() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.clearReceipts()
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            assertEquals(listOf(f.home), f.authenticated)
            assertEquals(listOf(f.home), f.fetched)
            f.service.remove(f.anchor, "home")
            f.clearReceipts()
            val refreshed = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request())).snapshot
            assertEquals(listOf(f.remote), f.authenticated)
            assertEquals(listOf(f.remote), f.fetched)
            assertNull(refreshed.selectedRouteId, "A refresh is not an automatic recovery selection")
            assertTrue(refreshed.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, refreshed.anchor)
        }
    }

    @Test fun explicitAddAuthenticatesChosenListedRouteAfterAdmissionAndKeepsOtherTombstones() = runTest {
        fixture { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            f.service.remove(f.anchor, "home")
            f.clearReceipts()
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true))).snapshot
            assertEquals(listOf(f.remote, f.home), f.authenticated)
            assertTrue(f.events.indexOf("probe:${f.home}") < f.events.indexOf("auth:${f.home}"))
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
        }
    }

    @Test fun explicitChoiceOfAnAdmittedRemoteRecoversWhenTheSelectedHomeIsUnavailable() = runTest {
        fixture { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            f.failedProbes += f.home
            f.clearReceipts()
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request()))
            assertTrue(f.authenticated.isEmpty(), "Verify does not automatically try remote")
            f.clearReceipts()
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.remote, explicit = true))).snapshot
            assertEquals(listOf(f.remote), f.authenticated)
            assertEquals(listOf(f.remote), f.fetched)
            assertEquals("remote", recovered.selectedRouteId)
            assertEquals(f.anchor, recovered.anchor)
        }
    }

    @Test fun explicitReAddOfKnownRemovedHomeBootstrapsThereWhenRemainingAdmittedRemoteIsUnavailable() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            f.probeFailure = DomainError.Transport.Unreachable
            f.failedProbes += f.remote
            val unknown = "https://unlisted.example.test/music"
            for (request in listOf(f.request(), f.request(unknown, explicit = true))) {
                f.clearReceipts()
                assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(request))
                assertTrue(f.authenticated.isEmpty(), "Verify and unlisted Add cannot send credentials when the admitted source is unavailable")
                assertTrue(f.fetched.isEmpty(), "No authenticated directory is fetched for either refused action")
                assertFalse(f.events.any { it.endsWith(unknown) }, "The unlisted target receives no request")
                assertFalse(f.events.contains("probe:${f.home}"), "Verify cannot automatically recover through the removed home")
                val unchanged = f.service.snapshot(f.anchor)
                assertNull(unchanged.selectedRouteId)
                assertTrue(unchanged.routes.single { it.id == "home" }.removed)
                assertEquals(f.anchor, unchanged.anchor)
                assertEquals(epoch, f.epoch())
            }
            f.clearReceipts()
            val result = f.service.enroll(f.request(f.home, explicit = true))
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(result,
                "An explicit known HTTPS re-add can authenticate its chosen bootstrap when another admitted record is unavailable; result=$result events=${f.events}").snapshot
            assertEquals(listOf(f.home), f.authenticated, "The unavailable remote receives no credentials")
            assertEquals(listOf(f.home), f.fetched, "The authenticated directory is fetched from the explicit historical bootstrap")
            val probeIndex = f.events.indexOf("probe:${f.home}")
            val authIndex = f.events.indexOf("auth:${f.home}")
            assertTrue(probeIndex >= 0 && probeIndex < authIndex,
                "Known historical bootstrap TLS/lineage admission must execute before credentials")
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
            assertEquals(epoch, f.epoch())
        }
    }

    @Test fun explicitKnownBootstrapRecoversWhenAdmittedSourcesAuthenticationTransportIsUnreachable() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            f.connectFailures[f.remote] = DomainError.Transport.Unreachable
            f.clearReceipts()
            val result = f.service.enroll(f.request(f.home, explicit = true))
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(result,
                "A public source probe does not prove its authenticated transport is available; result=$result events=${f.events}").snapshot
            assertEquals(listOf(f.remote, f.home), f.authenticated, "Only one deliberate known-bootstrap recovery follows the source transport failure")
            assertEquals(listOf(f.home), f.fetched)
            val probeIndex = f.events.indexOf("probe:${f.home}")
            val authIndex = f.events.indexOf("auth:${f.home}")
            assertTrue(probeIndex >= 0 && probeIndex < authIndex, "Fallback bootstrap admission precedes its credentials")
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
            assertEquals(epoch, f.epoch())
        }
    }

    @Test fun explicitKnownBootstrapRecoversWhenAdmittedSourcesAuthenticatedDirectoryIsUnreachable() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            f.fetchFailures[f.remote] = DomainError.Transport.Unreachable
            f.clearReceipts()
            val result = f.service.enroll(f.request(f.home, explicit = true))
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(result,
                "A source must serve the authenticated directory, not merely public discovery or sign-in; result=$result events=${f.events}").snapshot
            assertEquals(listOf(f.remote, f.home), f.authenticated)
            assertEquals(listOf(f.remote, f.home), f.fetched, "One known-bootstrap directory attempt follows the admitted source's unavailable directory")
            val probeIndex = f.events.indexOf("probe:${f.home}")
            val authIndex = f.events.indexOf("auth:${f.home}")
            assertTrue(probeIndex >= 0 && probeIndex < authIndex, "Fallback bootstrap admission precedes its credentials")
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
            assertEquals(epoch, f.epoch())
        }
    }

    @Test fun explicitKnownBootstrapHandlesSourceProbeTimeout() = runTest {
        assertAvailabilityRecovery(SourcePhase.Probe, DomainError.Transport.Timeout)
    }

    @Test fun explicitKnownBootstrapHandlesSourceConnectTimeout() = runTest {
        assertAvailabilityRecovery(SourcePhase.Connect, DomainError.Transport.Timeout)
    }

    @Test fun explicitKnownBootstrapHandlesSourceFetchTimeout() = runTest {
        assertAvailabilityRecovery(SourcePhase.Fetch, DomainError.Transport.Timeout)
    }

    @Test fun explicitKnownBootstrapHandlesSourceProbeTlsFailure() = runTest {
        assertAvailabilityRecovery(SourcePhase.Probe, DomainError.Security.TlsUntrusted(TlsTrustFailure.Hostname))
    }

    @Test fun explicitKnownBootstrapHandlesSourceConnectTlsFailure() = runTest {
        assertAvailabilityRecovery(SourcePhase.Connect, DomainError.Security.TlsUntrusted(TlsTrustFailure.Hostname))
    }

    @Test fun explicitKnownBootstrapHandlesSourceFetchTlsFailure() = runTest {
        assertAvailabilityRecovery(SourcePhase.Fetch, DomainError.Security.TlsUntrusted(TlsTrustFailure.Hostname))
    }

    @Test fun sourceIdentityProtocolAuthenticationServerAndCancellationFailuresNeverFallback() = runTest {
        val errors = listOf(DomainError.Protocol.ServerIdentityChanged, DomainError.Protocol.MalformedEnvelope,
            DomainError.Auth.InvalidCredentials, DomainError.Server.Busy(null), DomainError.Server.HttpStatus(503),
            DomainError.Transport.Cancelled, null)
        for (phase in SourcePhase.entries) for (error in errors) fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            if (error == null) f.cancelledPhase = phase else f.failSource(phase, error)
            f.clearReceipts()
            val result = assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request(f.home, explicit = true)))
            assertEquals(error ?: DomainError.Transport.Cancelled, result.error, "Fatal $phase failure cannot be routed around")
            assertEquals(if (phase == SourcePhase.Probe) emptyList() else listOf(f.remote), f.authenticated)
            assertEquals(if (phase == SourcePhase.Fetch) listOf(f.remote) else emptyList(), f.fetched)
            assertFalse(f.events.any { it.endsWith(f.home) }, "Fatal source failure cannot probe or authenticate the chosen target")
            val unchanged = f.service.snapshot(f.anchor)
            assertNull(unchanged.selectedRouteId)
            assertTrue(unchanged.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, unchanged.anchor)
            assertEquals(epoch, f.epoch())
        }
    }

    @Test fun unlistedExplicitAddNeverReceivesCredentialsAndDoesNotChangeSelection() = runTest {
        fixture { f ->
            val before = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request())).snapshot
            f.clearReceipts()
            val unknown = "https://unlisted.example.test/music"
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request(unknown, explicit = true)))
            assertEquals(listOf(f.home), f.authenticated)
            assertFalse(f.events.any { it.endsWith(unknown) })
            assertEquals(before, f.service.snapshot(f.anchor))
        }
    }

    @Test fun listedButFailedTlsOrLineageTargetNeverReceivesAddCredentials() = runTest {
        fixture { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            f.service.remove(f.anchor, "remote")
            val before = f.service.snapshot(f.anchor)
            f.clearReceipts()
            f.failedProbes += f.remote
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request(f.remote, explicit = true)))
            assertEquals(listOf(f.home), f.authenticated)
            assertTrue(f.events.contains("probe:${f.remote}"))
            assertEquals(before, f.service.snapshot(f.anchor))
        }
    }

    @Test fun lastRemovalOnlyExplicitKnownHistoricalHttpsCanRecoverAndOnlyChosenRemovalIsCleared() = runTest {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home"); f.service.remove(f.anchor, "remote")
            f.clearReceipts()
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request()))
            assertIs<ConnectionEnrollmentResult.Failed>(f.service.enroll(f.request("https://unknown.example.test/music", explicit = true)))
            assertTrue(f.authenticated.isEmpty())
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.remote, explicit = true))).snapshot
            assertEquals("probe:${f.remote}", f.events.first())
            assertEquals(listOf(f.remote), f.authenticated)
            assertEquals("remote", recovered.selectedRouteId)
            assertTrue(recovered.routes.single { it.id == "home" }.removed)
            assertFalse(recovered.routes.single { it.id == "remote" }.removed)
            assertEquals(f.anchor, recovered.anchor)
        }
    }

    @Test fun aLaterRemovalCancelsAnEarlierExplicitReAddWithoutRevivingItsTombstone() = runTest {
        fixture { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request()))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            f.clearReceipts()
            f.afterFetch = { f.service.remove(f.anchor, "home") }
            val result = f.service.enroll(f.request(f.home, explicit = true))
            assertEquals(DomainError.Transport.Cancelled, assertIs<ConnectionEnrollmentResult.Failed>(result).error)
            val after = f.service.snapshot(f.anchor)
            assertTrue(after.routes.single { it.id == "home" }.removed)
            assertNull(after.selectedRouteId)
            assertEquals(epoch, f.epoch())
            assertEquals(listOf(f.remote), f.authenticated, "A newer removal also fences target authentication")
        }
    }

    private enum class SourcePhase { Probe, Connect, Fetch }

    private suspend fun assertAvailabilityRecovery(phase: SourcePhase, error: DomainError) {
        fixture(http = true) { f ->
            assertIs<ConnectionEnrollmentResult.Enrolled>(f.service.enroll(f.request(f.home, explicit = true)))
            f.service.remove(f.anchor, "home")
            val epoch = f.epoch()
            f.failSource(phase, error)
            f.clearReceipts()
            val result = f.service.enroll(f.request(f.home, explicit = true))
            val recovered = assertIs<ConnectionEnrollmentResult.Enrolled>(result,
                "Explicit known bootstrap must recover from $phase $error; result=$result events=${f.events}").snapshot
            assertEquals(if (phase == SourcePhase.Probe) listOf(f.home) else listOf(f.remote, f.home), f.authenticated)
            assertEquals(if (phase == SourcePhase.Fetch) listOf(f.remote, f.home) else listOf(f.home), f.fetched)
            val sourceProbe = f.events.indexOf("probe:${f.remote}")
            val chosenProbe = f.events.indexOf("probe:${f.home}")
            val chosenAuth = f.events.indexOf("auth:${f.home}")
            assertTrue(sourceProbe >= 0 && chosenProbe > sourceProbe && chosenAuth > chosenProbe,
                "The chosen HTTPS bootstrap gets its own admission before credentials after the source attempt")
            assertEquals("home", recovered.selectedRouteId)
            assertFalse(recovered.routes.single { it.id == "home" }.removed)
            assertEquals(f.anchor, recovered.anchor)
            assertEquals(epoch, f.epoch())
        }
    }

    private suspend fun fixture(http: Boolean = false, fresh: Boolean = false, block: suspend (Fixture) -> Unit) {
        val store = DulcetDatabaseStore.open(createTestDriver())
        val f = Fixture(store, http)
        try {
            ServerIdentityStore(store.database).accept(f.anchor.accountId, f.anchor.serverIdentity)
            if (!fresh) assertTrue(initializeFreshConnectionOwnership(store.database, f.anchor))
            block(f)
        } finally { ConnectionRouteRegistry.forget(f.anchor.accountId); store.close() }
    }

    private class Fixture(val store: DulcetDatabaseStore, http: Boolean) {
        val home = "https://home.example.test/music"
        val remote = "https://remote.example.test/music"
        val anchor = OwnershipAnchor("recovery-fixture", if (http) "http://legacy.example.test/music" else home,
            "listener", ServerIdentityPolicy.identity("dulcet", "lineage"))
        val authenticated = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val events = mutableListOf<String>()
        var afterFetch: (() -> Unit)? = null
        val failedProbes = mutableSetOf<String>()
        var probeFailure: DomainError = DomainError.Protocol.ServerIdentityChanged
        val connectFailures = mutableMapOf<String, DomainError>()
        val fetchFailures = mutableMapOf<String, DomainError>()
        var cancelledPhase: SourcePhase? = null
        fun failSource(phase: SourcePhase, error: DomainError) {
            when (phase) {
                SourcePhase.Probe -> { failedProbes += remote; probeFailure = error }
                SourcePhase.Connect -> connectFailures[remote] = error
                SourcePhase.Fetch -> fetchFailures[remote] = error
            }
        }
        fun epoch() = store.database.libraryDepthQueries.selectEntry(anchor.accountId, MEDIA_BINDING_CATEGORY, MEDIA_BINDING_KEY).executeAsOneOrNull()
        fun clearReceipts() { authenticated.clear(); fetched.clear(); events.clear() }
        fun request(url: String = anchor.normalizedBaseUrl, explicit: Boolean = false) = ConnectionEnrollmentRequest(anchor,
            AccountConnectionRequest(url, anchor.username, "synthetic", false), explicit)
        val service = ConnectionEnrollmentService(store.database, connect = { request ->
            authenticated += request.serverUrl; events += "auth:${request.serverUrl}"
            if (request.serverUrl == remote && cancelledPhase == SourcePhase.Connect) throw kotlinx.coroutines.CancellationException("fixture")
            connectFailures[request.serverUrl]?.let { return@ConnectionEnrollmentService AccountConnectionResult.Failed(it) }
            AccountConnectionResult.Connected(ConnectedAccount(request.serverUrl, false, "1.16.1", true, "dulcet", "fixture",
                CapabilitySet(mapOf("dulcetRoutes" to setOf(1)), UserPermissions(true, true, false, false, false), false), emptyList(),
                anchor.username, anchor.serverIdentity))
        }, probe = ConnectionCandidateProbe { url, _ ->
            events += "probe:$url"
            if (url == remote && cancelledPhase == SourcePhase.Probe) throw kotlinx.coroutines.CancellationException("fixture")
            if (url in failedProbes) probeFailure else null
        }, fetch = { request ->
            fetched += request.serverUrl
            if (request.serverUrl == remote && cancelledPhase == SourcePhase.Fetch) throw kotlinx.coroutines.CancellationException("fixture")
            fetchFailures[request.serverUrl]?.let { throw AuthenticatedEndpointFailure(it) }
            afterFetch?.invoke()
            val body = """{"subsonic-response":{"status":"ok","version":"1.16.1","dulcetConnections":{"version":1,"revision":1,"instanceId":"lineage","principal":{"id":"user:1","username":"listener"},"routes":[{"id":"home","baseUrl":"$home","role":"private","priority":10},{"id":"remote","baseUrl":"$remote","role":"public","priority":20}],"legacyOwnershipAnchors":["http://legacy.example.test/music"],"authAliases":[]}}}""".encodeToByteArray()
            AuthenticatedEndpointResponse(200, body, "<redacted>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
                RequestTrace.observed("getDulcetConnections", "POST", "<redacted>", AuthenticationLocation.FormBody,
                    emptySet(), emptySet(), emptySet(), "1.16.1", "fixture"))
        })
    }
}
