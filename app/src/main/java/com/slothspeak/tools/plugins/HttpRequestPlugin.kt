package com.slothspeak.tools.plugins

import android.util.Log
import com.slothspeak.data.HttpWritePolicy
import com.slothspeak.data.ToolConfigManager
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolConfirmationRequest
import com.slothspeak.tools.ToolDefinition
import com.slothspeak.tools.ToolPlugin
import com.slothspeak.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * `curl`-like `http_request` tool: lets the LLM read web pages / APIs and, with the user's
 * approval, write to them.
 *
 * Safety rails:
 * - **Write confirmation** — `GET`/`HEAD` run unattended. `POST`/`PUT`/`PATCH`/`DELETE` are only
 *   sent after the user picks *Allow once*, *Always allow host* or *Always allow all* (the last
 *   two are persisted through [ToolConfigManager]). When no [ToolConfirmationHandler] is available
 *   the write fails closed.
 * - **Host Auth Profiles** — credentials the user saved for the target host (in
 *   EncryptedSharedPreferences) are attached automatically and always win over model-supplied
 *   values for the same header, so secrets never need to appear in the chat. They are redacted
 *   (`••••••`) in the confirmation preview.
 * - **Bounded output** — at most [MAX_RESPONSE_BYTES] of a response body is read into memory.
 * - **No automatic redirects** in the default client (like `curl` without `-L`): following a
 *   redirect would replay the body or the saved credentials against a host the user never
 *   approved. 3xx responses are returned with their `Location` header so the model can follow
 *   them explicitly — a redirected write then needs its own confirmation.
 */
