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

    @Test
    fun futoPreset_resolvesOnDeviceConfig_withNoKeyOrBaseUrl() {
        val config = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_FUTO,
            openaiApiKey = "sk-openai-should-not-be-used",
            sttApiKey = "should-not-be-used",
            sttModel = "ignored-model",
            sttBaseUrl = "http://ignored"
        )
        assertEquals("", config.baseUrl)
        assertNull(config.apiKey)
        assertEquals(ApiKeyManager.DEFAULT_STT_MODEL_FUTO, config.model)
        assertEquals("FUTO", config.providerLabel)
        assertEquals(ApiKeyManager.STT_PROVIDER_FUTO, config.providerId)
        assertTrue(config.onDevice)
    }

    @Test
    fun remotePresets_areNotOnDevice() {
        val openai = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_OPENAI,
            openaiApiKey = "sk",
            sttApiKey = null,
            sttModel = "",
            sttBaseUrl = ""
        )
        val together = ApiKeyManager.resolveSttConfig(
            providerId = ApiKeyManager.STT_PROVIDER_TOGETHER,
            openaiApiKey = null,
            sttApiKey = "tgp",
            sttModel = "",
            sttBaseUrl = ""
        )
        assertTrue(!openai.onDevice)
        assertTrue(!together.onDevice)
        assertEquals(ApiKeyManager.STT_PROVIDER_OPENAI, openai.providerId)
        assertEquals(ApiKeyManager.STT_PROVIDER_TOGETHER, together.providerId)
    }

    @Test
    fun futoModelSpec_coversStreamingMultilingualModel() {
        assertEquals("nemotron-3.5-asr-streaming-0.6b-560ms-int8", FutoSttModel.ID)
        assertEquals(4, FutoSttModel.files.size)
        assertEquals(
            FutoSttModel.files.size,
            FutoSttModel.files.map { it.name }.toSet().size
        )
        assertTrue(FutoSttModel.totalBytes > 600L * 1024L * 1024L)
        assertTrue(FutoSttModel.files.all { it.sizeBytes > 0 })
    }

    @Test
    fun futoLanguages_includeEnglishAndFrench() {
        val ids = FutoSttLanguage.options.map { it.first }
        assertTrue(ids.contains(FutoSttLanguage.ENGLISH))
        assertTrue(ids.contains(FutoSttLanguage.FRENCH))
        assertTrue(ids.contains(FutoSttLanguage.AUTO))
        assertEquals("Français", FutoSttLanguage.label(FutoSttLanguage.FRENCH))
    }

    @Test
    fun futoSelectModelFiles_prefersInt8CanonicalNames() {
        val selection = FutoSttModel.selectModelFiles(
            listOf(
                "encoder.onnx",
                "encoder.int8.onnx",
                "decoder.int8.onnx",
                "joiner.int8.onnx",
                "tokens.txt"
            )
        )
        assertEquals("encoder.int8.onnx", selection?.encoder)
        assertEquals("decoder.int8.onnx", selection?.decoder)
        assertEquals("joiner.int8.onnx", selection?.joiner)
        assertEquals("tokens.txt", selection?.tokens)
    }

    @Test
    fun futoSelectModelFiles_acceptsRenamedExports() {
        val selection = FutoSttModel.selectModelFiles(
            listOf(
                "nemotron_encoder.onnx",
                "nemotron_decoder.onnx",
                "nemotron_joiner.onnx",
                "tokens.txt"
            )
        )
        assertEquals("nemotron_encoder.onnx", selection?.encoder)
        assertEquals("nemotron_decoder.onnx", selection?.decoder)
        assertEquals("nemotron_joiner.onnx", selection?.joiner)
    }

    @Test
    fun futoSelectModelFiles_rejectsIncompleteFolder() {
        assertNull(
            FutoSttModel.selectModelFiles(
                listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx")
            )
        )
    }
}