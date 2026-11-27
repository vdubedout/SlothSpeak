package com.slothspeak.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.slothspeak.config.SystemPrompts

class ApiKeyManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun getKey(): String? {
        return prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun deleteKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasKey(): Boolean {
        return getKey() != null
    }

    fun getSelectedModel(): String {
        val stored = prefs.getString(KEY_MODEL, MODEL_ASTRA)
        return migrateLegacyModelId(stored) ?: MODEL_ASTRA
    }

    fun setSelectedModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun getReasoningEffort(): String {
        return prefs.getString(KEY_REASONING_EFFORT, EFFORT_HIGH) ?: EFFORT_HIGH
    }

    fun setReasoningEffort(effort: String) {
        prefs.edit().putString(KEY_REASONING_EFFORT, effort).apply()
    }

    fun getSelectedVoice(): String {
        return prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
    }

    fun setSelectedVoice(voice: String) {
        prefs.edit().putString(KEY_VOICE, voice).apply()
    }

    fun getPingBeforeReading(): Boolean {
        return prefs.getBoolean(KEY_PING_BEFORE_READING, false)
    }

    fun setPingBeforeReading(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PING_BEFORE_READING, enabled).apply()
    }

    fun getXSearchEnabled(): Boolean {
        return prefs.getBoolean(KEY_XSEARCH_ENABLED, false)
    }

    fun setXSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_XSEARCH_ENABLED, enabled).apply()
    }

    fun getPlaybackSpeed(): Float {
        return prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
    }

    fun setPlaybackSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).apply()
    }

    // Gemini API key methods
    fun saveGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getGeminiKey(): String? {
        return prefs.getString(KEY_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun deleteGeminiKey() {
        prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    fun hasGeminiKey(): Boolean {
        return getGeminiKey() != null
    }

    fun getThinkingLevel(): String {
        return prefs.getString(KEY_THINKING_LEVEL, THINKING_HIGH) ?: THINKING_HIGH
    }

    fun setThinkingLevel(level: String) {
        prefs.edit().putString(KEY_THINKING_LEVEL, level).apply()
    }

    // Claude API key methods
    fun saveClaudeKey(key: String) {
        prefs.edit().putString(KEY_CLAUDE_API_KEY, key.trim()).apply()
    }

    fun getClaudeKey(): String? {
        return prefs.getString(KEY_CLAUDE_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun deleteClaudeKey() {
        prefs.edit().remove(KEY_CLAUDE_API_KEY).apply()
    }

    fun hasClaudeKey(): Boolean {
        return getClaudeKey() != null
    }

    fun getClaudeEffort(): String {
        return prefs.getString(KEY_CLAUDE_EFFORT, CLAUDE_EFFORT_HIGH) ?: CLAUDE_EFFORT_HIGH
    }

    fun setClaudeEffort(effort: String) {
        prefs.edit().putString(KEY_CLAUDE_EFFORT, effort).apply()
    }

    // Grok (xAI) API key methods
    fun saveGrokKey(key: String) {
        prefs.edit().putString(KEY_GROK_API_KEY, key.trim()).apply()
    }

    fun getGrokKey(): String? {
        return prefs.getString(KEY_GROK_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun deleteGrokKey() {
        prefs.edit().remove(KEY_GROK_API_KEY).apply()
    }

    fun hasGrokKey(): Boolean {
        return getGrokKey() != null
    }

    // ============================================================
    // Built-in LLM provider model selection
    // ============================================================

    fun getBuiltinModel(provider: String): String? {
        return migrateLegacyModelId(prefs.getString(builtinModelKey(provider), null))
            ?.takeIf { it.isNotBlank() }
    }

    fun setBuiltinModel(provider: String, model: String) {
        prefs.edit().putString(builtinModelKey(provider), model).apply()
    }

    fun hasBuiltinLlmKey(provider: String): Boolean {
        return when (provider) {
            LLM_PROVIDER_OPENAI -> hasKey()
            LLM_PROVIDER_GEMINI -> hasGeminiKey()
            LLM_PROVIDER_CLAUDE -> hasClaudeKey()
            LLM_PROVIDER_GROK -> hasGrokKey()
            else -> false
        }
    }

    fun saveBuiltinLlmKey(provider: String, key: String) {
        when (provider) {
            LLM_PROVIDER_OPENAI -> saveKey(key)
            LLM_PROVIDER_GEMINI -> saveGeminiKey(key)
            LLM_PROVIDER_CLAUDE -> saveClaudeKey(key)
            LLM_PROVIDER_GROK -> saveGrokKey(key)
        }
    }

    fun deleteBuiltinLlmKey(provider: String) {
        when (provider) {
            LLM_PROVIDER_OPENAI -> deleteKey()
            LLM_PROVIDER_GEMINI -> deleteGeminiKey()
            LLM_PROVIDER_CLAUDE -> deleteClaudeKey()
            LLM_PROVIDER_GROK -> deleteGrokKey()
        }
    }

    // Compat provider selection
    fun getSelectedCompatProviderId(): String? {
        return prefs.getString(KEY_SELECTED_COMPAT_PROVIDER, null)?.takeIf { it.isNotBlank() }
    }

    fun setSelectedCompatProviderId(id: String?) {
        if (id != null) {
            prefs.edit().putString(KEY_SELECTED_COMPAT_PROVIDER, id).apply()
        } else {
            prefs.edit().remove(KEY_SELECTED_COMPAT_PROVIDER).apply()
        }
    }

    // System prompt methods
    fun getSystemPrompt(): String {
        return prefs.getString(KEY_SYSTEM_PROMPT, null) ?: SystemPrompts.SYSTEM_PROMPT
    }

    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
    }

    fun resetSystemPrompt() {
        prefs.edit().remove(KEY_SYSTEM_PROMPT).apply()
    }

    fun getDeepResearchSystemPrompt(): String {
        return prefs.getString(KEY_DEEP_RESEARCH_SYSTEM_PROMPT, null) ?: SystemPrompts.DEEP_RESEARCH_SYSTEM_PROMPT
    }

    fun setDeepResearchSystemPrompt(prompt: String) {
        prefs.edit().putString(KEY_DEEP_RESEARCH_SYSTEM_PROMPT, prompt).apply()
    }

    fun resetDeepResearchSystemPrompt() {
        prefs.edit().remove(KEY_DEEP_RESEARCH_SYSTEM_PROMPT).apply()
    }

    // Interactive voice mode methods
    fun getInteractiveVoiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_INTERACTIVE_VOICE, false)
    }

    fun setInteractiveVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERACTIVE_VOICE, enabled).apply()
    }

    fun getSilenceTimeoutMs(): Int {
        return prefs.getInt(KEY_SILENCE_TIMEOUT_MS, DEFAULT_SILENCE_TIMEOUT_MS)
    }

    fun setSilenceTimeoutMs(ms: Int) {
        prefs.edit().putInt(KEY_SILENCE_TIMEOUT_MS, ms.coerceIn(1000, 10000)).apply()
    }

    // TTS instructions methods
    fun getTtsInstructions(): String {
        return prefs.getString(KEY_TTS_INSTRUCTIONS, null) ?: DEFAULT_TTS_INSTRUCTIONS
    }

    fun setTtsInstructions(instructions: String) {
        prefs.edit().putString(KEY_TTS_INSTRUCTIONS, instructions).apply()
    }

    fun resetTtsInstructions() {
        prefs.edit().remove(KEY_TTS_INSTRUCTIONS).apply()
    }

    // ============================================================
    // STT (Speech-to-Text) Provider Config
    // ============================================================

    fun getSttProvider(): String {
        return prefs.getString(KEY_STT_PROVIDER, STT_PROVIDER_OPENAI) ?: STT_PROVIDER_OPENAI
    }

    fun setSttProvider(id: String) {
        prefs.edit().putString(KEY_STT_PROVIDER, id).apply()
    }

    fun getSttApiKey(): String? {
        return prefs.getString(KEY_STT_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun saveSttApiKey(key: String) {
        prefs.edit().putString(KEY_STT_API_KEY, key.trim()).apply()
    }

    fun deleteSttApiKey() {
        prefs.edit().remove(KEY_STT_API_KEY).apply()
    }

    fun hasSttApiKey(): Boolean {
        return getSttApiKey() != null
    }

    fun getSttModel(): String {
        return getSttModelForProvider(getSttProvider())
    }

    fun getSttModelForProvider(provider: String): String {
        val defaultModel = defaultSttModel(provider)
        return prefs.getString(sttModelKey(provider), defaultModel)?.takeIf { it.isNotBlank() }
            ?: defaultModel
    }

    fun setSttModel(model: String) {
        val provider = getSttProvider()
        prefs.edit().putString(sttModelKey(provider), model).apply()
    }

    fun getSttBaseUrl(): String {
        return getSttBaseUrlForProvider(getSttProvider())
    }

    fun getSttBaseUrlForProvider(provider: String): String {
        if (provider != STT_PROVIDER_CUSTOM) return defaultSttBaseUrl(provider)
        return prefs.getString(KEY_STT_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: ""
    }

    fun setSttBaseUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        prefs.edit().putString(KEY_STT_BASE_URL, normalized).apply()
    }

    fun getEffectiveSttConfig(): SttConfig {
        return resolveSttConfig(
            providerId = getSttProvider(),
            openaiApiKey = getKey(),
            sttApiKey = getSttApiKey(),
            sttModel = getSttModel(),
            sttBaseUrl = getSttBaseUrl()
        )
    }

    /**
     * Language for the on-device FUTO/Nemotron provider. Passed per stream,
     * so switching languages does not reload the model.
     */
    fun getFutoSttLanguage(): String {
        return prefs.getString(KEY_FUTO_STT_LANGUAGE, FutoSttLanguage.AUTO)
            ?: FutoSttLanguage.AUTO
    }

    fun setFutoSttLanguage(language: String) {
        prefs.edit().putString(KEY_FUTO_STT_LANGUAGE, language).apply()
    }

    data class SttConfig(
        val baseUrl: String,
        val apiKey: String?,
        val model: String,
        val providerLabel: String,
        val providerId: String = STT_PROVIDER_OPENAI,
        val onDevice: Boolean = false
    )

    // ============================================================
    // TTS (Text-to-Speech) Provider Config
    // ============================================================

    fun getTtsProvider(): String {
        return prefs.getString(KEY_TTS_PROVIDER, TTS_PROVIDER_OPENAI) ?: TTS_PROVIDER_OPENAI
    }

    fun setTtsProvider(id: String) {
        prefs.edit().putString(KEY_TTS_PROVIDER, id).apply()
    }

    fun getTtsModel(): String {
        return getTtsModelForProvider(getTtsProvider())
    }

    fun getTtsModelForProvider(provider: String): String {
        val defaultModel = defaultTtsModel(provider)
        return prefs.getString(ttsModelKey(provider), defaultModel)?.takeIf { it.isNotBlank() }
            ?: defaultModel
    }

    fun setTtsModel(model: String) {
        val provider = getTtsProvider()
        prefs.edit().putString(ttsModelKey(provider), model).apply()
    }

    fun getTtsVoice(): String {
        return getTtsVoiceForProvider(getTtsProvider())
    }

    fun getTtsVoiceForProvider(provider: String): String {
        val defaultVoice = defaultTtsVoice(provider)
        return prefs.getString(ttsVoiceKey(provider), defaultVoice)?.takeIf { it.isNotBlank() }
            ?: defaultVoice
    }

    fun setTtsVoice(voice: String) {
        val provider = getTtsProvider()
        prefs.edit().putString(ttsVoiceKey(provider), voice).apply()
    }

    fun getTtsCustomApiKey(): String? {
        return prefs.getString(KEY_TTS_CUSTOM_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun saveTtsCustomApiKey(key: String) {
        prefs.edit().putString(KEY_TTS_CUSTOM_API_KEY, key.trim()).apply()
    }

    fun deleteTtsCustomApiKey() {
        prefs.edit().remove(KEY_TTS_CUSTOM_API_KEY).apply()
    }

    fun getTtsBaseUrl(): String {
        return getTtsBaseUrlForProvider(getTtsProvider())
    }

    fun getTtsBaseUrlForProvider(provider: String): String {
        if (provider != TTS_PROVIDER_CUSTOM) return defaultTtsBaseUrl(provider)
        return prefs.getString(KEY_TTS_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: ""
    }

    fun setTtsBaseUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        prefs.edit().putString(KEY_TTS_BASE_URL, normalized).apply()
    }

    // Wake word settings
    fun getWakeWordEnabled(): Boolean {
        return prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun isTtsConfigured(): Boolean {
        return when (getTtsProvider()) {
            TTS_PROVIDER_OPENAI -> hasKey()
            TTS_PROVIDER_TOGETHER -> hasSttApiKey() // reuses Together STT key
            TTS_PROVIDER_CUSTOM -> getTtsBaseUrl().isNotBlank() && getTtsModel().isNotBlank()
            TTS_PROVIDER_ONDEVICE -> true
            else -> hasKey()
        }
    }

    fun getEffectiveTtsConfig(): TtsConfig {
        return resolveTtsConfig(
            providerId = getTtsProvider(),
            openaiApiKey = getKey(),
            togetherApiKey = getSttApiKey(),  // reuses Together STT key
            customApiKey = getTtsCustomApiKey(),
            ttsModel = getTtsModel(),
            ttsVoice = getTtsVoice(),
            ttsBaseUrl = getTtsBaseUrl(),
            selectedVoice = getSelectedVoice()
        )
    }

    data class TtsConfig(
        val provider: String,
        val baseUrl: String?,
        val apiKey: String?,
        val model: String,
        val voice: String
    )

    // Follow-up prompt methods
    fun getFollowUpPrompt(): String {
        return prefs.getString(KEY_FOLLOW_UP_PROMPT, null) ?: SystemPrompts.FOLLOW_UP_PROMPT
    }

    fun setFollowUpPrompt(prompt: String) {
        prefs.edit().putString(KEY_FOLLOW_UP_PROMPT, prompt).apply()
    }

    fun resetFollowUpPrompt() {
        prefs.edit().remove(KEY_FOLLOW_UP_PROMPT).apply()
    }

    companion object {
        private const val PREFS_FILE = "slothspeak_secure_prefs"
        private const val KEY_API_KEY = "openai_api_key"

        // STT provider constants
        const val STT_PROVIDER_OPENAI = "openai"
        const val STT_PROVIDER_TOGETHER = "together"
        const val STT_PROVIDER_CUSTOM = "custom"
        const val STT_PROVIDER_FUTO = "futo"

        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_STT_API_KEY = "stt_api_key"
        private const val KEY_STT_BASE_URL = "stt_base_url"
        private const val KEY_STT_MODEL_PREFIX = "stt_model_"
        private const val KEY_FUTO_STT_LANGUAGE = "futo_stt_language"

        const val OPENAI_BASE_URL = "https://api.openai.com/v1/"
        const val TOGETHER_BASE_URL = "https://api.together.ai/v1/"

        const val DEFAULT_STT_MODEL_OPENAI = "gpt-4o-transcribe"
        const val DEFAULT_STT_MODEL_TOGETHER = "openai/whisper-large-v3"
        const val DEFAULT_STT_MODEL_FUTO = FutoSttModel.ID

        private fun sttModelKey(provider: String): String = "${KEY_STT_MODEL_PREFIX}$provider"

        private fun defaultSttModel(provider: String): String = when (provider) {
            STT_PROVIDER_OPENAI -> DEFAULT_STT_MODEL_OPENAI
            STT_PROVIDER_TOGETHER -> DEFAULT_STT_MODEL_TOGETHER
            STT_PROVIDER_FUTO -> DEFAULT_STT_MODEL_FUTO
            STT_PROVIDER_CUSTOM -> ""
            else -> DEFAULT_STT_MODEL_OPENAI
        }

        private fun defaultSttBaseUrl(provider: String): String = when (provider) {
            STT_PROVIDER_OPENAI -> OPENAI_BASE_URL
            STT_PROVIDER_TOGETHER -> TOGETHER_BASE_URL
            STT_PROVIDER_FUTO -> ""
            STT_PROVIDER_CUSTOM -> ""
            else -> OPENAI_BASE_URL
        }

        // TTS provider constants
        const val TTS_PROVIDER_OPENAI = "openai"
        const val TTS_PROVIDER_TOGETHER = "together"
        const val TTS_PROVIDER_CUSTOM = "custom"
        const val TTS_PROVIDER_ONDEVICE = "ondevice"

        private const val KEY_TTS_PROVIDER = "tts_provider"
        private const val KEY_TTS_BASE_URL = "tts_base_url"
        private const val KEY_TTS_CUSTOM_API_KEY = "tts_custom_api_key"
        private const val KEY_TTS_MODEL_PREFIX = "tts_model_"
        private const val KEY_TTS_VOICE_PREFIX = "tts_voice_"

        const val DEFAULT_TTS_MODEL_OPENAI = "gpt-4o-mini-tts"
        const val DEFAULT_TTS_MODEL_TOGETHER = "canopylabs/orpheus-3b-0.1-ft"
        const val DEFAULT_TTS_VOICE_OPENAI = "marin"
        const val DEFAULT_TTS_VOICE_TOGETHER = "tara"

        private fun ttsModelKey(provider: String): String = "${KEY_TTS_MODEL_PREFIX}$provider"
        private fun ttsVoiceKey(provider: String): String = "${KEY_TTS_VOICE_PREFIX}$provider"

        private fun defaultTtsModel(provider: String): String = when (provider) {
            TTS_PROVIDER_OPENAI -> DEFAULT_TTS_MODEL_OPENAI
            TTS_PROVIDER_TOGETHER -> DEFAULT_TTS_MODEL_TOGETHER
            TTS_PROVIDER_CUSTOM -> ""
            TTS_PROVIDER_ONDEVICE -> ""
            else -> DEFAULT_TTS_MODEL_OPENAI
        }

        private fun defaultTtsVoice(provider: String): String = when (provider) {
            TTS_PROVIDER_OPENAI -> DEFAULT_TTS_VOICE_OPENAI
            TTS_PROVIDER_TOGETHER -> DEFAULT_TTS_VOICE_TOGETHER
            TTS_PROVIDER_CUSTOM -> ""
            TTS_PROVIDER_ONDEVICE -> ""
            else -> DEFAULT_TTS_VOICE_OPENAI
        }

        private fun defaultTtsBaseUrl(provider: String): String = when (provider) {
            TTS_PROVIDER_OPENAI -> OPENAI_BASE_URL
            TTS_PROVIDER_TOGETHER -> TOGETHER_BASE_URL
            TTS_PROVIDER_CUSTOM -> ""
            TTS_PROVIDER_ONDEVICE -> ""
            else -> OPENAI_BASE_URL
        }

        /**
         * Pure TTS config resolver — no SharedPreferences dependency, fully testable.
         */
        fun resolveTtsConfig(
            providerId: String,
            openaiApiKey: String?,
            togetherApiKey: String?,
            customApiKey: String?,
            ttsModel: String,
            ttsVoice: String,
            ttsBaseUrl: String,
            selectedVoice: String? = null
        ): TtsConfig {
            return when (providerId) {
                TTS_PROVIDER_OPENAI -> TtsConfig(
                    provider = TTS_PROVIDER_OPENAI,
                    baseUrl = OPENAI_BASE_URL,
                    apiKey = openaiApiKey,
                    model = ttsModel.ifBlank { DEFAULT_TTS_MODEL_OPENAI },
                    voice = selectedVoice?.takeIf { it.isNotBlank() } ?: ttsVoice.ifBlank { DEFAULT_TTS_VOICE_OPENAI }
                )
                TTS_PROVIDER_TOGETHER -> TtsConfig(
                    provider = TTS_PROVIDER_TOGETHER,
                    baseUrl = TOGETHER_BASE_URL,
                    apiKey = togetherApiKey?.takeIf { it.isNotBlank() },
                    model = ttsModel.ifBlank { DEFAULT_TTS_MODEL_TOGETHER },
                    voice = ttsVoice.ifBlank { DEFAULT_TTS_VOICE_TOGETHER }
                )
                TTS_PROVIDER_CUSTOM -> TtsConfig(
                    provider = TTS_PROVIDER_CUSTOM,
                    baseUrl = if (ttsBaseUrl.endsWith("/")) ttsBaseUrl else "$ttsBaseUrl/",
                    apiKey = customApiKey?.takeIf { it.isNotBlank() },
                    model = ttsModel,
                    voice = ttsVoice
                )
                TTS_PROVIDER_ONDEVICE -> TtsConfig(
                    provider = TTS_PROVIDER_ONDEVICE,
                    baseUrl = null,
                    apiKey = null,
                    model = "",
                    voice = ""
                )
                else -> TtsConfig(
                    provider = TTS_PROVIDER_OPENAI,
                    baseUrl = OPENAI_BASE_URL,
                    apiKey = openaiApiKey,
                    model = ttsModel.ifBlank { DEFAULT_TTS_MODEL_OPENAI },
                    voice = selectedVoice?.takeIf { it.isNotBlank() } ?: ttsVoice.ifBlank { DEFAULT_TTS_VOICE_OPENAI }
                )
            }
        }

        /**
         * Pure STT config resolver — no SharedPreferences dependency, fully testable.
         *
         * @param providerId one of STT_PROVIDER_OPENAI / STT_PROVIDER_TOGETHER / STT_PROVIDER_CUSTOM
         * @param openaiApiKey the main OpenAI key (from getKey()) — used only by openai preset
         * @param sttApiKey the STT-specific API key (from getSttApiKey()) — used by together/custom
         * @param sttModel the model string for the current provider (from getSttModel())
         * @param sttBaseUrl the base URL for custom provider (from getSttBaseUrl())
         */
        fun resolveSttConfig(
            providerId: String,
            openaiApiKey: String?,
            sttApiKey: String?,
            sttModel: String,
            sttBaseUrl: String
        ): SttConfig {
            return when (providerId) {
                STT_PROVIDER_OPENAI -> SttConfig(
                    baseUrl = OPENAI_BASE_URL,
                    apiKey = openaiApiKey,
                    model = sttModel.ifBlank { DEFAULT_STT_MODEL_OPENAI },
                    providerLabel = "OpenAI",
                    providerId = STT_PROVIDER_OPENAI
                )
                STT_PROVIDER_TOGETHER -> SttConfig(
                    baseUrl = TOGETHER_BASE_URL,
                    apiKey = sttApiKey?.takeIf { it.isNotBlank() },
                    model = sttModel.ifBlank { DEFAULT_STT_MODEL_TOGETHER },
                    providerLabel = "Together",
                    providerId = STT_PROVIDER_TOGETHER
                )
                STT_PROVIDER_FUTO -> SttConfig(
                    baseUrl = "",
                    apiKey = null,
                    model = DEFAULT_STT_MODEL_FUTO,
                    providerLabel = "FUTO",
                    providerId = STT_PROVIDER_FUTO,
                    onDevice = true
                )
                STT_PROVIDER_CUSTOM -> SttConfig(
                    baseUrl = sttBaseUrl.ifBlank { "" },
                    apiKey = sttApiKey?.takeIf { it.isNotBlank() },
                    model = sttModel,
                    providerLabel = "Custom",
                    providerId = STT_PROVIDER_CUSTOM
                )
                else -> SttConfig(
                    baseUrl = OPENAI_BASE_URL,
                    apiKey = openaiApiKey,
                    model = sttModel.ifBlank { DEFAULT_STT_MODEL_OPENAI },
                    providerLabel = "OpenAI",
                    providerId = STT_PROVIDER_OPENAI
                )
            }
        }
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CLAUDE_API_KEY = "claude_api_key"
        private const val KEY_CLAUDE_EFFORT = "claude_effort"
        private const val KEY_GROK_API_KEY = "grok_api_key"
        private const val KEY_SELECTED_COMPAT_PROVIDER = "selected_compat_provider_id"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"

        // Built-in LLM provider ids (used for per-provider model prefs and key dispatch)
        const val LLM_PROVIDER_OPENAI = "openai"
        const val LLM_PROVIDER_GEMINI = "gemini"
        const val LLM_PROVIDER_CLAUDE = "claude"
        const val LLM_PROVIDER_GROK = "grok"

        private const val KEY_BUILTIN_MODEL_PREFIX = "llm_model_"

        private fun builtinModelKey(provider: String): String = "$KEY_BUILTIN_MODEL_PREFIX$provider"

        // Built-in provider model lists: the latest flagship families. Order matters —
        // the first entry of each list is the default when the provider is activated.
        val OPENAI_MODELS = listOf(MODEL_ASTRA, MODEL_SOL, MODEL_TERRA, MODEL_LUNA)
        val GEMINI_MODELS = listOf(
            MODEL_GEMINI_CARBON,
            MODEL_GEMINI_NEON,
            MODEL_GEMINI_HYDROGEN,
            MODEL_GEMINI_ARGON,
            // Deep Research stays selectable, but exposes no thinking levels.
            MODEL_GEMINI_DEEP_RESEARCH
        )
        val CLAUDE_MODELS = listOf(
            MODEL_CLAUDE_OPUS,
            MODEL_CLAUDE_FABLE,
            MODEL_CLAUDE_SONNET,
            MODEL_CLAUDE_HAIKU
        )
        val GROK_MODELS = listOf(MODEL_GROK)

        fun modelsForBuiltin(provider: String): List<String> = when (provider) {
            LLM_PROVIDER_OPENAI -> OPENAI_MODELS
            LLM_PROVIDER_GEMINI -> GEMINI_MODELS
            LLM_PROVIDER_CLAUDE -> CLAUDE_MODELS
            LLM_PROVIDER_GROK -> GROK_MODELS
            else -> emptyList()
        }

        fun defaultBuiltinModel(provider: String): String =
            modelsForBuiltin(provider).firstOrNull() ?: MODEL_ASTRA

        // --- Family-aware model checks (issue #16) ---
        // Used instead of equality checks so every new model id routes to the right client.

        fun isOpenAiModel(model: String): Boolean = model in OPENAI_MODELS

        /** Chat models in the Gemini family; the deep-research agent has its own branch. */
        fun isGeminiModel(model: String): Boolean =
            model in GEMINI_MODELS && model != MODEL_GEMINI_DEEP_RESEARCH

        fun isClaudeModel(model: String): Boolean = model in CLAUDE_MODELS

        fun isGrokModel(model: String): Boolean = model in GROK_MODELS

        fun isDeepResearchModel(model: String): Boolean =
            model == MODEL_DEEP_RESEARCH || model == MODEL_GEMINI_DEEP_RESEARCH

        /**
         * Whether the model exposes OpenAI reasoning-effort levels. The deep-research
         * model is intentionally excluded: it always runs at its own fixed effort.
         */
        fun supportsOpenAiReasoningEffort(model: String): Boolean = model in OPENAI_MODELS

        /** Whether the model exposes Gemini thinking levels (not the deep-research agent). */
        fun supportsGeminiThinking(model: String): Boolean = isGeminiModel(model)

        /**
         * Model ids that older installs may still have persisted map to the current default
         * of their family, so an app update never routes a stored Claude/Gemini/Grok choice
         * through the wrong provider client. Deep-research ids are left untouched.
         */
        fun migrateLegacyModelId(model: String?): String? = when (model) {
            null -> null
            "gpt-5.5-pro", "gpt-5.5" -> MODEL_ASTRA
            "gemini-3.1-pro-preview", "gemini-3.5-pro-preview" -> MODEL_GEMINI_CARBON
            "claude-opus-4-8" -> MODEL_CLAUDE_OPUS
            "grok-4.3" -> MODEL_GROK
            else -> model
        }

        fun displayNameForBuiltin(provider: String): String = when (provider) {
            LLM_PROVIDER_OPENAI -> "OpenAI"
            LLM_PROVIDER_GEMINI -> "Gemini"
            LLM_PROVIDER_CLAUDE -> "Claude"
            LLM_PROVIDER_GROK -> "xAI Grok"
            else -> provider
        }
        private const val KEY_THINKING_LEVEL = "gemini_thinking_level"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_PING_BEFORE_READING = "ping_before_reading"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_XSEARCH_ENABLED = "xsearch_enabled"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_DEEP_RESEARCH_SYSTEM_PROMPT = "deep_research_system_prompt"
        private const val KEY_TTS_INSTRUCTIONS = "tts_instructions"
        private const val KEY_FOLLOW_UP_PROMPT = "follow_up_prompt"
        private const val KEY_INTERACTIVE_VOICE = "interactive_voice_enabled"
        private const val KEY_SILENCE_TIMEOUT_MS = "silence_timeout_ms"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val DEFAULT_SILENCE_TIMEOUT_MS = 2000
        const val DEFAULT_VOICE = "marin"
        val VOICES = listOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable",
            "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar"
        )
        const val MODEL_ASTRA = "gpt-6.2-astra"
        const val MODEL_SOL = "gpt-6.2-sol"
        const val MODEL_TERRA = "gpt-6.1-terra"
        const val MODEL_LUNA = "gpt-6-luna"
        const val MODEL_GEMINI_CARBON = "gemini-4-carbon"
        const val MODEL_GEMINI_NEON = "gemini-4-neon"
        const val MODEL_GEMINI_HYDROGEN = "gemini-4-hydrogen"
        const val MODEL_GEMINI_ARGON = "gemini-4-argon"
        const val MODEL_CLAUDE_FABLE = "claude-fable-5-2"
        const val MODEL_CLAUDE_OPUS = "claude-opus-5-5"
        const val MODEL_CLAUDE_SONNET = "claude-sonnet-5-5"
        const val MODEL_CLAUDE_HAIKU = "claude-haiku-5-5"
        const val MODEL_GROK = "grok-4.7"
        const val MODEL_DEEP_RESEARCH = "o3-deep-research"
        const val MODEL_GEMINI_DEEP_RESEARCH = "deep-research-max-preview-04-2026"
        const val CLAUDE_EFFORT_LOW = "low"
        const val CLAUDE_EFFORT_MEDIUM = "medium"
        const val CLAUDE_EFFORT_HIGH = "high"
        const val CLAUDE_EFFORT_MAX = "max"
        const val EFFORT_MEDIUM = "medium"
        const val EFFORT_HIGH = "high"
        const val EFFORT_XHIGH = "xhigh"
        const val THINKING_LOW = "low"
        const val THINKING_MEDIUM = "medium"
        const val THINKING_HIGH = "high"
        const val DEFAULT_TTS_INSTRUCTIONS =
            "Read naturally and clearly, conversational tone, moderate pace, " +
            "subtle pauses at punctuation. Avoid dramatic emphasis."
    }
}
