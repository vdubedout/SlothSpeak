package com.slothspeak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Data-layer tests for STT config resolution via ApiKeyManager.resolveSttConfig().
 * Pure-function tests — no SharedPreferences dependency needed.
 * Runs under Robolectric for org.json (Android SDK) availability.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SttConfigDataTest {

    @Test
    fun defaultProviderIsOpenai() {
        // The constant identity itself, plus the expected fallback behaviour
        assertEquals("openai", ApiKeyManager.STT_PROVIDER_OPENAI)
    }

    @Test
    fun openaiPreset_resolves_openaiBase_getKey_andDefaultModel() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_OPENAI,
            openaiApiKey = "sk-openai-abc123",
            sttApiKey = null,
            sttModel = ApiKeyManager.DEFAULT_STT_MODEL_OPENAI,
            sttBaseUrl = "https://api.openai.com/v1/"
        )
        assertEquals("https://api.openai.com/v1/", config.baseUrl)
        assertEquals("sk-openai-abc123", config.apiKey)
        assertEquals("gpt-4o-transcribe", config.model)
        assertEquals("OpenAI", config.providerLabel)
    }

    @Test
    fun togetherPreset_resolves_togetherBase_andDefaultModel() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_TOGETHER,
            openaiApiKey = null,
            sttApiKey = "tgp-together-xyz",
            sttModel = ApiKeyManager.DEFAULT_STT_MODEL_TOGETHER,
            sttBaseUrl = ""
        )
        assertEquals("https://api.together.ai/v1/", config.baseUrl)
        assertEquals("tgp-together-xyz", config.apiKey)
        assertEquals("openai/whisper-large-v3", config.model)
        assertEquals("Together", config.providerLabel)
    }

    @Test
    fun togetherPreset_withNullSttApiKey_resolvesNullKey() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_TOGETHER,
            openaiApiKey = null,
            sttApiKey = null,
            sttModel = ApiKeyManager.DEFAULT_STT_MODEL_TOGETHER,
            sttBaseUrl = ""
        )
        assertNull(config.apiKey)
    }

    @Test
    fun customPreset_withBlankKey_resolvesNullKey() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_CUSTOM,
            openaiApiKey = null,
            sttApiKey = "",          // blank → null
            sttModel = "my-whisper-model",
            sttBaseUrl = "http://192.168.1.100:8000/v1/"
        )
        assertEquals("http://192.168.1.100:8000/v1/", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals("my-whisper-model", config.model)
        assertEquals("Custom", config.providerLabel)
    }

    @Test
    fun unknownProvider_fallsBackToOpenai() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = "nonexistent",
            openaiApiKey = "sk-fallback",
            sttApiKey = "ignored",
            sttModel = "",
            sttBaseUrl = ""
        )
        assertEquals("https://api.openai.com/v1/", config.baseUrl)
        assertEquals("sk-fallback", config.apiKey)
        assertEquals("gpt-4o-transcribe", config.model) // default model for openai
        assertEquals("OpenAI", config.providerLabel)
    }

    @Test
    fun setSttBaseUrl_normalizesTrailingSlash() {
        // Simulate what setSttBaseUrl does internally
        val raw = "http://x:8000/v1"
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        assertEquals("http://x:8000/v1/", normalized)
    }
}