package com.slothspeak.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import java.io.InputStream
import java.io.OutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slothspeak.audio.AudioFocusManager
import com.slothspeak.audio.AudioRecorder
import com.slothspeak.audio.BluetoothAudioRouter
import com.slothspeak.audio.FutoSttEngine
import com.slothspeak.audio.FutoStreamingSession
import com.slothspeak.audio.PcmAudioRecorder
import com.slothspeak.audio.VadAudioRecorder
import com.slothspeak.audio.WakeWordDetector
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderManager
import com.slothspeak.data.FutoSttModelManager
import com.slothspeak.data.LlmProviderCatalog
import com.slothspeak.data.LlmProviderUiState
import com.slothspeak.data.db.SlothSpeakDatabase
import com.slothspeak.service.SlothSpeakService
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolTraceEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

class ConversationViewModel(application: Application) : AndroidViewModel(application) {

    private val apiKeyManager = ApiKeyManager(application)
    private val compatibleProviderManager = CompatibleProviderManager(application)
    private val llmProviderCatalog = LlmProviderCatalog(apiKeyManager, compatibleProviderManager)
    private val database = SlothSpeakDatabase.getInstance(application)
    private val bluetoothRouter = BluetoothAudioRouter(application)
    private val audioFocusManager = AudioFocusManager(application).also {
        it.bluetoothRouter = bluetoothRouter
    }
    val audioRecorder = AudioRecorder(application)
    private var vadRecorder: VadAudioRecorder? = null
    private var vadObserverJob: Job? = null

    // Live (streaming) on-device transcription — only when FUTO/Nemotron is selected
    private var streamingRecorder: PcmAudioRecorder? = null
    private var streamingSession: FutoStreamingSession? = null
    private var streamingObserverJob: Job? = null
    private var pendingStreamingFile: File? = null
    private var lastStreamingPartial: String = ""

    // Transcript edit step: auto-send 2 s after the last edit
    private var autoSendJob: Job? = null

    private var service: SlothSpeakService? = null
    private var serviceBound = false
    private var serviceDeferred: CompletableDeferred<SlothSpeakService>? = null
    private var stateObserverJob: Job? = null
    private var loadConversationJob: Job? = null
    @Volatile private var pendingReplyBind = false

    // Wake word detection (on-device, openWakeWord)
    private var wakeWordDetector: WakeWordDetector? = null
    private var wakeWordObserverJob: Job? = null

    // Current conversation tracking
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId

    private val _currentResponseId = MutableStateFlow<String?>(null)
    val currentResponseId: StateFlow<String?> = _currentResponseId

    // UI state
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Pipeline state mirrored from the service (reactive, survives recomposition)
    private val _pipelineState = MutableStateFlow<SlothSpeakService.PipelineState>(SlothSpeakService.PipelineState.Idle)
    val pipelineState: StateFlow<SlothSpeakService.PipelineState> = _pipelineState

    // Conversation display items for current session
    private val _sessionItems = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessionItems: StateFlow<List<SessionItem>> = _sessionItems

    // Configured LLM providers for the home-screen model switcher
    private val _llmProviders = MutableStateFlow<List<LlmProviderUiState>>(emptyList())
    val llmProviders: StateFlow<List<LlmProviderUiState>> = _llmProviders

    // Whether the user is currently viewing the active pipeline's output
    private val _isViewingActivePipeline = MutableStateFlow(false)
    val isViewingActivePipeline: StateFlow<Boolean> = _isViewingActivePipeline

    // Whether the service's pipeline is actively running (independent of replay/pipelineState)
    private val _isPipelineRunning = MutableStateFlow(false)
    val isPipelineRunning: StateFlow<Boolean> = _isPipelineRunning

    // Mute mode: processing completes but playback, ping, and follow-up are skipped
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    // Cancel confirmation dialog
    private val _showCancelConfirmation = MutableStateFlow(false)
    val showCancelConfirmation: StateFlow<Boolean> = _showCancelConfirmation

    data class UiState(
        val isRecording: Boolean = false,
        val hasApiKey: Boolean = false,
        val voiceAvailable: Boolean = false,
        val canConverse: Boolean = false,
        val ttsConfigured: Boolean = false,
        val showRecordingOverlay: Boolean = false,
        val lowStorageWarning: Boolean = false,
        val serviceBindError: Boolean = false,
        val isInteractiveRecording: Boolean = false,
        val vadSpeechDetected: Boolean = false,
        // Live on-device transcription (FUTO/Nemotron)
        val isStreamingTranscription: Boolean = false,
        val streamingModelLoading: Boolean = false,
        val streamingText: String = "",
        val streamingError: String? = null,
        val recordingDurationMs: Long = 0L,
        // Transcript edit step before the LLM call
        val isEditingTranscript: Boolean = false,
        val editableTranscript: String = "",
        val autoSendRemainingMs: Long = 0L
    )

