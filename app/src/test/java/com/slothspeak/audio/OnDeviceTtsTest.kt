package com.slothspeak.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.data.ApiKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * On-device TTS config-level tests.
 *
 * Full synthesize round-trip requires a real device with android.speech.tts
 * support — Robolectric's ShadowTextToSpeech does not fire the init callback
 * synchronously in all configurations, so the suspend-based integration test
 * is deferred to hardware-target testing.
 *
 * The config-level test below verifies that provider=ondevice correctly
 * resolves to TtsConfig with no apiKey, no baseUrl, and empty model/voice.
 * isTtsConfigured() semantics require a live ApiKeyManager (SharedPreferences),
 * which is not available in this pure-data test context.
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
}