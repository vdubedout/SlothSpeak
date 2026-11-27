package com.slothspeak.tools.plugins

import com.slothspeak.data.ToolConfigManager
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolDefinition
import com.slothspeak.tools.ToolPlugin
import com.slothspeak.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Read-only web search backed by the Brave Search REST API
 * (`GET https://api.search.brave.com/res/v1/web/search`).
 *
 * Exposes a single LLM tool, [TOOL_NAME], that returns the top results for a query as
 * compact numbered entries (title, URL, snippet) so the model can cite or summarise them.
 * Every failure mode — bad arguments, missing API key, HTTP errors, network errors — comes
 * back as an `isError` [ToolResult] with an actionable message instead of an exception,
 * so the agent loop can recover. Only coroutine cancellation propagates.
 *
 * Secrets: the API key lives in [ToolConfigManager]'s EncryptedSharedPreferences and is
 * only ever attached to the outgoing request as the `X-Subscription-Token` header.
 *
 * Rate limiting: Brave's free plan allows about one request per second and answers
 * HTTP 429 when several searches are fired at once. Every HTTP call therefore goes
 * through [awaitRateLimitSlot], which spaces consecutive request starts by
 * [ToolConfigManager.getBraveMinIntervalMs] (default 1 s, configurable in Settings → Tools).
 * The clock and sleep function are injectable so tests can assert the spacing without
 * actually sleeping.
 */
