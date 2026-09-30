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
    @Implementation fun activeAccountId(): String = "service-fixture"
}

/**
 * Spec §12.5: the service hands the controller the saved choice and the default network's cost, and
 * follows both as they change, so the next item resolves at the quality for the network it is on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FixtureAccountStore::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"])
class PlaybackServiceStreamingQualityTest {
    @Test fun theControllerFollowsTheSavedChoiceAndTheNetworksCost() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val settings = com.legitimateapps.dulcet.playback.StreamingQualitySettings.get(application)
        settings.set(com.legitimateapps.dulcet.core.StreamingQualityPreference(
            com.legitimateapps.dulcet.core.StreamingQuality.Original, com.legitimateapps.dulcet.core.StreamingQuality.Kbps192))
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val playback = assertNotNull(lifecycle.get().playback)
            val connectivity = org.robolectric.Shadows.shadowOf(
                application.getSystemService(android.net.ConnectivityManager::class.java))
            val callback = connectivity.networkCallbacks.single()
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(1)
            fun report(notMetered: Boolean) {
                val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
                if (notMetered) org.robolectric.Shadows.shadowOf(capabilities)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                callback.onCapabilitiesChanged(network, capabilities)
            }

            // Before the OS has classified a network, the metered choice applies.
            assertEquals(com.legitimateapps.dulcet.core.StreamingQuality.Kbps192, playback.nextStreamingQuality)
            report(notMetered = true)
            assertEquals(com.legitimateapps.dulcet.core.StreamingQuality.Original, playback.nextStreamingQuality)
            settings.set(com.legitimateapps.dulcet.core.StreamingQualityPreference(
                com.legitimateapps.dulcet.core.StreamingQuality.Kbps256, com.legitimateapps.dulcet.core.StreamingQuality.Kbps192))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(com.legitimateapps.dulcet.core.StreamingQuality.Kbps256, playback.nextStreamingQuality)
            report(notMetered = false)
            assertEquals(com.legitimateapps.dulcet.core.StreamingQuality.Kbps192, playback.nextStreamingQuality)
        } finally {
            lifecycle.destroy()
            application.getSharedPreferences("dulcet.streaming-quality", 0).edit().clear().commit()
        }
        assertTrue(org.robolectric.Shadows.shadowOf(
            application.getSystemService(android.net.ConnectivityManager::class.java)).networkCallbacks.isEmpty(),
            "the service's network callback is removed with the service")
    }
}
