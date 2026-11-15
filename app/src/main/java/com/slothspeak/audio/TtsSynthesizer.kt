package com.slothspeak.audio

import android.content.Context
import android.util.Log
import com.slothspeak.api.ApiException
import com.slothspeak.data.ApiKeyManager
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
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
 * Uses dynamic class loading to avoid compile-time dependency on the API
 * (not available in SDK 34 test harness but present on devices with TTS support).
 * If the API is unavailable at runtime, throws a clean ApiException.
 */
class OnDeviceTtsSynthesizer(
    private val context: Context
) : TtsSynthesizer {

    private val TAG = "OnDeviceTts"
    private val TTS_CLASS_NAME = "android.speech.tts.TextToSpeech"
    private val INIT_TIMEOUT_MS = 5000L
    private val SYNTHESIS_TIMEOUT_MS = 60_000L

    private var engine: java.lang.Object? = null
    private var ttsClass: Class<*>? = null
    private var initialized = false
    private var initFailed = false
    private var initError: String? = null

    private suspend fun ensureInitialized() {
        if (initialized && engine != null) return
        if (initFailed) {
            throw ApiException(initError ?: "On-device TTS engine unavailable. Please select a different provider.", 0, false)
        }

        val cls = try { Class.forName(TTS_CLASS_NAME) } catch (_: ClassNotFoundException) { null }
        if (cls == null) {
            initFailed = true
            initError = "On-device TTS engine not available on this device (${TTS_CLASS_NAME} not found)"
            Log.w(TAG, initError ?: "unknown")
            throw ApiException(initError ?: "unknown", 0, false)
        }

        try {
            val ctor = cls.getConstructor()
            val eng = ctor.newInstance()
            ttsClass = cls
            engine = eng as java.lang.Object
            initialized = true
            Log.i(TAG, "On-device TTS engine initialized successfully")
        } catch (e: Exception) {
            initFailed = true
            initError = "On-device TTS engine init failed: ${e.message}"
            Log.w(TAG, initError, e)
            throw ApiException(initError!!, 0, false)
        }
    }

    override suspend fun synthesize(text: String, outputFile: File, instructions: String?) {
        ensureInitialized()
        val eng = engine ?: throw ApiException("On-device TTS engine not available", 0, false)
        val cls = ttsClass ?: throw ApiException("On-device TTS engine not initialized", 0, false)

        try {
            // Load Bundle class
            val bundleClass = Class.forName("${TTS_CLASS_NAME}.Bundle")
            val bundle = bundleClass.getConstructor().newInstance()

            val utteranceId = "utt_${System.identityHashCode(this)}_${System.currentTimeMillis()}"
            val wavPath = outputFile.absolutePath

            // Set up handler with progress listener via reflection proxy
            val progressListenerClass = Class.forName("android.speech.tts.UtteranceProgressListener")
            val handlerClass = Class.forName("${TTS_CLASS_NAME}.Handler")
            val looperClass = Class.forName("android.os.Looper")

            val listener = java.lang.reflect.Proxy.newProxyInstance(
                progressListenerClass.getClassLoader(),
                java.lang.reflect.Array.newInstance(Class::class.java, 1).also {
                    java.lang.reflect.Array.set(it, 0, progressListenerClass)
                } as Array<Class<*>>,
                java.lang.reflect.InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onDone" -> Log.i(TAG, "Synthesis complete: $utteranceId")
                        "onError" -> {
                            val code = if (args != null && args.size > 1) (args[1] as? Int ?: -1) else -1
                            Log.e(TAG, "Synthesis error [$utteranceId]: code=$code")
                        }
                    }
                    null
                }
            )

            val getMainLooper = looperClass.getMethod("getMainLooper")
            val mainLooper = getMainLooper.invoke(null)
            val handlerCtor = handlerClass.getConstructor(looperClass)
            val handler = handlerCtor.newInstance(mainLooper)
            handlerClass.getMethod("setListener", String::class.java, progressListenerClass)
                .invoke(handler, utteranceId, listener)
            cls.getMethod("setHandler", handlerClass).invoke(eng, handler)

            // Call synthesizeToFile(text, bundle, absolutePath, utteranceId)
            cls.getMethod("synthesizeToFile",
                    String::class.java, bundleClass,
                    String::class.java, String::class.java)
                .invoke(eng, text, bundle, wavPath, utteranceId)

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw ApiException("On-device TTS produced empty audio file", 0, false)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ApiException) throw cause as ApiException
            throw ApiException("On-device TTS synthesis failed: ${cause?.message ?: e.message}", 0, false)
        } catch (e: Exception) {
            throw ApiException("On-device TTS synthesis failed: ${e.message}", 0, false)
        }
    }
}

// ---- Request / Service types ----

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