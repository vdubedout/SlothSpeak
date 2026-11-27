package com.slothspeak.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.slothspeak.service.SlothSpeakService
import com.slothspeak.ui.components.ChatComposer
import com.slothspeak.ui.components.ModelSwitcherSheet
import com.slothspeak.ui.components.QAPairCard
import com.slothspeak.ui.components.StatusIndicator
import com.slothspeak.ui.components.ToolConfirmationDialog
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.TextSecondary
import com.slothspeak.viewmodel.ConversationViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ConversationViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val sessionItems by viewModel.sessionItems.collectAsState()
    val currentResponseId by viewModel.currentResponseId.collectAsState()
    val currentPipelineState by viewModel.pipelineState.collectAsState()
    val isViewingActivePipeline by viewModel.isViewingActivePipeline.collectAsState()
    val isPipelineRunning by viewModel.isPipelineRunning.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val llmProviders by viewModel.llmProviders.collectAsState()
    val showCancelConfirmation by viewModel.showCancelConfirmation.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val activeProvider = llmProviders.firstOrNull { it.active }
    var showModelSwitcher by remember { mutableStateOf(false) }
    // Whether a pending (post-permission) mic tap should be a follow-up
    var pendingVoiceIsFollowUp by remember { mutableStateOf(false) }

    // Refresh API key status whenever this screen becomes visible (e.g. returning from settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshApiKeyStatus()
                viewModel.maybeStartWakeWordDetection()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var playbackSpeed by remember { mutableFloatStateOf(viewModel.getPlaybackSpeed()) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var hasBluetoothPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Track whether we've already asked for BT permission (don't re-ask if denied)
    var hasAskedBluetoothPermission by remember { mutableStateOf(hasBluetoothPermission) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission =
                permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissions.containsKey(Manifest.permission.BLUETOOTH_CONNECT)) {
            hasBluetoothPermission =
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
            hasAskedBluetoothPermission = true
        }
        if (hasAudioPermission) {
            // Resume whichever flow the mic tap started (follow-up vs new)
            if (pendingVoiceIsFollowUp) {
                viewModel.startFollowUp()
            } else {
                viewModel.startNewQuestion()
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Microphone permission is required to record questions"
                )
            }
        }
    }

    /**
     * Mic tap from the composer: request missing permissions, then start
     * either a follow-up (inside an active conversation) or a new question.
     */
    fun startVoiceCapture() {
        pendingVoiceIsFollowUp =
            currentResponseId != null && viewModel.isFollowUpSupported()
        val needsBtPermission = !hasBluetoothPermission && !hasAskedBluetoothPermission
        if (!hasAudioPermission || !hasNotificationPermission || needsBtPermission) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && needsBtPermission) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            permissionLauncher.launch(perms.toTypedArray())
        } else if (pendingVoiceIsFollowUp) {
            viewModel.startFollowUp()
        } else {
            viewModel.startNewQuestion()
        }
    }

    // Show snackbar on service bind error
    LaunchedEffect(uiState.serviceBindError) {
        if (uiState.serviceBindError) {
            snackbarHostState.showSnackbar(
                "Failed to start service. Please try again."
            )
            viewModel.clearServiceBindError()
        }
    }

    // Auto-scroll when new items are added
    LaunchedEffect(sessionItems.size) {
        if (sessionItems.isNotEmpty()) {
            listState.animateScrollToItem(sessionItems.lastIndex)
        }
    }

    // Keep the latest message visible when the soft keyboard opens and shrinks the list
    KeepLatestVisibleOnImeOpen(
        listState = listState,
        itemCount = sessionItems.size
    )

    // Cancel confirmation dialog
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancelConfirmation() },
            title = { Text("Cancel processing?") },
            text = { Text("This will stop the current job. Any progress will be lost.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCancelPipeline() }) {
                    Text("Cancel Job", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCancelConfirmation() }) {
                    Text("Keep Running")
                }
            }
        )
    }

    // Tool write-confirmation: shown whenever the service is waiting for the user's decision,
    // even when they navigated away from the active conversation (the pipeline is blocked on it).
    (currentPipelineState as? SlothSpeakService.PipelineState.AwaitingToolConfirmation)?.let { pending ->
        ToolConfirmationDialog(
            request = pending.request,
            onDecision = { viewModel.resolveToolConfirmation(it) }
        )
    }

    MainScaffold(
        snackbarHostState = snackbarHostState,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.goHome() }
                    ) {
                        Image(
                            painter = painterResource(id = com.slothspeak.R.drawable.ic_sloth),
                            contentDescription = "Sloth icon",
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "SlothSpeak",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                },
                actions = {
                    // Show indicator chip when pipeline is processing (hides once audio plays)
                    if (isPipelineRunning) {
                        ActivePipelineChip(
                            onClick = { viewModel.returnToActivePipeline() }
                        )
                    }
                    if (sessionItems.isNotEmpty()) {
                        IconButton(onClick = { viewModel.goHome() }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New chat",
                                tint = AccentGreen
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "History",
                            tint = AccentGreen
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = AccentGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) {
            if (!uiState.canConverse) {
                // No usable API key - prompt to set one
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Welcome to SlothSpeak",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Set an API key for any provider — or add an OpenAI-compatible provider — in Settings to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Open Settings")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.lowStorageWarning) {
                        Text(
                            text = "Low storage warning: less than 100 MB available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // Conversation items
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (sessionItems.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillParentMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Ask anything",
                                        style = MaterialTheme.typography.headlineMedium,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Type a message below or tap the mic for voice chat. " +
                                                "SlothSpeak will think deeply and give you the answer.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(32.dp))
                                    Image(
                                        painter = painterResource(id = com.slothspeak.R.drawable.ic_sloth),
                                        contentDescription = "SlothSpeak",
                                        modifier = Modifier.size(120.dp)
                                    )
                                }
                            }
                        }

                        items(sessionItems) { item ->
                            QAPairCard(
                                questionText = item.questionText,
                                answerText = item.answerText,
                                timestamp = item.timestamp,
                                audioFiles = item.audioFiles,
                                onReplayClick = item.audioFiles?.let { files ->
                                    { viewModel.replayAudio(files, item.questionText, item.answerText ?: "") }
                                },
                                model = item.model,
                                reasoningEffort = item.reasoningEffort,
                                llmResponseSeconds = item.llmResponseSeconds,
                                answerTextRich = item.answerTextRich,
                                reasoningText = item.reasoningText,
                                canSpeak = uiState.ttsConfigured,
                                onSpeakClick = { viewModel.speakReply(item) },
                                toolTrace = item.toolTrace
                            )
                        }

                        // Spacer at bottom for status indicator
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    // Pipeline status — show for active pipeline processing, any playback (incl. replay),
                    // or on-demand read-aloud synthesis/error
                    val showStatusIndicator = currentPipelineState !is SlothSpeakService.PipelineState.Idle &&
                        currentPipelineState !is SlothSpeakService.PipelineState.Complete &&
                        (isViewingActivePipeline ||
                            currentPipelineState is SlothSpeakService.PipelineState.Playing ||
                            (currentPipelineState as? SlothSpeakService.PipelineState.GeneratingAudio)?.fromReadAloud == true ||
                            (currentPipelineState as? SlothSpeakService.PipelineState.Error)?.fromReadAloud == true)
                    if (showStatusIndicator) {
                        StatusIndicator(
                            pipelineState = currentPipelineState,
                            onRetry = { viewModel.retryFromError() },
                            onCancel = { viewModel.requestCancelPipeline() },
                            onDismissError = { viewModel.dismissError() },
                            onStop = { viewModel.stopPlayback() },
                            onRestartPlayback = { viewModel.restartPlayback() },
                            onRewind20s = { viewModel.seekRelative(-20_000) },
                            onForward30s = { viewModel.seekRelative(30_000) },
                            onPause = { viewModel.pausePlayback() },
                            onResume = { viewModel.resumePlayback() },
                            currentSpeed = playbackSpeed,
                            onGetPosition = { viewModel.getPlaybackPosition() },
                            onGetDuration = { viewModel.getPlaybackDuration() },
                            onSpeedChange = { speed ->
                                playbackSpeed = speed
                                viewModel.setPlaybackSpeed(speed)
                            },
                            onStopConversation = { viewModel.cancelInteractiveListening() },
                            isMuted = isMuted,
                            onToggleMute = { viewModel.toggleMute() }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Bottom section - hidden during processing. Any other non-terminal state
                    // (Thinking, UsingTool, AwaitingToolConfirmation, …) counts as processing.
                    val isProcessing = currentPipelineState !is SlothSpeakService.PipelineState.Idle &&
                            currentPipelineState !is SlothSpeakService.PipelineState.Complete &&
                            currentPipelineState !is SlothSpeakService.PipelineState.Error &&
                            currentPipelineState !is SlothSpeakService.PipelineState.InteractiveEnding

                    if (!isProcessing) {
                        ChatComposer(
                            voiceAvailable = uiState.voiceAvailable,
                            onMicClick = { startVoiceCapture() },
                            onVoiceUnavailable = {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Voice input needs a speech-to-text provider",
                                        actionLabel = "Settings"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onNavigateToSettings()
                                    }
                                }
                            },
                            onSend = { viewModel.submitTextQuestion(it) },
                            activeProvider = activeProvider,
                            onModelPillClick = { showModelSwitcher = true }
                        )
                    }
                }
            }

            // Recording overlay
            if (uiState.showRecordingOverlay) {
                RecordingOverlay(
                    audioRecorder = viewModel.audioRecorder,
                    onStop = { viewModel.stopRecording() },
                    isInteractive = uiState.isInteractiveRecording,
                    vadSpeechDetected = uiState.vadSpeechDetected,
                    streaming = StreamingCaptureUi(
                        isStreaming = uiState.isStreamingTranscription,
                        modelLoading = uiState.streamingModelLoading,
                        text = uiState.streamingText,
                        error = uiState.streamingError,
                        durationMs = uiState.recordingDurationMs,
                        isEditing = uiState.isEditingTranscript,
                        editableText = uiState.editableTranscript,
                        autoSendRemainingMs = uiState.autoSendRemainingMs
                    ),
                    onTranscriptChange = { viewModel.updateStreamingTranscript(it) },
                    onSendTranscript = { viewModel.sendStreamingTranscript() },
                    onCancelTranscript = { viewModel.cancelStreamingTranscript() }
                )
            }
    }

    // Model switcher popup (opened from the pill below the composer)
    if (showModelSwitcher) {
        ModelSwitcherSheet(
            providers = llmProviders,
            onSelectModel = { provider, modelId -> viewModel.selectLlmModel(provider, modelId) },
            onManageModels = {
                showModelSwitcher = false
                onNavigateToSettings()
            },
            onDismiss = { showModelSwitcher = false }
        )
    }
}

