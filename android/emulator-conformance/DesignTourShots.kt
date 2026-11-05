package com.legitimateapps.dulcet.emulator

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The camera for `tools/design-tour-android`: a screen tour for design review, not a proof. Each
 * shot is the whole display as the person sees it, scaled to about 1000 px on its long edge and
 * written as a JPEG to the app's external files directory, `design-tour/<label>-<nn>-<screen>.jpg`,
 * from where the tool pulls it. A screen the tour could not reach is still photographed, named
 * `<nn>-<screen>-UNREACHED`, so a shot is never of a spinner in place of the screen it names.
 *
 * Runs only when the runner passes `dulcetDesignTour=true`: an ordinary instrumented run of the
 * target returns from the tour at once and photographs nothing.
 */
class DesignTourShots(private val label: String) {
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "design-tour",
    ).also { it.mkdirs() }
    private var number = 0
    private var foreignLauncherAnrRecoveries = 0
    val unreached = mutableListOf<String>()

    init {
        directory.listFiles { file -> file.name.startsWith("$label-") }?.forEach(File::delete)
    }

    /** Reaches one screen, then photographs it whether or not it was reached. */
    fun step(screen: String, state: String, reach: () -> Boolean) = step("$screen--$state", reach)

    fun step(screen: String, reach: () -> Boolean) {
        recoverForeignLauncherDialog()
        val reached = runCatching(reach).onFailure { println("DESIGN TOUR $screen: ${it.message}") }.getOrDefault(false)
        if (!reached) unreached += screen
        shoot(if (reached) screen else "$screen-UNREACHED")
        println("DESIGN TOUR screen=$screen reached=$reached")
    }

    fun shoot(screen: String) {
        number += 1
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        recoverForeignLauncherDialog()
        instrumentation.waitForIdleSync()
        waitOutSpinner(screen)
        val artwork = waitForArtwork(screen)
        // A tour's frame is proof the art had landed: a cover still out is a failure of the frame, except
        // on the states that are about not reaching a server or are held on purpose.
        if ((artwork.waiting > 0 || artwork.failed > 0) && NO_SERVER_STATES.none { it in screen } && HELD_STATES.none { it in screen }) {
            unreached += "$screen (artwork waiting=${artwork.waiting} failed=${artwork.failed})"
        }
        // API 34's cache otherwise retains a previous Compose destination in the XML sidecar,
        // even though the screenshot and Compose semantics have already changed.
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        // The accessibility connection can lag the first visible frame on a fresh hosted device.
        var activeRoot = instrumentation.uiAutomation.rootInActiveWindow
        repeat(10) {
            if (activeRoot == null) {
                Thread.sleep(100)
                activeRoot = instrumentation.uiAutomation.rootInActiveWindow
            }
        }
        File(directory, "%s-%02d-%s.xml".format(label, number, screen)).outputStream().use {
            activeRoot?.let { root ->
                fun dump(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    val text = android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())
                    val desc = android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())
                    it.write((" ".repeat(depth) + "<node text=\"$text\" description=\"$desc\" bounds=\"$bounds\" focused=\"${node.isFocused}\" checked=\"${node.isChecked}\" selected=\"${node.isSelected}\">\n").toByteArray())
                    repeat(node.childCount) { index -> node.getChild(index)?.let { child -> dump(child, depth + 1) } }
                    it.write((" ".repeat(depth) + "</node>\n").toByteArray())
                }
                dump(root, 0)
            }
        }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("The display could not be captured")
        val configuration = instrumentation.targetContext.resources.configuration
        File(directory, "%s-%02d-%s.json".format(label, number, screen)).writeText(
            org.json.JSONObject().put("screen", screen).put("label", label)
                .put("width", bitmap.width).put("height", bitmap.height)
                .put("fontScale", configuration.fontScale.toDouble())
                .put("night", configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                .put("foregroundPackage", activeRoot?.packageName)
                .put("artworkWaiting", artwork.waiting).put("artworkPainted", artwork.painted)
                .put("artworkMissing", artwork.missing).put("artworkFailed", artwork.failed)
                .put("artworkNotKept", artwork.notKept)
                .put("appPackage", instrumentation.targetContext.packageName)
                .put("foreignLauncherAnrRecoveries", foreignLauncherAnrRecoveries).toString(),
        )
        val scale = LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        try {
            File(directory, "%s-%02d-%s.jpg".format(label, number, screen)).outputStream().use {
                check(scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "JPEG encoding failed" }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    /** A Google APIs image can leave its launcher's ANR above an otherwise responsive app.
     * Recover only that named foreign process; record the intervention with every later photo.
     * An app ANR, permission dialog or any other unexpected foreground remains a capture failure.
     */
    private fun recoverForeignLauncherDialog() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return
        if (root.packageName?.toString() != "android") return
        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
            nodes += node
            repeat(node.childCount) { node.getChild(it)?.let(::collect) }
        }
        collect(root)
        if (nodes.none { it.text?.toString()?.let { text ->
                text.startsWith("Pixel Launcher") && text.contains("responding")
            } == true }) return
        val close = nodes.firstOrNull { it.viewIdResourceName == "android:id/aerr_close" }
            ?: nodes.firstOrNull { it.text?.toString() == "Close app" } ?: return
        val target = if (close.isClickable) close else close.parent ?: return
        if (!target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
        foreignLauncherAnrRecoveries += 1
        println("DESIGN TOUR recovered foreign Pixel Launcher ANR count=$foreignLauncherAnrRecoveries")
        Thread.sleep(500)
    }

    /** Lets artwork and animations land before a shot; asserts nothing. */
    /**
     * A page still fetching is not the page the step means to show. An indeterminate progress node
     * has no range in the accessibility tree; give it a bounded moment, and log one that stays so a
     * reviewer can tell a held state from a capture that came too early.
     */
    private fun waitOutSpinner(screen: String) {
        if (HELD_STATES.any { it in screen }) return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 6_000
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root == null || !root.hasSpinner()) return
            if (System.currentTimeMillis() >= deadline) {
                println("DESIGN TOUR screen=$screen spinner-still-showing=true")
                return
            }
            Thread.sleep(250)
        }
    }

    /**
     * A cover still on its first quick tries is not the picture the page will show. Waits those out, then
     * reports what every cover on the page ended up as, so a placeholder in a photo can be told apart:
     * a cover the server does not have, or one it failed to send.
     */
    private fun waitForArtwork(screen: String): com.legitimateapps.dulcet.ui.ArtworkActivity {
        val images = com.legitimateapps.dulcet.ui.ArtworkImages
        val started = System.currentTimeMillis()
        val deadline = started + ARTWORK_WAIT_MILLIS
        val onEntry = images.activity().waiting
        if (HELD_STATES.none { it in screen }) {
            while (images.activity().waiting > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        }
        // A cover answered this instant has not drawn yet.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        // Tiles still waiting on entry are what a frame taken now would have caught: a capture's timing.
        // Failed reads over the whole run are the loader's side: without them, no frame was its fault.
        println("DESIGN TOUR screen=$screen artwork onEntryWaiting=$onEntry waitedMillis=${System.currentTimeMillis() - started} failedReads=${images.failedReads()}")
        return images.activity().also {
            println("DESIGN TOUR screen=$screen artwork waiting=${it.waiting} painted=${it.painted} missing=${it.missing} failed=${it.failed} notKept=${it.notKept}")
        }
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.hasSpinner(): Boolean {
        if (className?.toString() == "android.widget.ProgressBar" && rangeInfo == null && isVisibleToUser) return true
        for (index in 0 until childCount) if (getChild(index)?.hasSpinner() == true) return true
        return false
    }

    fun settle(millis: Long = 1_200): Boolean {
        Thread.sleep(millis)
        return true
    }

    companion object {
        private val HELD_STATES = listOf("loading", "busy", "held", "pending", "waiting", "submitting")
        private val NO_SERVER_STATES = listOf("offline", "dropped", "server-away", "failed", "UNREACHED", "stale", "error")
        private const val ARTWORK_WAIT_MILLIS = 15_000L
        private const val LONG_EDGE = 1000
        private const val QUALITY = 55

        fun requested(): Boolean = InstrumentationRegistry.getArguments().getString("dulcetDesignTour") == "true"
    }
}
