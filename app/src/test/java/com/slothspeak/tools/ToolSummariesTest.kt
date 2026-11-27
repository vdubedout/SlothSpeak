package com.slothspeak.tools

import com.slothspeak.tools.plugins.BraveSearchPlugin
import com.slothspeak.tools.plugins.HttpRequestPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * One-line summaries/argument renderings for tool calls. Runs under Robolectric for the real
 * `org.json` (malformed arguments must degrade gracefully, never throw).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolSummariesTest {

    // --- formatToolSummary ---

    @Test
    fun braveSummary_showsQuery() {
        val call = ToolCall("c1", BraveSearchPlugin.TOOL_NAME, """{"query": "tokyo weather", "count": 5}""")
        assertEquals("Searching web: \"tokyo weather\"", formatToolSummary(call))
    }

    @Test
    fun braveSummary_trimsAndFallsBackToRawArgumentsWhenQueryMissing() {
        assertEquals(
            "Searching web: \"sloths\"",
            formatToolSummary(ToolCall("c1", BraveSearchPlugin.TOOL_NAME, """{"query": "  sloths  "}"""))
        )
        // Malformed arguments must never crash the status line — show them raw.
        assertEquals(
            "Searching web: \"{not json\"",
            formatToolSummary(ToolCall("c1", BraveSearchPlugin.TOOL_NAME, "{not json"))
        )
    }

    @Test
    fun httpSummary_showsMethodAndHost() {
        val call = ToolCall(
            "c2", HttpRequestPlugin.TOOL_NAME,
            """{"method": "post", "url": "https://www.wandub.com/api/pages?x=1#frag"}"""
        )
        assertEquals("HTTP POST www.wandub.com…", formatToolSummary(call))
    }

    @Test
    fun httpSummary_handlesSchemeLessUrlPortsAndMissingUrl() {
        fun summary(url: String?) = formatToolSummary(
            ToolCall("c", HttpRequestPlugin.TOOL_NAME, """{"method": "GET", "url": ${url?.let { "\"$it\"" } ?: "null"}}""")
        )
        assertEquals("HTTP GET example.com…", summary("example.com/path"))
        assertEquals("HTTP GET api.example.com…", summary("https://user:pw@api.example.com:8443/v1"))
        assertEquals("HTTP GET…", summary(null))
        assertEquals("HTTP GET…", summary(""))
    }

    @Test
    fun unknownTool_usesItsDisplayName() {
        assertEquals(
            "Running my_custom_tool",
            formatToolSummary(ToolCall("c", "my_custom_tool", """{"a": 1}"""))
        )
    }

    // --- formatToolArguments ---

    @Test
    fun braveArguments_queryCountFreshness() {
        assertEquals(
            "query=\"tokyo weather\" · count=3 · freshness=pd",
            formatToolArguments(BraveSearchPlugin.TOOL_NAME, """{"query": "tokyo weather", "count": 3, "freshness": "pd"}""")
        )
        assertEquals(
            "query=\"sloths\"",
            formatToolArguments(BraveSearchPlugin.TOOL_NAME, """{"query": "sloths", "freshness": null}""")
        )
        assertEquals(
            "query=\"sloths\" · count=5",
            formatToolArguments(BraveSearchPlugin.TOOL_NAME, """{"query": "sloths", "count": 5}""")
        )
    }

    @Test
    fun httpArguments_methodAndUrl() {
        assertEquals(
            "POST https://www.wandub.com/api/pages",
            formatToolArguments(
                HttpRequestPlugin.TOOL_NAME,
                """{"method": "POST", "url": "https://www.wandub.com/api/pages", "body": "…", "headers": {}}"""
            )
        )
    }

    @Test
    fun genericArguments_keyValuePairs() {
        assertEquals("a=1 · b=two", formatToolArguments("other_tool", """{"a": 1, "b": "two", "c": null}"""))
    }

    @Test
    fun malformedArguments_renderRawAndNeverThrow() {
        assertEquals("{not json", formatToolArguments("any", "{not json"))
        assertEquals("{not json", formatToolArguments(BraveSearchPlugin.TOOL_NAME, "{not json"))
        // Long garbage is truncated for the trace
        val long = "x".repeat(1000)
        assertEquals(200, formatToolArguments("any", long).length)
    }

    // --- formatToolDisplayName / hostOf ---

    @Test
    fun displayNames() {
        assertEquals("Brave Web Search", formatToolDisplayName("brave_web_search"))
        assertEquals("HTTP Request", formatToolDisplayName("http_request"))
        assertEquals("something_else", formatToolDisplayName("something_else"))
    }

    @Test
    fun hostOf_cases() {
        assertEquals("www.wandub.com", hostOf("https://www.wandub.com/api/pages"))
        assertEquals("api.example.com", hostOf("http://api.example.com:8080/"))
        assertEquals("example.com", hostOf("example.com/without/scheme"))
        assertNull(hostOf("/relative/path"))
        assertNull(hostOf(""))
        assertNull(hostOf("https://"))
    }
}
