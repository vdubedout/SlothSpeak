package com.slothspeak.data

/**
 * Single source of truth for the list of configured LLM providers, the active
 * provider/model, and switching between them. Shared by [SettingsViewModel]
 * (Settings screen) and [ConversationViewModel] (home-screen model switcher)
 * so both screens always agree on what is configured and active.
 *
 * All preference access goes through [LlmProviderStore]; the production store
 * is backed by [ApiKeyManager] + [CompatibleProviderManager]. The indirection
 * exists so unit tests can substitute an in-memory store — EncryptedSharedPreferences
 * requires the AndroidKeyStore, which Robolectric cannot provide.
 */
class LlmProviderCatalog internal constructor(private val store: LlmProviderStore) {

    constructor(
        apiKeyManager: ApiKeyManager,
        compatibleProviderManager: CompatibleProviderManager
    ) : this(ManagerLlmProviderStore(apiKeyManager, compatibleProviderManager))

    /**
     * Lists every configured provider (built-in with a key, plus all compatible
     * providers) with the active one marked. Includes [LlmProviderUiState.availableModels].
     */
    fun getConfiguredProviders(): List<LlmProviderUiState> {
        val selectedModel = store.selectedModel()
        val selectedCompatId = store.selectedCompatProviderId()
        val compatSelectionValid = selectedCompatId != null &&
            store.compatProvider(selectedCompatId) != null
        val providers = mutableListOf<LlmProviderUiState>()

        fun addBuiltin(type: LlmProviderType, providerKey: String) {
            if (!store.hasBuiltinLlmKey(providerKey)) return
            val models = ApiKeyManager.modelsForBuiltin(providerKey)
            val active = !compatSelectionValid && selectedModel in models
            val stored = store.builtinModel(providerKey)?.takeIf { it in models }
            val model = when {
                active -> selectedModel
                stored != null -> stored
                else -> ApiKeyManager.defaultBuiltinModel(providerKey)
            }
            val thinking = builtinThinking(type, model)
            providers += LlmProviderUiState(
                key = "builtin:$providerKey",
                type = type,
                displayName = ApiKeyManager.displayNameForBuiltin(providerKey),
                modelId = model,
                active = active,
                thinkingEnabled = thinking.enabled,
                thinkingLevel = thinking.level,
                thinkingLevels = thinking.levels,
                availableModels = models
            )
        }

        addBuiltin(LlmProviderType.OPENAI, ApiKeyManager.LLM_PROVIDER_OPENAI)
        addBuiltin(LlmProviderType.GEMINI, ApiKeyManager.LLM_PROVIDER_GEMINI)
        addBuiltin(LlmProviderType.CLAUDE, ApiKeyManager.LLM_PROVIDER_CLAUDE)
        addBuiltin(LlmProviderType.GROK, ApiKeyManager.LLM_PROVIDER_GROK)

        store.compatProviders().forEach { config ->
            providers += LlmProviderUiState(
                key = "compat:${config.id}",
                type = LlmProviderType.COMPAT,
                displayName = config.displayName,
                modelId = config.modelId,
                baseUrl = config.baseUrl,
                active = selectedCompatId == config.id,
                thinkingEnabled = config.thinkingEnabled,
                thinkingLevel = config.reasoningEffort,
                thinkingLevels = listOf("low", "high", "max"),
                compatProviderId = config.id,
                availableModels = listOf(config.modelId)
            )
        }

        return providers
    }

    /** The currently active provider, or null when nothing is configured/active. */
    fun getActiveProvider(): LlmProviderUiState? {
        return getConfiguredProviders().firstOrNull { it.active }
    }

    /**
     * Activates a provider using its current model (Settings "Activate" action
     * and the home-screen model switcher's folded-row tap).
     */
    fun activateProvider(provider: LlmProviderUiState) {
        selectModel(provider, provider.modelId)
    }

    /**
     * Switches the active model. For built-in providers the per-family stored
     * model and the global selected model are updated and any compatible
     * provider selection is cleared. For compatible providers the selected
     * compat provider id is set (its model lives in the provider config).
     */
    fun selectModel(provider: LlmProviderUiState, modelId: String) {
        if (provider.isCompat && provider.compatProviderId != null) {
            store.setSelectedCompatProviderId(provider.compatProviderId)
        } else {
            val providerKey = builtinProviderKey(provider.type)
            if (providerKey != null) {
                store.setBuiltinModel(providerKey, modelId)
            }
            store.setSelectedModel(modelId)
            store.setSelectedCompatProviderId(null)
        }
    }

    private fun builtinThinking(type: LlmProviderType, model: String): ThinkingSpec {
        return when (type) {
            LlmProviderType.OPENAI -> {
                // Every current GPT-6.x model exposes reasoning effort. The deep-research
                // model is not in OPENAI_MODELS, so it keeps its fixed effort.
                if (ApiKeyManager.supportsOpenAiReasoningEffort(model)) {
                    ThinkingSpec(
                        levels = listOf(
                            ApiKeyManager.EFFORT_MEDIUM,
                            ApiKeyManager.EFFORT_HIGH,
                            ApiKeyManager.EFFORT_XHIGH
                        ),
                        enabled = true,
                        level = store.reasoningEffort()
                    )
                } else ThinkingSpec(emptyList(), false, "")
            }
            LlmProviderType.GEMINI -> {
                // All Gemini 4 models expose thinking levels; the deep-research agent does not.
                if (ApiKeyManager.supportsGeminiThinking(model)) {
                    ThinkingSpec(
                        levels = listOf(
                            ApiKeyManager.THINKING_LOW,
                            ApiKeyManager.THINKING_MEDIUM,
                            ApiKeyManager.THINKING_HIGH
                        ),
                        enabled = true,
                        level = store.thinkingLevel()
                    )
                } else ThinkingSpec(emptyList(), false, "")
            }
            LlmProviderType.CLAUDE -> ThinkingSpec(
                levels = listOf(
                    ApiKeyManager.CLAUDE_EFFORT_LOW,
                    ApiKeyManager.CLAUDE_EFFORT_MEDIUM,
                    ApiKeyManager.CLAUDE_EFFORT_HIGH,
                    ApiKeyManager.CLAUDE_EFFORT_MAX
                ),
                enabled = true,
                level = store.claudeEffort()
            )
            LlmProviderType.GROK, LlmProviderType.COMPAT -> ThinkingSpec(emptyList(), false, "")
        }
    }

