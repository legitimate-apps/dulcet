package com.legitimateapps.dulcet.tv

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySessionState
import com.legitimateapps.dulcet.search.conformance.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvProductionLibrarySyncAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)
    private fun observed(): LibrarySessionState = compose.onNodeWithTag("library.surface")
        .fetchSemanticsNode().config[LibraryObservation]

    @Test fun librarySyncSchedulesSecondGenerationAndReopensOffline() {
        compose.onNodeWithTag("library.open").performClick()
        compose.waitUntil(10_000) { observed().savedReads == 1 }
        assertEquals(0, observed().syncStarts)
        compose.onNodeWithTag("library.sync").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("library.sync").assertIsFocused()
        compose.onNodeWithTag("library.sync").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(90_000) { observed().library?.generation == 1L && !observed().syncing }
        assertEquals(1, observed().syncStarts)
        assertEquals(0, observed().scheduledRefreshes)
        compose.onNodeWithTag("library.sync").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("library.card.0").assertIsFocused()
        val first = assertNotNull(observed().library)
        assertTrue(first.rows.isNotEmpty())
        compose.onNodeWithTag("library.row.0").assertTextContains(first.rows.first().title)

        // A stopped activity must not refresh. Resuming arms the production timer again.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LibrarySession.REFRESH_MILLIS))
        assertFalse(ShadowLog.getLogs().any { it.msg == "LIBRARY_REFRESH_FIRED monotonic=true" })
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        // Advance only the monotonic scheduler clock; the test never calls sync a second time.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LibrarySession.REFRESH_MILLIS))
        compose.waitUntil(90_000) { observed().library?.generation == 2L && !observed().syncing }
        assertEquals(2, observed().syncStarts)
        assertEquals(1, observed().scheduledRefreshes)
        assertTrue(ShadowLog.getLogs().any { it.msg == "LIBRARY_REFRESH_FIRED monotonic=true" })
        val second = assertNotNull(observed().library)
        assertEquals(first.rows, second.rows)
        compose.onNodeWithTag("library.row.0").assertTextContains(second.rows.first().title)

        environment.requireRefusalAndPointSavedAccountOffline()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("library.open").performClick()
        compose.waitUntil(10_000) { observed().savedReads == 1 }
        assertEquals(second, observed().library)
        assertEquals(0, observed().syncStarts)
        assertEquals(0, observed().scheduledRefreshes)
        compose.onNodeWithTag("library.row.0").assertTextContains(second.rows.first().title)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(LibrarySession.REFRESH_MILLIS))
        assertEquals(0, observed().syncStarts)
        assertEquals(0, observed().scheduledRefreshes)
        assertTrue(ShadowLog.getLogs().any { it.msg == "LIBRARY_SAVED_READ generation=2 syncStarts=0" })
        assertEquals(1, ShadowLog.getLogs().count { it.msg == "LIBRARY_REFRESH_FIRED monotonic=true" })
        assertEquals(second, observed().library)
        val probeIndex = second.rows.indexOfFirst { it.title == "Dulcet Health Probe" }
        assertTrue(probeIndex >= 0)
        compose.onNodeWithTag("library.rows").performScrollToNode(hasTestTag("library.row.$probeIndex"))
        compose.onNodeWithTag("library.row.$probeIndex").assertTextContains("Dulcet Health Probe")
        val diagnostics = ShadowLog.getLogs().joinToString { it.msg.orEmpty() }
        for (canary in listOf("dulcet-admin", "dulcet-ci-canary-password"))
            assertFalse(diagnostics.contains(canary), "Credential canary leaked")
        ShadowLog.getLogs().filter { it.tag == "DulcetLibrary" }.forEach { println(it.msg) }
        println("LIBRARY APP OBSERVED generations=1,2 scheduler-fired=true offline-refusal=true reopened-generation=2 offline-sync-starts=0")
    }
}
