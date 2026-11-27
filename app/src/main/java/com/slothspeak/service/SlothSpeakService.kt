package com.slothspeak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.slothspeak.MainActivity
import com.slothspeak.api.ApiException
import com.slothspeak.api.ClaudeClient
import com.slothspeak.api.GeminiClient
import com.slothspeak.api.GrokClient
import com.slothspeak.api.OpenAiCompatibleClient
import com.slothspeak.api.OpenAIClient
import com.slothspeak.audio.AudioFocusManager
import com.slothspeak.audio.AudioPlayer
import com.slothspeak.audio.BluetoothAudioRouter
import com.slothspeak.audio.FutoSttEngine
import com.slothspeak.audio.OpenAiCompatibleTtsSynthesizer
import com.slothspeak.audio.OnDeviceTtsSynthesizer
import com.slothspeak.audio.TtsProcessor
import com.slothspeak.audio.TtsSynthesizer
import com.slothspeak.audio.VadAudioRecorder
import com.slothspeak.audio.WakeWordDetector
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.CompatibleProviderManager
import com.slothspeak.data.FutoSttModelManager
import com.slothspeak.data.ToolConfigManager
import com.slothspeak.data.db.SlothSpeakDatabase
import com.slothspeak.data.db.entities.Conversation
import com.slothspeak.data.db.entities.QAPair
import com.slothspeak.harness.AgentHarness
import com.slothspeak.harness.OpenAiCompatibleChatAdapter
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolConfirmationRequest
import com.slothspeak.tools.ToolRegistry
import com.slothspeak.tools.ToolTraceEntry
import com.slothspeak.tools.formatToolSummary
import com.slothspeak.tools.plugins.BraveSearchPlugin
import com.slothspeak.tools.plugins.HttpRequestPlugin
import com.slothspeak.util.SpeechTextCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SlothSpeakService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pipelineJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var pipelineSuperseded = false
    @Volatile private var lastPipelineProcessingState: PipelineState? = null
    @Volatile private var pendingPipelineResult: PipelineState.Complete? = null
    @Volatile var isPipelineRunning = false
        private set

    // Tracks whether pause was caused by transient audio focus loss (vs user-initiated pause)
    @Volatile private var pausedByFocusLoss = false

    // Mute mode: when true, pipeline completes and saves but skips playback, ping, and follow-up
    @Volatile var isMuted = false
    @Volatile var wakeWordForegroundActive = false
        private set

    // TTS for an already-answered reply (synthesizeAndPlayReply); re-tapping the speaker
    // cancels the previous synthesis instead of queueing a second request.
    private var synthesisJob: Job? = null

    // Early DB persistence tracking
    @Volatile private var earlyConversationId: Long? = null
    @Volatile private var earlyQaPairId: Long? = null
    @Volatile private var earlyConversationWasNew: Boolean = false

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId
    private var wakeLock: PowerManager.WakeLock? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var vadRecorder: VadAudioRecorder? = null
    private var followUpJob: Job? = null
    private var onDeviceTtsSynthesizer: OnDeviceTtsSynthesizer? = null
    @Volatile private var pendingTtsSessionDir: File? = null

    // Wake word trigger state flow — observed by the ViewModel to start a fresh New Question
    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered

    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var openAIClient: OpenAIClient
    private lateinit var geminiClient: GeminiClient
    private lateinit var claudeClient: ClaudeClient
    private lateinit var grokClient: GrokClient
    private lateinit var compatibleProviderManager: CompatibleProviderManager
    private lateinit var openAiCompatibleClient: OpenAiCompatibleClient
    private lateinit var toolConfigManager: ToolConfigManager
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var database: SlothSpeakDatabase
    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var bluetoothRouter: BluetoothAudioRouter
    private var mediaSession: MediaSession? = null

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    // LLM response metadata for current pipeline run
    private var lastLlmResponseSeconds: Int = 0
    private var lastReasoningEffort: String? = null
    @Volatile private var lastAnswerTextRich: String? = null
    @Volatile private var lastReasoningText: String? = null
    /** Serialised [ToolTraceEntry] list of the last LLM call; null when no tool ran. */
    @Volatile private var lastToolCallsJson: String? = null

    // Tool confirmation bridge: non-null while the pipeline is suspended in
    // PipelineState.AwaitingToolConfirmation. The UI completes it via resolveToolConfirmation();
    // cancel/stop complete it with DENY so a pipeline never hangs on an unanswered prompt.
    @Volatile private var pendingToolConfirmation: CompletableDeferred<ToolConfirmationDecision>? = null

    sealed class PipelineState {
        data object Idle : PipelineState()
        data object Transcribing : PipelineState()
        data class Transcribed(val questionText: String) : PipelineState()
        data class Thinking(
            val questionText: String,
            val reasoningEffort: String = "",
            val statusMessage: String = "",
            val elapsedSeconds: Int = 0,
            val thinkingLabel: String = "Thinking"
        ) : PipelineState()
        /** A tool plugin is running (web search, HTTP request, …) as part of the LLM step. */
        data class UsingTool(
            val questionText: String,
            val toolName: String,
            val summary: String,
            val elapsedSeconds: Int = 0
        ) : PipelineState()
        /** The pipeline is suspended until the user approves or denies a sensitive tool call. */
        data class AwaitingToolConfirmation(
            val questionText: String,
            val request: ToolConfirmationRequest
        ) : PipelineState()
        data class ThinkingComplete(
            val questionText: String,
            val answerText: String,
            val responseId: String,
            val answerTextRich: String? = null
        ) : PipelineState()
        data class GeneratingAudio(
            val questionText: String,
            val answerText: String,
            val responseId: String,
            val completedChunks: Int,
            val totalChunks: Int,
            val statusMessage: String = "",
            val answerTextRich: String? = null
        ) : PipelineState()
        data class Playing(
            val questionText: String,
            val answerText: String,
            val responseId: String,
            val conversationId: Long,
            val currentChunk: Int,
            val totalChunks: Int,
            val audioFiles: List<File>,
            val isPaused: Boolean = false,
            val answerTextRich: String? = null,
            val reasoningText: String? = null
        ) : PipelineState()
        data class Complete(
            val questionText: String,
            val answerText: String,
            val responseId: String,
            val conversationId: Long,
            val audioFiles: List<File>,
            val model: String,
            val reasoningEffort: String?,
            val llmResponseSeconds: Int,
            val answerTextRich: String? = null,
            val reasoningText: String? = null,
            /** Room id of the saved QAPair — null for legacy/replay results. */
            val qaPairId: Long? = null,
            /** Serialised [ToolTraceEntry] list when tools were used for this answer. */
            val toolCallsJson: String? = null
        ) : PipelineState()
        data class ListeningForFollowUp(
            val conversationId: Long,
            val previousResponseId: String,
            val isPromptPlaying: Boolean = true,
            val isListening: Boolean = false,
            val isSpeechDetected: Boolean = false
        ) : PipelineState()
        data class InteractiveEnding(
            val conversationId: Long
        ) : PipelineState()
        data class Error(
            val message: String,
            val failedStep: FailedStep,
            val audioFile: File? = null,
            val questionText: String? = null,
            val answerText: String? = null,
            val responseId: String? = null,
            val isRetryable: Boolean = true,
            val answerTextRich: String? = null,
            val conversationId: Long? = null
        ) : PipelineState()
    }

    enum class FailedStep {
        TRANSCRIPTION, LLM, TTS, PLAYBACK
    }

    inner class LocalBinder : Binder() {
        fun getService(): SlothSpeakService = this@SlothSpeakService
    }

    override fun onCreate() {
        super.onCreate()
        apiKeyManager = ApiKeyManager(applicationContext)
        openAIClient = OpenAIClient(apiKeyManager)
        geminiClient = GeminiClient(apiKeyManager)
        claudeClient = ClaudeClient(apiKeyManager)
        grokClient = GrokClient(apiKeyManager)
        compatibleProviderManager = CompatibleProviderManager(applicationContext)
        openAiCompatibleClient = OpenAiCompatibleClient(compatibleProviderManager)
        toolConfigManager = ToolConfigManager(applicationContext)
        // The registry's call timeout also covers the time the user spends on a write-confirmation
        // prompt, so it must be generous; the network calls themselves are bounded by each
        // plugin's own OkHttp timeouts (connect/read/call). The user can cancel at any time.
        toolRegistry = ToolRegistry(
            plugins = listOf(BraveSearchPlugin(toolConfigManager), HttpRequestPlugin(toolConfigManager)),
            callTimeoutMs = TOOL_CALL_TIMEOUT_MS
        )
        audioPlayer = AudioPlayer()
        database = SlothSpeakDatabase.getInstance(applicationContext)
        bluetoothRouter = BluetoothAudioRouter(applicationContext)
        audioFocusManager = AudioFocusManager(applicationContext).also {
            it.bluetoothRouter = bluetoothRouter
        }
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocusCallbacks()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "SlothSpeak").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (_pipelineState.value is PipelineState.Playing) {
                        resumePlayback()
                    }
                }

                override fun onPause() {
                    if (_pipelineState.value is PipelineState.Playing) {
                        pausePlayback()
                    }
                }

                override fun onStop() {
                    if (_pipelineState.value is PipelineState.Playing) {
                        stopPlayback()
                    }
                }
            })
            // Set initial inactive state
            setPlaybackState(
                AndroidPlaybackState.Builder()
                    .setState(AndroidPlaybackState.STATE_NONE, 0, 1.0f)
                    .setActions(
                        AndroidPlaybackState.ACTION_PLAY or
                                AndroidPlaybackState.ACTION_PAUSE or
                                AndroidPlaybackState.ACTION_STOP or
                                AndroidPlaybackState.ACTION_PLAY_PAUSE
                    )
                    .build()
            )
        }
    }

    private fun setupAudioFocusCallbacks() {
        audioFocusManager.onFocusGained = {
            val state = _pipelineState.value
            if (state is PipelineState.Playing && state.isPaused && pausedByFocusLoss) {
                Log.d(TAG, "Focus regained after transient loss, resuming playback")
                pausedByFocusLoss = false
                resumePlayback()
            }
        }
        audioFocusManager.onFocusLostTransient = {
            val state = _pipelineState.value
            if (state is PipelineState.Playing && !state.isPaused) {
                Log.d(TAG, "Focus lost transiently, pausing playback")
                pausePlayback()
                pausedByFocusLoss = true
            }
        }
        audioFocusManager.onFocusLost = {
            val state = _pipelineState.value
            if (state is PipelineState.Playing) {
                Log.d(TAG, "Focus lost permanently, stopping playback")
                stopPlayback()
            } else if (state is PipelineState.ListeningForFollowUp) {
                Log.d(TAG, "Focus lost during follow-up listening, ending conversation")
                cancelFollowUpListening()
                endInteractiveConversation(state.conversationId)
            }
        }
    }

    private fun updateMediaSessionState(state: Int) {
        mediaSession?.setPlaybackState(
            AndroidPlaybackState.Builder()
                .setState(state, audioPlayer.getCurrentPosition().toLong(), audioPlayer.state.value.playbackSpeed)
                .setActions(
                    AndroidPlaybackState.ACTION_PLAY or
                            AndroidPlaybackState.ACTION_PAUSE or
                            AndroidPlaybackState.ACTION_STOP or
                            AndroidPlaybackState.ACTION_PLAY_PAUSE
                )
                .build()
        )
    }

    private fun activateMediaSession() {
        mediaSession?.isActive = true
        updateMediaSessionState(AndroidPlaybackState.STATE_PLAYING)
    }

    // ============================================================
    // TTS synthesizer factory
    // ============================================================

    private fun createTtsSynthesizer(): TtsSynthesizer {
        val config = apiKeyManager.getEffectiveTtsConfig()
        return when (config.provider) {
            ApiKeyManager.TTS_PROVIDER_ONDEVICE -> {
                var engine = onDeviceTtsSynthesizer
                if (engine == null) {
                    engine = OnDeviceTtsSynthesizer(applicationContext)
                    onDeviceTtsSynthesizer = engine
                }
                engine
            }
            else ->
                OpenAiCompatibleTtsSynthesizer(config)
        }
    }

    private fun deactivateMediaSession() {
        updateMediaSessionState(AndroidPlaybackState.STATE_STOPPED)
        mediaSession?.isActive = false
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ (targetSdk 35): starting a foreground service with the manifest-declared
        // microphone type requires RECORD_AUDIO to be granted AT THIS MOMENT. Text-only users
        // may never have granted mic access — degrade to mediaPlayback-only instead of crashing
        // with SecurityException. Voice recording from a foreground activity remains permitted
        // without the FGS microphone type.
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val serviceType = when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && micGranted ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        if (serviceType != 0) {
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification("Preparing..."), serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Preparing..."))
        }
        return START_NOT_STICKY
    }

    private fun emitProcessingState(state: PipelineState, notification: String? = null) {
        lastPipelineProcessingState = state
        if (!pipelineSuperseded) {
            _pipelineState.value = state
            notification?.let { updateNotification(it) }
        }
    }

    // ============================================================
    // LLM step for OpenAI-compatible providers + tool plumbing
    // ============================================================

    /** Result of the LLM step for an OpenAI-compatible provider (see [runCompatLlm]). */
    private class CompatLlmOutcome(
        val answerText: String,
        val reasoningText: String?,
        val responseId: String,
        /** Serialised [ToolTraceEntry] list, or null when no tool was used. */
        val toolCallsJson: String?
    )

    /**
     * Runs the LLM step for an OpenAI-compatible provider — shared by the voice, text and
     * retry pipelines so all three take the same path through the tool harness.
     *
     * With at least one enabled *and* configured tool plugin the request goes through
     * [AgentHarness]: tool calls are executed and fed back until the model answers in text.
     * Without tools it is a single plain chat completion, exactly as before tools existed.
     * Transport/API errors propagate to the caller.
     *
     * @param elapsedSecondsProvider elapsed time of the LLM step so far; forwarded to
     *   [PipelineState.UsingTool] so the status clock does not restart per tool call.
     */
    private suspend fun runCompatLlm(
        questionText: String,
        history: List<Pair<String, String>>,
        compatProvider: CompatibleProviderConfig,
        elapsedSecondsProvider: () -> Int
    ): CompatLlmOutcome {
        lastToolCallsJson = null
        val systemPrompt = apiKeyManager.getSystemPrompt()
        if (toolRegistry.activeTools().isEmpty()) {
            val result = openAiCompatibleClient.createResponse(
                question = questionText,
                history = history,
                provider = compatProvider,
                systemPrompt = systemPrompt
            )
            return CompatLlmOutcome(result.answerText, result.reasoningText, result.responseId, null)
        }

        // Same label the pipelines show for this provider's Thinking status.
        val effortLabel = if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else ""
        fun emitThinking() {
            emitProcessingState(
                PipelineState.Thinking(
                    questionText = questionText,
                    reasoningEffort = effortLabel,
                    statusMessage = "Waiting for response...",
                    elapsedSeconds = elapsedSecondsProvider()
                ),
                "Thinking${if (effortLabel.isNotEmpty()) " ($effortLabel)" else ""}..."
            )
        }

        Log.d(TAG, "Running agent harness with tools: ${toolRegistry.activeTools().joinToString { it.name }}")
        val result = AgentHarness(
            OpenAiCompatibleChatAdapter(openAiCompatibleClient, compatProvider),
            toolRegistry
        ).run(
            question = questionText,
            history = history,
            systemPrompt = systemPrompt,
            confirmationHandler = ToolConfirmationHandler { request ->
                awaitToolConfirmation(questionText, request)
            },
            onToolCallStart = { call ->
                val summary = formatToolSummary(call)
                emitProcessingState(
                    PipelineState.UsingTool(questionText, call.name, summary, elapsedSecondsProvider()),
                    summary
                )
            },
            // Back to "Thinking" while the model digests the tool result.
            onToolCallFinish = { emitThinking() }
        )
        lastToolCallsJson = result.toolTrace.takeIf { it.isNotEmpty() }?.let { ToolTraceEntry.toJson(it) }
        return CompatLlmOutcome(result.answerText, result.reasoningText, result.responseId, lastToolCallsJson)
    }

    /**
     * Suspends the pipeline until the user answers the dialog shown for [request]
     * (MainScreen → [resolveToolConfirmation]). Cancel/stop answer a pending prompt with
     * [ToolConfirmationDecision.DENY] so a pipeline never hangs on it; cancelling the
     * pipeline coroutine itself propagates unchanged.
     */
    private suspend fun awaitToolConfirmation(
        questionText: String,
        request: ToolConfirmationRequest
    ): ToolConfirmationDecision {
        val deferred = CompletableDeferred<ToolConfirmationDecision>()
        pendingToolConfirmation = deferred
        emitProcessingState(
            PipelineState.AwaitingToolConfirmation(questionText, request),
            "Approval needed: ${request.method} ${request.host}"
        )
        try {
            val decision = deferred.await()
            // Approved or denied, the tool call is still finishing — show it as in progress
            // (the clock restarts visually on the next timer tick).
            emitProcessingState(
                PipelineState.UsingTool(
                    questionText, request.toolName, "HTTP ${request.method} ${request.host}…",
                    currentElapsedSeconds()
                ),
                "Finishing HTTP ${request.method} ${request.host}"
            )
            return decision
        } finally {
            if (pendingToolConfirmation === deferred) pendingToolConfirmation = null
        }
    }

    /**
     * Answers the pending [PipelineState.AwaitingToolConfirmation] prompt with the user's
     * [decision]. No-op when nothing is pending (e.g. the dialog outlived the prompt).
     */
    fun resolveToolConfirmation(decision: ToolConfirmationDecision) {
        val deferred = pendingToolConfirmation ?: return
        pendingToolConfirmation = null
        Log.i(TAG, "Tool confirmation resolved: $decision")
        deferred.complete(decision)
    }

    /** Fail closed: cancel/stop must never leave a confirmation prompt without an answer. */
    private fun denyPendingToolConfirmation() {
        val deferred = pendingToolConfirmation ?: return
        pendingToolConfirmation = null
        deferred.complete(ToolConfirmationDecision.DENY)
    }

    /** Elapsed seconds currently shown by the Thinking / UsingTool status (0 in other states). */
    private fun currentElapsedSeconds(): Int {
        val state = if (pipelineSuperseded) lastPipelineProcessingState else _pipelineState.value
        return when (state) {
            is PipelineState.Thinking -> state.elapsedSeconds
            is PipelineState.UsingTool -> state.elapsedSeconds
            else -> 0
        }
    }

    /**
     * Ticks once a second for the duration of the LLM step and keeps the elapsed time of the
     * Thinking / UsingTool status current. The clock keeps running while the user is asked to
     * approve a tool call ([PipelineState.AwaitingToolConfirmation]); the loop only ends when
     * the LLM step is over or the pipeline was cancelled. Callers cancel the job once the LLM
     * call returns.
     */
    private fun launchThinkingTimer(thinkingLabel: String, effortLabel: String): Job {
        val effortSuffix = if (effortLabel.isNotEmpty()) " ($effortLabel)" else ""
        return serviceScope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                seconds++
                // Use lastPipelineProcessingState when superseded (replay overwrites _pipelineState)
                val currentState = if (pipelineSuperseded) lastPipelineProcessingState else _pipelineState.value
                when (currentState) {
                    is PipelineState.Thinking -> emitProcessingState(
                        currentState.copy(
                            statusMessage = "Waiting for response...",
                            elapsedSeconds = seconds
                        ),
                        "$thinkingLabel$effortSuffix... ${seconds}s"
                    )

                    is PipelineState.UsingTool -> emitProcessingState(
                        currentState.copy(elapsedSeconds = seconds),
                        "${currentState.summary} (${seconds}s)"
                    )

                    is PipelineState.AwaitingToolConfirmation -> Unit // waiting on the user — keep counting

                    else -> break
                }
            }
        }
    }

    fun isPipelineActive(): Boolean = pipelineJob?.isActive == true

    /** State-based check: true when the pipeline is in any non-terminal state (includes playback). */
    fun hasActivePipeline(): Boolean {
        val state = _pipelineState.value
        return state !is PipelineState.Idle && state !is PipelineState.Complete
                && state !is PipelineState.InteractiveEnding
    }

    fun isReplaySuperseding(): Boolean = pipelineSuperseded && playbackJob?.isActive == true

    fun hasPendingPipelineResult(): Boolean = pendingPipelineResult != null

    fun consumePendingPipelineResult(): PipelineState.Complete? {
        val result = pendingPipelineResult
        pendingPipelineResult = null
        return result
    }

    fun stopReplayOnly() {
        playbackJob?.cancel()
        synthesisJob?.cancel()
        audioPlayer.stop()
        audioFocusManager.abandonFocus()
        deactivateMediaSession()
    }

    fun restorePipelineState() {
        pipelineSuperseded = false
        // If the pipeline finished while a replay was active, consume the pending result
        val pending = consumePendingPipelineResult()
        if (pending != null) {
            launchPipelinePlayback(
                pending.questionText, pending.answerText, pending.responseId,
                pending.conversationId, pending.audioFiles, pending.model,
                pending.reasoningEffort, pending.llmResponseSeconds,
                pending.answerTextRich,
                reasoningText = pending.reasoningText,
                qaPairId = pending.qaPairId,
                toolCallsJson = pending.toolCallsJson
            )
        } else {
            val lastState = lastPipelineProcessingState
            if (lastState != null) {
                _pipelineState.value = lastState
            } else {
                _pipelineState.value = PipelineState.Idle
            }
        }
    }

    fun startPipeline(
        audioFile: File,
        conversationId: Long?,
        previousResponseId: String?
    ) {
        // Clean up any lingering early DB records from a previous cancelled pipeline
        val staleQaPairId = earlyQaPairId
        val staleConvId = earlyConversationId
        val staleWasNew = earlyConversationWasNew
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false
        _activeConversationId.value = null
        if (staleQaPairId != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().deleteQAPair(staleQaPairId)
                    if (staleWasNew && staleConvId != null) {
                        val remaining = database.conversationDao().getQAPairCountForConversation(staleConvId)
                        if (remaining == 0) {
                            database.conversationDao().deleteConversation(staleConvId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up stale early DB records", e)
                }
            }
        }

        pipelineSuperseded = false
        lastPipelineProcessingState = null
        pendingPipelineResult = null
        isPipelineRunning = true
        playbackJob?.cancel()
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            acquireWakeLock()
            try {
                runPipeline(audioFile, conversationId, previousResponseId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fallback: ensure any unhandled exception surfaces to the user
                val currentState = _pipelineState.value
                if (currentState !is PipelineState.Error) {
                    handleError(
                        e, FailedStep.LLM,
                        questionText = (currentState as? PipelineState.Thinking)?.questionText
                            ?: (currentState as? PipelineState.UsingTool)?.questionText
                            ?: (currentState as? PipelineState.AwaitingToolConfirmation)?.questionText
                            ?: (currentState as? PipelineState.ThinkingComplete)?.questionText
                            ?: (currentState as? PipelineState.GeneratingAudio)?.questionText
                            ?: (currentState as? PipelineState.Playing)?.questionText,
                        answerText = (currentState as? PipelineState.ThinkingComplete)?.answerText
                            ?: (currentState as? PipelineState.GeneratingAudio)?.answerText
                            ?: (currentState as? PipelineState.Playing)?.answerText,
                        responseId = (currentState as? PipelineState.ThinkingComplete)?.responseId
                            ?: (currentState as? PipelineState.GeneratingAudio)?.responseId
                            ?: (currentState as? PipelineState.Playing)?.responseId
                    )
                }
            } finally {
                releaseWakeLock()
            }
        }
    }

    /**
     * Public entry point for text-only questions (no audio recording needed).
     * Same bookkeeping as startPipeline but calls runPipelineFromText directly.
     *
     * @param autoSpeak when false the pipeline completes silently — TTS synthesis,
     *   playback, ping, and follow-up listening are skipped and the answer is
     *   only shown as text (the user can tap the speaker icon to hear it later).
     */
    fun startTextPipeline(
        questionText: String,
        conversationId: Long?,
        previousResponseId: String?,
        autoSpeak: Boolean = false
    ) {
        // Clean up any lingering early DB records from a previous cancelled pipeline
        val staleQaPairId = earlyQaPairId
        val staleConvId = earlyConversationId
        val staleWasNew = earlyConversationWasNew
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false
        _activeConversationId.value = null
        if (staleQaPairId != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().deleteQAPair(staleQaPairId)
                    if (staleWasNew && staleConvId != null) {
                        val remaining = database.conversationDao().getQAPairCountForConversation(staleConvId)
                        if (remaining == 0) {
                            database.conversationDao().deleteConversation(staleConvId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up stale early DB records", e)
                }
            }
        }

        pipelineSuperseded = false
        lastPipelineProcessingState = null
        pendingPipelineResult = null
        isPipelineRunning = true
        playbackJob?.cancel()
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            acquireWakeLock()
            try {
                runPipelineFromText(questionText, conversationId, previousResponseId, autoSpeak)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val currentState = _pipelineState.value
                if (currentState !is PipelineState.Error) {
                    handleError(
                        e, FailedStep.LLM,
                        questionText = questionText
                    )
                }
            } finally {
                releaseWakeLock()
            }
        }
    }

    /**
     * Transcribe a recording with the active STT provider. The FUTO provider
     * runs locally through sherpa-onnx; everything else stays on the existing
     * OpenAI-compatible HTTP path.
     */
    private suspend fun transcribeAudio(audioFile: File): String {
        val config = apiKeyManager.getEffectiveSttConfig()
        if (!config.onDevice ||
            config.providerId != ApiKeyManager.STT_PROVIDER_FUTO
        ) {
            return openAIClient.transcribeAudio(audioFile)
        }

        val modelInstalled = withContext(Dispatchers.IO) {
            FutoSttModelManager(applicationContext).isInstalled()
        }
        if (!modelInstalled) {
            throw ApiException(
                "On-device STT model is not downloaded. Open Settings → " +
                    "Speech-to-Text → FUTO to download it.",
                0,
                false
            )
        }

        return FutoSttEngine.get(applicationContext)
            .transcribe(audioFile, apiKeyManager.getFutoSttLanguage())
    }

    private suspend fun runPipeline(
        audioFile: File,
        existingConversationId: Long?,
        previousResponseId: String?
    ) {
        val model = apiKeyManager.getSelectedModel()
        val reasoningEffort = apiKeyManager.getReasoningEffort()
        val thinkingLevel = apiKeyManager.getThinkingLevel()
        val claudeEffort = apiKeyManager.getClaudeEffort()
        val webSearchEnabled = false
        val xSearchEnabled = apiKeyManager.getXSearchEnabled()
        val isGemini = model == ApiKeyManager.MODEL_GEMINI_PRO || model == ApiKeyManager.MODEL_GEMINI_PRO_35
        val isClaude = model == ApiKeyManager.MODEL_CLAUDE_OPUS
        val isGrok = model == ApiKeyManager.MODEL_GROK
        val isDeepResearch = model == ApiKeyManager.MODEL_DEEP_RESEARCH
        val isGeminiDeepResearch = model == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
        val isAnyDeepResearch = isDeepResearch || isGeminiDeepResearch
        val compatId = apiKeyManager.getSelectedCompatProviderId()
        val isCompat = compatId != null && compatibleProviderManager.getProvider(compatId) != null
        val compatProvider = if (isCompat) compatibleProviderManager.getProvider(compatId!!) else null
        val dbModel = compatProvider?.modelId ?: model

        // Step 1: Transcribe
        emitProcessingState(PipelineState.Transcribing, "Transcribing question...")

        val questionText: String
        try {
            questionText = transcribeAudio(audioFile)
        } catch (e: Exception) {
            handleError(e, FailedStep.TRANSCRIPTION, audioFile = audioFile)
            return
        }

        emitProcessingState(PipelineState.Transcribed(questionText))

        // Early DB save: insert conversation + placeholder QAPair so it appears in history
        try {
            val earlyConvId = existingConversationId ?: withContext(Dispatchers.IO) {
                database.conversationDao().insertConversation(Conversation())
            }
            val earlyPairId = withContext(Dispatchers.IO) {
                database.conversationDao().insertQAPair(
                    QAPair(
                        conversationId = earlyConvId,
                        questionText = questionText,
                        answerText = "",
                        responseId = "",
                        model = dbModel
                    )
                )
            }
            earlyConversationId = earlyConvId
            earlyQaPairId = earlyPairId
            earlyConversationWasNew = (existingConversationId == null)
            _activeConversationId.value = earlyConvId
        } catch (e: Exception) {
            Log.w(TAG, "Early DB save failed, will fall back to late save", e)
            earlyConversationId = null
            earlyQaPairId = null
            earlyConversationWasNew = false
        }

        // Step 2: LLM
        val effortLabel = when {
            isAnyDeepResearch -> ""
            isCompat && compatProvider != null -> if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else ""
            isClaude -> claudeEffort
            isGemini -> thinkingLevel
            isGrok -> ""
            model == ApiKeyManager.MODEL_PRO -> reasoningEffort
            else -> ""
        }
        val providerName = when {
            isGeminiDeepResearch -> "Gemini Deep Research"
            isDeepResearch -> "Deep Research"
            isCompat && compatProvider != null -> compatProvider.displayName
            isClaude -> "Claude"
            isGemini -> "Gemini"
            isGrok -> "Grok"
            else -> "OpenAI"
        }
        val thinkingLabel = if (isAnyDeepResearch) "Researching" else "Thinking"
        emitProcessingState(
            PipelineState.Thinking(
                questionText = questionText,
                reasoningEffort = effortLabel,
                statusMessage = "Sending request to $providerName...",
                elapsedSeconds = 0,
                thinkingLabel = thinkingLabel
            ),
            "Sending request..."
        )

        // Updates the elapsed time of the Thinking / UsingTool status every second
        val timerJob = launchThinkingTimer(thinkingLabel, effortLabel)

        val answerText: String
        val responseId: String
        lastReasoningText = null
        lastToolCallsJson = null
        val llmStartTime = System.currentTimeMillis()
        try {
            if (isDeepResearch) {
                val llmResponse = openAIClient.createDeepResearch(questionText)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                val citationResult = openAIClient.extractAnswerText(llmResponse)
                answerText = citationResult.cleanText
                lastAnswerTextRich = citationResult.richText
                responseId = llmResponse.id
            } else if (isGeminiDeepResearch) {
                val result = geminiClient.createDeepResearch(questionText)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isClaude) {
                // Load conversation history for Claude
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = claudeClient.generateContent(questionText, history, claudeEffort, webSearchEnabled)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = claudeEffort
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isGemini) {
                // Load conversation history for Gemini
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = geminiClient.generateContent(questionText, history, thinkingLevel, webSearchEnabled, model)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = thinkingLevel
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isGrok) {
                // Grok uses previous_response_id for chaining (like OpenAI)
                val result = grokClient.createResponse(questionText, previousResponseId, webSearchEnabled, xSearchEnabled)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                answerText = result.answerText
                lastAnswerTextRich = result.answerTextRich
                responseId = result.responseId
            } else if (isCompat && compatProvider != null) {
                // Load conversation history for compat provider
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = runCompatLlm(questionText, history, compatProvider) { currentElapsedSeconds() }
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else null
                answerText = result.answerText
                lastReasoningText = result.reasoningText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else {
                val llmResponse = openAIClient.createResponse(
                    questionText, previousResponseId, model, reasoningEffort, webSearchEnabled
                )
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = if (model == ApiKeyManager.MODEL_PRO) reasoningEffort else null
                val citationResult = openAIClient.extractAnswerText(llmResponse)
                answerText = citationResult.cleanText
                lastAnswerTextRich = citationResult.richText
                responseId = llmResponse.id
            }
            // Briefly show "received" status
            emitProcessingState(PipelineState.Thinking(
                questionText = questionText,
                reasoningEffort = effortLabel,
                statusMessage = "Response received, processing...",
                elapsedSeconds = currentElapsedSeconds()
            ))
        } catch (e: Exception) {
            timerJob.cancel()
            handleError(e, FailedStep.LLM, questionText = questionText)
            return
        }

        emitProcessingState(PipelineState.ThinkingComplete(questionText, answerText, responseId, lastAnswerTextRich))

        // Step 3: TTS
        val audioFiles: List<File>
        if (apiKeyManager.isTtsConfigured()) {
            val sessionDir = File(filesDir, "audio/${System.currentTimeMillis()}")
            cleanupPendingTtsSessionDir()
            pendingTtsSessionDir = sessionDir
            val ttsConfig = apiKeyManager.getEffectiveTtsConfig()
            val ttsInstructions = if (ttsConfig.provider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                apiKeyManager.getTtsInstructions()
            } else {
                null
            }
            val synthesizer = createTtsSynthesizer()
            val ttsProcessor = TtsProcessor(synthesizer, sessionDir, ttsInstructions)

            val ttsMonitorJob = serviceScope.launch {
                ttsProcessor.progress.collect { progress ->
                    if (progress.totalChunks > 0 && !progress.isComplete) {
                        emitProcessingState(
                            PipelineState.GeneratingAudio(
                                questionText, answerText, responseId,
                                progress.completedChunks, progress.totalChunks,
                                progress.statusMessage,
                                answerTextRich = lastAnswerTextRich
                            ),
                            "Audio ${progress.completedChunks}/${progress.totalChunks}: ${progress.statusMessage}"
                        )
                    }
                }
            }

            val ttsText = if (isGeminiDeepResearch) {
                SpeechTextCleaner.cleanForSpeech(answerText)
            } else {
                answerText
            }

            try {
                audioFiles = ttsProcessor.processText(ttsText)
                pendingTtsSessionDir = null
            } catch (e: Exception) {
                ttsMonitorJob.cancel()
                handleError(
                    e, FailedStep.TTS,
                    questionText = questionText,
                    answerText = answerText,
                    responseId = responseId,
                    answerTextRich = lastAnswerTextRich
                )
                return
            }
            ttsMonitorJob.cancel()
        } else {
            // No TTS configured — skip synthesis, proceed with empty audio
            audioFiles = emptyList()
        }

        // Step 4: Save to database (before playback so data isn't lost on cancel)
        val earlyConvId = earlyConversationId
        val earlyPairId = earlyQaPairId
        var savedQaPairId: Long? = null
        val conversationId = try {
            val audioPathsJson = audioFiles.joinToString(",") { it.absolutePath }
            if (earlyConvId != null && earlyPairId != null) {
                // Update the early placeholder record with final data
                database.conversationDao().updateQAPairResult(
                    qaPairId = earlyPairId,
                    answerText = answerText,
                    audioFilePaths = audioPathsJson,
                    responseId = responseId,
                    model = dbModel,
                    reasoningEffort = lastReasoningEffort,
                    llmResponseSeconds = lastLlmResponseSeconds,
                    answerTextRich = lastAnswerTextRich,
                    reasoningText = lastReasoningText,
                    toolCallsJson = lastToolCallsJson
                )
                savedQaPairId = earlyPairId
                earlyConvId
            } else {
                // Fallback: early save failed, do full insert
                val convId = existingConversationId ?: run {
                    database.conversationDao().insertConversation(Conversation())
                }
                savedQaPairId = database.conversationDao().insertQAPair(
                    QAPair(
                        conversationId = convId,
                        questionText = questionText,
                        answerText = answerText,
                        audioFilePaths = audioPathsJson,
                        responseId = responseId,
                        model = dbModel,
                        reasoningEffort = lastReasoningEffort,
                        llmResponseSeconds = lastLlmResponseSeconds,
                        answerTextRich = lastAnswerTextRich,
                        reasoningText = lastReasoningText,
                        toolCallsJson = lastToolCallsJson
                    )
                )
                convId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to database", e)
            earlyConvId ?: existingConversationId ?: -1L
        }

        // DB record is now complete — clear early tracking so cancel/stop won't delete it
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false

        // Step 5: Playback (on separate job so replay doesn't kill processing)
        launchPipelinePlayback(
            questionText, answerText, responseId, conversationId,
            audioFiles, dbModel, lastReasoningEffort, lastLlmResponseSeconds,
            lastAnswerTextRich,
            reasoningText = lastReasoningText,
            qaPairId = savedQaPairId,
            toolCallsJson = lastToolCallsJson
        )
    }

    private fun launchPipelinePlayback(
        questionText: String,
        answerText: String,
        responseId: String,
        conversationId: Long,
        audioFiles: List<File>,
        model: String,
        reasoningEffort: String?,
        llmResponseSeconds: Int,
        answerTextRich: String? = null,
        reasoningText: String? = null,
        qaPairId: Long? = null,
        toolCallsJson: String? = null
    ) {
        if (playbackJob?.isActive == true) {
            // Replay in progress — save result so it can be restored later
            pendingPipelineResult = PipelineState.Complete(
                questionText, answerText, responseId, conversationId, audioFiles,
                model = model, reasoningEffort = reasoningEffort,
                llmResponseSeconds = llmResponseSeconds,
                answerTextRich = answerTextRich,
                reasoningText = reasoningText,
                qaPairId = qaPairId,
                toolCallsJson = toolCallsJson
            )
            return
        }

        // Acquire before launching so it overlaps with the pipeline wake lock (no gap)
        acquirePlaybackWakeLock()

        playbackJob = serviceScope.launch {
            try {
                // Skip playback if a phone call is active — the conversation is already
                // saved to history so the user won't lose anything.
                if (audioFocusManager.isPhoneCallActive()) {
                    Log.i(TAG, "Phone call active — skipping playback, conversation saved to history")
                    isPipelineRunning = false
                    _activeConversationId.value = null
                    _pipelineState.value = PipelineState.Complete(
                        questionText, answerText, responseId, conversationId, audioFiles,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        llmResponseSeconds = llmResponseSeconds,
                        answerTextRich = answerTextRich,
                        reasoningText = reasoningText,
                        qaPairId = qaPairId,
                        toolCallsJson = toolCallsJson
                    )
                    updateNotification("Answer ready (skipped during call)")
                    delay(1000)
                    stopSelfWithWakeWordGuard()
                    return@launch
                }

                // No audio files (TTS skipped because no OpenAI key) — emit Complete directly
                if (audioFiles.isEmpty()) {
                    Log.i(TAG, "No audio files — skipping playback, conversation saved to history")
                    isPipelineRunning = false
                    _activeConversationId.value = null
                    _pipelineState.value = PipelineState.Complete(
                        questionText, answerText, responseId, conversationId, audioFiles,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        llmResponseSeconds = llmResponseSeconds,
                        answerTextRich = answerTextRich,
                        reasoningText = reasoningText,
                        qaPairId = qaPairId,
                        toolCallsJson = toolCallsJson
                    )
                    updateNotification("Answer ready")
                    delay(1000)
                    stopSelfWithWakeWordGuard()
                    return@launch
                }

                // Muted mode: skip playback, ping, and follow-up — go straight to Complete
                if (isMuted) {
                    Log.i(TAG, "Muted — skipping playback, conversation saved to history")
                    isPipelineRunning = false
                    _activeConversationId.value = null
                    isMuted = false
                    _pipelineState.value = PipelineState.Complete(
                        questionText, answerText, responseId, conversationId, audioFiles,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        llmResponseSeconds = llmResponseSeconds,
                        answerTextRich = answerTextRich,
                        reasoningText = reasoningText,
                        qaPairId = qaPairId,
                        toolCallsJson = toolCallsJson
                    )
                    updateNotification("Answer ready (muted)")
                    delay(1000)
                    stopSelfWithWakeWordGuard()
                    return@launch
                }

                val focusGranted = audioFocusManager.requestPlaybackFocus()
                if (!focusGranted) {
                    Log.w(TAG, "Audio focus not granted, proceeding anyway")
                }

                playPingAndPause()
                audioPlayer.setPlaybackSpeed(apiKeyManager.getPlaybackSpeed())

                // Pipeline processing is done — clear indicator before playback starts
                isPipelineRunning = false

                _pipelineState.value = PipelineState.Playing(
                    questionText, answerText, responseId, conversationId,
                    1, audioFiles.size, audioFiles,
                    answerTextRich = answerTextRich,
                    reasoningText = reasoningText
                )
                updateNotification("Playing answer...")
                activateMediaSession()

                val playbackMonitorJob = serviceScope.launch {
                    audioPlayer.state.collect { playState ->
                        if (playState.isPlaying || playState.isPaused) {
                            _pipelineState.value = PipelineState.Playing(
                                questionText, answerText, responseId, conversationId,
                                playState.currentChunk, playState.totalChunks,
                                audioFiles,
                                isPaused = playState.isPaused,
                                answerTextRich = answerTextRich,
                                reasoningText = reasoningText
                            )
                        }
                    }
                }

                try {
                    audioPlayer.playFiles(audioFiles)
                } catch (e: CancellationException) {
                    playbackMonitorJob.cancel()
                    throw e
                } catch (e: Exception) {
                    playbackMonitorJob.cancel()
                    audioFocusManager.abandonFocus()
                    deactivateMediaSession()
                    handleError(
                        e, FailedStep.PLAYBACK,
                        questionText = questionText,
                        answerText = answerText,
                        responseId = responseId,
                        answerTextRich = answerTextRich,
                        conversationId = conversationId
                    )
                    return@launch
                }
                playbackMonitorJob.cancel()
                deactivateMediaSession()

                // Pipeline playback complete — clear active indicator
                _activeConversationId.value = null

                // Check if interactive voice mode should kick in
                val interactiveEnabled = apiKeyManager.getInteractiveVoiceEnabled()
                val isDeepResearch = model == ApiKeyManager.MODEL_DEEP_RESEARCH ||
                        model == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
                if (interactiveEnabled && !isDeepResearch && responseId.isNotEmpty()) {
                    // Keep audio focus held through the follow-up prompt and listening
                    launchFollowUpListening(conversationId, responseId)
                } else {
                    isPipelineRunning = false
                    audioFocusManager.abandonFocus()
                    _pipelineState.value = PipelineState.Complete(
                        questionText, answerText, responseId, conversationId, audioFiles,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        llmResponseSeconds = llmResponseSeconds,
                        answerTextRich = answerTextRich,
                        reasoningText = reasoningText,
                        qaPairId = qaPairId,
                        toolCallsJson = toolCallsJson
                    )
                    updateNotification("Answer complete")
                    delay(1000)
                    stopSelfWithWakeWordGuard()
                }
            } finally {
                releasePlaybackWakeLock()
            }
        }
    }

    private suspend fun playPingAndPause() {
        if (!apiKeyManager.getPingBeforeReading()) return
        try {
            withContext(Dispatchers.Main) {
                val mediaPlayer = MediaPlayer.create(
                    applicationContext,
                    com.slothspeak.R.raw.notify
                )
                mediaPlayer?.let { player ->
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    player.setOnCompletionListener { it.release() }
                    player.start()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SlothSpeakService", "Failed to play ping sound", e)
        }
        delay(3000)
    }

    private fun playPingSound() {
        if (!apiKeyManager.getPingBeforeReading()) return
        if (!apiKeyManager.getInteractiveVoiceEnabled()) return
        try {
            val mediaPlayer = MediaPlayer.create(
                applicationContext,
                com.slothspeak.R.raw.notify
            )
            mediaPlayer?.let { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setOnCompletionListener { it.release() }
                player.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ping sound", e)
        }
    }

    private fun handleError(
        e: Exception,
        step: FailedStep,
        audioFile: File? = null,
        questionText: String? = null,
        answerText: String? = null,
        responseId: String? = null,
        answerTextRich: String? = null,
        conversationId: Long? = earlyConversationId
    ) {
        if (e is CancellationException) {
            return
        }
        isPipelineRunning = false
        // Don't delete early records (user might retry), but clear active indicator
        _activeConversationId.value = null
        val isRetryable = e is ApiException && e.isRetryable
        val message = e.message ?: "An unexpected error occurred"
        _pipelineState.value = PipelineState.Error(
            message = message,
            failedStep = step,
            audioFile = audioFile,
            questionText = questionText,
            answerText = answerText,
            responseId = responseId,
            isRetryable = isRetryable || step != FailedStep.TRANSCRIPTION,
            answerTextRich = answerTextRich,
            conversationId = conversationId
        )
        updateNotification("Error: $message")
    }

    fun retryFromError() {
        val currentState = _pipelineState.value
        if (currentState !is PipelineState.Error) return

        when (currentState.failedStep) {
            FailedStep.TRANSCRIPTION -> {
                currentState.audioFile?.let { file ->
                    startPipeline(file, null, null)
                }
            }
            FailedStep.LLM -> {
                // Re-run from LLM step
                currentState.questionText?.let { question ->
                    pipelineSuperseded = false
                    isPipelineRunning = true
                    playbackJob?.cancel()
                    pipelineJob = serviceScope.launch {
                        acquireWakeLock()
                        try {
                            val model = apiKeyManager.getSelectedModel()
                            val reasoningEffort = apiKeyManager.getReasoningEffort()
                            val thinkingLevel = apiKeyManager.getThinkingLevel()
                            val claudeEffort = apiKeyManager.getClaudeEffort()
                            val webSearchEnabled = false
                            val xSearchEnabled = apiKeyManager.getXSearchEnabled()
                            val isGemini = model == ApiKeyManager.MODEL_GEMINI_PRO || model == ApiKeyManager.MODEL_GEMINI_PRO_35
                            val isClaude = model == ApiKeyManager.MODEL_CLAUDE_OPUS
                            val isGrok = model == ApiKeyManager.MODEL_GROK
                            val isDeepResearch = model == ApiKeyManager.MODEL_DEEP_RESEARCH
                            val isGeminiDeepResearch = model == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
                            val isAnyDeepResearch = isDeepResearch || isGeminiDeepResearch
                            val compatId = apiKeyManager.getSelectedCompatProviderId()
                            val isCompat = compatId != null && compatibleProviderManager.getProvider(compatId) != null
                            val compatProvider = if (isCompat) compatibleProviderManager.getProvider(compatId!!) else null
                            val dbModel = compatProvider?.modelId ?: model
                            val effortLabel = when {
                                isAnyDeepResearch -> ""
                                isCompat && compatProvider != null -> if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else ""
                                isClaude -> claudeEffort
                                isGemini -> thinkingLevel
                                isGrok -> ""
                                model == ApiKeyManager.MODEL_PRO -> reasoningEffort
                                else -> ""
                            }
                            val providerName = when {
                                isGeminiDeepResearch -> "Gemini Deep Research"
                                isDeepResearch -> "Deep Research"
                                isCompat && compatProvider != null -> compatProvider.displayName
                                isClaude -> "Claude"
                                isGemini -> "Gemini"
                                isGrok -> "Grok"
                                else -> "OpenAI"
                            }
                            val retryThinkingLabel = if (isAnyDeepResearch) "Researching" else "Thinking"

                            _pipelineState.value = PipelineState.Thinking(
                                questionText = question,
                                reasoningEffort = effortLabel,
                                statusMessage = "Sending request to $providerName...",
                                elapsedSeconds = 0,
                                thinkingLabel = retryThinkingLabel
                            )
                            updateNotification("Retrying: Sending request...")

                            val timerJob = launchThinkingTimer(retryThinkingLabel, effortLabel)

                            lastReasoningText = null
                            lastToolCallsJson = null
                            val llmStartTime = System.currentTimeMillis()
                            val answer: String
                            val respId: String
                            if (isDeepResearch) {
                                val llmResponse = openAIClient.createDeepResearch(question)
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = null
                                val citationResult = openAIClient.extractAnswerText(llmResponse)
                                answer = citationResult.cleanText
                                lastAnswerTextRich = citationResult.richText
                                respId = llmResponse.id
                            } else if (isGeminiDeepResearch) {
                                val result = geminiClient.createDeepResearch(question)
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = null
                                answer = result.answerText
                                lastAnswerTextRich = null
                                respId = result.responseId
                            } else if (isClaude) {
                                val result = claudeClient.generateContent(question, emptyList(), claudeEffort, webSearchEnabled)
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = claudeEffort
                                answer = result.answerText
                                lastAnswerTextRich = null
                                respId = result.responseId
                            } else if (isGemini) {
                                val result = geminiClient.generateContent(question, emptyList(), thinkingLevel, webSearchEnabled, model)
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = thinkingLevel
                                answer = result.answerText
                                lastAnswerTextRich = null
                                respId = result.responseId
                            } else if (isGrok) {
                                val result = grokClient.createResponse(question, null, webSearchEnabled, xSearchEnabled)
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = null
                                answer = result.answerText
                                lastAnswerTextRich = result.answerTextRich
                                respId = result.responseId
                            } else if (isCompat && compatProvider != null) {
                                // Compat provider retry — no history (match Claude retry pattern)
                                val result = runCompatLlm(question, emptyList(), compatProvider) { currentElapsedSeconds() }
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else null
                                answer = result.answerText
                                lastReasoningText = result.reasoningText
                                lastAnswerTextRich = null
                                respId = result.responseId
                            } else {
                                val response = openAIClient.createResponse(
                                    question, null, model, reasoningEffort, webSearchEnabled
                                )
                                timerJob.cancel()
                                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                                lastReasoningEffort = if (model == ApiKeyManager.MODEL_PRO) reasoningEffort else null
                                val citationResult = openAIClient.extractAnswerText(response)
                                answer = citationResult.cleanText
                                lastAnswerTextRich = citationResult.richText
                                respId = response.id
                            }
                            // Continue pipeline from TTS
                            continuePipelineFromTts(
                                question, answer, respId,
                                reasoningEffort = lastReasoningEffort,
                                llmResponseSeconds = lastLlmResponseSeconds,
                                answerTextRich = lastAnswerTextRich,
                                toolCallsJson = lastToolCallsJson
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleError(e, FailedStep.LLM, questionText = question)
                        } finally {
                            releaseWakeLock()
                        }
                    }
                }
            }
            FailedStep.TTS, FailedStep.PLAYBACK -> {
                val question = currentState.questionText ?: return
                val answer = currentState.answerText ?: return
                val respId = currentState.responseId ?: return
                val richText = currentState.answerTextRich
                pipelineSuperseded = false
                isPipelineRunning = true
                playbackJob?.cancel()
                pipelineJob = serviceScope.launch {
                    acquireWakeLock()
                    try {
                        continuePipelineFromTts(
                            question, answer, respId,
                            reasoningEffort = lastReasoningEffort,
                            llmResponseSeconds = lastLlmResponseSeconds,
                            answerTextRich = richText,
                            toolCallsJson = lastToolCallsJson
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleError(
                            e, FailedStep.TTS,
                            questionText = question,
                            answerText = answer,
                            responseId = respId,
                            answerTextRich = richText
                        )
                    } finally {
                        releaseWakeLock()
                    }
                }
            }
        }
    }

    private suspend fun continuePipelineFromTts(
        questionText: String,
        answerText: String,
        responseId: String,
        reasoningEffort: String? = null,
        llmResponseSeconds: Int = 0,
        answerTextRich: String? = null,
        toolCallsJson: String? = null
    ) {
        val model = apiKeyManager.getSelectedModel()
        val compatId = apiKeyManager.getSelectedCompatProviderId()
        val compatProvider = compatibleProviderManager.getProvider(compatId ?: "")
        val dbModel = compatProvider?.modelId ?: model

        val audioFiles: List<File>
        if (apiKeyManager.isTtsConfigured()) {
            val sessionDir = File(filesDir, "audio/${System.currentTimeMillis()}")
            cleanupPendingTtsSessionDir()
            pendingTtsSessionDir = sessionDir
            val ttsConfig = apiKeyManager.getEffectiveTtsConfig()
            val ttsInstructions = if (ttsConfig.provider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                apiKeyManager.getTtsInstructions()
            } else {
                null
            }
            val synthesizer = createTtsSynthesizer()
            val ttsProcessor = TtsProcessor(synthesizer, sessionDir, ttsInstructions)

            val ttsMonitorJob = serviceScope.launch {
                ttsProcessor.progress.collect { progress ->
                    if (progress.totalChunks > 0 && !progress.isComplete) {
                        emitProcessingState(
                            PipelineState.GeneratingAudio(
                                questionText, answerText, responseId,
                                progress.completedChunks, progress.totalChunks,
                                progress.statusMessage,
                                answerTextRich = answerTextRich
                            ),
                            "Audio ${progress.completedChunks}/${progress.totalChunks}: ${progress.statusMessage}"
                        )
                    }
                }
            }

            val ttsText = if (model == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH) {
                SpeechTextCleaner.cleanForSpeech(answerText)
            } else {
                answerText
            }

            try {
                audioFiles = ttsProcessor.processText(ttsText)
                pendingTtsSessionDir = null
            } catch (e: Exception) {
                ttsMonitorJob.cancel()
                handleError(
                    e, FailedStep.TTS,
                    questionText = questionText,
                    answerText = answerText,
                    responseId = responseId,
                    answerTextRich = answerTextRich
                )
                return
            }
            ttsMonitorJob.cancel()
        } else {
            // No TTS configured — skip synthesis, proceed with empty audio
            audioFiles = emptyList()
        }

        // Save to database before playback so data isn't lost on cancel
        val earlyConvId = earlyConversationId
        val earlyPairId = earlyQaPairId
        val conversationId = try {
            val audioPathsJson = audioFiles.joinToString(",") { it.absolutePath }
            if (earlyConvId != null && earlyPairId != null) {
                database.conversationDao().updateQAPairResult(
                    qaPairId = earlyPairId,
                    answerText = answerText,
                    audioFilePaths = audioPathsJson,
                    responseId = responseId,
                    model = dbModel,
                    reasoningEffort = reasoningEffort,
                    llmResponseSeconds = llmResponseSeconds,
                    answerTextRich = answerTextRich,
                    reasoningText = lastReasoningText,
                    toolCallsJson = lastToolCallsJson
                )
                earlyConvId
            } else {
                val convId = database.conversationDao().insertConversation(Conversation())
                database.conversationDao().insertQAPair(
                    QAPair(
                        conversationId = convId,
                        questionText = questionText,
                        answerText = answerText,
                        audioFilePaths = audioPathsJson,
                        responseId = responseId,
                        model = dbModel,
                        reasoningEffort = reasoningEffort,
                        llmResponseSeconds = llmResponseSeconds,
                        answerTextRich = answerTextRich,
                        reasoningText = lastReasoningText,
                        toolCallsJson = lastToolCallsJson
                    )
                )
                convId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to database", e)
            earlyConvId ?: -1L
        }

        // DB record is now complete — clear early tracking so cancel/stop won't delete it
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false

        // Playback (on separate job so replay doesn't kill processing)
        launchPipelinePlayback(
            questionText, answerText, responseId, conversationId,
            audioFiles, dbModel, reasoningEffort, llmResponseSeconds,
            answerTextRich,
            reasoningText = lastReasoningText,
            toolCallsJson = toolCallsJson
        )
    }

    private fun launchFollowUpListening(conversationId: Long, previousResponseId: String) {
        followUpJob?.cancel()
        followUpJob = serviceScope.launch {
            try {
                // Emit state: prompt playing
                _pipelineState.value = PipelineState.ListeningForFollowUp(
                    conversationId = conversationId,
                    previousResponseId = previousResponseId,
                    isPromptPlaying = true,
                    isListening = false
                )
                updateNotification("Do you have a follow-up?")

                // Play the follow-up prompt audio
                playFollowUpPrompt()

                // Small pause after prompt
                delay(300)

                // Enable Bluetooth mic routing for follow-up listening
                val btRouted = bluetoothRouter.startBluetoothRecordingRoute()
                val btDevice = if (btRouted) bluetoothRouter.findBluetoothInputDevice() else null
                val useBtSource = btRouted && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S

                // Now start listening with VAD
                _pipelineState.value = PipelineState.ListeningForFollowUp(
                    conversationId = conversationId,
                    previousResponseId = previousResponseId,
                    isPromptPlaying = false,
                    isListening = true
                )
                updateNotification("Listening for follow-up...")

                val recorder = VadAudioRecorder(
                    context = applicationContext,
                    silenceAfterSpeechMs = apiKeyManager.getSilenceTimeoutMs(),
                    noSpeechTimeoutMs = 8000
                )
                vadRecorder = recorder

                val audioFile = recorder.startRecording(
                    serviceScope,
                    preferredDevice = btDevice,
                    useBluetoothAudioSource = useBtSource
                ) ?: run {
                    Log.e(TAG, "Failed to start VAD recorder for follow-up")
                    endInteractiveConversation(conversationId)
                    return@launch
                }

                // Monitor VAD state: update UI on speech detection, then wait for terminal state
                val monitorJob = serviceScope.launch {
                    recorder.state.collect { vadState ->
                        val currentPipelineState = _pipelineState.value
                        if (currentPipelineState is PipelineState.ListeningForFollowUp && vadState.isSpeechDetected) {
                            _pipelineState.value = currentPipelineState.copy(isSpeechDetected = true)
                        }
                    }
                }

                // Wait for a terminal VAD state
                val finalState = recorder.state.first { vadState ->
                    vadState.noSpeechDetected || vadState.speechEndedByVad ||
                            (!vadState.isRecording && vadState.durationMs > 0)
                }
                monitorJob.cancel()
                recorder.release()
                vadRecorder = null

                when {
                    finalState.noSpeechDetected -> {
                        Log.d(TAG, "No speech detected during follow-up, ending conversation")
                        bluetoothRouter.stopBluetoothRecordingRoute()
                        endInteractiveConversation(conversationId)
                    }
                    finalState.speechEndedByVad -> {
                        Log.d(TAG, "Follow-up speech ended by VAD")
                        bluetoothRouter.stopBluetoothRecordingRoute()
                        playPingSound()
                        delay(2000) // Let ping finish before releasing focus
                        // Release focus so other apps (e.g. Spotify) resume during transcription/LLM/TTS
                        audioFocusManager.abandonFocus()
                        handleFollowUpAudio(audioFile, conversationId, previousResponseId)
                    }
                    else -> {
                        // Recording stopped for another reason
                        Log.d(TAG, "Follow-up recording stopped unexpectedly")
                        bluetoothRouter.stopBluetoothRecordingRoute()
                    }
                }
            } catch (e: CancellationException) {
                vadRecorder?.release()
                vadRecorder = null
                bluetoothRouter.stopBluetoothRecordingRoute()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in follow-up listening", e)
                vadRecorder?.release()
                vadRecorder = null
                bluetoothRouter.stopBluetoothRecordingRoute()
                endInteractiveConversation(conversationId)
            }
        }
    }

    private suspend fun playFollowUpPrompt() {
        try {
            // Generate or use cached follow-up prompt audio using TTS
            val ttsConfig = apiKeyManager.getEffectiveTtsConfig()
            val voice = if (ttsConfig.provider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                apiKeyManager.getSelectedVoice()
            } else {
                ttsConfig.voice
            }
            val promptText = apiKeyManager.getFollowUpPrompt()
            val textHash = promptText.hashCode()
            val providerSuffix = ttsConfig.provider
            val promptFile = File(filesDir, "follow_up_prompt_${providerSuffix}_${voice}_${textHash}.mp3")

            if (!promptFile.exists() || promptFile.length() == 0L) {
                // Clean up old cached follow-up prompt files
                filesDir.listFiles()?.filter {
                    it.name.startsWith("follow_up_prompt_") && it.name != promptFile.name
                }?.forEach { it.delete() }

                // Generate the prompt audio
                Log.d(TAG, "Generating follow-up prompt audio for provider=$providerSuffix, text: \"$promptText\"")
                try {
                    val synthesizer = createTtsSynthesizer()
                    synthesizer.synthesize(
                        text = promptText,
                        outputFile = promptFile,
                        instructions = "Speak naturally and warmly, brief and conversational."
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate follow-up prompt, skipping", e)
                    return
                }
            }

            // Play the cached file
            withContext(Dispatchers.Main) {
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mediaPlayer.setDataSource(promptFile.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.playbackParams = PlaybackParams().setSpeed(apiKeyManager.getPlaybackSpeed())

                val completionDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                mediaPlayer.setOnCompletionListener {
                    it.release()
                    completionDeferred.complete(Unit)
                }
                mediaPlayer.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    completionDeferred.complete(Unit)
                    true
                }
                mediaPlayer.start()
                completionDeferred.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play follow-up prompt", e)
        }
    }

    private suspend fun handleFollowUpAudio(
        audioFile: File,
        conversationId: Long,
        previousResponseId: String
    ) {
        // Signal pipeline active for follow-up transcription so ViewModel won't kill it
        isPipelineRunning = true

        // Transcribe the follow-up recording
        val questionText: String
        try {
            emitProcessingState(PipelineState.Transcribing, "Transcribing follow-up...")
            questionText = transcribeAudio(audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transcribe follow-up audio", e)
            endInteractiveConversation(conversationId)
            return
        }

        // Check if this is a dismissal phrase
        val normalizedText = questionText.trim().lowercase()
            .replace(Regex("[.!?,]"), "")
            .trim()
        val dismissalPhrases = setOf(
            "no", "no thanks", "no thank you", "nope", "nah",
            "i'm done", "im done", "i am done",
            "that's all", "thats all", "that is all",
            "nothing", "never mind", "nevermind",
            "stop", "end", "bye", "goodbye", "good bye",
            "no follow up", "no followup", "no follow-up",
            "no more questions", "no questions"
        )

        if (normalizedText in dismissalPhrases || normalizedText.length <= 2) {
            Log.d(TAG, "Dismissal detected: \"$normalizedText\"")
            endInteractiveConversation(conversationId)
            return
        }

        // Real follow-up question: run the full pipeline
        Log.d(TAG, "Follow-up question: \"$questionText\"")

        // Clean up the temporary WAV file
        audioFile.delete()

        // Run the pipeline with the transcribed text directly
        // We need to skip transcription since we already have the text
        // Voice-driven follow-up keeps spoken output.
        runPipelineFromText(questionText, conversationId, previousResponseId, autoSpeak = true)
    }

    private suspend fun runPipelineFromText(
        questionText: String,
        existingConversationId: Long?,
        previousResponseId: String?,
        autoSpeak: Boolean = false
    ) {
        isPipelineRunning = true
        val model = apiKeyManager.getSelectedModel()
        val reasoningEffort = apiKeyManager.getReasoningEffort()
        val thinkingLevel = apiKeyManager.getThinkingLevel()
        val claudeEffort = apiKeyManager.getClaudeEffort()
        val webSearchEnabled = false
        val xSearchEnabled = apiKeyManager.getXSearchEnabled()
        val isGemini = model == ApiKeyManager.MODEL_GEMINI_PRO || model == ApiKeyManager.MODEL_GEMINI_PRO_35
        val isClaude = model == ApiKeyManager.MODEL_CLAUDE_OPUS
        val isGrok = model == ApiKeyManager.MODEL_GROK
        val isDeepResearch = model == ApiKeyManager.MODEL_DEEP_RESEARCH
        val isGeminiDeepResearch = model == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
        val isAnyDeepResearch = isDeepResearch || isGeminiDeepResearch
        val compatId = apiKeyManager.getSelectedCompatProviderId()
        val isCompat = compatId != null && compatibleProviderManager.getProvider(compatId) != null
        val compatProvider = if (isCompat) compatibleProviderManager.getProvider(compatId!!) else null
        val dbModel = compatProvider?.modelId ?: model

        emitProcessingState(PipelineState.Transcribed(questionText))

        // Early DB save: create conversation if null (mirrors runPipeline pattern)
        try {
            val actualConvId = existingConversationId ?: withContext(Dispatchers.IO) {
                database.conversationDao().insertConversation(Conversation())
            }
            val earlyPairId = withContext(Dispatchers.IO) {
                database.conversationDao().insertQAPair(
                    QAPair(
                        conversationId = actualConvId,
                        questionText = questionText,
                        answerText = "",
                        responseId = "",
                        model = dbModel
                    )
                )
            }
            earlyQaPairId = earlyPairId
            earlyConversationId = actualConvId
            earlyConversationWasNew = (existingConversationId == null)
            _activeConversationId.value = actualConvId
        } catch (e: Exception) {
            Log.w(TAG, "Early DB save failed for follow-up, will fall back to late save", e)
            earlyQaPairId = null
        }

        // Step 2: LLM
        val effortLabel = when {
            isCompat && compatProvider != null -> if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else ""
            isClaude -> claudeEffort
            isGemini -> thinkingLevel
            isGrok -> ""
            model == ApiKeyManager.MODEL_PRO -> reasoningEffort
            else -> ""
        }
        val providerName = when {
            isDeepResearch -> "Deep Research"
            isGeminiDeepResearch -> "Gemini Deep Research"
            isCompat && compatProvider != null -> compatProvider.displayName
            isClaude -> "Claude"
            isGemini -> "Gemini"
            isGrok -> "Grok"
            else -> "OpenAI"
        }
        val thinkingLabel = if (isAnyDeepResearch) "Researching" else "Thinking"
        emitProcessingState(
            PipelineState.Thinking(
                questionText = questionText,
                reasoningEffort = effortLabel,
                statusMessage = "Sending request to $providerName...",
                elapsedSeconds = 0,
                thinkingLabel = thinkingLabel
            ),
            "Sending request..."
        )

        val timerJob = launchThinkingTimer(thinkingLabel, effortLabel)

        val answerText: String
        val responseId: String
        lastReasoningText = null
        lastToolCallsJson = null
        val llmStartTime = System.currentTimeMillis()
        try {
            if (isDeepResearch) {
                val llmResponse = openAIClient.createDeepResearch(questionText)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                val citationResult = openAIClient.extractAnswerText(llmResponse)
                answerText = citationResult.cleanText
                lastAnswerTextRich = citationResult.richText
                responseId = llmResponse.id
            } else if (isGeminiDeepResearch) {
                val result = geminiClient.createDeepResearch(questionText)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isClaude) {
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = claudeClient.generateContent(questionText, history, claudeEffort, webSearchEnabled)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = claudeEffort
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isGemini) {
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = geminiClient.generateContent(questionText, history, thinkingLevel, webSearchEnabled, model)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = thinkingLevel
                answerText = result.answerText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else if (isGrok) {
                val result = grokClient.createResponse(questionText, previousResponseId, webSearchEnabled, xSearchEnabled)
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = null
                answerText = result.answerText
                lastAnswerTextRich = result.answerTextRich
                responseId = result.responseId
            } else if (isCompat && compatProvider != null) {
                val history = if (existingConversationId != null) {
                    withContext(Dispatchers.IO) {
                        database.conversationDao()
                            .getQAPairsForConversationOnce(existingConversationId)
                            .map { it.questionText to it.answerText }
                    }
                } else {
                    emptyList()
                }
                val result = runCompatLlm(questionText, history, compatProvider) { currentElapsedSeconds() }
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = if (compatProvider.thinkingEnabled) compatProvider.reasoningEffort else null
                answerText = result.answerText
                lastReasoningText = result.reasoningText
                lastAnswerTextRich = null
                responseId = result.responseId
            } else {
                val llmResponse = openAIClient.createResponse(
                    questionText, previousResponseId, model, reasoningEffort, webSearchEnabled
                )
                timerJob.cancel()
                lastLlmResponseSeconds = ((System.currentTimeMillis() - llmStartTime) / 1000).toInt()
                lastReasoningEffort = if (model == ApiKeyManager.MODEL_PRO) reasoningEffort else null
                val citationResult = openAIClient.extractAnswerText(llmResponse)
                answerText = citationResult.cleanText
                lastAnswerTextRich = citationResult.richText
                responseId = llmResponse.id
            }
        } catch (e: Exception) {
            timerJob.cancel()
            handleError(e, FailedStep.LLM, questionText = questionText)
            return
        }

        emitProcessingState(PipelineState.ThinkingComplete(questionText, answerText, responseId, lastAnswerTextRich))

        // Step 3: TTS (skipped entirely for silent text submissions)
        val audioFiles: List<File>
        if (!autoSpeak) {
            // Silent mode — answer is shown as text; audio can be synthesized
            // on demand later via synthesizeAndPlayReply().
            audioFiles = emptyList()
        } else if (apiKeyManager.isTtsConfigured()) {
            val sessionDir = File(filesDir, "audio/${System.currentTimeMillis()}")
            cleanupPendingTtsSessionDir()
            pendingTtsSessionDir = sessionDir
            val ttsConfig = apiKeyManager.getEffectiveTtsConfig()
            val ttsInstructions = if (ttsConfig.provider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                apiKeyManager.getTtsInstructions()
            } else {
                null
            }
            val synthesizer = createTtsSynthesizer()
            val ttsProcessor = TtsProcessor(synthesizer, sessionDir, ttsInstructions)

            val ttsMonitorJob = serviceScope.launch {
                ttsProcessor.progress.collect { progress ->
                    if (progress.totalChunks > 0 && !progress.isComplete) {
                        emitProcessingState(
                            PipelineState.GeneratingAudio(
                                questionText, answerText, responseId,
                                progress.completedChunks, progress.totalChunks,
                                progress.statusMessage,
                                answerTextRich = lastAnswerTextRich
                            ),
                            "Audio ${progress.completedChunks}/${progress.totalChunks}: ${progress.statusMessage}"
                        )
                    }
                }
            }

            try {
                audioFiles = ttsProcessor.processText(answerText)
                pendingTtsSessionDir = null
            } catch (e: Exception) {
                ttsMonitorJob.cancel()
                handleError(
                    e, FailedStep.TTS,
                    questionText = questionText,
                    answerText = answerText,
                    responseId = responseId,
                    answerTextRich = lastAnswerTextRich
                )
                return
            }
            ttsMonitorJob.cancel()
        } else {
            // No TTS configured — skip TTS, proceed with empty audio
            audioFiles = emptyList()
        }

        // Step 4: Save to database
        val earlyConvId = earlyConversationId
        val earlyPairId = earlyQaPairId
        var savedQaPairId: Long? = null
        val conversationId = try {
            val audioPathsJson = audioFiles.joinToString(",") { it.absolutePath }
            if (earlyConvId != null && earlyPairId != null) {
                database.conversationDao().updateQAPairResult(
                    qaPairId = earlyPairId,
                    answerText = answerText,
                    audioFilePaths = audioPathsJson,
                    responseId = responseId,
                    model = dbModel,
                    reasoningEffort = lastReasoningEffort,
                    llmResponseSeconds = lastLlmResponseSeconds,
                    answerTextRich = lastAnswerTextRich,
                    reasoningText = lastReasoningText,
                    toolCallsJson = lastToolCallsJson
                )
                savedQaPairId = earlyPairId
                earlyConvId
            } else {
                val convId = existingConversationId ?: run {
                    withContext(Dispatchers.IO) {
                        database.conversationDao().insertConversation(Conversation())
                    }
                }
                savedQaPairId = database.conversationDao().insertQAPair(
                    QAPair(
                        conversationId = convId,
                        questionText = questionText,
                        answerText = answerText,
                        audioFilePaths = audioPathsJson,
                        responseId = responseId,
                        model = dbModel,
                        reasoningEffort = lastReasoningEffort,
                        llmResponseSeconds = lastLlmResponseSeconds,
                        answerTextRich = lastAnswerTextRich,
                        reasoningText = lastReasoningText,
                        toolCallsJson = lastToolCallsJson
                    )
                )
                convId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to database", e)
            earlyConvId ?: existingConversationId ?: -1L
        }

        // DB record is now complete — clear early tracking so cancel/stop won't delete it
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false

        // Step 5: Playback (silent submissions skip playback inside and go
        // straight to Complete since audioFiles is empty)
        launchPipelinePlayback(
            questionText, answerText, responseId, conversationId,
            audioFiles, dbModel, lastReasoningEffort, lastLlmResponseSeconds,
            lastAnswerTextRich,
            reasoningText = lastReasoningText,
            qaPairId = savedQaPairId,
            toolCallsJson = lastToolCallsJson
        )
    }

    private fun endInteractiveConversation(conversationId: Long) {
        isPipelineRunning = false
        _activeConversationId.value = null
        audioFocusManager.abandonFocus()
        _pipelineState.value = PipelineState.InteractiveEnding(conversationId)
        updateNotification("Conversation complete")
        serviceScope.launch {
            delay(1500)
            _pipelineState.value = PipelineState.Idle
            stopSelfWithWakeWordGuard()
        }
    }

    fun cancelFollowUpListening() {
        followUpJob?.cancel()
        followUpJob = null
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.stopBluetoothRecordingRoute()
        audioFocusManager.abandonFocus()
    }

    /**
     * Aborts follow-up VAD listening because the app went to the background.
     * Releases the microphone and drops back to Idle; the conversation itself is
     * kept so the user can resume from the UI when they come back.
     */
    fun abandonFollowUpListening() {
        if (_pipelineState.value !is PipelineState.ListeningForFollowUp) return
        Log.i(TAG, "App backgrounded — abandoning follow-up listening")
        cancelFollowUpListening()
        isPipelineRunning = false
        _pipelineState.value = PipelineState.Idle
        stopSelfWithWakeWordGuard()
    }

    private fun cleanupPendingTtsSessionDir() {
        val tempAudioDir = pendingTtsSessionDir ?: return
        pendingTtsSessionDir = null
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (tempAudioDir.exists()) {
                    tempAudioDir.deleteRecursively()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up temp TTS audio dir", e)
            }
        }
    }

    /**
     * Dismisses an active pipeline error and returns to [PipelineState.Idle].
     * Stops foreground work / clears notification when no pipeline is running,
     * fails closed any pending tool confirmation, and cleans up incomplete temp audio.
     * When TTS failed after the LLM already answered, persists the text answer so
     * history stays intact and follow-up questions remain possible.
     */
    fun dismissError() {
        val errorState = _pipelineState.value as? PipelineState.Error

        // Fail closed: any pending tool confirmation must never hang.
        denyPendingToolConfirmation()

        synthesisJob?.cancel()
        synthesisJob = null
        followUpJob?.cancel()
        followUpJob = null
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.stopBluetoothRecordingRoute()
        cleanupPendingTtsSessionDir()

        val pairId = earlyQaPairId
        val convId = earlyConversationId
        val wasNew = earlyConversationWasNew
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false
        _activeConversationId.value = null

        if (pairId != null) {
            if (errorState != null && errorState.failedStep == FailedStep.TTS && errorState.answerText != null) {
                val model = apiKeyManager.getSelectedModel()
                val compatId = apiKeyManager.getSelectedCompatProviderId()
                val compatProvider = compatibleProviderManager.getProvider(compatId ?: "")
                val dbModel = compatProvider?.modelId ?: model
                val answer = errorState.answerText
                val respId = errorState.responseId ?: ""
                val rich = errorState.answerTextRich
                val reasoning = lastReasoningText
                val effort = lastReasoningEffort
                val llmSec = lastLlmResponseSeconds
                val toolsJson = lastToolCallsJson
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        database.conversationDao().updateQAPairResult(
                            qaPairId = pairId,
                            answerText = answer,
                            audioFilePaths = "",
                            responseId = respId,
                            model = dbModel,
                            reasoningEffort = effort,
                            llmResponseSeconds = llmSec,
                            answerTextRich = rich,
                            reasoningText = reasoning,
                            toolCallsJson = toolsJson
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist answer text on TTS error dismiss", e)
                    }
                }
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        database.conversationDao().deleteQAPair(pairId)
                        if (wasNew && convId != null) {
                            val remaining = database.conversationDao().getQAPairCountForConversation(convId)
                            if (remaining == 0) {
                                database.conversationDao().deleteConversation(convId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clean up dismissed early DB records", e)
                    }
                }
            }
        }

        _pipelineState.value = PipelineState.Idle

        if (!isPipelineRunning) {
            isMuted = false
            playbackJob?.cancel()
            audioPlayer.stop()
            audioFocusManager.abandonFocus()
            deactivateMediaSession()
            stopSelfWithWakeWordGuard()
        }
    }

    fun cancelPipeline() {
        isMuted = false
        cleanupPendingTtsSessionDir()
        // Clean up early DB records
        val cancelledQaPairId = earlyQaPairId
        val cancelledConvId = earlyConversationId
        val cancelledWasNew = earlyConversationWasNew
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false
        _activeConversationId.value = null
        if (cancelledQaPairId != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().deleteQAPair(cancelledQaPairId)
                    if (cancelledWasNew && cancelledConvId != null) {
                        val remaining = database.conversationDao().getQAPairCountForConversation(cancelledConvId)
                        if (remaining == 0) {
                            database.conversationDao().deleteConversation(cancelledConvId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up cancelled early DB records", e)
                }
            }
        }

        isPipelineRunning = false
        pipelineSuperseded = true
        pipelineJob?.cancel()
        playbackJob?.cancel()
        synthesisJob?.cancel()
        // Fail closed: a tool-confirmation prompt left unanswered must never hang the pipeline
        denyPendingToolConfirmation()
        followUpJob?.cancel()
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.stopBluetoothRecordingRoute()
        audioPlayer.stop()
        audioFocusManager.abandonFocus()
        deactivateMediaSession()
        _pipelineState.value = PipelineState.Idle
        stopSelfWithWakeWordGuard()
    }

    fun setPlaybackSpeed(speed: Float) {
        audioPlayer.setPlaybackSpeed(speed)
        apiKeyManager.setPlaybackSpeed(speed)
    }

    fun getPlaybackSpeed(): Float = apiKeyManager.getPlaybackSpeed()

    fun pausePlayback() {
        pausedByFocusLoss = false
        audioPlayer.pause()
        updateMediaSessionState(AndroidPlaybackState.STATE_PAUSED)
    }

    fun resumePlayback() {
        audioPlayer.resume()
        updateMediaSessionState(AndroidPlaybackState.STATE_PLAYING)
    }

    fun stopPlayback() {
        isMuted = false
        // Clean up early DB records (stopPlayback also cancels the pipeline)
        val stoppedQaPairId = earlyQaPairId
        val stoppedConvId = earlyConversationId
        val stoppedWasNew = earlyConversationWasNew
        earlyConversationId = null
        earlyQaPairId = null
        earlyConversationWasNew = false
        _activeConversationId.value = null
        if (stoppedQaPairId != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    database.conversationDao().deleteQAPair(stoppedQaPairId)
                    if (stoppedWasNew && stoppedConvId != null) {
                        val remaining = database.conversationDao().getQAPairCountForConversation(stoppedConvId)
                        if (remaining == 0) {
                            database.conversationDao().deleteConversation(stoppedConvId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clean up stopped early DB records", e)
                }
            }
        }

        isPipelineRunning = false
        pipelineSuperseded = true
        pipelineJob?.cancel()
        playbackJob?.cancel()
        synthesisJob?.cancel()
        // Fail closed: a tool-confirmation prompt left unanswered must never hang the pipeline
        denyPendingToolConfirmation()
        followUpJob?.cancel()
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.stopBluetoothRecordingRoute()
        audioPlayer.stop()
        audioFocusManager.abandonFocus()
        deactivateMediaSession()
        _pipelineState.value = PipelineState.Idle
        stopSelfWithWakeWordGuard()
    }

    fun replayAudio(files: List<File>, questionText: String = "", answerText: String = "") {
        pipelineSuperseded = true   // Stop pipeline from emitting states
        playbackJob?.cancel()       // Cancel any active playback (not processing!)
        audioPlayer.stop()
        audioFocusManager.abandonFocus()
        deactivateMediaSession()

        // Apply saved playback speed
        audioPlayer.setPlaybackSpeed(apiKeyManager.getPlaybackSpeed())

        _pipelineState.value = PipelineState.Playing(
            questionText = questionText,
            answerText = answerText,
            responseId = "",
            conversationId = -1L,
            currentChunk = 1,
            totalChunks = files.size,
            audioFiles = files
        )
        updateNotification("Replaying audio...")

        // Acquire wake lock before launching so playback works with screen off
        acquirePlaybackWakeLock()

        playbackJob = serviceScope.launch {
            try {
                val focusGranted = audioFocusManager.requestPlaybackFocus()
                if (!focusGranted) {
                    Log.w(TAG, "Audio focus not granted for replay, proceeding anyway")
                }
                activateMediaSession()

                val playbackMonitorJob = serviceScope.launch {
                    audioPlayer.state.collect { playState ->
                        if (playState.isPlaying || playState.isPaused) {
                            _pipelineState.value = PipelineState.Playing(
                                questionText = questionText,
                                answerText = answerText,
                                responseId = "",
                                conversationId = -1L,
                                currentChunk = playState.currentChunk,
                                totalChunks = playState.totalChunks,
                                audioFiles = files,
                                isPaused = playState.isPaused
                            )
                        }
                    }
                }

                try {
                    audioPlayer.playFiles(files)
                } catch (_: CancellationException) {
                    playbackMonitorJob.cancel()
                    throw CancellationException()
                } catch (_: Exception) {
                }

                playbackMonitorJob.cancel()
                audioFocusManager.abandonFocus()
                deactivateMediaSession()

                // If the pipeline finished during this replay, auto-play its result
                val pending = consumePendingPipelineResult()
                if (pending != null) {
                    pipelineSuperseded = false
                    launchPipelinePlayback(
                        pending.questionText, pending.answerText, pending.responseId,
                        pending.conversationId, pending.audioFiles, pending.model,
                        pending.reasoningEffort, pending.llmResponseSeconds,
                        pending.answerTextRich,
                        reasoningText = pending.reasoningText,
                        qaPairId = pending.qaPairId,
                        toolCallsJson = pending.toolCallsJson
                    )
                } else {
                    _pipelineState.value = PipelineState.Idle
                    stopSelfWithWakeWordGuard()
                }
            } finally {
                releasePlaybackWakeLock()
            }
        }
    }

    /**
     * Synthesizes audio for an already-completed (silent) text reply and plays
     * it through the normal replay path, so pause/resume/seek/speed/stop all
     * work. Persists the generated file paths to the QAPair when [qaPairId] is
     * given, and reports the files back via [onAudioReady] so the caller can
     * update its UI (subsequent taps replay without re-synthesizing).
     */
    fun synthesizeAndPlayReply(
        qaPairId: Long?,
        questionText: String,
        answerText: String,
        onAudioReady: ((List<File>) -> Unit)? = null
    ) {
        // Stop any active playback and stop the pipeline from emitting states
        // while synthesis runs (same supersession contract as replayAudio).
        pipelineSuperseded = true
        playbackJob?.cancel()
        // A rapid double-tap must not queue a second TTS request: the previous synthesis
        // is cancelled (and never reaches replayAudio) before the new one starts.
        synthesisJob?.cancel()
        audioPlayer.stop()
        audioFocusManager.abandonFocus()
        deactivateMediaSession()

        updateNotification("Generating audio...")

        synthesisJob = serviceScope.launch {
            val audioFiles: List<File>
            try {
                val sessionDir = File(filesDir, "audio/${System.currentTimeMillis()}")
                val ttsConfig = apiKeyManager.getEffectiveTtsConfig()
                val ttsInstructions = if (ttsConfig.provider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                    apiKeyManager.getTtsInstructions()
                } else {
                    null
                }
                val synthesizer = createTtsSynthesizer()
                val ttsProcessor = TtsProcessor(synthesizer, sessionDir, ttsInstructions)
                audioFiles = ttsProcessor.processText(answerText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to synthesize reply audio", e)
                return@launch
            }

            if (audioFiles.isEmpty()) {
                Log.w(TAG, "TTS produced no audio for reply")
                return@launch
            }

            if (qaPairId != null) {
                try {
                    withContext(Dispatchers.IO) {
                        database.conversationDao().updateAudioFilePaths(
                            qaPairId,
                            audioFiles.joinToString(",") { it.absolutePath }
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist synthesized audio paths", e)
                }
            }

            onAudioReady?.invoke(audioFiles)
            // Reuse the replay path so all playback controls work seamlessly.
            replayAudio(audioFiles, questionText, answerText)
        }
    }

    fun restartPlayback() {
        val currentState = _pipelineState.value
        if (currentState !is PipelineState.Playing) return
        audioPlayer.stop()
        replayAudio(currentState.audioFiles, currentState.questionText, currentState.answerText)
    }

    fun getPlaybackPosition(): Long {
        val durations = audioPlayer.getChunkDurations()
        val idx = audioPlayer.getCurrentChunkIndex()
        val pos = audioPlayer.getCurrentPosition()
        return durations.take(idx).sumOf { it.toLong() } + pos
    }

    fun getPlaybackDuration(): Long {
        return audioPlayer.getChunkDurations().sumOf { it.toLong() }
    }

    fun seekRelative(deltaMs: Int) {
        val durations = audioPlayer.getChunkDurations()
        if (durations.isEmpty()) return

        val currentIdx = audioPlayer.getCurrentChunkIndex()
        val currentPos = audioPlayer.getCurrentPosition()

        // Compute global position: sum of all previous chunk durations + position in current chunk
        val globalPos = durations.take(currentIdx).sumOf { it.toLong() } + currentPos
        val totalDuration = durations.sumOf { it.toLong() }
        if (totalDuration <= 0) return

        val targetGlobal = (globalPos + deltaMs).coerceIn(0L, totalDuration)

        // Walk durations to find target chunk and offset within it
        var accumulated = 0L
        for (i in durations.indices) {
            val chunkEnd = accumulated + durations[i]
            if (targetGlobal < chunkEnd || i == durations.lastIndex) {
                val offset = (targetGlobal - accumulated).toInt().coerceAtLeast(0)
                audioPlayer.seekToChunk(i, offset)
                return
            }
            accumulated = chunkEnd
        }
    }

    fun resetState() {
        _pipelineState.value = PipelineState.Idle
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SlothSpeak Processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while processing your question"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlothSpeak")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SlothSpeak::PipelineWakeLock"
        ).apply {
            acquire(65 * 60 * 1000L) // 65 minute timeout (covers Deep Research)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun acquirePlaybackWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        playbackWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SlothSpeak::PlaybackWakeLock"
        ).apply {
            acquire(30 * 60 * 1000L) // 30 minute timeout
        }
    }

    private fun releasePlaybackWakeLock() {
        playbackWakeLock?.let {
            if (it.isHeld) it.release()
        }
        playbackWakeLock = null
    }

    // ============================================================
    // Wake Word Detection Lifecycle
    /**
     * Called by the ViewModel's WakeWordDetector when 'Hey Jarvis' is detected.
     * Emits a trigger that the ViewModel observes to start a fresh New Question flow.
     */
    fun triggerWakeWordDetected() {
        Log.i(TAG, "Wake word detected — triggering fresh New Question")
        _wakeWordTriggered.value = true
    }

    /**
     * The ViewModel calls this to acknowledge and reset the wake word trigger.
     */
    fun acknowledgeWakeWordTrigger() {
        _wakeWordTriggered.value = false
    }

    /**
     * Called by the ViewModel when the wake word detector is armed.
     * Starts/keeps this service alive as a foreground MICROPHONE service so
     * Android doesn't silence the AudioRecord session when the app backgrounds.
     */
    fun startWakeWordForeground() {
        val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) return  // mic not available, skip foregrounding
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return

        if (!wakeWordForegroundActive) {
            wakeWordForegroundActive = true
            Log.i(TAG, "Wake word foreground: starting MICROPHONE + MEDIA_PLAYBACK FGS")
        }
        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            createNotification("Listening for \"Hey Jarvis\"…"),
            serviceType
        )
    }

    /**
     * Called by the ViewModel when the wake word detector is stopped.
     * If no pipeline is active, stops the foreground service (and ourselves).
     */
    fun stopWakeWordForegroundIfIdle() {
        val wasActive = wakeWordForegroundActive
        wakeWordForegroundActive = false
        if (!wasActive) return
        Log.i(TAG, "Wake word foreground: stopped")
        // If no pipeline is active, drop the FGS state (which also removes the
        // "Listening…" notification) and stop ourselves. If a pipeline IS
        // active, it owns the foreground and will stop us when it completes.
        if (!isPipelineRunning && !hasActivePipeline()) {
            dropForegroundAndStopSelf()
        }
    }

    /**
     * Removes the ongoing notification and exits the foreground state before
     * stopping. Without this a bound service lingers as a foreground service
     * and its "Listening…" notification survives indefinitely.
     */
    private fun dropForegroundAndStopSelf() {
        try {
            androidx.core.app.ServiceCompat.stopForeground(
                this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
            )
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        stopSelf()
    }

    /**
     * Guard called before every stopSelf() in pipeline-completion paths.
     * If wake word foreground is active and no pipeline is running, re-assert
     * the foreground instead of stopping, keeping the mic alive.
     */
    private fun stopSelfWithWakeWordGuard() {
        if (wakeWordForegroundActive && !isPipelineRunning && !hasActivePipeline()) {
            // Stay alive — wake word listening needs the foreground service
            val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (micGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    createNotification("Listening for \"Hey Jarvis\"…"),
                    serviceType
                )
            } else {
                dropForegroundAndStopSelf()
            }
            return
        }
        dropForegroundAndStopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        pipelineJob?.cancel()
        playbackJob?.cancel()
        followUpJob?.cancel()
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.release()
        audioPlayer.stop()
        audioFocusManager.release()
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
        releasePlaybackWakeLock()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        playbackJob?.cancel()
        followUpJob?.cancel()
        vadRecorder?.release()
        vadRecorder = null
        bluetoothRouter.release()
        serviceScope.cancel()
        audioPlayer.stop()
        audioFocusManager.release()
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
        releasePlaybackWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SlothSpeakService"
        const val CHANNEL_ID = "slothspeak_processing"
        const val NOTIFICATION_ID = 1
        /** Safety net per tool call (includes user approval time; see [onCreate]). */
        private const val TOOL_CALL_TIMEOUT_MS = 10 * 60_000L
    }
}
