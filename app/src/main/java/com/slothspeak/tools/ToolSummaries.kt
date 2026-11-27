package com.slothspeak.tools

import com.slothspeak.tools.plugins.BraveSearchPlugin
import com.slothspeak.tools.plugins.HttpRequestPlugin
import org.json.JSONException
import org.json.JSONObject

/*
 * Human-readable one-liners derived from a [ToolCall]. They are shown while a tool runs
 * (the pipeline status "Searching web: …") and in the persisted tool trace of an answer.
 * Everything here is best-effort: malformed arguments never throw, they just render raw.
 */

/** One-line status for a tool that is about to run, e.g. `Searching web: "tokyo weather"`. */
internal fun formatToolSummary(call: ToolCall): String {
    val args = parseArguments(call.argumentsJson)
    return when (call.name) {
        BraveSearchPlugin.TOOL_NAME -> {
            val query = args?.queryString("query")?.ifBlank { null } ?: call.argumentsJson
            "Searching web: \"$query\""
        }

        HttpRequestPlugin.TOOL_NAME -> {
            val method = args?.queryString("method")?.uppercase()?.ifBlank { null } ?: "GET"
            val host = args?.queryString("url")?.let(::hostOf)
            if (host == null) "HTTP $method…" else "HTTP $method $host…"
        }

        else -> "Running ${formatToolDisplayName(call.name)}"
    }
}

/** Short single-line rendering of a tool's arguments for the tool trace. */
internal fun formatToolArguments(name: String, argumentsJson: String): String {
    val args = parseArguments(argumentsJson) ?: return argumentsJson.take(MAX_RAW_CHARS)
    return when (name) {
        BraveSearchPlugin.TOOL_NAME -> buildList {
            args.queryString("query")?.let { add("query=\"$it\"") }
            if (args.has("count")) add("count=${args.optInt("count")}")
            args.queryString("freshness")?.let { add("freshness=$it") }
        }.joinToString(" · ")

        HttpRequestPlugin.TOOL_NAME -> listOfNotNull(
            args.queryString("method")?.uppercase(),
            args.queryString("url")
        ).joinToString(" ")

        else -> args.keys().asSequence()
            .mapNotNull { key -> args.queryString(key)?.let { "$key=$it" } }
            .joinToString(" · ")
            .ifEmpty { argumentsJson.take(MAX_RAW_CHARS) }
    }
}

/** User-facing plugin name for a tool; unknown tools keep their raw name. */
internal fun formatToolDisplayName(name: String): String = when (name) {
    BraveSearchPlugin.TOOL_NAME -> "Brave Web Search"
    HttpRequestPlugin.TOOL_NAME -> "HTTP Request"
    else -> name
}

private const val MAX_RAW_CHARS = 200
private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val AUTHORITY = Regex("^[^/?#]*")

/** Host (without userinfo/port) of an absolute or scheme-less URL, or null when absent. */
internal fun hostOf(url: String): String? {
    val authority = AUTHORITY.find(url.trim().replace(SCHEME_PREFIX, ""))?.value
        ?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return authority.substringAfterLast('@').substringBefore(':').ifBlank { null }
}

private fun parseArguments(argumentsJson: String): JSONObject? = try {
    JSONObject(argumentsJson)
} catch (_: JSONException) {
    null
}

/** `optString` renders JSON `null` as the literal "null"; treat that (and blanks) as absent. */
private fun JSONObject.queryString(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifBlank { null }
