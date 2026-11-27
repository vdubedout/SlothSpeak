package com.slothspeak.tools.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slothspeak.api.toChatTool
import com.slothspeak.data.HostAuthProfile
import com.slothspeak.data.HttpWritePolicy
import com.slothspeak.data.ToolConfigManager
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolConfirmationRequest
import com.slothspeak.tools.ToolRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Unit tests for HttpRequestPlugin. The network is replaced by an OkHttp *application*
 * interceptor that records the request and returns a canned response without ever calling
 * `chain.proceed`, so no socket is opened. ToolConfigManager is the real implementation (plain
 * SharedPreferences under Robolectric), so the persistence of "always allow" decisions is
 * verified end to end.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HttpRequestPluginTest {

    // --- Fakes ---

    /** The request exactly as the plugin built it (before OkHttp adds Host / User-Agent etc.). */
    private class SentRequest(val call: Call, val request: Request) {
        val method: String get() = request.method
        val url: HttpUrl get() = request.url
        val headers: Headers get() = request.headers

        val bodyBytes: ByteArray? = request.body?.let { body ->
            Buffer().also { body.writeTo(it) }.readByteArray()
        }

        /** Body decoded with the charset of its own media type. */
        val bodyText: String?
            get() = bodyBytes?.let { bytes ->
                String(bytes, request.body?.contentType()?.charset() ?: Charsets.UTF_8)
            }

        fun header(name: String): String? = request.header(name)

        fun respond(
            code: Int,
            message: String,
            contentType: String? = null,
            body: String? = null,
            headers: Map<String, String> = emptyMap()
        ): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .apply {
                if (contentType != null) header("Content-Type", contentType)
                headers.forEach { (name, value) -> header(name, value) }
            }
            .body((body ?: "").toResponseBody(contentType?.toMediaTypeOrNull()))
            .build()
    }

    /** Stands in for the network: records requests, answers from [respond], never proceeds the chain. */
    private class FakeTransport : Interceptor {
        val sent = mutableListOf<SentRequest>()
        var respond: (SentRequest) -> Response = { it.respond(200, "OK", "application/json", """{"ok":true}""") }

        override fun intercept(chain: Interceptor.Chain): Response {
            val recorded = SentRequest(chain.call(), chain.request())
            sent += recorded
            return respond(recorded)
        }
    }

    /** Confirmation handler that records every prompt and answers with a fixed [decision]. */
    private class RecordingHandler(private val decision: ToolConfirmationDecision) : ToolConfirmationHandler {
        val prompts = mutableListOf<ToolConfirmationRequest>()

        override suspend fun requestConfirmation(request: ToolConfirmationRequest): ToolConfirmationDecision {
            prompts += request
            return decision
        }
    }

    // --- Fixture ---

    private lateinit var configPrefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences
    private lateinit var manager: ToolConfigManager
    private lateinit var transport: FakeTransport
    private lateinit var plugin: HttpRequestPlugin

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        configPrefs = context.getSharedPreferences("test_http_plugin_config", Context.MODE_PRIVATE)
        securePrefs = context.getSharedPreferences("test_http_plugin_secure", Context.MODE_PRIVATE)
        clearPrefs()
        manager = ToolConfigManager(configPrefs, securePrefs)
        transport = FakeTransport()
        plugin = HttpRequestPlugin(manager, OkHttpClient.Builder().addInterceptor(transport).build())
    }

    @After
    fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        configPrefs.edit().clear().commit()
        securePrefs.edit().clear().commit()
    }

    /** Builds the raw JSON arguments string the model would send. */
    private fun args(vararg fields: Pair<String, Any?>): String = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }.toString()

    private fun call(arguments: String, id: String = "call_1") =
        ToolCall(id = id, name = HttpRequestPlugin.TOOL_NAME, argumentsJson = arguments)

    private fun request(method: String, url: String, vararg fields: Pair<String, Any?>) =
        call(args("method" to method, "url" to url, *fields))

    private fun get(url: String = ITEMS_URL, vararg fields: Pair<String, Any?>) = request("GET", url, *fields)

    private fun allowOnce() = RecordingHandler(ToolConfirmationDecision.ALLOW_ONCE)

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    // --- Metadata & tool definition ---

    @Test
    fun plugin_metadataMatchesSpec() {
        assertEquals(ToolConfigManager.PLUGIN_HTTP_REQUEST, plugin.id)
        assertEquals("http_request", HttpRequestPlugin.TOOL_NAME)
        assertEquals("HTTP Request (curl)", plugin.displayName)
        assertEquals(
            "Perform HTTP requests (GET, POST, PUT, PATCH, DELETE, HEAD) to web pages or APIs.",
            plugin.description
        )
        assertTrue("the plugin needs no credentials", plugin.isConfigured())
    }

    @Test
    fun plugin_isEnabledFollowsTheUserToggle() {
        assertFalse(plugin.isEnabled())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, true)
        assertTrue(plugin.isEnabled())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, false)
        assertFalse(plugin.isEnabled())
    }

    @Test
    fun tools_exposesASingleHttpRequestToolWithAValidJsonObjectSchema() {
        val tool = plugin.tools().single()

        assertEquals("http_request", tool.name)
        assertTrue(tool.description.isNotBlank())

        val schema = JSONObject(tool.inputSchemaJson)
        assertEquals("object", schema.getString("type"))
        assertEquals(setOf("method", "url"), schema.getJSONArray("required").toStringList().toSet())

        val props = schema.getJSONObject("properties")
        assertEquals(
            setOf("method", "url", "headers", "body", "content_type", "strip_html"),
            props.keys().asSequence().toSet()
        )

        val method = props.getJSONObject("method")
        assertEquals("string", method.getString("type"))
        val methods = method.getJSONArray("enum").toStringList()
        assertEquals(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"), methods)
        assertEquals(HttpRequestPlugin.ALLOWED_METHODS, methods.toSet())

        assertEquals("string", props.getJSONObject("url").getString("type"))

        val headers = props.getJSONObject("headers")
        assertEquals("object", headers.getString("type"))
        assertEquals("string", headers.getJSONObject("additionalProperties").getString("type"))

        assertEquals("string", props.getJSONObject("body").getString("type"))
        assertEquals("string", props.getJSONObject("content_type").getString("type"))

        val stripHtml = props.getJSONObject("strip_html")
        assertEquals("boolean", stripHtml.getString("type"))
        assertTrue(stripHtml.getBoolean("default"))
    }

    @Test
    fun tools_schemaSurvivesTheOpenAiToolMapping() {
        val schemaAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val tool = plugin.tools().single()

        val mapped = tool.toChatTool(schemaAdapter)

        assertEquals("http_request", mapped.function.name)
        assertEquals(schemaAdapter.fromJson(tool.inputSchemaJson), mapped.function.parameters)
        assertEquals(listOf("method", "url"), mapped.function.parameters["required"])
        assertFalse("the schema must not collapse to an empty parameter map", mapped.function.parameters.isEmpty())
    }

    @Test
    fun defaultHttpClient_doesNotFollowRedirectsAndHasBoundedTimeouts() {
        val client = HttpRequestPlugin.defaultHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.connectTimeoutMillis > 0)
        assertTrue(client.readTimeoutMillis > 0)
        assertTrue(client.writeTimeoutMillis > 0)
        assertTrue("must stay below ToolRegistry's 30s call timeout", client.callTimeoutMillis in 1 until 30_000)
    }

    // --- Read-only requests ---

    @Test
    fun get_executesWithoutConfirmation_andFormatsTheResponse() = runBlocking {
        transport.respond = { it.respond(200, "OK", "application/json", """{"items":[1,2]}""") }
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        val result = plugin.execute(get(), handler)

        assertFalse(result.isError)
        assertEquals("call_1", result.toolCallId)
        assertEquals("http_request", result.name)
        assertEquals("HTTP 200 OK\nContent-Type: application/json\n\n{\"items\":[1,2]}", result.content)

        val sent = transport.sent.single()
        assertEquals("GET", sent.method)
        assertEquals("https://api.example.com/items", sent.url.toString())
        assertNull(sent.bodyBytes)
        assertTrue("GET must never prompt the user", handler.prompts.isEmpty())
    }

    @Test
    fun head_executesWithoutConfirmation_andSendsNoBody() = runBlocking {
        transport.respond = { it.respond(200, "OK", "text/html") }
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        val result = plugin.execute(request("HEAD", HTML_URL), handler)

        assertFalse(result.isError)
        assertEquals("HTTP 200 OK\nContent-Type: text/html", result.content)
        assertEquals("HEAD", transport.sent.single().method)
        assertNull(transport.sent.single().bodyBytes)
        assertTrue(handler.prompts.isEmpty())
    }

    @Test
    fun get_sendsModelHeadersAndKeepsTheUrlIntact() = runBlocking {
        val result = plugin.execute(
            get(
                "https://API.Example.com:8443/search?q=sloth%20talk&page=2",
                "headers" to JSONObject(mapOf("Accept" to "application/vnd.api+json", "X-Request-Id" to "abc-123"))
            )
        )

        assertFalse(result.isError)
        val sent = transport.sent.single()
        assertEquals("GET", sent.method)
        assertEquals("https", sent.url.scheme)
        assertEquals("api.example.com", sent.url.host)
        assertEquals(8443, sent.url.port)
        assertEquals("/search", sent.url.encodedPath)
        assertEquals("q=sloth%20talk&page=2", sent.url.encodedQuery)
        assertEquals("application/vnd.api+json", sent.header("Accept"))
        assertEquals("abc-123", sent.header("X-Request-Id"))
        assertEquals(listOf("Accept", "X-Request-Id"), sent.headers.names().toList())
    }

    @Test
    fun method_isUpperCasedAndTrimmedBeforeUse() = runBlocking {
        val result = plugin.execute(request("  get ", ITEMS_URL))

        assertFalse(result.isError)
        assertEquals("GET", transport.sent.single().method)
    }

    @Test
    fun get_ignoresBodyAndContentTypeArguments() = runBlocking {
        plugin.execute(get(ITEMS_URL, "body" to "ignored", "content_type" to "text/plain"))

        val sent = transport.sent.single()
        assertNull(sent.bodyBytes)
        assertNull(sent.header("Content-Type"))
    }

    @Test
    fun headersPassedAsAJsonStringAreAccepted() = runBlocking {
        plugin.execute(get(ITEMS_URL, "headers" to """{"Accept":"text/plain"}"""))

        assertEquals("text/plain", transport.sent.single().header("Accept"))
    }

    @Test
    fun emptyResponseMessageAndMissingHeadersAreHandled() = runBlocking {
        transport.respond = { it.respond(204, "", null, null) }

        val result = plugin.execute(get())

        assertEquals("HTTP 204", result.content)
        assertFalse(result.isError)
    }

    // --- HTML stripping ---

    @Test
    fun get_htmlResponse_isReducedToReadableTextByDefault() = runBlocking {
        transport.respond = { it.respond(200, "OK", "text/html; charset=utf-8", SAMPLE_HTML) }

        val result = plugin.execute(get(HTML_URL))

        assertFalse(result.isError)
        assertEquals(
            "HTTP 200 OK\nContent-Type: text/html; charset=utf-8\n\n" +
                "Sloth page\n\n" +
                "Welcome to SlothSpeak\n\n" +
                "Sloths are slow & steady.\n\n" +
                "It's \"tree\" life © 2026\n\n" +
                "line one\nline two",
            result.content
        )
    }

    @Test
    fun get_htmlResponse_keepsTheRawHtmlWhenStripHtmlIsFalse() = runBlocking {
        transport.respond = { it.respond(200, "OK", "text/html", SAMPLE_HTML) }

        val result = plugin.execute(get(HTML_URL, "strip_html" to false))

        assertEquals("HTTP 200 OK\nContent-Type: text/html\n\n$SAMPLE_HTML", result.content)
    }

    @Test
    fun get_stripHtmlAcceptsStringBooleans_andMatchesContentTypeCaseInsensitively() = runBlocking {
        transport.respond = { it.respond(200, "OK", "TEXT/HTML; charset=UTF-8", "<p>a</p><p>b</p>") }

        assertEquals(
            "HTTP 200 OK\nContent-Type: TEXT/HTML; charset=UTF-8\n\n<p>a</p><p>b</p>",
            plugin.execute(get(HTML_URL, "strip_html" to "false")).content
        )
        assertEquals(
            "HTTP 200 OK\nContent-Type: TEXT/HTML; charset=UTF-8\n\na\n\nb",
            plugin.execute(get(HTML_URL)).content
        )
    }

    @Test
    fun stripHtml_isOnlyAppliedToHtmlResponses() = runBlocking {
        transport.respond = { it.respond(200, "OK", "application/json", """{"snippet":"<p>not a &amp; tag</p>"}""") }
        assertEquals(
            "HTTP 200 OK\nContent-Type: application/json\n\n{\"snippet\":\"<p>not a &amp; tag</p>\"}",
            plugin.execute(get(ITEMS_URL)).content
        )

        transport.respond = { it.respond(200, "OK", "text/plain", "<b>bold</b> &amp; more") }
        assertEquals(
            "HTTP 200 OK\nContent-Type: text/plain\n\n<b>bold</b> &amp; more",
            plugin.execute(get(ITEMS_URL)).content
        )

        transport.respond = { it.respond(200, "OK", null, "<i>no content type</i>") }
        assertEquals(
            "HTTP 200 OK\n\n<i>no content type</i>",
            plugin.execute(get(ITEMS_URL)).content
        )
    }

    @Test
    fun textualContentTypesAreReturnedVerbatim() = runBlocking {
        for (type in listOf("application/json", "application/vnd.api+json", "application/xml", "application/xhtml+xml", "application/javascript", "text/plain")) {
            transport.respond = { it.respond(200, "OK", type, "<p>a &amp; b</p>") }

            assertEquals("type $type", "HTTP 200 OK\nContent-Type: $type\n\n<p>a &amp; b</p>", plugin.execute(get(ITEMS_URL)).content)
        }
    }

    @Test
    fun binaryResponse_isNotDecoded() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 0xFE.toByte())
        transport.respond = { sent ->
            Response.Builder()
                .request(sent.request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "image/png")
                .body(bytes.toResponseBody("image/png".toMediaType()))
                .build()
        }

        val result = plugin.execute(get("https://www.example.com/logo.png"))

        assertFalse(result.isError)
        assertEquals("HTTP 200 OK\nContent-Type: image/png\n\n[binary content (image/png, 7 bytes) not shown]", result.content)
    }

    @Test
    fun stripHtml_removesScriptsStylesCommentsAndTags() {
        assertEquals("Visible", HttpRequestPlugin.stripHtml("<div>Visible</div>"))
        assertEquals("keep", HttpRequestPlugin.stripHtml("<p>keep</p><script>evil()"))
        assertEquals(
            "ok",
            HttpRequestPlugin.stripHtml(
                "<SCRIPT TYPE=\"text/javascript\">bad()</SCRIPT><STYLE>a{}</STYLE><DIV CLASS=\"x\">ok</DIV>"
            )
        )
        assertEquals(
            "a",
            HttpRequestPlugin.stripHtml("<p>a</p><!--[if IE]><script>bad()</script><![endif]--><!-- unclosed comment")
        )
        assertEquals("x", HttpRequestPlugin.stripHtml("<![CDATA[ cdata ]]>x<?xml version=\"1.0\"?>"))
        assertEquals("a < b and c > d", HttpRequestPlugin.stripHtml("<p>a < b and c > d</p>"))
    }

    @Test
    fun stripHtml_dropsElementsWhoseContentIsNotProse() {
        assertEquals(
            "Visible",
            HttpRequestPlugin.stripHtml(
                "<div>Visible</div>" +
                    "<svg viewBox=\"0 0 1 1\"><title>icon</title><path d=\"M0 0\"/><text>x</text></svg>" +
                    "<template>t</template><textarea rows=\"2\">draft &amp; notes</textarea>" +
                    "<select><option value=\"1\">Un</option></select><STYLE>a{}</STYLE>"
            )
        )
        // Fallback content is the only thing a JS-only page has to say: keep it.
        assertEquals(
            "Please enable JavaScript",
            HttpRequestPlugin.stripHtml("<body><noscript>Please enable JavaScript</noscript></body>")
        )
    }

    @Test
    fun stripHtml_decodesEntitiesWithoutDoubleUnescaping() {
        assertEquals(
            "Tom & Jerry <3 > 2 \"quoted\" it's © ❤ ′",
            HttpRequestPlugin.stripHtml("Tom &amp; Jerry &lt;3 &gt; 2 &quot;quoted&quot; it&#39;s &nbsp;© &#x2764; &#8242;")
        )
        assertEquals("café –—… «ok»", HttpRequestPlugin.stripHtml("caf&eacute; &ndash;&mdash;&hellip; &laquo;ok&raquo;"))
        assertEquals("&lt;tag&gt;", HttpRequestPlugin.stripHtml("&amp;lt;tag&amp;gt;"))
        assertEquals("&#39;", HttpRequestPlugin.stripHtml("&amp;#39;"))
        assertEquals("&lt;", HttpRequestPlugin.stripHtml("&#38;lt;"))
        // Code points that do not exist are left alone rather than crashing or silently dropped.
        assertEquals("&#1114112; &#xD800;", HttpRequestPlugin.stripHtml("&#1114112; &#xD800;"))
    }

    @Test
    fun stripHtml_turnsBlockElementsIntoLineBreaks() {
        assertEquals("a\nb\nc", HttpRequestPlugin.stripHtml("a<br>b<br/>c"))
        assertEquals("One\n\nTwo", HttpRequestPlugin.stripHtml("<ul><li>One</li><li>Two</li></ul>"))
        assertEquals("Title\n\nParagraph", HttpRequestPlugin.stripHtml("<title>Title</title><p>Paragraph</p>"))
        assertEquals("row", HttpRequestPlugin.stripHtml("<table><tr><td>row</td></tr></table>"))
    }

    @Test
    fun stripHtml_collapsesWhitespaceAndBlankLines() {
        assertEquals("a b c\n\nd", HttpRequestPlugin.stripHtml("a   b\t\tc\r\n\r\n\r\n\r\n  d  \n\n"))
        assertEquals("plain\ntext", HttpRequestPlugin.stripHtml("  plain\n   text  "))
        assertEquals("", HttpRequestPlugin.stripHtml("<div>   </div>"))
        assertEquals("no tags at all", HttpRequestPlugin.stripHtml("no tags at all"))
    }

    // --- Argument validation (nothing may reach the network or the user) ---

    @Test
    fun invalidArguments_returnAnErrorResultWithoutSendingAnything() = runBlocking {
        val cases = listOf(
            // malformed JSON
            "" to "Invalid arguments",
            "{not json" to "Invalid arguments",
            "[1,2]" to "Invalid arguments",
            "\"just a string\"" to "Invalid arguments",
            "null" to "Invalid arguments",
            // method
            """{"url":"https://api.example.com"}""" to "Invalid 'method'",
            """{"method":"CONNECT","url":"https://api.example.com"}""" to "Invalid 'method'",
            """{"method":"OPTIONS","url":"https://api.example.com"}""" to "Invalid 'method'",
            """{"method":42,"url":"https://api.example.com"}""" to "Invalid 'method'",
            // url
            """{"method":"GET"}""" to "Invalid 'url'",
            """{"method":"GET","url":""}""" to "Invalid 'url'",
            """{"method":"GET","url":"http://"}""" to "Invalid 'url'",
            """{"method":"GET","url":"file:///etc/passwd"}""" to "Invalid 'url'",
            """{"method":"GET","url":"ws://example.com/socket"}""" to "Invalid 'url'",
            """{"method":"GET","url":"javascript:alert(1)"}""" to "Invalid 'url'",
            """{"method":"GET","url":"ht!tp://example.com"}""" to "Invalid 'url'",
            // headers
            """{"method":"GET","url":"https://api.example.com","headers":["a"]}""" to "Invalid 'headers'",
            """{"method":"GET","url":"https://api.example.com","headers":"not json"}""" to "Invalid 'headers'",
            """{"method":"GET","url":"https://api.example.com","headers":{"X-A":"a\r\nX-Injected: 1"}}""" to "line breaks",
            // content type
            """{"method":"POST","url":"https://api.example.com","body":"x","content_type":"json"}""" to "Invalid 'content_type'",
            // writes are rejected before the user is ever asked
            """{"method":"POST","url":"ftp://example.com/x","body":"x"}""" to "Invalid 'url'"
        )
        val handler = allowOnce()

        cases.forEachIndexed { index, (arguments, expectedFragment) ->
            val result = plugin.execute(call(arguments, id = "call_$index"), handler)

            assertTrue("case $index ($arguments) must be an error", result.isError)
            assertTrue(
                "case $index: '${result.content}' must mention '$expectedFragment'",
                result.content.contains(expectedFragment)
            )
            assertEquals("call_$index", result.toolCallId)
        }

        assertTrue("invalid input must never reach the network", transport.sent.isEmpty())
        assertTrue("invalid input must never prompt the user", handler.prompts.isEmpty())
    }

    @Test
    fun nonHttpScheme_isRejectedWithAHint() = runBlocking {
        val result = plugin.execute(get("ftp://example.com/file"))

        assertTrue(result.isError)
        assertEquals("Invalid 'url': scheme \"ftp\" is not supported, use http:// or https://.", result.content)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun headerWithLineBreak_isRejectedBeforeSending() = runBlocking {
        val result = plugin.execute(get(ITEMS_URL, "headers" to JSONObject(mapOf("X-Test" to "a\r\nX-Injected: 1"))))

        assertTrue(result.isError)
        assertTrue(result.content.startsWith("Invalid 'headers':"))
        assertTrue(result.content.contains("X-Test"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun headerWithInvalidName_isRejectedByHeaderValidation() = runBlocking {
        val result = plugin.execute(get(ITEMS_URL, "headers" to JSONObject(mapOf("X Test" to "a"))))

        assertTrue(result.isError)
        assertTrue(result.content, result.content.startsWith("Invalid headers:"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun invalidContentType_isRejectedBeforeSending() = runBlocking {
        val handler = allowOnce()

        val result = plugin.execute(request("POST", ITEMS_URL, "body" to "x", "content_type" to "json"), handler)

        assertTrue(result.isError)
        assertEquals("Invalid 'content_type': \"json\" is not a valid media type (expected e.g. \"application/json\").", result.content)
        assertTrue(handler.prompts.isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun unknownToolName_returnsAnErrorResult() = runBlocking {
        val result = plugin.execute(ToolCall(id = "call_x", name = "web_search", argumentsJson = "{}"))

        assertTrue(result.isError)
        assertEquals("call_x", result.toolCallId)
        assertEquals("web_search", result.name)
        assertTrue(result.content.contains("web_search"))
        assertTrue(transport.sent.isEmpty())
    }

    // --- Write confirmation ---

    @Test
    fun post_allowOnce_sendsTheRequestAndPersistsNothing() = runBlocking {
        val handler = allowOnce()

        val result = plugin.execute(request("POST", ITEMS_URL, "body" to """{"title":"sloth"}"""), handler)

        assertFalse(result.isError)
        val sent = transport.sent.single()
        assertEquals("POST", sent.method)
        assertEquals("""{"title":"sloth"}""", sent.bodyText)
        assertEquals("application/json", sent.header("Content-Type"))

        val prompt = handler.prompts.single()
        assertEquals("http_request", prompt.toolName)
        assertEquals("POST", prompt.method)
        assertEquals(ITEMS_URL, prompt.url)
        assertEquals("api.example.com", prompt.host)
        assertEquals(mapOf("Content-Type" to "application/json"), prompt.headers)
        assertEquals("""{"title":"sloth"}""", prompt.bodyPreview)

        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
        assertTrue(manager.getAllowedWriteHosts().isEmpty())

        // "Allow once" is per call: the next write prompts again.
        plugin.execute(request("POST", "https://api.example.com/other", "body" to "{}"), handler)
        assertEquals(2, handler.prompts.size)
        assertEquals(2, transport.sent.size)
        assertTrue(manager.getAllowedWriteHosts().isEmpty())
    }

    @Test
    fun putPatchDelete_eachPromptAndAttachTheBody() = runBlocking {
        for (method in listOf("PUT", "PATCH", "DELETE")) {
            val handler = allowOnce()

            val result = plugin.execute(request(method, ITEMS_URL, "body" to "payload"), handler)

            assertFalse("$method should succeed", result.isError)
            assertEquals(method, handler.prompts.single().method)
            val sent = transport.sent.last()
            assertEquals(method, sent.method)
            assertEquals("payload", sent.bodyText)
            assertEquals("application/json", sent.header("Content-Type"))
        }
        assertEquals(3, transport.sent.size)
    }

    @Test
    fun alwaysAllowHost_persistsTheHost_andBypassesThePromptForThatHostOnly() = runBlocking {
        val handler = RecordingHandler(ToolConfirmationDecision.ALWAYS_ALLOW_HOST)

        plugin.execute(request("POST", "https://API.Example.COM/items", "body" to "a"), handler)

        assertEquals(setOf("api.example.com"), manager.getAllowedWriteHosts())
        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())

        // Same host (any case / path / port): no prompt.
        plugin.execute(request("DELETE", "https://api.example.com/items/1"), handler)
        plugin.execute(request("PATCH", "http://api.example.com:8080/items/1", "body" to "b"), handler)
        assertEquals(1, handler.prompts.size)
        assertEquals(3, transport.sent.size)

        // A different host still prompts.
        plugin.execute(request("POST", "https://other.example.org/items", "body" to "b"), handler)
        assertEquals(2, handler.prompts.size)
        assertEquals("other.example.org", handler.prompts.last().host)
        assertEquals(setOf("api.example.com", "other.example.org"), manager.getAllowedWriteHosts())
        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
        assertEquals(4, transport.sent.size)
    }

    @Test
    fun alwaysAllowHost_alsoMatchesAHostSavedWithAPort() = runBlocking {
        manager.addAllowedWriteHost("localhost:8000")
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        plugin.execute(request("POST", "http://LOCALHOST:8000/items", "body" to "a"), handler)
        plugin.execute(request("POST", "http://other.example.org/items", "body" to "a"), handler)

        assertEquals(1, transport.sent.size)
        assertEquals(listOf("other.example.org"), handler.prompts.map { it.host })
    }

    @Test
    fun alwaysAllowAll_setsTheGlobalPolicy_andBypassesThePromptForEveryHost() = runBlocking {
        val handler = RecordingHandler(ToolConfirmationDecision.ALWAYS_ALLOW_ALL)

        plugin.execute(request("POST", ITEMS_URL, "body" to "a"), handler)

        assertEquals(HttpWritePolicy.ALWAYS_ALLOW, manager.getHttpWritePolicy())
        assertTrue(manager.getAllowedWriteHosts().isEmpty())

        plugin.execute(request("PUT", "https://other.example.org/x", "body" to "b"), handler)
        plugin.execute(request("DELETE", "http://localhost:8000/x", "body" to "c"), handler)
        plugin.execute(request("PATCH", "http://10.0.0.5/x", "body" to "d"))
        assertEquals(1, handler.prompts.size)
        assertEquals(4, transport.sent.size)
    }

    @Test
    fun deny_doesNotSendTheRequest_andReturnsAnErrorResult() = runBlocking {
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        val result = plugin.execute(request("POST", ITEMS_URL, "body" to "x"), handler)

        assertTrue(result.isError)
        assertEquals("User denied HTTP POST request to https://api.example.com/items.", result.content)
        assertEquals("call_1", result.toolCallId)
        assertEquals("http_request", result.name)
        assertTrue("a denied write must never reach the network", transport.sent.isEmpty())
        assertEquals(1, handler.prompts.size)
        assertEquals(HttpWritePolicy.ASK, manager.getHttpWritePolicy())
        assertTrue(manager.getAllowedWriteHosts().isEmpty())
    }

    @Test
    fun nullConfirmationHandler_failsClosedForEveryWriteMethod() = runBlocking {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val result = plugin.execute(request(method, ITEMS_URL, "body" to "x"))

            assertTrue("$method must fail", result.isError)
            assertEquals("User denied HTTP $method request to https://api.example.com/items.", result.content)
        }
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun nullConfirmationHandler_doesNotBlockReads() = runBlocking {
        val result = plugin.execute(get())

        assertFalse(result.isError)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun preApprovedHost_bypassesThePrompt() = runBlocking {
        manager.addAllowedWriteHost("api.example.com")
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        val result = plugin.execute(request("POST", ITEMS_URL, "body" to "x"), handler)

        assertFalse(result.isError)
        assertEquals(1, transport.sent.size)
        assertTrue(handler.prompts.isEmpty())
    }

    @Test
    fun alwaysAllowPolicy_bypassesThePrompt() = runBlocking {
        manager.setHttpWritePolicy(HttpWritePolicy.ALWAYS_ALLOW)
        val handler = RecordingHandler(ToolConfirmationDecision.DENY)

        val result = plugin.execute(request("POST", "https://other.example.org/x", "body" to "x"), handler)

        assertFalse(result.isError)
        assertEquals(1, transport.sent.size)
        assertTrue(handler.prompts.isEmpty())
    }

    @Test
    fun confirmationHandlerFailure_failsClosedAndExplainsWhy() = runBlocking {
        val failing = ToolConfirmationHandler { throw IllegalStateException("dialog crashed") }

        val result = plugin.execute(request("POST", ITEMS_URL, "body" to "x"), failing)

        assertTrue(result.isError)
        assertEquals("call_1", result.toolCallId)
        assertEquals(
            "HTTP POST request to https://api.example.com/items was not approved: dialog crashed",
            result.content
        )
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun confirmationHandlerCancellation_propagatesWithoutSending() = runBlocking {
        val cancelling = ToolConfirmationHandler { throw CancellationException("stop") }

        try {
            plugin.execute(request("POST", ITEMS_URL, "body" to "x"), cancelling)
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertTrue(transport.sent.isEmpty())
        }
    }

    @Test
    fun write_bodyPreviewIsTruncatedWhileTheFullBodyIsSent() = runBlocking {
        val body = "x".repeat(HttpRequestPlugin.MAX_BODY_PREVIEW_CHARS + 500)
        val handler = allowOnce()

        plugin.execute(request("POST", ITEMS_URL, "body" to body), handler)

        assertEquals(HttpRequestPlugin.MAX_BODY_PREVIEW_CHARS, handler.prompts.single().bodyPreview!!.length)
        assertEquals(body, transport.sent.single().bodyText)
    }

    @Test
    fun write_withoutABody_hasAnEmptyBodyAndNoContentType() = runBlocking {
        val handler = allowOnce()

        plugin.execute(request("POST", ITEMS_URL), handler)
        plugin.execute(request("POST", ITEMS_URL, "body" to null), handler)

        assertEquals(listOf("", ""), handler.prompts.map { it.bodyPreview })
        for (sent in transport.sent) {
            assertEquals("", sent.bodyText)
            assertNull("no Content-Type when no body was given", sent.header("Content-Type"))
        }
    }

    @Test
    fun write_bodyGivenAsAJsonObjectIsSerialised() = runBlocking {
        plugin.execute(request("POST", ITEMS_URL, "body" to JSONObject(mapOf("a" to 1))), allowOnce())

        assertEquals("""{"a":1}""", transport.sent.single().bodyText)
    }

    @Test
    fun write_contentTypeArgumentSetsTheHeaderAndTheBodyEncoding() = runBlocking {
        plugin.execute(
            request("POST", ITEMS_URL, "body" to "café", "content_type" to "text/plain; charset=iso-8859-1"),
            allowOnce()
        )

        val sent = transport.sent.single()
        assertEquals("text/plain; charset=iso-8859-1", sent.header("Content-Type"))
        assertEquals("café", sent.bodyText)
        assertEquals("ISO-8859-1 uses one byte per character (UTF-8 would need 5)", 4, sent.bodyBytes!!.size)
    }

    @Test
    fun write_explicitContentTypeHeaderWinsOverTheArgument() = runBlocking {
        plugin.execute(
            request(
                "POST", ITEMS_URL,
                "body" to "<a/>",
                "content_type" to "text/plain",
                "headers" to JSONObject(mapOf("content-type" to "application/xml"))
            ),
            allowOnce()
        )

        assertEquals("application/xml", transport.sent.single().header("Content-Type"))
    }

    @Test
    fun delete_withoutABody_sendsNoBodyAndNoContentType() = runBlocking {
        val handler = allowOnce()

        plugin.execute(request("DELETE", "https://api.example.com/items/7"), handler)

        val sent = transport.sent.single()
        assertEquals("DELETE", sent.method)
        assertNull(sent.bodyBytes)
        assertNull(sent.header("Content-Type"))
        assertNull(handler.prompts.single().bodyPreview)
    }

    @Test
    fun write_confirmationUrlIsTheNormalisedUrl() = runBlocking {
        val handler = allowOnce()

        plugin.execute(request("POST", "https://API.Example.com", "body" to ""), handler)

        assertEquals("https://api.example.com/", handler.prompts.single().url)
    }

    // --- Host auth profiles ---

    @Test
    fun hostAuthProfile_injectsSavedCredentials_andOverridesModelSuppliedValues() = runBlocking {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer saved-token"))

        plugin.execute(
            get(ITEMS_URL, "headers" to JSONObject(mapOf("authorization" to "Bearer dummy", "Accept" to "application/json")))
        )

        val sent = transport.sent.single()
        assertEquals(listOf("Bearer saved-token"), sent.headers.values("Authorization"))
        assertEquals("exactly one Authorization header, whatever the casing the model used", 1, sent.headers.names().count { it.equals("Authorization", ignoreCase = true) })
        assertEquals("application/json", sent.header("Accept"))
        assertEquals(listOf("Accept", "Authorization"), sent.headers.names().toList())
    }

    @Test
    fun hostAuthProfile_matchesTheHostCaseInsensitively() = runBlocking {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "API.Example.COM", "X-Api-Key", "key-one"))
        manager.saveHostAuthProfile(HostAuthProfile("p2", "api.example.com", "Private-Token", "token-two"))

        plugin.execute(get("https://api.example.com/items"))
        plugin.execute(get("https://API.EXAMPLE.COM/items"))

        assertEquals(2, transport.sent.size)
        for (sent in transport.sent) {
            assertEquals("key-one", sent.header("X-Api-Key"))
            assertEquals("token-two", sent.header("Private-Token"))
        }
    }

    @Test
    fun hostAuthProfile_neverLeaksCredentialsToOtherHosts() = runBlocking {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer saved-token"))

        for (url in listOf(
            "https://other.example.com/items",
            "https://sub.api.example.com/items",
            "https://api.example.com.evil.org/items",
            "https://notapi.example.com/items",
            "http://api.example.com.evil.org:8080/items"
        )) {
            plugin.execute(get(url))
            assertNull("credentials leaked to $url", transport.sent.last().header("Authorization"))
        }
    }

    @Test
    fun hostAuthProfile_withABlankValueIsIgnored() = runBlocking {
        // e.g. the encrypted value went missing: never clobber a model-supplied header with emptiness.
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", ""))

        plugin.execute(get(ITEMS_URL, "headers" to JSONObject(mapOf("Authorization" to "Bearer from-model"))))

        assertEquals("Bearer from-model", transport.sent.single().header("Authorization"))
    }

    @Test
    fun writeConfirmation_redactsSecretsButTheRealValuesAreSent() = runBlocking {
        manager.saveHostAuthProfile(HostAuthProfile("p1", "api.example.com", "Authorization", "Bearer saved-token"))
        val handler = allowOnce()

        plugin.execute(
            request(
                "POST", ITEMS_URL,
                "headers" to JSONObject(
                    mapOf(
                        "X-Api-Key" to "k-123",
                        "Cookie" to "session=abc",
                        "Proxy-Authorization" to "Basic xyz",
                        "X-Auth-Token" to "t-1",
                        "X-Secret" to "s-1",
                        "X-Password" to "p-1",
                        "X-Access-Key" to "ak-1",
                        "Accept" to "application/json",
                        "X-Request-Id" to "rid-1"
                    )
                ),
                "body" to "{}"
            ),
            handler
        )

        val redacted = "••••••"
        assertEquals(
            mapOf(
                "X-Api-Key" to redacted,
                "Cookie" to redacted,
                "Proxy-Authorization" to redacted,
                "X-Auth-Token" to redacted,
                "X-Secret" to redacted,
                "X-Password" to redacted,
                "X-Access-Key" to redacted,
                "Accept" to "application/json",
                "X-Request-Id" to "rid-1",
                "Authorization" to redacted,
                "Content-Type" to "application/json"
            ),
            handler.prompts.single().headers
        )
        assertFalse(handler.prompts.single().toString().contains("saved-token"))

        val sent = transport.sent.single()
        assertEquals("Bearer saved-token", sent.header("Authorization"))
        assertEquals("k-123", sent.header("X-Api-Key"))
        assertEquals("session=abc", sent.header("Cookie"))
        assertEquals("Basic xyz", sent.header("Proxy-Authorization"))
        assertEquals("t-1", sent.header("X-Auth-Token"))
        assertEquals("s-1", sent.header("X-Secret"))
        assertEquals("p-1", sent.header("X-Password"))
        assertEquals("ak-1", sent.header("X-Access-Key"))
    }

    @Test
    fun redactHeaders_onlyTouchesSensitiveHeadersAndPreservesOrder() {
        val redacted = HttpRequestPlugin.redactHeaders(
            linkedMapOf(
                "Accept" to "application/json",
                "authorization" to "Bearer x",
                "X-CUSTOM-HEADER" to "v",
                "Cookie" to "c",
                "X-Api-Key" to "k",
                "Api-Key" to "k2",
                "Set-Password" to "p",
                "Content-Type" to "application/json"
            ),
            injectedHeaderNames = setOf("x-custom-header")
        )

        assertEquals(
            listOf(
                "Accept" to "application/json",
                "authorization" to "••••••",
                "X-CUSTOM-HEADER" to "••••••",
                "Cookie" to "••••••",
                "X-Api-Key" to "••••••",
                "Api-Key" to "••••••",
                "Set-Password" to "••••••",
                "Content-Type" to "application/json"
            ),
            redacted.toList()
        )
        assertEquals(emptyMap<String, String>(), HttpRequestPlugin.redactHeaders(emptyMap(), emptySet()))
    }

    // --- Response handling ---

    @Test
    fun redirect_isReturnedWithItsLocationForTheModelToFollow() = runBlocking {
        transport.respond = {
            it.respond(302, "Found", "text/html", "", headers = mapOf("Location" to "https://www.example.com/login"))
        }

        val result = plugin.execute(get("https://example.com/account"))

        assertFalse(result.isError)
        assertEquals(
            "HTTP 302 Found\nContent-Type: text/html\nLocation: https://www.example.com/login",
            result.content
        )
        assertEquals("redirects must not be followed implicitly", 1, transport.sent.size)
    }

    @Test
    fun httpErrorStatus_returnsStatusAndBodyAsAnErrorResult() = runBlocking {
        transport.respond = { it.respond(404, "Not Found", "application/json", """{"error":"no such item"}""") }

        val notFound = plugin.execute(get("https://api.example.com/items/999"))

        assertTrue(notFound.isError)
        assertEquals(
            "HTTP 404 Not Found\nContent-Type: application/json\n\n{\"error\":\"no such item\"}",
            notFound.content
        )

        transport.respond = { it.respond(503, "Service Unavailable", "text/plain", "down for maintenance") }

        val unavailable = plugin.execute(get(ITEMS_URL))

        assertTrue(unavailable.isError)
        assertEquals("HTTP 503 Service Unavailable\nContent-Type: text/plain\n\ndown for maintenance", unavailable.content)
    }

    @Test
    fun successAndRedirectStatuses_areNotErrors() = runBlocking {
        for (code in listOf(200, 201, 202, 204, 301, 304)) {
            transport.respond = { it.respond(code, "Status", null, null) }

            val result = plugin.execute(get(ITEMS_URL))

            assertFalse("HTTP $code must not be flagged as an error", result.isError)
            assertEquals("HTTP $code Status", result.content)
        }
    }

    @Test
    fun networkIOException_returnsAnErrorResult() = runBlocking {
        transport.respond = { throw IOException("Failed to connect to api.example.com") }

        val result = plugin.execute(get(ITEMS_URL))

        assertTrue(result.isError)
        assertEquals("call_1", result.toolCallId)
        assertEquals("http_request", result.name)
        assertEquals(
            "HTTP GET request to https://api.example.com/items failed: Failed to connect to api.example.com",
            result.content
        )
    }

    @Test
    fun largeResponse_isCappedAtMaxResponseBytes() = runBlocking {
        transport.respond = { it.respond(200, "OK", "text/plain", "x".repeat(HttpRequestPlugin.MAX_RESPONSE_BYTES + 1_000)) }

        val result = plugin.execute(get(ITEMS_URL))

        assertFalse(result.isError)
        assertEquals(
            "HTTP 200 OK\nContent-Type: text/plain\n\n" +
                "x".repeat(HttpRequestPlugin.MAX_RESPONSE_BYTES) +
                "\n\n[... response body truncated at ${HttpRequestPlugin.MAX_RESPONSE_BYTES} bytes ...]",
            result.content
        )
    }

    @Test
    fun response_exactlyAtTheCapIsNotMarkedAsTruncated() = runBlocking {
        transport.respond = { it.respond(200, "OK", "text/plain", "y".repeat(HttpRequestPlugin.MAX_RESPONSE_BYTES)) }

        val result = plugin.execute(get(ITEMS_URL))

        assertEquals(
            "HTTP 200 OK\nContent-Type: text/plain\n\n" + "y".repeat(HttpRequestPlugin.MAX_RESPONSE_BYTES),
            result.content
        )
    }

    @Test
    fun responseCap_neverLeavesHalfACharacter() = runBlocking {
        // The odd leading byte makes the cap fall in the middle of a 2-byte character.
        transport.respond = {
            it.respond(200, "OK", "text/plain; charset=utf-8", "a" + "é".repeat(HttpRequestPlugin.MAX_RESPONSE_BYTES / 2 + 10))
        }

        val result = plugin.execute(get(ITEMS_URL))

        val body = result.content.substringAfter("\n\n").substringBeforeLast("\n\n[")
        assertEquals(HttpRequestPlugin.MAX_RESPONSE_BYTES / 2, body.length)
        assertTrue(body.startsWith("aé"))
        assertFalse("a partial character must be trimmed, not replaced", body.contains('\uFFFD'))
        assertTrue(result.content.endsWith("\n\n[... response body truncated at ${HttpRequestPlugin.MAX_RESPONSE_BYTES} bytes ...]"))
    }

    // --- Cancellation ---

    /** Transport that blocks like a socket read until OkHttp's cancel() is observed, then fails the call. */
    private fun FakeTransport.blockUntilCancelled(onStarted: () -> Unit = {}) {
        respond = { sent ->
            onStarted()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!sent.call.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            throw IOException("Canceled")
        }
    }

    /** Runs a request on [plugin] that blocks until cancelled, cancels the caller and reports what came out and how long the join took. */
    private suspend fun CoroutineScope.executeAndCancel(plugin: HttpRequestPlugin): Pair<Throwable?, Long> {
        val started = CompletableDeferred<Unit>()
        transport.blockUntilCancelled { started.complete(Unit) }
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            try {
                plugin.execute(get("https://slow.example.com/wait"))
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }
        withTimeout(5_000) { started.await() }
        val cancelAt = System.nanoTime()
        job.cancelAndJoin()
        return outcome.await() to (System.nanoTime() - cancelAt) / 1_000_000
    }

    @Test
    fun cancellation_abortsTheInFlightCall_andPropagates() = runBlocking {
        val (failure, elapsedMs) = executeAndCancel(plugin)

        assertTrue("expected a CancellationException but got $failure", failure is CancellationException)
        assertTrue("the in-flight call should be cancelled promptly (took ${elapsedMs}ms)", elapsedMs < 2_000)
        assertTrue(transport.sent.single().call.isCanceled())
    }

    @Test
    fun cancellation_isNotStarvedWhenTheOnlyIoThreadIsBusy() = runBlocking {
        // The blocking request occupies the single thread of this dispatcher, so any work that
        // needs to be dispatched to it before the call can be cancelled would wait on itself.
        val singleThreaded = HttpRequestPlugin(
            manager,
            OkHttpClient.Builder().addInterceptor(transport).build(),
            Dispatchers.IO.limitedParallelism(1)
        )

        val (failure, elapsedMs) = executeAndCancel(singleThreaded)

        assertTrue("expected a CancellationException but got $failure", failure is CancellationException)
        assertTrue("the cancel was starved behind the blocked IO thread (took ${elapsedMs}ms)", elapsedMs < 2_000)
        assertTrue(transport.sent.single().call.isCanceled())
    }

    // --- ToolRegistry integration ---

    @Test
    fun registry_onlyAdvertisesTheToolWhenEnabled_andKeepsErrorFlags() = runBlocking {
        val registry = ToolRegistry(listOf(plugin))
        assertTrue("a disabled plugin advertises no tools", registry.activeTools().isEmpty())

        manager.setPluginEnabled(ToolConfigManager.PLUGIN_HTTP_REQUEST, true)
        assertEquals(listOf("http_request"), registry.activeTools().map { it.name })

        val denied = registry.execute(request("POST", ITEMS_URL, "body" to "x"), RecordingHandler(ToolConfirmationDecision.DENY))
        assertTrue(denied.isError)
        assertTrue(transport.sent.isEmpty())

        val ok = registry.execute(get(), allowOnce())
        assertFalse(ok.isError)
        assertEquals("HTTP 200 OK\nContent-Type: application/json\n\n{\"ok\":true}", ok.content)
        assertEquals(1, transport.sent.size)
    }

    private companion object {
        const val ITEMS_URL = "https://api.example.com/items"
        const val HTML_URL = "https://www.example.com/page"

        val SAMPLE_HTML = """
            <!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8">
                <title>Sloth page</title>
                <script type="text/javascript">
                  var x = 1 < 2; alert("script content must vanish");
                </script>
                <style>
                  body { color: red; } /* style content must vanish */
                </style>
                <!-- a comment that must vanish -->
              </head>
              <body>
                <h1>Welcome&nbsp;to SlothSpeak</h1>
                <p>Sloths are <b>slow</b> &amp; steady.</p>
                <!--[if IE]><p>conditional comment</p><![endif]-->
                <p>It&#39;s <a href="/x">"tree"</a> life &copy; 2026</p>
                <div>   line one
                     line two   </div>
              </body>
            </html>
        """.trimIndent()
    }
}
