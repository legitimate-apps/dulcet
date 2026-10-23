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
 * Enter a fresh Browse category by remote, after Browse has made its initial focus claim.
 *
 * The category header can be composed before the first live card arrives. That card deliberately
 * takes default focus and scrolls the header away, so seeing a category node is not a promise that
 * it will survive a subsequent RequestFocus. Waiting for the actual initial focus, then moving
 * back to the header, exercises the same lazy-list navigation as a person with the remote.
 */
fun ComposeTestRule.focusTvBrowseCategory(tag: String) {
    val categories = listOf("albums", "artists", "genres", "favourites", "playlists")
        .map { "library.view.$it" }
    require(tag in categories) { "Unsupported Browse category: $tag" }
    fun focusedTags(): List<String> = onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "?" } }
    fun remote(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
        waitForIdle()
    }
    try {
        println("TV BROWSE NAVIGATION target=$tag before-default=${focusedTags()} " +
            "header-composed=${onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()}")
        waitUntil("Browse's first live card takes initial focus", 30_000) {
            "library.home.0.item.0" in focusedTags()
        }
        println("TV BROWSE NAVIGATION target=$tag initial=${focusedTags()} " +
            "header-composed=${onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()}")
        // The Songs/Downloaded row lies between the first shelf and Albums. UP reveals both
        // rows; unlike a semantics lookup this also brings a lazy, uncomposed header into view.
        repeat(4) {
            if ("library.view.albums" !in focusedTags()) {
                remote(KeyEvent.KEYCODE_DPAD_UP)
                println("TV BROWSE NAVIGATION after-UP=${focusedTags()}")
            }
        }
        check("library.view.albums" in focusedTags()) { "UP from Browse never reached Albums" }
        repeat(categories.indexOf(tag)) { remote(KeyEvent.KEYCODE_DPAD_RIGHT) }
        check(tag in focusedTags()) { "RIGHT along Browse categories did not reach $tag: ${focusedTags()}" }
        println("TV BROWSE NAVIGATION target=$tag focused=true remote-only=true")
    } catch (failure: Throwable) {
        println("TV BROWSE NAVIGATION target=$tag failed focused=${focusedTags()}")
        println(onRoot(useUnmergedTree = true).printToString())
        throw failure
    }
}