class BraveSearchPlugin(
    private val toolConfigManager: ToolConfigManager,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleepMs: suspend (Long) -> Unit = { delay(it) }
) : ToolPlugin {

    /** Serialises the rate-limiter bookkeeping across simultaneous tool calls. */
    private val rateLimitMutex = Mutex()

    /** Start timestamp of the last HTTP call, or null when none has been sent yet. */
    private var lastRequestAtMs: Long? = null

    /** Parsed once so a malformed [baseUrl] fails fast at construction, not per call. */
    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrl()

    override val id: String = ToolConfigManager.PLUGIN_BRAVE_SEARCH

    override val displayName: String = "Brave Web Search"

    override val description: String = "Search the web using the Brave Search API for up-to-date information."

    override fun isConfigured(): Boolean = toolConfigManager.hasBraveApiKey()

    override fun isEnabled(): Boolean = toolConfigManager.isPluginEnabled(id)

    override fun tools(): List<ToolDefinition> = listOf(TOOL_DEFINITION)

    override suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler?): ToolResult {
        // Web search is a read-only GET with no side effects, so the confirmation
        // handler is intentionally unused here.
        if (call.name != TOOL_NAME) {
            return errorResult(call, "Unknown tool \"${call.name}\" — this plugin only provides \"$TOOL_NAME\".")
        }

        val arguments = try {
            JSONObject(call.argumentsJson)
        } catch (e: JSONException) {
            return errorResult(call, "Invalid arguments JSON: ${e.message}")
        }

        val query = if (arguments.isNull("query")) "" else arguments.optString("query").trim()
        if (query.isEmpty()) {
            return errorResult(call, "Missing required parameter \"query\" (a non-blank search string).")
        }

        // Absent/unparseable count falls back to the default; anything out of range is clamped.
        val count = arguments.optInt("count", DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)

        val freshness = if (arguments.isNull("freshness")) {
            null
        } else {
            arguments.optString("freshness").trim().lowercase().ifEmpty { null }
        }
        if (freshness != null && freshness !in VALID_FRESHNESS) {
            return errorResult(call, "Invalid freshness \"$freshness\". Use one of: ${VALID_FRESHNESS.joinToString(", ")}.")
        }

        val apiKey = toolConfigManager.getBraveApiKey()
        if (apiKey.isNullOrBlank()) {
            return errorResult(call, "Brave Search API key is not configured.")
        }

        val request = buildRequest(query, count, freshness, apiKey)

        return try {
            awaitRateLimitSlot()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    handleResponse(call, query, response)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            errorResult(call, "Brave Search network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Waits until at least [ToolConfigManager.getBraveMinIntervalMs] has elapsed since the
     * previous request started, then claims the current slot. The mutex makes the
     * read-sleep-write sequence atomic, so simultaneous calls queue up instead of
     * hitting Brave in the same instant. A configured interval of 0 disables the delay.
     */
    private suspend fun awaitRateLimitSlot() {
        val intervalMs = toolConfigManager.getBraveMinIntervalMs().toLong()
        rateLimitMutex.withLock {
            val previous = lastRequestAtMs
            if (previous != null && intervalMs > 0) {
                val remainingMs = intervalMs - (nowMs() - previous)
                if (remainingMs > 0) sleepMs(remainingMs)
            }
            lastRequestAtMs = nowMs()
        }
    }

    private fun buildRequest(query: String, count: Int, freshness: String?, apiKey: String): Request {
        val url = baseHttpUrl.newBuilder()
            .addPathSegments(SEARCH_PATH)
            .addQueryParameter("q", query)
            .addQueryParameter("count", count.toString())
            .apply { if (freshness != null) addQueryParameter("freshness", freshness) }
            .build()

        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header(HEADER_SUBSCRIPTION_TOKEN, apiKey)
            .build()
    }

    /** Runs inside the `Response.use { }` block; the body is read exactly once for 2xx. */
    private fun handleResponse(call: ToolCall, query: String, response: Response): ToolResult {
        if (!response.isSuccessful) return httpErrorResult(call, response.code)

        val body = response.body?.string().orEmpty()
        val content = try {
            formatResults(query, body)
        } catch (e: JSONException) {
            return errorResult(call, "Brave Search returned an unexpected response.")
        }
        return ToolResult(toolCallId = call.id, name = call.name, content = content, isError = false)
    }

    private fun httpErrorResult(call: ToolCall, code: Int): ToolResult {
        val message = when (code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                "Invalid Brave Search API key (HTTP $code). Please check your key in Settings → Tools."

            HTTP_TOO_MANY_REQUESTS ->
                "Brave Search rate limit exceeded (HTTP 429). Please try again later."

            else ->
                "Brave Search request failed (HTTP $code)."
        }
        return errorResult(call, message)
    }

    private fun errorResult(call: ToolCall, message: String): ToolResult = ToolResult(
        toolCallId = call.id,
        name = call.name,
        content = message,
        isError = true
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.search.brave.com/"
        const val TOOL_NAME = "brave_web_search"

        const val DEFAULT_COUNT = 5
        const val MIN_COUNT = 1
        const val MAX_COUNT = 10

        /** Brave `freshness` values: past day / week / month / year. */
        val VALID_FRESHNESS: Set<String> = setOf("pd", "pw", "pm", "py")

        private const val SEARCH_PATH = "res/v1/web/search"
        private const val HEADER_SUBSCRIPTION_TOKEN = "X-Subscription-Token"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** JSON Schema *object* forwarded verbatim as the OpenAI `tools[].function.parameters` field. */
        private val TOOL_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "The web search query."
                },
                "count": {
                  "type": "integer",
                  "description": "Number of results to return (1-10, default 5).",
                  "minimum": 1,
                  "maximum": 10,
                  "default": 5
                },
                "freshness": {
                  "type": "string",
                  "description": "Optional. Filter results by discovery date: pd (past day), pw (past week), pm (past month), py (past year).",
                  "enum": ["pd", "pw", "pm", "py"]
                }
              },
              "required": ["query"]
            }
        """.trimIndent()

        /** LLM-facing tool definition; declared after [TOOL_SCHEMA_JSON] (companion initialisers run in order). */
        private val TOOL_DEFINITION = ToolDefinition(
            name = TOOL_NAME,
            description = "Search the web with Brave Search and return the top results " +
                "(title, URL, snippet) for a query. Use for current events or facts that may have changed recently.",
            inputSchemaJson = TOOL_SCHEMA_JSON
        )

        /**
         * Read-only search client. `callTimeout` bounds the whole call on its own, so the request
         * never depends on ToolRegistry's per-call safety net (which also covers user time spent
         * on confirmation prompts of other plugins).
         */
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        // --- Response formatting ---

        private val BLOCK_TAG_REGEX = Regex("(?i)<\\s*/?(?:br|p|div|li)\\b[^>]*>")
        private val ANY_TAG_REGEX = Regex("</?[a-zA-Z][^>]*>")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val HTML_ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

        private val NAMED_ENTITIES: Map<String, String> = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "hellip" to "…",
            "mdash" to "—",
            "ndash" to "–",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "copy" to "©",
            "reg" to "®"
        )

        /**
         * Renders the `web.results` array of a Brave response for LLM consumption, e.g.
         * ```
         * 1. Title Here (2 days ago)
         *    URL: https://example.com
         *    Description snippet here...
         *    - Optional extra snippet
         * ```
         * Throws [JSONException] if [body] is not a JSON object.
         */
        internal fun formatResults(query: String, body: String): String {
            val items = JSONObject(body)
                .optJSONObject("web")
                ?.optJSONArray("results")
                .let { array -> array?.toObjectList() }
                .orEmpty()

            if (items.isEmpty()) return "No web results found for \"$query\"."

            return items.mapIndexed { index, item -> formatResult(index + 1, item) }.joinToString("\n")
        }

        private fun JSONArray.toObjectList(): List<JSONObject> =
            (0 until length()).mapNotNull { i -> opt(i) as? JSONObject }

        private fun formatResult(index: Int, item: JSONObject): String {
            val title = cleanText(item.stringOrBlank("title")).ifBlank { "(no title)" }
            val age = cleanText(item.stringOrBlank("age"))
            val url = cleanText(item.stringOrBlank("url"))
            val description = cleanText(item.stringOrBlank("description"))
            val extraSnippets = item.optJSONArray("extra_snippets")
                ?.let { array -> (0 until array.length()).mapNotNull { i -> (array.opt(i) as? String)?.let(::cleanText)?.takeIf { it.isNotBlank() } } }
                .orEmpty()

            return buildString {
                append(index).append(". ").append(title)
                if (age.isNotBlank()) append(" (").append(age).append(')')
                append('\n')
                if (url.isNotBlank()) append("   URL: ").append(url).append('\n')
                if (description.isNotBlank()) append("   ").append(description).append('\n')
                for (snippet in extraSnippets) {
                    append("   - ").append(snippet).append('\n')
                }
            }.trimEnd('\n')
        }

        /** `optString` renders `null` as the literal string "null"; treat it as absent. */
        private fun JSONObject.stringOrBlank(key: String): String =
            if (isNull(key)) "" else optString(key)

        /**
         * Strips the basic HTML Brave embeds in snippets (e.g. `<strong>` highlights),
         * unescapes common HTML entities and collapses whitespace. Tags are removed before
         * entities are decoded so literal `&lt;b&gt;` text survives as `<b>` rather than being eaten.
         */
        internal fun cleanText(raw: String): String {
            val withoutTags = ANY_TAG_REGEX.replace(BLOCK_TAG_REGEX.replace(raw, " "), "")
            val decoded = unescapeHtmlEntities(withoutTags)
            return WHITESPACE_REGEX.replace(decoded, " ").trim()
        }

        private fun unescapeHtmlEntities(text: String): String = HTML_ENTITY_REGEX.replace(text) { match ->
            val body = match.groupValues[1]
            val replacement = when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let(::codePointToString)

                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let(::codePointToString)

                else -> NAMED_ENTITIES[body]
            }
            replacement ?: match.value
        }

        private fun codePointToString(codePoint: Int): String? {
            val isSurrogate = codePoint in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code
            return if (isSurrogate) null else runCatching { String(Character.toChars(codePoint)) }.getOrNull()
        }
    }
}
