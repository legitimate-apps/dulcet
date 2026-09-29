package com.legitimateapps.dulcet

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.playback.PlaybackService
import com.legitimateapps.dulcet.playback.releasePlaybackForSignOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.*

/**
 * Sign-out stops playback through the production service, session and controller (spec §14.7):
 * only account storage is replaced, as in [PlaybackServiceOwnershipTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FixtureAccountStore::class], instrumentedPackages = ["com.legitimateapps.dulcet"])
class PlaybackReleaseTest {
    @Test fun releasingClosesTheControllerEndsTheSessionAndTheNextAccountGetsItsOwn() {
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        val service = lifecycle.get()
        try {
            val released = assertNotNull(service.ensurePlayback())
            assertEquals(1, service.sessions.size, "The control needs a registered session first")
            assertFalse(released.isClosed(), "The control needs a live controller first")

            service.releasePlayback()

            // Closed, not merely dropped: a controller left open keeps its player, its database and
            // its delivery retry, which goes on sending the signed-out account's credentials.
            assertTrue(released.isClosed(), "The signed-out account's controller must be closed")
            assertNull(service.playback, "The signed-out account's controller must not stay reachable")
            assertTrue(service.sessions.isEmpty(), "The media session must be removed from the service")
            val next = assertNotNull(service.ensurePlayback())
            assertNotSame(released, next, "A later account must never inherit the released controller")
            assertEquals(1, service.sessions.size)
            service.releasePlayback()
            service.releasePlayback() // Idempotent: nothing left to release.
            assertNull(service.playback)
        } finally { lifecycle.destroy() }
    }

    @Test fun theSignOutReachesTheServiceThroughItsLocalBindingAndUnbinds() {
        val application = RuntimeEnvironment.getApplication()
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        val service = lifecycle.get()
        try {
            assertNotNull(service.ensurePlayback())
            val intent = Intent(application, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND)
            shadowOf(application).setComponentNameAndServiceForBindServiceForIntent(intent,
                ComponentName(application, PlaybackService::class.java), service.onBind(intent))

            // The binding answers on the main looper, as it does in the app; idle it rather than block it.
            var released: Boolean? = null
            CoroutineScope(Dispatchers.Main.immediate).launch { released = ServicePlaybackRelease(application).release() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, released)

            assertNull(service.playback)
            assertTrue(service.sessions.isEmpty())
            assertEquals(1, shadowOf(application).unboundServiceConnections.size, "The sign-out must not keep the service bound")
        } finally { lifecycle.destroy() }
    }

    /**
     * A service that never answers must fail the sign-out, never count as released: the account's
     * credential would otherwise be deleted while a live controller still holds it.
     */
    @Test fun aServiceThatNeverAnswersFailsTheReleaseAfterItsBoundAndUnbinds() = runTest {
        val silent = SilentBindingContext(RuntimeEnvironment.getApplication())
        val release = async { releasePlaybackForSignOut(silent) }
        runCurrent()
        assertEquals(1, silent.binds, "The control requires the binding to have been asked for")
        advanceTimeBy(9_999)
        assertFalse(release.isCompleted, "The release waits for its bound, not less")
        advanceTimeBy(2)
        assertEquals(false, release.await(), "An unanswered release must fail the sign-out")
        assertEquals(1, silent.unbinds, "The abandoned binding must be released")
    }

    /**
     * With the service not running, the release bind creates it. A controller built at that
     * creation would restore the signing-out account's queue and send its plays only to be closed,
     * so none is built; the service builds one lazily afterwards, as for any later bind.
     */
    @Test fun aServiceCreatedByTheReleaseBindBuildsNoController() {
        val creating = CreatingBindingContext(RuntimeEnvironment.getApplication())
        try {
            var released: Boolean? = null
            CoroutineScope(Dispatchers.Main.immediate).launch { released = releasePlaybackForSignOut(creating) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, released)
            val service = assertNotNull(creating.lifecycle, "control: the release bind created the service").get()
            assertEquals(false, creating.controllerAtCreation, "A service created to be released must build no controller")
            assertEquals(1, creating.unbinds, "The sign-out must not keep the service bound")
            assertNotNull(service.ensurePlayback(), "Afterwards the service still builds one when asked")
        } finally { creating.lifecycle?.destroy() }
    }
}

/** An account can be saved without a sign-out (the connect form shown for an unreadable record, and on TV). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [SwitchableAccountStore::class], instrumentedPackages = ["com.legitimateapps.dulcet"])
class PlaybackAccountChangeTest {
    @Test fun anAccountSavedWithoutASignOutNeverInheritsTheOldController() {
        SwitchableAccountStore.current = StoredAccount("account-a", "A", "http://127.0.0.1:9", "user-a", "pw-a", true)
        val lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        val service = lifecycle.get()
        try {
            val first = assertNotNull(service.ensurePlayback())
            assertSame(first, service.ensurePlayback(), "The same account keeps its controller")
            SwitchableAccountStore.unreadable = true
            assertSame(first, service.ensurePlayback(), "An unreadable record of the same account keeps its controller")
            SwitchableAccountStore.unreadable = false

            SwitchableAccountStore.current = StoredAccount("account-b", "B", "http://127.0.0.1:9", "user-b", "pw-b", true)
            val second = assertNotNull(service.ensurePlayback())
            assertNotSame(first, second, "Account B must not be handed account A's controller")
            assertTrue(first.isClosed(), "Account A's controller must be closed")
            assertEquals(1, service.sessions.size)
            assertSame(second.sessionPlayer, service.sessions.single().player)

            SwitchableAccountStore.current = null
            assertNull(service.ensurePlayback(), "With no account saved there is no controller")
            assertTrue(second.isClosed())
            assertTrue(service.sessions.isEmpty())
        } finally {
            SwitchableAccountStore.current = null
            lifecycle.destroy()
        }
    }
}

@Implements(AndroidAccountCredentialStore::class)
class SwitchableAccountStore {
    companion object {
        @JvmStatic var current: StoredAccount? = null
        @JvmStatic var unreadable = false
    }
    @Implementation fun load(): StoredAccount? =
        if (unreadable) throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord) else current
    @Implementation fun activeAccountId(): String? = current?.id
}

/** Whether the controller's player has been released, which only its close does. */
private fun AndroidPlaybackController.isClosed(): Boolean =
    ((sessionPlayer as ForwardingPlayer).wrappedPlayer as ExoPlayer).isReleased

/** Creates the service on a bind, as the platform does for a service not yet running, and connects it. */
private class CreatingBindingContext(base: Context) : ContextWrapper(base) {
    var lifecycle: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
    var controllerAtCreation: Boolean? = null
    var unbinds = 0
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        android.os.Handler(Looper.getMainLooper()).post {
            val created = Robolectric.buildService(PlaybackService::class.java, service).create().also { lifecycle = it }
            controllerAtCreation = created.get().playback != null
            conn.onServiceConnected(ComponentName(this, PlaybackService::class.java), created.get().onBind(service))
        }
        return true
    }
    override fun unbindService(conn: ServiceConnection) { unbinds += 1 }
}

/** Accepts a binding and never connects it. */
private class SilentBindingContext(base: Context) : ContextWrapper(base) {
    var binds = 0
    var unbinds = 0
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean { binds += 1; return true }
    override fun unbindService(conn: ServiceConnection) { unbinds += 1 }
}
