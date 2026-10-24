package com.legitimateapps.dulcet.emulator

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Focus a live Home card without racing a remote sweep's lazy viewport changes. */
fun ComposeTestRule.focusTvListenNowItem(shelf: String, matcher: SemanticsMatcher) {
    try {
        // Shelf keys exist even before an empty shelf's live answer. Anchor the logical shelf,
        // rather than moving the remote past it while waiting for that answer.
        onNodeWithTag("listenNow.surface").performScrollToKey(shelf)
        waitUntil("$shelf has an attached focusable card", 30_000) {
            // Finders synchronize layout and must stay on the test thread (#366). Only the
            // attachment check and action run together on the UI thread, between layout passes.
            val nodes = onAllNodes(matcher).fetchSemanticsNodes()
            check(nodes.size <= 1) { "Home card selector matched ${nodes.size} nodes" }
            nodes.singleOrNull()?.let { node ->
                runOnUiThread {
                    if (!node.layoutInfo.isAttached || !node.layoutInfo.isPlaced || node.layoutInfo.isDeactivated ||
                        node.boundsInRoot.width <= 0 || node.boundsInRoot.height <= 0 || !matcher.matches(node)) false
                    else checkNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)) {
                        "The Home card must offer RequestFocus"
                    }.action?.invoke() == true
                }
            } ?: false
        }
        waitForIdle()
        onNode(matcher).assertIsFocused()
        println("TV HOME FOCUS shelf=$shelf current-attached=true focused=true")
    } catch (failure: Throwable) {
        println(onRoot(useUnmergedTree = true).printToString())
        throw failure
    }
}
