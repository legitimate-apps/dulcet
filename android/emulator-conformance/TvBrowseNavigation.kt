package com.legitimateapps.dulcet.emulator

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Enter a fresh library category by remote, after the root has made its initial focus claim.
 *
 * The category rows form the root's left pane, always composed; the shelves' first card deliberately
 * takes default focus, so seeing a category node is not a promise that a later RequestFocus keeps it.
 * Waiting for the actual initial focus, then moving back to the pane, exercises the same navigation
 * as a person with the remote: LEFT from the shelves reaches the list, then the list walks vertically.
 */
fun ComposeTestRule.focusTvBrowseCategory(tag: String) {
    // The left pane's rows, top to bottom.
    val categories = listOf("playlists", "favourites", "downloaded", "mostPlayed", "artists", "albums", "songs", "genres")
        .map { "library.view.$it" }
    require(tag in categories) { "Unsupported library category: $tag" }
    fun focusedTags(): List<String> = onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }
    fun remote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        waitForIdle()
    }
    try {
        println("TV BROWSE NAVIGATION target=$tag before-default=${focusedTags()} " +
            "pane-composed=${onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()}")
        waitUntil("the root's first live card takes initial focus", 30_000) {
            "library.home.0.item.0" in focusedTags()
        }
        println("TV BROWSE NAVIGATION target=$tag initial=${focusedTags()} " +
            "pane-composed=${onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()}")
        // LEFT from the shelves' content reaches the category list.
        repeat(4) {
            if (focusedTags().none { it in categories }) {
                remote(KeyEvent.KEYCODE_DPAD_LEFT)
                println("TV BROWSE NAVIGATION after-LEFT=${focusedTags()}")
            }
        }
        val reached = focusedTags().firstOrNull { it in categories }
        check(reached != null) { "LEFT from the shelves never reached the categories: ${focusedTags()}" }
        val distance = categories.indexOf(tag) - categories.indexOf(reached)
        repeat(kotlin.math.abs(distance)) {
            remote(if (distance < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        }
        check(tag in focusedTags()) { "Vertical movement along the category list did not reach $tag: ${focusedTags()}" }
        println("TV BROWSE NAVIGATION target=$tag focused=true remote-only=true")
    } catch (failure: Throwable) {
        println("TV BROWSE NAVIGATION target=$tag failed focused=${focusedTags()}")
        println(onRoot(useUnmergedTree = true).printToString())
        throw failure
    }
}
