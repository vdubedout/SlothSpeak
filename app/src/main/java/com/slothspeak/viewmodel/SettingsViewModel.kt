package com.slothspeak.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slothspeak.api.ModelDiscovery
import com.slothspeak.audio.FutoSttEngine
import com.slothspeak.config.SystemPrompts
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.CompatibleProviderManager
import com.slothspeak.data.FutoSttLanguage
import com.slothspeak.data.FutoSttModel
import com.slothspeak.data.FutoSttModelManager
import com.slothspeak.data.HostAuthProfile
import com.slothspeak.data.HttpWritePolicy
import com.slothspeak.data.LlmProviderCatalog
import com.slothspeak.data.LlmProviderType
import com.slothspeak.data.LlmProviderUiState
import com.slothspeak.data.SavedApiKeyLookup
import com.slothspeak.data.SavedKeyTarget
import com.slothspeak.data.SavedProviderKey
import com.slothspeak.data.SttProviderUiState
import com.slothspeak.data.ToolConfigManager
import com.slothspeak.data.TtsProviderUiState
import com.slothspeak.data.ToolsSettingsState
import com.slothspeak.data.db.SlothSpeakDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    /** Injectable seam so the Tools settings can be unit-tested without the Android Keystore. */
    private val toolConfigManager: ToolConfigManager = ToolConfigManager(application)
) : AndroidViewModel(application) {

    private val apiKeyManager = ApiKeyManager(application)
    private val compatibleProviderManager = CompatibleProviderManager(application)
    private val llmProviderCatalog = LlmProviderCatalog(apiKeyManager, compatibleProviderManager)
    private val futoModelManager = FutoSttModelManager(application)
    private val database = SlothSpeakDatabase.getInstance(application)
    private var futoDownloadJob: Job? = null

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    data class SettingsState(
        val selectedModel: String = ApiKeyManager.MODEL_ASTRA,
        val reasoningEffort: String = ApiKeyManager.EFFORT_HIGH,
        val audioStorageSizeMb: Long = 0,
        val conversationCount: Int = 0,
        val showClearHistoryDialog: Boolean = false,
        val showDeleteAllAudioDialog: Boolean = false,
        val selectedVoice: String = ApiKeyManager.DEFAULT_VOICE,
        val pingBeforeReading: Boolean = false,
        val thinkingLevel: String = ApiKeyManager.THINKING_HIGH,
        val claudeEffort: String = ApiKeyManager.CLAUDE_EFFORT_HIGH,
        val xSearchEnabled: Boolean = false,
        val systemPrompt: String = SystemPrompts.SYSTEM_PROMPT,
        val deepResearchSystemPrompt: String = SystemPrompts.DEEP_RESEARCH_SYSTEM_PROMPT,
        val ttsInstructions: String = ApiKeyManager.DEFAULT_TTS_INSTRUCTIONS,
        val interactiveVoiceEnabled: Boolean = false,
        val silenceTimeoutMs: Int = ApiKeyManager.DEFAULT_SILENCE_TIMEOUT_MS,
        val followUpPrompt: String = SystemPrompts.FOLLOW_UP_PROMPT,
        // Compat providers
        val compatProviders: List<CompatibleProviderConfig> = emptyList(),
        val selectedCompatProviderId: String? = null,
        // Unified LLM providers (configured only)
        val llmProviders: List<LlmProviderUiState> = emptyList(),
        // STT
        val sttProvider: String = ApiKeyManager.STT_PROVIDER_OPENAI,
        val hasSttApiKey: Boolean = false,
        val sttKeySaved: Boolean = false,
        val sttModel: String = ApiKeyManager.DEFAULT_STT_MODEL_OPENAI,
        val sttBaseUrl: String = "https://api.openai.com/v1/",
        val sttProviders: List<SttProviderUiState> = emptyList(),
        val futoSttLanguage: String = FutoSttLanguage.AUTO,
        val futoModelInstalled: Boolean = false,
        val futoModelDownloading: Boolean = false,
        val futoModelImporting: Boolean = false,
        val futoModelProgress: Float = 0f,
        val futoModelTotalMb: Long = FutoSttModel.totalMegabytes,
        val futoModelError: String? = null,
        // Wake word
        val wakeWordEnabled: Boolean = false,
        // TTS
        val ttsProvider: String = ApiKeyManager.TTS_PROVIDER_OPENAI,
        val ttsModel: String = ApiKeyManager.DEFAULT_TTS_MODEL_OPENAI,
        val ttsVoice: String = ApiKeyManager.DEFAULT_TTS_VOICE_OPENAI,
        val ttsBaseUrl: String = "https://api.openai.com/v1/",
        val hasTtsCustomApiKey: Boolean = false,
        val ttsKeySaved: Boolean = false,
        val ttsProviders: List<TtsProviderUiState> = emptyList(),
        // Tools (plugins for OpenAI-compatible models)
        val tools: ToolsSettingsState = ToolsSettingsState()
    )

    init {
        refreshState()
    }

    fun refreshState() {
        viewModelScope.launch {
            val audioSize = withContext(Dispatchers.IO) { calculateAudioStorageSize() }
            val convCount = withContext(Dispatchers.IO) { database.conversationDao().getConversationCount() }
            val futoStatus = withContext(Dispatchers.IO) { futoModelManager.status() }
            val llmProviders = buildLlmProviders()
            val sttProviders = buildSttProviders()
            val ttsProviders = buildTtsProviders()
            val tools = withContext(Dispatchers.IO) { toolConfigManager.snapshot() }
            _state.value = _state.value.copy(
                selectedModel = apiKeyManager.getSelectedModel(),
                reasoningEffort = apiKeyManager.getReasoningEffort(),
                selectedVoice = apiKeyManager.getSelectedVoice(),
                pingBeforeReading = apiKeyManager.getPingBeforeReading(),
                audioStorageSizeMb = audioSize,
                conversationCount = convCount,
                thinkingLevel = apiKeyManager.getThinkingLevel(),
                claudeEffort = apiKeyManager.getClaudeEffort(),
                xSearchEnabled = apiKeyManager.getXSearchEnabled(),
                systemPrompt = apiKeyManager.getSystemPrompt(),
                deepResearchSystemPrompt = apiKeyManager.getDeepResearchSystemPrompt(),
                ttsInstructions = apiKeyManager.getTtsInstructions(),
                interactiveVoiceEnabled = apiKeyManager.getInteractiveVoiceEnabled(),
                silenceTimeoutMs = apiKeyManager.getSilenceTimeoutMs(),
                wakeWordEnabled = apiKeyManager.getWakeWordEnabled(),
                followUpPrompt = apiKeyManager.getFollowUpPrompt(),
                compatProviders = compatibleProviderManager.getProviders(),
                selectedCompatProviderId = apiKeyManager.getSelectedCompatProviderId(),
                llmProviders = llmProviders,
                sttProviders = sttProviders,
                ttsProviders = ttsProviders,
                sttProvider = apiKeyManager.getSttProvider(),
                hasSttApiKey = apiKeyManager.hasSttApiKey(),
                sttModel = apiKeyManager.getSttModel(),
                sttBaseUrl = apiKeyManager.getSttBaseUrl(),
                futoSttLanguage = apiKeyManager.getFutoSttLanguage(),
                futoModelInstalled = futoStatus.installed,
                futoModelProgress = if (_state.value.futoModelDownloading ||
                    _state.value.futoModelImporting
                ) {
                    _state.value.futoModelProgress
                } else {
                    futoStatus.progress
                },
                futoModelTotalMb = futoStatus.totalBytes / (1024L * 1024L),
                ttsProvider = apiKeyManager.getTtsProvider(),
                ttsModel = apiKeyManager.getTtsModel(),
                ttsVoice = apiKeyManager.getTtsVoice(),
                ttsBaseUrl = apiKeyManager.getTtsBaseUrl(),
                hasTtsCustomApiKey = apiKeyManager.getTtsCustomApiKey() != null,
                ttsKeySaved = false,
                tools = tools
            )
        }
    }

    fun setThinkingLevel(level: String) {
        apiKeyManager.setThinkingLevel(level)
        _state.value = _state.value.copy(thinkingLevel = level)
    }

    fun setClaudeEffort(effort: String) {
        apiKeyManager.setClaudeEffort(effort)
        _state.value = _state.value.copy(claudeEffort = effort)
    }

    /**
     * Saves a compatible provider together with an optional API key.
     * A blank key keeps the existing key (use provider deletion to remove it).
     * New providers become active immediately.
     *
     * A key prefilled by the dialog (re-used from the same endpoint) is stored
     * under the new provider id here, so it is not lost.
     */
    fun saveCompatProvider(config: CompatibleProviderConfig, apiKey: String?) {
        val isNew = compatibleProviderManager.getProvider(config.id) == null
        compatibleProviderManager.saveProvider(config)
        if (!apiKey.isNullOrBlank()) {
            compatibleProviderManager.saveProviderKey(config.id, apiKey)
        }
        if (isNew) {
            apiKeyManager.setSelectedCompatProviderId(config.id)
        }
        refreshState()
    }

    /**
     * Saved API key to prefill the LLM provider dialog with, so re-using the
     * same endpoint (a second Z.ai entry, an already configured custom base
     * URL, …) does not require pasting the key again.
     *
     * Returns null when no matching key is stored — the dialog then starts with
     * an empty key field. The key is only returned to the UI and never logged.
     */
    fun findSavedApiKey(target: SavedKeyTarget): String? {
        val compatKeys = compatibleProviderManager.getProviders().map { config ->
            SavedProviderKey(
                providerId = config.id,
                baseUrl = config.baseUrl,
                apiKey = compatibleProviderManager.getProviderKey(config.id).orEmpty()
            )
        }
        val builtinKeys = mutableMapOf<String, String>()
        apiKeyManager.getKey()?.let { builtinKeys[ApiKeyManager.LLM_PROVIDER_OPENAI] = it }
        apiKeyManager.getGeminiKey()?.let { builtinKeys[ApiKeyManager.LLM_PROVIDER_GEMINI] = it }
        apiKeyManager.getClaudeKey()?.let { builtinKeys[ApiKeyManager.LLM_PROVIDER_CLAUDE] = it }
        apiKeyManager.getGrokKey()?.let { builtinKeys[ApiKeyManager.LLM_PROVIDER_GROK] = it }
        // Together is the only other built-in LLM endpoint we reuse (via its STT key).
        apiKeyManager.getSttApiKey()?.let { builtinKeys[ApiKeyManager.STT_PROVIDER_TOGETHER] = it }
        return SavedApiKeyLookup.findSavedKey(target, compatKeys, builtinKeys)
    }

    fun deleteCompatProvider(id: String) {
        val wasActive = apiKeyManager.getSelectedCompatProviderId() == id
        compatibleProviderManager.deleteProvider(id)
        if (wasActive) {
            apiKeyManager.setSelectedCompatProviderId(null)
            val next = buildLlmProviders().firstOrNull()
            if (next != null) activateProvider(next)
        }
        refreshState()
    }

    /**
     * Fetches model IDs from a compatible provider's `/models` endpoint.
     * Uses the key typed in the dialog, falling back to the stored provider key.
     */
    suspend fun fetchCompatModels(
        baseUrl: String,
        providerId: String?,
        apiKey: String?
    ): Result<List<String>> {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: providerId?.let { compatibleProviderManager.getProviderKey(it) }
        return ModelDiscovery.fetchModels(baseUrl, key)
    }


    // ============================================================
    // Unified LLM provider settings
    // ============================================================

    fun activateLlmProvider(provider: LlmProviderUiState) {
        activateProvider(provider)
        refreshState()
    }

    /**
     * Adds or updates a built-in provider (OpenAI, Gemini, Claude, xAI).
     * A blank [apiKey] keeps the currently configured key.
     */
    fun saveBuiltinLlmProvider(
        type: LlmProviderType,
        apiKey: String?,
        modelId: String,
        activate: Boolean
    ) {
        val providerKey = builtinProviderKey(type) ?: return
        val existing = _state.value.llmProviders.firstOrNull { it.type == type }
        if (!apiKey.isNullOrBlank()) {
            apiKeyManager.saveBuiltinLlmKey(providerKey, apiKey)
        }
        val models = ApiKeyManager.modelsForBuiltin(providerKey)
        val model = modelId.takeIf { it in models } ?: ApiKeyManager.defaultBuiltinModel(providerKey)
        apiKeyManager.setBuiltinModel(providerKey, model)
        // Newly added providers, explicit activation, and edits to the active
        // provider take effect immediately.
        if (activate || existing == null || existing.active) {
            apiKeyManager.setSelectedModel(model)
            apiKeyManager.setSelectedCompatProviderId(null)
        }
        refreshState()
    }

    fun deleteBuiltinLlmProvider(type: LlmProviderType) {
        val providerKey = builtinProviderKey(type) ?: return
        val wasActive = _state.value.llmProviders.firstOrNull { it.type == type }?.active == true
        apiKeyManager.deleteBuiltinLlmKey(providerKey)
        if (wasActive) {
            val next = buildLlmProviders().firstOrNull()
            if (next != null) activateProvider(next)
        }
        refreshState()
    }

    /**
     * Applies a thinking/reasoning setting to a provider. Built-in providers map
     * to their existing preference, while compatible providers are updated in
     * their stored config.
     */
    fun setLlmThinking(provider: LlmProviderUiState, enabled: Boolean, level: String) {
        when (provider.type) {
            LlmProviderType.OPENAI -> apiKeyManager.setReasoningEffort(level)
            LlmProviderType.GEMINI -> apiKeyManager.setThinkingLevel(level)
            LlmProviderType.CLAUDE -> apiKeyManager.setClaudeEffort(level)
            LlmProviderType.GROK -> Unit
            LlmProviderType.COMPAT -> {
                val id = provider.compatProviderId ?: return
                val config = compatibleProviderManager.getProvider(id) ?: return
                compatibleProviderManager.saveProvider(
                    config.copy(thinkingEnabled = enabled, reasoningEffort = level)
                )
            }
        }
        refreshLlmProviders()
    }

    private fun refreshLlmProviders() {
        _state.value = _state.value.copy(llmProviders = buildLlmProviders())
    }

    /** Delegates provider activation to the shared [LlmProviderCatalog]. */
    private fun activateProvider(provider: LlmProviderUiState) {
        llmProviderCatalog.activateProvider(provider)
    }

    private fun builtinProviderKey(type: LlmProviderType): String? = when (type) {
        LlmProviderType.OPENAI -> ApiKeyManager.LLM_PROVIDER_OPENAI
        LlmProviderType.GEMINI -> ApiKeyManager.LLM_PROVIDER_GEMINI
        LlmProviderType.CLAUDE -> ApiKeyManager.LLM_PROVIDER_CLAUDE
        LlmProviderType.GROK -> ApiKeyManager.LLM_PROVIDER_GROK
        LlmProviderType.COMPAT -> null
    }

    /** Delegates provider listing to the shared [LlmProviderCatalog]. */
    private fun buildLlmProviders(): List<LlmProviderUiState> {
        return llmProviderCatalog.getConfiguredProviders()
    }

    private fun buildSttProviders(): List<SttProviderUiState> {
        val current = apiKeyManager.getSttProvider()
        val hasOpenAi = apiKeyManager.hasKey()
        val hasTogether = apiKeyManager.hasSttApiKey()
        val customBase = apiKeyManager.getSttBaseUrlForProvider(ApiKeyManager.STT_PROVIDER_CUSTOM)
        val customModel = apiKeyManager.getSttModelForProvider(ApiKeyManager.STT_PROVIDER_CUSTOM)
        val customConfigured = customBase.isNotBlank() && customModel.isNotBlank()

        return listOf(
            SttProviderUiState(
                id = ApiKeyManager.STT_PROVIDER_OPENAI,
                displayName = "OpenAI",
                description = "gpt-4o-transcribe · uses OpenAI key from LLM",
                model = apiKeyManager.getSttModelForProvider(ApiKeyManager.STT_PROVIDER_OPENAI),
                baseUrl = null,
                configured = hasOpenAi,
                active = current == ApiKeyManager.STT_PROVIDER_OPENAI,
                hasKey = hasOpenAi,
                status = if (hasOpenAi) "Using OpenAI key from LLM section" else "OpenAI key not configured"
            ),
            SttProviderUiState(
                id = ApiKeyManager.STT_PROVIDER_TOGETHER,
                displayName = "Together",
                description = "whisper-large-v3 · requires Together key",
                model = apiKeyManager.getSttModelForProvider(ApiKeyManager.STT_PROVIDER_TOGETHER),
                baseUrl = null,
                configured = hasTogether,
                active = current == ApiKeyManager.STT_PROVIDER_TOGETHER,
                hasKey = hasTogether,
                status = if (hasTogether) "Key configured" else "Key missing"
            ),
            SttProviderUiState(
                id = ApiKeyManager.STT_PROVIDER_CUSTOM,
                displayName = "Custom",
                description = "Any OpenAI-compatible STT endpoint",
                model = customModel,
                baseUrl = customBase.takeIf { it.isNotBlank() },
                configured = customConfigured,
                active = current == ApiKeyManager.STT_PROVIDER_CUSTOM,
                hasKey = hasTogether,
                status = if (customConfigured) "Custom endpoint" else "Not configured"
            ),
            futoSttProvider(current)
        )
    }

    private fun futoSttProvider(current: String): SttProviderUiState {
        val status = futoModelManager.status()
        val language = apiKeyManager.getFutoSttLanguage()
        return SttProviderUiState(
            id = ApiKeyManager.STT_PROVIDER_FUTO,
            displayName = "FUTO (on-device)",
            description = "${FutoSttModel.DISPLAY_NAME} · English + French · offline",
            model = if (status.installed) {
                "${FutoSttModel.DISPLAY_NAME} · ${FutoSttLanguage.label(language)}"
            } else {
                FutoSttModel.DISPLAY_NAME
            },
            baseUrl = null,
            configured = status.installed,
            active = current == ApiKeyManager.STT_PROVIDER_FUTO,
            hasKey = false,
            status = if (status.installed) {
                "Downloaded · ${FutoSttLanguage.label(language)} · no network needed"
            } else {
                "Model not downloaded (${FutoSttModel.totalMegabytes} MB)"
            },
            onDevice = true
        )
    }

    private fun buildTtsProviders(): List<TtsProviderUiState> {
        val current = apiKeyManager.getTtsProvider()
        val hasOpenAi = apiKeyManager.hasKey()
        val hasTogether = apiKeyManager.hasSttApiKey()
        val customBase = apiKeyManager.getTtsBaseUrlForProvider(ApiKeyManager.TTS_PROVIDER_CUSTOM)
        val customModel = apiKeyManager.getTtsModelForProvider(ApiKeyManager.TTS_PROVIDER_CUSTOM)
        val customConfigured = customBase.isNotBlank() && customModel.isNotBlank()

        return listOf(
            TtsProviderUiState(
                id = ApiKeyManager.TTS_PROVIDER_OPENAI,
                displayName = "OpenAI",
                description = "gpt-4o-mini-tts · uses OpenAI key from LLM",
                model = apiKeyManager.getTtsModelForProvider(ApiKeyManager.TTS_PROVIDER_OPENAI),
                voice = apiKeyManager.getTtsVoiceForProvider(ApiKeyManager.TTS_PROVIDER_OPENAI),
                baseUrl = null,
                configured = hasOpenAi,
                active = current == ApiKeyManager.TTS_PROVIDER_OPENAI,
                onDevice = false,
                hasKey = hasOpenAi,
                status = if (hasOpenAi) "Using OpenAI key from LLM section" else "OpenAI key not configured"
            ),
            TtsProviderUiState(
                id = ApiKeyManager.TTS_PROVIDER_TOGETHER,
                displayName = "Together",
                description = "Orpheus · uses Together key from STT",
                model = apiKeyManager.getTtsModelForProvider(ApiKeyManager.TTS_PROVIDER_TOGETHER),
                voice = apiKeyManager.getTtsVoiceForProvider(ApiKeyManager.TTS_PROVIDER_TOGETHER),
                baseUrl = null,
                configured = hasTogether,
                active = current == ApiKeyManager.TTS_PROVIDER_TOGETHER,
                onDevice = false,
                hasKey = hasTogether,
                status = if (hasTogether) "Using Together key from Speech-to-Text" else "Together key missing — configure in STT"
            ),
            TtsProviderUiState(
                id = ApiKeyManager.TTS_PROVIDER_CUSTOM,
                displayName = "Custom",
                description = "Any OpenAI-compatible TTS endpoint",
                model = customModel,
                voice = apiKeyManager.getTtsVoiceForProvider(ApiKeyManager.TTS_PROVIDER_CUSTOM),
                baseUrl = customBase.takeIf { it.isNotBlank() },
                configured = customConfigured,
                active = current == ApiKeyManager.TTS_PROVIDER_CUSTOM,
                onDevice = false,
                hasKey = apiKeyManager.getTtsCustomApiKey() != null,
                status = if (customConfigured) "Custom endpoint" else "Not configured"
            ),
            TtsProviderUiState(
                id = ApiKeyManager.TTS_PROVIDER_ONDEVICE,
                displayName = "On-device",
                description = "Android system TTS · free & offline",
                model = "",
                voice = "",
                baseUrl = null,
                configured = true,
                active = current == ApiKeyManager.TTS_PROVIDER_ONDEVICE,
                onDevice = true,
                hasKey = false,
                status = "On-device · free & offline"
            )
        )
    }

    fun setReasoningEffort(effort: String) {
        apiKeyManager.setReasoningEffort(effort)
        _state.value = _state.value.copy(reasoningEffort = effort)
    }

    fun setSelectedVoice(voice: String) {
        apiKeyManager.setSelectedVoice(voice)
        _state.value = _state.value.copy(selectedVoice = voice)
    }

    fun setPingBeforeReading(enabled: Boolean) {
        apiKeyManager.setPingBeforeReading(enabled)
        _state.value = _state.value.copy(pingBeforeReading = enabled)
    }

    fun setXSearchEnabled(enabled: Boolean) {
        apiKeyManager.setXSearchEnabled(enabled)
        _state.value = _state.value.copy(xSearchEnabled = enabled)
    }

    fun setSystemPrompt(prompt: String) {
        apiKeyManager.setSystemPrompt(prompt)
        _state.value = _state.value.copy(systemPrompt = prompt)
    }

    fun setDeepResearchSystemPrompt(prompt: String) {
        apiKeyManager.setDeepResearchSystemPrompt(prompt)
        _state.value = _state.value.copy(deepResearchSystemPrompt = prompt)
    }

    fun resetSystemPrompt() {
        apiKeyManager.resetSystemPrompt()
        _state.value = _state.value.copy(systemPrompt = SystemPrompts.SYSTEM_PROMPT)
    }

    fun resetDeepResearchSystemPrompt() {
        apiKeyManager.resetDeepResearchSystemPrompt()
        _state.value = _state.value.copy(deepResearchSystemPrompt = SystemPrompts.DEEP_RESEARCH_SYSTEM_PROMPT)
    }

    fun setTtsInstructions(instructions: String) {
        apiKeyManager.setTtsInstructions(instructions)
        _state.value = _state.value.copy(ttsInstructions = instructions)
    }

    fun resetTtsInstructions() {
        apiKeyManager.resetTtsInstructions()
        _state.value = _state.value.copy(ttsInstructions = ApiKeyManager.DEFAULT_TTS_INSTRUCTIONS)
    }

    fun setInteractiveVoiceEnabled(enabled: Boolean) {
        apiKeyManager.setInteractiveVoiceEnabled(enabled)
        _state.value = _state.value.copy(interactiveVoiceEnabled = enabled)
    }

    fun setSilenceTimeoutMs(ms: Int) {
        apiKeyManager.setSilenceTimeoutMs(ms)
        _state.value = _state.value.copy(silenceTimeoutMs = ms)
    }

    // ============================================================
    // Wake Word Settings
    // ============================================================

    fun setWakeWordEnabled(enabled: Boolean) {
        apiKeyManager.setWakeWordEnabled(enabled)
        _state.value = _state.value.copy(wakeWordEnabled = enabled)
    }

    fun setFollowUpPrompt(prompt: String) {
        apiKeyManager.setFollowUpPrompt(prompt)
        _state.value = _state.value.copy(followUpPrompt = prompt)
    }

    fun resetFollowUpPrompt() {
        apiKeyManager.resetFollowUpPrompt()
        _state.value = _state.value.copy(followUpPrompt = SystemPrompts.FOLLOW_UP_PROMPT)
    }

    fun showDeleteAllAudioDialog() {
        _state.value = _state.value.copy(showDeleteAllAudioDialog = true)
    }

    fun dismissDeleteAllAudioDialog() {
        _state.value = _state.value.copy(showDeleteAllAudioDialog = false)
    }

    fun confirmDeleteAllAudio() {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete all audio files referenced in QA pairs
            val audioPaths = database.conversationDao().getAllAudioPaths()
            for (pathsString in audioPaths) {
                pathsString.split(",").forEach { path ->
                    File(path.trim()).delete()
                }
            }
            // Also delete the audio directory contents
            val audioDir = File(getApplication<Application>().filesDir, "audio")
            audioDir.deleteRecursively()

            // Clear audio path references from all QA pairs
            database.conversationDao().clearAllAudioPaths()

            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showDeleteAllAudioDialog = false,
                    audioStorageSizeMb = 0
                )
            }
        }
    }

    fun showClearHistoryDialog() {
        _state.value = _state.value.copy(showClearHistoryDialog = true)
    }

    fun dismissClearHistoryDialog() {
        _state.value = _state.value.copy(showClearHistoryDialog = false)
    }

    fun confirmClearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete all audio files
            val audioPaths = database.conversationDao().getAllAudioPaths()
            for (pathsString in audioPaths) {
                pathsString.split(",").forEach { path ->
                    File(path.trim()).delete()
                }
            }
            // Also delete the audio directory contents
            val audioDir = File(getApplication<Application>().filesDir, "audio")
            audioDir.deleteRecursively()

            database.conversationDao().clearAll()

            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    showClearHistoryDialog = false,
                    audioStorageSizeMb = 0,
                    conversationCount = 0
                )
            }
        }
    }

    fun deleteConversationAudioOnly(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val audioPaths = database.conversationDao().getAudioPathsForConversation(conversationId)
            for (pathsString in audioPaths) {
                pathsString.split(",").forEach { path ->
                    File(path.trim()).delete()
                }
            }
            val qaPairs = database.conversationDao().getQAPairsForConversationOnce(conversationId)
            for (pair in qaPairs) {
                database.conversationDao().clearAudioForQAPair(pair.id)
            }
            withContext(Dispatchers.Main) {
                refreshState()
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val audioPaths = database.conversationDao().getAudioPathsForConversation(conversationId)
            for (pathsString in audioPaths) {
                pathsString.split(",").forEach { path ->
                    File(path.trim()).delete()
                }
            }
            database.conversationDao().deleteConversation(conversationId)
            withContext(Dispatchers.Main) {
                refreshState()
            }
        }
    }

    // ============================================================
    // STT (Speech-to-Text) Provider
    // ============================================================

    fun saveSttApiKey(key: String) {
        if (key.isBlank()) return
        apiKeyManager.saveSttApiKey(key)
        _state.value = _state.value.copy(hasSttApiKey = true, sttKeySaved = true)
        refreshSpeechProviders()
    }

    fun deleteSttApiKey() {
        apiKeyManager.deleteSttApiKey()
        _state.value = _state.value.copy(hasSttApiKey = false, sttKeySaved = false)
        refreshSpeechProviders()
    }

    fun setSttProvider(id: String) {
        apiKeyManager.setSttProvider(id)
        if (id != ApiKeyManager.STT_PROVIDER_FUTO) {
            // Free the ~650 MB on-device model when it is no longer selected.
            FutoSttEngine.releaseShared()
        }
        refreshState()
    }

    fun setSttModel(model: String) {
        apiKeyManager.setSttModel(model)
        _state.value = _state.value.copy(sttModel = model)
        refreshSpeechProviders()
    }

    fun setSttBaseUrl(url: String) {
        apiKeyManager.setSttBaseUrl(url)
        _state.value = _state.value.copy(sttBaseUrl = url)
        refreshSpeechProviders()
    }

    fun setFutoSttLanguage(language: String) {
        apiKeyManager.setFutoSttLanguage(language)
        _state.value = _state.value.copy(futoSttLanguage = language)
        refreshSpeechProviders()
    }

    fun downloadFutoSttModel() {
        if (_state.value.futoModelDownloading || _state.value.futoModelImporting) return
        futoDownloadJob?.cancel()
        FutoSttEngine.releaseShared()
        _state.value = _state.value.copy(
            futoModelDownloading = true,
            futoModelImporting = false,
            futoModelError = null,
            futoModelProgress = 0f
        )
        futoDownloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    futoModelManager.download { done, total ->
                        _state.value = _state.value.copy(
                            futoModelProgress = if (total > 0) {
                                (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            futoModelTotalMb = total / (1024L * 1024L)
                        )
                    }
                }
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false,
                    futoModelInstalled = true,
                    futoModelProgress = 1f,
                    futoModelError = null
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false
                )
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false,
                    futoModelError = e.message ?: "Model download failed"
                )
            } finally {
                refreshSpeechProviders()
            }
        }
    }

    fun importFutoSttModel(treeUri: Uri) {
        if (_state.value.futoModelDownloading || _state.value.futoModelImporting) return
        futoDownloadJob?.cancel()
        FutoSttEngine.releaseShared()
        _state.value = _state.value.copy(
            futoModelDownloading = false,
            futoModelImporting = true,
            futoModelError = null,
            futoModelProgress = 0f
        )
        futoDownloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    futoModelManager.importFromFolder(treeUri) { done, total ->
                        _state.value = _state.value.copy(
                            futoModelProgress = if (total > 0) {
                                (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        )
                    }
                }
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false,
                    futoModelInstalled = true,
                    futoModelProgress = 1f,
                    futoModelError = null
                )
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false
                )
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    futoModelDownloading = false,
                    futoModelImporting = false,
                    futoModelError = e.message ?: "Model import failed"
                )
            } finally {
                refreshSpeechProviders()
            }
        }
    }

    fun deleteFutoSttModel() {
        // The UI only offers delete when idle; ignore spurious calls mid-download
        // so an in-flight write cannot race with directory deletion.
        if (_state.value.futoModelDownloading || _state.value.futoModelImporting) return
        futoDownloadJob?.cancel()
        futoDownloadJob = null
        FutoSttEngine.releaseShared()
        futoModelManager.delete()
        _state.value = _state.value.copy(
            futoModelInstalled = false,
            futoModelDownloading = false,
            futoModelImporting = false,
            futoModelProgress = 0f,
            futoModelError = null
        )
        refreshSpeechProviders()
    }

    fun cancelFutoSttDownload() {
        val job = futoDownloadJob ?: return
        futoDownloadJob = null
        viewModelScope.launch {
            job.cancelAndJoin()
            _state.value = _state.value.copy(
                futoModelDownloading = false,
                futoModelImporting = false,
                futoModelProgress = futoModelManager.status().progress,
                futoModelError = null
            )
            refreshSpeechProviders()
        }
    }

    // ============================================================
    // TTS (Text-to-Speech) Provider
    // ============================================================

    fun setTtsProvider(id: String) {
        apiKeyManager.setTtsProvider(id)
        refreshState()
    }

    fun setTtsModel(model: String) {
        apiKeyManager.setTtsModel(model)
        _state.value = _state.value.copy(ttsModel = model)
        refreshSpeechProviders()
    }

    fun setTtsVoice(voice: String) {
        apiKeyManager.setTtsVoice(voice)
        _state.value = _state.value.copy(ttsVoice = voice)
        refreshSpeechProviders()
    }

    fun setTtsBaseUrl(url: String) {
        apiKeyManager.setTtsBaseUrl(url)
        _state.value = _state.value.copy(ttsBaseUrl = url)
        refreshSpeechProviders()
    }

    fun saveTtsCustomApiKey(key: String) {
        if (key.isBlank()) return
        apiKeyManager.saveTtsCustomApiKey(key)
        _state.value = _state.value.copy(hasTtsCustomApiKey = true, ttsKeySaved = true)
        refreshSpeechProviders()
    }

    fun deleteTtsCustomApiKey() {
        apiKeyManager.deleteTtsCustomApiKey()
        _state.value = _state.value.copy(hasTtsCustomApiKey = false, ttsKeySaved = false)
        refreshSpeechProviders()
    }

    private fun refreshSpeechProviders() {
        _state.value = _state.value.copy(
            sttProviders = buildSttProviders(),
            ttsProviders = buildTtsProviders()
        )
    }

    // ============================================================
    // Tools (plugins for OpenAI-compatible models)
    // ============================================================

    private fun refreshTools() {
        _state.value = _state.value.copy(tools = toolConfigManager.snapshot())
    }

    fun setBraveSearchEnabled(enabled: Boolean) {
        toolConfigManager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, enabled)
        refreshTools()
    }

    /** Stores the Brave Search API key encrypted; blank keys are ignored. */
    fun saveBraveApiKey(key: String) {
        if (key.isBlank()) return
        toolConfigManager.saveBraveApiKey(key)
        refreshTools()
    }

    fun deleteBraveApiKey() {
        toolConfigManager.deleteBraveApiKey()
        refreshTools()
    }

    /** Delay between consecutive Brave searches; clamped to 0..10000 ms by the manager. */
    fun setBraveMinIntervalMs(intervalMs: Int) {
        toolConfigManager.setBraveMinIntervalMs(intervalMs)
        refreshTools()
    }

    fun setHttpRequestEnabled(enabled: Boolean) {
        toolConfigManager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, enabled)
        refreshTools()
    }

    fun setHttpWritePolicy(policy: HttpWritePolicy) {
        toolConfigManager.setHttpWritePolicy(policy)
        refreshTools()
    }

    /** Persists the per-prompt tool-call budget; [ToolConfigManager] clamps it to 1..50. */
    fun setMaxToolCalls(value: Int) {
        toolConfigManager.setMaxToolCalls(value)
        refreshTools()
    }

    /**
     * Persists the tool-safety preamble as typed (blank resets it to the default). The state
     * mirrors the raw text so typing stays responsive, like [setSystemPrompt].
     */
    fun setToolSafetyPrompt(prompt: String) {
        toolConfigManager.setToolSafetyPrompt(prompt)
        _state.value = _state.value.copy(tools = _state.value.tools.copy(toolSafetyPrompt = prompt))
    }

    fun resetToolSafetyPrompt() {
        toolConfigManager.resetToolSafetyPrompt()
        refreshTools()
    }

    fun removeAllowedWriteHost(host: String) {
        toolConfigManager.removeAllowedWriteHost(host)
        refreshTools()
    }

    fun resetAllowedWriteHosts() {
        toolConfigManager.clearAllowedWriteHosts()
        refreshTools()
    }

    /** Adds a host auth profile; the header value goes to EncryptedSharedPreferences. */
    fun saveHostAuthProfile(host: String, headerName: String, headerValue: String) {
        if (host.isBlank() || headerName.isBlank() || headerValue.isBlank()) return
        toolConfigManager.saveHostAuthProfile(
            HostAuthProfile(
                id = UUID.randomUUID().toString(),
                host = host.trim(),
                headerName = headerName.trim(),
                headerValue = headerValue
            )
        )
        refreshTools()
    }

    fun deleteHostAuthProfile(id: String) {
        toolConfigManager.deleteHostAuthProfile(id)
        refreshTools()
    }

    private fun calculateAudioStorageSize(): Long {
        val audioDir = File(getApplication<Application>().filesDir, "audio")
        if (!audioDir.exists()) return 0
        var totalBytes = 0L
        audioDir.walkTopDown().forEach { file ->
            if (file.isFile) totalBytes += file.length()
        }
        return totalBytes / (1024 * 1024) // Convert to MB
    }
}
