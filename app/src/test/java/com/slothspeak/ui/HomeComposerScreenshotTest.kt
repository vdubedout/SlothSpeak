package com.slothspeak.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.slothspeak.ui.components.ChatComposer
import com.slothspeak.ui.components.ModelSwitcherContent
import com.slothspeak.ui.components.QAPairCard
import com.slothspeak.ui.theme.SlothSpeakTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for the ChatGPT/Claude-style home screen: bottom
 * composer with mic/send and the model pill below, the folded-by-default
 * model switcher, the expanded multi-model list, and the Q&A card with the
 * read-aloud speaker button.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeComposerScreenshotTest {

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
        modelId = ApiKeyManager.MODEL_ASTRA,
        active = true,
        thinkingEnabled = true,
        thinkingLevel = ApiKeyManager.EFFORT_HIGH,
        thinkingLevels = listOf(
            ApiKeyManager.EFFORT_MEDIUM,
            ApiKeyManager.EFFORT_HIGH,
            ApiKeyManager.EFFORT_XHIGH
        ),
        availableModels = ApiKeyManager.OPENAI_MODELS
    )

    private val claude = LlmProviderUiState(
        key = "builtin:claude",
        type = LlmProviderType.CLAUDE,
        displayName = "Claude",
        modelId = ApiKeyManager.MODEL_CLAUDE_OPUS,
        thinkingEnabled = true,
        thinkingLevel = ApiKeyManager.CLAUDE_EFFORT_HIGH,
        thinkingLevels = listOf(
            ApiKeyManager.CLAUDE_EFFORT_LOW,
            ApiKeyManager.CLAUDE_EFFORT_MEDIUM,
            ApiKeyManager.CLAUDE_EFFORT_HIGH,
            ApiKeyManager.CLAUDE_EFFORT_MAX
        ),
        availableModels = ApiKeyManager.CLAUDE_MODELS
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
        compatProviderId = deepSeekConfig.id,
        availableModels = listOf(deepSeekConfig.modelId)
    )

    @Test
    fun homeComposer_withModelPillBelow_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        ChatComposer(
                            voiceAvailable = true,
                            onMicClick = {},
                            onVoiceUnavailable = {},
                            onSend = {},
                            activeProvider = openAi,
                            onModelPillClick = {}
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Ask anything…").assertExists()
        rule.onNodeWithContentDescription("Voice chat").assertExists()
        rule.onNodeWithContentDescription("Send").assertExists()
        // Model pill rendered directly below the composer row
        rule.onNodeWithText("GPT-6.2 Astra").assertExists()
        rule.onNodeWithContentDescription("Switch model").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/homeComposer_withModelPillBelow.png")
    }

    @Test
    fun modelSwitcher_folded_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    ModelSwitcherContent(
                        providers = listOf(openAi, claude, deepSeek),
                        onSelectModel = { _, _ -> },
                        onManageModels = {}
                    )
                }
            }
        }
        rule.waitForIdle()

        // Folded by default: provider rows visible, specific model rows hidden
        rule.onNodeWithText("OpenAI").assertExists()
        rule.onNodeWithText("Claude").assertExists()
        rule.onNodeWithText("DeepSeek").assertExists()
        rule.onNodeWithText("GPT-6.2 Astra").assertExists()
        rule.onNodeWithText("Claude Opus 5.5").assertExists()
        rule.onNodeWithText("deepseek-v4-flash").assertExists()
        rule.onNodeWithText("GPT-6.2 Sol").assertDoesNotExist()
        rule.onNodeWithText("GPT-6.1 Terra").assertDoesNotExist()

        rule.onRoot().captureRoboImage("build/ui-screenshots/modelSwitcher_folded.png")
    }

    @Test
    fun modelSwitcher_expanded_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    ModelSwitcherContent(
                        providers = listOf(openAi, claude, deepSeek),
                        onSelectModel = { _, _ -> },
                        onManageModels = {}
                    )
                }
            }
        }
        rule.waitForIdle()

        // Expand the only multi-model provider (OpenAI)
        rule.onAllNodesWithContentDescription("Expand models")[0].performClick()
        rule.waitForIdle()

        // Specific model rows become visible (GPT-5.5 Pro appears both in the
        // header subtitle and as a model row, so match the unique ones exactly)
        rule.onNodeWithText("GPT-6.2 Sol").assertExists()
        rule.onNodeWithText("GPT-6.1 Terra").assertExists()
        assertTrue(
            "expected header subtitle + model row",
            rule.onAllNodesWithText("GPT-6.2 Astra").fetchSemanticsNodes().size >= 2
        )

        rule.onRoot().captureRoboImage("build/ui-screenshots/modelSwitcher_expanded.png")
    }

    @Test
    fun qaPairCard_withSpeakerButton_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What is the capital of France?",
                        answerText = "The capital of France is Paris. It has been the " +
                            "country's center of politics, culture, and economy for centuries.",
                        timestamp = System.currentTimeMillis(),
                        audioFiles = null,
                        onReplayClick = null,
                        model = "gpt-5.5",
                        llmResponseSeconds = 4,
                        canSpeak = true,
                        onSpeakClick = {}
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("What is the capital of France?").assertExists()
        rule.onNodeWithContentDescription("Read aloud").assertExists()
        rule.onNodeWithContentDescription("Copy").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/qaPairCard_withSpeakerButton.png")
    }
}
