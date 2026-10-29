package com.legitimateapps.dulcet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

public data class StoredAccount(
    val id: String,
    val serverName: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
    val serverIdentity: String = "",
) {
    override fun toString(): String = "StoredAccount(id=$id, <redacted>)"
}

public interface AccountCredentialStore {
    fun load(): StoredAccount?
    fun save(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String,
        allowLocalHttp: Boolean,
    ): StoredAccount
    /** A server-authenticated continuation retains its provider namespace, including password
     * refreshes and a canonical username confirmed by the server. Unsupported stores return null. */
    fun saveAuthenticatedContinuation(expected: StoredAccount, confirmed: StoredAccount): StoredAccount? = null
    fun delete()
    /** All encrypted saved accounts; switching never destroys another account's namespace. */
    fun remove(id: String) { if (activeAccountId() == id) delete() }
    fun savedAccounts(): List<StoredAccount> = listOfNotNull(load())
    /** Strongest accepted lineage, including identity learned after a legacy credential was saved. */
    fun acceptedServerIdentity(account: StoredAccount): String = account.serverIdentity
    /** Route enrollment is additive; unsupported stores retain single-address behavior. */
    suspend fun enrollConnections(account: StoredAccount, request: com.legitimateapps.dulcet.core.AccountConnectionRequest) {}
    fun savedAccountIds(): Set<String> = setOfNotNull(activeAccountId())
    fun activate(id: String): StoredAccount? = load()?.takeIf { it.id == id }
    /** Adds an authenticated server, keeping the accounts already saved. */
    fun saveAdditional(account: StoredAccount): StoredAccount = save(account.serverName, account.serverUrl,
        account.username, account.password, account.allowLocalHttp)

    /**
     * The id of the saved account, read without decrypting its record, so it is known even when
     * [load] cannot read the record (spec §14.7: such an account can still be signed out). Null when
     * no account is saved.
     */
    fun activeAccountId(): String? = load()?.id

    /**
     * Deletes the secure-storage key of an account whose record is already gone: a key whose
     * deletion failed during a sign-out is retried at a later launch. Never the saved account's.
     */
    fun deleteKey(id: String) {}
}

public class CredentialStoreException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        SecureStorageUnavailable,
        CorruptRecord,
        PersistenceFailed,
    }
}

/**
 * The only credential cryptography used by [AndroidAccountCredentialStore].
 *
 * Keeping this boundary injectable lets host tests make Android Keystore unavailable and prove
 * that the production record store fails closed. There is deliberately no plaintext implementation
 * and no recovery branch that can persist the encoded account without this boundary succeeding.
 */
public interface AccountCredentialCipher {
    fun encrypt(id: String, plaintext: ByteArray): ByteArray
    fun decrypt(id: String, payload: ByteArray): ByteArray
    fun delete(id: String)
}

