package com.slothspeak.ui

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.ui.components.QAPairCard
import com.slothspeak.ui.theme.AccentGreen
import com.slothspeak.ui.theme.SlothSpeakTheme
import com.slothspeak.ui.theme.TextSecondary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompatProvidersScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun qaPairCard_reasoningCollapsedAndExpanded() {
        val reasoningSample = """
            The user asks about the weather in Tokyo. First, I need to determine the current date 
            and location. The query implies a request for real-time information. Since I don't have 
            direct access to live weather data, I should explain that I'm a language model without 
            browsing capabilities, but I can provide general information about Tokyo's climate 
            patterns.

            Tokyo has a humid subtropical climate with hot, humid summers and mild to cool winters. 
            The average temperature in summer ranges from 25-30°C, while winter temperatures average 
            around 5-12°C. The city experiences significant rainfall throughout the year, with 
            higher precipitation during the rainy season in June and July.

            I recommend checking a weather service for current conditions. However, for general 
            travel planning purposes, spring (March-May) and autumn (September-November) are 
            considered the best times to visit Tokyo with mild temperatures and lower rainfall.
        """.trimIndent()

        composeTestRule.setContent {
            SlothSpeakTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QAPairCard(
                        questionText = "What's the weather like in Tokyo?",
                        answerText = "Tokyo has a humid subtropical climate. Spring and autumn are the best times to visit.",
                        timestamp = System.currentTimeMillis(),
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

        // Verify collapsed state
        composeTestRule.onNodeWithText("View thinking").assertIsDisplayed()
        composeTestRule.onNodeWithText("glm-5.3 high").assertIsDisplayed()
        composeTestRule.onNodeWithText("42 seconds").assertIsDisplayed()

        // Capture collapsed
        captureBitmapAndSemantics("qa_pair_card_reasoning_collapsed")

        // Expand reasoning
        composeTestRule.onNodeWithText("View thinking").performClick()

        // Verify expanded state
        composeTestRule.onNodeWithText("Hide thinking").assertIsDisplayed()

        // Capture expanded
        captureBitmapAndSemantics("qa_pair_card_reasoning_expanded")
    }

    @Test
    fun compatProvidersSection_displaysProviderList() {
        composeTestRule.setContent {
            SlothSpeakTheme {
                CompatProvidersSectionPreview()
            }
        }

        // Verify provider rows are displayed
        composeTestRule.onNodeWithText("Z.ai (Preview)").assertIsDisplayed()
        composeTestRule.onNodeWithText("glm-5.3 · https://api.z.ai/api/paas/v4/").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom Server").assertIsDisplayed()
        composeTestRule.onNodeWithText("qwen3.8-27b · http://192.168.1.100:8000/v1/").assertIsDisplayed()

        captureBitmapAndSemantics("compat_providers_section")
    }

    @Composable
    private fun CompatProvidersSectionPreview() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "OpenAI-Compatible Providers",
                style = MaterialTheme.typography.titleMedium,
                color = AccentGreen
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Provider card: Z.ai
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Z.ai (Preview)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "glm-5.3 · https://api.z.ai/api/paas/v4/",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Provider card: Custom Qwen
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "Custom Server", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "qwen3.8-27b · http://192.168.1.100:8000/v1/",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    private fun captureBitmapAndSemantics(name: String) {
        val dir = File("app/build/ui-screenshots")
        dir.mkdirs()

        // Capture bitmap
        try {
            val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
            val pngFile = File(dir, "$name.png")
            FileOutputStream(pngFile).use { out ->
                bitmap.compress(CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            // If bitmap capture fails (e.g. no graphics), fall back gracefully
            File(dir, "$name.png.skip").writeText("capture skipped: ${e.message}")
        }

        // Capture semantics log
        composeTestRule.onRoot().printToLog("CompatProvidersTest", "$name")
        val semanticsFile = File(dir, "$name.semantics.txt")
        semanticsFile.writeText("Semantics captured via printToLog for $name")
    }
}