package com.slothspeak.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationRequest
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.PureBlack
import com.slothspeak.ui.theme.PureWhite
import com.slothspeak.ui.theme.SurfaceVariant
import com.slothspeak.ui.theme.TextSecondary

private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/**
 * Asks the user to approve a tool side effect before it leaves the device — currently the
 * `http_request` plugin for POST/PUT/PATCH/DELETE. Shows exactly what will be sent (method,
 * full URL, redacted headers, body preview) and the four decisions, ordered from narrowest to
 * broadest scope so each is unmistakable.
 *
 * Fails closed: dismissing the dialog (back gesture, tap outside) answers [ToolConfirmationDecision.DENY].
 */
@Composable
fun ToolConfirmationDialog(
    request: ToolConfirmationRequest,
    onDecision: (ToolConfirmationDecision) -> Unit
) {
    val monospace = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    AlertDialog(
        onDismissRequest = { onDecision(ToolConfirmationDecision.DENY) },
        title = { Text("Allow HTTP ${request.method}?") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Method pill + full target URL
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MethodBadge(method = request.method)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = request.url,
                        style = monospace,
                        color = PureWhite,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "The assistant wants to send this request from your device. " +
                        "Saved credentials are shown as ••••••.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Headers — secrets are already redacted by the plugin
                if (request.headers.isNotEmpty()) {
                    SectionLabel("Headers")
                    request.headers.forEach { (name, value) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.width(120.dp)
                            )
                            Text(
                                text = value,
                                style = monospace,
                                color = PureWhite,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Body preview
                if (!request.bodyPreview.isNullOrBlank()) {
                    SectionLabel("Body")
                    Surface(
                        color = SurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = request.bodyPreview,
                            style = monospace,
                            color = PureWhite,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Decisions, narrowest scope first; broad/destructive ones are visually distinct.
                Button(
                    onClick = { onDecision(ToolConfirmationDecision.ALLOW_ONCE) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PureBlack)
                ) {
                    Text("Allow once")
                }
                OutlinedButton(
                    onClick = { onDecision(ToolConfirmationDecision.ALWAYS_ALLOW_HOST) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AccentGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                ) {
                    Text(
                        text = "Always allow ${request.host}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = { onDecision(ToolConfirmationDecision.ALWAYS_ALLOW_ALL) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text(
                        text = "Always allow all HTTP writes",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = { onDecision(ToolConfirmationDecision.DENY) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = PureWhite)
                ) {
                    Text("Deny", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** Compact coloured pill for the HTTP method; write methods are tinted like a warning. */
@Composable
private fun MethodBadge(method: String) {
    val tint = if (method in WRITE_METHODS) ErrorRed else AccentGreen
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.18f)
    ) {
        Text(
            text = method,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = AccentGreen
    )
}