    data class SessionItem(
        val questionText: String,
        val answerText: String?,
        val responseId: String?,
        val audioFiles: List<File>?,
        val isProcessing: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
        val model: String? = null,
        val reasoningEffort: String? = null,
        val llmResponseSeconds: Int? = null,
        val answerTextRich: String? = null,
        val reasoningText: String? = null,
        /** Room id of the saved QAPair, when this item is backed by one. */
        val qaPairId: Long? = null,
        /** Tools the model used to produce this answer (empty when none were used). */
        val toolTrace: List<ToolTraceEntry> = emptyList()
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as SlothSpeakService.LocalBinder
            service = localBinder.getService()
            serviceBound = true
            serviceDeferred?.complete(service!!)
            observeServiceState()

            // Sync isPipelineRunning from service (only upward to true, never downward)
            val svc = service
            if (svc != null && svc.isPipelineRunning) {
                _isPipelineRunning.value = true
            }

            // Auto-reconnect on activity recreation: if pipeline is active and
            // we have no items, rebuild the UI from the current pipeline state
            if (svc != null && svc.isPipelineRunning && _sessionItems.value.isEmpty()) {
                _isViewingActivePipeline.value = true
                val currentState = svc.pipelineState.value
                _pipelineState.value = currentState
                val convId = svc.activeConversationId.value
                _currentConversationId.value = convId
                viewModelScope.launch {
                    rebuildSessionFromPipelineState(currentState, convId)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            _isPipelineRunning.value = false
        }
    }

    init {
        _uiState.value = _uiState.value.copy(
            hasApiKey = apiKeyManager.hasKey(),
            voiceAvailable = computeVoiceAvailable(),
            canConverse = computeCanConverse(),
            ttsConfigured = apiKeyManager.isTtsConfigured()
        )
        _llmProviders.value = llmProviderCatalog.getConfiguredProviders()
        checkStorage()
        reconnectToActiveService()
        // Arm wake word detection after first frame renders (one-shot).
        // Guards: only arm if the service is NOT currently being bound
        // (otherwise the service-connected handler would re-arm).
        viewModelScope.launch {
            delay(500)
            if (!serviceBound && !_isPipelineRunning.value) {
                maybeStartWakeWordDetection()
            }
        }
    }

    fun refreshApiKeyStatus() {
        _uiState.value = _uiState.value.copy(
            hasApiKey = apiKeyManager.hasKey(),
            voiceAvailable = computeVoiceAvailable(),
            canConverse = computeCanConverse(),
            ttsConfigured = apiKeyManager.isTtsConfigured()
        )
        _llmProviders.value = llmProviderCatalog.getConfiguredProviders()
    }

    /**
     * Switches the active LLM model/provider via the shared catalog and
     * refreshes the provider list (used by the home-screen model switcher).
     */
    fun selectLlmModel(provider: LlmProviderUiState, modelId: String = provider.modelId) {
        llmProviderCatalog.selectModel(provider, modelId)
        _llmProviders.value = llmProviderCatalog.getConfiguredProviders()
    }

    private fun computeVoiceAvailable(): Boolean {
        val config = apiKeyManager.getEffectiveSttConfig()
        return when (apiKeyManager.getSttProvider()) {
            ApiKeyManager.STT_PROVIDER_OPENAI -> apiKeyManager.hasKey()
            ApiKeyManager.STT_PROVIDER_TOGETHER -> apiKeyManager.hasSttApiKey()
            ApiKeyManager.STT_PROVIDER_FUTO ->
                FutoSttModelManager(getApplication()).isInstalled()
            ApiKeyManager.STT_PROVIDER_CUSTOM ->
                config.baseUrl.isNotBlank() && config.model.isNotBlank()
            else -> false
        }
    }

    private fun computeCanConverse(): Boolean {
        if (apiKeyManager.hasKey() || apiKeyManager.hasGeminiKey() ||
            apiKeyManager.hasClaudeKey() || apiKeyManager.hasGrokKey()
        ) return true
        val compatId = apiKeyManager.getSelectedCompatProviderId()
        if (compatId != null) {
            val provider = compatibleProviderManager.getProvider(compatId)
            if (provider != null) {
                val key = compatibleProviderManager.getProviderKey(compatId)
                if (!key.isNullOrBlank()) return true
            }
        }
        return false
    }

    // ============================================================
    // Wake Word Detection Lifecycle
    // ============================================================

    @Volatile private var wakeWordDisabledForSession = false

    /**
     * Single-flight guard for detector creation. Several callers can race here
     * (lifecycle ON_START, screen ON_RESUME, ViewModel init delay, pipeline
     * completion), and each extra WakeWordDetector opens its own AudioRecord —
     * a leaked one keeps the microphone open after [stopWakeWordDetection].
     */
    @Volatile private var wakeWordStartInProgress = false

    /**
     * True while the app is in the foreground. Passive listening (wake word)
     * must never be armed from the background.
     */
    @Volatile private var isAppInForeground = true

    /**
     * Called by the Activity on ON_START — re-arms passive listening.
     */
    fun onAppForegrounded() {
        isAppInForeground = true
        maybeStartWakeWordDetection()
    }

    /**
     * Called by the Activity on ON_STOP. The microphone must never stay open
     * while the app is in the background, so passive listening is torn down:
     * - wake word detector (and its foreground MICROPHONE service)
     * - follow-up VAD listening in the pipeline
     *
     * Playback and user-initiated recording are left untouched.
     */
    fun onAppBackgrounded() {
        isAppInForeground = false
        Log.i(TAG, "App backgrounded — stopping listening")
        stopWakeWordDetection()

        val serviceState = service?.pipelineState?.value
        val isFollowUpListening =
            serviceState is SlothSpeakService.PipelineState.ListeningForFollowUp ||
                    _pipelineState.value is SlothSpeakService.PipelineState.ListeningForFollowUp
        if (isFollowUpListening) {
            Log.i(TAG, "Cancelling follow-up listening — app is in the background")
            service?.abandonFollowUpListening()
            _isPipelineRunning.value = false
            _isViewingActivePipeline.value = false
            _pipelineState.value = SlothSpeakService.PipelineState.Idle
        }
    }

    /**
     * Start the wake word detector if conditions are met:
     * - wake word toggle enabled
     * - no pipeline active (Idle state after unbind or before start)
     * - RECORD_AUDIO granted at app level
     * - not disabled for session due to prior init failure
     *
     * The detector runs while no pipeline/recording/playback is active.
     */
    /**
     * Try to start the wake word detector.
     * Clears the session-disable flag when the toggle is on, so that returning
     * from Settings after toggling off→on triggers a fresh init attempt.
     */
    fun maybeStartWakeWordDetection() {
        // Never arm the mic while the app is in the background
        if (!isAppInForeground) return
        if (!apiKeyManager.getWakeWordEnabled()) return
        // Clear the session-disable flag when the toggle is on — the user
        // may have toggled off→on since the failure, requesting a fresh attempt.
        if (wakeWordDisabledForSession) {
            wakeWordDisabledForSession = false
        }
        if (_isPipelineRunning.value) return
        if (wakeWordDetector?.isRunning == true) return
        // Never fight the user's capture/transcript-edit screen for the mic.
        if (_uiState.value.isRecording || _uiState.value.showRecordingOverlay) return

        // Don't start if a pipeline is active
        val state = _pipelineState.value
        if (state !is SlothSpeakService.PipelineState.Idle &&
            state !is SlothSpeakService.PipelineState.Complete) return

        // Claim the start slot before launching — otherwise concurrent callers
        // each build a detector and only the last one is tracked/stopped.
        synchronized(this) {
            if (wakeWordStartInProgress) return
            wakeWordStartInProgress = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Extract models from the APK bundle OFF the main thread
                val melPath = ensureWakewordModel("melspectrogram.onnx") ?: run {
                    Log.w(TAG, "Wake word unavailable — missing melspectrogram model"); return@launch
                }
                val embPath = ensureWakewordModel("embedding_model.onnx") ?: run {
                    Log.w(TAG, "Wake word unavailable — missing embedding model"); return@launch
                }
                val wwPath = ensureWakewordModel("hey_jarvis_v0.1.onnx") ?: run {
                    Log.w(TAG, "Wake word unavailable — missing wake word model"); return@launch
                }

                // The app may have gone to the background while models were loading
                if (!isAppInForeground || wakeWordDetector?.isRunning == true) return@launch

                Log.i(TAG, "Starting wake word detector")
                val detector = WakeWordDetector(
                    context = getApplication(),
                    melSpectrogramPath = melPath,
                    embeddingModelPath = embPath,
                    wakeWordModelPath = wwPath,
                    onDetected = {
                        Log.i(TAG, "Wake word 'Hey Jarvis' detected!")
                        stopWakeWordDetection()
                        val svc = service
                        if (svc != null && serviceBound) {
                            svc.triggerWakeWordDetected()
                        } else {
                            startNewQuestion()
                        }
                    }
                )
                detector.start()
                // Once the detector is started, ensure the Service is running as foreground
                // MICROPHONE so Android doesn't silence the AudioRecord when backgrounded.
                withContext(Dispatchers.Main) {
                    // Bail out (and release the AudioRecord) if we lost the foreground
                    // or a detector got armed in the meantime.
                    if (!isAppInForeground || !detector.isRunning || wakeWordDetector != null) {
                        detector.release()
                        return@withContext
                    }
                    wakeWordDetector = detector
                    ensureServiceForWakeWord()
                }
            } finally {
                wakeWordStartInProgress = false
            }
        }
    }

    /**
     * Stop the wake word detector if running.
     */
    fun stopWakeWordDetection() {
        if (wakeWordDetector != null) {
            Log.i(TAG, "Stopping wake word detector")
            wakeWordDetector!!.release()
            wakeWordDetector = null
        }
        // Release the foreground MICROPHONE service so Android can reclaim the mic
        val svc = service
        if (svc != null) {
            svc.stopWakeWordForegroundIfIdle()
        }
    }

    /**
     * Ensures the SlothSpeakService is bound and running as foreground MICROPHONE
     * to prevent Android from silencing the wake word detector's AudioRecord when
     * the app backgrounds or the screen locks.
     */
    private fun ensureServiceForWakeWord() {
        if (serviceBound && service != null) {
            service!!.startWakeWordForeground()
            return
        }
        // Service not bound yet — start it as foreground for wake word listening
        viewModelScope.launch(Dispatchers.IO) {
            val deferred = bindAndAwaitService()
            try {
                val svc = withTimeout(5_000) { deferred.await() }
                svc.startWakeWordForeground()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind service for wake word foreground", e)
            }
        }
    }

    /**
     * Called from Settings toggle path to reset the session-disable flag when
     * the user toggles wake word off then on (e.g. after an ONNX init failure).
     */
    fun resetWakeWordDisabledForSession() {
        wakeWordDisabledForSession = false
    }

    /**
     * Extracts a wake word ONNX model from the APK bundle to the app's cache
     * directory on first run. Subsequent calls return the cached path.
     *
     * Uses atomic write: writes to `<name>.tmp` then renames to `<name>` so a
     * killed process never leaves a corrupt cached model.
     *
     * Uses android.content.Context.assets.open(...) which reads from the APK's
     * bundled asset resources under `assets/wakeword/`.
     */
    private fun ensureWakewordModel(modelName: String): String? {
        val cacheDir = File(getApplication<Application>().filesDir, "wakeword")
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, modelName)

