package com.slothspeak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.slothspeak.data.HostAuthProfile
import com.slothspeak.data.HttpWritePolicy
import com.slothspeak.data.ToolsSettingsState
import com.slothspeak.service.SlothSpeakService
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationRequest
import com.slothspeak.tools.ToolTraceEntry
import com.slothspeak.ui.components.QAPairCard
import com.slothspeak.ui.components.StatusIndicator
import com.slothspeak.ui.components.ToolConfirmationDialog
import com.slothspeak.ui.screens.ToolsSettingsSection
import com.slothspeak.ui.theme.SlothSpeakTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for the tool integration (issue #6): the Settings → Tools section, the
 * HTTP write-confirmation dialog, and the persisted tool trace on a Q&A card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsUiScreenshotTest {

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

    private val configuredTools = ToolsSettingsState(
        braveSearchEnabled = true,
        braveApiKeyConfigured = true,
        httpRequestEnabled = true,
        httpWritePolicy = HttpWritePolicy.ASK,
        allowedWriteHosts = listOf("www.wandub.com"),
        hostAuthProfiles = listOf(
            HostAuthProfile(
                id = "profile-1",
                host = "www.wandub.com",
                headerName = "Authorization",
                headerValue = "Bearer this-secret-must-never-be-rendered"
            )
        )
    )

    private val writeRequest = ToolConfirmationRequest(
        toolName = "http_request",
        method = "POST",
        url = "https://www.wandub.com/api/pages",
        host = "www.wandub.com",
        // Values are already redacted by HttpRequestPlugin.redactHeaders().
        headers = linkedMapOf(
            "Authorization" to "••••••",
            "Content-Type" to "application/json"
        ),
        bodyPreview = BODY_PREVIEW
    )

    private val toolTrace = listOf(
        ToolTraceEntry(
            name = "brave_web_search",
            argumentsJson = """{"query": "WanDub wiki API", "count": 3}""",
            resultPreview = "1. WanDub Otter Wiki (3 days ago)\n   URL: https://www.wandub.com/\n" +
                "   Description: Community wiki for WanDub projects.\n" +
                "2. Otter Wiki API (1 week ago)\n   URL: https://www.wandub.com/api",
            isError = false,
            durationMs = 842L
        ),
        ToolTraceEntry(
            name = "http_request",
            argumentsJson = """{"method": "GET", "url": "https://www.wandub.com/api/pages"}""",
            resultPreview = "HTTP 200\nContent-Type: application/json\n\n" +
                """{"pages": [{"title": "Home", "path": "Home"}, {"title": "Ai", "path": "Home/Ai"}]}""",
            isError = false,
            durationMs = 315L
        )
    )

    @Composable
    private fun ToolsSection(state: ToolsSettingsState) {
        SlothSpeakTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ToolsSettingsSection(
                        state = state,
                        onToggleBraveSearch = {},
                        onSaveBraveApiKey = {},
                        onDeleteBraveApiKey = {},
                        onToggleHttpRequest = {},
                        onSetHttpWritePolicy = {},
                        onRemoveAllowedWriteHost = {},
                        onResetAllowedWriteHosts = {},
                        onSaveHostAuthProfile = { _, _, _ -> },
                        onDeleteHostAuthProfile = {}
                    )
                }
            }
        }
    }

    // ========================
    //  1. Settings → Tools (configured)
    // ========================

    @Test
    fun toolsSettings_configured_png() {
        rule.setContent { ToolsSection(configuredTools) }
        rule.waitForIdle()

        // Brave card
        rule.onNodeWithText("Tools").assertExists()
        rule.onNodeWithText("Brave Web Search").assertExists()
        rule.onNodeWithText("API key configured").assertExists()
        rule.onNodeWithText("Update API key").assertExists()
        rule.onNodeWithContentDescription("Delete Brave API key").assertExists()

        // HTTP card: enabled, ask-before-write selected, one allowed host, one redacted profile
        rule.onNodeWithText("HTTP Request (curl)").assertExists()
        rule.onNodeWithText("Ask before write").assertExists()
        rule.onNodeWithText("Always allow writes").assertExists()
        rule.onNodeWithText("Always-allowed hosts").assertExists()
        // Once as an always-allowed host, once as the host of the auth profile
        rule.onAllNodesWithText("www.wandub.com").assertCountEquals(2)
        rule.onNodeWithText("Reset all").assertExists()
        rule.onNodeWithText("Host Auth Profiles").assertExists()
        rule.onNodeWithText("Authorization: ••••••").assertExists()
        rule.onNodeWithContentDescription("Delete auth profile for www.wandub.com").assertExists()
        rule.onNodeWithText("Add auth profile").assertExists()

        // The secret header value is never rendered anywhere
        rule.onAllNodesWithText("Bearer this-secret", substring = true).assertCountEquals(0)

        rule.onRoot().captureRoboImage("build/ui-screenshots/toolsSettings_configured.png")
    }

    @Test
    fun toolsSettings_defaults_png() {
        rule.setContent { ToolsSection(ToolsSettingsState()) }
        rule.waitForIdle()

        rule.onNodeWithText("API key required").assertExists()
        rule.onNodeWithText("Add API key").assertExists()
        rule.onNodeWithContentDescription("Delete Brave API key").assertDoesNotExist()
        rule.onNodeWithText("Always-allowed hosts").assertDoesNotExist()
        rule.onNodeWithText("Add auth profile").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/toolsSettings_defaults.png")
    }

    // ========================
    //  2. Tool write-confirmation dialog
    // ========================

    @Test
    fun toolConfirmationDialog_httpWrite_png() {
        var decision: ToolConfirmationDecision? = null
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {}
                ToolConfirmationDialog(request = writeRequest, onDecision = { decision = it })
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Allow HTTP POST?").assertExists()
        rule.onNodeWithText(writeRequest.url).assertExists()
        rule.onNodeWithText("Headers").assertExists()
        rule.onNodeWithText("Authorization").assertExists()
        rule.onNodeWithText("••••••").assertExists()
        rule.onNodeWithText("Content-Type").assertExists()
        rule.onNodeWithText("application/json").assertExists()
        rule.onNodeWithText("Body").assertExists()
        rule.onNodeWithText(BODY_PREVIEW).assertExists()

        // All four decisions are offered
        rule.onNodeWithText("Allow once").assertExists()
        rule.onNodeWithText("Always allow www.wandub.com").assertExists()
        rule.onNodeWithText("Always allow all HTTP writes").assertExists()
        rule.onNodeWithText("Deny").assertExists()

        // The dialog lives in its own window; Roborazzi captures the whole screen for it.
        rule.onNodeWithText("Allow HTTP POST?").captureRoboImage("build/ui-screenshots/toolConfirmationDialog_httpWrite.png")

        // Deny is reported back to the service (fail closed)
        rule.onNodeWithText("Deny").performClick()
        rule.waitForIdle()
        assertEquals(ToolConfirmationDecision.DENY, decision)
    }

    @Test
    fun toolConfirmationDialog_eachActionReportsItsDecision() {
        val decisions = mutableListOf<ToolConfirmationDecision>()
        var request: ToolConfirmationRequest = writeRequest
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {}
                ToolConfirmationDialog(request = request, onDecision = {
                    decisions.add(it)
                    // Keep the dialog up so every action can be exercised in one go.
                    request = writeRequest
                })
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Always allow www.wandub.com").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Always allow all HTTP writes").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Deny").performClick()
        rule.waitForIdle()

        assertEquals(
            listOf(
                ToolConfirmationDecision.ALLOW_ONCE,
                ToolConfirmationDecision.ALWAYS_ALLOW_HOST,
                ToolConfirmationDecision.ALWAYS_ALLOW_ALL,
                ToolConfirmationDecision.DENY
            ),
            decisions
        )
    }

    // ========================
    //  3. Q&A card with tool trace
    // ========================

    @Test
    fun qaPairCard_withToolTrace_png() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What pages does the WanDub wiki have?",
                        answerText = "The wiki currently has two pages: Home and Home/Ai. " +
                            "I found this by searching the web and reading the wiki's page API.",
                        timestamp = 1700000000000L,
                        audioFiles = null,
                        onReplayClick = null,
                        model = "glm-5.3",
                        reasoningEffort = "high",
                        llmResponseSeconds = 9,
                        toolTrace = toolTrace
                    )
                }
            }
        }
        rule.waitForIdle()

        // Collapsed by default
        rule.onNodeWithText("Tools used (2)").assertExists()
        rule.onNodeWithContentDescription("Show tool trace").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Hide tools").assertExists()
        rule.onNodeWithText("1. Brave Web Search").assertExists()
        rule.onNodeWithText("2. HTTP Request").assertExists()
        rule.onAllNodesWithText("OK").assertCountEquals(2)
        rule.onNodeWithText("842ms").assertExists()
        rule.onNodeWithText("315ms").assertExists()
        rule.onNodeWithText("query=\"WanDub wiki API\" · count=3").assertExists()
        rule.onNodeWithText("GET https://www.wandub.com/api/pages").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/qaPairCard_withToolTrace.png")
    }

    @Test
    fun qaPairCard_withoutToolTrace_hidesSection() {
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What is the capital of France?",
                        answerText = "Paris.",
                        timestamp = 1700000000000L,
                        audioFiles = null,
                        onReplayClick = null
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Tools used (0)").assertDoesNotExist()
        rule.onAllNodesWithText("Tools used", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Hide tools").assertCountEquals(0)
    }

    // ========================
    //  4. Status indicator while a tool runs / awaits approval
    // ========================

    @Test
    fun statusIndicator_usingTool_png() {
        val state = SlothSpeakService.PipelineState.UsingTool(
            questionText = "What pages does the WanDub wiki have?",
            toolName = "brave_web_search",
            summary = "Searching web: \"WanDub wiki API\"",
            elapsedSeconds = 4
        )
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    StatusIndicator(
                        pipelineState = state,
                        onRetry = {},
                        onCancel = {},
                        onToggleMute = {}
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Searching web: \"WanDub wiki API\"").assertExists()
        rule.onNodeWithText("4s elapsed").assertExists()
        rule.onNodeWithContentDescription("Cancel").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/statusIndicator_usingTool.png")
    }

    @Test
    fun statusIndicator_awaitingToolConfirmation_png() {
        val state = SlothSpeakService.PipelineState.AwaitingToolConfirmation(
            questionText = "Create a wiki page",
            request = writeRequest
        )
        rule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    StatusIndicator(
                        pipelineState = state,
                        onRetry = {},
                        onCancel = {},
                        onToggleMute = {}
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Waiting for approval: POST www.wandub.com").assertExists()
        rule.onNodeWithContentDescription("Cancel").assertExists()

        rule.onRoot().captureRoboImage("build/ui-screenshots/statusIndicator_awaitingToolConfirmation.png")
    }

    private companion object {
        const val BODY_PREVIEW = """{"title": "Notes", "body": "Created from SlothSpeak"}"""
    }
}
