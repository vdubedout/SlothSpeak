package com.slothspeak.data

/**
 * Provider families supported by the unified LLM settings section.
 *
 * Built-in types are backed by [ApiKeyManager] (one key + one selected model per
 * family), while [COMPAT] instances are backed by [CompatibleProviderManager].
 */
enum class LlmProviderType {
    OPENAI,
    GEMINI,
    CLAUDE,
    GROK,
    COMPAT
}

/**
 * UI-facing view of one configured LLM provider.
 *
 * Only configured providers are listed. [active] marks the provider whose model
 * is currently used by the pipeline, and [thinkingLevels] is empty when the
 * selected model does not expose a reasoning/thinking control.
 */
data class LlmProviderUiState(
    val key: String,
    val type: LlmProviderType,
    val displayName: String,
    val modelId: String,
    val baseUrl: String? = null,
    val active: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val thinkingLevel: String = "high",
    val thinkingLevels: List<String> = emptyList(),
    val compatProviderId: String? = null,
    /**
     * Models the user can switch to for this provider. Built-in providers list
     * their full model family; compatible providers expose their single
     * configured model. Empty for legacy callers that never populate it.
     */
    val availableModels: List<String> = emptyList()
) {
    val supportsThinking: Boolean get() = thinkingLevels.isNotEmpty()
    val isCompat: Boolean get() = type == LlmProviderType.COMPAT
}
