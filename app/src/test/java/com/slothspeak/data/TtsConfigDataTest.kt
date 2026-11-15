package com.slothspeak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Data-layer tests for TTS config resolution via ApiKeyManager.resolveTtsConfig().
 * Pure-function tests — no SharedPreferences dependency needed.
 * Runs under Robolectric for org.json (Android SDK) availability.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TtsConfigDataTest {

    @Test
    fun defaultProviderIsOpenai() {
        assertEquals("openai", ApiKeyManager.TTS_PROVIDER_OPENAI)
    }

    @Test
    fun openaiPreset_resolves_openaiBase_andDefaultModelAndVoice() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_OPENAI,
            openaiApiKey = "sk-openai-abc123",
            togetherApiKey = null,
            customApiKey = null,
            ttsModel = ApiKeyManager.DEFAULT_TTS_MODEL_OPENAI,
            ttsVoice = ApiKeyManager.DEFAULT_TTS_VOICE_OPENAI,
            ttsBaseUrl = ""
        )
        assertEquals("openai", config.provider)
        assertEquals("https://api.openai.com/v1/", config.baseUrl)
        assertEquals("sk-openai-abc123", config.apiKey)
        assertEquals("gpt-4o-mini-tts", config.model)
        assertEquals("marin", config.voice)
    }

    @Test
    fun togetherPreset_resolves_togetherBase_andDefaultModelAndVoice() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_TOGETHER,
            openaiApiKey = null,
            togetherApiKey = "tgp-together-xyz",
            customApiKey = null,
            ttsModel = ApiKeyManager.DEFAULT_TTS_MODEL_TOGETHER,
            ttsVoice = ApiKeyManager.DEFAULT_TTS_VOICE_TOGETHER,
            ttsBaseUrl = ""
        )
        assertEquals("together", config.provider)
        assertEquals("https://api.together.ai/v1/", config.baseUrl)
        assertEquals("tgp-together-xyz", config.apiKey)
        assertEquals("canopylabs/orpheus-3b-0.1-ft", config.model)
        assertEquals("tara", config.voice)
    }

    @Test
    fun togetherPreset_withNullTogetherKey_resolvesNullKey() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_TOGETHER,
            openaiApiKey = null,
            togetherApiKey = null,
            customApiKey = null,
            ttsModel = ApiKeyManager.DEFAULT_TTS_MODEL_TOGETHER,
            ttsVoice = ApiKeyManager.DEFAULT_TTS_VOICE_TOGETHER,
            ttsBaseUrl = ""
        )
        assertNull(config.apiKey)
    }

    @Test
    fun customPreset_withBlankKey_resolvesNullKey() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_CUSTOM,
            openaiApiKey = null,
            togetherApiKey = null,
            customApiKey = "",  // blank → null
            ttsModel = "my-tts-model",
            ttsVoice = "custom-voice",
            ttsBaseUrl = "http://192.168.1.100:8000/v1/"
        )
        assertEquals("custom", config.provider)
        assertEquals("http://192.168.1.100:8000/v1/", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals("my-tts-model", config.model)
        assertEquals("custom-voice", config.voice)
    }

    @Test
    fun ondevicePreset_resolves_noKey_noBaseUrl() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_ONDEVICE,
            openaiApiKey = "ignored",
            togetherApiKey = "ignored",
            customApiKey = "ignored",
            ttsModel = "",
            ttsVoice = "",
            ttsBaseUrl = ""
        )
        assertEquals("ondevice", config.provider)
        assertNull(config.baseUrl)
        assertNull(config.apiKey)
        assertEquals("", config.model)
        assertEquals("", config.voice)
    }

    @Test
    fun customPreset_normalizesBaseUrl_trailingSlash() {
        val raw = "http://192.168.1.100:8000/v1"
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        assertEquals("http://192.168.1.100:8000/v1/", normalized)
    }

    @Test
    fun unknownProvider_fallsBackToOpenai() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = "nonexistent",
            openaiApiKey = "sk-fallback",
            togetherApiKey = "ignored",
            customApiKey = "ignored",
            ttsModel = "",
            ttsVoice = "",
            ttsBaseUrl = ""
        )
        assertEquals("openai", config.provider)
        assertEquals("https://api.openai.com/v1/", config.baseUrl)
        assertEquals("sk-fallback", config.apiKey)
        assertEquals("gpt-4o-mini-tts", config.model)  // default model for openai
        assertEquals("marin", config.voice)  // default voice for openai
    }
}