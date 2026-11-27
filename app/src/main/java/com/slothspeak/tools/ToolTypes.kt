package com.slothspeak.tools

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException

/**
 * MCP-aligned tool definition.
 *
 * [inputSchemaJson] must be a valid JSON Schema *object* string. It maps 1:1 to MCP
 * `inputSchema` and is forwarded verbatim as the OpenAI `tools[].function.parameters`
 * field (parsed into a generic map before serialisation, see
 * [com.slothspeak.api.OpenAiCompatibleClient]).
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

/** A tool invocation requested by the model. [argumentsJson] is the raw JSON arguments string. */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** Outcome of a tool invocation; fed back to the model as a `role=tool` message. */
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false
)

/**
 * Diagnostic record of one tool invocation. Serializable to/from JSON via Moshi codegen
 * so the trace can be persisted (e.g. as a Room column) and restored for display.
 */
@JsonClass(generateAdapter = true)
data class ToolTraceEntry(
    val name: String,
    val argumentsJson: String,
    val resultPreview: String,
    val isError: Boolean,
    val durationMs: Long
) {
    companion object {
        private val moshi by lazy { Moshi.Builder().build() }
        private val listType = Types.newParameterizedType(List::class.java, ToolTraceEntry::class.java)

        /** Serialises a trace list to a compact JSON array string. */
        fun toJson(entries: List<ToolTraceEntry>): String =
            moshi.adapter<List<ToolTraceEntry>>(listType).toJson(entries)

        /**
         * Restores a trace list persisted with [toJson].
         * Null/blank or malformed input (bad JSON, wrong types, missing fields) yields an
         * empty list — trace data is diagnostic and must never crash the caller.
         */
        fun fromJson(json: String?): List<ToolTraceEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                moshi.adapter<List<ToolTraceEntry>>(listType).fromJson(json) ?: emptyList()
            } catch (_: JsonDataException) {
                emptyList()
            } catch (_: IOException) {
                emptyList()
            }
        }
    }
}

/**
 * Description of an outbound HTTP request a plugin is about to perform, surfaced to the
 * user before anything leaves the device.
 */
data class ToolConfirmationRequest(
    val toolName: String,
    val method: String,
    val url: String,
    val host: String,
    val headers: Map<String, String>,
    val bodyPreview: String?
)

enum class ToolConfirmationDecision { ALLOW_ONCE, ALWAYS_ALLOW_HOST, ALWAYS_ALLOW_ALL, DENY }

/** Implemented by the UI/service layer to ask the user to approve a sensitive tool call. */
fun interface ToolConfirmationHandler {
    suspend fun requestConfirmation(request: ToolConfirmationRequest): ToolConfirmationDecision
}
