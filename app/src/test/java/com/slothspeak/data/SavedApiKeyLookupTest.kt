package com.slothspeak.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for the saved-API-key lookup that prefills the LLM provider
 * dialog. The helper intentionally has no SharedPreferences dependency.
 */
class SavedApiKeyLookupTest {

    private val zaiOne = SavedProviderKey(
        providerId = "zai-1",
        baseUrl = "https://api.z.ai/api/paas/v4/",
        apiKey = "zai-key-with-1"
    )
    private val zaiTwo = SavedProviderKey(
        providerId = "zai-2",
        baseUrl = "https://api.z.ai/api/paas/v4/",
        apiKey = "zai-key-with-2"
    )
    private val deepSeek = SavedProviderKey(
        providerId = "deepseek-1",
        baseUrl = "https://api.deepseek.com/v1/",
        apiKey = "deepseek-key"
    )

    private val openAiBuiltin = mapOf(ApiKeyManager.LLM_PROVIDER_OPENAI to "sk-builtin-openai")

    // ============================================================
    // URL normalisation
    // ============================================================

    @Test
    fun normalize_trimsLowercasesAndStripsTrailingSlash() {
        assertEquals(
            "https://api.z.ai/api/paas/v4",
            SavedApiKeyLookup.normalizeBaseUrl("  HTTPS://API.Z.ai/api/paas/v4/  ")
        )
    }

    @Test
    fun normalize_defaultsMissingSchemeToHttps() {
        assertEquals(
            "https://api.deepseek.com/v1",
            SavedApiKeyLookup.normalizeBaseUrl("api.deepseek.com/v1/")
        )
    }

    @Test
    fun normalize_dropsDefaultPortsAndQueryOrFragment() {
        assertEquals(
            "https://api.example.com/v1",
            SavedApiKeyLookup.normalizeBaseUrl("https://api.example.com:443/v1/?key=abc#top")
        )
        assertEquals(
            "http://localhost:8000/v1",
            SavedApiKeyLookup.normalizeBaseUrl("http://localhost:8000/v1/")
        )
    }

    @Test
    fun normalize_collapsesDuplicateSlashesAndBlankInput() {
        assertEquals(
            "https://api.example.com/v1/models",
            SavedApiKeyLookup.normalizeBaseUrl("https://api.example.com//v1//models//")
        )
        assertEquals("", SavedApiKeyLookup.normalizeBaseUrl("   "))
    }

    // ============================================================
    // Precedence
    // ============================================================

    @Test
    fun editedProvider_prefillsItsOwnStoredKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(
                baseUrl = "https://api.z.ai/api/paas/v4/",
                compatProviderId = zaiTwo.providerId
            ),
            compatKeys = listOf(zaiOne, zaiTwo),
            builtinKeys = emptyMap()
        )
        assertEquals("zai-key-with-2", key)
    }

    @Test
    fun secondProviderOnSameEndpoint_prefillsFirstProviderKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.z.ai/api/paas/v4/"),
            compatKeys = listOf(zaiOne),
            builtinKeys = emptyMap()
        )
        assertEquals("zai-key-with-1", key)
    }

    @Test
    fun trailingSlashAndCaseDifferencesStillMatch() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "HTTPS://API.Z.AI/api/paas/v4"),
            compatKeys = listOf(zaiOne),
            builtinKeys = emptyMap()
        )
        assertEquals("zai-key-with-1", key)
    }

    @Test
    fun trailingV1MismatchOnSameHostMatches() {
        val custom = SavedProviderKey(
            providerId = "custom-1",
            baseUrl = "https://llm.example.com/",
            apiKey = "custom-key"
        )
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://llm.example.com/v1/"),
            compatKeys = listOf(custom),
            builtinKeys = emptyMap()
        )
        assertEquals("custom-key", key)
    }

    @Test
    fun editedProvider_doesNotKeepOldKeyAfterBaseUrlChanges() {
        // Provider zai-1 now points at a different service: its key must not leak.
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(
                baseUrl = "https://api.deepseek.com/v1/",
                compatProviderId = zaiOne.providerId
            ),
            compatKeys = listOf(zaiOne),
            builtinKeys = emptyMap()
        )
        assertNull(key)
    }

    @Test
    fun ownKeyWinsOverAnotherProviderOnSameEndpoint() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(
                baseUrl = "https://api.z.ai/api/paas/v4/",
                compatProviderId = zaiTwo.providerId
            ),
            compatKeys = listOf(zaiOne, zaiTwo),
            builtinKeys = emptyMap()
        )
        assertEquals("zai-key-with-2", key)
    }

    @Test
    fun compatProviderOnBuiltinEndpoint_winsOverBuiltinPreset() {
        val compatOnOpenAi = SavedProviderKey(
            providerId = "custom-openai",
            baseUrl = "https://api.openai.com/v1/",
            apiKey = "sk-from-compat"
        )
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.openai.com/v1/"),
            compatKeys = listOf(compatOnOpenAi),
            builtinKeys = openAiBuiltin
        )
        assertEquals("sk-from-compat", key)
    }

    // ============================================================
    // Built-in endpoints
    // ============================================================

    @Test
    fun compatProviderOnOpenAiEndpoint_prefillsBuiltinOpenAiKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.openai.com/v1/"),
            compatKeys = emptyList(),
            builtinKeys = openAiBuiltin
        )
        assertEquals("sk-builtin-openai", key)
    }

    @Test
    fun compatProviderOnTogetherEndpoint_prefillsTogetherKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.together.ai/v1/"),
            compatKeys = emptyList(),
            builtinKeys = mapOf(ApiKeyManager.STT_PROVIDER_TOGETHER to "together-key")
        )
        assertEquals("together-key", key)
    }

    @Test
    fun editingBuiltinProvider_prefillsItsOwnKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(
                baseUrl = "",
                builtinProviderKey = ApiKeyManager.LLM_PROVIDER_OPENAI
            ),
            compatKeys = listOf(zaiOne),
            builtinKeys = openAiBuiltin
        )
        assertEquals("sk-builtin-openai", key)
    }

    // ============================================================
    // No matches
    // ============================================================

    @Test
    fun differentEndpoint_returnsNoKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.deepseek.com/v1/"),
            compatKeys = listOf(zaiOne),
            builtinKeys = openAiBuiltin
        )
        assertNull(key)
    }

    @Test
    fun samePathOnDifferentHost_returnsNoKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.z.ai.example.com/api/paas/v4/"),
            compatKeys = listOf(zaiOne),
            builtinKeys = emptyMap()
        )
        assertNull(key)
    }

    @Test
    fun blankBaseUrlWithoutProvider_returnsNoKey() {
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = ""),
            compatKeys = listOf(zaiOne, deepSeek),
            builtinKeys = openAiBuiltin
        )
        assertNull(key)
    }

    @Test
    fun blankStoredKeysAreIgnored() {
        val blankKeyProvider = SavedProviderKey(
            providerId = "zai-blank",
            baseUrl = "https://api.z.ai/api/paas/v4/",
            apiKey = "   "
        )
        val key = SavedApiKeyLookup.findSavedKey(
            target = SavedKeyTarget(baseUrl = "https://api.z.ai/api/paas/v4/"),
            compatKeys = listOf(blankKeyProvider),
            builtinKeys = emptyMap()
        )
        assertNull(key)
    }
}
