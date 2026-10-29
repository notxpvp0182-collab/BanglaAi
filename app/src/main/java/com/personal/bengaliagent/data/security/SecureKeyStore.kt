package com.personal.bengaliagent.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.personal.bengaliagent.domain.model.ProviderType
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed AES-256-GCM encryption using AndroidKeyStore.
 * Never logs keys, never backs them up to cloud, and allows immediate deletion.
 */
class SecureKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveApiKey(provider: ProviderType, plainKey: String) {
        val trimmed = plainKey.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey(provider)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString(prefKey(provider), encoded).apply()
    }

    fun getApiKey(provider: ProviderType): String? {
        val encoded = prefs.getString(prefKey(provider), null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = combined[0].toInt() and 0xFF
            val iv = ByteArray(ivSize)
            val cipherText = ByteArray(combined.size - 1 - ivSize)
            System.arraycopy(combined, 1, iv, 0, ivSize)
            System.arraycopy(combined, 1 + ivSize, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteApiKey(provider: ProviderType) {
        prefs.edit()
            .remove(prefKey(provider))
            .remove(verifiedKey(provider))
            .apply()
    }

    fun setProviderVerified(provider: ProviderType, verified: Boolean) {
        prefs.edit().putBoolean(verifiedKey(provider), verified).apply()
    }

    fun isProviderVerified(provider: ProviderType): Boolean {
        return prefs.getBoolean(verifiedKey(provider), false) && !getApiKey(provider).isNullOrBlank()
    }

    private fun prefKey(provider: ProviderType) = "enc_key_${provider.id}"
    private fun verifiedKey(provider: ProviderType) = "verified_${provider.id}"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bengali_agent_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_FILE = "secure_provider_keys"
    }
}
