package com.slothspeak.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slothspeak.data.HostAuthProfile
import com.slothspeak.data.HttpWritePolicy
import com.slothspeak.data.ToolsSettingsState
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.TextSecondary

/**
 * Settings → Tools: plugin toggles and credentials for OpenAI-compatible models.
 *
 * - Brave Web Search: enable switch + encrypted API key.
 * - HTTP Request (curl): enable switch, write-confirmation policy, always-allowed hosts and
 *   per-host auth profiles. Header values are secrets and are never rendered — only "••••••".
 */
@Composable
fun ToolsSettingsSection(
    state: ToolsSettingsState,
    onToggleBraveSearch: (Boolean) -> Unit,
    onSaveBraveApiKey: (String) -> Unit,
    onDeleteBraveApiKey: () -> Unit,
    onToggleHttpRequest: (Boolean) -> Unit,
    onSetHttpWritePolicy: (HttpWritePolicy) -> Unit,
    onRemoveAllowedWriteHost: (String) -> Unit,
    onResetAllowedWriteHosts: () -> Unit,
    onSaveHostAuthProfile: (host: String, headerName: String, headerValue: String) -> Unit,
    onDeleteHostAuthProfile: (String) -> Unit
) {
    var showBraveKeyDialog by remember { mutableStateOf(false) }
    var showAuthProfileDialog by remember { mutableStateOf(false) }

    SectionHeader("Tools")

    Text(
        text = "Enable modular plugins for OpenAI-compatible models.",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    // ---- Brave Web Search ----

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PluginToggleRow(
                title = "Brave Web Search",
                description = "Lets the model search the web for up-to-date information.",
                checked = state.braveSearchEnabled,
                onCheckedChange = onToggleBraveSearch
            )
            Text(
                text = if (state.braveApiKeyConfigured) "API key configured" else "API key required",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.braveApiKeyConfigured) AccentGreen else TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { showBraveKeyDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.braveApiKeyConfigured) "Update API key" else "Add API key")
                }
                if (state.braveApiKeyConfigured) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDeleteBraveApiKey) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Brave API key",
                            tint = ErrorRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ---- HTTP Request (curl) ----

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PluginToggleRow(
                title = "HTTP Request (curl)",
                description = "Fetch web pages and APIs. GET and HEAD only read; " +
                    "POST, PUT, PATCH and DELETE are sent only after your approval.",
                checked = state.httpRequestEnabled,
                onCheckedChange = onToggleHttpRequest
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Write confirmation",
                style = MaterialTheme.typography.titleSmall
            )
            PolicyOption(
                label = "Ask before write",
                description = "Every POST, PUT, PATCH or DELETE needs your approval.",
                selected = state.httpWritePolicy == HttpWritePolicy.ASK,
                onSelect = { onSetHttpWritePolicy(HttpWritePolicy.ASK) }
            )
            PolicyOption(
                label = "Always allow writes",
                description = "Send writes without asking. Not recommended.",
                selected = state.httpWritePolicy == HttpWritePolicy.ALWAYS_ALLOW,
                onSelect = { onSetHttpWritePolicy(HttpWritePolicy.ALWAYS_ALLOW) }
            )

            if (state.allowedWriteHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Always-allowed hosts",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onResetAllowedWriteHosts) {
                        Text("Reset all", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.allowedWriteHosts.forEach { host ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = host,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveAllowedWriteHost(host) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $host",
                                tint = ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Host Auth Profiles",
                style = MaterialTheme.typography.titleSmall
            )
            if (state.hostAuthProfiles.isEmpty()) {
                Text(
                    text = "No saved credentials. Add one to have auth headers attached automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            state.hostAuthProfiles.forEach { profile ->
                HostAuthProfileRow(profile = profile, onDelete = { onDeleteHostAuthProfile(profile.id) })
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showAuthProfileDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add auth profile")
            }
        }
    }

    if (showBraveKeyDialog) {
        BraveApiKeyDialog(
            hasKey = state.braveApiKeyConfigured,
            onDismiss = { showBraveKeyDialog = false },
            onSave = { key ->
                onSaveBraveApiKey(key)
                showBraveKeyDialog = false
            }
        )
    }

    if (showAuthProfileDialog) {
        HostAuthProfileDialog(
            onDismiss = { showAuthProfileDialog = false },
            onSave = { host, headerName, headerValue ->
                onSaveHostAuthProfile(host, headerName, headerValue)
                showAuthProfileDialog = false
            }
        )
    }
}

@Composable
private fun PluginToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentGreen,
                checkedTrackColor = AccentGreen.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f),
                uncheckedBorderColor = Color.Gray
            )
        )
    }
}

@Composable
private fun PolicyOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextSecondary
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/** `host` + `headerName`; the secret header value is only ever shown as "••••••". */
@Composable
private fun HostAuthProfileRow(profile: HostAuthProfile, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.host.ifBlank { "(no host)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${profile.headerName}: ••••••",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete auth profile for ${profile.host}",
                tint = ErrorRed,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BraveApiKeyDialog(
    hasKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasKey) "Update Brave Search API key" else "Add Brave Search API key") },
        text = {
            Column {
                Text(
                    text = "Create a free key at brave.com/search/api. " +
                        "It is stored encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (hasKey) "New API key" else "API key") },
                    placeholder = { Text("BSA…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = toolsFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(apiKey.trim()) }, enabled = apiKey.isNotBlank()) {
                Text("Save", color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ErrorRed)
            }
        }
    )
}

@Composable
private fun HostAuthProfileDialog(
    onDismiss: () -> Unit,
    onSave: (host: String, headerName: String, headerValue: String) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var headerName by remember { mutableStateOf("Authorization") }
    var headerValue by remember { mutableStateOf("") }
    val canSave = host.isNotBlank() && headerName.isNotBlank() && headerValue.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add auth profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Attached automatically to requests for this host — the model never " +
                        "sees or needs the secret. Stored encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("www.wandub.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    colors = toolsFieldColors()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = headerName,
                    onValueChange = { headerName = it },
                    label = { Text("Header name") },
                    placeholder = { Text("Authorization") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = toolsFieldColors()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = headerValue,
                    onValueChange = { headerValue = it },
                    label = { Text("Header value") },
                    placeholder = { Text("Bearer …") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = toolsFieldColors()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(host, headerName, headerValue) }, enabled = canSave) {
                Text("Save", color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ErrorRed)
            }
        }
    )
}

@Composable
private fun toolsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentGreen,
    cursorColor = AccentGreen,
    focusedLabelColor = AccentGreen
)
