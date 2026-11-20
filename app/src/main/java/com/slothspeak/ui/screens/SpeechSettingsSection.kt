package com.slothspeak.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.SttProviderUiState
import com.slothspeak.data.TtsProviderUiState
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.TextSecondary

// ============================================================
// Speech-to-Text
// ============================================================

@Composable
fun SttSettingsSection(
    providers: List<SttProviderUiState>,
    onSetProvider: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onSetModel: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SttProviderUiState?>(null) }

    val active = providers.firstOrNull { it.active }
    val configuredOthers = providers.filter { it.configured && !it.active }

    SectionHeader("Speech-to-Text")
    Text(
        text = "Transcribes your voice. Only the active provider is used.",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    active?.let { provider ->
        SpeechActiveCard(
            title = provider.displayName,
            subtitle = provider.model.ifBlank { "No model configured" },
            detail = provider.baseUrl,
            status = provider.status,
            statusOk = provider.configured,
            onEdit = {
                editing = provider
                dialogOpen = true
            }
        )
    }

    configuredOthers.forEach { provider ->
        SpeechConfiguredRow(
            title = provider.displayName,
            subtitle = provider.model,
            onUse = { onSetProvider(provider.id) }
        )
    }

    AddProviderButton {
        editing = null
        dialogOpen = true
    }

    if (dialogOpen) {
        SttProviderDialog(
            editing = editing,
            providers = providers,
            onDismiss = {
                dialogOpen = false
                editing = null
            },
            onSave = { id, key, model, baseUrl ->
                onSetProvider(id)
                if (id == ApiKeyManager.STT_PROVIDER_CUSTOM) onSetBaseUrl(baseUrl)
                if (id != ApiKeyManager.STT_PROVIDER_OPENAI) onSetModel(model)
                if (!key.isNullOrBlank()) onSaveKey(key)
                dialogOpen = false
                editing = null
            },
            onRemoveKey = {
                onDeleteKey()
                dialogOpen = false
                editing = null
            }
        )
    }
}

// ============================================================
// Text-to-Speech
// ============================================================

@Composable
fun TtsSettingsSection(
    providers: List<TtsProviderUiState>,
    onSetProvider: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSetVoice: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSaveCustomKey: (String) -> Unit,
    onDeleteCustomKey: () -> Unit
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TtsProviderUiState?>(null) }

    val active = providers.firstOrNull { it.active }
    val configuredOthers = providers.filter { it.configured && !it.active && !it.onDevice }

    SectionHeader("Text-to-Speech")
    Text(
        text = "Synthesizes answers. Only the active provider is used.",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    active?.let { provider ->
        val subtitle = when {
            provider.onDevice -> "Android system TTS"
            provider.voice.isNotBlank() -> "${provider.model} · ${provider.voice}"
            else -> provider.model.ifBlank { "No model configured" }
        }
        SpeechActiveCard(
            title = provider.displayName,
            subtitle = subtitle,
            detail = provider.baseUrl,
            status = provider.status,
            statusOk = provider.configured,
            onEdit = {
                editing = provider
                dialogOpen = true
            }
        )
    }

    configuredOthers.forEach { provider ->
        val subtitle = if (provider.voice.isNotBlank()) {
            "${provider.model} · ${provider.voice}"
        } else {
            provider.model
        }
        SpeechConfiguredRow(
            title = provider.displayName,
            subtitle = subtitle,
            onUse = { onSetProvider(provider.id) }
        )
    }

    AddProviderButton {
        editing = null
        dialogOpen = true
    }

    if (dialogOpen) {
        TtsProviderDialog(
            editing = editing,
            providers = providers,
            onDismiss = {
                dialogOpen = false
                editing = null
            },
            onSave = { id, key, model, voice, baseUrl ->
                onSetProvider(id)
                if (id == ApiKeyManager.TTS_PROVIDER_CUSTOM) onSetBaseUrl(baseUrl)
                if (id == ApiKeyManager.TTS_PROVIDER_TOGETHER ||
                    id == ApiKeyManager.TTS_PROVIDER_CUSTOM
                ) {
                    onSetModel(model)
                    onSetVoice(voice)
                }
                if (!key.isNullOrBlank()) onSaveCustomKey(key)
                dialogOpen = false
                editing = null
            },
            onRemoveCustomKey = {
                onDeleteCustomKey()
                dialogOpen = false
                editing = null
            }
        )
    }
}

// ============================================================
// Shared compact rows
// ============================================================

@Composable
private fun SpeechActiveCard(
    title: String,
    subtitle: String,
    detail: String?,
    status: String,
    statusOk: Boolean,
    onEdit: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    SpeechStatusPill("Active")
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusOk) AccentGreen else ErrorRed,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit $title",
                    tint = AccentGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeechConfiguredRow(
    title: String,
    subtitle: String,
    onUse: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1
                )
                Text(
                    text = "Configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGreen
                )
            }
            TextButton(onClick = onUse) {
                Text("Use", color = AccentGreen)
            }
        }
    }
}

