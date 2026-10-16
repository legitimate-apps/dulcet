package com.legitimateapps.dulcet.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest
import java.util.Base64

/** Salted bcrypt cost 12; a fixed SHA-256 prehash preserves passwords beyond bcrypt's 72-byte limit. */
object SlowHash {
    fun hash(secret: String): String = "bcrypt-sha256$" + BCrypt.withDefaults().hashToString(12, prehash(secret))
    fun verify(secret: String, encoded: String): Boolean = runCatching {
        if (!encoded.startsWith("bcrypt-sha256$")) return false
        BCrypt.verifyer().verify(prehash(secret), encoded.removePrefix("bcrypt-sha256$")).verified
    }.getOrDefault(false)
    private fun prehash(secret: String): CharArray = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())).toCharArray()
}
