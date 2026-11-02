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
    fun settle(millis: Long = 1_200): Boolean {
        Thread.sleep(millis)
        return true
    }

    companion object {
        private const val LONG_EDGE = 1000
        private const val QUALITY = 55

        fun requested(): Boolean = InstrumentationRegistry.getArguments().getString("dulcetDesignTour") == "true"
    }
}
