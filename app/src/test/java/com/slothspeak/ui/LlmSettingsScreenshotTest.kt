package com.slothspeak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.slothspeak.data.ApiKeyManager
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.LlmProviderType
import com.slothspeak.data.LlmProviderUiState
import com.slothspeak.ui.screens.LlmSettingsSection
import com.slothspeak.ui.theme.SlothSpeakTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for the unified LLM settings section: active provider
 * with thinking chip, configured provider rows, and the shared add-model flow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LlmSettingsScreenshotTest {

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            roborazziOptions = RoborazziOptions(
                taskType = RoborazziTaskType.Record
            )
        )
    )

    @get:Rule
    val rule = createComposeRule()

    private val openAi = LlmProviderUiState(
        key = "builtin:openai",
        type = LlmProviderType.OPENAI,
        displayName = "OpenAI",
        modelId = ApiKeyManager.MODEL_PRO,
        active = true,
        thinkingEnabled = true,
        thinkingLevel = ApiKeyManager.EFFORT_HIGH,
        thinkingLevels = listOf(
            ApiKeyManager.EFFORT_MEDIUM,
            ApiKeyManager.EFFORT_HIGH,
            ApiKeyManager.EFFORT_XHIGH
        )
    )

    private val claude = LlmProviderUiState(
        key = "builtin:claude",
        type = LlmProviderType.CLAUDE,
        displayName = "Claude",
        modelId = ApiKeyManager.MODEL_CLAUDE_OPUS,
        thinkingEnabled = true,
        thinkingLevel = ApiKeyManager.CLAUDE_EFFORT_HIGH,
        thinkingLevels = listOf("low", "medium", "high", "max")
    )

    private val deepSeekConfig = CompatibleProviderConfig.createDeepSeek("deepseek-1")

    private val deepSeek = LlmProviderUiState(
        key = "compat:deepseek-1",
        type = LlmProviderType.COMPAT,
        displayName = deepSeekConfig.displayName,
        modelId = deepSeekConfig.modelId,
        baseUrl = deepSeekConfig.baseUrl,
        thinkingEnabled = true,
        thinkingLevel = "high",
        thinkingLevels = listOf("low", "high", "max"),
        compatProviderId = deepSeekConfig.id
    )

    @Composable
    private fun Section(providers: List<LlmProviderUiState>) {
        SlothSpeakTheme {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    LlmSettingsSection(
                        providers = providers,
                        compatProviders = listOf(deepSeekConfig),
                        selectedModel = ApiKeyManager.MODEL_PRO,
                        xSearchEnabled = false,
                        onActivate = {},
                        onSaveBuiltin = { _, _, _, _ -> },
                        onDeleteBuiltin = {},
                        onSaveCompat = { _, _ -> },
                        onDeleteCompat = {},
                        onSetThinking = { _, _, _ -> },
                        onSetXSearch = {},
                        onFetchModels = { _, _, _ -> Result.success(emptyList()) }
                    )
                }
            }
        }
    }

    @Test
    fun llmSettings_activeAndConfigured_png() {
        rule.setContent {
            Section(listOf(openAi, claude, deepSeek))
        }
        rule.waitForIdle()

        rule.onNodeWithText("LLM").assertExists()
        rule.onNodeWithText("OpenAI").assertExists()
        rule.onNodeWithText("ACTIVE").assertExists()
        rule.onNodeWithText("GPT-5.5 Pro").assertExists()
        rule.onNodeWithText("Thinking · High").assertExists()
        rule.onNodeWithText("Modify").assertExists()
        rule.onNodeWithText("Claude").assertExists()
        rule.onNodeWithText("Claude Opus 4.8").assertExists()
        rule.onNodeWithText("DeepSeek").assertExists()
        rule.onNodeWithText("deepseek-v4-flash").assertExists()
        rule.onNodeWithText("Add model").assertExists()
        // Web search was removed; the Search section is Grok/X-only.
        rule.onNodeWithText("Search").assertDoesNotExist()
        rule.onNodeWithText("Enable web search").assertDoesNotExist()
        rule.onNodeWithText("Enable X search").assertDoesNotExist()

        rule.onRoot().captureRoboImage("build/ui-screenshots/llmSettings_activeAndConfigured.png")
    }

    @Test
    fun llmSettings_addModelFlow_png() {
        rule.setContent {
            Section(listOf(openAi))
        }
        rule.waitForIdle()

        rule.onNodeWithText("Add model").performClick()
        rule.waitForIdle()

        // The same chooser hosts built-in and OpenAI-compatible providers.
        rule.onNodeWithText("Z.ai").assertExists()
        rule.onNodeWithText("DeepSeek").assertExists()
        rule.onNodeWithText("Custom").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/llmSettings_addModelFlow.png")
    }
}
