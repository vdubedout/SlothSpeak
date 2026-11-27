package com.slothspeak.tools

import com.slothspeak.tools.plugins.BraveSearchPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Unit tests for the model-facing digest of previous tool calls (issue #14). Runs under
 * Robolectric for the real `org.json` (arguments rendering must degrade gracefully, never throw).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolTraceHistoryDigestTest {

    private val search = ToolTraceEntry(
        name = "brave_web_search",
        argumentsJson = """{"query": "WanDub wiki API", "count": 3}""",
        resultPreview = "1. WanDub Otter Wiki\n   URL: https://www.wandub.com/\n   Description: Community wiki.",
        isError = false,
        durationMs = 842L
    )

    private val http = ToolTraceEntry(
        name = "http_request",
        argumentsJson = """{"method": "GET", "url": "https://www.wandub.com/api/pages"}""",
        resultPreview = "HTTP 200\n\n{\"pages\": [{\"title\": \"Home\"}]}",
        isError = false,
        durationMs = 315L
    )

    private val failed = ToolTraceEntry(
        name = "http_request",
        argumentsJson = """{"method": "GET", "url": "https://www.wandub.com/missing"}""",
        resultPreview = "Tool http_request failed: HTTP 404",
        isError = true,
        durationMs = 120L
    )

    // --- formatToolTraceDigest ---

    @Test
    fun digest_emptyList_isNull() {
        assertNull(formatToolTraceDigest(emptyList()))
    }

    @Test
    fun digest_singleEntry_hasNameArgumentsPreviewAndFootnote() {
        val digest = formatToolTraceDigest(listOf(search))!!

        assertTrue(digest.startsWith("[Previous tool calls for this answer: "))
        assertTrue(digest.contains("brave_web_search(query=\"WanDub wiki API\" · count=3) -> "))
        assertTrue(digest.contains("1. WanDub Otter Wiki URL: https://www.wandub.com/ Description: Community wiki."))
        assertFalse("success must not be flagged", digest.contains("ERROR"))
        assertTrue(digest.contains("]\n$DIGEST_FOOTNOTE"))
        assertTrue(digest.endsWith(DIGEST_FOOTNOTE))
    }

    @Test
    fun digest_multipleEntries_areJoinedWithSemicolonsInTraceOrder() {
        val digest = formatToolTraceDigest(listOf(search, http))!!

        val body = digest.substringAfter(": ").substringBefore("]\n")
        assertEquals(2, body.split("; ").size)
        assertTrue(body.indexOf("brave_web_search") < body.indexOf("http_request"))
        assertTrue(body.contains("http_request(GET https://www.wandub.com/api/pages) -> HTTP 200 {\"pages\": [{\"title\": \"Home\"}]}"))
    }

    @Test
    fun digest_errorEntry_isFlagged() {
        val digest = formatToolTraceDigest(listOf(search, failed))!!

        assertTrue(digest.contains("http_request(GET https://www.wandub.com/missing) -> ERROR: Tool http_request failed: HTTP 404"))
        // Only the failed call is flagged.
        assertEquals(1, Regex("ERROR: ").findAll(digest).count())
    }

    @Test
    fun digest_collapsesNewlinesAndWhitespaceSoItStaysOnOneLine() {
        val digest = formatToolTraceDigest(listOf(search))!!

        val header = digest.lineSequence().first()
        assertTrue(header.startsWith("[Previous tool calls for this answer: "))
        assertTrue(header.endsWith("]"))
        assertFalse(header.contains("\n"))
    }

    @Test
    fun digest_truncatesLongPreviewsAndArguments() {
        val longPreview = "x".repeat(5_000)
        // Known tool: the query is rendered verbatim by formatToolArguments, so the digest caps it.
        val longQuery = "y".repeat(1_000)
        val entry = ToolTraceEntry(
            name = BraveSearchPlugin.TOOL_NAME,
            argumentsJson = """{"query": "$longQuery"}""",
            resultPreview = longPreview,
            isError = false,
            durationMs = 1L
        )

        val digest = formatToolTraceDigest(listOf(entry))!!

        val args = "query=\"" + longQuery + "\""
        assertTrue(digest.contains("brave_web_search(" + args.take(DIGEST_ARGUMENT_CHARS) + "…) -> "))
        assertTrue(digest.contains(" -> " + "x".repeat(DIGEST_PREVIEW_CHARS) + "…"))
        assertFalse(digest.contains("x".repeat(DIGEST_PREVIEW_CHARS + 1)))
        assertFalse(digest.contains("y".repeat(DIGEST_ARGUMENT_CHARS)))
    }

    @Test
    fun digest_nonJsonArguments_areCappedToTheRawFallback() {
        val raw = "y".repeat(1_000)
        val entry = ToolTraceEntry("custom_tool", raw, "ok", false, 1L)

        val digest = formatToolTraceDigest(listOf(entry))!!

        assertTrue(digest.contains("custom_tool(" + raw.take(200) + ") -> ok"))
        assertFalse(digest.contains("y".repeat(201)))
    }

    @Test
    fun digest_blankPreview_rendersPlaceholder() {
        val entry = search.copy(resultPreview = "   ")

        assertTrue(formatToolTraceDigest(listOf(entry))!!.contains(") -> (no output)"))
    }

    @Test
    fun digest_unknownTool_rendersGenericArguments() {
        val entry = ToolTraceEntry(
            name = "custom_tool",
            argumentsJson = """{"path": "/a/b", "limit": 5}""",
            resultPreview = "ok",
            isError = false,
            durationMs = 1L
        )

        val digest = formatToolTraceDigest(listOf(entry))!!

        // Generic tools list every stringified key/value pair.
        assertTrue(digest.contains("custom_tool(path=/a/b · limit=5) -> ok"))
    }

    // --- answerWithToolTraceDigest ---

    @Test
    fun answer_nullTrace_isUnchanged() {
        assertEquals("Plain answer.", answerWithToolTraceDigest("Plain answer.", null))
    }

    @Test
    fun answer_blankTrace_isUnchanged() {
        assertEquals("Plain answer.", answerWithToolTraceDigest("Plain answer.", "   "))
    }

    @Test
    fun answer_malformedTrace_isUnchanged() {
        assertEquals("Plain answer.", answerWithToolTraceDigest("Plain answer.", "{not json"))
    }

    @Test
    fun answer_emptyTrace_isUnchanged() {
        assertEquals("Plain answer.", answerWithToolTraceDigest("Plain answer.", "[]"))
    }

    @Test
    fun answer_withTrace_appendsDigestAfterABlankLine() {
        val json = ToolTraceEntry.toJson(listOf(search, failed))

        val result = answerWithToolTraceDigest("The wiki has a Home page.", json)

        assertTrue(result.startsWith("The wiki has a Home page.\n\n[Previous tool calls for this answer: "))
        assertTrue(result.endsWith(DIGEST_FOOTNOTE))
        assertTrue(result.contains("ERROR: Tool http_request failed: HTTP 404"))
    }

    @Test
    fun answer_trailingWhitespace_isTrimmedBeforeTheDigest() {
        val json = ToolTraceEntry.toJson(listOf(search))

        val result = answerWithToolTraceDigest("Answer.  \n\n", json)

        assertTrue(result.startsWith("Answer.\n\n[Previous tool calls"))
        assertEquals("Answer.", result.substringBefore("\n\n"))
    }

    @Test
    fun answer_roundTripsThroughPersistedJson() {
        // What the harness persists is what the digest reads back.
        val trace = listOf(search.copy(resultPreview = search.resultPreview.take(500)), http)
        val json = ToolTraceEntry.toJson(trace)

        val digest = formatToolTraceDigest(ToolTraceEntry.fromJson(json))!!

        assertEquals(formatToolTraceDigest(trace), digest)
    }
}
