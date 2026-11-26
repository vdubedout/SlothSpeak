package com.slothspeak.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moshi codegen JSON helpers for persisting a tool trace (plain JVM test). */
class ToolTraceEntryJsonTest {

    private val entries = listOf(
        ToolTraceEntry(
            name = "web_search",
            argumentsJson = """{"query":"weather in Paris"}""",
            resultPreview = "sunny, 21°C",
            isError = false,
            durationMs = 1234L
        ),
        ToolTraceEntry(
            name = "http_request",
            argumentsJson = """{"url":"https://example.com","method":"POST"}""",
            resultPreview = "Tool http_request failed: boom",
            isError = true,
            durationMs = 0L
        )
    )

    @Test
    fun toJson_producesAJsonArrayOfCamelCaseFields() {
        val json = ToolTraceEntry.toJson(entries)

        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"name\":\"web_search\""))
        assertTrue(json.contains("\"isError\":true"))
        assertTrue(json.contains("\"durationMs\":1234"))
    }

    @Test
    fun roundTrip_restoresEveryField() {
        assertEquals(entries, ToolTraceEntry.fromJson(ToolTraceEntry.toJson(entries)))
    }

    @Test
    fun fromJson_emptyListRoundTrips() {
        assertTrue(ToolTraceEntry.fromJson(ToolTraceEntry.toJson(emptyList())).isEmpty())
    }

    @Test
    fun fromJson_nullOrBlankInputYieldsEmptyList() {
        assertTrue(ToolTraceEntry.fromJson(null).isEmpty())
        assertTrue(ToolTraceEntry.fromJson("").isEmpty())
        assertTrue(ToolTraceEntry.fromJson("   ").isEmpty())
    }

    @Test
    fun fromJson_malformedInputYieldsEmptyListInsteadOfThrowing() {
        assertTrue(ToolTraceEntry.fromJson("not json").isEmpty())
        assertTrue(ToolTraceEntry.fromJson("{\"name\":\"missing-bracket\"").isEmpty())
        assertTrue(ToolTraceEntry.fromJson("""[{"name":"x"}]""").isEmpty()) // missing required fields
    }
}
