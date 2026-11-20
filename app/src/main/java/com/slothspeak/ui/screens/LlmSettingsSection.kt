package com.slothspeak.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.LlmProviderType
import com.slothspeak.data.LlmProviderUiState
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Unified LLM settings: every configured provider is listed as a compact card
 * with the active one highlighted. Adding a model always goes through the same
 * provider dialog, whether it is a built-in provider or an OpenAI-compatible one.
 */
@Composable
fun LlmSettingsSection(
    providers: List<LlmProviderUiState>,
    compatProviders: List<CompatibleProviderConfig>,
    selectedModel: String,
    xSearchEnabled: Boolean,
    onActivate: (LlmProviderUiState) -> Unit,
    onSaveBuiltin: (LlmProviderType, String?, String, Boolean) -> Unit,
    onDeleteBuiltin: (LlmProviderType) -> Unit,
    onSaveCompat: (CompatibleProviderConfig, String?) -> Unit,
    onDeleteCompat: (String) -> Unit,
    onSetThinking: (LlmProviderUiState, Boolean, String) -> Unit,
    onSetXSearch: (Boolean) -> Unit,
    onFetchModels: suspend (String, String?, String?) -> Result<List<String>>
) {
    var showProviderDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<LlmProviderUiState?>(null) }
    var selectedTemplate by remember { mutableStateOf<LlmTemplate?>(null) }
    var thinkingProvider by remember { mutableStateOf<LlmProviderUiState?>(null) }
    var deletingProvider by remember { mutableStateOf<LlmProviderUiState?>(null) }

    val active = providers.firstOrNull { it.active }
    val configured = providers.filterNot { it.active }

    SectionHeader("LLM")

    Text(
        text = "The active model answers your questions. Add any provider with the same setup flow.",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    if (providers.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("No model configured yet", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Add an API key to start answering questions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }

    active?.let { provider ->
        LlmActiveProviderCard(
            provider = provider,
            onEdit = {
                editingProvider = provider
                selectedTemplate = null
                showProviderDialog = true
            },
            onDelete = { deletingProvider = provider },
            onModifyThinking = { thinkingProvider = provider }
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    configured.forEach { provider ->
        LlmConfiguredProviderRow(
            provider = provider,
            onActivate = { onActivate(provider) },
            onEdit = {
                editingProvider = provider
                selectedTemplate = null
                showProviderDialog = true
            },
            onDelete = { deletingProvider = provider }
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    Spacer(modifier = Modifier.height(4.dp))

    Button(
        onClick = {
            editingProvider = null
            selectedTemplate = null
            showProviderDialog = true
        },
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add model")
    }

    if (selectedModel == ApiKeyManager.MODEL_GROK) {
        Spacer(modifier = Modifier.height(24.dp))
        SearchSettings(
            xSearchEnabled = xSearchEnabled,
            onSetXSearch = onSetXSearch
        )
    }

    if (showProviderDialog) {
        LlmProviderDialog(
            provider = editingProvider,
            template = selectedTemplate,
            providers = providers,
            compatProviders = compatProviders,
            onPickTemplate = { selectedTemplate = it },
            onDismiss = {
                showProviderDialog = false
                editingProvider = null
                selectedTemplate = null
            },
            onSaveBuiltin = { type, key, model, activate ->
                onSaveBuiltin(type, key, model, activate)
                showProviderDialog = false
                editingProvider = null
                selectedTemplate = null
            },
            onSaveCompat = { config, key ->
                onSaveCompat(config, key)
                showProviderDialog = false
                editingProvider = null
                selectedTemplate = null
            },
            onFetchModels = onFetchModels
        )
    }

    thinkingProvider?.let { provider ->
        LlmThinkingDialog(
            provider = provider,
            onDismiss = { thinkingProvider = null },
            onApply = { enabled, level ->
                onSetThinking(provider, enabled, level)
                thinkingProvider = null
            }
        )
    }

    deletingProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { deletingProvider = null },
            title = { Text("Delete ${provider.displayName}?") },
            text = {
                Text(
                    if (provider.isCompat) {
                        "This removes the provider and its API key from the app."
                    } else {
                        "This removes the saved ${provider.displayName} API key from the app."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (provider.isCompat && provider.compatProviderId != null) {
                            onDeleteCompat(provider.compatProviderId)
                        } else {
                            onDeleteBuiltin(provider.type)
                        }
                        deletingProvider = null
                    }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProvider = null }) {
                    Text("Cancel", color = AccentGreen)
                }
            }
        )
    }
}

// ============================================================
// Provider cards
// ============================================================

@Composable
private fun LlmActiveProviderCard(
    provider: LlmProviderUiState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onModifyThinking: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill("Active")
                    }
                    Text(
                        text = friendlyModelName(provider.modelId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (!provider.baseUrl.isNullOrBlank()) {
                        Text(
                            text = provider.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "Key configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit ${provider.displayName}",
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete ${provider.displayName}",
                        tint = ErrorRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (provider.supportsThinking) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinkingChip(provider)
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onModifyThinking) {
                        Text("Modify", color = AccentGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmConfiguredProviderRow(
    provider: LlmProviderUiState,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = provider.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = friendlyModelName(provider.modelId),
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
            TextButton(onClick = onActivate) {
                Text("Use", color = AccentGreen)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit ${provider.displayName}",
                    tint = AccentGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${provider.displayName}",
                    tint = ErrorRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingChip(provider: LlmProviderUiState) {
    val label = if (provider.thinkingEnabled) {
        "Thinking · ${provider.thinkingLevel.replaceFirstChar { it.uppercase() }}"
    } else {
        "Thinking · Off"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = AccentGreen.copy(alpha = 0.14f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AccentGreen,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun StatusPill(text: String) {
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
// Search settings (part of the LLM section)
// ============================================================

@Composable
private fun SearchSettings(
    xSearchEnabled: Boolean,
    onSetXSearch: (Boolean) -> Unit
) {
    SectionHeader("Search")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Enable X search", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Search X (Twitter) posts for real-time information.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = xSearchEnabled,
                    onCheckedChange = onSetXSearch,
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
    }
}

// ============================================================
// Thinking / reasoning dialog
// ============================================================

@Composable
private fun LlmThinkingDialog(
    provider: LlmProviderUiState,
    onDismiss: () -> Unit,
    onApply: (Boolean, String) -> Unit
) {
    var enabled by remember(provider.key) { mutableStateOf(provider.thinkingEnabled) }
    var level by remember(provider.key) { mutableStateOf(provider.thinkingLevel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thinking — ${provider.displayName}") },
        text = {
            Column {
                if (provider.isCompat) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable thinking", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Send reasoning parameters to this provider",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (enabled) {
                    provider.thinkingLevels.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { level = option }
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = level == option,
                                onClick = { level = option },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentGreen,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = option.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Higher effort gives deeper reasoning but slower answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(enabled, level) }) {
                Text("Save", color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// ============================================================
// Unified add/edit provider dialog
// ============================================================

private enum class LlmTemplateId { OPENAI, GEMINI, CLAUDE, GROK, ZAI, DEEPSEEK, CUSTOM }

private data class LlmTemplate(
    val id: LlmTemplateId,
    val label: String,
    val type: LlmProviderType,
    val providerKey: String? = null,
    val displayName: String,
    val defaultModel: String,
    val baseUrl: String = "",
    val sendThinkingParams: Boolean = true,
    val maxTokens: Int? = null,
    val keyPlaceholder: String = "sk-…"
)

private val llmTemplates = listOf(
    LlmTemplate(
        id = LlmTemplateId.OPENAI,
        label = "OpenAI",
        type = LlmProviderType.OPENAI,
        providerKey = ApiKeyManager.LLM_PROVIDER_OPENAI,
        displayName = "OpenAI",
        defaultModel = ApiKeyManager.MODEL_STANDARD,
        keyPlaceholder = "sk-…"
    ),
    LlmTemplate(
        id = LlmTemplateId.GEMINI,
        label = "Gemini",
        type = LlmProviderType.GEMINI,
        providerKey = ApiKeyManager.LLM_PROVIDER_GEMINI,
        displayName = "Gemini",
        defaultModel = ApiKeyManager.MODEL_GEMINI_PRO,
        keyPlaceholder = "AIza…"
    ),
    LlmTemplate(
        id = LlmTemplateId.CLAUDE,
        label = "Claude",
        type = LlmProviderType.CLAUDE,
        providerKey = ApiKeyManager.LLM_PROVIDER_CLAUDE,
        displayName = "Claude",
        defaultModel = ApiKeyManager.MODEL_CLAUDE_OPUS,
        keyPlaceholder = "sk-ant-…"
    ),
    LlmTemplate(
        id = LlmTemplateId.GROK,
        label = "xAI Grok",
        type = LlmProviderType.GROK,
        providerKey = ApiKeyManager.LLM_PROVIDER_GROK,
        displayName = "xAI Grok",
        defaultModel = ApiKeyManager.MODEL_GROK,
        keyPlaceholder = "xai-…"
    ),
    LlmTemplate(
        id = LlmTemplateId.ZAI,
        label = "Z.ai",
        type = LlmProviderType.COMPAT,
        displayName = "Z.ai",
        defaultModel = "glm-5.3",
        baseUrl = "https://api.z.ai/api/paas/v4/"
    ),
    LlmTemplate(
        id = LlmTemplateId.DEEPSEEK,
        label = "DeepSeek",
        type = LlmProviderType.COMPAT,
        displayName = "DeepSeek",
        defaultModel = "deepseek-v4-flash",
        baseUrl = "https://api.deepseek.com/v1/",
        maxTokens = 8192
    ),
    LlmTemplate(
        id = LlmTemplateId.CUSTOM,
        label = "Custom",
        type = LlmProviderType.COMPAT,
        displayName = "Custom",
        defaultModel = "",
        baseUrl = "",
        sendThinkingParams = false
    )
)

private data class LlmFormSeed(
    val displayName: String,
    val modelId: String,
    val baseUrl: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: String,
    val sendThinkingParams: Boolean,
    val maxTokens: String
)

private fun templateFor(provider: LlmProviderUiState): LlmTemplate {
    return llmTemplates.firstOrNull { it.type == provider.type && it.type != LlmProviderType.COMPAT }
        ?: llmTemplates.first { it.id == LlmTemplateId.CUSTOM }
}

@Composable
private fun LlmProviderDialog(
    provider: LlmProviderUiState?,
    template: LlmTemplate?,
    providers: List<LlmProviderUiState>,
    compatProviders: List<CompatibleProviderConfig>,
    onPickTemplate: (LlmTemplate) -> Unit,
    onDismiss: () -> Unit,
    onSaveBuiltin: (LlmProviderType, String?, String, Boolean) -> Unit,
    onSaveCompat: (CompatibleProviderConfig, String?) -> Unit,
    onFetchModels: suspend (String, String?, String?) -> Result<List<String>>
) {
    if (provider == null && template == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add model") },
            text = {
                Column {
                    Text(
                        text = "Pick a provider. Built-in and OpenAI-compatible providers use the same setup flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    llmTemplates.chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { item ->
                                OutlinedButton(
                                    onClick = { onPickTemplate(item) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                                ) {
                                    Text(item.label, maxLines = 1)
                                }
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
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

    val resolvedTemplate = template ?: templateFor(provider!!)
    val compatConfig = provider?.compatProviderId?.let { id ->
        compatProviders.firstOrNull { it.id == id }
    }
    // Adding a built-in that is already configured edits the existing entry.
    val builtinExisting = if (provider == null && resolvedTemplate.type != LlmProviderType.COMPAT) {
        providers.firstOrNull { it.type == resolvedTemplate.type }
    } else {
        null
    }
    val effectiveProvider = provider ?: builtinExisting

    val seed = remember(effectiveProvider?.key, resolvedTemplate.id) {
        LlmFormSeed(
            displayName = effectiveProvider?.displayName ?: resolvedTemplate.displayName,
            modelId = effectiveProvider?.modelId ?: resolvedTemplate.defaultModel,
            baseUrl = compatConfig?.baseUrl
                ?: effectiveProvider?.baseUrl
                ?: resolvedTemplate.baseUrl,
            thinkingEnabled = compatConfig?.thinkingEnabled ?: true,
            reasoningEffort = compatConfig?.reasoningEffort
                ?: effectiveProvider?.thinkingLevel
                ?: "high",
            sendThinkingParams = compatConfig?.sendThinkingParams
                ?: resolvedTemplate.sendThinkingParams,
            maxTokens = compatConfig?.maxTokens?.toString()
                ?: resolvedTemplate.maxTokens?.toString()
                ?: ""
        )
    }

    var displayName by remember(seed) { mutableStateOf(seed.displayName) }
    var modelId by remember(seed) { mutableStateOf(seed.modelId) }
    var baseUrl by remember(seed) { mutableStateOf(seed.baseUrl) }
    var apiKey by remember(seed) { mutableStateOf("") }
    var thinkingEnabled by remember(seed) { mutableStateOf(seed.thinkingEnabled) }
    var reasoningEffort by remember(seed) { mutableStateOf(seed.reasoningEffort) }
    var sendThinkingParams by remember(seed) { mutableStateOf(seed.sendThinkingParams) }
    var maxTokens by remember(seed) { mutableStateOf(seed.maxTokens) }
    val scope = rememberCoroutineScope()
    var fetchedModels by remember(seed) { mutableStateOf<List<String>>(emptyList()) }
    var showModelMenu by remember(seed) { mutableStateOf(false) }
    var fetchingModels by remember(seed) { mutableStateOf(false) }
    var fetchError by remember(seed) { mutableStateOf<String?>(null) }

    val isCompat = resolvedTemplate.type == LlmProviderType.COMPAT
    val isBuiltinAdd = !isCompat && effectiveProvider == null
    val requiresKey = isBuiltinAdd
    val canSave = displayName.isNotBlank() &&
        modelId.isNotBlank() &&
        (!isCompat || baseUrl.isNotBlank()) &&
        (!requiresKey || apiKey.isNotBlank())

    val title = when {
        provider != null -> "Edit ${provider.displayName}"
        builtinExisting != null -> "Edit ${builtinExisting.displayName}"
        else -> "Add ${resolvedTemplate.label}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (isCompat) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = llmFieldColors()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (isCompat) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL (include /v1/)") },
                        placeholder = { Text("https://api.example.com/v1/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = llmFieldColors()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = modelId,
                            onValueChange = { modelId = it },
                            label = { Text("Model ID") },
                            placeholder = { Text("e.g. qwen3.8-27b") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = llmFieldColors()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    fetchingModels = true
                                    fetchError = null
                                    onFetchModels(
                                        baseUrl,
                                        provider?.compatProviderId,
                                        apiKey.takeIf { it.isNotBlank() }
                                    ).onSuccess { models ->
                                        fetchedModels = models
                                        if (models.isEmpty()) {
                                            fetchError = "No models returned"
                                        } else {
                                            showModelMenu = true
                                        }
                                    }.onFailure { error ->
                                        fetchError = error.message ?: "Could not fetch models"
                                    }
                                    fetchingModels = false
                                }
                            },
                            enabled = baseUrl.isNotBlank() && !fetchingModels,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                        ) {
                            if (fetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentGreen
                                )
                            } else {
                                Text("Fetch")
                            }
                        }
                    }
                    if (fetchedModels.isNotEmpty()) {
                        Box {
                            AssistChip(
                                onClick = { showModelMenu = true },
                                label = { Text("${fetchedModels.size} models found") },
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false }
                            ) {
                                fetchedModels.forEach { fetchedModel ->
                                    DropdownMenuItem(
                                        text = { Text(fetchedModel) },
                                        onClick = {
                                            modelId = fetchedModel
                                            showModelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    fetchError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    val models = ApiKeyManager.modelsForBuiltin(resolvedTemplate.providerKey.orEmpty())
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    models.forEach { model ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { modelId = model }
                        ) {
                            RadioButton(
                                selected = modelId == model,
                                onClick = { modelId = model },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentGreen,
                                    unselectedColor = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = friendlyModelName(model),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(
                            if (effectiveProvider != null) "Replace API key (optional)"
                            else "API key"
                        )
                    },
                    placeholder = {
                        Text(
                            if (effectiveProvider != null) "Configured — leave blank to keep"
                            else resolvedTemplate.keyPlaceholder
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = llmFieldColors()
                )

                Text(
                    text = "Keys are stored encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (isCompat) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable thinking", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Send reasoning parameters",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { thinkingEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f)
                            )
                        )
                    }

                    if (thinkingEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Reasoning effort",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("low", "high", "max").forEach { effort ->
                                Button(
                                    onClick = { reasoningEffort = effort },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (reasoningEffort == effort) AccentGreen
                                        else AccentGreen.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(effort.replaceFirstChar { it.uppercase() }, maxLines = 1)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Send thinking parameters", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (sendThinkingParams) "ON for Z.ai/DeepSeek"
                                else "OFF for Custom (vLLM)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = sendThinkingParams,
                            onCheckedChange = { sendThinkingParams = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                        label = { Text("Max tokens (optional)") },
                        placeholder = { Text("Blank = provider default") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = llmFieldColors()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isCompat) {
                        val id = provider?.compatProviderId
                            ?: "${resolvedTemplate.id.name.lowercase()}-${System.currentTimeMillis()}"
                        val config = CompatibleProviderConfig(
                            id = id,
                            displayName = displayName.trim(),
                            baseUrl = baseUrl.trim(),
                            modelId = modelId.trim(),
                            thinkingEnabled = thinkingEnabled,
                            reasoningEffort = reasoningEffort,
                            sendThinkingParams = sendThinkingParams,
                            maxTokens = maxTokens.toIntOrNull()
                        )
                        onSaveCompat(config, apiKey.takeIf { it.isNotBlank() })
                    } else {
                        onSaveBuiltin(
                            resolvedTemplate.type,
                            apiKey.takeIf { it.isNotBlank() },
                            modelId,
                            provider == null
                        )
                    }
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
private fun llmFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentGreen,
    cursorColor = AccentGreen,
    focusedLabelColor = AccentGreen
)

private fun friendlyModelName(modelId: String): String = when (modelId) {
    ApiKeyManager.MODEL_STANDARD -> "GPT-5.5"
    ApiKeyManager.MODEL_PRO -> "GPT-5.5 Pro"
    ApiKeyManager.MODEL_DEEP_RESEARCH -> "o3 Deep Research"
    ApiKeyManager.MODEL_GEMINI_PRO -> "Gemini 3.1 Pro"
    ApiKeyManager.MODEL_GEMINI_PRO_35 -> "Gemini 3.5 Pro"
    ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH -> "Gemini Deep Research"
    ApiKeyManager.MODEL_CLAUDE_OPUS -> "Claude Opus 4.8"
    ApiKeyManager.MODEL_GROK -> "Grok 4.3"
    else -> modelId
}
