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

internal data class StoredAccount(
    val id: String,
    val serverName: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    override fun toString(): String = "StoredAccount(id=$id, <redacted>)"
}

internal interface AccountCredentialStore {
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

internal class CredentialStoreException(cause: Throwable? = null) : Exception(cause)

internal class AndroidAccountCredentialStore(context: Context) : AccountCredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredAccount? {
        val id = preferences.getString(ACTIVE_ACCOUNT_KEY, null) ?: return null
        val encrypted = preferences.getString(payloadKey(id), null)
            ?: throw CredentialStoreException()
        return try {
            CredentialCodec.decode(id, decrypt(id, Base64.decode(encrypted, Base64.NO_WRAP)))
        } catch (failure: Exception) {
            throw CredentialStoreException(failure)
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
                encrypt(account.id, CredentialCodec.encode(account)),
                Base64.NO_WRAP,
            )
            val committed = preferences.edit()
                .putString(payloadKey(account.id), encoded)
                .putString(ACTIVE_ACCOUNT_KEY, account.id)
                .commit()
            if (!committed) throw CredentialStoreException()

            if (previousId != null && previousId != account.id) {
                preferences.edit().remove(payloadKey(previousId)).apply()
                deleteKey(previousId)
            }
            account
        } catch (failure: Exception) {
            deleteKey(account.id)
            if (failure is CredentialStoreException) throw failure
            throw CredentialStoreException(failure)
        }
    }

    override fun delete() {
        val id = preferences.getString(ACTIVE_ACCOUNT_KEY, null) ?: return
        if (!preferences.edit().remove(payloadKey(id)).remove(ACTIVE_ACCOUNT_KEY).commit()) {
            throw CredentialStoreException()
        }
        try {
            deleteKey(id)
        } catch (failure: Exception) {
            throw CredentialStoreException(failure)
        }
    }

    private fun encrypt(id: String, plaintext: ByteArray): ByteArray {
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

    private fun decrypt(id: String, payload: ByteArray): ByteArray {
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
        keyStore().getKey(alias(id), null) as? SecretKey ?: throw CredentialStoreException()

    private fun deleteKey(id: String) {
        keyStore().deleteEntry(alias(id))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun alias(id: String): String = "com.legitimateapps.dulcet.$id"
    private fun payloadKey(id: String): String = "account.$id"

    internal companion object {
        const val PREFERENCES_NAME = "dulcet.account"
        private const val ACTIVE_ACCOUNT_KEY = "activeAccountId"
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
