package com.slothspeak.audio

import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.util.TextChunker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * On-device TTS config-level and chunk-limit tests.
 *
 * Full synthesize round-trip requires a real device with android.speech.tts
 * support — Robolectric's ShadowTextToSpeech does not fire the init callback
 * synchronously in all configurations, so the suspend-based integration test
 * is deferred to hardware-target testing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnDeviceTtsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
    fun onDeviceTts_maxChunkChars_withinEngineLimit() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val synthesizer = OnDeviceTtsSynthesizer(context)
        val engineLimit = TextToSpeech.getMaxSpeechInputLength()

        assertTrue("maxChunkChars must be positive", synthesizer.maxChunkChars > 0)
        assertTrue(
            "maxChunkChars (${synthesizer.maxChunkChars}) must be <= engine limit ($engineLimit)",
            synthesizer.maxChunkChars <= engineLimit
        )
        if (engineLimit > 1) {
            assertTrue(
                "maxChunkChars (${synthesizer.maxChunkChars}) should leave headroom below $engineLimit",
                synthesizer.maxChunkChars < engineLimit
            )
        }
    }

    @Test
    fun onDeviceTts_computeMaxChunkChars_guardsEdgeLimits() {
        assertEquals(3000, OnDeviceTtsSynthesizer.computeMaxChunkChars(4000))
        assertEquals(3000, OnDeviceTtsSynthesizer.computeMaxChunkChars(10000))
        assertEquals(1999, OnDeviceTtsSynthesizer.computeMaxChunkChars(2000))
        assertEquals(1, OnDeviceTtsSynthesizer.computeMaxChunkChars(1))
        assertEquals(1, OnDeviceTtsSynthesizer.computeMaxChunkChars(0))
        assertEquals(1, OnDeviceTtsSynthesizer.computeMaxChunkChars(-1))
    }

    @Test
    fun openAiCompatibleTts_usesDefaultMaxChunkChars() {
        val config = ApiKeyManager.resolveTtsConfig(
            providerId = ApiKeyManager.TTS_PROVIDER_OPENAI,
            openaiApiKey = "sk-test",
            togetherApiKey = null,
            customApiKey = null,
            ttsModel = ApiKeyManager.DEFAULT_TTS_MODEL_OPENAI,
            ttsVoice = "marin",
            ttsBaseUrl = ""
        )
        val synthesizer = OpenAiCompatibleTtsSynthesizer(config)
        assertEquals(TextChunker.DEFAULT_MAX_CHUNK_SIZE, synthesizer.maxChunkChars)
    }

    @Test
    fun ttsProcessor_chunksUsingSynthesizerMaxChunkChars() = runBlocking {
        val receivedChunks = ConcurrentLinkedQueue<String>()
        val fakeSynthesizer = object : TtsSynthesizer {
            override val maxChunkChars: Int = 100

            override suspend fun synthesize(text: String, outputFile: File, instructions: String?) {
                receivedChunks.add(text)
                outputFile.writeBytes(byteArrayOf(1, 2, 3))
            }
        }

        val longText = (1..8).joinToString(" ") { "Sentence number $it is moderately long for testing." }
        val expectedChunks = TextChunker.chunkText(longText, maxChunkSize = 100)
        val processor = TtsProcessor(fakeSynthesizer, tempFolder.newFolder("audio"))
        val files = processor.processText(longText)

        assertTrue("Expected multiple chunks, got ${expectedChunks.size}", expectedChunks.size > 1)
        assertEquals(expectedChunks.size, receivedChunks.size)
        assertEquals(expectedChunks.size, files.size)
        assertEquals(expectedChunks.toSet(), receivedChunks.toSet())
        assertTrue(
            "Every chunk must respect synthesizer.maxChunkChars=100, got lengths ${receivedChunks.map { it.length }}",
            receivedChunks.all { it.length <= 100 }
        )
    }
}