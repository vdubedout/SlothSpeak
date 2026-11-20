package com.slothspeak.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.slothspeak.audio.AudioRecorder
import com.slothspeak.ui.components.RecordStopButton
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.TextSecondary
import java.util.Locale

/**
 * UI state for the live on-device (FUTO/Nemotron) capture screen.
 * Non-streaming providers leave [isStreaming] false and use the classic overlay.
 */
data class StreamingCaptureUi(
    val isStreaming: Boolean = false,
    val modelLoading: Boolean = false,
    val text: String = "",
    val error: String? = null,
    val durationMs: Long = 0L,
    val isEditing: Boolean = false,
    val editableText: String = "",
    val autoSendRemainingMs: Long = 0L
)

@Composable
fun RecordingOverlay(
    audioRecorder: AudioRecorder,
    onStop: () -> Unit,
    isInteractive: Boolean = false,
    vadSpeechDetected: Boolean = false,
    streaming: StreamingCaptureUi = StreamingCaptureUi(),
    onTranscriptChange: (String) -> Unit = {},
    onSendTranscript: () -> Unit = {},
    onCancelTranscript: () -> Unit = {}
) {
    if (streaming.isStreaming) {
        StreamingRecordingOverlay(
            streaming = streaming,
            isInteractive = isInteractive,
            vadSpeechDetected = vadSpeechDetected,
            onStop = onStop,
            onTranscriptChange = onTranscriptChange,
            onSendTranscript = onSendTranscript,
            onCancelTranscript = onCancelTranscript
        )
        return
    }

    val recordingState by audioRecorder.state.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "recording_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Recording indicator dot - green when speech detected in interactive mode
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .alpha(dotAlpha)
                    .background(
                        if (isInteractive && vadSpeechDetected) AccentGreen else ErrorRed,
                        CircleShape
                    )
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (isInteractive) {
                // Interactive mode: show "Listening..." with VAD status
                Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (vadSpeechDetected)
                        "Speaking... will auto-stop when you pause"
                    else
                        "Waiting for speech...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vadSpeechDetected) AccentGreen else TextSecondary
                )
            } else {
                // Normal mode: countdown
                val remainingMs = (AudioRecorder.MAX_DURATION_MS - recordingState.durationMs)
                    .coerceAtLeast(0)
                val remainingSec = remainingMs / 1000
                val remainMin = remainingSec / 60
                val remainSec = remainingSec % 60
                Text(
                    text = String.format("%d:%02d", remainMin, remainSec),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "remaining",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Time progress bar
                val progress = (recordingState.durationMs.toFloat() / AudioRecorder.MAX_DURATION_MS)
                    .coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = progress,
                    color = if (remainingSec < 30) ErrorRed else AccentGreen,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(horizontal = 48.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Stop button
            RecordStopButton(onClick = onStop)

            if (recordingState.autoStopped) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recording auto-stopped at 5 minute limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen
                )
            }
        }
    }
}

/**
 * Live capture screen used when the on-device FUTO/Nemotron model is selected:
 * status/loading stays at the top, the streaming transcript fills the rest of
 * the screen, and the controls stay at the bottom.
 */
@Composable
private fun StreamingRecordingOverlay(
    streaming: StreamingCaptureUi,
    isInteractive: Boolean,
    vadSpeechDetected: Boolean,
    onStop: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onSendTranscript: () -> Unit,
    onCancelTranscript: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming_capture_dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streaming_dot_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // ---- Top: loading / recording status ----
            StreamingStatusHeader(
                streaming = streaming,
                isInteractive = isInteractive,
                vadSpeechDetected = vadSpeechDetected,
                dotAlpha = dotAlpha
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (streaming.error != null) {
                ErrorPanel(
                    message = streaming.error,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }

            if (streaming.modelLoading) {
                Text(
                    text = "The on-device model is loading. Your words will stream " +
                        "here as soon as it is ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp)
                )
            } else if (streaming.isEditing) {
                TranscriptEditor(
                    text = streaming.editableText,
                    autoSendRemainingMs = streaming.autoSendRemainingMs,
                    onTextChange = onTranscriptChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                LiveTranscript(
                    text = streaming.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Bottom: recorder / edit controls ----
            if (streaming.isEditing) {
                TranscriptControls(
                    canSend = streaming.editableText.isNotBlank(),
                    onSend = onSendTranscript,
                    onCancel = onCancelTranscript
                )
            } else if (streaming.error == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    RecordStopButton(onClick = onStop)
                }
            } else {
                OutlinedButton(
                    onClick = onCancelTranscript,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun StreamingStatusHeader(
    streaming: StreamingCaptureUi,
    isInteractive: Boolean,
    vadSpeechDetected: Boolean,
    dotAlpha: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (streaming.modelLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = AccentGreen
            )
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .alpha(dotAlpha)
                    .background(
                        if (streaming.isEditing) AccentGreen
                        else if (isInteractive && vadSpeechDetected) AccentGreen
                        else ErrorRed,
                        CircleShape
                    )
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    streaming.modelLoading -> "Loading Nemotron…"
                    streaming.isEditing -> "Review your question"
                    isInteractive && vadSpeechDetected -> "Listening — speaking…"
                    else -> "Listening — live transcript"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = when {
                    streaming.modelLoading -> "On-device speech model"
                    streaming.isEditing -> "Edit, or wait 2 s to send automatically"
                    isInteractive && !vadSpeechDetected -> "Waiting for speech…"
                    else -> "Tap stop when you are done"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        if (!streaming.modelLoading && !streaming.isEditing) {
            Text(
                text = formatDuration(streaming.durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun LiveTranscript(
    text: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text, scrollState.maxValue) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            if (text.isBlank()) {
                Text(
                    text = "Start speaking…",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextSecondary
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TranscriptEditor(
    text: String,
    autoSendRemainingMs: Long,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text("Your question…") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                cursorColor = AccentGreen,
                focusedLabelColor = AccentGreen
            ),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Character counter
            Text(
                text = "${text.length} characters",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                text = when {
                    text.isBlank() -> "Nothing to send"
                    autoSendRemainingMs > 0 -> String.format(
                        Locale.US,
                        "Auto-send in %.1fs",
                        autoSendRemainingMs / 1000f
                    )
                    else -> "Sending…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (text.isBlank()) TextSecondary else AccentGreen
            )
        }
    }
}

@Composable
private fun TranscriptControls(
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Discard")
        }
        Button(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Text("Send now")
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Live transcription is unavailable",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
