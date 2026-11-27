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
    var readingStyleExpanded by remember { mutableStateOf(false) }
    var systemPromptsExpanded by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

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
            // LLM comes first: configured models, active model, thinking, search.
            LlmSettingsSection(
                providers = state.llmProviders,
                compatProviders = state.compatProviders,
                selectedModel = state.selectedModel,
                xSearchEnabled = state.xSearchEnabled,
                onActivate = { viewModel.activateLlmProvider(it) },
                onSaveBuiltin = { type, key, model, activate ->
                    viewModel.saveBuiltinLlmProvider(type, key, model, activate)
                },
                onDeleteBuiltin = { viewModel.deleteBuiltinLlmProvider(it) },
                onSaveCompat = { config, key -> viewModel.saveCompatProvider(config, key) },
                onDeleteCompat = { viewModel.deleteCompatProvider(it) },
                onSetThinking = { provider, enabled, level ->
                    viewModel.setLlmThinking(provider, enabled, level)
                },
                onSetXSearch = { viewModel.setXSearchEnabled(it) },
                onFetchModels = { baseUrl, providerId, apiKey ->
                    viewModel.fetchCompatModels(baseUrl, providerId, apiKey)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Tools: plugins (Brave Search, HTTP request) for OpenAI-compatible models.
            ToolsSettingsSection(
                state = state.tools,
                onToggleBraveSearch = { viewModel.setBraveSearchEnabled(it) },
                onSaveBraveApiKey = { viewModel.saveBraveApiKey(it) },
                onDeleteBraveApiKey = { viewModel.deleteBraveApiKey() },
                onToggleHttpRequest = { viewModel.setHttpRequestEnabled(it) },
                onSetHttpWritePolicy = { viewModel.setHttpWritePolicy(it) },
                onRemoveAllowedWriteHost = { viewModel.removeAllowedWriteHost(it) },
                onResetAllowedWriteHosts = { viewModel.resetAllowedWriteHosts() },
                onSaveHostAuthProfile = { host, headerName, headerValue ->
                    viewModel.saveHostAuthProfile(host, headerName, headerValue)
                },
                onDeleteHostAuthProfile = { viewModel.deleteHostAuthProfile(it) },
                onSetMaxToolCalls = { viewModel.setMaxToolCalls(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Speech-to-Text Section
            SttSettingsSection(
                providers = state.sttProviders,
                futoLanguage = state.futoSttLanguage,
                futoModelInstalled = state.futoModelInstalled,
                futoModelDownloading = state.futoModelDownloading,
                futoModelImporting = state.futoModelImporting,
                futoModelProgress = state.futoModelProgress,
                futoModelTotalMb = state.futoModelTotalMb,
                futoModelError = state.futoModelError,
                onSetProvider = { viewModel.setSttProvider(it) },
                onSaveKey = { viewModel.saveSttApiKey(it) },
                onDeleteKey = { viewModel.deleteSttApiKey() },
                onSetModel = { viewModel.setSttModel(it) },
                onSetBaseUrl = { viewModel.setSttBaseUrl(it) },
                onSetFutoLanguage = { viewModel.setFutoSttLanguage(it) },
                onDownloadFutoModel = { viewModel.downloadFutoSttModel() },
                onCancelFutoDownload = { viewModel.cancelFutoSttDownload() },
                onImportFutoModel = { viewModel.importFutoSttModel(it) },
                onDeleteFutoModel = { viewModel.deleteFutoSttModel() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Text-to-Speech Section
            TtsSettingsSection(
                providers = state.ttsProviders,
                onSetProvider = { viewModel.setTtsProvider(it) },
                onSetModel = { viewModel.setTtsModel(it) },
                onSetVoice = { viewModel.setTtsVoice(it) },
                onSetBaseUrl = { viewModel.setTtsBaseUrl(it) },
                onSaveCustomKey = { viewModel.saveTtsCustomApiKey(it) },
                onDeleteCustomKey = { viewModel.deleteTtsCustomApiKey() }
            )

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
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = AccentGreen,
        modifier = Modifier.padding(bottom = 8.dp)
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
