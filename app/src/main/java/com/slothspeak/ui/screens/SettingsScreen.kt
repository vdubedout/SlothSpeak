package com.slothspeak.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.slothspeak.config.SystemPrompts
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.CompatibleProviderManager
import com.slothspeak.ui.theme.ErrorRed
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.TextSecondary
import com.slothspeak.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var apiKeyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var geminiKeyInput by remember { mutableStateOf("") }
    var showGeminiKey by remember { mutableStateOf(false) }
    var claudeKeyInput by remember { mutableStateOf("") }
    var showClaudeKey by remember { mutableStateOf(false) }
    var grokKeyInput by remember { mutableStateOf("") }
    var showGrokKey by remember { mutableStateOf(false) }
    var apiKeysExpanded by remember { mutableStateOf(false) }
    var showApiKeyDisclaimer by remember { mutableStateOf(false) }
    var systemPromptsExpanded by remember { mutableStateOf(false) }
    var readingStyleExpanded by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    // Compat provider manager (local instance for key checks in UI)
    val context = LocalContext.current
    val compatibleProviderManager = remember { CompatibleProviderManager(context) }

    // Compat provider state
    var showCompatAddDialog by remember { mutableStateOf(false) }
    var editingCompatProvider by remember { mutableStateOf<CompatibleProviderConfig?>(null) }
    var compatEditDisplayName by remember { mutableStateOf("") }
    var compatEditBaseUrl by remember { mutableStateOf("") }
    var compatEditModelId by remember { mutableStateOf("") }
    var compatEditKey by remember { mutableStateOf("") }
    var compatEditThinkingEnabled by remember { mutableStateOf(true) }
    var compatEditReasoningEffort by remember { mutableStateOf("high") }
    var compatEditSendThinkingParams by remember { mutableStateOf(true) }
    var compatEditMaxTokens by remember { mutableStateOf("") }
    var compatSelectedTemplate by remember { mutableStateOf<String?>(null) }
    var compatShowDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AccentGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // API Keys Section (collapsible)
            val configuredKeyCount = listOf(
                state.hasApiKey, state.hasGeminiKey, state.hasClaudeKey, state.hasGrokKey
            ).count { it }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!apiKeysExpanded) {
                            showApiKeyDisclaimer = true
                        } else {
                            apiKeysExpanded = false
                        }
                    }
                    .padding(bottom = 8.dp)
            ) {
                Column {
                    Text(
                        text = "API Keys",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentGreen
                    )
                    if (!apiKeysExpanded) {
                        Text(
                            text = "$configuredKeyCount of 4 keys configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (apiKeysExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (apiKeysExpanded) "Collapse" else "Expand",
                    tint = AccentGreen
                )
            }

            AnimatedVisibility(visible = apiKeysExpanded) {
                Column {
                    // OpenAI API Key Section
                    SectionHeader("OpenAI API Key")

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Status
                            Text(
                                text = if (state.hasApiKey) "API key is saved" else "No API key set",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.hasApiKey) AccentGreen else TextSecondary
                            )

                            if (state.keySaved) {
                                Text(
                                    text = "Key saved successfully!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentGreen
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("OpenAI API Key") },
                                placeholder = { Text("sk-...") },
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
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row {
                                Button(
                                    onClick = {
                                        viewModel.saveApiKey(apiKeyInput)
                                        apiKeyInput = ""
                                    },
                                    enabled = apiKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Text("Save")
                                }

                                if (state.hasApiKey) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDeleteKeyDialog() },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = ErrorRed
                                        )
                                    ) {
                                        Text("Delete Key")
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Used for text-to-speech and OpenAI models. Voice transcription uses the Speech-to-Text provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    SectionHeader("Gemini API Key")

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (state.hasGeminiKey) "API key is saved" else "No API key set",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.hasGeminiKey) AccentGreen else TextSecondary
                            )

                            if (state.geminiKeySaved) {
                                Text(
                                    text = "Key saved successfully!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentGreen
                                )
                            }

                            Text(
                                text = "Required only if using Gemini model. STT and TTS still use OpenAI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = geminiKeyInput,
                                onValueChange = { geminiKeyInput = it },
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AIza...") },
                                visualTransformation = if (showGeminiKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                        Icon(
                                            imageVector = if (showGeminiKey) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (showGeminiKey) "Hide" else "Show",
                                            tint = TextSecondary
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row {
                                Button(
                                    onClick = {
                                        viewModel.saveGeminiKey(geminiKeyInput)
                                        geminiKeyInput = ""
                                    },
                                    enabled = geminiKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Text("Save")
                                }

                                if (state.hasGeminiKey) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDeleteGeminiKeyDialog() },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = ErrorRed
                                        )
                                    ) {
                                        Text("Delete Key")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Claude API Key Section
                    SectionHeader("Claude API Key")

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (state.hasClaudeKey) "API key is saved" else "No API key set",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.hasClaudeKey) AccentGreen else TextSecondary
                            )

                            if (state.claudeKeySaved) {
                                Text(
                                    text = "Key saved successfully!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentGreen
                                )
                            }

                            Text(
                                text = "Required only if using Claude model. STT and TTS still use OpenAI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = claudeKeyInput,
                                onValueChange = { claudeKeyInput = it },
                                label = { Text("Claude API Key") },
                                placeholder = { Text("sk-ant-...") },
                                visualTransformation = if (showClaudeKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showClaudeKey = !showClaudeKey }) {
                                        Icon(
                                            imageVector = if (showClaudeKey) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (showClaudeKey) "Hide" else "Show",
                                            tint = TextSecondary
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row {
                                Button(
                                    onClick = {
                                        viewModel.saveClaudeKey(claudeKeyInput)
                                        claudeKeyInput = ""
                                    },
                                    enabled = claudeKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Text("Save")
                                }

                                if (state.hasClaudeKey) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDeleteClaudeKeyDialog() },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = ErrorRed
                                        )
                                    ) {
                                        Text("Delete Key")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // xAI API Key Section
                    SectionHeader("xAI API Key")

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (state.hasGrokKey) "API key is saved" else "No API key set",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.hasGrokKey) AccentGreen else TextSecondary
                            )

                            if (state.grokKeySaved) {
                                Text(
                                    text = "Key saved successfully!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AccentGreen
                                )
                            }

                            Text(
                                text = "Required only if using Grok model. STT and TTS still use OpenAI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = grokKeyInput,
                                onValueChange = { grokKeyInput = it },
                                label = { Text("xAI API Key") },
                                placeholder = { Text("xai-...") },
                                visualTransformation = if (showGrokKey) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showGrokKey = !showGrokKey }) {
                                        Icon(
                                            imageVector = if (showGrokKey) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                            contentDescription = if (showGrokKey) "Hide" else "Show",
                                            tint = TextSecondary
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row {
                                Button(
                                    onClick = {
                                        viewModel.saveGrokKey(grokKeyInput)
                                        grokKeyInput = ""
                                    },
                                    enabled = grokKeyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Text("Save")
                                }

                                if (state.hasGrokKey) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDeleteGrokKeyDialog() },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = ErrorRed
                                        )
                                    ) {
                                        Text("Delete Key")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))


            // Speech-to-Text Section
            SectionHeader("Speech-to-Text")

            Text(
                text = "Choose which provider handles voice transcription.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SttSection(
                sttProvider = state.sttProvider,
                hasSttApiKey = state.hasSttApiKey,
                sttModel = state.sttModel,
                sttBaseUrl = state.sttBaseUrl,
                sttKeySaved = state.sttKeySaved,
                onSetProvider = { viewModel.setSttProvider(it) },
                onSaveApiKey = { viewModel.saveSttApiKey(it) },
                onDeleteApiKey = { viewModel.deleteSttApiKey() },
                onSetModel = { viewModel.setSttModel(it) },
                onSetBaseUrl = { viewModel.setSttBaseUrl(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Text-to-Speech Section
            SectionHeader("Text-to-Speech")

            Text(
                text = "Choose which provider handles voice synthesis.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            TtsSection(
                ttsProvider = state.ttsProvider,
                ttsModel = state.ttsModel,
                ttsVoice = state.ttsVoice,
                ttsBaseUrl = state.ttsBaseUrl,
                hasTtsCustomApiKey = state.hasTtsCustomApiKey,
                ttsKeySaved = state.ttsKeySaved,
                onSetProvider = { viewModel.setTtsProvider(it) },
                onSetModel = { viewModel.setTtsModel(it) },
                onSetVoice = { viewModel.setTtsVoice(it) },
                onSetBaseUrl = { viewModel.setTtsBaseUrl(it) },
                onSaveCustomApiKey = { viewModel.saveTtsCustomApiKey(it) },
                onDeleteCustomApiKey = { viewModel.deleteTtsCustomApiKey() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Gemini API Key Section
            // OpenAI-Compatible Providers Section
            SectionHeader("OpenAI-Compatible Providers")

            Text(
                text = "Connect to Z.ai, DeepSeek, self-hosted Qwen, or any OpenAI-compatible API.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Add Provider Button
            Button(
                onClick = { showCompatAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Provider")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Provider list
            CompatProvidersList(
                providers = state.compatProviders,
                onProviderEdit = { provider ->
                    editingCompatProvider = provider
                    compatEditDisplayName = provider.displayName
                    compatEditBaseUrl = provider.baseUrl
                    compatEditModelId = provider.modelId
                    compatEditKey = compatibleProviderManager.getProviderKey(provider.id) ?: ""
                    compatEditThinkingEnabled = provider.thinkingEnabled
                    compatEditReasoningEffort = provider.reasoningEffort
                    compatEditSendThinkingParams = provider.sendThinkingParams
                    compatEditMaxTokens = provider.maxTokens?.toString() ?: ""
                    compatSelectedTemplate = null
                },
                onProviderDelete = { id -> compatShowDeleteConfirm = id }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Model Selection
            SectionHeader("Model")

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // OpenAI
                    ProviderHeader("OpenAI")

                    val compatIsNull = state.selectedCompatProviderId == null

                    ModelOption(
                        title = "GPT-5.5",
                        description = "Excellent quality, fast responses, lower cost.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_STANDARD,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_STANDARD) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ModelOption(
                        title = "GPT-5.5 Pro",
                        description = "Maximum reasoning, highest quality answers. Slower and more expensive.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_PRO,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_PRO) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ModelOption(
                        title = "o3 Deep Research",
                        description = "Multi-minute deep web research using o3.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_DEEP_RESEARCH,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_DEEP_RESEARCH) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Google
                    ProviderHeader("Google")

                    ModelOption(
                        title = "Gemini 3.1 Pro",
                        description = "Google's most intelligent model. Requires separate Gemini API key.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_GEMINI_PRO,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_GEMINI_PRO) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ModelOption(
                        title = "Gemini 3.5 Pro",
                        description = "Google's latest flagship model. Requires separate Gemini API key.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_GEMINI_PRO_35,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_GEMINI_PRO_35) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ModelOption(
                        title = "Gemini Deep Research",
                        description = "Multi-minute deep web research. Uses Gemini API key.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Anthropic
                    ProviderHeader("Anthropic")

                    ModelOption(
                        title = "Claude Opus 4.8",
                        description = "Anthropic's most capable model with adaptive thinking. Requires separate Claude API key.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_CLAUDE_OPUS,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_CLAUDE_OPUS) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // xAI
                    ProviderHeader("xAI")

                    ModelOption(
                        title = "Grok 4.3",
                        description = "xAI's most capable model. Requires separate xAI API key.",
                        selected = compatIsNull && state.selectedModel == ApiKeyManager.MODEL_GROK,
                        onClick = { viewModel.setModel(ApiKeyManager.MODEL_GROK) }
                    )

                    // OpenAI-Compatible Providers
                    if (state.compatProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        ProviderHeader("OpenAI-Compatible")

                        state.compatProviders.forEach { provider ->
                            Spacer(modifier = Modifier.height(8.dp))
                            ModelOption(
                                title = "${provider.modelId} — ${provider.displayName}",
                                description = "OpenAI-compatible · ${provider.baseUrl}",
                                selected = state.selectedCompatProviderId == provider.id,
                                onClick = { viewModel.selectCompatProvider(provider.id) }
                            )
                        }
                    }
                }
            }

            // Reasoning Effort for selected compat provider
            val selectedCompatProvider = state.compatProviders.firstOrNull { it.id == state.selectedCompatProviderId }
            if (selectedCompatProvider != null && selectedCompatProvider.thinkingEnabled) {
                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader("Reasoning Effort (${selectedCompatProvider.displayName})")

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ModelOption(
                            title = "Low",
                            description = "Fastest responses, minimal reasoning",
                            selected = selectedCompatProvider.reasoningEffort == "low",
                            onClick = {
                                val updated = selectedCompatProvider.copy(reasoningEffort = "low")
                                viewModel.saveCompatProvider(updated)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "High",
                            description = "Deep reasoning, default setting",
                            selected = selectedCompatProvider.reasoningEffort == "high",
                            onClick = {
                                val updated = selectedCompatProvider.copy(reasoningEffort = "high")
                                viewModel.saveCompatProvider(updated)
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "Max",
                            description = "Maximum reasoning depth, may be slow",
                            selected = selectedCompatProvider.reasoningEffort == "max",
                            onClick = {
                                val updated = selectedCompatProvider.copy(reasoningEffort = "max")
                                viewModel.saveCompatProvider(updated)
                            }
                        )
                    }
                }
            }

            // Reasoning Effort (only for OpenAI Pro model)
            if (state.selectedModel == ApiKeyManager.MODEL_PRO) {
                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader("Reasoning Effort")

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ModelOption(
                            title = "Medium",
                            description = "Faster responses, less deep thinking",
                            selected = state.reasoningEffort == ApiKeyManager.EFFORT_MEDIUM,
                            onClick = { viewModel.setReasoningEffort(ApiKeyManager.EFFORT_MEDIUM) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "High",
                            description = "Good balance of speed and quality",
                            selected = state.reasoningEffort == ApiKeyManager.EFFORT_HIGH,
                            onClick = { viewModel.setReasoningEffort(ApiKeyManager.EFFORT_HIGH) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "XHigh",
                            description = "Maximum reasoning depth, slowest",
                            selected = state.reasoningEffort == ApiKeyManager.EFFORT_XHIGH,
                            onClick = { viewModel.setReasoningEffort(ApiKeyManager.EFFORT_XHIGH) }
                        )
                    }
                }
            }

            // Thinking Level (only for Gemini models)
            if (state.selectedModel == ApiKeyManager.MODEL_GEMINI_PRO ||
                state.selectedModel == ApiKeyManager.MODEL_GEMINI_PRO_35) {
                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader("Thinking Level")

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ModelOption(
                            title = "Low",
                            description = "Faster responses, minimal reasoning",
                            selected = state.thinkingLevel == ApiKeyManager.THINKING_LOW,
                            onClick = { viewModel.setThinkingLevel(ApiKeyManager.THINKING_LOW) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "Medium",
                            description = "Balanced thinking for most tasks",
                            selected = state.thinkingLevel == ApiKeyManager.THINKING_MEDIUM,
                            onClick = { viewModel.setThinkingLevel(ApiKeyManager.THINKING_MEDIUM) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "High",
                            description = "Maximum reasoning depth, default",
                            selected = state.thinkingLevel == ApiKeyManager.THINKING_HIGH,
                            onClick = { viewModel.setThinkingLevel(ApiKeyManager.THINKING_HIGH) }
                        )
                    }
                }
            }

            // Reasoning Effort (only for Claude model)
            if (state.selectedModel == ApiKeyManager.MODEL_CLAUDE_OPUS) {
                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader("Reasoning Effort")

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ModelOption(
                            title = "Low",
                            description = "Fastest responses, minimal thinking",
                            selected = state.claudeEffort == ApiKeyManager.CLAUDE_EFFORT_LOW,
                            onClick = { viewModel.setClaudeEffort(ApiKeyManager.CLAUDE_EFFORT_LOW) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "Medium",
                            description = "Balanced thinking for most tasks",
                            selected = state.claudeEffort == ApiKeyManager.CLAUDE_EFFORT_MEDIUM,
                            onClick = { viewModel.setClaudeEffort(ApiKeyManager.CLAUDE_EFFORT_MEDIUM) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "High",
                            description = "Deep reasoning, default setting",
                            selected = state.claudeEffort == ApiKeyManager.CLAUDE_EFFORT_HIGH,
                            onClick = { viewModel.setClaudeEffort(ApiKeyManager.CLAUDE_EFFORT_HIGH) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ModelOption(
                            title = "Max",
                            description = "Maximum reasoning depth, no constraints",
                            selected = state.claudeEffort == ApiKeyManager.CLAUDE_EFFORT_MAX,
                            onClick = { viewModel.setClaudeEffort(ApiKeyManager.CLAUDE_EFFORT_MAX) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Search
            SectionHeader("Search")

            val isDeepResearch = state.selectedModel == ApiKeyManager.MODEL_DEEP_RESEARCH ||
                    state.selectedModel == ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH
            val webSearchEffective = isDeepResearch || state.webSearchEnabled

            val isGrok = state.selectedModel == ApiKeyManager.MODEL_GROK

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
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
                            Text(
                                text = if (isDeepResearch) "Web search (always on)" else "Enable web search",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (isDeepResearch)
                                    "Deep Research always uses web search"
                                else
                                    "Allow the model to search the web for current information",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = webSearchEffective,
                            onCheckedChange = { viewModel.setWebSearchEnabled(it) },
                            enabled = !isDeepResearch,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f),
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable X search",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Search X (Twitter) posts for real-time information. Grok only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.xSearchEnabled,
                            onCheckedChange = { viewModel.setXSearchEnabled(it) },
                            enabled = isGrok,
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

            Spacer(modifier = Modifier.height(24.dp))

            // Voice Selection — only for OpenAI TTS provider
            if (state.ttsProvider == ApiKeyManager.TTS_PROVIDER_OPENAI) {
                SectionHeader("Voice")

                VoiceSelector(
                    selectedVoice = state.selectedVoice,
                    onVoiceSelected = { viewModel.setSelectedVoice(it) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // TTS Reading Instructions (collapsible) — OpenAI only
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { readingStyleExpanded = !readingStyleExpanded }
                    .padding(bottom = 8.dp)
            ) {
                Column {
                    Text(
                        text = "Reading Style",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentGreen
                    )
                    if (!readingStyleExpanded) {
                        Text(
                            text = if (state.ttsInstructions != ApiKeyManager.DEFAULT_TTS_INSTRUCTIONS)
                                "Custom instructions" else "Using default",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (readingStyleExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (readingStyleExpanded) "Collapse" else "Expand",
                    tint = AccentGreen
                )
            }

            AnimatedVisibility(visible = readingStyleExpanded) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Instructions sent to the text-to-speech model to control how it reads aloud.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = state.ttsInstructions,
                            onValueChange = { viewModel.setTtsInstructions(it) },
                            label = { Text("TTS instructions") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )

                        if (state.ttsInstructions != ApiKeyManager.DEFAULT_TTS_INSTRUCTIONS) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.resetTtsInstructions() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = AccentGreen
                                )
                            ) {
                                Text("Reset to Default")
                            }
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(24.dp))

            // Playback
            SectionHeader("Playback")

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
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
                            Text(
                                text = "Pings",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Play a ping after end-of-question detected and before reading answer",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.pingBeforeReading,
                            onCheckedChange = { viewModel.setPingBeforeReading(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f),
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Interactive voice",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Auto-detect when you stop speaking, then ask for follow-ups after each answer",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.interactiveVoiceEnabled,
                            onCheckedChange = { viewModel.setInteractiveVoiceEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f),
                                uncheckedBorderColor = Color.Gray
                            )
                        )
                    }

                    // Silence timeout slider (only when interactive voice is enabled)
                    if (state.interactiveVoiceEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        ) {
                            val displaySeconds = "%.1f".format(state.silenceTimeoutMs / 1000f)
                            Text(
                                text = "Silence timeout: ${displaySeconds}s",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "How long to wait after you stop speaking before processing",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            var sliderValue by remember(state.silenceTimeoutMs) {
                                mutableStateOf(state.silenceTimeoutMs.toFloat())
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    viewModel.setSilenceTimeoutMs(sliderValue.toInt())
                                },
                                valueRange = 1000f..10000f,
                                steps = 17, // 18 intervals of 500ms: 1000, 1500, 2000, ..., 10000
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentGreen,
                                    activeTrackColor = AccentGreen,
                                    inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("1s", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                Text("10s", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            }
                        }

                        // Follow-up prompt text field
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        ) {
                            Text(
                                text = "Follow-up prompt",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Text spoken after each answer to ask for follow-ups",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = state.followUpPrompt,
                                onValueChange = { viewModel.setFollowUpPrompt(it) },
                                label = { Text("Follow-up prompt") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp),
                                maxLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            if (state.followUpPrompt != SystemPrompts.FOLLOW_UP_PROMPT) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.resetFollowUpPrompt() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = AccentGreen
                                    )
                                ) {
                                    Text("Reset to Default")
                                }
                            }
                        }
                    }
                }
            }

            // Wake Word Section
            SectionHeader("Wake Word")
            WakeWordSection(state.wakeWordEnabled) { viewModel.setWakeWordEnabled(it) }

            Spacer(modifier = Modifier.height(24.dp))

            // System Prompts Section (collapsible)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { systemPromptsExpanded = !systemPromptsExpanded }
                    .padding(bottom = 8.dp)
            ) {
                Column {
                    Text(
                        text = "System Prompts",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentGreen
                    )
                    if (!systemPromptsExpanded) {
                        val standardCustom = state.systemPrompt != SystemPrompts.SYSTEM_PROMPT
                        val deepCustom = state.deepResearchSystemPrompt != SystemPrompts.DEEP_RESEARCH_SYSTEM_PROMPT
                        val summary = when {
                            standardCustom && deepCustom -> "Both prompts customized"
                            standardCustom -> "Standard prompt customized"
                            deepCustom -> "Deep Research prompt customized"
                            else -> "Using defaults"
                        }
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Icon(
                    imageVector = if (systemPromptsExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (systemPromptsExpanded) "Collapse" else "Expand",
                    tint = AccentGreen
                )
            }

            AnimatedVisibility(visible = systemPromptsExpanded) {
                Column {
                    // Standard System Prompt
                    Text(
                        text = "Standard Prompt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = state.systemPrompt,
                                onValueChange = { viewModel.setSystemPrompt(it) },
                                label = { Text("System prompt for all models") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                maxLines = 8,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            if (state.systemPrompt != SystemPrompts.SYSTEM_PROMPT) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.resetSystemPrompt() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = AccentGreen
                                    )
                                ) {
                                    Text("Reset to Default")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Deep Research System Prompt
                    Text(
                        text = "Deep Research Prompt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = state.deepResearchSystemPrompt,
                                onValueChange = { viewModel.setDeepResearchSystemPrompt(it) },
                                label = { Text("System prompt for Deep Research models") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                maxLines = 8,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen,
                                    cursorColor = AccentGreen,
                                    focusedLabelColor = AccentGreen
                                )
                            )

                            if (state.deepResearchSystemPrompt != SystemPrompts.DEEP_RESEARCH_SYSTEM_PROMPT) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.resetDeepResearchSystemPrompt() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = AccentGreen
                                    )
                                ) {
                                    Text("Reset to Default")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // History Management
            SectionHeader("History Management")

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Audio files: ${state.audioStorageSizeMb} MB",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "${state.conversationCount} conversations stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.showDeleteAllAudioDialog() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            enabled = state.audioStorageSizeMb > 0
                        ) {
                            Text("Delete All Audio")
                        }

                        OutlinedButton(
                            onClick = { viewModel.showClearHistoryDialog() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            enabled = state.conversationCount > 0
                        ) {
                            Text("Delete All")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About Section
            SectionHeader("About")

            Text(
                text = "https://github.com/JonesSteven/SlothSpeak",
                style = MaterialTheme.typography.bodySmall,
                color = AccentGreen,
                modifier = Modifier
                    .clickable { uriHandler.openUri("https://github.com/JonesSteven/SlothSpeak") }
                    .padding(top = 4.dp)
            )

            Text(
                text = "License (MIT)",
                style = MaterialTheme.typography.bodySmall,
                color = AccentGreen,
                modifier = Modifier
                    .clickable { showLicenseDialog = true }
                    .padding(top = 8.dp)
            )

            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.bodySmall,
                color = AccentGreen,
                modifier = Modifier
                    .clickable { showLicensesDialog = true }
                    .padding(top = 8.dp)
            )

            Text(
                text = "SlothSpeak V 0.9.2",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Delete Key Confirmation Dialog
    if (state.showDeleteKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteKeyDialog() },
            title = { Text("Delete API Key?") },
            text = {
                Text("This will remove your saved OpenAI API key. You will need to enter it again to use SlothSpeak.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteKey() }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteKeyDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Gemini Key Confirmation Dialog
    if (state.showDeleteGeminiKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteGeminiKeyDialog() },
            title = { Text("Delete Gemini API Key?") },
            text = {
                Text("This will remove your saved Gemini API key. You will need to enter it again to use Gemini models.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteGeminiKey() }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteGeminiKeyDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Claude Key Confirmation Dialog
    if (state.showDeleteClaudeKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteClaudeKeyDialog() },
            title = { Text("Delete Claude API Key?") },
            text = {
                Text("This will remove your saved Claude API key. You will need to enter it again to use Claude models.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteClaudeKey() }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteClaudeKeyDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Grok Key Confirmation Dialog
    if (state.showDeleteGrokKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteGrokKeyDialog() },
            title = { Text("Delete xAI API Key?") },
            text = {
                Text("This will remove your saved xAI API key. You will need to enter it again to use Grok models.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteGrokKey() }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteGrokKeyDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete All Audio Confirmation Dialog
    if (state.showDeleteAllAudioDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteAllAudioDialog() },
            title = { Text("Delete All Audio?") },
            text = {
                Text("This will permanently delete all audio files but keep your conversation history. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDeleteAllAudio() }
                ) {
                    Text("Delete Audio", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteAllAudioDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear History Confirmation Dialog
    if (state.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearHistoryDialog() },
            title = { Text("Clear All History?") },
            text = {
                Text("This will permanently delete all conversations and audio files. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmClearHistory() }
                ) {
                    Text("Clear All", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearHistoryDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // API Key Disclaimer Dialog
    if (showApiKeyDisclaimer) {
        AlertDialog(
            onDismissRequest = {
                showApiKeyDisclaimer = false
            },
            title = { Text("API Key Usage") },
            text = {
                Text(
                    "You are responsible for compliance with all terms and conditions " +
                        "associated with your use of API keys.\n\n" +
                        "Use of API keys will incur charges. Set limits and monitor appropriately.\n\n" +
                        "Text To Speech is AI-generated and not a human voice.\n\n" +
                        "AI can make mistakes. Verify important information."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showApiKeyDisclaimer = false
                    apiKeysExpanded = true
                }) {
                    Text("OK", color = AccentGreen)
                }
            }
        )
    }

    // Open Source Licenses Dialog
    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Open Source Licenses") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    LicenseEntry("Jetpack Compose", "Google", "Apache 2.0")
                    LicenseEntry("AndroidX Core / Activity / Lifecycle / Navigation", "Google", "Apache 2.0")
                    LicenseEntry("Room Database", "Google", "Apache 2.0")
                    LicenseEntry("Security Crypto", "Google", "Apache 2.0")
                    LicenseEntry("OkHttp", "Square", "Apache 2.0")
                    LicenseEntry("Retrofit", "Square", "Apache 2.0")
                    LicenseEntry("Moshi", "Square", "Apache 2.0")
                    LicenseEntry("Kotlin / Coroutines", "JetBrains", "Apache 2.0")
                    LicenseEntry("Silero VAD", "Georgy Konovalov", "MIT")
                    LicenseEntry("Noto Color Emoji", "Google", "SIL Open Font License 1.1")
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("OK", color = AccentGreen)
                }
            }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("License") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "MIT License\n\n" +
                            "Copyright (c) 2025 SlothSpeak Contributors\n\n" +
                            "Permission is hereby granted, free of charge, to any person obtaining a copy " +
                            "of this software and associated documentation files (the \"Software\"), to deal " +
                            "in the Software without restriction, including without limitation the rights " +
                            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
                            "copies of the Software, and to permit persons to whom the Software is " +
                            "furnished to do so, subject to the following conditions:\n\n" +
                            "The above copyright notice and this permission notice shall be included in all " +
                            "copies or substantial portions of the Software.\n\n" +
                            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
                            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
                            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
                            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
                            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
                            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
                            "SOFTWARE.\n\n" +
                            "--- Additional Disclaimers ---\n\n" +
                            "THIRD-PARTY API USAGE AND COSTS\n\n" +
                            "This software makes direct API calls to third-party services including but not " +
                            "limited to OpenAI, Google (Gemini), Anthropic (Claude), and xAI (Grok). These " +
                            "API calls may incur charges on your account with each respective provider. You " +
                            "are solely responsible for any and all costs, fees, or charges resulting from " +
                            "your use of these third-party APIs through this software. The authors and " +
                            "contributors of this software bear no responsibility for any charges incurred.\n\n" +
                            "TERMS OF USE COMPLIANCE\n\n" +
                            "You are solely responsible for complying with the terms of use, acceptable use " +
                            "policies, and any other agreements of all third-party API providers accessed " +
                            "through this software. The authors and contributors of this software make no " +
                            "representations regarding your eligibility to use any third-party service and " +
                            "accept no liability for violations of third-party terms.\n\n" +
                            "API AVAILABILITY\n\n" +
                            "This software depends on third-party APIs that may change, become unavailable, " +
                            "or be discontinued at any time without notice. The authors and contributors of " +
                            "this software make no guarantees regarding the availability, reliability, or " +
                            "continued compatibility of any third-party service.\n\n" +
                            "API KEY SECURITY\n\n" +
                            "This software stores API keys locally on your device using encrypted storage. " +
                            "You are solely responsible for the security of your API keys, including but not " +
                            "limited to safeguarding your device, preventing unauthorized access, and " +
                            "revoking keys if you suspect they have been compromised. The authors and " +
                            "contributors of this software accept no liability for unauthorized use of your " +
                            "API keys.\n\n" +
                            "AUDIO RECORDING AND PRIVACY\n\n" +
                            "This software records audio via the device microphone and transmits it to " +
                            "third-party services for speech-to-text transcription. When Interactive Voice " +
                            "Mode is enabled, the microphone may activate automatically after answer " +
                            "playback to listen for follow-up questions. You are responsible for ensuring " +
                            "that your use of audio recording complies with all applicable laws and " +
                            "regulations, including those governing consent and privacy.\n\n" +
                            "COMPUTER-GENERATED AUDIO DISCLOSURE\n\n" +
                            "All audio output produced by this software is entirely computer-generated using " +
                            "text-to-speech technology. The voices are synthetic and do not represent real " +
                            "human speakers. The audio content is generated by AI language models and may " +
                            "contain inaccuracies, errors, or hallucinations. You should not rely on the " +
                            "audio output as authoritative or factual without independent verification.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("OK", color = AccentGreen)
                }
            }
        )
    }

    // Compat provider delete confirmation dialog
    val deletingCompatId = compatShowDeleteConfirm
    if (deletingCompatId != null) {
        val providerName = state.compatProviders.firstOrNull { it.id == deletingCompatId }?.displayName ?: "this provider"
        AlertDialog(
            onDismissRequest = { compatShowDeleteConfirm = null },
            title = { Text("Delete $providerName?") },
            text = { Text("This will remove the provider and its API key from the app.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCompatProvider(deletingCompatId)
                        compatShowDeleteConfirm = null
                    }
                ) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { compatShowDeleteConfirm = null }) {
                    Text("Cancel", color = AccentGreen)
                }
            }
        )
    }

    // Compat provider add/edit dialog
    if (showCompatAddDialog || editingCompatProvider != null) {
        val isEditing = editingCompatProvider != null
        val dialogTitle = if (isEditing) "Edit ${editingCompatProvider?.displayName ?: "Provider"}" else "Add Provider"

        AlertDialog(
            onDismissRequest = {
                showCompatAddDialog = false
                editingCompatProvider = null
            },
            title = { Text(dialogTitle) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (!isEditing) {
                        // Template chooser for new providers
                        Text(
                            text = "Select a template or configure manually:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = {
                                    val provider = CompatibleProviderConfig.createZai("zai-${System.currentTimeMillis()}")
                                    editingCompatProvider = provider
                                    compatEditDisplayName = provider.displayName
                                    compatEditBaseUrl = provider.baseUrl
                                    compatEditModelId = provider.modelId
                                    compatEditKey = ""
                                    compatEditThinkingEnabled = provider.thinkingEnabled
                                    compatEditReasoningEffort = provider.reasoningEffort
                                    compatEditSendThinkingParams = provider.sendThinkingParams
                                    compatEditMaxTokens = provider.maxTokens?.toString() ?: ""
                                    compatSelectedTemplate = "zai"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Z.ai", maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    val provider = CompatibleProviderConfig.createZai("zai-${System.currentTimeMillis()}").copy(
                                        displayName = "Z.ai Flash",
                                        modelId = "glm-5.3-flash"
                                    )
                                    editingCompatProvider = provider
                                    compatEditDisplayName = provider.displayName
                                    compatEditBaseUrl = provider.baseUrl
                                    compatEditModelId = provider.modelId
                                    compatEditKey = ""
                                    compatEditThinkingEnabled = provider.thinkingEnabled
                                    compatEditReasoningEffort = provider.reasoningEffort
                                    compatEditSendThinkingParams = provider.sendThinkingParams
                                    compatEditMaxTokens = provider.maxTokens?.toString() ?: ""
                                    compatSelectedTemplate = "zai"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen.copy(alpha = 0.7f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Z.ai F.", maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    val provider = CompatibleProviderConfig.createDeepSeek("deepseek-${System.currentTimeMillis()}")
                                    editingCompatProvider = provider
                                    compatEditDisplayName = provider.displayName
                                    compatEditBaseUrl = provider.baseUrl
                                    compatEditModelId = provider.modelId
                                    compatEditKey = ""
                                    compatEditThinkingEnabled = provider.thinkingEnabled
                                    compatEditReasoningEffort = provider.reasoningEffort
                                    compatEditSendThinkingParams = provider.sendThinkingParams
                                    compatEditMaxTokens = provider.maxTokens?.toString() ?: ""
                                    compatSelectedTemplate = "deepseek"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("DeepSeek", maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    val provider = CompatibleProviderConfig.createCustom("custom-${System.currentTimeMillis()}")
                                    editingCompatProvider = provider
                                    compatEditDisplayName = provider.displayName
                                    compatEditBaseUrl = provider.baseUrl
                                    compatEditModelId = provider.modelId
                                    compatEditKey = ""
                                    compatEditThinkingEnabled = provider.thinkingEnabled
                                    compatEditReasoningEffort = provider.reasoningEffort
                                    compatEditSendThinkingParams = provider.sendThinkingParams
                                    compatEditMaxTokens = provider.maxTokens?.toString() ?: ""
                                    compatSelectedTemplate = "custom"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Custom", maxLines = 1)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Edit form (shown after template selected or when editing existing)
                    if (editingCompatProvider != null) {
                        OutlinedTextField(
                            value = compatEditDisplayName,
                            onValueChange = { compatEditDisplayName = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = compatEditBaseUrl,
                            onValueChange = { compatEditBaseUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text("…include /v1/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = compatEditModelId,
                            onValueChange = { compatEditModelId = it },
                            label = { Text("Model ID") },
                            placeholder = { if (compatSelectedTemplate == "custom") Text("e.g. qwen3.8-27b") else Text("Select from template") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = compatEditKey,
                            onValueChange = { compatEditKey = it },
                            label = { Text(if (compatSelectedTemplate == "custom") "API Key (optional)" else "API Key") },
                            placeholder = { if (compatSelectedTemplate == "custom") Text("Leave blank for self-hosted") else Text("sk-…") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Thinking toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable thinking", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Send reasoning_content parameters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = compatEditThinkingEnabled,
                                onCheckedChange = { compatEditThinkingEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AccentGreen,
                                    checkedTrackColor = AccentGreen.copy(alpha = 0.5f)
                                )
                            )
                        }

                        // Reasoning effort (only when thinking enabled)
                        if (compatEditThinkingEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Reasoning Effort", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("low", "high", "max").forEach { effort ->
                                    Button(
                                        onClick = { compatEditReasoningEffort = effort },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (compatEditReasoningEffort == effort) AccentGreen
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

                        // Send thinking parameters toggle (visible always)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Send thinking parameters",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    if (compatEditSendThinkingParams) "ON for Z.ai/DeepSeek" else "OFF for Custom (vLLM)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = compatEditSendThinkingParams,
                                onCheckedChange = { compatEditSendThinkingParams = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AccentGreen,
                                    checkedTrackColor = AccentGreen.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Max tokens
                        OutlinedTextField(
                            value = compatEditMaxTokens,
                            onValueChange = { compatEditMaxTokens = it.filter { c -> c.isDigit() } },
                            label = { Text("Max Tokens (optional)") },
                            placeholder = { Text("Blank = provider default") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                cursorColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val provider = editingCompatProvider ?: return@TextButton
                        val updated = provider.copy(
                            displayName = compatEditDisplayName.ifBlank { provider.displayName },
                            baseUrl = compatEditBaseUrl.ifBlank { provider.baseUrl },
                            modelId = compatEditModelId.ifBlank { provider.modelId },
                            thinkingEnabled = compatEditThinkingEnabled,
                            reasoningEffort = compatEditReasoningEffort,
                            sendThinkingParams = compatEditSendThinkingParams,
                            maxTokens = compatEditMaxTokens.toIntOrNull()
                        )
                        viewModel.saveCompatProvider(updated)
                        if (compatEditKey.isNotBlank()) {
                            viewModel.saveCompatProviderKey(updated.id, compatEditKey)
                        } else {
                            // Blank key on existing provider = clear it
                            viewModel.deleteCompatProviderKey(updated.id)
                        }
                        showCompatAddDialog = false
                        editingCompatProvider = null
                    },
                    enabled = editingCompatProvider != null &&
                            compatEditBaseUrl.isNotBlank() &&
                            compatEditModelId.isNotBlank()
                ) {
                    Text("Save", color = AccentGreen)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCompatAddDialog = false
                        editingCompatProvider = null
                    }
                ) {
                    Text("Cancel", color = ErrorRed)
                }
            }
        )
    }
}

@Composable
private fun LicenseEntry(name: String, author: String, license: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "$author — $license",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
fun WakeWordSection(
    wakeWordEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hey Jarvis wake word",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Runs fully on-device using openWakeWord (open-source, Apache-2.0). No account needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = wakeWordEnabled,
                onCheckedChange = onToggle,
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

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = AccentGreen,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ProviderHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun VoiceSelector(
    selectedVoice: String,
    onVoiceSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var playingVoice by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    // Clean up MediaPlayer when composable leaves the composition
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    fun playSample(voice: String) {
        // Stop any currently playing sample
        mediaPlayer?.release()
        mediaPlayer = null
        playingVoice = null

        val resId = voiceSampleResId(voice)
        if (resId == 0) return

        val player = android.media.MediaPlayer.create(context, resId)
        player?.let {
            it.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            it.setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
                playingVoice = null
            }
            it.start()
            mediaPlayer = it
            playingVoice = voice
        }
    }

    fun stopSample() {
        mediaPlayer?.release()
        mediaPlayer = null
        playingVoice = null
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AI-generated TTS Voice",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { voiceMenuExpanded = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                ) {
                    Text(
                        text = selectedVoice.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (playingVoice == selectedVoice) stopSample()
                        else playSample(selectedVoice)
                    }
                ) {
                    Icon(
                        imageVector = if (playingVoice == selectedVoice) Icons.Default.Stop
                            else Icons.Default.PlayArrow,
                        contentDescription = if (playingVoice == selectedVoice) "Stop" else "Play sample",
                        tint = AccentGreen,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = voiceMenuExpanded,
                onDismissRequest = { voiceMenuExpanded = false }
            ) {
                ApiKeyManager.VOICES.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = voice.replaceFirstChar { it.uppercase() },
                                color = if (voice == selectedVoice) AccentGreen
                                    else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (playingVoice == voice) stopSample()
                                    else playSample(voice)
                                }
                            ) {
                                Icon(
                                    imageVector = if (playingVoice == voice) Icons.Default.Stop
                                        else Icons.Default.PlayArrow,
                                    contentDescription = if (playingVoice == voice) "Stop" else "Play sample",
                                    tint = AccentGreen
                                )
                            }
                        },
                        onClick = {
                            onVoiceSelected(voice)
                            voiceMenuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun voiceSampleResId(voice: String): Int {
    return when (voice) {
        "alloy" -> com.slothspeak.R.raw.voice_alloy
        "ash" -> com.slothspeak.R.raw.voice_ash
        "ballad" -> com.slothspeak.R.raw.voice_ballad
        "coral" -> com.slothspeak.R.raw.voice_coral
        "echo" -> com.slothspeak.R.raw.voice_echo
        "fable" -> com.slothspeak.R.raw.voice_fable
        "nova" -> com.slothspeak.R.raw.voice_nova
        "onyx" -> com.slothspeak.R.raw.voice_onyx
        "sage" -> com.slothspeak.R.raw.voice_sage
        "shimmer" -> com.slothspeak.R.raw.voice_shimmer
        "verse" -> com.slothspeak.R.raw.voice_verse
        "marin" -> com.slothspeak.R.raw.voice_marin
        "cedar" -> com.slothspeak.R.raw.voice_cedar
        else -> 0
    }
}

@Composable
private fun ModelOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentGreen,
                unselectedColor = TextSecondary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun CompatProvidersList(
    providers: List<CompatibleProviderConfig>,
    onProviderEdit: (CompatibleProviderConfig) -> Unit,
    onProviderDelete: (String) -> Unit
) {
    providers.forEach { provider ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { onProviderEdit(provider) }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "${provider.modelId} · ${provider.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { onProviderDelete(provider.id) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = ErrorRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SttSection(
    sttProvider: String,
    hasSttApiKey: Boolean,
    sttModel: String,
    sttBaseUrl: String,
    sttKeySaved: Boolean,
    onSetProvider: (String) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onDeleteApiKey: () -> Unit,
    onSetModel: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit
) {
    var sttKeyInput by remember { mutableStateOf("") }
    var showSttKey by remember { mutableStateOf(false) }

    // Provider selection radio buttons
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Provider",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // OpenAI
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = sttProvider == ApiKeyManager.STT_PROVIDER_OPENAI,
                    onClick = { onSetProvider(ApiKeyManager.STT_PROVIDER_OPENAI) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "OpenAI (gpt-4o-transcribe)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Uses your OpenAI API key above",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Together
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = sttProvider == ApiKeyManager.STT_PROVIDER_TOGETHER,
                    onClick = { onSetProvider(ApiKeyManager.STT_PROVIDER_TOGETHER) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Together (whisper-large-v3)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Requires a Together.ai API key",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = sttProvider == ApiKeyManager.STT_PROVIDER_CUSTOM,
                    onClick = { onSetProvider(ApiKeyManager.STT_PROVIDER_CUSTOM) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Custom (OpenAI-compatible)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Self-hosted or any OpenAI-compatible STT endpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Provider-specific configuration card
    when (sttProvider) {
        ApiKeyManager.STT_PROVIDER_OPENAI -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Uses your OpenAI API key configured above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Model: gpt-4o-transcribe",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        ApiKeyManager.STT_PROVIDER_TOGETHER -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (hasSttApiKey) "API key is saved" else "No API key set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasSttApiKey) AccentGreen else TextSecondary
                    )

                    if (sttKeySaved) {
                        Text(
                            text = "Key saved successfully!",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = sttKeyInput,
                        onValueChange = { sttKeyInput = it },
                        label = { Text("Together.ai API Key") },
                        placeholder = { Text("tgp-...") },
                        visualTransformation = if (showSttKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSttKey = !showSttKey }) {
                                Icon(
                                    imageVector = if (showSttKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showSttKey) "Hide" else "Show",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row {
                        Button(
                            onClick = {
                                onSaveApiKey(sttKeyInput)
                                sttKeyInput = ""
                            },
                            enabled = sttKeyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Save")
                        }

                        if (hasSttApiKey) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onDeleteApiKey() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = ErrorRed
                                )
                            ) {
                                Text("Delete Key")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model field
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = sttModel,
                        onValueChange = { onSetModel(it) },
                        label = { Text("Model ID") },
                        placeholder = { Text("openai/whisper-large-v3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )
                }
            }
        }

        ApiKeyManager.STT_PROVIDER_CUSTOM -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Base URL
                    Text(
                        text = "Base URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = sttBaseUrl,
                        onValueChange = { onSetBaseUrl(it) },
                        label = { Text("Base URL (include /v1/)") },
                        placeholder = { Text("http://192.168.1.100:8000/v1/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = sttModel,
                        onValueChange = { onSetModel(it) },
                        label = { Text("Model ID") },
                        placeholder = { Text("openai/whisper-large-v3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // API key (optional)
                    Text(
                        text = if (hasSttApiKey) "API key is saved (optional)" else "API key (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasSttApiKey) AccentGreen else TextSecondary
                    )

                    if (sttKeySaved) {
                        Text(
                            text = "Key saved successfully!",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = sttKeyInput,
                        onValueChange = { sttKeyInput = it },
                        label = { Text("API Key (leave blank for no auth)") },
                        placeholder = { Text("Optional API key") },
                        visualTransformation = if (showSttKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSttKey = !showSttKey }) {
                                Icon(
                                    imageVector = if (showSttKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showSttKey) "Hide" else "Show",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row {
                        Button(
                            onClick = {
                                onSaveApiKey(sttKeyInput)
                                sttKeyInput = ""
                            },
                            enabled = sttKeyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Save")
                        }

                        if (hasSttApiKey) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onDeleteApiKey() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = ErrorRed
                                )
                            ) {
                                Text("Delete Key")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// TTS (Text-to-Speech) Provider Section
// ============================================================

@Composable
fun TtsSection(
    ttsProvider: String,
    ttsModel: String,
    ttsVoice: String,
    ttsBaseUrl: String,
    hasTtsCustomApiKey: Boolean,
    ttsKeySaved: Boolean,
    onSetProvider: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSetVoice: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSaveCustomApiKey: (String) -> Unit,
    onDeleteCustomApiKey: () -> Unit
) {
    var ttsCustomKeyInput by remember { mutableStateOf("") }
    var showTtsCustomKey by remember { mutableStateOf(false) }

    // Provider selection radio buttons
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Provider",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // OpenAI
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = ttsProvider == ApiKeyManager.TTS_PROVIDER_OPENAI,
                    onClick = { onSetProvider(ApiKeyManager.TTS_PROVIDER_OPENAI) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "OpenAI (gpt-4o-mini-tts)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Uses your OpenAI API key above",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Together
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = ttsProvider == ApiKeyManager.TTS_PROVIDER_TOGETHER,
                    onClick = { onSetProvider(ApiKeyManager.TTS_PROVIDER_TOGETHER) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Together (Orpheus)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Uses your Together key from Speech-to-Text",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Custom
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = ttsProvider == ApiKeyManager.TTS_PROVIDER_CUSTOM,
                    onClick = { onSetProvider(ApiKeyManager.TTS_PROVIDER_CUSTOM) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Custom (OpenAI-compatible)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Self-hosted or any OpenAI-compatible TTS endpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // On-device
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = ttsProvider == ApiKeyManager.TTS_PROVIDER_ONDEVICE,
                    onClick = { onSetProvider(ApiKeyManager.TTS_PROVIDER_ONDEVICE) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = AccentGreen,
                        unselectedColor = TextSecondary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "On-device (Android system TTS)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Free, offline, uses your device's system text-to-speech engine",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Provider-specific configuration card
    when (ttsProvider) {
        ApiKeyManager.TTS_PROVIDER_OPENAI -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Uses your OpenAI API key configured above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Model: gpt-4o-mini-tts",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Voice is chosen with the OpenAI voice picker below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        ApiKeyManager.TTS_PROVIDER_TOGETHER -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Uses your Together.ai API key from Speech-to-Text.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model field
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = ttsModel,
                        onValueChange = { onSetModel(it) },
                        label = { Text("Model ID") },
                        placeholder = { Text(ApiKeyManager.DEFAULT_TTS_MODEL_TOGETHER) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Voice field
                    Text(
                        text = "Voice",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = ttsVoice,
                        onValueChange = { onSetVoice(it) },
                        label = { Text("Voice ID") },
                        placeholder = { Text(ApiKeyManager.DEFAULT_TTS_VOICE_TOGETHER) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )
                }
            }
        }

        ApiKeyManager.TTS_PROVIDER_CUSTOM -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Base URL
                    Text(
                        text = "Base URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = ttsBaseUrl,
                        onValueChange = { onSetBaseUrl(it) },
                        label = { Text("Base URL (include /v1/)") },
                        placeholder = { Text("http://192.168.1.100:8000/v1/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Model
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = ttsModel,
                        onValueChange = { onSetModel(it) },
                        label = { Text("Model ID") },
                        placeholder = { Text("tts-model-id") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Voice
                    Text(
                        text = "Voice",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = ttsVoice,
                        onValueChange = { onSetVoice(it) },
                        label = { Text("Voice ID (optional)") },
                        placeholder = { Text("Voice name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // API key (optional)
                    Text(
                        text = if (hasTtsCustomApiKey) "API key is saved (optional)" else "API key (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasTtsCustomApiKey) AccentGreen else TextSecondary
                    )

                    if (ttsKeySaved) {
                        Text(
                            text = "Key saved successfully!",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentGreen
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ttsCustomKeyInput,
                        onValueChange = { ttsCustomKeyInput = it },
                        label = { Text("API Key (leave blank for no auth)") },
                        placeholder = { Text("Optional API key") },
                        visualTransformation = if (showTtsCustomKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showTtsCustomKey = !showTtsCustomKey }) {
                                Icon(
                                    imageVector = if (showTtsCustomKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showTtsCustomKey) "Hide" else "Show",
                                    tint = TextSecondary
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            cursorColor = AccentGreen,
                            focusedLabelColor = AccentGreen
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row {
                        Button(
                            onClick = {
                                onSaveCustomApiKey(ttsCustomKeyInput)
                                ttsCustomKeyInput = ""
                            },
                            enabled = ttsCustomKeyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Save")
                        }

                        if (hasTtsCustomApiKey) {
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onDeleteCustomApiKey() },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = ErrorRed
                                )
                            ) {
                                Text("Delete Key")
                            }
                        }
                    }
                }
            }
        }

        ApiKeyManager.TTS_PROVIDER_ONDEVICE -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Uses your device's system text-to-speech engine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Free and offline. No API key required. " +
                                "Voice and model settings are managed by the system.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
