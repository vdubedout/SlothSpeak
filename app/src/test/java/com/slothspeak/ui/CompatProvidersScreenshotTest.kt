package com.slothspeak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.ui.components.QAPairCard
import com.slothspeak.ui.screens.CompatProvidersList
import com.slothspeak.ui.screens.SttSection
import com.slothspeak.ui.screens.TextInputQuestionBar
import com.slothspeak.ui.screens.TtsSection
import com.slothspeak.ui.screens.WakeWordSection
import com.slothspeak.ui.theme.SlothSpeakTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi-based screenshot tests for CompatProviders UI.
 * Captures PNG screenshots to app/build/ui-screenshots/ via captureRoboImage.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompatProvidersScreenshotTest {

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            roborazziOptions = com.github.takahirom.roborazzi.RoborazziOptions(
                taskType = RoborazziTaskType.Record
            )
        )
    )

    @get:Rule
    val rule = createComposeRule()

    // ---- Fake fixtures ----

    private val zaiProvider = CompatibleProviderConfig.createZai("zai-1")

    private val customProvider = CompatibleProviderConfig.createCustom("custom-1").copy(
        displayName = "Qwen 3.8 27B",
        baseUrl = "http://192.168.1.100:8000/v1/",
        modelId = "qwen3.8-27b"
    )

    private val reasoningSample = """
The user asks about the weather in Tokyo. First, I need to determine the current date and location.
The query implies a request for real-time information. Since I don't have direct access to live
weather data, I should explain that I'm a language model without browsing capabilities.

Tokyo has a humid subtropical climate with hot, humid summers and mild to cool winters.
The average temperature in summer ranges from 25-30°C, while winter temperatures average around
5-12°C. The city experiences significant rainfall throughout the year, with higher precipitation
during the rainy season in June and July.

I recommend checking a weather service for current conditions. However, for general travel planning
purposes, spring (March-May) and autumn (September-November) are considered the best times to visit
Tokyo with mild temperatures and lower rainfall.

Additional analysis: The user may be planning a trip to Tokyo. I should provide practical advice
about seasonal clothing, peak tourist seasons, and indoor versus outdoor activities based on the
weather patterns. The rainy season (tsuyu) in June-July can significantly affect sightseeing plans.
Typhoon season from August to October may also be relevant for travel planning purposes. The
best advice would be to recommend visiting during spring cherry blossom season or autumn when
the weather is most favorable and the city's parks and gardens are at their most beautiful.
    """.trimIndent()

    // ========================
    //  Test 1: Compat Providers Settings Section
    // ========================

    @Test
    fun settings_compatProvidersSection_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "OpenAI-Compatible Providers",
                            style = MaterialTheme.typography.titleMedium,
                            color = com.slothspeak.ui.theme.AccentGreen
                        )
                        CompatProvidersList(
                            providers = listOf(zaiProvider, customProvider),
                            onProviderEdit = {},
                            onProviderDelete = {}
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // Semantics assertions
        rule.onNodeWithText("Z.ai").assertIsDisplayed()
        rule.onNodeWithText("glm-5.3 · https://api.z.ai/api/paas/v4/").assertIsDisplayed()
        rule.onNodeWithText("Qwen 3.8 27B").assertIsDisplayed()
        rule.onNodeWithText("qwen3.8-27b · http://192.168.1.100:8000/v1/").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/settings_compatProvidersSection.png")
    }

    // ========================
    //  Test 2: QAPairCard — Thinking Collapsed
    // ========================

    @Test
    fun qaPairCard_thinkingCollapsed_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What's the weather like in Tokyo?",
                        answerText = "Tokyo has a humid subtropical climate. Spring and autumn are the best times to visit.",
                        timestamp = 1700000000000L,
                        audioFiles = null,
                        onReplayClick = null,
                        model = "glm-5.3",
                        reasoningEffort = "high",
                        llmResponseSeconds = 42,
                        reasoningText = reasoningSample
                    )
                }
            }
        }
        rule.waitForIdle()

        // Assert collapsed — only "View thinking" toggle visible
        rule.onNodeWithText("View thinking").assertIsDisplayed()
        rule.onNodeWithText("42 seconds").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/qaPairCard_thinkingCollapsed.png")
    }

    // ========================
    //  Test 3: QAPairCard — Thinking Expanded
    // ========================

    @Test
    fun qaPairCard_thinkingExpanded_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What's the weather like in Tokyo?",
                        answerText = "Tokyo has a humid subtropical climate. Spring and autumn are the best times to visit.",
                        timestamp = 1700000000000L,
                        audioFiles = null,
                        onReplayClick = null,
                        model = "glm-5.3",
                        reasoningEffort = "high",
                        llmResponseSeconds = 42,
                        reasoningText = reasoningSample
                    )
                }
            }
        }
        rule.waitForIdle()

        // Click to expand
        rule.onNodeWithText("View thinking").performClick()
        rule.waitForIdle()

        // Assert expanded — "Hide thinking" toggle visible
        rule.onNodeWithText("Hide thinking").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/qaPairCard_thinkingExpanded.png")
    }
    // ========================
    //  Test 4: TextInputQuestionBar — Text-only mode (no voice)
    // ========================

    @Test
    fun mainScreen_textInputMode_png() {
        var submittedText = ""
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    TextInputQuestionBar(
                        onQuestionSubmit = { submittedText = it },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        rule.waitForIdle()

        // Semantics assertions
        rule.onNodeWithText("Voice input needs a speech-to-text provider — Settings").assertIsDisplayed()
        rule.onNodeWithText("Type your question…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()

        // Type text and submit
        rule.onNodeWithText("Type your question…").performTextInput("What is the weather?")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitForIdle()

        // Assert callback fired with the question
        assert(submittedText == "What is the weather?") { "Expected question to be submitted" }

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/mainScreen_textInputMode.png")
    }

    // ========================
    //  Test 5: STT Settings Section
    // ========================

    @Test
    fun settings_sttSection_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Speech-to-Text",
                            style = MaterialTheme.typography.titleMedium,
                            color = com.slothspeak.ui.theme.AccentGreen
                        )
                        SttSection(
                            sttProvider = "together",
                            hasSttApiKey = true,
                            sttModel = "openai/whisper-large-v3",
                            sttBaseUrl = "https://api.together.ai/v1/",
                            sttKeySaved = true,
                            onSetProvider = {},
                            onSaveApiKey = {},
                            onDeleteApiKey = {},
                            onSetModel = {},
                            onSetBaseUrl = {}
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // Semantics assertions — three provider rows
        rule.onNodeWithText("OpenAI (gpt-4o-transcribe)").assertIsDisplayed()
        rule.onNodeWithText("Together (whisper-large-v3)").assertIsDisplayed()
        rule.onNodeWithText("Custom (OpenAI-compatible)").assertIsDisplayed()

        // Key-saved indicator
        rule.onNodeWithText("API key is saved").assertIsDisplayed()
        rule.onNodeWithText("Key saved successfully!").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/settings_sttSection.png")
    }

    // ========================
    //  Test 6: TTS Settings Section
    // ========================

    @Test
    fun settings_ttsSection_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Text-to-Speech",
                            style = MaterialTheme.typography.titleMedium,
                            color = com.slothspeak.ui.theme.AccentGreen
                        )
                        TtsSection(
                            ttsProvider = "together",
                            ttsModel = "canopylabs/orpheus-3b-0.1-ft",
                            ttsVoice = "tara",
                            ttsBaseUrl = "https://api.together.ai/v1/",
                            hasTtsCustomApiKey = false,
                            ttsKeySaved = false,
                            onSetProvider = {},
                            onSetModel = {},
                            onSetVoice = {},
                            onSetBaseUrl = {},
                            onSaveCustomApiKey = {},
                            onDeleteCustomApiKey = {}
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // Semantics assertions — four provider rows
        rule.onNodeWithText("OpenAI (gpt-4o-mini-tts)").assertIsDisplayed()
        rule.onNodeWithText("Together (Orpheus)").assertIsDisplayed()
        rule.onNodeWithText("Custom (OpenAI-compatible)").assertIsDisplayed()
        rule.onNodeWithText("On-device (Android system TTS)").assertIsDisplayed()

        // Together selected — model and voice fields should be visible
        rule.onNodeWithText("Model").assertIsDisplayed()
        rule.onNodeWithText("Voice").assertIsDisplayed()
        rule.onNodeWithText("Uses your Together.ai API key from Speech-to-Text.").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/settings_ttsSection.png")
    }

    // ========================
    //  Test 7: Wake Word Settings Section
    // ========================

    @Test
    fun settings_wakeWordSection_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Wake Word",
                            style = MaterialTheme.typography.titleMedium,
                            color = com.slothspeak.ui.theme.AccentGreen
                        )
                        WakeWordSection(
                            wakeWordEnabled = false,
                            onToggle = {}
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // Semantics assertions
        rule.onNodeWithText("Hey Jarvis wake word").assertIsDisplayed()

        // PNG capture
        rule.onRoot().captureRoboImage("build/ui-screenshots/settings_wakeWordSection.png")
    }
}