package com.slothspeak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Data-layer tests for STT config resolution in ApiKeyManager.
 * Runs under Robolectric for SharedPreferences availability.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SttConfigDataTest {

    @Test
    fun sttConfig_defaultProviderIsOpenai() {
        assertEquals(ApiKeyManager.STT_PROVIDER_OPENAI, ApiKeyManager.STT_PROVIDER_OPENAI)
    }

    @Test
    fun sttConfig_openaiPreset_resolvesCorrectly() {
        val config = ApiKeyManager.SttConfig(
            baseUrl = "https://api.openai.com/v1/",
            apiKey = null,
            model = "gpt-4o-transcribe",
            providerLabel = "OpenAI"
        )
        assertEquals("https://api.openai.com/v1/", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals("gpt-4o-transcribe", config.model)
        assertEquals("OpenAI", config.providerLabel)
    }

    @Test
    fun sttConfig_togetherPreset_resolvesCorrectly() {
        val config = ApiKeyManager.SttConfig(
            baseUrl = "https://api.together.ai/v1/",
            apiKey = "test-together-key",
            model = "openai/whisper-large-v3",
            providerLabel = "Together"
        )
        assertEquals("https://api.together.ai/v1/", config.baseUrl)
        assertEquals("test-together-key", config.apiKey)
        assertEquals("openai/whisper-large-v3", config.model)
        assertEquals("Together", config.providerLabel)
    }

    @Test
    fun sttConfig_customPreset_withBlankKey_hasNullKey() {
        val config = ApiKeyManager.SttConfig(
            baseUrl = "http://192.168.1.100:8000/v1/",
            apiKey = null,
            model = "openai/whisper-large-v3",
            providerLabel = "Custom"
        )
        assertEquals("http://192.168.1.100:8000/v1/", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals("openai/whisper-large-v3", config.model)
        assertEquals("Custom", config.providerLabel)
    }
}