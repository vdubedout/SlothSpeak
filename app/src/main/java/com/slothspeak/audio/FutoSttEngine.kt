package com.slothspeak.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.slothspeak.api.ApiException
import com.slothspeak.data.FutoSttModel
import com.slothspeak.data.FutoSttModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs the FUTO-recommended Nemotron 3.5 multilingual streaming model
 * (sherpa-onnx export) fully on-device.
 *
 * Two entry points share one loaded recognizer:
 * - [transcribe] for an already recorded WAV (follow-up VAD, retries, fallback)
 * - [startStreaming] for live audio frames while the user is speaking
 *
 * The recognizer is process-wide ([get]) because loading the ~650 MB model is
 * expensive and both the ViewModel (live capture) and the foreground service
 * (batch follow-up transcription) need it.
 */
class FutoSttEngine private constructor(private val context: Context) {

    private val initLock = Mutex()
    private var recognizer: OnlineRecognizer? = null

    /**
     * sherpa's recognizer is not safe for concurrent decode calls across
     * streams. Batch transcription holds this for the whole file; live
     * sessions hold it only while feeding a chunk.
     */
    private val engineLock = ReentrantLock()
    private val activeSessions = AtomicInteger(0)

    /**
     * Transcribe [wavFile] (16 kHz mono PCM16, as produced by
     * [VadAudioRecorder]) using [language] ("auto", "en" or "fr").
     */
    suspend fun transcribe(wavFile: File, language: String): String =
        withContext(Dispatchers.Default) {
            val engine = ensureRecognizer()

            val wave = try {
                WaveReader.readWave(wavFile.absolutePath)
            } catch (e: Exception) {
                throw ApiException(
                    "Could not read the recording for on-device transcription.",
                    0,
                    false
                )
            }

            if (wave.sampleRate <= 0) {
                throw ApiException("Invalid sample rate in recording.", 0, false)
            }

            val samples = if (wave.sampleRate == SAMPLE_RATE) {
                wave.samples
            } else {
                // The recorder always produces 16 kHz, but accept other WAVs
                // (e.g. shared files) by resampling to the model's rate.
                resampleLinear(wave.samples, wave.sampleRate, SAMPLE_RATE)
            }
            if (samples.isEmpty()) {
                throw ApiException("The recording is empty.", 0, false)
            }

            engineLock.withLock {
                val stream = engine.createStream("")
                try {
                    // Select the language for this utterance. Empty/auto lets the
                    // model detect it. Tags such as <en-US> are stripped by sherpa.
                    stream.setOption("language", language)

                    var offset = 0
                    while (offset < samples.size) {
                        val count = minOf(CHUNK_SAMPLES, samples.size - offset)
                        stream.acceptWaveform(
                            samples.copyOfRange(offset, offset + count),
                            SAMPLE_RATE
                        )
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        offset += count
                    }

                    stream.inputFinished()
                    while (engine.isReady(stream)) {
                        engine.decode(stream)
                    }

                    engine.getResult(stream).text.trim()
                } catch (e: ApiException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "On-device transcription failed", e)
                    throw ApiException(
                        "On-device transcription failed. Try a shorter recording or " +
                            "re-download the model in Settings.",
                        0,
                        false
                    )
                } finally {
                    stream.release()
                }
            }
        }

    /**
     * Load the model (if needed) and open a live streaming session.
     * Callers must [FutoStreamingSession.finish] or [FutoStreamingSession.release]
     * the returned session.
     */
    suspend fun startStreaming(language: String): FutoStreamingSession =
        withContext(Dispatchers.Default) {
            val engine = ensureRecognizer()
            activeSessions.incrementAndGet()
            try {
                FutoStreamingSession(
                    engine = engine,
                    language = language,
                    engineLock = engineLock,
                    onReleased = { activeSessions.decrementAndGet() }
                )
            } catch (e: Throwable) {
                activeSessions.decrementAndGet()
                throw e
            }
        }

    private suspend fun ensureRecognizer(): OnlineRecognizer = initLock.withLock {
        recognizer ?: buildRecognizer().also {
            recognizer = it
            Log.i(TAG, "On-device STT model loaded")
        }
    }

    private fun buildRecognizer(): OnlineRecognizer {
        val manager = FutoSttModelManager(context)
        val status = manager.status()
        if (!status.installed) {
            throw ApiException(
                "On-device STT model is not downloaded. Open Settings → " +
                    "Speech-to-Text → FUTO to download it.",
                0,
                false
            )
        }

        val dir = manager.modelDir
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(dir, FutoSttModel.ENCODER_FILE).absolutePath,
                decoder = File(dir, FutoSttModel.DECODER_FILE).absolutePath,
                joiner = File(dir, FutoSttModel.JOINER_FILE).absolutePath
            ),
            tokens = File(dir, FutoSttModel.TOKENS_FILE).absolutePath,
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            debug = false,
            provider = "cpu",
            modelType = ""
        )

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
                dither = 0f
            ),
            modelConfig = modelConfig,
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        )

        return try {
            // null AssetManager → resolve model paths from the filesystem.
            OnlineRecognizer(null, config)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize on-device STT model", e)
            throw ApiException(
                "Could not load the on-device STT model. Try deleting and " +
                    "downloading it again in Settings.",
                0,
                false
            )
        }
    }

    fun shutdown() {
        try {
            recognizer?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing on-device STT model", e)
        }
        recognizer = null
    }

    private fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (samples.isEmpty() || fromRate == toRate) return samples
        val outSize = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val output = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate.toDouble()
        for (i in 0 until outSize) {
            val src = i * step
            val index = src.toInt()
            val fraction = (src - index).toFloat()
            val a = samples[index.coerceIn(0, samples.size - 1)]
            val b = samples[(index + 1).coerceIn(0, samples.size - 1)]
            output[i] = a + (b - a) * fraction
        }
        return output
    }

    companion object {
        private const val TAG = "FutoSttEngine"
        const val SAMPLE_RATE = 16_000
        // Nemotron 3.5 multilingual uses a 128-bin log-mel frontend.
        private const val FEATURE_DIM = 128
        // 320 ms of audio per feed; matches the model's smallest sensible
        // streaming step while keeping JNI calls cheap.
        const val CHUNK_SAMPLES = 5_120

        @Volatile
        private var shared: FutoSttEngine? = null

        /** Process-wide engine so live capture and batch follow-up share one model. */
        fun get(context: Context): FutoSttEngine {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: FutoSttEngine(context.applicationContext).also { shared = it }
            }
        }

        /**
         * Drop the loaded model, e.g. when the user switches away from FUTO.
         * Does nothing while a live streaming session is active.
         */
        fun releaseShared() {
            synchronized(this) {
                val engine = shared ?: return
                if (engine.activeSessions.get() > 0) return
                engine.shutdown()
                shared = null
            }
        }
    }
}