public class AndroidAccountCredentialStore public constructor(
    context: Context,
    private val cipher: AccountCredentialCipher,
) : AccountCredentialStore {
    constructor(context: Context) : this(context, AndroidKeystoreAccountCredentialCipher())

    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredAccount? {
        val id = preferences.getString(ACTIVE_ACCOUNT_KEY, null) ?: return null
        return load(id)
    }

    private fun load(id: String): StoredAccount {
        val encrypted = preferences.getString(payloadKey(id), null)
            ?: throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord)
        return try {
            CredentialCodec.decode(
                id,
                cipher.decrypt(id, Base64.decode(encrypted, Base64.NO_WRAP)),
            ).also { account ->
                if (account.serverIdentity.isNotEmpty()) com.legitimateapps.dulcet.core.AndroidServerIdentity.seed(
                    applicationContext, account.id, account.serverIdentity)
                com.legitimateapps.dulcet.core.AndroidConnections(applicationContext).restore(
                    com.legitimateapps.dulcet.core.OwnershipAnchor(account.id, account.serverUrl, account.username,
                        acceptedServerIdentity(account)))
            }
        } catch (failure: CredentialStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord, failure)
        }
    }

    override fun savedAccountIds(): Set<String> = preferences.all.keys
        .filter { it.startsWith("account.") }.map { it.removePrefix("account.") }.toSet()

    override fun savedAccounts(): List<StoredAccount> = savedAccountIds().mapNotNull { id -> runCatching { load(id) }.getOrNull() }

    override fun acceptedServerIdentity(account: StoredAccount): String = try {
        com.legitimateapps.dulcet.core.AndroidServerIdentity.acceptedIdentity(applicationContext, account.id, account.serverIdentity)
    } catch (failure: Exception) {
        throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed, failure)
    }

    override fun activate(id: String): StoredAccount? {
        if (id !in savedAccountIds()) return null
        val account = load(id)
        if (!preferences.edit().putString(ACTIVE_ACCOUNT_KEY, id).commit())
            throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        return account
    }

    override fun saveAdditional(account: StoredAccount): StoredAccount = writeNew(account.copy(id = UUID.randomUUID().toString()), true)

    override fun save(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String,
        allowLocalHttp: Boolean,
    ): StoredAccount {
        return writeNew(StoredAccount(UUID.randomUUID().toString(), serverName, serverUrl, username, password, allowLocalHttp), false)
    }

    private fun writeNew(account: StoredAccount, keepPrevious: Boolean): StoredAccount {
        val previousId = preferences.getString(ACTIVE_ACCOUNT_KEY, null)
        return try {
            val encoded = Base64.encodeToString(
                try {
                    cipher.encrypt(account.id, CredentialCodec.encode(account))
                } catch (failure: Exception) {
                    throw CredentialStoreException(
                        CredentialStoreException.Reason.SecureStorageUnavailable,
                        failure,
                    )
                },
                Base64.NO_WRAP,
            )
            val committed = preferences.edit()
                .putString(payloadKey(account.id), encoded)
                .putString(ACTIVE_ACCOUNT_KEY, account.id)
                .commit()
            if (!committed) {
                throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
            }

            if (!keepPrevious && previousId != null && previousId != account.id) {
                preferences.edit().remove(payloadKey(previousId)).apply()
                // The new account is already committed, so old-key deletion is best-effort.
                // A stale keystore entry is preferable to destroying the active credential.
                runCatching { cipher.delete(previousId) }
            }
            if (account.serverIdentity.isNotEmpty()) com.legitimateapps.dulcet.core.AndroidServerIdentity.accept(
                applicationContext, account.id, account.serverIdentity)
            account
        } catch (failure: Exception) {
            // A failed secure write must not leave the new account selected, even if a
            // SharedPreferences disk commit reported failure after updating its in-memory map.
            preferences.edit()
                .remove(payloadKey(account.id))
                .apply {
                    if (previousId == null) remove(ACTIVE_ACCOUNT_KEY)
                    else putString(ACTIVE_ACCOUNT_KEY, previousId)
                }
                .commit()
            runCatching { cipher.delete(account.id) }
            if (failure is CredentialStoreException) throw failure
            throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed, failure)
        }
    }

    override suspend fun enrollConnections(account: StoredAccount, request: com.legitimateapps.dulcet.core.AccountConnectionRequest) {
        if (!account.serverUrl.startsWith("https://")) return
        com.legitimateapps.dulcet.core.AndroidConnections(applicationContext).enroll(
            com.legitimateapps.dulcet.core.ConnectionEnrollmentRequest(
                com.legitimateapps.dulcet.core.OwnershipAnchor(account.id, account.serverUrl, account.username,
                    acceptedServerIdentity(account)), request.copy(serverUrl = account.serverUrl)))
    }

    override fun saveAuthenticatedContinuation(expected: StoredAccount, confirmed: StoredAccount): StoredAccount? {
        fun endpoint(value: StoredAccount) = com.legitimateapps.dulcet.core.PlaybackEndpointAccount(
            value.id, value.serverUrl, value.username, value.password, value.allowLocalHttp,
        )
        val identity = com.legitimateapps.dulcet.core.MediaBindingIdentity
        if (load(expected.id) != expected) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        if (confirmed.id != expected.id ||
            !identity.isValidBinding(expected.id, expected.serverUrl, expected.username) ||
            !identity.isValidBinding(confirmed.id, confirmed.serverUrl, confirmed.username) ||
            identity.canonicalServerUrl(expected.serverUrl) != identity.canonicalServerUrl(confirmed.serverUrl)
        ) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        // The caller authenticated this canonical principal (or the unchanged saved request).
        // Adopt before committing: either spelling remains valid if secure storage fails.
        if (!com.legitimateapps.dulcet.core.AndroidMediaBindingIdentity.adoptAuthenticatedAccount(
                applicationContext, endpoint(expected), endpoint(confirmed),
            )) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        val previousPayload = preferences.getString(payloadKey(expected.id), null)
            ?: throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord)
        val encoded = try {
            Base64.encodeToString(cipher.encrypt(confirmed.id, CredentialCodec.encode(confirmed)), Base64.NO_WRAP)
        } catch (failure: Exception) {
            throw CredentialStoreException(CredentialStoreException.Reason.SecureStorageUnavailable, failure)
        }
        // Other account choices must not be overwritten by an old authentication result.
        if (load(expected.id) != expected) throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        if (!preferences.edit().putString(payloadKey(confirmed.id), encoded).commit()) {
            preferences.edit().putString(payloadKey(expected.id), previousPayload).commit()
            throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        }
        if (confirmed.serverIdentity.isNotEmpty()) com.legitimateapps.dulcet.core.AndroidServerIdentity.accept(
            applicationContext, confirmed.id, confirmed.serverIdentity)
        return confirmed
    }

    override fun activeAccountId(): String? = preferences.getString(ACTIVE_ACCOUNT_KEY, null)

    override fun deleteKey(id: String) {
        require(id !in savedAccountIds()) { "a saved account's key is deleted only with its record" }
        try {
            cipher.delete(id)
        } catch (failure: Exception) {
            throw CredentialStoreException(CredentialStoreException.Reason.SecureStorageUnavailable, failure)
        }
    }

    /**
     * Deletes the saved record, then its key. The record's deletion is judged by `commit()`, which
     * reports whether it reached disk — never by the in-memory map, which is updated either way — and
     * fails with [CredentialStoreException.Reason.PersistenceFailed] when it did not. A key that could
     * not be deleted after that fails with [CredentialStoreException.Reason.SecureStorageUnavailable]:
     * the record is gone from disk by then, and the key alone decrypts nothing.
     */
    override fun delete() { activeAccountId()?.let(::remove) }

    override fun remove(id: String) {
        val wasActive = activeAccountId() == id
        val payload = preferences.getString(payloadKey(id), null)
        val edit = preferences.edit().remove(payloadKey(id))
        if (wasActive) {
            val next = (savedAccountIds() - id).sorted().firstOrNull()
            if (next == null) edit.remove(ACTIVE_ACCOUNT_KEY) else edit.putString(ACTIVE_ACCOUNT_KEY, next)
        }
        if (!edit.commit()) {
            // The failed commit still removed the record from the in-memory map. Put it back, so this
            // process goes on seeing the account that is still on disk rather than no account.
            val restore = preferences.edit()
            if (wasActive) restore.putString(ACTIVE_ACCOUNT_KEY, id)
            if (payload != null) restore.putString(payloadKey(id), payload)
            restore.apply()
            throw CredentialStoreException(CredentialStoreException.Reason.PersistenceFailed)
        }
        try {
            cipher.delete(id)
        } catch (failure: Exception) {
            throw CredentialStoreException(
                CredentialStoreException.Reason.SecureStorageUnavailable,
                failure,
            )
        }
    }

    public fun hasActiveAccountPointer(): Boolean = preferences.contains(ACTIVE_ACCOUNT_KEY)

    public fun storedEntryCount(): Int = preferences.all.size

    private fun payloadKey(id: String): String = "account.$id"

    public companion object {
        const val PREFERENCES_NAME = "dulcet.account"
        private const val ACTIVE_ACCOUNT_KEY = "activeAccountId"
    }
}

