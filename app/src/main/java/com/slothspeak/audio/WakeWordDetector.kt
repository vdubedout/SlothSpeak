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
 * Invokes [onDetected] callback when the score >= 0.5.
 * Includes a ~1 second refractory period after detection.
 *
 * Safe no-op when RECORD_AUDIO permission is missing.
 *
 * Usage:
 *   val detector = WakeWordDetector(context, melPath, embPath, wwPath) { onWakeWordDetected() }
 *   detector.start()
 *   // ... later ...
 *   detector.stop()
 *   detector.release()
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

    private var floatBuffer = kotlin.FloatArray(FRAME_SIZE)

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
            // Convert short[] to float32[], normalize int16 range
            for (i in 0 until FRAME_SIZE) {
                floatBuffer[i] = frame[i].toFloat() / 32768.0f
            }

            // Pack float data into a little-endian byte buffer, then wrap as FloatBuffer
            val byteBuf = java.nio.ByteBuffer.allocate(FRAME_SIZE * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until FRAME_SIZE) {
                byteBuf.putFloat(floatBuffer[i])
            }
            byteBuf.rewind()
            val inputShape = jlongArray(1L, FRAME_SIZE.toLong())

            // --- melspectrogram ---
            val melInput = OnnxTensor.createTensor(ortEnv!!, byteBuf.asFloatBuffer(), inputShape)
            val melInName = melSess.getInputNames().iterator().next()
            val melOutName = melSess.getOutputNames().iterator().next()

            val melResult = melSess.run(java.util.HashMap(mapOf(melInName to melInput)))
            melInput.close()

            val melOutTensor = melResult.get(melOutName) as? OnnxTensor ?: return
            val melBuf = melOutTensor.getFloatBuffer()
            val melShape = melOutTensor.getInfo().shape
            melResult.close()

            // --- embedding model ---
            val embInput = OnnxTensor.createTensor(ortEnv!!, melBuf, melShape)
            val embInName = embSess.getInputNames().iterator().next()
            val embOutName = embSess.getOutputNames().iterator().next()

            val embResult = embSess.run(java.util.HashMap(mapOf(embInName to embInput)))
            embInput.close()

            val embOutTensor = embResult.get(embOutName) as? OnnxTensor ?: return
            val embBuf = embOutTensor.getFloatBuffer()
            val embShape = embOutTensor.getInfo().shape
            embResult.close()

            // --- wake word classifier ---
            val wwInput = OnnxTensor.createTensor(ortEnv!!, embBuf, embShape)
            val wwInName = wwSess.getInputNames().iterator().next()
            val wwOutName = wwSess.getOutputNames().iterator().next()

            val wwResult = wwSess.run(java.util.HashMap(mapOf(wwInName to wwInput)))
            wwInput.close()

            val wwOutTensor = wwResult.get(wwOutName) as? OnnxTensor ?: return
            val scoreBuf = wwOutTensor.getFloatBuffer()
            wwResult.close()

            val probability = if (scoreBuf.capacity() > 0) scoreBuf.get(0) else 0.0f

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

    private fun stopRecording() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        const val FRAME_SIZE = 1280
        private const val PROB_THRESHOLD = 0.5f
        private const val REFRACTORY_MS = 1000L
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

private fun <K, V> mapOf(key: K, value: V): java.util.HashMap<K, V> {
    val m = java.util.HashMap<K, V>()
    m.put(key, value)
    return m
}