/**
 * Live recognition session fed with microphone frames.
 *
 * Audio is accumulated into [FutoSttEngine.CHUNK_SAMPLES] (320 ms) chunks so
 * partial results update a few times per second without a JNI call per frame.
 */
class FutoStreamingSession internal constructor(
    private val engine: OnlineRecognizer,
    language: String,
    private val engineLock: ReentrantLock,
    private val onReleased: () -> Unit
) {
    private val stream = engine.createStream("")
    private val pending = FloatArray(FutoSttEngine.CHUNK_SAMPLES)
    private var pendingCount = 0
    private var released = false
    private var partial = ""

    init {
        // "auto" lets the model detect the language; tags are stripped by sherpa.
        stream.setOption("language", language)
    }

    /**
     * Feed [count] PCM16 samples. Returns the current partial transcript.
     * Safe to call from the recorder's IO thread.
     */
    fun accept(samples: ShortArray, count: Int): String = engineLock.withLock {
        if (released || count <= 0) return@withLock partial
        try {
            var offset = 0
            var fed = false
            while (offset < count) {
                val take = minOf(count - offset, FutoSttEngine.CHUNK_SAMPLES - pendingCount)
                for (i in 0 until take) {
                    pending[pendingCount + i] = samples[offset + i] / 32768f
                }
                pendingCount += take
                offset += take
                if (pendingCount == FutoSttEngine.CHUNK_SAMPLES) {
                    feed(pending)
                    pendingCount = 0
                    fed = true
                }
            }
            if (fed) {
                partial = engine.getResult(stream).text
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Live on-device transcription failed", e)
        }
        partial
    }

    /** Flush the remainder and return the final transcript. */
    fun finish(): String = engineLock.withLock {
        if (released) return@withLock partial
        try {
            if (pendingCount > 0) {
                feed(pending.copyOf(pendingCount))
                pendingCount = 0
            }
            stream.inputFinished()
            while (engine.isReady(stream)) {
                engine.decode(stream)
            }
            partial = engine.getResult(stream).text.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to finalize on-device transcription", e)
        } finally {
            releaseLocked()
        }
        partial
    }

    fun release() = engineLock.withLock {
        releaseLocked()
    }

    private fun releaseLocked() {
        if (released) return
        released = true
        try {
            stream.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing live stream", e)
        }
        onReleased()
    }

    private fun feed(chunk: FloatArray) {
        stream.acceptWaveform(chunk, FutoSttEngine.SAMPLE_RATE)
        while (engine.isReady(stream)) {
            engine.decode(stream)
        }
    }

    private companion object {
        private const val TAG = "FutoStreamingSession"
    }
}
