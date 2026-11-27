package com.slothspeak.ui

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.slothspeak.ui.screens.KeepLatestVisibleOnImeOpen
import com.slothspeak.ui.screens.MainScaffold
import com.slothspeak.ui.theme.SlothSpeakTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun homeScreen_imeOpen_composerAndModelPill_png() {
        val samplePairs = listOf(
            "What is the capital of France?" to
                "The capital of France is Paris, situated on the Seine river in northern France.",
            "How about Italy?" to
                "The capital of Italy is Rome, known for nearly three millennia of influential art and architecture.",
            "And what about Japan?" to
                "The capital of Japan is Tokyo, the world's most populous metropolitan area."
        )
        var hostView: View? = null
        var density: Density? = null
        var modelPillClicks = 0

        rule.setContent {
            hostView = LocalView.current
            density = LocalDensity.current
            val snackbarHostState = remember { SnackbarHostState() }
            val listState = rememberLazyListState()
            KeepLatestVisibleOnImeOpen(listState = listState, itemCount = samplePairs.size)

            SlothSpeakTheme {
                MainScaffold(
                    snackbarHostState = snackbarHostState,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "SlothSpeak",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(samplePairs) { (q, a) ->
                                QAPairCard(
                                    questionText = q,
                                    answerText = a,
                                    timestamp = 1700000000000L,
                                    audioFiles = null,
                                    onReplayClick = null,
                                    model = "gpt-6.2-astra",
                                    llmResponseSeconds = 4
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        ChatComposer(
                            voiceAvailable = true,
                            onMicClick = {},
                            onVoiceUnavailable = {},
                            onSend = {},
                            activeProvider = openAi,
                            onModelPillClick = { modelPillClicks++ }
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        val targetView = requireNotNull(hostView)
        val activeDensity = requireNotNull(density)
        val statusBarDp = 36.dp
        val navBarDp = 48.dp
        val imeDp = 300.dp
        val statusBarPx = with(activeDensity) { statusBarDp.roundToPx() }
        val navBarPx = with(activeDensity) { navBarDp.roundToPx() }
        val imePx = with(activeDensity) { imeDp.roundToPx() }

        // 1. Baseline: keyboard closed, edge-to-edge status bar + navigation bar insets
        val closedInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .build()
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(targetView, closedInsets)
        }
        rule.waitForIdle()

        val rootBounds = rule.onRoot().getUnclippedBoundsInRoot()
        val topBarClosed = rule.onNodeWithText("SlothSpeak").getUnclippedBoundsInRoot()
        val inputClosed = rule.onNodeWithText("Ask anything…").getUnclippedBoundsInRoot()
        val modelPillClosed = rule.onNodeWithText("GPT-6.2 Astra").getUnclippedBoundsInRoot()

        // 2. Keyboard open: 300dp IME inset alongside the same 48dp navigation bar inset
        val openInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarPx))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .build()
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(targetView, openInsets)
        }
        rule.waitForIdle()

        val topBarOpen = rule.onNodeWithText("SlothSpeak").getUnclippedBoundsInRoot()
        val inputOpen = rule.onNodeWithText("Ask anything…").getUnclippedBoundsInRoot()
        val modelPillOpen = rule.onNodeWithText("GPT-6.2 Astra").getUnclippedBoundsInRoot()
        val latestQuestion = rule.onNodeWithText("And what about Japan?").getUnclippedBoundsInRoot()

        val imeTopDp = rootBounds.bottom - imeDp
        val expectedLiftDp = imeDp - navBarDp // 252dp: IME replaces nav bar, no double-padding

        // Top bar stays anchored at the top
        assertEquals(topBarClosed.top.value, topBarOpen.top.value, 1f)

        // Both the input box and the model chip below it sit completely above the keyboard
        assertTrue(
            "Expected input box bottom (${inputOpen.bottom}) <= IME top ($imeTopDp)",
            inputOpen.bottom <= imeTopDp
        )
        assertTrue(
            "Expected model chip bottom (${modelPillOpen.bottom}) <= IME top ($imeTopDp)",
            modelPillOpen.bottom <= imeTopDp
        )

        // Model chip lifts by (ime - navBar), confirming zero double-padding with navigationBars
        val actualLiftDp = modelPillClosed.bottom - modelPillOpen.bottom
        assertTrue(
            "Expected model chip to lift by ~$expectedLiftDp (got $actualLiftDp; closed=${modelPillClosed.bottom}, open=${modelPillOpen.bottom})",
            abs(actualLiftDp.value - expectedLiftDp.value) <= 2f
        )

        // Latest conversation item remains visible above the composer
        assertTrue(
            "Expected latest message (${latestQuestion.bottom}) above input (${inputOpen.top})",
            latestQuestion.bottom <= inputOpen.top
        )

        // Model chip stays tappable with keyboard open
        rule.onNodeWithText("GPT-6.2 Astra").performClick()
        rule.waitForIdle()
        assertEquals(1, modelPillClicks)

        rule.onRoot().captureRoboImage("build/ui-screenshots/homeScreen_imeOpen_composerAndModelPill.png")
    }

    @Test
    fun keepLatestVisibleOnImeOpen_clampsIndexWhenListHasFewerItems() {
        val samplePairs = listOf(
            "Question 1" to "Answer 1",
            "Question 2" to "Answer 2",
            "Question 3" to "Answer 3"
        )
        var hostView: View? = null
        var density: Density? = null

        rule.setContent {
            hostView = LocalView.current
            density = LocalDensity.current
            val listState = rememberLazyListState()
            // Intentionally pass an itemCount larger than LazyColumn's totalItemsCount (no trailing spacer)
            KeepLatestVisibleOnImeOpen(listState = listState, itemCount = samplePairs.size + 5)

            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(samplePairs) { (q, a) ->
                            QAPairCard(
                                questionText = q,
                                answerText = a,
                                timestamp = 1700000000000L,
                                audioFiles = null,
                                onReplayClick = null
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        val targetView = requireNotNull(hostView)
        val imePx = with(requireNotNull(density)) { 300.dp.roundToPx() }
        val openInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
            .build()
        rule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(targetView, openInsets)
        }
        rule.waitForIdle()

        // Clamped scroll succeeds without throwing and keeps the last card present
        rule.onNodeWithText("Question 3").assertExists()
    }
}
