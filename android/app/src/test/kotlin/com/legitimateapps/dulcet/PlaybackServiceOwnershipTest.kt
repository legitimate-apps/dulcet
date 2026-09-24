package com.legitimateapps.dulcet

import android.content.Intent
import com.legitimateapps.dulcet.playback.PlaybackService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FixtureAccountStore::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"])
class PlaybackServiceOwnershipTest {
    @Test fun localBindingRegistersTheProductionSessionWithoutAControllerConnection() {
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        val service = lifecycle.get()
        try {
            val binder = service.onBind(Intent(PlaybackService.LOCAL_BIND)) as PlaybackService.LocalBinder
            assertSame(service, binder.service)
            val playback = assertNotNull(service.playback)
            assertEquals(1, service.sessions.size, "Local binding must register service ownership")
            assertSame(playback.sessionPlayer, service.sessions.single().player)
            service.onUnbind(Intent(PlaybackService.LOCAL_BIND))
            assertEquals(1, service.sessions.size)
        } finally { lifecycle.destroy() }
        assertTrue(service.sessions.isEmpty())
    }
}

/** Only account storage is replaced; service, controller and Media3 session are production. */
@Implements(AndroidAccountCredentialStore::class)
class FixtureAccountStore {
    @Implementation fun load(): StoredAccount = StoredAccount("service-fixture", "Fixture",
        "http://127.0.0.1:9", "USER_CANARY", "PASSWORD_CANARY", true)
}