        // Already cached — return immediately
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile.absolutePath
        }

        // Atomic write: .tmp then rename
        val tmpFile = File(cacheDir, "${modelName}.tmp")
        try {
            val app = getApplication<Application>()
            app.assets.open("wakeword/$modelName").use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            // Rename to final path (atomic on same filesystem)
            if (tmpFile.exists() && tmpFile.length() > 0) {
                tmpFile.renameTo(cachedFile)
                Log.i(TAG, "Extracted wake word model: $modelName from APK assets")
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    return cachedFile.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract model $modelName from APK assets", e)
            wakeWordDisabledForSession = true
        } finally {
            // Clean up any leftover tmp file
            try { if (tmpFile.exists()) tmpFile.delete() } catch (_: Exception) {}
        }

        return null
    }

    fun goHome() {
        loadConversationJob?.cancel()
        val svc = service

        // Only stop replay audio if a replay is actually superseding
        if (svc != null && svc.isReplaySuperseding()) {
            svc.stopReplayOnly()
        }

        // Clear conversation state — go to blank slate
        _currentConversationId.value = null
        _currentResponseId.value = null
        _sessionItems.value = emptyList()
        _isViewingActivePipeline.value = false

        if (_isPipelineRunning.value) {
            // Pipeline is actively processing/playing — keep service bound for indicator chip.
            // The observer is still running and will show the indicator.
            // Read current pipeline state so the chip can display status.
            if (svc != null) {
                _pipelineState.value = svc.pipelineState.value
            }
        } else {
            // No active pipeline — clean up fully
            _pipelineState.value = SlothSpeakService.PipelineState.Idle
            svc?.stopPlayback()
            unbindServiceSafely()
            maybeStartWakeWordDetection()
        }
    }

    fun returnToActivePipeline() {
        val svc = service ?: return
        if (!_isPipelineRunning.value) return

        // If a replay was superseding the pipeline's UI state, stop it and restore
        if (svc.isReplaySuperseding()) {
            svc.stopReplayOnly()
            svc.restorePipelineState()
        }

        _isViewingActivePipeline.value = true

        // Read current state directly — collector hasn't fired yet
        val currentState = svc.pipelineState.value
        _pipelineState.value = currentState
        val convId = svc.activeConversationId.value
        _currentConversationId.value = convId
        _currentResponseId.value = null

        // Load prior sessions from DB asynchronously, then append current item
        viewModelScope.launch {
            rebuildSessionFromPipelineState(currentState, convId)
        }
    }

    /**
     * Rebuilds session items by loading all prior Q&A pairs from the database,
     * then appending the current in-progress item from the pipeline state.
     * This preserves multi-session conversation history during auto-navigate.
     */
    private suspend fun rebuildSessionFromPipelineState(
        state: SlothSpeakService.PipelineState,
        conversationId: Long?
    ) {
        val question: String?
        val answer: String?
        val richText: String?
        when (state) {
            is SlothSpeakService.PipelineState.Transcribing -> {
                question = "Transcribing..."
                answer = null
                richText = null
            }
            is SlothSpeakService.PipelineState.Transcribed -> {
                question = state.questionText
                answer = null
                richText = null
            }
            is SlothSpeakService.PipelineState.Thinking -> {
                question = state.questionText
                answer = null
                richText = null
            }
            // Tool use is part of the LLM step: same in-progress item as Thinking.
            is SlothSpeakService.PipelineState.UsingTool -> {
                question = state.questionText
                answer = null
                richText = null
            }
            is SlothSpeakService.PipelineState.AwaitingToolConfirmation -> {
                question = state.questionText
                answer = null
                richText = null
            }
            is SlothSpeakService.PipelineState.ThinkingComplete -> {
                question = state.questionText
                answer = state.answerText
                richText = state.answerTextRich
            }
            is SlothSpeakService.PipelineState.GeneratingAudio -> {
                question = state.questionText
                answer = state.answerText
                richText = state.answerTextRich
            }
            is SlothSpeakService.PipelineState.Playing -> {
                question = state.questionText
                answer = state.answerText
                richText = state.answerTextRich
            }
            is SlothSpeakService.PipelineState.Error -> {
                question = state.questionText ?: "Error"
                answer = state.answerText
                richText = state.answerTextRich
            }
            is SlothSpeakService.PipelineState.ListeningForFollowUp,
            is SlothSpeakService.PipelineState.InteractiveEnding -> {
                question = null
                answer = null
                richText = null
            }
            else -> {
                question = null
                answer = null
                richText = null
            }
        }

        // Load all prior completed Q&A pairs from the database
        val priorItems = if (conversationId != null) {
            try {
                val qaPairs = withContext(Dispatchers.IO) {
                    database.conversationDao().getQAPairsForConversationOnce(conversationId)
                }
                qaPairs.map { pair ->
                    val audioFiles = pair.audioFilePaths?.split(",")
                        ?.map { File(it.trim()) }
                        ?.filter { it.exists() }
                        ?.ifEmpty { null }
                    SessionItem(
                        questionText = pair.questionText,
                        answerText = pair.answerText,
                        responseId = pair.responseId,
                        audioFiles = audioFiles,
                        isProcessing = false,
                        timestamp = pair.createdAt,
                        model = pair.model,
                        reasoningEffort = pair.reasoningEffort,
                        llmResponseSeconds = pair.llmResponseSeconds,
                        answerTextRich = pair.answerTextRich,
                        reasoningText = pair.reasoningText,
                        toolTrace = ToolTraceEntry.fromJson(pair.toolCallsJson),
                        qaPairId = pair.id
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load prior sessions for rebuild", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        if (question != null) {
            val currentItem = SessionItem(
                questionText = question,
                answerText = answer,
                responseId = null,
                audioFiles = null,
                isProcessing = true,
                answerTextRich = richText,
                reasoningText = null
            )
            // The current Q&A pair may already be in the DB (early save creates a
            // placeholder after transcription, final save updates it before playback).
            // If the last DB item matches the current question, replace it with the
            // processing version to avoid duplication.
            val items = priorItems.toMutableList()
            if (items.isNotEmpty() && items.last().questionText == question) {
                items[items.lastIndex] = currentItem
            } else {
                items.add(currentItem)
            }
            _sessionItems.value = items
        } else {
            // No current item (e.g. ListeningForFollowUp) — just show DB items
            _sessionItems.value = priorItems
        }
    }

    fun startNewQuestion() {
        loadConversationJob?.cancel()
        stopWakeWordDetection()
        service?.cancelFollowUpListening() // avoid chain leak + double-recorder vs service VAD
        _currentConversationId.value = null
        _currentResponseId.value = null
        _sessionItems.value = emptyList()
        _isViewingActivePipeline.value = false  // Will be set true when pipeline starts
        _isMuted.value = false
        startRecording()
    }

    fun isFollowUpSupported(): Boolean {
        val model = apiKeyManager.getSelectedModel()
        return model != ApiKeyManager.MODEL_DEEP_RESEARCH &&
                model != ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
    }

    fun startFollowUp() {
        stopWakeWordDetection()
        startRecording()
    }

    /**
     * Expose follow-up tracking IDs so the text flow can reuse the same
     * conversation chaining that the voice follow-up flow uses.
     */
    fun getFollowUpConversationId(): Long? = _currentConversationId.value
    fun getFollowUpResponseId(): String? = _currentResponseId.value

    /**
     * Submit a text question (no voice/OpenAI key required).
     * Guards: blank input, must be able to converse.
     *
     * Inside an active conversation (with a follow-up-capable model) the
     * existing conversation id / response id are threaded through so the
     * message becomes a follow-up and the session list keeps growing. On a
     * blank home screen — or when the active model can't chain (Deep Research)
     * — a fresh conversation is started. Text answers complete silently:
     * the user taps the speaker icon to hear the reply on demand.
     */
    fun submitTextQuestion(question: String) {
        if (question.isBlank()) return
        if (_isPipelineRunning.value) return // Close double-submit window before processing state lands
        if (!computeCanConverse()) return

        // Stop wake word detector before any pipeline activity
        stopWakeWordDetection()

        stopRecording() // Safety: stop any in-progress recording

        // Follow-up chaining: only when already inside a conversation the
        // active model can extend. Otherwise start a brand-new conversation.
        val isFollowUp = _currentConversationId.value != null && isFollowUpSupported()
        if (!isFollowUp) {
            _currentConversationId.value = null
            _currentResponseId.value = null
            _sessionItems.value = emptyList()
        }

        _isPipelineRunning.value = true
        val deferred = bindAndAwaitService()

        viewModelScope.launch {
            try {
                val svc = withTimeout(10_000) { deferred.await() }
                _isViewingActivePipeline.value = true
                svc.startTextPipeline(
                    question,
                    if (isFollowUp) _currentConversationId.value else null,
                    if (isFollowUp) _currentResponseId.value else null,
                    autoSpeak = false
                )
            } catch (e: TimeoutCancellationException) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Timed out waiting for service bind for text question", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } catch (e: Exception) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Failed to bind service for text question", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            }
        }
    }

    private fun startRecording() {
        if (!computeVoiceAvailable()) return // any usable STT provider — not just the OpenAI key

        val isInteractive = apiKeyManager.getInteractiveVoiceEnabled()
        val isStreaming = isFutoStreamingActive()

        // Live capture requests audio focus after the model loads so other apps
        // are not silenced while the ~650 MB model is being initialized.
        if (!isStreaming) {
            audioFocusManager.requestRecordingFocus()
        }

        // Show recording overlay immediately
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            showRecordingOverlay = true,
            isInteractiveRecording = isInteractive,
            vadSpeechDetected = false,
            isStreamingTranscription = isStreaming,
            streamingModelLoading = isStreaming,
            streamingText = "",
            streamingError = null,
            recordingDurationMs = 0L,
            isEditingTranscript = false,
            editableTranscript = "",
            autoSendRemainingMs = 0L
        )

        // Launch coroutine to handle BT routing (suspend) then start recording
        viewModelScope.launch {
            // Enable Bluetooth mic routing if a BT headset is connected
            val btRouted = bluetoothRouter.startBluetoothRecordingRoute()
            val btDevice = if (btRouted) bluetoothRouter.findBluetoothInputDevice() else null
            // On API <31, use VOICE_COMMUNICATION audio source when BT SCO is active
            val useBtSource = btRouted && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S

            if (isStreaming) {
                startStreamingCapture(btDevice, useBtSource, isInteractive)
                return@launch
            }

            if (isInteractive) {
                // Use VAD-based recording for auto-stop
                val recorder = VadAudioRecorder(
                    context = getApplication(),
                    silenceAfterSpeechMs = apiKeyManager.getSilenceTimeoutMs(),
                    noSpeechTimeoutMs = 8000
                )
                vadRecorder = recorder

                val audioFile = recorder.startRecording(
                    viewModelScope,
                    preferredDevice = btDevice,
                    useBluetoothAudioSource = useBtSource
                )
                if (audioFile == null) {
                    // Failed to start VAD recorder, fall back to normal recording
                    vadRecorder = null
                    _uiState.value = _uiState.value.copy(isInteractiveRecording = false)
                    audioRecorder.startRecording(
                        viewModelScope,
                        preferredDevice = btDevice,
                        useBluetoothAudioSource = useBtSource
                    )
                    return@launch
                }

                // Observe VAD state for auto-stop
                vadObserverJob = launch {
                    // Monitor speech detection for UI updates
                    val uiMonitorJob = launch {
                        recorder.state.collect { vadState ->
                            _uiState.value = _uiState.value.copy(
                                vadSpeechDetected = vadState.isSpeechDetected
                            )
                        }
                    }

                    // Wait for a terminal VAD state
                    val finalState = recorder.state.first { vadState ->
                        vadState.speechEndedByVad || vadState.noSpeechDetected
                    }
                    uiMonitorJob.cancel()

                    when {
                        finalState.speechEndedByVad -> {
                            Log.d(TAG, "VAD auto-stop: speech ended")
                            playPingSound()
                            delay(2000) // Let ping finish before releasing focus
                            stopRecordingInternal(audioFile, discardSilent = false)
                        }
                        finalState.noSpeechDetected -> {
                            Log.d(TAG, "VAD auto-stop: no speech detected")
                            stopRecordingInternal(audioFile, discardSilent = true)
                        }
                    }
                }
            } else {
                audioRecorder.startRecording(
                    viewModelScope,
                    preferredDevice = btDevice,
                    useBluetoothAudioSource = useBtSource
                )
            }
        }
    }

    /**
     * FUTO/Nemotron runs on-device, so audio can be transcribed while the user
     * is still speaking. Other STT providers keep the record-then-upload flow.
     */
    private fun isFutoStreamingActive(): Boolean =
        apiKeyManager.getSttProvider() == ApiKeyManager.STT_PROVIDER_FUTO

    /**
     * Load the on-device model, then start PCM capture and live recognition.
     * The overlay shows a loading header until the model is ready.
     */
    private suspend fun startStreamingCapture(
        btDevice: android.media.AudioDeviceInfo?,
        useBtSource: Boolean,
        isInteractive: Boolean
    ) {
        val context = getApplication<Application>()
        val session = try {
            FutoSttEngine.get(context).startStreaming(apiKeyManager.getFutoSttLanguage())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start on-device streaming model", e)
            bluetoothRouter.stopBluetoothRecordingRoute()
            audioFocusManager.abandonFocus()
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                streamingModelLoading = false,
                streamingError = e.message ?: "Could not load the on-device speech model."
            )
            return
        }

        // User may have cancelled while the model was loading.
        if (!_uiState.value.isRecording || !_uiState.value.showRecordingOverlay) {
            session.release()
            bluetoothRouter.stopBluetoothRecordingRoute()
            audioFocusManager.abandonFocus()
            return
        }

        streamingSession = session
        lastStreamingPartial = ""
        audioFocusManager.requestRecordingFocus()

        val recorder = PcmAudioRecorder(
            context = context,
            enableVadAutoStop = isInteractive,
            silenceAfterSpeechMs = apiKeyManager.getSilenceTimeoutMs(),
            noSpeechTimeoutMs = 8000,
            onFrame = { samples, count ->
                val partial = session.accept(samples, count)
                if (partial != lastStreamingPartial) {
                    lastStreamingPartial = partial
                    _uiState.update { current ->
                        if (current.isStreamingTranscription) {
                            current.copy(streamingText = partial)
                        } else {
                            current
                        }
                    }
                }
            }
        )
        streamingRecorder = recorder

        val audioFile = recorder.start(
            viewModelScope,
            preferredDevice = btDevice,
            useBluetoothAudioSource = useBtSource
        )
        if (audioFile == null) {
            streamingRecorder = null
            streamingSession = null
            session.release()
            bluetoothRouter.stopBluetoothRecordingRoute()
            audioFocusManager.abandonFocus()
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                streamingModelLoading = false,
                streamingError = "Could not start the microphone for live transcription."
            )
            return
        }
        pendingStreamingFile = audioFile
        _uiState.value = _uiState.value.copy(streamingModelLoading = false)

        streamingObserverJob?.cancel()
        streamingObserverJob = viewModelScope.launch {
            var terminalHandled = false
            recorder.state.collect { st ->
                if (_uiState.value.isStreamingTranscription) {
                    _uiState.update { current ->
                        if (current.isStreamingTranscription) {
                            current.copy(
                                recordingDurationMs = st.durationMs,
                                vadSpeechDetected = st.isSpeechDetected
                            )
                        } else {
                            current
                        }
                    }
                }
                if (terminalHandled) return@collect
                when {
                    st.speechEndedByVad -> {
                        terminalHandled = true
                        Log.d(TAG, "Streaming VAD auto-stop: speech ended")
                        if (apiKeyManager.getInteractiveVoiceEnabled()) playPingSound()
                        viewModelScope.launch { stopStreamingCapture(goToEdit = true) }
                    }
                    st.noSpeechDetected -> {
                        terminalHandled = true
                        Log.d(TAG, "Streaming VAD auto-stop: no speech detected")
                        viewModelScope.launch { stopStreamingCapture(goToEdit = false, discard = true) }
                    }
                    st.maxDurationReached -> {
                        terminalHandled = true
                        viewModelScope.launch { stopStreamingCapture(goToEdit = true) }
                    }
                }
            }
        }
    }

    /**
     * Stop live capture, finalize the transcript, and either open the edit step
     * or fall back to the normal batch transcription when no text was produced.
     */
    private suspend fun stopStreamingCapture(goToEdit: Boolean, discard: Boolean = false) {
        streamingObserverJob?.cancel()
        streamingObserverJob = null

        val recorder = streamingRecorder ?: return
        streamingRecorder = null

        val file = withContext(Dispatchers.IO) { recorder.stopAndAwait() }
        recorder.release()

        val session = streamingSession
        streamingSession = null
        val finalText = withContext(Dispatchers.Default) {
            session?.finish()?.trim().orEmpty()
        }
        session?.release()

        bluetoothRouter.stopBluetoothRecordingRoute()
        audioFocusManager.abandonFocus()

        val audioFile = file ?: pendingStreamingFile
        pendingStreamingFile = null

        if (discard) {
            audioFile?.delete()
            resetStreamingUi()
            maybeStartWakeWordDetection()
            return
        }

        if (!goToEdit || finalText.isBlank()) {
            // Nothing to edit — keep the existing batch transcription path so
            // audio is not lost if live recognition produced no text.
            resetStreamingUi()
            if (audioFile != null && audioFile.exists() && audioFile.length() > WAV_HEADER_BYTES) {
                stopRecordingInternal(audioFile, discardSilent = false)
            } else {
                audioFile?.delete()
                maybeStartWakeWordDetection()
            }
            return
        }

        audioFile?.delete()
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            showRecordingOverlay = true,
            isStreamingTranscription = true,
            streamingModelLoading = false,
            streamingText = finalText,
            streamingError = null,
            recordingDurationMs = 0L,
            vadSpeechDetected = false,
            isEditingTranscript = true,
            editableTranscript = finalText,
            autoSendRemainingMs = AUTO_SEND_DELAY_MS
        )
        startAutoSendCountdown()
    }

    private fun resetStreamingUi() {
        autoSendJob?.cancel()
        autoSendJob = null
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            showRecordingOverlay = false,
            isStreamingTranscription = false,
            streamingModelLoading = false,
            streamingText = "",
            streamingError = null,
            recordingDurationMs = 0L,
            isEditingTranscript = false,
            editableTranscript = "",
            autoSendRemainingMs = 0L,
            vadSpeechDetected = false
        )
    }

    /** User edits the transcript: restart the 2 s auto-send countdown. */
    fun updateStreamingTranscript(text: String) {
        if (!_uiState.value.isEditingTranscript) return
        _uiState.value = _uiState.value.copy(editableTranscript = text)
        startAutoSendCountdown()
    }

    private fun startAutoSendCountdown() {
        autoSendJob?.cancel()
        if (_uiState.value.editableTranscript.isBlank()) {
            autoSendJob = null
            _uiState.value = _uiState.value.copy(autoSendRemainingMs = 0L)
            return
        }
        autoSendJob = viewModelScope.launch {
            var remaining = AUTO_SEND_DELAY_MS
            _uiState.value = _uiState.value.copy(autoSendRemainingMs = remaining)
            while (remaining > 0) {
                delay(AUTO_SEND_TICK_MS)
                remaining = (remaining - AUTO_SEND_TICK_MS).coerceAtLeast(0)
                _uiState.value = _uiState.value.copy(autoSendRemainingMs = remaining)
            }
            sendStreamingTranscript()
        }
    }

    /** Send the (possibly edited) live transcript to the LLM now. */
    fun sendStreamingTranscript() {
        if (!_uiState.value.isEditingTranscript) return
        autoSendJob?.cancel()
        autoSendJob = null
        val question = _uiState.value.editableTranscript.trim()
        if (question.isBlank()) return
        pendingStreamingFile?.delete()
        pendingStreamingFile = null
        resetStreamingUi()
        submitStreamingQuestion(question)
    }

    /** Discard the transcript and return to the main screen. */
    fun cancelStreamingTranscript() {
        autoSendJob?.cancel()
        autoSendJob = null
        pendingStreamingFile?.delete()
        pendingStreamingFile = null
        resetStreamingUi()
        maybeStartWakeWordDetection()
    }

    private fun submitStreamingQuestion(question: String) {
        if (_isPipelineRunning.value) return
        stopWakeWordDetection()
        _isPipelineRunning.value = true
        val deferred = bindAndAwaitService()
        viewModelScope.launch {
            try {
                val svc = withTimeout(10_000) { deferred.await() }
                _isViewingActivePipeline.value = true
                // Voice-driven transcript (recorded audio) keeps spoken output.
                svc.startTextPipeline(
                    question,
                    _currentConversationId.value,
                    _currentResponseId.value,
                    autoSpeak = true
                )
            } catch (e: TimeoutCancellationException) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Timed out waiting for service bind for live transcript", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } catch (e: Exception) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Failed to bind service for live transcript", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            }
        }
    }

    private fun bindAndAwaitService(): CompletableDeferred<SlothSpeakService> {
        val context = getApplication<Application>()
        val intent = Intent(context, SlothSpeakService::class.java)
        context.startForegroundService(intent)

        // If already bound and service is available, return immediately.
        // This prevents a timeout when onServiceConnected won't fire again
        // (e.g., after replay finishes but the binding was never released).
        service?.let { svc ->
            if (serviceBound) {
                return CompletableDeferred(svc)
            }
        }

        val deferred = CompletableDeferred<SlothSpeakService>()
        serviceDeferred = deferred

        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            deferred.completeExceptionally(
                IllegalStateException("Failed to bind to SlothSpeakService")
            )
        }

        return deferred
    }

    fun stopRecording() {
        // FUTO/Nemotron live capture: finalize and open the transcript editor.
        if (_uiState.value.isStreamingTranscription) {
            if (streamingRecorder != null) {
                viewModelScope.launch { stopStreamingCapture(goToEdit = true) }
            } else {
                // Still loading the on-device model — cancel before capture starts.
                bluetoothRouter.stopBluetoothRecordingRoute()
                audioFocusManager.abandonFocus()
                resetStreamingUi()
                maybeStartWakeWordDetection()
            }
            return
        }

        val recorder = vadRecorder
        if (recorder != null) {
            // VAD mode: stop VAD recorder and use its output file
            vadObserverJob?.cancel()
            vadObserverJob = null
            val audioFile = recorder.stopRecording()
            recorder.release()
            vadRecorder = null

            if (audioFile != null && audioFile.exists() && audioFile.length() > 44) {
                // Has data beyond WAV header
                stopRecordingInternal(audioFile, discardSilent = false)
            } else {
                // No meaningful audio, just clean up
                bluetoothRouter.stopBluetoothRecordingRoute()
                audioFocusManager.abandonFocus()
                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    showRecordingOverlay = false,
                    isInteractiveRecording = false,
                    vadSpeechDetected = false
                )
            }
        } else {
            val audioFile = audioRecorder.stopRecording() ?: return
            stopRecordingInternal(audioFile, discardSilent = false)
        }
    }

    private fun playPingSound() {
        if (!apiKeyManager.getPingBeforeReading()) return
        if (!apiKeyManager.getInteractiveVoiceEnabled()) return
        try {
            val player = MediaPlayer.create(
                getApplication(),
                com.slothspeak.R.raw.notify
            )
            player?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ping sound", e)
        }
    }

    private fun stopRecordingInternal(audioFile: File, discardSilent: Boolean) {
        vadObserverJob?.cancel()
        vadObserverJob = null
        vadRecorder?.release()
        vadRecorder = null

        // Safety: no live capture should survive a batch handoff.
        streamingObserverJob?.cancel()
        streamingObserverJob = null
        streamingRecorder?.release()
        streamingRecorder = null
        streamingSession?.release()
        streamingSession = null

        // Release Bluetooth routing before releasing focus to avoid earpiece routing
        bluetoothRouter.stopBluetoothRecordingRoute()
        // Release recording focus so other apps can resume during transcription/thinking
        audioFocusManager.abandonFocus()
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            showRecordingOverlay = false,
            serviceBindError = false,
            isInteractiveRecording = false,
            vadSpeechDetected = false,
            isStreamingTranscription = false,
            streamingModelLoading = false,
            streamingText = "",
            streamingError = null,
            recordingDurationMs = 0L,
            isEditingTranscript = false,
            editableTranscript = "",
            autoSendRemainingMs = 0L
        )

        if (discardSilent) {
            // No speech was detected, discard silently
            audioFile.delete()
            return
        }

        _isPipelineRunning.value = true
        val deferred = bindAndAwaitService()

        viewModelScope.launch {
            try {
                val svc = withTimeout(10_000) { deferred.await() }
                _isViewingActivePipeline.value = true
                svc.startPipeline(
                    audioFile,
                    _currentConversationId.value,
                    _currentResponseId.value
                )
            } catch (e: TimeoutCancellationException) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Timed out waiting for service bind", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } catch (e: Exception) {
                _isPipelineRunning.value = false
                Log.e(TAG, "Failed to bind service", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            }
        }
    }

    private fun observeServiceState() {
        stateObserverJob?.cancel()
        stateObserverJob = viewModelScope.launch {
            service?.pipelineState?.collect { state ->
                // Always mirror pipeline state (needed for indicator chip)
                _pipelineState.value = state

                // Handle lifecycle-critical states regardless of viewing mode.
                // Complete/Idle MUST always call unbindServiceSafely() to prevent
                // service leaks when the user navigates away during processing.
                when (state) {
                    is SlothSpeakService.PipelineState.Complete -> {
                        _isPipelineRunning.value = false
                        _isMuted.value = false
                        if (_isViewingActivePipeline.value) {
                            _currentConversationId.value = state.conversationId
                            _currentResponseId.value = state.responseId
                            updateLastItem(
                                state.questionText,
                                state.answerText,
                                state.responseId,
                                state.audioFiles,
                                isProcessing = false,
                                model = state.model,
                                reasoningEffort = state.reasoningEffort,
                                llmResponseSeconds = state.llmResponseSeconds,
                                answerTextRich = state.answerTextRich,
                                reasoningText = state.reasoningText,
                                qaPairId = state.qaPairId,
                                toolTrace = state.toolCallsJson?.let { ToolTraceEntry.fromJson(it) } ?: emptyList()
                            )
                        }
                        _isViewingActivePipeline.value = false
                        maybeStartWakeWordDetection()
                        unbindServiceSafely()
                        return@collect
                    }

                    is SlothSpeakService.PipelineState.Idle -> {
                        if (_isViewingActivePipeline.value) {
                            val items = _sessionItems.value
                            if (items.any { it.isProcessing }) {
                                _sessionItems.value = items.map { it.copy(isProcessing = false) }
                            }
                        }
                        // Only unbind if the real pipeline isn't running or a reply bind isn't in flight.
                        // Replay finishing emits Idle but the pipeline may still be processing.
                        if (!_isPipelineRunning.value && !pendingReplyBind) {
                            maybeStartWakeWordDetection()
                            unbindServiceSafely()
                        }
                        return@collect
                    }

                    is SlothSpeakService.PipelineState.InteractiveEnding -> {
                        _isPipelineRunning.value = false
                        if (_isViewingActivePipeline.value) {
                            _currentConversationId.value = state.conversationId
                            _isViewingActivePipeline.value = false
                            val items = _sessionItems.value.toMutableList()
                            // Remove the stale "Transcribing..." placeholder from the dismissed follow-up
                            if (items.lastOrNull()?.isProcessing == true && items.lastOrNull()?.answerText == null) {
                                items.removeAt(items.lastIndex)
                            }
                            _sessionItems.value = items.map { it.copy(isProcessing = false) }
                        }
                        return@collect
                    }

                    is SlothSpeakService.PipelineState.Playing -> {
                        // Sync from service — replay doesn't touch isPipelineRunning,
                        // so it stays true during replay but false for pipeline's own playback
                        val svcRunning = service?.isPipelineRunning ?: false
                        _isPipelineRunning.value = svcRunning
                        val isReplay = service?.isReplaySuperseding() ?: false

                        if (!isReplay) {
                            // Pipeline's own playback starting — auto-navigate to active conversation
                            if (!_isViewingActivePipeline.value) {
                                loadConversationJob?.cancel()
                                _isViewingActivePipeline.value = true
                                val convId = service?.activeConversationId?.value
                                _currentConversationId.value = convId
                                _currentResponseId.value = state.responseId.ifEmpty { null }
                                rebuildSessionFromPipelineState(state, convId)
                            }
                            updateLastItem(
                                state.questionText,
                                state.answerText,
                                state.responseId,
                                state.audioFiles,
                                isProcessing = true,
                                answerTextRich = state.answerTextRich
                            )
                        }
                        // For replay Playing, skip UI mutations (user is viewing history)
                        return@collect
                    }

                    else -> { /* handled below */ }
                }

                // Sync isPipelineRunning from service for follow-up pipelines
                val svcRunning = service?.isPipelineRunning ?: false
                if (svcRunning && !_isPipelineRunning.value) {
                    _isPipelineRunning.value = true
                }

                // For non-terminal states, skip UI mutations if not viewing
                if (!_isViewingActivePipeline.value) return@collect

                when (state) {
                    is SlothSpeakService.PipelineState.Transcribing -> {
                        addOrUpdateProcessingItem("Transcribing...", null)
                    }

                    is SlothSpeakService.PipelineState.Transcribed -> {
                        addOrUpdateProcessingItem(state.questionText, null)
                    }

                    is SlothSpeakService.PipelineState.Thinking -> {
                        addOrUpdateProcessingItem(state.questionText, null)
                    }

                    is SlothSpeakService.PipelineState.UsingTool -> {
                        addOrUpdateProcessingItem(state.questionText, null)
                    }

                    is SlothSpeakService.PipelineState.AwaitingToolConfirmation -> {
                        addOrUpdateProcessingItem(state.questionText, null)
                    }

                    is SlothSpeakService.PipelineState.ThinkingComplete -> {
                        addOrUpdateProcessingItem(state.questionText, state.answerText, answerTextRich = state.answerTextRich)
                    }

                    is SlothSpeakService.PipelineState.GeneratingAudio -> {
                        if (!state.fromReadAloud) {
                            addOrUpdateProcessingItem(state.questionText, state.answerText, answerTextRich = state.answerTextRich)
                        }
                    }

                    is SlothSpeakService.PipelineState.ListeningForFollowUp -> {
                        _currentConversationId.value = state.conversationId
                        _currentResponseId.value = state.previousResponseId
                        val items = _sessionItems.value
                        if (items.any { it.isProcessing }) {
                            _sessionItems.value = items.map { it.copy(isProcessing = false) }
                        }
                    }

                    is SlothSpeakService.PipelineState.Error -> {
                        if (!state.fromReadAloud) {
                            addOrUpdateProcessingItem(
                                state.questionText ?: "Error",
                                state.answerText,
                                isProcessing = false,
                                answerTextRich = state.answerTextRich
                            )
                        }
                    }

                    // Complete, Idle, InteractiveEnding already handled above
                    else -> {}
                }
            }
        }

        // Also observe wake word trigger from the service
        viewModelScope.launch {
            service?.wakeWordTriggered?.collect { triggered ->
                if (triggered) {
                    Log.i(TAG, "Wake word triggered — starting fresh New Question")
                    service?.acknowledgeWakeWordTrigger()
                    // Start a fresh New Question (Feature A semantics)
                    startNewQuestion()
                }
            }
        }
    }

    private fun addOrUpdateProcessingItem(question: String, answer: String?, isProcessing: Boolean = true, answerTextRich: String? = null) {
        val items = _sessionItems.value.toMutableList()
        val lastItem = items.lastOrNull()
        if (lastItem?.isProcessing == true) {
            items[items.lastIndex] = lastItem.copy(
                questionText = question,
                answerText = answer ?: lastItem.answerText,
                isProcessing = isProcessing,
                answerTextRich = answerTextRich ?: lastItem.answerTextRich
            )
        } else {
            items.add(
                SessionItem(
                    questionText = question,
                    answerText = answer,
                    responseId = null,
                    audioFiles = null,
                    isProcessing = isProcessing,
                    answerTextRich = answerTextRich,
                    reasoningText = null
                )
            )
        }
        _sessionItems.value = items
    }

    private fun updateLastItem(
        question: String,
        answer: String,
        responseId: String,
        audioFiles: List<File>,
        isProcessing: Boolean,
        model: String? = null,
        reasoningEffort: String? = null,
        llmResponseSeconds: Int? = null,
        answerTextRich: String? = null,
        reasoningText: String? = null,
        qaPairId: Long? = null,
        toolTrace: List<ToolTraceEntry> = emptyList()
    ) {
        val items = _sessionItems.value.toMutableList()
        // Find the target item to update:
        // 1. An item currently being processed (normal pipeline flow)
        // 2. An item matching this question text (history replay, where the
        //    replayed item may not be the last one)
        // 3. Fall back to the last item
        val targetIndex = items.indexOfLast { it.isProcessing }
            .takeIf { it >= 0 }
            ?: items.indexOfLast { it.questionText == question }
                .takeIf { it >= 0 }
            ?: items.lastIndex

        val newItem = SessionItem(
            questionText = question,
            answerText = answer,
            responseId = responseId,
            audioFiles = audioFiles,
            isProcessing = isProcessing,
            model = model,
            reasoningEffort = reasoningEffort,
            llmResponseSeconds = llmResponseSeconds,
            answerTextRich = answerTextRich,
            reasoningText = reasoningText,
            qaPairId = qaPairId,
            toolTrace = toolTrace
        )

        if (targetIndex >= 0) {
            // Preserve existing metadata (model, timing) when updating for replay
            // (replay Playing state doesn't carry model/reasoningEffort/llmResponseSeconds)
            val existing = items[targetIndex]
            items[targetIndex] = newItem.copy(
                model = model ?: existing.model,
                reasoningEffort = reasoningEffort ?: existing.reasoningEffort,
                llmResponseSeconds = llmResponseSeconds ?: existing.llmResponseSeconds,
                answerTextRich = answerTextRich ?: existing.answerTextRich,
                reasoningText = reasoningText ?: existing.reasoningText,
                timestamp = existing.timestamp,
                qaPairId = qaPairId ?: existing.qaPairId,
                // Playback states don't carry the trace; keep the one saved at Complete.
                toolTrace = toolTrace.ifEmpty { existing.toolTrace }
            )
        } else {
            items.add(newItem)
        }
        _sessionItems.value = items
    }

    fun retryFromError() {
        service?.retryFromError()
    }

    /**
     * Dismisses a pipeline error so the user can continue chatting or reading.
     * Preserves conversation/response IDs when TTS failed after an LLM answer so
     * follow-up questions continue in the same thread.
     */
    fun dismissError() {
        val currentState = _pipelineState.value
        val isReadAloudError = (currentState as? SlothSpeakService.PipelineState.Error)?.fromReadAloud == true
        if (currentState is SlothSpeakService.PipelineState.Error &&
            currentState.failedStep == SlothSpeakService.FailedStep.TTS &&
            currentState.answerText != null
        ) {
            if (!isReadAloudError) {
                currentState.conversationId?.let { _currentConversationId.value = it }
                currentState.responseId?.takeIf { it.isNotEmpty() }?.let { _currentResponseId.value = it }
                _sessionItems.value = applyDismissedTtsErrorMetadata(_sessionItems.value, currentState)
            }
        } else if (currentState is SlothSpeakService.PipelineState.Error &&
            currentState.answerText == null &&
            !isReadAloudError
        ) {
            val items = _sessionItems.value
            if (items.lastOrNull()?.answerText == null) {
                _sessionItems.value = items.dropLast(1)
            }
        }
        val isBackgroundPipelineActive = _isPipelineRunning.value
        service?.dismissError()
        if (isReadAloudError && isBackgroundPipelineActive) {
            return
        }
        _isPipelineRunning.value = false
        _isViewingActivePipeline.value = false
        _isMuted.value = false
        _pipelineState.value = SlothSpeakService.PipelineState.Idle
        unbindServiceSafely()
        maybeStartWakeWordDetection()
    }

    /** Answers the service's pending tool write-confirmation prompt (see [SlothSpeakService.resolveToolConfirmation]). */
    fun resolveToolConfirmation(decision: ToolConfirmationDecision) {
        service?.resolveToolConfirmation(decision)
    }

    fun cancelPipeline() {
        stopWakeWordDetection()
        vadObserverJob?.cancel()
        vadObserverJob = null
        vadRecorder?.release()
        vadRecorder = null
        releaseStreamingCapture(deleteFile = true)
        bluetoothRouter.stopBluetoothRecordingRoute()
        service?.cancelPipeline()
        audioFocusManager.abandonFocus()
        _isPipelineRunning.value = false
        _isViewingActivePipeline.value = false
        _isMuted.value = false
        _uiState.value = _uiState.value.copy(
            isRecording = false,
            showRecordingOverlay = false,
            isInteractiveRecording = false,
            vadSpeechDetected = false,
            isStreamingTranscription = false,
            streamingModelLoading = false,
            streamingText = "",
            streamingError = null,
            recordingDurationMs = 0L,
            isEditingTranscript = false,
            editableTranscript = "",
            autoSendRemainingMs = 0L
        )
        unbindServiceSafely()
        maybeStartWakeWordDetection()
    }

    fun requestCancelPipeline() {
        _showCancelConfirmation.value = true
    }

    fun confirmCancelPipeline() {
        _showCancelConfirmation.value = false
        cancelPipeline()
    }

    fun dismissCancelConfirmation() {
        _showCancelConfirmation.value = false
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        service?.isMuted = newMuted
    }

    fun cancelInteractiveListening() {
        service?.cancelFollowUpListening()
        val conversationId = _currentConversationId.value
        if (conversationId != null) {
            // Let the service handle transitioning to Idle
            service?.cancelPipeline()
        }
        _isPipelineRunning.value = false
        unbindServiceSafely()
    }

    /**
     * Replay audio for a previous Q&A pair (playback only, no pipeline).
     * Stops the wake word detector to free the mic during playback.
     */
    fun replayAudio(files: List<File>, questionText: String = "", answerText: String = "") {
        stopWakeWordDetection()
        _uiState.value = _uiState.value.copy(serviceBindError = false)
        pendingReplyBind = true
        val deferred = bindAndAwaitService()

        viewModelScope.launch {
            try {
                val svc = withTimeout(10_000) { deferred.await() }
                svc.replayAudio(files, questionText, answerText)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out waiting for service bind for replay", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service for replay", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } finally {
                pendingReplyBind = false
            }
        }
    }

    /**
     * Speaks a session item's reply aloud. If audio already exists it is
     * replayed directly; otherwise the service synthesizes it (persisting the
     * paths to the DB when the item is backed by a QAPair), and the matching
     * SessionItem is updated so subsequent taps replay without re-synthesizing.
     */
    fun speakReply(item: SessionItem) {
        val audioFiles = item.audioFiles
        if (!audioFiles.isNullOrEmpty()) {
            replayAudio(audioFiles, item.questionText, item.answerText ?: "")
            return
        }
        val answerText = item.answerText ?: return
        stopWakeWordDetection()
        _uiState.value = _uiState.value.copy(serviceBindError = false)
        pendingReplyBind = true
        val deferred = bindAndAwaitService()

        viewModelScope.launch {
            try {
                val svc = withTimeout(10_000) { deferred.await() }
                svc.synthesizeAndPlayReply(
                    qaPairId = item.qaPairId,
                    questionText = item.questionText,
                    answerText = answerText,
                    responseId = item.responseId,
                    model = item.model
                ) { files ->
                    // Persist files on the matching item so replay is instant next time.
                    val items = _sessionItems.value.toMutableList()
                    val index = items.indexOfFirst { candidate ->
                        candidate.qaPairId != null && candidate.qaPairId == item.qaPairId ||
                            (candidate.qaPairId == null &&
                                candidate.timestamp == item.timestamp &&
                                candidate.questionText == item.questionText)
                    }
                    if (index >= 0) {
                        items[index] = items[index].copy(audioFiles = files)
                        _sessionItems.value = items
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out waiting for service bind for speak reply", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service for speak reply", e)
                _uiState.value = _uiState.value.copy(serviceBindError = true)
            } finally {
                pendingReplyBind = false
            }
        }
    }

    /**
     * Load a conversation from the database and display it on the main screen.
     * No audio playback is started — the user can tap play on individual Q&A pairs.
     */
    fun loadConversation(conversationId: Long) {
        // If loading the same conversation that the pipeline is actively processing,
        // return to the live pipeline view instead of loading stale DB data
        val svc = service
        if (svc != null && svc.activeConversationId.value == conversationId && svc.isPipelineRunning) {
            returnToActivePipeline()
            return
        }
        _isViewingActivePipeline.value = false

        loadConversationJob?.cancel()
        loadConversationJob = viewModelScope.launch {
            try {
                val qaPairs = withContext(Dispatchers.IO) {
                    database.conversationDao().getQAPairsForConversationOnce(conversationId)
                }

                val items = qaPairs.map { pair ->
                    val audioFiles = pair.audioFilePaths?.split(",")
                        ?.map { File(it.trim()) }
                        ?.filter { it.exists() }
                        ?.ifEmpty { null }
                    SessionItem(
                        questionText = pair.questionText,
                        answerText = pair.answerText,
                        responseId = pair.responseId,
                        audioFiles = audioFiles,
                        isProcessing = false,
                        timestamp = pair.createdAt,
                        model = pair.model,
                        reasoningEffort = pair.reasoningEffort,
                        llmResponseSeconds = pair.llmResponseSeconds,
                        answerTextRich = pair.answerTextRich,
                        reasoningText = pair.reasoningText,
                        toolTrace = ToolTraceEntry.fromJson(pair.toolCallsJson),
                        qaPairId = pair.id
                    )
                }

                _sessionItems.value = items
                _currentConversationId.value = conversationId
                _currentResponseId.value = qaPairs.lastOrNull()?.responseId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load conversation", e)
            }
        }
    }

    fun pausePlayback() = service?.pausePlayback()
    fun resumePlayback() = service?.resumePlayback()

    fun stopPlayback() {
        val svc = service ?: return

        if (svc.isReplaySuperseding()) {
            // Replay is active — stop it
            svc.stopReplayOnly()
            if (_isPipelineRunning.value) {
                // Pipeline still running in background — restore its state
                svc.restorePipelineState()
            } else {
                // No pipeline — go idle and unbind
                _pipelineState.value = SlothSpeakService.PipelineState.Idle
                unbindServiceSafely()
            }
            return
        }

        // Capture IDs from Playing state so follow-up is available after stop
        val currentState = _pipelineState.value
        if (currentState is SlothSpeakService.PipelineState.Playing) {
            if (currentState.responseId.isNotEmpty()) {
                _currentResponseId.value = currentState.responseId
            }
            if (currentState.conversationId >= 0) {
                _currentConversationId.value = currentState.conversationId
            }
        }
        val items = _sessionItems.value
        if (items.any { it.isProcessing }) {
            _sessionItems.value = items.map { it.copy(isProcessing = false) }
        }
        _isPipelineRunning.value = false
        svc.stopPlayback()
        unbindServiceSafely()
        maybeStartWakeWordDetection()
    }

    fun restartPlayback() {
        stopWakeWordDetection()
        service?.restartPlayback()
    }

    fun seekRelative(deltaMs: Int) = service?.seekRelative(deltaMs)

    fun getPlaybackPosition(): Long = service?.getPlaybackPosition() ?: 0L
    fun getPlaybackDuration(): Long = service?.getPlaybackDuration() ?: 0L

    fun setPlaybackSpeed(speed: Float) {
        service?.setPlaybackSpeed(speed)
        // Also persist directly in case service isn't bound yet
        apiKeyManager.setPlaybackSpeed(speed)
    }

    fun getPlaybackSpeed(): Float = apiKeyManager.getPlaybackSpeed()

    fun clearServiceBindError() {
        _uiState.value = _uiState.value.copy(serviceBindError = false)
    }

    /**
     * On init, try to bind to an already-running service (e.g., after Activity recreation).
     * Uses flags=0 (no BIND_AUTO_CREATE) so it only connects if the service is already running.
     * If the service is mid-pipeline, onServiceConnected will fire, observeServiceState()
     * will collect the current PipelineState, and the UI will be restored.
     */
    private fun reconnectToActiveService() {
        if (serviceBound) return
        val context = getApplication<Application>()
        val intent = Intent(context, SlothSpeakService::class.java)
        try {
            context.bindService(intent, serviceConnection, 0)
        } catch (_: Exception) {
        }
    }

    private fun checkStorage() {
        try {
            val stat = StatFs(getApplication<Application>().filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val availableMb = availableBytes / (1024 * 1024)
            _uiState.value = _uiState.value.copy(lowStorageWarning = availableMb < 100)
        } catch (_: Exception) {
        }
    }

    private fun unbindServiceSafely() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        serviceDeferred?.cancel()
        serviceDeferred = null
        if (serviceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            serviceBound = false
        }
        service = null
    }

    override fun onCleared() {
        super.onCleared()
        vadObserverJob?.cancel()
        vadRecorder?.release()
        releaseStreamingCapture(deleteFile = true)
        bluetoothRouter.release()
        audioRecorder.release()
        audioFocusManager.release()
        unbindServiceSafely()
    }

    /** Cancel any live capture / transcript edit state and release resources. */
    private fun releaseStreamingCapture(deleteFile: Boolean) {
        streamingObserverJob?.cancel()
        streamingObserverJob = null
        autoSendJob?.cancel()
        autoSendJob = null
        streamingRecorder?.release()
        streamingRecorder = null
        streamingSession?.release()
        streamingSession = null
        if (deleteFile) {
            pendingStreamingFile?.delete()
        }
        pendingStreamingFile = null
    }

    companion object {
        private const val TAG = "ConversationViewModel"
        private const val AUTO_SEND_DELAY_MS = 2_000L
        private const val AUTO_SEND_TICK_MS = 100L
        private const val WAV_HEADER_BYTES = 44L

        /**
         * Links a TTS-failed [SessionItem] to its persisted DB [SlothSpeakService.PipelineState.Error.qaPairId],
         * [SlothSpeakService.PipelineState.Error.responseId], and [SlothSpeakService.PipelineState.Error.model]
         * on dismiss so subsequent "Read aloud" taps persist audio and follow-ups stay chained.
         */
        internal fun applyDismissedTtsErrorMetadata(
            items: List<SessionItem>,
            errorState: SlothSpeakService.PipelineState.Error
        ): List<SessionItem> {
            if (items.isEmpty()) return items
            val targetIndex = items.indexOfLast {
                it.questionText == errorState.questionText && it.qaPairId == null
            }.takeIf { it >= 0 } ?: items.lastIndex
            val existing = items[targetIndex]
            val updated = items.toMutableList()
            updated[targetIndex] = existing.copy(
                responseId = errorState.responseId?.takeIf { it.isNotEmpty() } ?: existing.responseId,
                qaPairId = errorState.qaPairId ?: existing.qaPairId,
                model = errorState.model ?: existing.model
            )
            return updated
        }
    }
}
