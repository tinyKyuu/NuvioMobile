package com.nuvio.app.features.watchtogether.hosted

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object WatchTogetherPlatformSecurity : WatchTogetherCredentialStore {
    private const val PREFERENCES_NAME = "nuvio_watch_together_credentials"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "com.nuvio.media.watchtogether.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    override fun load(key: String): String? {
        val stored = preferencesOrThrow().getString(key, null) ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { preferencesOrThrow().edit().remove(key).apply() }
            .getOrNull()
    }

    override fun save(key: String, value: String) {
        preferencesOrThrow().edit().putString(key, encrypt(value)).apply()
    }

    override fun delete(key: String) {
        preferencesOrThrow().edit().remove(key).apply()
    }

    private fun preferencesOrThrow(): SharedPreferences =
        checkNotNull(preferences) { "Watch Together security storage is not initialized" }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        return "${cipher.iv.toBase64()}.${ciphertext.toBase64()}"
    }

    private fun decrypt(value: String): String {
        val separator = value.indexOf('.')
        require(separator > 0 && separator < value.lastIndex) { "Invalid encrypted credential" }
        val iv = value.substring(0, separator).fromBase64()
        val ciphertext = value.substring(separator + 1).fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
