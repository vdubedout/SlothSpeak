package com.slothspeak.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.slothspeak.api.ApiException
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.util.TextChunker
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Interface for TTS synthesis — one method: synthesize text to an audio file.
 */
interface TtsSynthesizer {
    /**
     * Maximum number of characters allowed in a single [synthesize] call.
     */
    val maxChunkChars: Int
        get() = TextChunker.DEFAULT_MAX_CHUNK_SIZE

    /**
     * Synthesize [text] into [outputFile].
     * @param instructions optional speaking-style instructions (OpenAI only)
     * @throws ApiException with provider-named messages on failure
     */
    suspend fun synthesize(text: String, outputFile: File, instructions: String?)
}

/**
 * OpenAI-compatible TTS synthesizer (covers OpenAI, Together.ai, custom endpoints).
 * Uses the cached OkHttp+Retrofit pattern from OpenAiCompatibleClient.
 */
class OpenAiCompatibleTtsSynthesizer(
    private val config: ApiKeyManager.TtsConfig
) : TtsSynthesizer {

    private val moshi = Moshi.Builder().build()
    private val httpClientCache = mutableMapOf<String, OkHttpClient>()
    private val serviceCache = mutableMapOf<String, TtsHttpService>()

    private fun getOrCreateHttpClient(baseUrl: String, apiKey: String?): OkHttpClient {
        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        return httpClientCache.getOrPut(cacheKey) {
            val authInterceptor = Interceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (!apiKey.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                chain.proceed(requestBuilder.build())
            }

            OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun getOrCreateService(baseUrl: String, apiKey: String?): TtsHttpService {
        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        return serviceCache.getOrPut(cacheKey) {
            val httpClient = getOrCreateHttpClient(baseUrl, apiKey)
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(TtsHttpService::class.java)
        }
    }

    override suspend fun synthesize(text: String, outputFile: File, instructions: String?) {
        val baseUrl = normalizeBaseUrl(config.baseUrl ?: "")
        val service = getOrCreateService(baseUrl, config.apiKey)

        // Build request: openai includes instructions + selected voice;
        // together/custom omit instructions and rely on config.model/config.voice.
        val request = when (config.provider) {
            ApiKeyManager.TTS_PROVIDER_OPENAI -> TtsRequestBody(
                model = config.model,
                input = text,
                voice = config.voice,
                instructions = if (instructions != null) instructions else ""  // non-null for OpenAI
            )
            else -> TtsRequestBody(
                model = config.model,
                input = text,
                voice = config.voice,
                instructions = null  // Moshi omits null fields from JSON
            )
        }

        val response = service.createSpeech(request)
        if (response.isSuccessful) {
            val body = response.body() ?: throw ApiException("Empty TTS response")
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            val code = response.code()
            val errorBody = response.errorBody()?.string()
            val label = providerLabel()
            throw when (code) {
                401 -> ApiException("Invalid $label API key. Please check your key in Settings.", code, true)
                429 -> ApiException("Rate limited by $label. Please wait and try again.", code, true)
                in 500..599 -> ApiException("$label server error. Please try again later.", code, true)
                else -> ApiException(
                    errorBody?.let { "Request failed: $it" } ?: "$label request failed (HTTP $code)",
                    code,
                    true
                )
            }
        }
    }

    private fun providerLabel(): String = when (config.provider) {
        ApiKeyManager.TTS_PROVIDER_OPENAI -> "OpenAI TTS"
        ApiKeyManager.TTS_PROVIDER_TOGETHER -> "Together TTS"
        ApiKeyManager.TTS_PROVIDER_CUSTOM -> "Custom TTS"
        else -> "TTS"
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) throw ApiException("TTS base URL is blank", 0, false)
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}

/**
 * On-device TTS synthesizer using Android's android.speech.tts.TextToSpeech.
 * Direct typed API — no reflection. Interfaces with the engine via its native
 * uttterance-progress listener and Bundle-based parameterization.
 * Instructions are not applicable (system engine default); voice/model are
 * managed by the system.
 *
 * Lazy-init on first synthesize. A single engine instance is reused.
 */
class OnDeviceTtsSynthesizer internal constructor(
    private val context: Context,
    private val maxSpeechInputLengthProvider: () -> Int
) : TtsSynthesizer {

    constructor(context: Context) : this(context, { TextToSpeech.getMaxSpeechInputLength() })

    private val TAG = "OnDeviceTts"
    private val INIT_TIMEOUT_MS = 30_000L
    private val SYNTHESIS_TIMEOUT_MS = 60_000L

    override val maxChunkChars: Int
        get() = computeMaxChunkChars(maxSpeechInputLengthProvider())

    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val initLock = Semaphore(1)

    private suspend fun ensureEngine() = initLock.withPermit {
        if (tts != null) return@withPermit

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.i(TAG, "On-device TTS engine initialized (status=$status)")
                ready.complete(true)
            } else {
                val msg = "On-device TTS engine failed to initialize (status=$status)"
                Log.w(TAG, msg)
                ready.completeExceptionally(
                    ApiException(msg, 0, false)
                )
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(TAG, "Synthesis started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.i(TAG, "Synthesis complete: $utteranceId")
                    val id = utteranceId ?: return
                    pending.remove(id)?.complete(Unit)
                }

                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?) {
                    val id = utteranceId ?: return
                    Log.e(TAG, "Synthesis error: $utteranceId")
                    pending.remove(id)?.completeExceptionally(
                        ApiException("On-device TTS synthesis failed", 0, false)
                    )
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    val id = utteranceId ?: return
                    Log.e(TAG, "Synthesis error: $utteranceId (errorCode=$errorCode)")
                    pending.remove(id)?.completeExceptionally(
                        ApiException("On-device TTS synthesis failed (errorCode=$errorCode)", 0, false)
                    )
                }
            })
        }
    }

    override suspend fun synthesize(text: String, outputFile: File, instructions: String?) {
        ensureEngine()

        // Wait for engine initialization
        try {
            withTimeout(INIT_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            throw ApiException("On-device TTS engine init timed out", 0, false)
        } catch (e: ApiException) {
            throw e
        }

        val engine = tts ?: throw ApiException("On-device TTS engine unavailable", 0, false)

        // Check language availability
        val langStatus = engine.isLanguageAvailable(java.util.Locale.getDefault())
        if (langStatus == TextToSpeech.LANG_MISSING_DATA ||
            langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw ApiException(
                "On-device TTS has no voice data for the device language (status=$langStatus)",
                0, false
            )
        }

        // Build params bundle (default rate)
        val params = Bundle()

        val utteranceId = "slothspeak_${System.nanoTime()}"
        val done = CompletableDeferred<Unit>().also { pending.put(utteranceId, it) }

        val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(utteranceId)
            val engineLimit = maxSpeechInputLengthProvider()
            val msg = "On-device TTS request rejected (code=$result, chars=${text.length}, engineLimit=$engineLimit)"
            Log.e(TAG, msg)
            throw ApiException(msg, 0, false)
        }

        try {
            withTimeout(SYNTHESIS_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(utteranceId)
            engine.stop()
            throw ApiException("On-device TTS synthesis timed out", 0, false)
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw ApiException("On-device TTS produced empty audio file", 0, false)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts = null
        // Cancel any pending utterances
        for ((id, deferred) in pending.entries) {
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(
                    ApiException("On-device TTS was shut down", 0, false)
                )
            }
        }
        pending.clear()
    }

    companion object {
        private const val SAFE_MAX_CHUNK_CHARS = 3000

        /**
         * Derives a safe per-utterance character limit from Android's
         * [TextToSpeech.getMaxSpeechInputLength]. Leaves headroom below the engine
         * limit and clamps to at least 1 so chunking always makes forward progress.
         */
        internal fun computeMaxChunkChars(engineLimit: Int): Int {
            return minOf(engineLimit - 1, SAFE_MAX_CHUNK_CHARS).coerceAtLeast(1)
        }
    }
}

// ---- Request / Service types ----

@JsonClass(generateAdapter = true)
data class TtsRequestBody(
    val model: String,
    val input: String,
    val voice: String,
    @Json(name = "response_format")
    val responseFormat: String = "mp3",
    val instructions: String? = null  // null → Moshi omits from JSON
)

interface TtsHttpService {
    @Streaming
    @POST("audio/speech")
    suspend fun createSpeech(
        @Body request: TtsRequestBody
    ): retrofit2.Response<okhttp3.ResponseBody>
}