class HttpRequestPlugin(
    private val toolConfigManager: ToolConfigManager,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /** Dispatcher the blocking OkHttp call runs on; injectable for tests. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ToolPlugin {

    override val id: String = ToolConfigManager.PLUGIN_HTTP_REQUEST

    override val displayName: String = "HTTP Request (curl)"

    override val description: String =
        "Perform HTTP requests (GET, POST, PUT, PATCH, DELETE, HEAD) to web pages or APIs."

    /** Works out of the box: no credentials are required to use the plugin. */
    override fun isConfigured(): Boolean = true

    override fun isEnabled(): Boolean = toolConfigManager.isPluginEnabled(id)

    override fun tools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = TOOL_NAME,
            description = TOOL_DESCRIPTION,
            inputSchemaJson = INPUT_SCHEMA_JSON
        )
    )

    override suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler?): ToolResult {
        if (call.name != TOOL_NAME) {
            return call.error("Unknown tool: ${call.name} (expected $TOOL_NAME)")
        }

        val args = try {
            parseArguments(call.argumentsJson)
        } catch (e: IllegalArgumentException) {
            return call.error(e.message ?: "Invalid arguments")
        }

        // Built before any confirmation prompt so an invalid header fails fast instead of asking
        // the user to approve a request that could never be sent.
        val prepared = try {
            prepareRequest(args)
        } catch (e: IllegalArgumentException) {
            return call.error(e.message ?: "Invalid request")
        }

        if (args.method !in READ_ONLY_METHODS) {
            approveWrite(call, args.url, prepared, confirmationHandler)?.let { return it }
        }

        return executeRequest(call, prepared.request, args.stripHtml)
    }

    /** Everything that is needed to send the request and to describe it to the user. */
    private class PreparedRequest(
        val request: Request,
        /** Final header map (model headers + saved credentials + resolved Content-Type). */
        val headers: Map<String, String>,
        /** Lowercase names of the headers that came from saved host credentials. */
        val injectedHeaderNames: Set<String>,
        val bodyText: String?
    )

    /**
     * Merges saved credentials, resolves the content type and builds the OkHttp request.
     * @throws IllegalArgumentException with a model-facing message for invalid headers/content type.
     */
    private fun prepareRequest(args: Arguments): PreparedRequest {
        // Saved credentials for this host always win over model-supplied values for the same
        // header (case-insensitive), so the model never has to guess them.
        val headers = LinkedHashMap(args.headers)
        val injectedHeaderNames = HashSet<String>()
        for (profile in toolConfigManager.getHostAuthProfilesForHost(args.url.host)) {
            if (profile.headerName.isBlank() || profile.headerValue.isBlank()) continue
            headers.putReplacing(profile.headerName, profile.headerValue)
            injectedHeaderNames.add(profile.headerName.lowercase())
        }
        if (injectedHeaderNames.isNotEmpty()) {
            Log.d(TAG, "Attached ${injectedHeaderNames.size} saved auth header(s) for ${args.url.host}")
        }

        // POST/PUT/PATCH always carry a body (empty when omitted); DELETE only when one is given.
        val hasBody = args.method in ALWAYS_BODY_METHODS || (args.method == "DELETE" && args.body != null)
        val bodyText = if (hasBody) args.body.orEmpty() else null
        val contentTypeValue: String? = if (hasBody) {
            headers.firstValue("Content-Type")
                ?: args.contentType
                ?: DEFAULT_CONTENT_TYPE.takeIf { args.body != null }
        } else {
            null
        }
        val contentType = if (contentTypeValue != null) {
            contentTypeValue.toMediaTypeOrNull() ?: throw IllegalArgumentException(
                "Invalid 'content_type': \"$contentTypeValue\" is not a valid media type (expected e.g. \"application/json\")."
            )
        } else {
            null
        }
        // Keep the header in sync with the body's media type: OkHttp overwrites Content-Type from
        // the request body on the wire, and the confirmation preview shows the merged headers.
        if (contentType != null) headers.putReplacing("Content-Type", contentType.toString())
        val requestBody = bodyText?.toRequestBody(contentType)

        val request = try {
            Request.Builder()
                .url(args.url)
                .method(args.method, requestBody)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid headers: ${e.message}", e)
        }
        return PreparedRequest(request, headers, injectedHeaderNames, bodyText)
    }

    /**
     * Returns null when a mutating request may be sent, otherwise the `isError` [ToolResult] to
     * return. Skips the prompt when the user already allowed this host or all writes; fails
     * closed (nothing is sent) when nobody can approve the request.
     */
    private suspend fun approveWrite(
        toolCall: ToolCall,
        url: HttpUrl,
        prepared: PreparedRequest,
        confirmationHandler: ToolConfirmationHandler?
    ): ToolResult? {
        val method = prepared.request.method
        val host = url.host.lowercase()
        if (isPreApproved(host, url.port)) return null

        val handler = confirmationHandler ?: return toolCall.denied(method, url)
        val decision = try {
            handler.requestConfirmation(
                ToolConfirmationRequest(
                    toolName = TOOL_NAME,
                    method = method,
                    url = url.toString(),
                    host = host,
                    headers = redactHeaders(prepared.headers, prepared.injectedHeaderNames),
                    bodyPreview = prepared.bodyText?.take(MAX_BODY_PREVIEW_CHARS)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: a broken confirmation flow must never turn into an unapproved write.
            Log.w(TAG, "Confirmation for $method $host failed: ${e}")
            return toolCall.error(
                "HTTP $method request to $url was not approved: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        return when (decision) {
            ToolConfirmationDecision.ALLOW_ONCE -> null
            ToolConfirmationDecision.ALWAYS_ALLOW_HOST -> {
                toolConfigManager.addAllowedWriteHost(host)
                null
            }
            ToolConfirmationDecision.ALWAYS_ALLOW_ALL -> {
                toolConfigManager.setHttpWritePolicy(HttpWritePolicy.ALWAYS_ALLOW)
                null
            }
            ToolConfirmationDecision.DENY -> toolCall.denied(method, url)
        }
    }

    /** Runs the request on [ioDispatcher] and maps transport failures to `isError` results. */
    private suspend fun executeRequest(toolCall: ToolCall, request: Request, stripHtml: Boolean): ToolResult {
        val call = httpClient.newCall(request)
        try {
            return withContext(ioDispatcher) {
                // execute() below blocks this IO thread. Cancelling a coroutine that is stuck in
                // non-suspending code is not observed until it returns, but cancelling a *suspended*
                // child is immediate: this watcher's finally therefore aborts the socket as soon as
                // the caller is cancelled (user cancel, ToolRegistry timeout). It uses Unconfined so
                // the cancel never waits for a free IO thread behind the ones blocked in execute().
                val finished = AtomicBoolean(false)
                val cancellationWatcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) call.cancel()
                    }
                }
                try {
                    call.execute().use { response -> formatResponse(toolCall, response, stripHtml) }
                } finally {
                    finished.set(true)
                    cancellationWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A cancelled call surfaces as an IOException too: keep it a cancellation.
            coroutineContext.ensureActive()
            Log.w(TAG, "${request.method} ${request.url.host} failed: ${e}")
            return toolCall.error(
                "HTTP ${request.method} request to ${request.url} failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun formatResponse(toolCall: ToolCall, response: Response, stripHtml: Boolean): ToolResult {
        val contentType = response.header("Content-Type")
        val body = readBodyText(response.body)

        var text = body.text
        if (stripHtml && contentType != null && contentType.contains("text/html", ignoreCase = true)) {
            text = stripHtml(text)
        }

        return ToolResult(
            toolCallId = toolCall.id,
            name = toolCall.name,
            content = buildString {
                append("HTTP ").append(response.code)
                if (response.message.isNotBlank()) append(' ').append(response.message)
                if (contentType != null) append("\nContent-Type: ").append(contentType)
                response.header("Location")?.let { append("\nLocation: ").append(it) }
                if (text.isNotEmpty()) append("\n\n").append(text)
                if (body.truncated) {
                    append("\n\n[... response body truncated at ").append(MAX_RESPONSE_BYTES).append(" bytes ...]")
                }
            },
            isError = response.code >= 400
        )
    }

    /**
     * Reads at most [MAX_RESPONSE_BYTES] of the body (one extra byte is buffered just to detect
     * truncation) so a huge download can never OOM the app. Binary content is not decoded.
     */
    private fun readBodyText(body: ResponseBody?): BodyText {
        if (body == null) return BodyText.EMPTY
        val mediaType = body.contentType()
        if (!isTextual(mediaType)) {
            val length = body.contentLength()
            val size = if (length >= 0) "$length bytes" else "unknown size"
            return BodyText("[binary content (${mediaType ?: "unknown type"}, $size) not shown]", truncated = false)
        }
        val charset: Charset = mediaType?.charset() ?: Charsets.UTF_8
        val source = body.source()
        val truncated = source.request(MAX_RESPONSE_BYTES + 1L)
        val bytes = source.buffer.readByteArray(if (truncated) MAX_RESPONSE_BYTES.toLong() else source.buffer.size)
        var text = String(bytes, charset)
        // Never leave half of a multi-byte character at the truncation point.
        if (truncated && text.isNotEmpty() && text.last() == REPLACEMENT_CHAR) text = text.dropLast(1)
        return BodyText(text, truncated)
    }

    private fun isPreApproved(host: String, port: Int): Boolean {
        if (toolConfigManager.getHttpWritePolicy() == HttpWritePolicy.ALWAYS_ALLOW) return true
        val allowedHosts = toolConfigManager.getAllowedWriteHosts()
        return host in allowedHosts || "$host:$port" in allowedHosts
    }

    private fun ToolCall.error(message: String): ToolResult =
        ToolResult(toolCallId = id, name = name, content = message, isError = true)

    private fun ToolCall.denied(method: String, url: HttpUrl): ToolResult =
        error("User denied HTTP $method request to $url.")

    // --- Argument parsing ---

    private class Arguments(
        val method: String,
        val url: HttpUrl,
        val headers: LinkedHashMap<String, String>,
        val body: String?,
        val contentType: String?,
        val stripHtml: Boolean
    )

    /** @throws IllegalArgumentException with a model-facing message when the arguments are invalid. */
    private fun parseArguments(argumentsJson: String): Arguments {
        val root = try {
            JSONObject(argumentsJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid arguments: expected a JSON object (${e.message})")
        }

        val method = root.optString("method").trim().uppercase()
        require(method in ALLOWED_METHODS) {
            "Invalid 'method': must be one of ${ALLOWED_METHODS.joinToString(", ")}."
        }

        val rawUrl = root.optStringOrNull("url")
        val url = rawUrl?.toHttpUrlOrNull()
        if (url == null || url.scheme !in ALLOWED_SCHEMES) {
            val scheme = rawUrl?.let { SCHEME_PATTERN.find(it)?.groupValues?.get(1)?.lowercase() }
            val detail = if (scheme != null && scheme !in ALLOWED_SCHEMES) {
                " scheme \"$scheme\" is not supported, use http:// or https://"
            } else {
                " a valid http:// or https:// URL is required"
            }
            throw IllegalArgumentException("Invalid 'url':$detail.")
        }

        val headers = LinkedHashMap<String, String>()
        when (val rawHeaders = root.opt("headers")) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> rawHeaders.copyHeadersTo(headers)
            // Models sometimes double-encode the object; accept a JSON string too.
            is String -> if (rawHeaders.isNotBlank()) {
                try {
                    JSONObject(rawHeaders).copyHeadersTo(headers)
                } catch (e: JSONException) {
                    throw IllegalArgumentException("Invalid 'headers': expected a JSON object of string values (${e.message})")
                }
            }
            else -> throw IllegalArgumentException("Invalid 'headers': expected a JSON object of string values.")
        }

        val body = root.optStringOrNull("body")
        val contentType = root.optStringOrNull("content_type")?.trim()?.ifBlank { null }
        val stripHtml = root.optBoolean("strip_html", true)

        return Arguments(method, url, headers, body, contentType, stripHtml)
    }

    private fun JSONObject.copyHeadersTo(target: MutableMap<String, String>) {
        val names = keys()
        while (names.hasNext()) {
            val name = names.next()
            if (isNull(name)) continue
            val value = optString(name)
            require(name.isNotBlank()) { "Invalid 'headers': header names must not be blank." }
            require(!value.contains('\r') && !value.contains('\n')) {
                "Invalid 'headers': the value of \"$name\" must not contain line breaks."
            }
            target[name.trim()] = value.trim()
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)

    /** Removes any case-variant of [name] before inserting, so a header is never sent twice. */
    private fun MutableMap<String, String>.putReplacing(name: String, value: String) {
        keys.filter { it.equals(name, ignoreCase = true) }.forEach { remove(it) }
        put(name, value)
    }

    private fun Map<String, String>.firstValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private class BodyText(val text: String, val truncated: Boolean) {
        companion object {
            val EMPTY = BodyText("", truncated = false)
        }
    }

    companion object {
        const val TOOL_NAME = "http_request"

        /** Methods the model may use; anything else is rejected before any request is built. */
        val ALLOWED_METHODS: Set<String> = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /** Largest response body (in bytes) that is read into memory. */
        const val MAX_RESPONSE_BYTES = 256 * 1024

        /** Longest body prefix shown to the user in the write confirmation. */
        const val MAX_BODY_PREVIEW_CHARS = 2_000

        /** Placeholder substituted for secrets in confirmation previews. */
        const val REDACTED = "••••••"

        private const val TAG = "HttpRequestPlugin"
        private const val DEFAULT_CONTENT_TYPE = "application/json"
        private val ALLOWED_SCHEMES = setOf("http", "https")
        private val READ_ONLY_METHODS = setOf("GET", "HEAD")
        private val ALWAYS_BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        private val SENSITIVE_HEADER_NAMES = setOf("authorization", "proxy-authorization", "cookie", "x-api-key")
        private val SENSITIVE_HEADER_FRAGMENTS = listOf("token", "secret", "key", "password")

        private val TEXTUAL_APPLICATION_SUBTYPES = setOf(
            "json", "xml", "javascript", "x-javascript", "ecmascript", "x-www-form-urlencoded",
            "yaml", "x-yaml", "graphql", "manifest+json"
        )

        private const val TOOL_DESCRIPTION =
            "Perform an HTTP request to a web page or API (like curl). GET and HEAD only read; " +
                "POST, PUT, PATCH and DELETE send `body` and require the user's approval. " +
                "Returns the HTTP status line, the Content-Type and Location headers and the " +
                "response body; HTML pages are reduced to readable text unless strip_html is false. " +
                "Redirects are not followed automatically: issue a new request with the Location URL. " +
                "Credentials saved for the host are attached automatically, so never put secrets in headers."

        private val INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "method": {
                  "type": "string",
                  "enum": ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"],
                  "description": "HTTP method. GET and HEAD are read-only; POST, PUT, PATCH and DELETE modify data and need the user's approval."
                },
                "url": {
                  "type": "string",
                  "description": "Full http:// or https:// URL to request."
                },
                "headers": {
                  "type": "object",
                  "additionalProperties": { "type": "string" },
                  "description": "Optional request headers, e.g. {'Accept': 'application/json'}."
                },
                "body": {
                  "type": "string",
                  "description": "Request body for POST, PUT, PATCH or DELETE. Ignored for GET and HEAD."
                },
                "content_type": {
                  "type": "string",
                  "description": "Media type of the body, e.g. text/plain. Defaults to application/json when a body is provided and no Content-Type header is set."
                },
                "strip_html": {
                  "type": "boolean",
                  "default": true,
                  "description": "Strip scripts, styles and tags from text/html responses to get readable text. Set to false to receive the raw HTML."
                }
              },
              "required": ["method", "url"]
            }
        """

        // --- HTML stripping ---

        // Elements whose content is never page prose: dropped together with their content.
        private val DROPPED_ELEMENT = Regex("(?is)<(script|style|svg|template|textarea|select)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)")
        private val HTML_COMMENT = Regex("(?s)<!--.*?(?:-->|\\z)")
        private val CDATA_SECTION = Regex("(?s)<!\\[CDATA\\[.*?(?:\\]\\]>|\\z)")
        private val BLOCK_LEVEL_TAG = Regex(
            "(?i)</?(?:br|hr|p|div|li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|tfoot|caption|h[1-6]|" +
                "section|article|header|footer|nav|aside|main|blockquote|pre|form|fieldset|address|" +
                "figure|figcaption|details|summary|title|center)\\b[^>]*>"
        )
        private val ANY_TAG = Regex("(?s)</?[A-Za-z][^>]*>|<![^>]*>|<\\?[^>]*>")
        private val HTML_ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
        private val HORIZONTAL_WHITESPACE = Regex("[\\t\\x0B\\f\\u00A0 ]+")
        private val SPACE_AROUND_NEWLINE = Regex(" *\\n *")
        private val EXCESS_BLANK_LINES = Regex("\\n{3,}")
        private const val REPLACEMENT_CHAR = '\uFFFD'

        /**
         * Named HTML entities (the HTML 4 Latin-1 letters plus the most common symbols and
         * punctuation) to plain text. Unknown names are left untouched.
         */
        private val NAMED_ENTITIES: Map<String, String> = mapOf(
            // Structural / punctuation entities that show up in virtually every document.
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
            "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»", "bull" to "•",
            "middot" to "·", "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡",
            "permil" to "‰", "prime" to "′", "Prime" to "″",
            // Symbols.
            "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
            "times" to "×", "divide" to "÷", "micro" to "µ", "euro" to "€", "pound" to "£",
            "yen" to "¥", "cent" to "¢", "curren" to "¤", "larr" to "←", "uarr" to "↑",
            "rarr" to "→", "darr" to "↓", "harr" to "↔", "minus" to "−", "infin" to "∞",
            "ne" to "≠", "le" to "≤", "ge" to "≥", "lowast" to "∗",
            // Latin-1 letters with diacritics (HTML 4 names).
            "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã", "Auml" to "Ä",
            "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É",
            "Ecirc" to "Ê", "Euml" to "Ë", "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î",
            "Iuml" to "Ï", "ETH" to "Ð", "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó",
            "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø", "Ugrave" to "Ù",
            "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "Yacute" to "Ý", "THORN" to "Þ",
            "szlig" to "ß", "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
            "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç", "egrave" to "è",
            "eacute" to "é", "ecirc" to "ê", "euml" to "ë", "igrave" to "ì", "iacute" to "í",
            "icirc" to "î", "iuml" to "ï", "eth" to "ð", "ntilde" to "ñ", "ograve" to "ò",
            "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "oslash" to "ø",
            "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
            "thorn" to "þ", "yuml" to "ÿ", "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š",
            "scaron" to "š", "Yuml" to "Ÿ"
        )

        /**
         * Default client: conservative timeouts (shorter than ToolRegistry's 30 s call timeout) and
         * no automatic redirect following — see the class docs.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * Redacts the values of headers that carry secrets: everything injected from a
         * [com.slothspeak.data.HostAuthProfile] plus well-known sensitive header names
         * (`Authorization`, `Proxy-Authorization`, `Cookie`, `X-Api-Key`) and any header whose
         * name mentions token / secret / key / password. Order is preserved.
         */
        internal fun redactHeaders(headers: Map<String, String>, injectedHeaderNames: Set<String>): Map<String, String> {
            if (headers.isEmpty()) return emptyMap()
            val redacted = LinkedHashMap<String, String>(headers.size)
            for ((name, value) in headers) {
                val lowerName = name.lowercase()
                val sensitive = lowerName in injectedHeaderNames ||
                    lowerName in SENSITIVE_HEADER_NAMES ||
                    SENSITIVE_HEADER_FRAGMENTS.any { lowerName.contains(it) }
                redacted[name] = if (sensitive) REDACTED else value
            }
            return redacted
        }

        /** True when a body of this media type makes sense to hand to the model as text. */
        private fun isTextual(mediaType: MediaType?): Boolean {
            if (mediaType == null) return true
            if (mediaType.type == "text") return true
            if (mediaType.type != "application") return false
            val subtype = mediaType.subtype
            return subtype in TEXTUAL_APPLICATION_SUBTYPES ||
                subtype.endsWith("+json") ||
                subtype.endsWith("+xml") ||
                subtype.endsWith("+yaml")
        }

        /**
         * Turns an HTML document into readable text: removes scripts, styles, comments, inline SVG
         * and form controls (with their content) and every remaining tag, puts line breaks where
         * block elements were, decodes common entities and collapses runs of whitespace / blank lines.
         */
        internal fun stripHtml(html: String): String {
            var text = html
            text = DROPPED_ELEMENT.replace(text, "")
            text = HTML_COMMENT.replace(text, "")
            text = CDATA_SECTION.replace(text, "")
            text = BLOCK_LEVEL_TAG.replace(text, "\n")
            text = ANY_TAG.replace(text, "")
            text = decodeEntities(text)
            return collapseWhitespace(text)
        }

        private fun decodeEntities(text: String): String {
            if ('&' !in text) return text
            // One left-to-right pass: text produced by an entity (e.g. the '&' of '&amp;') is never
            // re-scanned, so '&amp;lt;' and '&#38;lt;' both come out as the literal '&lt;'.
            return HTML_ENTITY.replace(text) { match ->
                val body = match.groupValues[1]
                if (body.startsWith("#")) {
                    val digits = body.substring(1)
                    val codePoint = if (digits[0] == 'x' || digits[0] == 'X') {
                        digits.substring(1).toIntOrNull(16)
                    } else {
                        digits.toIntOrNull()
                    }
                    codePoint?.toCodePointString() ?: match.value
                } else {
                    NAMED_ENTITIES[body] ?: match.value
                }
            }
        }

        private fun Int.toCodePointString(): String? {
            if (this !in 0..Character.MAX_CODE_POINT || this in 0xD800..0xDFFF) return null
            return String(Character.toChars(this))
        }

        private fun collapseWhitespace(text: String): String {
            var out = text.replace("\r\n", "\n").replace('\r', '\n')
            out = HORIZONTAL_WHITESPACE.replace(out, " ")
            out = SPACE_AROUND_NEWLINE.replace(out, "\n")
            out = EXCESS_BLANK_LINES.replace(out, "\n\n")
            return out.trim()
        }
    }
}
