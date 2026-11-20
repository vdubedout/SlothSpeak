package com.slothspeak.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM recorder for live (streaming) speech recognition.
 *
 * Unlike [AudioRecorder] (MediaRecorder → AAC/m4a), this produces raw 16 kHz
 * mono PCM16 frames so they can be fed to the on-device recognizer while the
 * user is still speaking. The same audio is also written to a WAV file so the
 * existing batch transcription path can be used as a fallback.
 *
 * VAD is optional: when [enableVadAutoStop] is false the recording continues
 * until [stopAndAwait] is called (or the 5 minute cap is hit).
 */
class PcmAudioRecorder(
    private val context: Context,
    private val enableVadAutoStop: Boolean,
    private val silenceAfterSpeechMs: Int = 1800,
    private val noSpeechTimeoutMs: Int = 8000,
    private val maxRecordingMs: Long = MAX_RECORDING_MS,
    private val onFrame: ((ShortArray, Int) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var vadSilero: VadSilero? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null

    @Volatile
    private var stopRequested = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    data class State(
        val isRecording: Boolean = false,
        val isSpeechDetected: Boolean = false,
        val speechEndedByVad: Boolean = false,
        val noSpeechDetected: Boolean = false,
        val maxDurationReached: Boolean = false,
        val durationMs: Long = 0
    )

    /**
     * Start capturing. Returns the WAV destination, or null when the microphone
     * (or VAD) could not be initialized.
     */
    fun start(
        scope: CoroutineScope,
        preferredDevice: AudioDeviceInfo? = null,
        useBluetoothAudioSource: Boolean = false
    ): File? {
        if (_state.value.isRecording) return outputFile

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return null
        }

        stopRequested = false
        val file = File(context.cacheDir, "stream_recording_${System.currentTimeMillis()}.wav")
        outputFile = file

        if (enableVadAutoStop) {
            try {
                vadSilero = Vad.builder()
                    .setContext(context)
                    .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_512)
                    .setMode(Mode.NORMAL)
                    .setSpeechDurationMs(250)
                    .setSilenceDurationMs(silenceAfterSpeechMs)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Silero VAD", e)
                return null
            }
        }

        val audioSource = if (useBluetoothAudioSource) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val record = try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, FRAME_SIZE_SAMPLES * 2 * 4)
            AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            cleanupResources()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            cleanupResources()
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            cleanupResources()
            return null
        }
        preferredDevice?.let { record.setPreferredDevice(it) }
        audioRecord = record

        val raf = try {
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(wavHeader(0L))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recording file", e)
            record.release()
            audioRecord = null
            cleanupResources()
            return null
        }

        _state.value = State(isRecording = true)
        record.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            recordAndStream(record, raf)
        }

        return file
    }

    private suspend fun recordAndStream(record: AudioRecord, raf: RandomAccessFile) {
        val frameBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        val startTime = System.currentTimeMillis()
        var hasSpeechBeenDetected = false
        var dataBytes = 0L

        try {
            while (currentCoroutineContext()[Job]?.isActive == true && !stopRequested) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= maxRecordingMs) {
                    Log.d(TAG, "Max recording duration reached")
                    _state.value = _state.value.copy(
                        maxDurationReached = true,
                        durationMs = elapsed
                    )
                    break
                }

                val read = record.read(frameBuffer, 0, FRAME_SIZE_SAMPLES)
                if (read <= 0) {
                    if (stopRequested) break
                    if (read < 0) {
                        Log.e(TAG, "AudioRecord.read() error: $read")
                        break
                    }
                    continue
                }

                // Write to the WAV file immediately so audio survives any crash.
                val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in 0 until read) {
                    bytes.putShort(frameBuffer[sample])
                }
                raf.write(bytes.array())
                dataBytes += read * 2L

                // Feed the live recognizer before doing anything else.
                try {
                    onFrame?.invoke(frameBuffer, read)
                } catch (e: Exception) {
                    Log.e(TAG, "Live transcription frame failed", e)
                }

                val updatedElapsed = System.currentTimeMillis() - startTime
                if (enableVadAutoStop) {
                    val isSpeech = vadSilero?.isSpeech(frameBuffer) == true
                    when {
                        isSpeech && !hasSpeechBeenDetected -> {
                            hasSpeechBeenDetected = true
                            _state.value = _state.value.copy(
                                isSpeechDetected = true,
                                durationMs = updatedElapsed
                            )
                        }
                        !isSpeech && hasSpeechBeenDetected -> {
                            _state.value = _state.value.copy(
                                speechEndedByVad = true,
                                durationMs = updatedElapsed
                            )
                            break
                        }
                        else -> {
                            _state.value = _state.value.copy(durationMs = updatedElapsed)
                        }
                    }
                    if (!hasSpeechBeenDetected && updatedElapsed >= noSpeechTimeoutMs) {
                        Log.d(TAG, "No speech detected within ${noSpeechTimeoutMs}ms")
                        _state.value = _state.value.copy(
                            noSpeechDetected = true,
                            durationMs = updatedElapsed
                        )
                        break
                    }
                } else {
                    _state.value = _state.value.copy(
                        isSpeechDetected = true,
                        durationMs = updatedElapsed
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during PCM recording", e)
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
            audioRecord = null

            try {
                if (dataBytes > 0) {
                    raf.seek(4)
                    raf.write(intToLittleEndian((WAV_HEADER_SIZE - 8 + dataBytes).toInt()))
                    raf.seek(40)
                    raf.write(intToLittleEndian(dataBytes.toInt()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to finalize WAV header", e)
            }
            try {
                raf.close()
            } catch (_: Exception) {
            }

            try {
                vadSilero?.close()
            } catch (_: Exception) {
            }
            vadSilero = null

            _state.value = _state.value.copy(isRecording = false)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun wavHeader(dataBytes: Long): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((WAV_HEADER_SIZE - 8 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        return header.array()
    }

    /**
     * Stop the microphone, wait for the WAV to be finalized, and return the file.
     */
    suspend fun stopAndAwait(): File? {
        stopRequested = true
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        recordingJob?.join()
        recordingJob = null
        return outputFile
    }

    /** Best-effort teardown without joining. Used when the ViewModel is cleared. */
    fun release() {
        stopRequested = true
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun cleanupResources() {
        try {
            vadSilero?.close()
        } catch (_: Exception) {
        }
        vadSilero = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "PcmAudioRecorder"
        const val MAX_RECORDING_MS = 5L * 60 * 1000
        private const val FRAME_SIZE_SAMPLES = 512
        private const val SAMPLE_RATE = 16_000
        private const val WAV_HEADER_SIZE = 44
    }
}
