package com.slothspeak.tools

/*
 * Model-facing digest of the tool calls made for a previous answer (issue #14).
 *
 * Conversation history is replayed as plain (question, answer) text turns, so without this
 * the model has no memory of the tools it already ran and tends to relaunch identical calls
 * in follow-up prompts. [answerWithToolTraceDigest] appends a compact digest — name,
 * arguments, result preview and error flag per call — to each previous answer that used
 * tools, built from the `QAPair.toolCallsJson` trace persisted by the service.
 *
 * Everything here is pure and never throws: malformed trace JSON simply yields no digest.
 */

/** Result preview chars kept per call; the persisted preview is already capped at 500. */
internal const val DIGEST_PREVIEW_CHARS = 400
/** Chars kept for a call's arguments. */
internal const val DIGEST_ARGUMENT_CHARS = 200

internal const val DIGEST_FOOTNOTE =
    "These tool calls already ran for the answer above. Do not repeat identical calls " +
        "unless the data may have changed since; reuse their results."

/**
 * One-line digest of [entries], e.g.
 * `[Previous tool calls for this answer: brave_web_search(query="x") -> 1. …; http_request(GET https://a.b) -> ERROR: 404]`
 * followed by [DIGEST_FOOTNOTE]. Null when there is nothing to report.
 */
internal fun formatToolTraceDigest(entries: List<ToolTraceEntry>): String? {
    if (entries.isEmpty()) return null
    val calls = entries.joinToString("; ") { entry ->
        val args = singleLine(formatToolArguments(entry.name, entry.argumentsJson)).truncate(DIGEST_ARGUMENT_CHARS)
        val preview = singleLine(entry.resultPreview).truncate(DIGEST_PREVIEW_CHARS)
        val outcome = if (entry.isError) "ERROR: $preview" else preview.ifBlank { "(no output)" }
        "${entry.name}($args) -> $outcome"
    }
    return "[Previous tool calls for this answer: $calls]\n$DIGEST_FOOTNOTE"
}

/**
 * [answer] followed by a blank line and the digest of the trace serialised in
 * [toolCallsJson]; [answer] unchanged when the trace is null, blank, malformed or empty.
 */
internal fun answerWithToolTraceDigest(answer: String, toolCallsJson: String?): String {
    val digest = formatToolTraceDigest(ToolTraceEntry.fromJson(toolCallsJson)) ?: return answer
    return answer.trimEnd() + "\n\n" + digest
}

private fun singleLine(text: String): String = text.replace(WHITESPACE, " ").trim()

private fun String.truncate(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars) + "…"

private val WHITESPACE = Regex("\\s+")
