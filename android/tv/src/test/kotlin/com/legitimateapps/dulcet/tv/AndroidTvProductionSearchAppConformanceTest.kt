package com.legitimateapps.dulcet.tv

import android.app.Application
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.search.conformance.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvProductionSearchAppConformanceTest {
    private val environment = ProductionSearchEnvironment()
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    @Test fun conf41ProductionQueryMergesAndRoutesOpaqueId() {
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithTag("search.query").performClick()
        compose.onNodeWithTag("search.query").performKeyInput {
            pressKey(Key.D)
        }
        assertSingleCharacterLocalRows()
        compose.onNodeWithTag("search.query").performKeyInput {
            pressKey(Key.U); pressKey(Key.L)
            pressKey(Key.C); pressKey(Key.E); pressKey(Key.T)
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Dulcet Health Probe", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search.result.0").assertTextContains(environment.overlap.title)
        compose.onNodeWithTag("search.result.1").assertTextContains(environment.localOnly.title)
        compose.onNodeWithTag("search.result.2").assertTextContains(environment.serverOnly.title)
        compose.onNodeWithTag("search.result.0").assertTextContains("Dulcet Health Probe")
        assertNull(shadowOf(app).nextStartedActivity, "Rendering must not activate a result")
        compose.onNodeWithTag("search.query").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.0").assertIsFocused()
        compose.onNodeWithTag("search.result.0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.1").assertIsFocused()
        compose.onNodeWithTag("search.result.1").performKeyInput { pressKey(Key.DirectionCenter) }
        val routed = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(SearchDetailActivity::class.java.name, routed.component?.className)
        assertEquals(SearchDetailIntent.ACTION, routed.action)
        assertEquals(SearchDetailIntent.SOURCE_SEARCH, routed.getStringExtra(SearchDetailIntent.EXTRA_SOURCE))
        assertEquals(environment.localOnly.id.rawId, routed.getStringExtra(SearchDetailIntent.EXTRA_RAW_ID))
        assertEquals(environment.localOnly.type.name, routed.getStringExtra(SearchDetailIntent.EXTRA_RESULT_TYPE))
        assertEquals(environment.account.providerInstanceId, routed.getStringExtra(SearchDetailIntent.EXTRA_PROVIDER_INSTANCE_ID))
        val diagnostics = routed.toUri(Intent.URI_INTENT_SCHEME) + environment.account.toString() +
            org.robolectric.shadows.ShadowLog.getLogs().joinToString { it.msg.orEmpty() }
        for (canary in listOf(environment.account.username, environment.account.password)) {
            assertFalse(diagnostics.contains(canary), "Credential canary leaked")
        }
        val canaries = listOf("user-canary-!", "password-canary-!", "token-canary-!", "salt-canary-!", "query-canary-!")
        val redacted = com.legitimateapps.dulcet.core.Redactor.redactUrl(
            "http://127.0.0.1:4533/rest/search3.view?u=${canaries[0]}&p=${canaries[1]}&t=${canaries[2]}&s=${canaries[3]}&query=${canaries[4]}")
        assertEquals("http://127.0.0.1:4533/rest/search3.view?<redacted>", redacted)
        for (canary in canaries) assertFalse(redacted.contains(canary), "Query canary leaked")
        println("CONF-41 OBSERVED production app query: cached replacement index=0 local-only index=1 server-only index=2 opaque activation unchanged")
    }
    private fun assertSingleCharacterLocalRows() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Dulcet local only", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search.result.0").assertTextContains("Dulcet")
        compose.onNodeWithTag("search.result.1").assertTextContains(environment.localOnly.title)
        compose.onNodeWithTag("search.result.2").assertDoesNotExist()
        println("CONF-41 OBSERVED first-character query returns committed local library rows")
    }

}
