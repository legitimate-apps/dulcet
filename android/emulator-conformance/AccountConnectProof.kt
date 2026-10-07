package com.legitimateapps.dulcet.emulator

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import java.io.File
import java.security.KeyStore

/**
 * The device conditions the account-connect proofs put the production app in. None of them replaces
 * a piece of the app: each changes what the device itself does, and the app's own code meets it.
 */

/**
 * The app's preferences directory refuses writes while [block] runs, as a full or read-only disk
 * does: the production credential store's `commit()` then reports that nothing reached the disk.
 * Made read-only by the app's own user, which owns the directory, and restored afterwards whatever
 * [block] did. Fails rather than proceeding when the directory still accepts writes, so a device that
 * ignored the change cannot pass for one whose save failed.
 */
fun <T> withPreferencesWritesRefused(context: Context, block: () -> T): T {
    val directory = File(context.applicationInfo.dataDir, "shared_prefs")
    check(directory.isDirectory || directory.mkdirs()) { "The app's preferences directory could not be made" }
    check(directory.setWritable(false, false)) { "The app's preferences directory could not be made read-only" }
    try {
        check(!directory.canWrite()) { "The app's preferences directory still accepts writes" }
        return block()
    } finally {
        check(directory.setWritable(true, true)) { "The app's preferences directory could not be made writable again" }
    }
}

/** What the credential store's own file on disk says, read past the in-memory preferences. */
fun savedAccountFileNamesAnAccount(context: Context): Boolean {
    val file = File(File(context.applicationInfo.dataDir, "shared_prefs"), "${AndroidAccountCredentialStore.PREFERENCES_NAME}.xml")
    return file.exists() && "activeAccountId" in file.readText()
}

/**
 * Deletes the saved account's key from the device's Keystore, as a Keystore reset does, leaving its
 * encrypted record in place: the record can then no longer be read. Returns the account's id.
 */
fun loseSavedAccountKey(context: Context): String {
    val id = checkNotNull(AndroidAccountCredentialStore(context).activeAccountId()) { "No account is saved" }
    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val alias = "com.legitimateapps.dulcet.$id"
    check(keys.containsAlias(alias)) { "The saved account has no key in the Keystore to lose" }
    keys.deleteEntry(alias)
    check(!keys.containsAlias(alias)) { "The Keystore kept the saved account's key" }
    return id
}

/**
 * Whether any connection forwarded by [relay] carried a Subsonic error envelope with [code] in the
 * server's answer: what the server itself said, read from the wire.
 */
fun ServerRelay.serverAnsweredErrorCode(code: Int): Boolean = connections().any { tapped ->
    val answer = String(tapped.answered(), Charsets.ISO_8859_1)
    "\"status\":\"failed\"" in answer && Regex("\"code\"\\s*:\\s*$code\\b").containsMatchIn(answer)
}

/** Whether the app's saved account was removed, so the next proof starts from an empty install. */
fun forgetSavedAccount(context: Context) {
    AndroidAccountCredentialStore(context).delete()
    check(AndroidAccountCredentialStore(context).activeAccountId() == null) { "The saved account could not be removed" }
}

/**
 * Waits until [activity]'s window holds the input focus, so that a touch or a key reaches the app as
 * it would reach it for a person looking at it. The account proofs run first after the emulator boots,
 * and there the first launch has been left without focus: a touch went to the launcher (core-ci run
 * 37638393461), and in run 37643560014 the launcher, relaunching under the boot's load, raised an
 * "isn't responding" dialog that held the focus over the app. Another app's not-responding dialog is
 * answered by stopping that app, which closes the dialog, as a person answers it with Close app;
 * halfway, the screen is also woken, the keyguard dismissed and the shade collapsed. The app under
 * test is never touched. A window still without focus fails naming the window that holds it.
 */
fun awaitWindowInFront(instrumentation: Instrumentation, activity: () -> Activity?, timeoutMillis: Long = 60_000) {
    fun focused(): Boolean {
        var focused = false
        instrumentation.runOnMainSync { focused = activity()?.hasWindowFocus() == true }
        return focused
    }
    fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
    }
    fun focusHolder() = shell("dumpsys window").lines()
        .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mFocusedWindow" in it }
        .distinct().joinToString(" | ") { it.trim().take(200) }
    val ownPackage = instrumentation.targetContext.packageName
    val notResponding = Regex("""Application Not Responding: ([A-Za-z0-9_.]+)""")
    val start = SystemClock.uptimeMillis()
    var prepared = false
    var lastLook = start
    val answered = mutableListOf<String>()
    while (SystemClock.uptimeMillis() - start < timeoutMillis) {
        if (focused()) {
            if (answered.isNotEmpty()) println("ANDROID EMULATOR FOCUS another app's not-responding dialog answered: ${answered.joinToString(",")}")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - start > 3_000 && now - lastLook > 2_000) {
            lastLook = now
            notResponding.findAll(focusHolder()).map { it.groupValues[1] }.distinct()
                .filter { it != ownPackage && it !in answered }
                .forEach { stuck -> shell("am force-stop $stuck"); answered += stuck }
        }
        if (!prepared && now - start > timeoutMillis / 2) {
            prepared = true
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("cmd statusbar collapse")
        }
        SystemClock.sleep(100)
    }
    error("The app's window never came to the front after ${timeoutMillis}ms (woken and unlocked halfway: $prepared; " +
        "not-responding apps stopped: ${answered.ifEmpty { listOf("none") }.joinToString(",")}). Focus: ${focusHolder()}")
}