    private fun builtinProviderKey(type: LlmProviderType): String? = when (type) {
        LlmProviderType.OPENAI -> ApiKeyManager.LLM_PROVIDER_OPENAI
        LlmProviderType.GEMINI -> ApiKeyManager.LLM_PROVIDER_GEMINI
        LlmProviderType.CLAUDE -> ApiKeyManager.LLM_PROVIDER_CLAUDE
        LlmProviderType.GROK -> ApiKeyManager.LLM_PROVIDER_GROK
        LlmProviderType.COMPAT -> null
    }

    private data class ThinkingSpec(
        val levels: List<String>,
        val enabled: Boolean,
        val level: String
    )

    companion object {
        /**
         * Human-friendly display name for a model id (e.g. `gpt-6.2-astra` →
         * `GPT-6.2 Astra`). Unknown ids are shown as-is.
         */
        fun friendlyModelName(modelId: String): String = when (modelId) {
            ApiKeyManager.MODEL_ASTRA -> "GPT-6.2 Astra"
            ApiKeyManager.MODEL_SOL -> "GPT-6.2 Sol"
            ApiKeyManager.MODEL_TERRA -> "GPT-6.1 Terra"
            ApiKeyManager.MODEL_LUNA -> "GPT-6 Luna"
            ApiKeyManager.MODEL_DEEP_RESEARCH -> "o3 Deep Research"
            ApiKeyManager.MODEL_GEMINI_CARBON -> "Gemini 4 Carbon"
            ApiKeyManager.MODEL_GEMINI_NEON -> "Gemini 4 Neon"
            ApiKeyManager.MODEL_GEMINI_HYDROGEN -> "Gemini 4 Hydrogen"
            ApiKeyManager.MODEL_GEMINI_ARGON -> "Gemini 4 Argon"
            ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH -> "Gemini Deep Research"
            ApiKeyManager.MODEL_CLAUDE_FABLE -> "Claude Fable 5.2"
            ApiKeyManager.MODEL_CLAUDE_OPUS -> "Claude Opus 5.5"
            ApiKeyManager.MODEL_CLAUDE_SONNET -> "Claude Sonnet 5.5"
            ApiKeyManager.MODEL_CLAUDE_HAIKU -> "Claude Haiku 5.5"
            ApiKeyManager.MODEL_GROK -> "Grok 4.7"
            else -> modelId
        }
    }
}

/**
 * Preference-backed reads/writes the catalog needs. Implemented over
 * [ApiKeyManager] + [CompatibleProviderManager] in production; faked in tests.
 */
interface LlmProviderStore {
    fun selectedModel(): String
    fun setSelectedModel(model: String)

    fun selectedCompatProviderId(): String?
    fun setSelectedCompatProviderId(id: String?)

    fun builtinModel(providerKey: String): String?
    fun setBuiltinModel(providerKey: String, model: String)

    fun hasBuiltinLlmKey(providerKey: String): Boolean

    fun compatProviders(): List<CompatibleProviderConfig>
    fun compatProvider(id: String): CompatibleProviderConfig?

    fun reasoningEffort(): String
    fun thinkingLevel(): String
    fun claudeEffort(): String
}

/**
 * Production store delegating to the encrypted preference managers.
 */
private class ManagerLlmProviderStore(
    private val apiKeyManager: ApiKeyManager,
    private val compatibleProviderManager: CompatibleProviderManager
) : LlmProviderStore {
    override fun selectedModel(): String = apiKeyManager.getSelectedModel()
    override fun setSelectedModel(model: String) = apiKeyManager.setSelectedModel(model)

    override fun selectedCompatProviderId(): String? = apiKeyManager.getSelectedCompatProviderId()
    override fun setSelectedCompatProviderId(id: String?) =
        apiKeyManager.setSelectedCompatProviderId(id)

    override fun builtinModel(providerKey: String): String? =
        apiKeyManager.getBuiltinModel(providerKey)

    override fun setBuiltinModel(providerKey: String, model: String) =
        apiKeyManager.setBuiltinModel(providerKey, model)

    override fun hasBuiltinLlmKey(providerKey: String): Boolean =
        apiKeyManager.hasBuiltinLlmKey(providerKey)

    override fun compatProviders(): List<CompatibleProviderConfig> =
        compatibleProviderManager.getProviders()

    override fun compatProvider(id: String): CompatibleProviderConfig? =
        compatibleProviderManager.getProvider(id)

    override fun reasoningEffort(): String = apiKeyManager.getReasoningEffort()
    override fun thinkingLevel(): String = apiKeyManager.getThinkingLevel()
    override fun claudeEffort(): String = apiKeyManager.getClaudeEffort()
}