private class AndroidKeystoreAccountCredentialCipher : AccountCredentialCipher {
    override fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(id))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    override fun decrypt(id: String, payload: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val ivSize = input.readInt()
        require(ivSize in 12..32)
        val iv = ByteArray(ivSize).also(input::readFully)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in 16..MAX_CIPHERTEXT_BYTES)
        val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
        require(input.available() == 0)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey(id), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(id: String): SecretKey {
        val store = keyStore()
        (store.getKey(alias(id), null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias(id),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existingKey(id: String): SecretKey =
        keyStore().getKey(alias(id), null) as? SecretKey
            ?: throw CredentialStoreException(
                CredentialStoreException.Reason.SecureStorageUnavailable,
            )

    override fun delete(id: String) {
        keyStore().deleteEntry(alias(id))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun alias(id: String): String = "com.legitimateapps.dulcet.$id"
    private companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_CIPHERTEXT_BYTES = 1_048_576
    }
}

private object CredentialCodec {
    fun encode(account: StoredAccount): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(2)
            output.writeUTF(account.serverName)
            output.writeUTF(account.serverUrl)
            output.writeUTF(account.username)
            output.writeUTF(account.password)
            output.writeBoolean(account.allowLocalHttp)
            output.writeUTF(account.serverIdentity)
        }
        bytes.toByteArray()
    }

    fun decode(id: String, payload: ByteArray): StoredAccount {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val version = input.readInt()
        require(version in 1..2)
        val serverName = input.readUTF()
        val legacyIdentity = if (serverName.lowercase() in setOf("navidrome", "dulcet", "dulcet server"))
            com.legitimateapps.dulcet.core.ServerIdentityPolicy.identity(serverName) else ""
        val account = StoredAccount(
            id = id,
            serverName = serverName,
            serverUrl = input.readUTF(),
            username = input.readUTF(),
            password = input.readUTF(),
            allowLocalHttp = input.readBoolean(),
            serverIdentity = if (version >= 2) input.readUTF() else legacyIdentity,
        )
        require(input.available() == 0)
        return account
    }
}
