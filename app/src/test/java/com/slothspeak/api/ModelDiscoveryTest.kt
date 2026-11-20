package com.slothspeak.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDiscoveryTest {

    @Test
    fun dataArrayWithIds() {
        val body = """
            {"object":"list","data":[
              {"id":"glm-5.3","object":"model"},
              {"id":"glm-5.3-flash","object":"model"}
            ]}
        """.trimIndent()

        assertEquals(listOf("glm-5.3", "glm-5.3-flash"), ModelDiscovery.parseModelIds(body))
    }

    @Test
    fun modelsArrayWithNames() {
        val body = """{"models":[{"name":"qwen3.8-27b"},{"name":"llama-4"}]}"""

        assertEquals(listOf("llama-4", "qwen3.8-27b"), ModelDiscovery.parseModelIds(body))
    }

    @Test
    fun bareArrayAndDuplicates() {
        val body = """["gpt-x","gpt-x","gpt-y"]"""

        assertEquals(listOf("gpt-x", "gpt-y"), ModelDiscovery.parseModelIds(body))
    }

    @Test
    fun blankBody() {
        assertEquals(emptyList<String>(), ModelDiscovery.parseModelIds("  "))
    }
}
