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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * On-device wake word detection using openWakeWord ONNX models.
 *
 * Architecture (verified against actual ONNX graphs):
 *   Audio (16kHz mono 16-bit) -> 1280-sample chunks (80ms)
 *   -> melspectrogram.onnx (OUT: [time, 1, 1, 32], ~8 frames/chunk)
 *   -> ring buffer of mel frames (capped at MEL_WINDOW=76)
 *   -> if >=76: embedding_model.onnx (IN: [1, 76, 32, 1], OUT: [1, 1, 1, 96])
 *   -> ring buffer of embedding vectors (capped at EMB_WINDOW=16)
 *   -> if >=16: hey_jarvis_v0.1.onnx (IN: [1, 16, 96], OUT: [1, 1])
 *   -> score >= 0.5 -> fire (1s refractory)
 *
 * Uses only ONNX Runtime Java API patterns proven to compile in this SDK.
 * Shape validation reads from ONNX metadata (fails loudly on mismatch).
 * Native buffers copied to Kotlin float arrays before Result.close().
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

    // ONNX I/O names (resolved at init)
    private var melInName: String = ""
    private var melOutName: String = ""
    private var embInName: String = ""
    private var embOutName: String = ""
    private var wwInName: String = ""
    private var wwOutName: String = ""

    @Volatile private var running = false
    private var wakeWordDetected = false
    private var refractoryUntilMs: Long = 0

    // Ring buffers (reference caps)
    private val melRing = ArrayDeque<kotlin.FloatArray>()
    private val embRing = ArrayDeque<kotlin.FloatArray>()
    private val rawBuffer = kotlin.FloatArray(16000 * 10) // 10s raw audio ring
    private var rawWritePos = 0
    private var rawTotalWritten = 0
    private var predictionCount = 0

    // Pre-allocated I/O buffer (1760 samples max input slice)
    private val inputBytes = ByteBuffer.allocate((FRAME_SIZE + 480) * 4)

    // mel/embedding output copied here before close()
    private var melOutFloat: kotlin.FloatArray? = null
    private var embOutFloat: kotlin.FloatArray? = null

    private var disabledForSession = false
    private var consecutiveFailures = 0
    // Diagnostics: peak sample level and max classifier score, reset every 2s
    private var diagMaxAbsSample: Int = 0
    private var diagMaxScore: Float = 0.0f
    private var diagLastLogMs: Long = 0L

    val isRunning: Boolean get() = running
    val wasDetected: Boolean get() = wakeWordDetected

    fun start() {
        if (running) return
        if (disabledForSession) { Log.w(TAG, "Disabled for session"); return }
        if (!checkPermissions()) return
        if (ortEnv == null) {
            if (!initializeOnnx()) { disabledForSession = true; return }
        }
        wakeWordDetected = false; melRing.clear(); embRing.clear(); rawWritePos = 0; rawTotalWritten = 0; predictionCount = 0; running = true
        detectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        detectJob = detectScope!!.launch {
            try { runDetectionLoop() }
            catch (e: Exception) { Log.e(TAG, "Error: ${e.message}", e); running = false; stopRecording() }
        }
    }

    fun stop() {
        running = false
        val job = detectJob
        detectJob = null
        detectScope?.cancel()
        detectScope = null
        stopRecording()
        // Close the ONNX sessions only once the detection loop has actually
        // exited. Closing them underneath a running processChunk() — which is
        // exactly what happens when we release from the onDetected callback or
        // when the app goes to the background mid-chunk — native-crashes with a
        // SIGSEGV inside OrtSession.run().
        if (job == null) releaseOnnx() else job.invokeOnCompletion { releaseOnnx() }
    }

    fun release() { stop() }
    fun resetDisabled() { disabledForSession = false }

    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) { Log.w(TAG, "No RECORD_AUDIO"); return false }
        return true
    }

    // ---- ONNX init ----

    private fun initializeOnnx(): Boolean {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            melSession = ortEnv!!.createSession(melSpectrogramPath)
            embSession = ortEnv!!.createSession(embeddingModelPath)
            wwSession = ortEnv!!.createSession(wakeWordModelPath)

            // Cache I/O names (single input/output per model)
            for (n in melSession!!.getInputNames()) { melInName = n; break }
            for (n in melSession!!.getOutputNames()) { melOutName = n; break }
            for (n in embSession!!.getInputNames()) { embInName = n; break }
            for (n in embSession!!.getOutputNames()) { embOutName = n; break }
            for (n in wwSession!!.getInputNames()) { wwInName = n; break }
            for (n in wwSession!!.getOutputNames()) { wwOutName = n; break }

            // Fail-loud shape assertion at init (mismatched models must disable the
            // detector immediately — per-frame failures are circuit-broken in processChunk).
            val embIn = (embSession!!.inputInfo.values.first().getInfo() as ai.onnxruntime.TensorInfo).shape
            val wwIn = (wwSession!!.inputInfo.values.first().getInfo() as ai.onnxruntime.TensorInfo).shape
            require(embIn.size == 4 &&
                (embIn[1] == MEL_WINDOW.toLong() || embIn[1] == -1L) &&
                (embIn[2] == 32L || embIn[2] == -1L) &&
                (embIn[3] == 1L || embIn[3] == -1L)) {
                "embedding model input shape mismatch: ${embIn.toList()} (expected [N,$MEL_WINDOW,32,1])"
            }
            require(wwIn.size == 3 &&
                (wwIn[0] == 1L || wwIn[0] == -1L) &&
                (wwIn[1] == EMB_WINDOW.toLong() || wwIn[1] == -1L) &&
                (wwIn[2] == EMB_DIM.toLong() || wwIn[2] == -1L)) {
                "wake word model input shape mismatch: ${wwIn.toList()} (expected [1,$EMB_WINDOW,$EMB_DIM])"
            }

            Log.i(TAG, "Wake word ONNX initialized (mel=$melInName->$melOutName, emb=$embInName->$embOutName, ww=$wwInName->$wwOutName)")
            return true
        } catch (e: OrtException) { Log.e(TAG, "ORT error", e); releaseOnnx(); return false }
        catch (e: Exception) { Log.e(TAG, "Init error (shape validation failed — model mismatch?)", e); releaseOnnx(); return false }
    }

    private fun releaseOnnx() {
        try { wwSession?.close() } catch (_: Exception) {}; wwSession = null
        try { embSession?.close() } catch (_: Exception) {}; embSession = null
        try { melSession?.close() } catch (_: Exception) {}; melSession = null
    }

    // ---- Detection loop ----

    private suspend fun runDetectionLoop() {
        val bufSize = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SIZE * 2)
        val readBuf = kotlin.ShortArray(bufSize)
        audioRecord = try { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize) }
        catch (e: Exception) { Log.e(TAG, "AudioRecord failed", e); running = false; return }
        try { audioRecord!!.startRecording() } catch (e: Exception) { Log.e(TAG, "Start failed", e); running = false; return }

        val chunkBuf = kotlin.ShortArray(FRAME_SIZE); var ci = 0
        while (running && (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true)) {
            try {
                val n = audioRecord!!.read(readBuf, 0, bufSize)
                if (n <= 0) { delay(10); continue }
                for (i in 0 until n) { chunkBuf[ci] = readBuf[i]; ci++
                    if (ci >= FRAME_SIZE) { processChunk(chunkBuf); ci = 0 } }
            } catch (e: Exception) { Log.w(TAG, "Loop", e); delay(50) }
        }
        stopRecording()
    }

    // ---- Per-chunk pipeline ----

    /** @return max absolute sample value in this chunk (for diagnostics) */
    private fun updateAudioDiagnostics(chunk: kotlin.ShortArray): Int {
        var peak = 0
        for (i in 0 until FRAME_SIZE) {
            val sampleVal = chunk[i].toInt()
            val absolute = if (sampleVal < 0) -sampleVal else sampleVal
            if (absolute > peak) peak = absolute
        }
        if (peak > diagMaxAbsSample) diagMaxAbsSample = peak
        return peak
    }

    private fun maybeLogDiagnostics(score: Float) {
        if (score > diagMaxScore) diagMaxScore = score
        val now = System.currentTimeMillis()
        if (now - diagLastLogMs < 2000L) return
        diagLastLogMs = now
        if (diagMaxAbsSample > 0 || diagMaxScore > 0.001f) {
            Log.d(TAG, "audio peak=" + diagMaxAbsSample + ", max score=" + diagMaxScore)
        }
        diagMaxAbsSample = 0
        diagMaxScore = 0.0f
    }

    private suspend fun processChunk(chunk: kotlin.ShortArray) {
        try {
            updateAudioDiagnostics(chunk)
            val env = ortEnv ?: return

            // ---- Raw audio ring buffer (reference: openwakeword-android audioLoop) ----
            for (i in 0 until FRAME_SIZE) {
                rawBuffer[rawWritePos] = chunk[i].toFloat() // RAW int16 as float32
                rawWritePos = (rawWritePos + 1) % rawBuffer.size
            }
            rawTotalWritten += FRAME_SIZE
            if (rawTotalWritten < FRAME_SIZE + MEL_CONTEXT_SAMPLES) return

            // Slice = last 1760 samples (1280 new + 480 overlap context)
            val sliceLen = FRAME_SIZE + MEL_CONTEXT_SAMPLES
            val audioSlice = kotlin.FloatArray(sliceLen)
            var readPos = (rawWritePos - sliceLen + rawBuffer.size) % rawBuffer.size
            for (i in 0 until sliceLen) { audioSlice[i] = rawBuffer[readPos]; readPos = (readPos + 1) % rawBuffer.size }

            // ======== Step 1: Mel-spectrogram (input [1,1760], output [1,1,T,32]) ========
            inputBytes.clear(); inputBytes.order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sliceLen) { inputBytes.putFloat(audioSlice[i]) }
            inputBytes.rewind()

            val melTensor = OnnxTensor.createTensor(env, inputBytes.asFloatBuffer(), makeLongArray(1L, sliceLen.toLong()))
            val melResult = melSession!!.run(java.util.HashMap(mapOf(melInName to melTensor)))
            melTensor.close()

            val melRaw = melResult.get(melOutName).orElse(null) as? ai.onnxruntime.OnnxTensor
            if (melRaw == null) { melResult.close(); noteFailure("mel output tensor missing"); return }
            val melShape = melRaw.getInfo().getShape()
            val timeDim = melShape[2].toInt()   // runtime shape [1,1,T,bins]
            val numBins = melShape[3].toInt()
            val totalFloats = timeDim * numBins
            if (melOutFloat == null || melOutFloat!!.size < totalFloats) { melOutFloat = kotlin.FloatArray(totalFloats) }
            val melBuf = melRaw.getFloatBuffer(); melBuf.rewind()
            for (i in 0 until totalFloats) { melOutFloat!![i] = melBuf.get() }
            melResult.close()

            // Post-process per reference: /10 + 2 — REQUIRED before the embedding model
            for (t in 0 until timeDim) {
                val off = t * numBins
                val frame = kotlin.FloatArray(numBins)
                for (b in 0 until numBins) { frame[b] = melOutFloat!![off + b] / 10.0f + 2.0f }
                melRing.addLast(frame)
                if (melRing.size > MEL_BUFFER_MAX) melRing.removeFirst()
            }
            if (melRing.size < MEL_WINDOW) return

            // ======== Step 2: Embedding on the LAST 76 mel frames ========
            val embBytes = ByteBuffer.allocate(MEL_WINDOW * numBins * 4).order(ByteOrder.LITTLE_ENDIAN)
            val startIdx = melRing.size - MEL_WINDOW
            var skipped = 0
            for (fr in melRing) { if (skipped++ < startIdx) continue; for (b in 0 until numBins) { embBytes.putFloat(fr[b]) } }
            embBytes.rewind()

            val embTensor = OnnxTensor.createTensor(env, embBytes.asFloatBuffer(), makeLongArray(1L, MEL_WINDOW.toLong(), numBins.toLong(), 1L))
            val embResult = embSession!!.run(java.util.HashMap(mapOf(embInName to embTensor)))
            embTensor.close()

            val embRaw = embResult.get(embOutName).orElse(null) as? ai.onnxruntime.OnnxTensor
            if (embRaw == null) { embResult.close(); noteFailure("embedding output tensor missing"); return }
            val embBuf = embRaw.getFloatBuffer()
            val embLen = embBuf.capacity()
            if (embOutFloat == null || embOutFloat!!.size < embLen) { embOutFloat = kotlin.FloatArray(embLen) }
            embBuf.rewind()
            for (i in 0 until embLen) { embOutFloat!![i] = embBuf.get() }
            embResult.close()

            val embVec = kotlin.FloatArray(embLen)
            for (i in 0 until embLen) { embVec[i] = embOutFloat!![i] }
            embRing.addLast(embVec)
            if (embRing.size > EMB_BUFFER_MAX) embRing.removeFirst()
            if (embRing.size < EMB_WINDOW) return

            // ======== Step 3: Classifier (skip first 5 predictions per reference) ========
            predictionCount++
            if (predictionCount <= SKIP_INITIAL_PREDICTIONS) return

            val wwBytes = ByteBuffer.allocate(EMB_WINDOW * EMB_DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
            val eStart = embRing.size - EMB_WINDOW
            var eSkipped = 0
            for (v in embRing) { if (eSkipped++ < eStart) continue; for (i in 0 until EMB_DIM) { wwBytes.putFloat(v[i]) } }
            wwBytes.rewind()

            val wwTensor = OnnxTensor.createTensor(env, wwBytes.asFloatBuffer(), makeLongArray(1L, EMB_WINDOW.toLong(), EMB_DIM.toLong()))
            val wwResult = wwSession!!.run(java.util.HashMap(mapOf(wwInName to wwTensor)))
            wwTensor.close()

            val wwRaw = wwResult.get(wwOutName).orElse(null) as? ai.onnxruntime.OnnxTensor
            if (wwRaw == null) { wwResult.close(); noteFailure("classifier output tensor missing"); return }
            val scoreBuf = wwRaw.getFloatBuffer()
            val probability = if (scoreBuf.capacity() > 0) scoreBuf.get(0) else 0.0f
            wwResult.close()

            maybeLogDiagnostics(probability)

            if (probability >= PROB_THRESHOLD && System.currentTimeMillis() >= refractoryUntilMs) {
                wakeWordDetected = true
                Log.i(TAG, "Wake word 'Hey Jarvis' detected! (score=$probability)")
                refractoryUntilMs = System.currentTimeMillis() + REFRACTORY_MS
                running = false
                onDetected(); stopRecording()
                return // loop is done — don't touch the sessions after release
            }
            consecutiveFailures = 0
        } catch (e: Exception) { noteFailure("processChunk", e) }
    }

    /** Persistent-inference-failure circuit breaker: after MAX_CONSECUTIVE_FAILURES
     *  consecutive failures, log once and disable the detector for the session
     *  (prevents per-frame log spam + mic/battery burn on corrupt models). */
    private fun noteFailure(where: String, e: Exception? = null) {
        if (disabledForSession) return // already tripped — don't spam
        consecutiveFailures++
        if (e != null) Log.w(TAG, "$where (failure $consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)", e)
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Wake word disabled for session after $consecutiveFailures consecutive failures ($where)")
            disabledForSession = true
            running = false
            stopRecording()
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
        const val MEL_WINDOW = 76
        const val EMB_WINDOW = 16
        const val EMB_DIM = 96
        const val MEL_CONTEXT_SAMPLES = 480 // reference: 160*3 STFT overlap
        const val MEL_BUFFER_MAX = 970
        const val EMB_BUFFER_MAX = 120
        const val SKIP_INITIAL_PREDICTIONS = 5
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val PROB_THRESHOLD = 0.5f
        private const val REFRACTORY_MS = 1000L
    }
}

// ---- Helpers (proven to compile in this SDK) ----

private fun <K, V> mapOf(key: K, value: V): java.util.HashMap<K, V> {
    val m = java.util.HashMap<K, V>()
    m.put(key, value)
    return m
}

private fun makeLongArray(vararg values: Long): LongArray {
    val arr = LongArray(values.size)
    for (i in 0 until values.size) { arr[i] = values[i] }
    return arr
}