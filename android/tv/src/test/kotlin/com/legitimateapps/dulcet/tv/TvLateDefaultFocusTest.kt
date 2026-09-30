package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The library home's first card arriving late — a cold server — composed as TvLibraryEntry composes
 * it: the real top bar, the route's focus memory, the bar's focus, and the home's rest on the bar
 * until content exists. The card is a stand-in with the home card's tag and default; the claim
 * under test is [tvFocus]'s. Every move is a remote key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvLateDefaultFocusTest {
    @get:Rule val compose = createComposeRule()
    private var rowsArrived by mutableStateOf(false)

    /** Review B1: the person moved to Account before the rows came; the card must not take focus from it. */
    @Test fun aCardArrivingAfterThePersonMovedAlongTheBarLeavesFocusWhereTheyPutIt() {
        host()
        assertFocused("library.open", "at launch, before the rows")
        key(Key.DirectionRight)
        assertFocused("tv.account.open", "after RIGHT along the bar")

        rowsArrived = true
        compose.waitForIdle()
        assertTrue(exists(CARD), "setup: the card arrived")
        assertFocused("tv.account.open", "after the rows arrived")
        assertTrue(!focused(CARD), "The late card must not take focus from Account")
    }

    /** The card still takes focus when it arrives while the remote rests on the Library tab. */
    @Test fun aCardArrivingWhileTheLibraryTabHoldsFocusTakesIt() {
        host()
        assertFocused("library.open", "at launch, before the rows")
        rowsArrived = true
        compose.waitForIdle()
        assertFocused(CARD, "after the rows arrived")
    }

    private fun host() {
        compose.setContent {
            MaterialTheme {
                val navigation = remember { TvNavigationFocus() }
                val route = remember { TvRouteFocus() }
                Column(Modifier.fillMaxSize()) {
                    TvTopBar(top = ROUTE_LIBRARY, root = ROUTE_LIBRARY, playing = false, focus = navigation,
                        onSearch = {}, onLibrary = {}, onNowPlaying = {}, onAccount = {})
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        CompositionLocalProvider(
                            LocalTvRouteFocus provides route,
                            LocalTvNavFocus provides navigation.current,
                            LocalTvNavigation provides navigation,
                        ) {
                            EnterRoute()
                            if (rowsArrived) {
                                Button(onClick = {}, modifier = Modifier.tvFocus(CARD, default = true)) { Text("Album") }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
            .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun assertFocused(tag: String, `when`: String) = assertTrue(focused(tag), "$tag must hold focus $`when`")

    private companion object {
        const val CARD = "library.home.0.item.0"
    }
}