@Composable
private fun AddProviderButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add provider")
    }
}

@Composable
private fun SpeechStatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentGreen.copy(alpha = 0.18f)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AccentGreen,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================
// STT dialog
// ============================================================

private data class SttFormSeed(val model: String, val baseUrl: String)

@Composable
private fun SttProviderDialog(
    editing: SttProviderUiState?,
    providers: List<SttProviderUiState>,
    onDismiss: () -> Unit,
    onSave: (id: String, key: String?, model: String, baseUrl: String) -> Unit,
    onRemoveKey: () -> Unit
) {
    var selectedId by remember { mutableStateOf(editing?.id) }
    val selected = providers.firstOrNull { it.id == selectedId }
    val seed = remember(selectedId, providers) {
        selectedId?.let { id ->
            SttFormSeed(
                model = selected?.model ?: sttDefaultModel(id),
                baseUrl = selected?.baseUrl ?: sttDefaultBaseUrl(id)
            )
        }
    }
    var apiKey by remember(seed) { mutableStateOf("") }
    var model by remember(seed) { mutableStateOf(seed?.model.orEmpty()) }
    var baseUrl by remember(seed) { mutableStateOf(seed?.baseUrl.orEmpty()) }
    var showKey by remember(seed) { mutableStateOf(false) }

    val id = selectedId
    if (id == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add provider") },
            text = {
                Column {
                    providers.chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { provider ->
                                OutlinedButton(
                                    onClick = { selectedId = provider.id },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                                ) {
                                    Text(provider.displayName, maxLines = 1)
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
        return
    }

    val isOpenAi = id == ApiKeyManager.STT_PROVIDER_OPENAI
    val isCustom = id == ApiKeyManager.STT_PROVIDER_CUSTOM
    val requiresKey = id == ApiKeyManager.STT_PROVIDER_TOGETHER && selected?.configured != true
    val canSave = when {
        isOpenAi -> true
        isCustom -> baseUrl.isNotBlank() && model.isNotBlank()
        else -> model.isNotBlank() && (!requiresKey || apiKey.isNotBlank())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Edit ${selected?.displayName}" else "Add ${selected?.displayName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (isOpenAi) {
                    Text(
                        "Uses the OpenAI API key from the LLM section.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    if (selected?.configured != true) {
                        Text(
                            "No OpenAI key configured yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    if (isCustom) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL (include /v1/)") },
                            placeholder = { Text("http://192.168.1.100:8000/v1/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = speechFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model ID") },
                        placeholder = {
                            Text(
                                if (isCustom) "openai/whisper-large-v3"
                                else ApiKeyManager.DEFAULT_STT_MODEL_TOGETHER
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = speechFieldColors()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (isCustom) "API key (optional)" else "API key") },
                        placeholder = {
                            Text(
                                if (selected?.hasKey == true) "Configured — leave blank to keep"
                                else if (isCustom) "Leave blank for no auth"
                                else "tgp-…"
                            )
                        },
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = speechFieldColors()
                    )

                    if (selected?.hasKey == true) {
                        TextButton(onClick = onRemoveKey) {
                            Text("Remove key", color = ErrorRed)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(id, apiKey.takeIf { it.isNotBlank() }, model, baseUrl) },
                enabled = canSave
            ) {
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

// ============================================================
// TTS dialog
// ============================================================

private data class TtsFormSeed(val model: String, val voice: String, val baseUrl: String)

@Composable
private fun TtsProviderDialog(
    editing: TtsProviderUiState?,
    providers: List<TtsProviderUiState>,
    onDismiss: () -> Unit,
    onSave: (id: String, key: String?, model: String, voice: String, baseUrl: String) -> Unit,
    onRemoveCustomKey: () -> Unit
) {
    var selectedId by remember { mutableStateOf(editing?.id) }
    val selected = providers.firstOrNull { it.id == selectedId }
    val seed = remember(selectedId, providers) {
        selectedId?.let { id ->
            TtsFormSeed(
                model = selected?.model ?: ttsDefaultModel(id),
                voice = selected?.voice ?: ttsDefaultVoice(id),
                baseUrl = selected?.baseUrl ?: ttsDefaultBaseUrl(id)
            )
        }
    }
    var apiKey by remember(seed) { mutableStateOf("") }
    var model by remember(seed) { mutableStateOf(seed?.model.orEmpty()) }
    var voice by remember(seed) { mutableStateOf(seed?.voice.orEmpty()) }
    var baseUrl by remember(seed) { mutableStateOf(seed?.baseUrl.orEmpty()) }
    var showKey by remember(seed) { mutableStateOf(false) }

    val id = selectedId
    if (id == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add provider") },
            text = {
                Column {
                    providers.chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { provider ->
                                OutlinedButton(
                                    onClick = { selectedId = provider.id },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                                ) {
                                    Text(provider.displayName, maxLines = 1)
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
        return
    }

    val isOpenAi = id == ApiKeyManager.TTS_PROVIDER_OPENAI
    val isTogether = id == ApiKeyManager.TTS_PROVIDER_TOGETHER
    val isCustom = id == ApiKeyManager.TTS_PROVIDER_CUSTOM
    val isOnDevice = id == ApiKeyManager.TTS_PROVIDER_ONDEVICE
    val canSave = when {
        isOnDevice || isOpenAi -> true
        isTogether -> model.isNotBlank() && voice.isNotBlank()
        else -> baseUrl.isNotBlank() && model.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Edit ${selected?.displayName}" else "Add ${selected?.displayName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    isOpenAi -> {
                        Text(
                            "Uses the OpenAI API key from the LLM section.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        if (selected?.configured != true) {
                            Text(
                                "No OpenAI key configured yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    isOnDevice -> {
                        Text(
                            "Uses your device's system text-to-speech engine.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            "Free and offline. No API key required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    isTogether -> {
                        Text(
                            "Uses the Together API key from Speech-to-Text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected?.configured == true) TextSecondary else ErrorRed
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TtsModelVoiceFields(
                            model = model,
                            onModelChange = { model = it },
                            voice = voice,
                            onVoiceChange = { voice = it }
                        )
                    }

                    isCustom -> {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL (include /v1/)") },
                            placeholder = { Text("http://192.168.1.100:8000/v1/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = speechFieldColors()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TtsModelVoiceFields(
                            model = model,
                            onModelChange = { model = it },
                            voice = voice,
                            onVoiceChange = { voice = it }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API key (optional)") },
                            placeholder = {
                                Text(
                                    if (selected?.hasKey == true) "Configured — leave blank to keep"
                                    else "Leave blank for no auth"
                                )
                            },
                            visualTransformation = if (showKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        imageVector = if (showKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = if (showKey) "Hide" else "Show",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = speechFieldColors()
                        )
                        if (selected?.hasKey == true) {
                            TextButton(onClick = onRemoveCustomKey) {
                                Text("Remove key", color = ErrorRed)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        id,
                        apiKey.takeIf { it.isNotBlank() },
                        model,
                        voice,
                        baseUrl
                    )
                },
                enabled = canSave
            ) {
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
private fun TtsModelVoiceFields(
    model: String,
    onModelChange: (String) -> Unit,
    voice: String,
    onVoiceChange: (String) -> Unit
) {
    OutlinedTextField(
        value = model,
        onValueChange = onModelChange,
        label = { Text("Model ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = speechFieldColors()
    )
    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
        value = voice,
        onValueChange = onVoiceChange,
        label = { Text("Voice ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = speechFieldColors()
    )
}

// ============================================================
// Defaults + field colors
// ============================================================

private fun sttDefaultModel(id: String): String = when (id) {
    ApiKeyManager.STT_PROVIDER_OPENAI -> ApiKeyManager.DEFAULT_STT_MODEL_OPENAI
    ApiKeyManager.STT_PROVIDER_TOGETHER -> ApiKeyManager.DEFAULT_STT_MODEL_TOGETHER
    else -> ""
}

private fun sttDefaultBaseUrl(id: String): String = when (id) {
    ApiKeyManager.STT_PROVIDER_OPENAI -> ApiKeyManager.OPENAI_BASE_URL
    ApiKeyManager.STT_PROVIDER_TOGETHER -> ApiKeyManager.TOGETHER_BASE_URL
    else -> ""
}

private fun ttsDefaultModel(id: String): String = when (id) {
    ApiKeyManager.TTS_PROVIDER_OPENAI -> ApiKeyManager.DEFAULT_TTS_MODEL_OPENAI
    ApiKeyManager.TTS_PROVIDER_TOGETHER -> ApiKeyManager.DEFAULT_TTS_MODEL_TOGETHER
    else -> ""
}

private fun ttsDefaultVoice(id: String): String = when (id) {
    ApiKeyManager.TTS_PROVIDER_OPENAI -> ApiKeyManager.DEFAULT_TTS_VOICE_OPENAI
    ApiKeyManager.TTS_PROVIDER_TOGETHER -> ApiKeyManager.DEFAULT_TTS_VOICE_TOGETHER
    else -> ""
}

private fun ttsDefaultBaseUrl(id: String): String = when (id) {
    ApiKeyManager.TTS_PROVIDER_OPENAI -> ApiKeyManager.OPENAI_BASE_URL
    ApiKeyManager.TTS_PROVIDER_TOGETHER -> ApiKeyManager.TOGETHER_BASE_URL
    else -> ""
}

@Composable
private fun speechFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentGreen,
    cursorColor = AccentGreen,
    focusedLabelColor = AccentGreen
)
