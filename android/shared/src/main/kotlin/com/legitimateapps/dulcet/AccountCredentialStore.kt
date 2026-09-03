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
    fun delete()
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

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredAccount? {
        val id = preferences.getString(ACTIVE_ACCOUNT_KEY, null) ?: return null
        val encrypted = preferences.getString(payloadKey(id), null)
            ?: throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord)
        return try {
            CredentialCodec.decode(
                id,
                cipher.decrypt(id, Base64.decode(encrypted, Base64.NO_WRAP)),
            )
        } catch (failure: CredentialStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw CredentialStoreException(CredentialStoreException.Reason.CorruptRecord, failure)
        }
    }

    override fun save(
        serverName: String,
        serverUrl: String,
        username: String,
        password: String,
        allowLocalHttp: Boolean,
    ): StoredAccount {
        val previousId = preferences.getString(ACTIVE_ACCOUNT_KEY, null)
        val account = StoredAccount(
            id = UUID.randomUUID().toString(),
            serverName = serverName,
            serverUrl = serverUrl,
            username = username,
            password = password,
            allowLocalHttp = allowLocalHttp,
        )
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

            if (previousId != null && previousId != account.id) {
                preferences.edit().remove(payloadKey(previousId)).apply()
                // The new account is already committed, so old-key deletion is best-effort.
                // A stale keystore entry is preferable to destroying the active credential.
                runCatching { cipher.delete(previousId) }
            }
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

    override fun delete() {
        val id = preferences.getString(ACTIVE_ACCOUNT_KEY, null) ?: return
        if (!preferences.edit().remove(payloadKey(id)).remove(ACTIVE_ACCOUNT_KEY).commit()) {
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
            output.writeInt(1)
            output.writeUTF(account.serverName)
            output.writeUTF(account.serverUrl)
            output.writeUTF(account.username)
            output.writeUTF(account.password)
            output.writeBoolean(account.allowLocalHttp)
        }
        bytes.toByteArray()
    }

    fun decode(id: String, payload: ByteArray): StoredAccount {
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == 1)
        val account = StoredAccount(
            id = id,
            serverName = input.readUTF(),
            serverUrl = input.readUTF(),
            username = input.readUTF(),
            password = input.readUTF(),
            allowLocalHttp = input.readBoolean(),
        )
        require(input.available() == 0)
        return account
    }
}
