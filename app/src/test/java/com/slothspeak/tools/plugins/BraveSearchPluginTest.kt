package com.slothspeak.tools.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.data.ToolConfigManager
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for BraveSearchPlugin. Runs under Robolectric for real SharedPreferences
 * (backing a real [ToolConfigManager]) and real org.json.
 *
 * The Brave API is simulated by an OkHttp [Interceptor] that records the outgoing request and
 * replays a scripted response — nothing ever reaches the network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BraveSearchPluginTest {

    /** Stands in for api.search.brave.com: records every request, returns a scripted response. */
    private class MockBraveApi : Interceptor {
        var code = 200
        var body: String = """{"web":{"results":[]}}"""

        /** When set, thrown from the interceptor instead of returning a response. */
        var failure: Exception? = null

        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request()
            failure?.let { throw it }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Mock")
                .body(body.toResponseBody(JSON_MEDIA_TYPE))
                .build()
        }
    }

    private companion object {
        const val API_KEY = "BSA-test-key-123"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        const val DEFAULT_URL =
            "https://api.search.brave.com/res/v1/web/search?q=kotlin%20coroutines&count=5"

        val TWO_RESULTS_BODY = """
            {"query":{"original":"kotlin coroutines"},"web":{"results":[
              {"title":"Kotlin Coroutines","url":"https://kotlinlang.org/docs/coroutines-overview.html","description":"A guide to coroutines","age":"2 days ago"},
              {"title":"Android","url":"https://developer.android.com/kotlin/coroutines","description":"Coroutines on Android"}
            ]}}
        """.trimIndent()

        val TWO_RESULTS_EXPECTED = """
            1. Kotlin Coroutines (2 days ago)
               URL: https://kotlinlang.org/docs/coroutines-overview.html
               A guide to coroutines
            2. Android
               URL: https://developer.android.com/kotlin/coroutines
               Coroutines on Android
        """.trimIndent()

        val NOT_CONFIGURED_MESSAGE = "Brave Search API key is not configured."
    }

    private lateinit var configPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var manager: ToolConfigManager
    private lateinit var client: OkHttpClient
    private lateinit var api: MockBraveApi
    private lateinit var plugin: BraveSearchPlugin

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configPrefs = context.getSharedPreferences("test_brave_config", Context.MODE_PRIVATE)
        securePrefs = context.getSharedPreferences("test_brave_secure", Context.MODE_PRIVATE)
        clearPrefs()
        manager = ToolConfigManager(configPrefs, securePrefs)
        api = MockBraveApi()
        client = OkHttpClient.Builder().addInterceptor(api).build()
        plugin = BraveSearchPlugin(manager, client)
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    private fun clearPrefs() {
        configPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()
    }

    // --- Helpers ---

    private fun configured() {
        manager.saveBraveApiKey(API_KEY)
    }

    private fun call(
        args: String = """{"query":"kotlin coroutines"}""",
        name: String = BraveSearchPlugin.TOOL_NAME,
        id: String = "call_1"
    ) = ToolCall(id = id, name = name, argumentsJson = args)

    private fun webResults(vararg items: String): String =
        """{"query":{"original":"kotlin coroutines"},"web":{"results":[${items.joinToString(",")}]}}"""

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.keyNames(): Set<String> = names()?.toStringList()?.toSet().orEmpty()

    // --- Metadata ---

    @Test
    fun metadata_matchesSpec() {
        assertEquals("brave_search", plugin.id)
        assertEquals(ToolConfigManager.PLUGIN_BRAVE_SEARCH, plugin.id)
        assertEquals("Brave Web Search", plugin.displayName)
        assertEquals(
            "Search the web using the Brave Search API for up-to-date information.",
            plugin.description
        )
    }

    @Test
    fun isConfigured_reflectsApiKeyPresence() {
        assertFalse(plugin.isConfigured())

        manager.saveBraveApiKey(API_KEY)
        assertTrue(plugin.isConfigured())

        manager.deleteBraveApiKey()
        assertFalse(plugin.isConfigured())

        // Blank keys are treated as absent by ToolConfigManager.
        manager.saveBraveApiKey("   ")
        assertFalse(plugin.isConfigured())
    }

    @Test
    fun isEnabled_reflectsTheUserToggleForThisPluginOnly() {
        assertFalse(plugin.isEnabled())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, true)
        assertTrue(plugin.isEnabled())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, true)
        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, false)
        assertFalse(plugin.isEnabled())
    }

    @Test
    fun isConfigured_isIndependentFromEnabled() {
        configured()
        assertFalse(plugin.isEnabled())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_BRAVE_SEARCH, true)
        manager.deleteBraveApiKey()
        assertTrue(plugin.isEnabled())
        assertFalse(plugin.isConfigured())
    }

    // --- Tool definition ---

    @Test
    fun tools_exposesExactlyOneToolNamedBraveWebSearch() {
        val tools = plugin.tools()

        assertEquals(1, tools.size)
        assertEquals("brave_web_search", tools.single().name)
        assertTrue(tools.single().description.isNotBlank())
    }

    @Test
    fun tools_schema_isAValidJsonObjectMatchingTheSpec() {
        val schema = JSONObject(plugin.tools().single().inputSchemaJson)

        assertEquals("object", schema.getString("type"))

        val properties = schema.getJSONObject("properties")
        assertEquals(setOf("query", "count", "freshness"), properties.keyNames())

        val query = properties.getJSONObject("query")
        assertEquals("string", query.getString("type"))

        val count = properties.getJSONObject("count")
        assertEquals("integer", count.getString("type"))
        assertEquals(1, count.getInt("minimum"))
        assertEquals(10, count.getInt("maximum"))
        assertEquals(5, count.getInt("default"))

        val freshness = properties.getJSONObject("freshness")
        assertEquals("string", freshness.getString("type"))
        assertEquals(listOf("pd", "pw", "pm", "py"), freshness.getJSONArray("enum").toStringList())

        assertEquals(listOf("query"), schema.getJSONArray("required").toStringList())
    }

    @Test
    fun tools_schema_roundTripsThroughTheGenericMapUsedByTheOpenAiClient() {
        // OpenAiCompatibleClient parses inputSchemaJson into Map<String, Any?> before serialising.
        val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )

        val parsed = requireNotNull(adapter.fromJson(plugin.tools().single().inputSchemaJson))

        assertEquals("object", parsed["type"])
        assertEquals(listOf("query"), parsed["required"])
        @Suppress("UNCHECKED_CAST")
        val properties = parsed["properties"] as Map<String, Any?>
        assertEquals(setOf("query", "count", "freshness"), properties.keys)
        @Suppress("UNCHECKED_CAST")
        val count = properties["count"] as Map<String, Any?>
        assertEquals(5.0, (count["default"] as Number).toDouble(), 0.0)
        assertEquals(1.0, (count["minimum"] as Number).toDouble(), 0.0)
        assertEquals(10.0, (count["maximum"] as Number).toDouble(), 0.0)
        @Suppress("UNCHECKED_CAST")
        val freshness = properties["freshness"] as Map<String, Any?>
        assertEquals(listOf("pd", "pw", "pm", "py"), (freshness["enum"] as List<*>).map { it.toString() })
    }

    // --- Request building: URL, encoding, headers ---

    @Test
    fun execute_buildsExpectedUrlAndHeaders() = runBlocking {
        configured()

        val result = plugin.execute(call())

        assertFalse(result.isError)
        val request = api.requests.single()
        assertEquals(DEFAULT_URL, request.url.toString())
        assertEquals("/res/v1/web/search", request.url.encodedPath)
        assertEquals("GET", request.method)
        assertEquals("application/json", request.header("Accept"))
        assertEquals(1, request.headers("Accept").size)
        assertEquals(API_KEY, request.header("X-Subscription-Token"))
        assertEquals(1, request.headers("X-Subscription-Token").size)
        assertNull(request.url.queryParameter("freshness"))
    }

    @Test
    fun execute_apiKeyIsOnlySentInTheHeaderNeverInTheUrl() = runBlocking {
        configured()

        plugin.execute(call())

        val request = api.requests.single()
        assertEquals(API_KEY, request.header("X-Subscription-Token"))
        assertFalse(request.url.toString().contains(API_KEY))
        assertNull("no Authorization header should be added", request.header("Authorization"))
    }

    @Test
    fun execute_resultCarriesTheCallIdAndToolName() = runBlocking {
        configured()

        val result = plugin.execute(call(id = "call_42"))

        assertEquals("call_42", result.toolCallId)
        assertEquals("brave_web_search", result.name)
    }

    @Test
    fun execute_urlEncodesQueryParameters() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"kotlin & \"coroutines\" + guide?"}"""))

        val url = api.requests.single().url
        assertEquals(
            "https://api.search.brave.com/res/v1/web/search?q=kotlin%20%26%20%22coroutines%22%20%2B%20guide%3F&count=5",
            url.toString()
        )
        assertEquals("kotlin & \"coroutines\" + guide?", url.queryParameter("q"))
        assertEquals("5", url.queryParameter("count"))
        // The encoded '&' inside the query must not be mistaken for a parameter separator.
        assertEquals(listOf("kotlin & \"coroutines\" + guide?"), url.queryParameterValues("q"))
    }

    @Test
    fun execute_count_defaultsToFiveWhenAbsent() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"a"}"""))

        assertEquals("5", api.requests.single().url.queryParameter("count"))
    }

    @Test
    fun execute_count_lowValuesClampToOne() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"a","count":0}"""))
        assertEquals("1", api.requests.single().url.queryParameter("count"))

        plugin.execute(call("""{"query":"a","count":-7}"""))
        assertEquals("1", api.requests.last().url.queryParameter("count"))
    }

    @Test
    fun execute_count_highValuesClampToTen() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"a","count":99}"""))
        assertEquals("10", api.requests.single().url.queryParameter("count"))

        plugin.execute(call("""{"query":"a","count":100000}"""))
        assertEquals("10", api.requests.last().url.queryParameter("count"))
    }

    @Test
    fun execute_count_inRangeValuesAreKeptIncludingBoundaries() = runBlocking {
        configured()

        for (count in listOf(1, 3, 10)) {
            plugin.execute(call("""{"query":"a","count":$count}"""))
            assertEquals(count.toString(), api.requests.last().url.queryParameter("count"))
        }
    }

    @Test
    fun execute_count_nonNumericOrNullValueFallsBackToDefault() = runBlocking {
        configured()

        for (args in listOf("""{"query":"a","count":"many"}""", """{"query":"a","count":null}""", """{"query":"a","count":[]}""")) {
            plugin.execute(call(args))
            assertEquals("5", api.requests.last().url.queryParameter("count"))
        }
    }

    @Test
    fun execute_count_numericStringOrFloatIsCoerced() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"a","count":"7"}"""))
        assertEquals("7", api.requests.last().url.queryParameter("count"))

        plugin.execute(call("""{"query":"a","count":2.9}"""))
        assertEquals("2", api.requests.last().url.queryParameter("count"))
    }

    @Test
    fun execute_freshness_isIncludedWhenValid() = runBlocking {
        configured()

        for (value in listOf("pd", "pw", "pm", "py")) {
            plugin.execute(call("""{"query":"a","freshness":"$value"}"""))
            val url = api.requests.last().url
            assertEquals(value, url.queryParameter("freshness"))
            assertEquals(
                "https://api.search.brave.com/res/v1/web/search?q=a&count=5&freshness=$value",
                url.toString()
            )
        }
    }

    @Test
    fun execute_freshness_isOmittedWhenAbsentBlankOrNull() = runBlocking {
        configured()

        val variants = listOf(
            """{"query":"a"}""",
            """{"query":"a","freshness":""}""",
            """{"query":"a","freshness":"  "}""",
            """{"query":"a","freshness":null}"""
        )
        for (args in variants) {
            plugin.execute(call(args))
            val url = api.requests.last().url
            assertNull("freshness must be absent for $args", url.queryParameter("freshness"))
            assertFalse(url.toString().contains("freshness"))
        }
    }

    @Test
    fun execute_freshness_isNormalisedToLowerCase() = runBlocking {
        configured()

        plugin.execute(call("""{"query":"a","freshness":" PW "}"""))

        assertEquals("pw", api.requests.single().url.queryParameter("freshness"))
    }

    @Test
    fun execute_freshness_invalidValueReturnsHelpfulErrorWithoutSendingARequest() = runBlocking {
        configured()

        val result = plugin.execute(call("""{"query":"a","freshness":"week"}"""))

        assertTrue(result.isError)
        assertEquals("Invalid freshness \"week\". Use one of: pd, pw, pm, py.", result.content)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_baseUrlWithoutTrailingSlashProducesTheSamePath() = runBlocking {
        manager.saveBraveApiKey(API_KEY)
        val noSlash = BraveSearchPlugin(manager, client, baseUrl = "https://api.search.brave.com")

        noSlash.execute(call())

        assertEquals(DEFAULT_URL, api.requests.single().url.toString())
    }

    @Test
    fun execute_customBaseUrlWithPathPrefixIsRespected() = runBlocking {
        manager.saveBraveApiKey(API_KEY)

        BraveSearchPlugin(manager, client, baseUrl = "https://proxy.example.com/brave").execute(call())

        assertEquals(
            "https://proxy.example.com/brave/res/v1/web/search?q=kotlin%20coroutines&count=5",
            api.requests.single().url.toString()
        )
    }

    @Test
    fun constructor_rejectsMalformedBaseUrl() {
        val thrown = runCatching { BraveSearchPlugin(manager, client, baseUrl = "not a url") }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException but got $thrown", thrown is IllegalArgumentException)
    }

    // --- Successful response parsing ---

    @Test
    fun execute_formatsSuccessfulResultsAsNumberedItems() = runBlocking {
        configured()
        api.body = TWO_RESULTS_BODY

        val result = plugin.execute(call())

        assertFalse(result.isError)
        assertEquals(TWO_RESULTS_EXPECTED, result.content)
    }

    @Test
    fun execute_includesExtraSnippetsIndentedUnderTheResult() = runBlocking {
        configured()
        api.body = webResults(
            """{"title":"T","url":"https://e.com","description":"D","extra_snippets":["First extra","Second extra"]}"""
        )

        val result = plugin.execute(call())

        assertEquals("1. T\n   URL: https://e.com\n   D\n   - First extra\n   - Second extra", result.content)
    }

    @Test
    fun execute_ignoresBlankAndNonStringExtraSnippets() = runBlocking {
        configured()
        api.body = webResults(
            """{"title":"T","url":"https://e.com","description":"D","extra_snippets":["  ",42,null,"Keep"]}"""
        )

        val result = plugin.execute(call())

        assertEquals("1. T\n   URL: https://e.com\n   D\n   - Keep", result.content)
    }

    @Test
    fun execute_stripsHtmlTagsAndUnescapesEntitiesInSnippets() = runBlocking {
        configured()
        api.body = webResults(
            """{"title":"<b>Bold</b> Kotlin &amp; coroutines",""" +
                """"url":"https://e.com/a?x=1&amp;y=2",""" +
                """"description":"Use <strong>suspend</strong> funs &mdash; they &quot;pause&quot; &lt;without&gt; blocking&#39; the thread.&nbsp;Done<br>Next line &copy; 2026 &hellip; &#x27;ok&#x27;",""" +
                """"extra_snippets":["<em>Emphasised</em> snippet"]}"""
        )

        val result = plugin.execute(call())

        assertEquals(
            "1. Bold Kotlin & coroutines\n" +
                "   URL: https://e.com/a?x=1&y=2\n" +
                "   Use suspend funs — they \"pause\" <without> blocking' the thread. Done Next line © 2026 … 'ok'\n" +
                "   - Emphasised snippet",
            result.content
        )
    }

    @Test
    fun cleanText_keepsLiteralEscapedTagTextAndComparisonOperators() {
        // Tags are stripped *before* entities are decoded, so escaped tag text survives literally.
        assertEquals("<b> is a tag", BraveSearchPlugin.cleanText("&lt;b&gt; is a tag"))
        // A bare '<' that does not open a tag is left alone.
        assertEquals("if x < y then y > z", BraveSearchPlugin.cleanText("if x < y then y > z"))
        // Block-level tags become spaces; inline tags are removed; whitespace is collapsed.
        assertEquals("one two three", BraveSearchPlugin.cleanText("<p>one</p><div>two</div><span>  three </span>"))
        // Unknown entities are left untouched.
        assertEquals("a &unknown; b &#xZZ; c", BraveSearchPlugin.cleanText("a &unknown; b &#xZZ; c"))
    }

    @Test
    fun execute_emptyResultsReturnNoResultsMessage() = runBlocking {
        configured()

        val bodies = listOf(
            """{"web":{"results":[]}}""",
            """{"web":null}""",
            """{"web":{}}""",
            """{}""",
            """{"query":{"original":"kotlin coroutines"}}"""
        )
        for (body in bodies) {
            api.body = body
            val result = plugin.execute(call())
            assertFalse("expected success for $body", result.isError)
            assertEquals("No web results found for \"kotlin coroutines\".", result.content)
        }
    }

    @Test
    fun execute_skipsNonObjectEntriesAndKeepsNumberingContinuous() = runBlocking {
        configured()
        api.body = webResults(
            """{"title":"A","url":"https://a.com","description":"a"}""",
            """"oops"""",
            "null",
            """{"title":"C","url":"https://c.com","description":"c"}"""
        )

        val result = plugin.execute(call())

        assertEquals("1. A\n   URL: https://a.com\n   a\n2. C\n   URL: https://c.com\n   c", result.content)
    }

    @Test
    fun execute_handlesMissingOptionalFields() = runBlocking {
        configured()
        api.body = webResults(
            """{"url":"https://a.com"}""",
            """{"title":"","description":"only description","age":null}""",
            """{"title":"No desc","url":"https://c.com","age":"3 weeks ago"}"""
        )

        val result = plugin.execute(call())

        assertEquals(
            "1. (no title)\n   URL: https://a.com\n" +
                "2. (no title)\n   only description\n" +
                "3. No desc (3 weeks ago)\n   URL: https://c.com",
            result.content
        )
    }

    @Test
    fun execute_nonJsonSuccessBodyReturnsUnexpectedResponseError() = runBlocking {
        configured()
        api.body = "<html>Not JSON</html>"

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search returned an unexpected response.", result.content)
    }

    // --- Argument / credential validation (no request must be sent) ---

    @Test
    fun execute_unknownToolNameReturnsErrorWithoutSendingARequest() = runBlocking {
        configured()

        val result = plugin.execute(call(name = "http_request"))

        assertTrue(result.isError)
        assertEquals("call_1", result.toolCallId)
        assertEquals("http_request", result.name)
        assertTrue(result.content.contains("http_request"))
        assertTrue(result.content.contains("brave_web_search"))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_malformedArgumentsJsonReturnsErrorWithoutSendingARequest() = runBlocking {
        configured()

        for (args in listOf("{not json", "", "{\"query\":", "[\"not\",\"an\",\"object\"]")) {
            val result = plugin.execute(call(args))
            assertTrue("expected error for <$args>", result.isError)
            assertTrue(result.content.startsWith("Invalid arguments JSON"))
            assertEquals("call_1", result.toolCallId)
        }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_blankOrMissingQueryReturnsErrorWithoutSendingARequest() = runBlocking {
        configured()

        val variants = listOf(
            """{"query":"   "}""",
            """{"query":""}""",
            "{}",
            """{"query":null}""",
            """{"count":3,"freshness":"pw"}"""
        )
        for (args in variants) {
            val result = plugin.execute(call(args))
            assertTrue("expected error for $args", result.isError)
            assertTrue(result.content.startsWith("Missing required parameter \"query\""))
        }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_missingApiKeyReturnsErrorWithoutSendingARequest() = runBlocking {
        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals(NOT_CONFIGURED_MESSAGE, result.content)
        assertEquals("call_1", result.toolCallId)
        assertEquals("brave_web_search", result.name)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_blankApiKeyIsTreatedAsNotConfigured() = runBlocking {
        manager.saveBraveApiKey("   ")

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals(NOT_CONFIGURED_MESSAGE, result.content)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun execute_argumentValidationHappensBeforeTheApiKeyCheck() = runBlocking {
        // Validation order: tool name → arguments → API key → HTTP.
        val badTool = plugin.execute(call(name = "other"))
        assertTrue(badTool.isError)
        assertTrue(badTool.content.startsWith("Unknown tool"))

        val badArgs = plugin.execute(call("{bad"))
        assertTrue(badArgs.isError)
        assertTrue(badArgs.content.startsWith("Invalid arguments JSON"))

        assertTrue(api.requests.isEmpty())
    }

    // --- HTTP status handling ---

    @Test
    fun execute_http401ReturnsInvalidApiKeyError() = runBlocking {
        configured()
        api.code = 401
        api.body = """{"error":"invalid key"}"""

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Invalid Brave Search API key (HTTP 401). Please check your key in Settings → Tools.", result.content)
    }

    @Test
    fun execute_http403ReturnsInvalidApiKeyError() = runBlocking {
        configured()
        api.code = 403

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Invalid Brave Search API key (HTTP 403). Please check your key in Settings → Tools.", result.content)
    }

    @Test
    fun execute_http429ReturnsRateLimitError() = runBlocking {
        configured()
        api.code = 429

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search rate limit exceeded (HTTP 429). Please try again later.", result.content)
    }

    @Test
    fun execute_http500ReturnsGenericError() = runBlocking {
        configured()
        api.code = 500
        api.body = "<html>Internal Server Error</html>"

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search request failed (HTTP 500).", result.content)
    }

    @Test
    fun execute_http400ReturnsGenericError() = runBlocking {
        configured()
        api.code = 400
        api.body = """{"error":"bad request"}"""

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search request failed (HTTP 400).", result.content)
    }

    @Test
    fun execute_httpErrorsStillCarryTheCallIdentity() = runBlocking {
        configured()
        api.code = 502

        val result = plugin.execute(call(id = "call_err"))

        assertTrue(result.isError)
        assertEquals("call_err", result.toolCallId)
        assertEquals("brave_web_search", result.name)
        assertEquals("Brave Search request failed (HTTP 502).", result.content)
    }

    // --- Transport errors ---

    @Test
    fun execute_networkIOExceptionReturnsErrorWithMessage() = runBlocking {
        configured()
        api.failure = IOException("connection timed out")

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search network error: connection timed out", result.content)
    }

    @Test
    fun execute_networkIOExceptionWithoutMessageNamesTheExceptionType() = runBlocking {
        configured()
        api.failure = IOException()

        val result = plugin.execute(call())

        assertTrue(result.isError)
        assertEquals("Brave Search network error: IOException", result.content)
    }

    @Test
    fun execute_rethrowsCancellationExceptionThrownByTheTransport() = runBlocking {
        configured()
        api.failure = CancellationException("cancelled")

        val thrown = runCatching { plugin.execute(call()) }.exceptionOrNull()

        assertTrue("expected CancellationException but got $thrown", thrown is CancellationException)
    }

    @Test
    fun execute_rethrowsCallerCancellationInsteadOfReturningAResult() = runBlocking {
        configured()
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val outcome = CompletableDeferred<ToolResult?>()
        val blockingPlugin = BraveSearchPlugin(
            manager,
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestStarted.countDown()
                    releaseResponse.await(10, TimeUnit.SECONDS)
                    api.intercept(chain)
                }
                .build()
        )
        api.body = TWO_RESULTS_BODY

        val job = launch(Dispatchers.Default) {
            try {
                outcome.complete(blockingPlugin.execute(call()))
            } catch (e: CancellationException) {
                outcome.complete(null)
            }
        }

        // Cancel while the request is in flight, then let the (blocking) call finish.
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        releaseResponse.countDown()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull("plugin must not swallow caller cancellation", outcome.await())
    }
}
