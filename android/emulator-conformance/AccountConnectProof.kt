package com.legitimateapps.dulcet.emulator

import android.content.Context
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
