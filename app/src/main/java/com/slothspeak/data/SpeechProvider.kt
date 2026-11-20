package com.slothspeak.data

/**
 * UI-facing view of one speech provider.
 *
 * Speech providers are single-instance (unlike LLM compatible providers), so
 * the compact settings section shows the active provider plus any other
 * configured providers as "Use" rows.
 */
data class SttProviderUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val model: String,
    val baseUrl: String?,
    val configured: Boolean,
    val active: Boolean,
    val hasKey: Boolean,
    val status: String
)

data class TtsProviderUiState(
    val id: String,
    val displayName: String,
    val description: String,
    val model: String,
    val voice: String,
    val baseUrl: String?,
    val configured: Boolean,
    val active: Boolean,
    val onDevice: Boolean,
    val hasKey: Boolean,
    val status: String
)
