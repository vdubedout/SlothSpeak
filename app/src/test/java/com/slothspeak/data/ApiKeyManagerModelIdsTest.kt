package com.slothspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the built-in model catalogue constants and the legacy-id
 * migration helpers (issue #16). No Android framework needed.
 */
class ApiKeyManagerModelIdsTest {

    @Test
    fun modelLists_containTheVerifiedLatestIds() {
        assertEquals(
            listOf("gpt-6.2-astra", "gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna"),
            ApiKeyManager.OPENAI_MODELS
        )
        assertEquals(
            listOf(
                "gemini-4-carbon",
                "gemini-4-neon",
                "gemini-4-hydrogen",
                "gemini-4-argon",
                "deep-research-max-preview-04-2026"
            ),
            ApiKeyManager.GEMINI_MODELS
        )
        assertEquals(
            listOf("claude-opus-5-5", "claude-fable-5-2", "claude-sonnet-5-5", "claude-haiku-5-5"),
            ApiKeyManager.CLAUDE_MODELS
        )
        assertEquals(listOf("grok-4.7"), ApiKeyManager.GROK_MODELS)
        // OpenAI retired its deep-research model; only the four chat models are offered.
        assertEquals(4, ApiKeyManager.OPENAI_MODELS.size)
        assertFalse(ApiKeyManager.OPENAI_MODELS.contains(ApiKeyManager.MODEL_DEEP_RESEARCH))
    }

    @Test
    fun deepResearchConstants_areUnchanged() {
        assertEquals("o3-deep-research", ApiKeyManager.MODEL_DEEP_RESEARCH)
        assertEquals("deep-research-max-preview-04-2026", ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH)
    }

    @Test
    fun migration_mapsRemovedIdsToTheirFamilyDefault() {
        assertEquals(ApiKeyManager.MODEL_ASTRA, ApiKeyManager.migrateLegacyModelId("gpt-5.5-pro"))
        assertEquals(ApiKeyManager.MODEL_ASTRA, ApiKeyManager.migrateLegacyModelId("gpt-5.5"))
        assertEquals(
            ApiKeyManager.MODEL_GEMINI_CARBON,
            ApiKeyManager.migrateLegacyModelId("gemini-3.1-pro-preview")
        )
        assertEquals(
            ApiKeyManager.MODEL_GEMINI_CARBON,
            ApiKeyManager.migrateLegacyModelId("gemini-3.5-pro-preview")
        )
        assertEquals(ApiKeyManager.MODEL_CLAUDE_OPUS, ApiKeyManager.migrateLegacyModelId("claude-opus-4-8"))
        assertEquals(ApiKeyManager.MODEL_GROK, ApiKeyManager.migrateLegacyModelId("grok-4.3"))
    }

    @Test
    fun migration_leavesCurrentAndDeepResearchIdsUntouched() {
        ApiKeyManager.OPENAI_MODELS.forEach {
            assertEquals(it, ApiKeyManager.migrateLegacyModelId(it))
        }
        ApiKeyManager.GEMINI_MODELS.forEach {
            assertEquals(it, ApiKeyManager.migrateLegacyModelId(it))
        }
        ApiKeyManager.CLAUDE_MODELS.forEach {
            assertEquals(it, ApiKeyManager.migrateLegacyModelId(it))
        }
        assertEquals(
            ApiKeyManager.MODEL_DEEP_RESEARCH,
            ApiKeyManager.migrateLegacyModelId(ApiKeyManager.MODEL_DEEP_RESEARCH)
        )
        assertEquals(
            ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH,
            ApiKeyManager.migrateLegacyModelId(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH)
        )
        // Compat / custom ids pass through unchanged.
        assertEquals("glm-5.3", ApiKeyManager.migrateLegacyModelId("glm-5.3"))
        assertNull(ApiKeyManager.migrateLegacyModelId(null))
    }

    @Test
    fun geminiDeepResearch_staysSelectableWhileOpenAiDeepResearchDoesNot() {
        assertFalse(ApiKeyManager.OPENAI_MODELS.contains(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertTrue(ApiKeyManager.GEMINI_MODELS.contains(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
        assertTrue(ApiKeyManager.isDeepResearchModel(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertTrue(ApiKeyManager.isDeepResearchModel(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
        // Neither deep-research agent exposes thinking/effort levels.
        assertFalse(ApiKeyManager.supportsOpenAiReasoningEffort(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertFalse(ApiKeyManager.supportsGeminiThinking(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
    }
}
