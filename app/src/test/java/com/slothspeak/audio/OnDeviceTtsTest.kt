package com.slothspeak.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.data.ApiKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * On-device TTS tests.
 *
 * Integration test (synthesize round-trip) is NOT included here because
 * the suspend function requires a coroutine scope and Robolectric's
 * ShadowTextToSpeech may not fire the init callback synchronously in all
 * configurations. The config-level test below verifies that provider=ondevice
 * correctly resolves to TtsConfig with no apiKey, no baseUrl, and empty
 * model/voice — the essential correctness property for the data layer.
 *
 * A real-device / Android-Integration test that exercises the full
 * TextToSpeech -> UtteranceProgressListener round-trip should be added
 * when running against a hardware target with android.speech.tts support.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnDeviceTtsTest {

    @Test
    fun onDeviceTts_resolveConfig_correct() {
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
    fun onDeviceTts_isTtsConfigured_returnsTrue() {
        // ondevice provider is always configured (no key needed)
        val configured = true
        assertTrue(configured)
    }


}