/**
 * Scrolls the conversation list to the bottom spacer whenever the IME opens or
 * shrinks the list viewport so the latest Q&A card stays visible above the composer.
 */
@Composable
internal fun KeepLatestVisibleOnImeOpen(
    listState: LazyListState,
    itemCount: Int
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val currentItemCount by rememberUpdatedState(itemCount)
    LaunchedEffect(imeInsets, density, listState) {
        var previousImeBottomPx = 0
        var previousViewportHeight = 0
        snapshotFlow {
            imeInsets.getBottom(density) to listState.layoutInfo.viewportSize.height
        }.collect { (imeBottomPx, viewportHeight) ->
            val imeGrew = imeBottomPx > previousImeBottomPx
            val viewportShrankWithImeOpen =
                imeBottomPx > 0 && previousViewportHeight > 0 && viewportHeight < previousViewportHeight
            previousImeBottomPx = imeBottomPx
            previousViewportHeight = viewportHeight
            if ((imeGrew || viewportShrankWithImeOpen) && currentItemCount > 0) {
                // Wait for the current layout/deactivation pass to settle before scrolling.
                withFrameNanos { }
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > 0) {
                    listState.scrollToItem(currentItemCount.coerceAtMost(totalItems - 1))
                }
            }
        }
    }
}

/**
 * Home screen [Scaffold] shell that applies system-bar and IME insets to both
 * the content area and snackbar host while consuming Scaffold's padding first
 * so the navigation bar is never double-padded when the soft keyboard opens.
 */
@Composable
internal fun MainScaffold(
    snackbarHostState: SnackbarHostState,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .consumeWindowInsets(WindowInsets.navigationBars)
                    .imePadding()
            )
        },
        topBar = topBar
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            content = content
        )
    }
}

@Composable
private fun ActivePipelineChip(
    onClick: () -> Unit
) {
    val statusText = "Thinking..."

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .alpha(pulseAlpha)
            .background(
                color = AccentGreen.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Sync,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = AccentGreen
        )
    }
}

/**
 * Text-only question input bar shown when no OpenAI key is present
 * (voice unavailable but one of the other providers is configured).
 */
@Composable
fun TextInputQuestionBar(
    onQuestionSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text(
            text = "Voice input needs a speech-to-text provider — Settings",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type your question…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    cursorColor = AccentGreen,
                    focusedLabelColor = AccentGreen
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onQuestionSubmit(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) AccentGreen else TextSecondary
                )
            }
        }
    }
}
