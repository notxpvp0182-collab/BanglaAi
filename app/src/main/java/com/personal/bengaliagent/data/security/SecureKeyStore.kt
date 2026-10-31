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

enum class CredentialRole(val id: String, val label: String) {
    PRIMARY_VOICE("voice", "Voice API Key (Gemini Live)"),
    INTELLIGENCE("intelligence", "Intelligence & Reasoning Key"),
    VISION("vision", "Vision & Screen Key"),
    BACKUP("backup", "Backup Failover Key")
}

/**
 * Hardware-backed AES-256-GCM credential manager supporting multiple independent API keys
 * per provider (Voice, Intelligence, Vision, Backup) with safe rotation.
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

    fun saveApiKey(
        provider: ProviderType,
        plainKey: String,
        role: CredentialRole = CredentialRole.PRIMARY_VOICE
    ) {
        val trimmed = plainKey.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey(provider, role)
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
        prefs.edit().putString(prefKey(provider, role), encoded).apply()
    }

    fun getApiKey(
        provider: ProviderType,
        role: CredentialRole = CredentialRole.PRIMARY_VOICE
    ): String? {
        val encoded = prefs.getString(prefKey(provider, role), null) ?: return null
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

    /**
     * Resolves the ordered list of distinct valid keys for a specific task role:
     * Role-Specific Key -> Primary Voice Key -> Backup Key.
     */
    fun getKeysForRoleWithBackup(
        provider: ProviderType,
        preferredRole: CredentialRole
    ): List<String> {
        val candidates = listOfNotNull(
            getApiKey(provider, preferredRole)?.takeIf { it.isNotBlank() },
            getApiKey(provider, CredentialRole.PRIMARY_VOICE)?.takeIf { it.isNotBlank() },
            getApiKey(provider, CredentialRole.INTELLIGENCE)?.takeIf { it.isNotBlank() },
            getApiKey(provider, CredentialRole.VISION)?.takeIf { it.isNotBlank() },
            getApiKey(provider, CredentialRole.BACKUP)?.takeIf { it.isNotBlank() }
        )
        return candidates.distinct()
    }

    fun deleteApiKey(provider: ProviderType, role: CredentialRole? = null) {
        val editor = prefs.edit()
        if (role == null) {
            for (r in CredentialRole.entries) {
                editor.remove(prefKey(provider, r))
            }
            editor.remove(verifiedKey(provider))
        } else {
            editor.remove(prefKey(provider, role))
            if (role == CredentialRole.PRIMARY_VOICE &&
                getKeysForRoleWithBackup(provider, CredentialRole.BACKUP).isEmpty()
            ) {
                editor.remove(verifiedKey(provider))
            }
        }
        editor.apply()
    }

    fun setProviderVerified(provider: ProviderType, verified: Boolean) {
        prefs.edit().putBoolean(verifiedKey(provider), verified).apply()
    }

    fun isProviderVerified(provider: ProviderType): Boolean {
        return prefs.getBoolean(verifiedKey(provider), false) &&
            getKeysForRoleWithBackup(provider, CredentialRole.PRIMARY_VOICE).isNotEmpty()
    }

    private fun prefKey(provider: ProviderType, role: CredentialRole): String =
        if (role == CredentialRole.PRIMARY_VOICE) {
            "enc_key_${provider.id}"
        } else {
            "enc_key_${provider.id}_${role.id}"
        }

    private fun verifiedKey(provider: ProviderType) = "verified_${provider.id}"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bengali_agent_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_FILE = "secure_provider_keys"
    }
}
