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
        var selectedModelValue: String = ApiKeyManager.MODEL_PRO
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
        assertEquals(ApiKeyManager.MODEL_PRO, openAi.modelId)
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
            selectedModelValue = ApiKeyManager.MODEL_PRO
            compatConfigs += CompatibleProviderConfig.createDeepSeek("ds-1")
            selectedCompatId = "ds-1"
        }
        val catalog = LlmProviderCatalog(store)

        val openAi = catalog.getConfiguredProviders().first { it.type == LlmProviderType.OPENAI }
        catalog.selectModel(openAi, ApiKeyManager.MODEL_STANDARD)

        assertEquals(ApiKeyManager.MODEL_STANDARD, store.selectedModelValue)
        assertEquals(ApiKeyManager.MODEL_STANDARD, store.builtinModels[ApiKeyManager.LLM_PROVIDER_OPENAI])
        assertNull(store.selectedCompatId)

        val refreshed = catalog.getConfiguredProviders()
        val openAiAfter = refreshed.first { it.type == LlmProviderType.OPENAI }
        assertTrue(openAiAfter.active)
        assertEquals(ApiKeyManager.MODEL_STANDARD, openAiAfter.modelId)
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
        assertEquals(ApiKeyManager.MODEL_PRO, store.selectedModelValue)

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

    @Test
    fun friendlyModelName_mapsKnownModels() {
        assertEquals("GPT-5.5", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_STANDARD))
        assertEquals("GPT-5.5 Pro", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_PRO))
        assertEquals("Claude Opus 4.8", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_CLAUDE_OPUS))
        assertEquals("Gemini 3.1 Pro", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GEMINI_PRO))
        assertEquals("Grok 4.3", LlmProviderCatalog.friendlyModelName(ApiKeyManager.MODEL_GROK))
    }

    @Test
    fun friendlyModelName_passesThroughUnknownModels() {
        assertEquals("glm-5.3", LlmProviderCatalog.friendlyModelName("glm-5.3"))
    }
}
