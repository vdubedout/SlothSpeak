package com.slothspeak.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slothspeak.tools.ToolTraceEntry
import com.slothspeak.tools.formatToolArguments
import com.slothspeak.tools.formatToolDisplayName
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.SurfaceVariant
import com.slothspeak.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun QAPairCard(
    questionText: String,
    answerText: String?,
    timestamp: Long,
    audioFiles: List<File>?,
    onReplayClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    model: String? = null,
    reasoningEffort: String? = null,
    llmResponseSeconds: Int? = null,
    answerTextRich: String? = null,
    reasoningText: String? = null,
    /** Whether TTS is available so silent text replies can be read aloud. */
    canSpeak: Boolean = false,
    /** Called when the speaker button is tapped (synthesize/replay + play). */
    onSpeakClick: (() -> Unit)? = null,
    /** Tools the model used for this answer; rendered as a collapsible "Tools used (N)" section. */
    toolTrace: List<ToolTraceEntry> = emptyList()
) {
    val context = LocalContext.current
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val hasMetadata = model != null || llmResponseSeconds != null
    val displayAnswerText = answerTextRich ?: answerText
    var showReasoning by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val hasReasoning = !reasoningText.isNullOrBlank()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Question
            Text(
                text = questionText,
                style = MaterialTheme.typography.bodyLarge,
                color = AccentGreen
            )

            if (displayAnswerText != null) {
                Spacer(modifier = Modifier.height(12.dp))

                // Answer
                Text(
                    text = displayAnswerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Reasoning toggle
            if (hasReasoning) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showReasoning = !showReasoning }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showReasoning) "Hide thinking" else "View thinking",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showReasoning) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showReasoning) "Hide" else "Show",
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = showReasoning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = reasoningText ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Tool trace toggle (same styling as "View thinking")
            if (toolTrace.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showTools = !showTools }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showTools) "Hide tools" else "Tools used (${toolTrace.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = if (showTools) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (showTools) "Hide tool trace" else "Show tool trace",
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(visible = showTools) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        toolTrace.forEachIndexed { index, entry ->
                            ToolTraceEntryView(index = index, entry = entry)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (hasMetadata) {
                // Metadata section
                Column {
                    // Line 1: Date/time with ordinal suffix
                    Text(
                        text = formatTimestampWithOrdinal(timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Line 2: Model + reasoning effort
                    if (model != null) {
                        Text(
                            text = formatModelDisplay(model, reasoningEffort),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    // Line 3: Response time
                    if (llmResponseSeconds != null) {
                        Text(
                            text = "$llmResponseSeconds seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom row: copy + speaker/replay buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    if (displayAnswerText != null) {
                        IconButton(
                            onClick = { copyQAToClipboard(context, questionText, displayAnswerText) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = TextSecondary
                            )
                        }
                    }
                    if (shouldShowSpeaker(displayAnswerText, audioFiles, canSpeak, onSpeakClick, onReplayClick)) {
                        IconButton(
                            onClick = { (onSpeakClick ?: onReplayClick)?.invoke() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read aloud",
                                tint = AccentGreen
                            )
                        }
                    }
                    if (audioFiles != null && onReplayClick != null) {
                        IconButton(
                            onClick = onReplayClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Replay",
                                tint = AccentGreen
                            )
                        }
                    }
                }
            } else {
                // Fallback for old records without metadata
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFormat.format(Date(timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (displayAnswerText != null) {
                        IconButton(
                            onClick = { copyQAToClipboard(context, questionText, displayAnswerText) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = TextSecondary
                            )
                        }
                    }

                    if (shouldShowSpeaker(displayAnswerText, audioFiles, canSpeak, onSpeakClick, onReplayClick)) {
                        IconButton(
                            onClick = { (onSpeakClick ?: onReplayClick)?.invoke() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read aloud",
                                tint = AccentGreen
                            )
                        }
                    }

                    if (audioFiles != null && onReplayClick != null) {
                        IconButton(
                            onClick = onReplayClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Replay",
                                tint = AccentGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One invocation in the tool trace: name, OK/Error badge, duration, arguments and result preview. */
@Composable
private fun ToolTraceEntryView(index: Int, entry: ToolTraceEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}. ${formatToolDisplayName(entry.name)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = (if (entry.isError) ErrorRed else AccentGreen).copy(alpha = 0.18f)
            ) {
                Text(
                    text = if (entry.isError) "Error" else "OK",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isError) ErrorRed else AccentGreen,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${entry.durationMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Text(
            text = formatToolArguments(entry.name, entry.argumentsJson),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.resultPreview.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = SurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = entry.resultPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/**
 * Speaker button visibility: the card has an answer plus a way to voice it
 * (audio already exists, or TTS is configured), and a click handler is wired.
 */private fun shouldShowSpeaker(
    displayAnswerText: String?,
    audioFiles: List<File>?,
    canSpeak: Boolean,
    onSpeakClick: (() -> Unit)?,
    onReplayClick: (() -> Unit)?
): Boolean {
    if (displayAnswerText == null) return false
    if (onSpeakClick == null && onReplayClick == null) return false
    return audioFiles != null || canSpeak
}

private fun formatTimestampWithOrdinal(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val suffix = getDayOrdinalSuffix(day)
    val dateFormat = SimpleDateFormat("h:mm a MMM d", Locale.getDefault())
    return "${dateFormat.format(Date(timestamp))}$suffix"
}

private fun formatModelDisplay(model: String, reasoningEffort: String?): String {
    val displayName = when (model) {
        "gpt-5.5-pro" -> "GPT 5.5 Pro"
        "gpt-5.5" -> "GPT 5.5"
        "gemini-3.1-pro-preview" -> "Gemini 3.1 Pro"
        "gemini-3.5-pro-preview" -> "Gemini 3.5 Pro"
        "claude-opus-4-8" -> "Claude Opus 4.8"
        "grok-4.3" -> "Grok 4.3"
        "o3-deep-research" -> "Deep Research"
        "deep-research-max-preview-04-2026" -> "Gemini Deep Research"
        else -> model.replace("gpt-", "GPT ").replace("-", " ")
    }
    return if (reasoningEffort != null) "$displayName $reasoningEffort" else displayName
}

private fun copyQAToClipboard(context: Context, question: String, answer: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = "Q: $question\n\nA: $answer"
    clipboard.setPrimaryClip(ClipData.newPlainText("SlothSpeak Q&A", text))
}

private fun getDayOrdinalSuffix(day: Int): String {
    return when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}