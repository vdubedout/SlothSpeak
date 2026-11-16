package com.slothspeak.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.HashMap

/**
 * On-device wake word detection using openWakeWord ONNX models.
 *
 * Listens for "Hey Jarvis" on a dedicated coroutine using Android's
 * AudioRecord API for streaming 16 kHz mono 16-bit PCM capture,
 * feeding 80 ms frames (1280 samples) through a 3-stage ONNX pipeline:
 * melspectrogram -> embedding_model -> hey_jarvis classifier.
 *
 * Pre-allocates reusable float buffers to avoid native-memory use-after-free
 * when ONNX Result objects are closed.
 *
 * Invokes [onDetected] callback when the score >= 0.5.
 * Includes a ~1 second refractory period after detection.
 *
 * Safe no-op when RECORD_AUDIO permission is missing.
 */
class WakeWordDetector(
    private val context: Context,
    private val melSpectrogramPath: String,
    private val embeddingModelPath: String,
    private val wakeWordModelPath: String,
    private val onDetected: () -> Unit
) {
    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wwSession: OrtSession? = null
    private var audioRecord: AudioRecord? = null
    private var detectJob: kotlinx.coroutines.Job? = null
    private var detectScope: CoroutineScope? = null
    @Volatile private var running = false
    private var wakeWordDetected = false
    private var refractoryUntilMs: Long = 0

    // ---- Pre-allocated buffers (reused per frame, no allocation in hot path) ----

    // Input frame: float32 array of 1280 samples
    private var inputFrameBytes: ByteBuffer = ByteBuffer.allocate(FRAME_SIZE * 4)
    private var inputShape: kotlin.LongArray = jlongArray(1L, FRAME_SIZE.toLong())

    // Mel-spectrogram output: pre-allocated based on inferred shape (96 frames x 32 mel bins)
    private var melOutBuffer: FloatBuffer = FloatBuffer.allocate(MEL_FRAMES * MEL_BINS)
    private var melShape: kotlin.LongArray = jlongArray(1L, 1L, MEL_FRAMES.toLong(), MEL_BINS.toLong())

    // Embedding output: pre-allocated (common sizes: 512 or 1024)
    private var embOutBuffer: FloatBuffer = FloatBuffer.allocate(EMBEDDING_SIZE)
    private var embShape: kotlin.LongArray = jlongArray(1L, EMBEDDING_SIZE.toLong())

    // Score output: single float
    private var scoreBuffer: FloatBuffer = FloatBuffer.allocate(1)

    // Reusable HashMap for ONNX inputs (cleared between runs)
    private val inputMap = java.util.HashMap<String, OnnxTensorLike>()

    val isRunning: Boolean get() = running
    val wasDetected: Boolean get() = wakeWordDetected

    fun start() {
        if (running) return
        if (!checkPermissions()) return

        if (ortEnv == null) {
            if (!initializeOnnx()) {
                Log.e(TAG, "Failed to initialize ONNX Runtime -- wake word unavailable")
                return
            }
        }

        wakeWordDetected = false
        running = true

        detectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        detectJob = detectScope!!.launch {
            try {
                runDetectionLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}", e)
                running = false
                stopRecording()
            }
        }
    }

    fun stop() {
        running = false
        detectJob?.cancel()
        detectJob = null
        detectScope = null
        stopRecording()
    }

    fun release() {
        stop()
        releaseOnnx()
    }

    // ---- Permission ----

    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return false
        }
        return true
    }

    // ---- ONNX init ----

    private fun initializeOnnx(): Boolean {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            melSession = ortEnv!!.createSession(melSpectrogramPath)
            embSession = ortEnv!!.createSession(embeddingModelPath)
            wwSession = ortEnv!!.createSession(wakeWordModelPath)
            return true
        } catch (e: OrtException) {
            Log.e(TAG, "ONNX Runtime error: ${e.message}", e)
            releaseOnnx()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX models", e)
            releaseOnnx()
            return false
        }
    }

    private fun releaseOnnx() {
        try { wwSession?.close() } catch (_: Exception) {}
        wwSession = null
        try { embSession?.close() } catch (_: Exception) {}
        embSession = null
        try { melSession?.close() } catch (_: Exception) {}
        melSession = null
    }

    // ---- Detection loop ----

    private suspend fun runDetectionLoop() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val readBufSize = maxOf(minBuf, FRAME_SIZE * 2)
        val readBuffer = kotlin.ShortArray(readBufSize)

        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, readBufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            running = false
            return
        }

        try {
            audioRecord!!.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            running = false
            return
        }

        val melSess = melSession ?: return
        val embSess = embSession ?: return
        val wwSess = wwSession ?: return

        val frameBuf = kotlin.ShortArray(FRAME_SIZE)
        var frameIdx = 0

        while (running && (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true)) {
            try {
                val n = audioRecord!!.read(readBuffer, 0, readBufSize)
                if (n <= 0) {
                    delay(10)
                    continue
                }
                for (i in 0 until n) {
                    frameBuf[frameIdx] = readBuffer[i]
                    frameIdx++
                    if (frameIdx >= FRAME_SIZE) {
                        processFrame(frameBuf, melSess, embSess, wwSess)
                        frameIdx = 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Detection loop error", e)
                delay(50)
            }
        }

        stopRecording()
    }

    private suspend fun processFrame(
        frame: kotlin.ShortArray,
        melSess: OrtSession,
        embSess: OrtSession,
        wwSess: OrtSession
    ) {
        val now = System.currentTimeMillis()
        if (now < refractoryUntilMs) return

        try {
            // ---- Step 0: pack float32 input bytes ----
            inputFrameBytes.clear()
            inputFrameBytes.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until FRAME_SIZE) {
                inputFrameBytes.putFloat(frame[i].toFloat() / 32768.0f)
            }
            inputFrameBytes.rewind()

            val melInName = melSess.getInputNames().iterator().next()
            val melOutName = melSess.getOutputNames().iterator().next()

            // ---- Step 1: melspectrogram ----
            val melInput = OnnxTensor.createTensor(ortEnv!!, inputFrameBytes.asFloatBuffer(), inputShape)
            inputMap.clear(); inputMap.put(melInName, melInput)
            val melResult = melSess.run(inputMap)
            melInput.close()

            val melOutTensor = melResult.get(melOutName) as? OnnxTensor ?: return
            // COPY data OUT before close() to avoid use-after-free
            val melRaw = melOutTensor.getFloatBuffer()
            copyFloatBuffer(melRaw, melOutBuffer)
            melResult.close()

            val embInName = embSess.getInputNames().iterator().next()
            val embOutName = embSess.getOutputNames().iterator().next()

            // ---- Step 2: embedding model ----
            val embInput = OnnxTensor.createTensor(ortEnv!!, melOutBuffer, melShape)
            inputMap.clear(); inputMap.put(embInName, embInput)
            val embResult = embSess.run(inputMap)
            embInput.close()

            val embOutTensor = embResult.get(embOutName) as? OnnxTensor ?: return
            val embRaw = embOutTensor.getFloatBuffer()
            copyFloatBuffer(embRaw, embOutBuffer)
            embResult.close()

            val wwInName = wwSess.getInputNames().iterator().next()
            val wwOutName = wwSess.getOutputNames().iterator().next()

            // ---- Step 3: wake word classifier ----
            val wwInput = OnnxTensor.createTensor(ortEnv!!, embOutBuffer, embShape)
            inputMap.clear(); inputMap.put(wwInName, wwInput)
            val wwResult = wwSess.run(inputMap)
            wwInput.close()

            val wwOutTensor = wwResult.get(wwOutName) as? OnnxTensor ?: return
            val scoreRaw = wwOutTensor.getFloatBuffer()
            copyFloatBuffer(scoreRaw, scoreBuffer)
            wwResult.close()

            val probability = if (scoreBuffer.capacity() > 0) scoreBuffer.get(0) else 0.0f

            if (probability >= PROB_THRESHOLD) {
                wakeWordDetected = true
                Log.i(TAG, "Wake word 'Hey Jarvis' detected! (score=$probability)")
                refractoryUntilMs = System.currentTimeMillis() + REFRACTORY_MS
                running = false
                onDetected()
                stopRecording()
            }

        } catch (e: ai.onnxruntime.OrtException) {
            Log.w(TAG, "ONNX Runtime error", e)
        } catch (e: Exception) {
            Log.w(TAG, "Frame processing error", e)
        }
    }

    /** Copies float data from a source FloatBuffer into a pre-allocated destination. */
    private fun copyFloatBuffer(src: FloatBuffer, dst: FloatBuffer) {
        src.rewind()
        val n = minOf(src.remaining(), dst.capacity())
        dst.clear()
        dst.put(src) // reads up to src.remaining() floats
        dst.rewind()
    }

    private fun stopRecording() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        const val FRAME_SIZE = 1280        // 80ms at 16kHz
        private const val PROB_THRESHOLD = 0.5f
        private const val REFRACTORY_MS = 1000L
        private const val MEL_FRAMES = 96   // melspectrogram time frames
        private const val MEL_BINS = 32     // mel frequency bins
        private const val EMBEDDING_SIZE = 512  // embedding dimension
    }
}

// ---- Helpers ----

private fun jlongArray(vararg values: kotlin.Long): kotlin.LongArray {
    val arr = kotlin.LongArray(values.size)
    for (i in 0 until values.size) {
        arr[i] = values[i]
    }
    return arr
}