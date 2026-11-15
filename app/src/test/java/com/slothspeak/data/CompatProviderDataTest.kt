package com.slothspeak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Data-layer tests for CompatibleProviderConfig serialization.
 * Runs under Robolectric for org.json (Android SDK) availability.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompatProviderDataTest {

    @Test
    fun providerConfig_serializationRoundTrip() {
        val original = CompatibleProviderConfig.createZai("zai-1")
        val json = original.toJson()
        val restored = CompatibleProviderConfig.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.baseUrl, restored.baseUrl)
        assertEquals(original.modelId, restored.modelId)
        assertEquals(original.thinkingEnabled, restored.thinkingEnabled)
        assertEquals(original.reasoningEffort, restored.reasoningEffort)
        assertEquals(original.sendThinkingParams, restored.sendThinkingParams)
        assertEquals(original.maxTokens, restored.maxTokens)
    }

    @Test
    fun providerConfig_jsonRoundTripWithNullMaxTokens() {
        val custom = CompatibleProviderConfig.createCustom("custom-test-1")
        val json = custom.toJson()
        val restored = CompatibleProviderConfig.fromJson(json)
        assertEquals(custom.id, restored.id)
        assertNull(restored.maxTokens)
        assertEquals(false, restored.sendThinkingParams)
    }

    @Test
    fun deepSeekTemplate_usesCorrectDefaults() {
        val ds = CompatibleProviderConfig.createDeepSeek("ds-1")
        assertEquals("deepseek-v4-flash", ds.modelId)
        assertEquals("https://api.deepseek.com/v1/", ds.baseUrl)
        assertTrue(ds.thinkingEnabled)
        assertEquals("high", ds.reasoningEffort)
        assertTrue(ds.sendThinkingParams)
        assertEquals(8192, ds.maxTokens)
    }

    @Test
    fun customTemplate_hasEmptyDefaults() {
        val c = CompatibleProviderConfig.createCustom("custom-1")
        assertEquals("", c.baseUrl)
        assertEquals("", c.modelId)
        assertTrue(c.thinkingEnabled)
        assertEquals("high", c.reasoningEffort)
        assertEquals(false, c.sendThinkingParams)
        assertNull(c.maxTokens)
    }
}