package com.slothspeak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for the shared [LlmProviderCatalog]: provider listing, model
 * expansion lists, and switching between built-in and compatible providers.
 *
 * Uses an in-memory [LlmProviderStore] because EncryptedSharedPreferences
 * requires the AndroidKeyStore, which Robolectric cannot provide.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LlmProviderCatalogTest {

    private class FakeStore : LlmProviderStore {
        var selectedModelValue: String = ApiKeyManager.MODEL_ASTRA
        var selectedCompatId: String? = null
        val builtinModels = mutableMapOf<String, String>()
        val builtinKeys = mutableSetOf<String>()
        val compatConfigs = mutableListOf<CompatibleProviderConfig>()
        var reasoningEffortValue = ApiKeyManager.EFFORT_HIGH
        var thinkingLevelValue = ApiKeyManager.THINKING_HIGH
        var claudeEffortValue = ApiKeyManager.CLAUDE_EFFORT_HIGH

        override fun selectedModel(): String = selectedModelValue
        override fun setSelectedModel(model: String) { selectedModelValue = model }

        override fun selectedCompatProviderId(): String? = selectedCompatId
        override fun setSelectedCompatProviderId(id: String?) { selectedCompatId = id }

        override fun builtinModel(providerKey: String): String? = builtinModels[providerKey]
        override fun setBuiltinModel(providerKey: String, model: String) {
            builtinModels[providerKey] = model
        }

        override fun hasBuiltinLlmKey(providerKey: String): Boolean =
            providerKey in builtinKeys

        override fun compatProviders(): List<CompatibleProviderConfig> = compatConfigs.toList()
        override fun compatProvider(id: String): CompatibleProviderConfig? =
            compatConfigs.firstOrNull { it.id == id }

        override fun reasoningEffort(): String = reasoningEffortValue
        override fun thinkingLevel(): String = thinkingLevelValue
        override fun claudeEffort(): String = claudeEffortValue
    }

    private fun storeWithOpenAiAndClaude(): FakeStore = FakeStore().apply {
        builtinKeys += ApiKeyManager.LLM_PROVIDER_OPENAI
        builtinKeys += ApiKeyManager.LLM_PROVIDER_CLAUDE
    }

    // ---------------------------------------------------------------
    // Provider listing
    // ---------------------------------------------------------------

    @Test
    fun getConfiguredProviders_listsBuiltinProvidersWithModels() {
        val catalog = LlmProviderCatalog(storeWithOpenAiAndClaude())

        val providers = catalog.getConfiguredProviders()
        assertEquals(2, providers.size)

        val openAi = providers[0]
        assertEquals("builtin:openai", openAi.key)
        assertEquals("OpenAI", openAi.displayName)
        assertEquals(ApiKeyManager.MODEL_ASTRA, openAi.modelId)
        assertTrue(openAi.active)
        assertEquals(ApiKeyManager.OPENAI_MODELS, openAi.availableModels)
        assertTrue(openAi.supportsThinking)

        val claude = providers[1]
        assertEquals("builtin:claude", claude.key)
        assertEquals(ApiKeyManager.MODEL_CLAUDE_OPUS, claude.modelId)
        assertFalse(claude.active)
        assertEquals(ApiKeyManager.CLAUDE_MODELS, claude.availableModels)
    }

    @Test
    fun getConfiguredProviders_listsCompatProviderWithSingleModel() {
        val store = FakeStore().apply {
            compatConfigs += CompatibleProviderConfig.createDeepSeek("ds-1")
            selectedCompatId = "ds-1"
        }
        val catalog = LlmProviderCatalog(store)

        val providers = catalog.getConfiguredProviders()
        assertEquals(1, providers.size)

        val deepSeek = providers[0]
        assertEquals("compat:ds-1", deepSeek.key)
        assertEquals("DeepSeek", deepSeek.displayName)
        assertEquals("deepseek-v4-flash", deepSeek.modelId)
        assertTrue(deepSeek.isCompat)
        assertTrue(deepSeek.active)
        assertEquals(listOf("deepseek-v4-flash"), deepSeek.availableModels)
        assertEquals("ds-1", deepSeek.compatProviderId)
    }

    @Test
    fun getConfiguredProviders_compatSelectionDeactivatesBuiltins() {
        val store = storeWithOpenAiAndClaude().apply {
            compatConfigs += CompatibleProviderConfig.createZai("zai-1")
            selectedCompatId = "zai-1"
        }
        val catalog = LlmProviderCatalog(store)

        val providers = catalog.getConfiguredProviders()
        assertEquals(3, providers.size)
        assertTrue(providers.first { it.isCompat }.active)
        assertFalse(providers.first { it.type == LlmProviderType.OPENAI }.active)
        assertFalse(providers.first { it.type == LlmProviderType.CLAUDE }.active)
    }

    @Test
    fun getConfiguredProviders_ignoresCompatWithMissingSelection() {
        val store = FakeStore().apply {
            compatConfigs += CompatibleProviderConfig.createZai("zai-1")
            // selectedCompatId stays null
        }
        val catalog = LlmProviderCatalog(store)

        val providers = catalog.getConfiguredProviders()
        assertEquals(1, providers.size)
        assertFalse(providers[0].active)
    }

    @Test
    fun getActiveProvider_returnsActiveOrNull() {
        val catalog = LlmProviderCatalog(storeWithOpenAiAndClaude())
        assertEquals("builtin:openai", catalog.getActiveProvider()?.key)

        val emptyCatalog = LlmProviderCatalog(FakeStore())
        assertNull(emptyCatalog.getActiveProvider())
    }

    // ---------------------------------------------------------------
    // Model switching
    // ---------------------------------------------------------------

    @Test
    fun selectModel_builtinUpdatesGlobalAndFamilyModelAndClearsCompat() {
        val store = storeWithOpenAiAndClaude().apply {
            selectedModelValue = ApiKeyManager.MODEL_ASTRA
            compatConfigs += CompatibleProviderConfig.createDeepSeek("ds-1")
            selectedCompatId = "ds-1"
        }
        val catalog = LlmProviderCatalog(store)

        val openAi = catalog.getConfiguredProviders().first { it.type == LlmProviderType.OPENAI }
        catalog.selectModel(openAi, ApiKeyManager.MODEL_SOL)

        assertEquals(ApiKeyManager.MODEL_SOL, store.selectedModelValue)
        assertEquals(ApiKeyManager.MODEL_SOL, store.builtinModels[ApiKeyManager.LLM_PROVIDER_OPENAI])
        assertNull(store.selectedCompatId)

        val refreshed = catalog.getConfiguredProviders()
        val openAiAfter = refreshed.first { it.type == LlmProviderType.OPENAI }
        assertTrue(openAiAfter.active)
        assertEquals(ApiKeyManager.MODEL_SOL, openAiAfter.modelId)
        assertFalse(refreshed.first { it.isCompat }.active)
    }

    @Test
    fun selectModel_builtinPreservesOtherFamilyModels() {
        val store = storeWithOpenAiAndClaude()
        val catalog = LlmProviderCatalog(store)

        val claude = catalog.getConfiguredProviders().first { it.type == LlmProviderType.CLAUDE }
        catalog.selectModel(claude, ApiKeyManager.MODEL_CLAUDE_OPUS)

        assertEquals(ApiKeyManager.MODEL_CLAUDE_OPUS, store.selectedModelValue)
        assertEquals(
            ApiKeyManager.MODEL_CLAUDE_OPUS,
            store.builtinModels[ApiKeyManager.LLM_PROVIDER_CLAUDE]
        )
        assertFalse(store.builtinModels.containsKey(ApiKeyManager.LLM_PROVIDER_OPENAI))
    }

    @Test
    fun selectModel_compatSetsCompatSelection() {
        val store = storeWithOpenAiAndClaude().apply {
            compatConfigs += CompatibleProviderConfig.createDeepSeek("ds-1")
        }
        val catalog = LlmProviderCatalog(store)

        val deepSeek = catalog.getConfiguredProviders().first { it.isCompat }
        catalog.selectModel(deepSeek, deepSeek.modelId)

        assertEquals("ds-1", store.selectedCompatId)
        // Global model pref is untouched by compat selection
        assertEquals(ApiKeyManager.MODEL_ASTRA, store.selectedModelValue)

        val refreshed = catalog.getConfiguredProviders()
        assertTrue(refreshed.first { it.isCompat }.active)
        assertFalse(refreshed.first { it.type == LlmProviderType.OPENAI }.active)
        assertTrue(catalog.getActiveProvider()?.isCompat == true)
    }

    @Test
    fun activateProvider_activatesWithCurrentModel() {
        val store = storeWithOpenAiAndClaude()
        val catalog = LlmProviderCatalog(store)

        val claude = catalog.getConfiguredProviders().first { it.type == LlmProviderType.CLAUDE }
        catalog.activateProvider(claude)

        assertEquals(ApiKeyManager.MODEL_CLAUDE_OPUS, store.selectedModelValue)
        assertEquals(ApiKeyManager.MODEL_CLAUDE_OPUS, catalog.getActiveProvider()?.modelId)
    }

    // ---------------------------------------------------------------
    // Display formatting
    // ---------------------------------------------------------------

    // ---------------------------------------------------------------
    // Model catalogue (issue #16)
    // ---------------------------------------------------------------

    @Test
    fun modelLists_matchTheVerifiedLatestModels() {
        assertEquals(
            listOf("gpt-6.2-astra", "gpt-6.2-sol", "gpt-6.1-terra", "gpt-6-luna"),
            ApiKeyManager.OPENAI_MODELS
        )
        assertEquals(
            listOf("gemini-4-carbon", "gemini-4-neon", "gemini-4-hydrogen", "gemini-4-argon"),
            ApiKeyManager.GEMINI_MODELS
        )
        assertEquals(
            listOf("claude-opus-5-5", "claude-fable-5-2", "claude-sonnet-5-5", "claude-haiku-5-5"),
            ApiKeyManager.CLAUDE_MODELS
        )
        assertEquals(listOf("grok-4.7"), ApiKeyManager.GROK_MODELS)

        // Deep-research ids stay available to the machinery but are not picker entries.
        assertFalse(ApiKeyManager.OPENAI_MODELS.contains(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertFalse(ApiKeyManager.GEMINI_MODELS.contains(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
    }

    @Test
    fun defaultBuiltinModel_isTheFirstModelOfEachFamily() {
        assertEquals(ApiKeyManager.MODEL_ASTRA, ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_OPENAI))
        assertEquals(
            ApiKeyManager.MODEL_GEMINI_CARBON,
            ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_GEMINI)
        )
        assertEquals(
            ApiKeyManager.MODEL_CLAUDE_OPUS,
            ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_CLAUDE)
        )
        assertEquals(ApiKeyManager.MODEL_GROK, ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_GROK))
        assertEquals("gpt-6.2-astra", ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_OPENAI))
        assertEquals("gemini-4-carbon", ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_GEMINI))
        assertEquals("claude-opus-5-5", ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_CLAUDE))
        assertEquals("grok-4.7", ApiKeyManager.defaultBuiltinModel(ApiKeyManager.LLM_PROVIDER_GROK))
    }

    @Test
    fun familyChecks_recogniseEveryNewModel() {
        ApiKeyManager.OPENAI_MODELS.forEach { assertTrue(it, ApiKeyManager.isOpenAiModel(it)) }
        ApiKeyManager.GEMINI_MODELS.forEach { assertTrue(it, ApiKeyManager.isGeminiModel(it)) }
        ApiKeyManager.CLAUDE_MODELS.forEach { assertTrue(it, ApiKeyManager.isClaudeModel(it)) }
        ApiKeyManager.GROK_MODELS.forEach { assertTrue(it, ApiKeyManager.isGrokModel(it)) }

        assertFalse(ApiKeyManager.isOpenAiModel(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertFalse(ApiKeyManager.isGeminiModel(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
        assertFalse(ApiKeyManager.isClaudeModel(ApiKeyManager.MODEL_GROK))
        assertTrue(ApiKeyManager.isDeepResearchModel(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertTrue(ApiKeyManager.isDeepResearchModel(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
    }

    @Test
    fun thinkingGating_enabledForEveryReasoningModelOnly() {
        ApiKeyManager.OPENAI_MODELS.forEach { model ->
            assertTrue("OpenAI $model should expose effort", ApiKeyManager.supportsOpenAiReasoningEffort(model))
            assertFalse("Gemini $model should not expose Gemini thinking", ApiKeyManager.supportsGeminiThinking(model))
        }
        ApiKeyManager.GEMINI_MODELS.forEach { model ->
            assertTrue("Gemini $model should expose thinking", ApiKeyManager.supportsGeminiThinking(model))
            assertFalse("OpenAI $model should not expose effort", ApiKeyManager.supportsOpenAiReasoningEffort(model))
        }
        assertFalse(ApiKeyManager.supportsOpenAiReasoningEffort(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertFalse(ApiKeyManager.supportsGeminiThinking(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH))
    }

    @Test
    fun getConfiguredProviders_enablesThinkingForEveryOpenAiAndGeminiModel() {
        val store = FakeStore().apply {
            builtinKeys += ApiKeyManager.LLM_PROVIDER_OPENAI
            builtinKeys += ApiKeyManager.LLM_PROVIDER_GEMINI
        }
        val catalog = LlmProviderCatalog(store)

        for (model in ApiKeyManager.OPENAI_MODELS) {
            store.selectedModelValue = model
            val openAi = catalog.getConfiguredProviders().first { it.type == LlmProviderType.OPENAI }
            assertEquals(model, openAi.modelId)
            assertTrue("$model should support thinking", openAi.supportsThinking)
            assertEquals(
                listOf(ApiKeyManager.EFFORT_MEDIUM, ApiKeyManager.EFFORT_HIGH, ApiKeyManager.EFFORT_XHIGH),
                openAi.thinkingLevels
            )
        }
        for (model in ApiKeyManager.GEMINI_MODELS) {
            store.selectedModelValue = model
            val gemini = catalog.getConfiguredProviders().first { it.type == LlmProviderType.GEMINI }
            assertEquals(model, gemini.modelId)
            assertTrue("$model should support thinking", gemini.supportsThinking)
            assertEquals(
                listOf(ApiKeyManager.THINKING_LOW, ApiKeyManager.THINKING_MEDIUM, ApiKeyManager.THINKING_HIGH),
                gemini.thinkingLevels
            )
        }
    }

    @Test
    fun friendlyModelName_mapsKnownModels() {
        assertEquals("GPT-6.2 Astra", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_ASTRA))
        assertEquals("GPT-6.2 Sol", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_SOL))
        assertEquals("GPT-6.1 Terra", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_TERRA))
        assertEquals("GPT-6 Luna", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_LUNA))
        assertEquals("Gemini 4 Carbon", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_CARBON))
        assertEquals("Gemini 4 Neon", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_NEON))
        assertEquals("Gemini 4 Hydrogen", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_HYDROGEN))
        assertEquals("Gemini 4 Argon", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_ARGON))
        assertEquals("Claude Fable 5.2", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_CLAUDE_FABLE))
        assertEquals("Claude Opus 5.5", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_CLAUDE_OPUS))
        assertEquals("Claude Sonnet 5.5", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_CLAUDE_SONNET))
        assertEquals("Claude Haiku 5.5", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_CLAUDE_HAIKU))
        assertEquals("Grok 4.7", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GROK))
        assertEquals("o3 Deep Research", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_DEEP_RESEARCH))
        assertEquals(
            "Gemini Deep Research",
            LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_DEEP_RESEARCH)
        )
    }

    @Test
    fun friendlyModelName_passesThroughUnknownModels() {
        assertEquals("glm-5.3", LlmProviderCatalog.friendlyModelName("glm-5.3"))
    }
}
