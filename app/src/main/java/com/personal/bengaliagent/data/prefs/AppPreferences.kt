package com.personal.bengaliagent.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.bengaliagent.domain.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "agent_settings")

class AppPreferences(private val context: Context) {

    val isSetupCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETED] ?: false
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_SETUP_COMPLETED] = completed }
    }

    suspend fun getPreferredProvider(): ProviderType {
        val prefs = context.dataStore.data.first()
        return ProviderType.fromId(prefs[KEY_PREFERRED_PROVIDER] ?: ProviderType.GEMINI.id)
    }

    suspend fun setPreferredProvider(provider: ProviderType) {
        context.dataStore.edit { it[KEY_PREFERRED_PROVIDER] = provider.id }
    }

    suspend fun getVerifiedModel(provider: ProviderType): String? {
        val prefs = context.dataStore.data.first()
        return prefs[modelKey(provider)]?.takeIf { it.isNotBlank() }
    }

    suspend fun setVerifiedModel(provider: ProviderType, modelName: String?) {
        context.dataStore.edit {
            if (modelName.isNullOrBlank()) {
                it.remove(modelKey(provider))
            } else {
                it[modelKey(provider)] = modelName.trim()
            }
        }
    }

    suspend fun getLastConnectionMessage(): String {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_LAST_CONNECTION_MSG] ?: "Not connected"
    }

    suspend fun setLastConnectionMessage(msg: String) {
        context.dataStore.edit { it[KEY_LAST_CONNECTION_MSG] = msg }
    }

    suspend fun isCloudSharingConsented(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_CLOUD_CONSENT] ?: true
    }

    suspend fun setCloudSharingConsented(consented: Boolean) {
        context.dataStore.edit { it[KEY_CLOUD_CONSENT] = consented }
    }

    suspend fun isAiCodingCapabilityEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_AI_CODING_ENABLED] ?: true
    }

    suspend fun setAiCodingCapabilityEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_CODING_ENABLED] = enabled }
    }

    suspend fun isAiVideoEditCapabilityEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_AI_VIDEO_EDIT_ENABLED] ?: true
    }

    suspend fun setAiVideoEditCapabilityEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_VIDEO_EDIT_ENABLED] = enabled }
    }

    suspend fun getVoiceLanguageTag(): String {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_VOICE_LANG] ?: "bn-BD"
    }

    suspend fun setVoiceLanguageTag(tag: String) {
        context.dataStore.edit { it[KEY_VOICE_LANG] = tag }
    }

    suspend fun isTtsAutoSpeakEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_TTS_AUTO_SPEAK] ?: true
    }

    suspend fun setTtsAutoSpeakEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TTS_AUTO_SPEAK] = enabled }
    }

    suspend fun isSpeakerDetectionEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_SPEAKER_DETECTION_ENABLED] ?: false
    }

    suspend fun setSpeakerDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SPEAKER_DETECTION_ENABLED] = enabled }
    }

    suspend fun getEnrolledSpeakerEmbedding(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_SPEAKER_EMBEDDING]
    }

    suspend fun setEnrolledSpeakerEmbedding(serialized: String?) {
        context.dataStore.edit {
            if (serialized.isNullOrBlank()) {
                it.remove(KEY_SPEAKER_EMBEDDING)
            } else {
                it[KEY_SPEAKER_EMBEDDING] = serialized
            }
        }
    }

    suspend fun getMemoryRetentionDays(): Int {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_MEMORY_RETENTION_DAYS] ?: 90
    }

    suspend fun setMemoryRetentionDays(days: Int) {
        context.dataStore.edit { it[KEY_MEMORY_RETENTION_DAYS] = days }
    }

    private fun modelKey(provider: ProviderType) = stringPreferencesKey("verified_model_${provider.id}")

    companion object {
        private val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val KEY_PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
        private val KEY_LAST_CONNECTION_MSG = stringPreferencesKey("last_connection_msg")
        private val KEY_CLOUD_CONSENT = booleanPreferencesKey("cloud_consent")
        private val KEY_AI_CODING_ENABLED = booleanPreferencesKey("ai_coding_capability_enabled")
        private val KEY_AI_VIDEO_EDIT_ENABLED = booleanPreferencesKey("ai_video_edit_capability_enabled")
        private val KEY_VOICE_LANG = stringPreferencesKey("voice_lang_tag")
        private val KEY_TTS_AUTO_SPEAK = booleanPreferencesKey("tts_auto_speak")
        private val KEY_SPEAKER_DETECTION_ENABLED = booleanPreferencesKey("speaker_detection_enabled")
        private val KEY_SPEAKER_EMBEDDING = stringPreferencesKey("speaker_embedding_vec")
        private val KEY_MEMORY_RETENTION_DAYS = intPreferencesKey("memory_retention_days")
    }
}
