package com.legitimateapps.dulcet.server.auth

import com.legitimateapps.dulcet.server.db.Db
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class User(val id: String, val name: String, val isAdmin: Boolean)

/**
 * Subsonic token authentication (`t = md5(password + s)`) needs the plain password on the server,
 * so its passwords remain encrypted (AES-GCM) under a key kept in the data folder.
 * Dulcet API login uses a separate slow hash and never opens this recoverable copy.
 * The key file is created once with owner-only permissions.
 */
class PasswordVault(private val key: ByteArray) {
    private val random = SecureRandom()

    fun seal(password: String): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(password.toByteArray())
    }

    fun open(sealed: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12).decodeToString()
    }

    companion object {
        fun fromDataFolder(dataFolder: Path): PasswordVault = PasswordVault(keyFromDataFolder(dataFolder))

        fun keyFromDataFolder(dataFolder: Path): ByteArray {
            val file = dataFolder.resolve("secret.key")
            if (!Files.exists(file)) {
                Files.createDirectories(dataFolder)
                if (!Files.getFileStore(dataFolder).supportsFileAttributeView("posix")) {
                    throw UnsupportedOperationException("Data folder must support owner-only POSIX key permissions")
                }
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.write(file, key)
            }
            return Files.readAllBytes(file)
        }
    }
}

sealed interface AuthOutcome {
    data class Ok(val user: User) : AuthOutcome
    data object WrongCredentials : AuthOutcome
}

class Users(private val db: Db, private val vault: PasswordVault, private val clock: () -> Long = System::currentTimeMillis) {
    init {
        db.write {
            if (query("PRAGMA table_info(user)") { it.string("name") }.none { it == "password_hash" }) execute("ALTER TABLE user ADD COLUMN password_hash TEXT")
            query("SELECT id,password_enc FROM user WHERE password_hash IS NULL") { it.string("id") to it.bytes("password_enc")!! }.forEach { (id, sealed) ->
                execute("UPDATE user SET password_hash=? WHERE id=?", SlowHash.hash(vault.open(sealed)), id)
            }
        }
    }

    /** Header/browser Dulcet authentication never decrypts the OpenSubsonic password. */
    fun authenticateDulcet(name: String, password: String): AuthOutcome {
        val encoded = db.read { string("SELECT password_hash FROM user WHERE name=?", name) }
        val verifier = encoded ?: DUMMY_HASH
        val valid = SlowHash.verify(password, verifier)
        return if (valid && encoded != null) AuthOutcome.Ok(find(name)!!) else AuthOutcome.WrongCredentials
    }

    fun create(name: String, password: String, isAdmin: Boolean): User {
        require(name.isNotBlank()) { "user name is empty" }
        require(password.isNotEmpty()) { "password is empty" }
        val id = java.util.UUID.randomUUID().toString()
        db.write { execute("INSERT INTO user(id, name, password_enc, password_hash, is_admin, created) VALUES (?,?,?,?,?,?)", id, name, vault.seal(password), SlowHash.hash(password), isAdmin, clock()) }
        return User(id, name, isAdmin)
    }

    fun setPassword(name: String, password: String): Boolean =
        db.write {
            val changed = execute("UPDATE user SET password_enc = ?, password_hash = ? WHERE name = ?", vault.seal(password), SlowHash.hash(password), name)
            if (changed > 0 && long("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='api_grant'") > 0) {
                execute("UPDATE api_grant SET revoked=1 WHERE user_id=(SELECT id FROM user WHERE name=?)", name)
                execute("DELETE FROM api_authorization WHERE user_id=(SELECT id FROM user WHERE name=?)", name)
            }
            changed
        } > 0

    fun find(name: String): User? = db.read {
        single("SELECT id, name, is_admin FROM user WHERE name = ?", name) { User(it.string("id"), it.string("name"), it.bool("is_admin")) }
    }

    fun byId(id: String): User? = db.read {
        single("SELECT id, name, is_admin FROM user WHERE id = ?", id) { User(it.string("id"), it.string("name"), it.bool("is_admin")) }
    }

    fun all(): List<User> = db.read { query("SELECT id, name, is_admin FROM user ORDER BY name") { User(it.string("id"), it.string("name"), it.bool("is_admin")) } }

    fun count(): Long = db.read { long("SELECT COUNT(*) FROM user") }

    fun authenticateToken(name: String, token: String, salt: String): AuthOutcome {
        val password = password(name) ?: return AuthOutcome.WrongCredentials
        val expected = md5Hex(password + salt)
        return if (MessageDigest.isEqual(expected.toByteArray(), token.lowercase().toByteArray())) AuthOutcome.Ok(find(name)!!) else AuthOutcome.WrongCredentials
    }

    fun authenticatePassword(name: String, given: String): AuthOutcome {
        val password = password(name) ?: return AuthOutcome.WrongCredentials
        val plain = if (given.startsWith("enc:")) decodeHex(given.removePrefix("enc:")) ?: return AuthOutcome.WrongCredentials else given
        return if (MessageDigest.isEqual(password.toByteArray(), plain.toByteArray())) AuthOutcome.Ok(find(name)!!) else AuthOutcome.WrongCredentials
    }

    private fun password(name: String): String? =
        db.read { single("SELECT password_enc FROM user WHERE name = ?", name) { it.bytes("password_enc") } }?.let(vault::open)

    companion object {
        private val DUMMY_HASH by lazy { SlowHash.hash("invalid-user-dummy-password") }
        fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun decodeHex(hex: String): String? {
            if (hex.length % 2 != 0) return null
            return runCatching { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString() }.getOrNull()
        }
    }
}
