package com.slothspeak.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Request models

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    val thinking: ThinkingConfig? = null,
    @Json(name = "reasoning_effort")
    val reasoningEffort: String? = null,
    @Json(name = "max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean? = null,
    // OpenAI function calling — omitted (null) for plain chat requests.
    val tools: List<ChatTool>? = null,
    @Json(name = "tool_choice")
    val toolChoice: String? = null
)

/**
 * One chat message. Moshi omits null fields, so a plain history message serialises to
 * exactly `{"role": ..., "content": ...}`.
 *
 * History messages never carry `reasoning_content`: DeepSeek rejects requests that echo it
 * and it wastes tokens elsewhere (see docs/compat-providers.md).
 */
@JsonClass(generateAdapter = true)
data class ChatRequestMessage(
    val role: String,
    val content: String? = null,
    @Json(name = "tool_calls")
    val toolCalls: List<ChatToolCall>? = null,
    @Json(name = "tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null
)

/** `tools[]` entry — a function tool. `type` is always "function" in the Chat Completions API. */
@JsonClass(generateAdapter = true)
data class ChatTool(
    val type: String = "function",
    val function: ChatToolFunction
)

/**
 * [parameters] is a JSON Schema object describing the arguments. Moshi serialises the
 * generic map verbatim, so any nested schema (properties, required, enum, ...) round-trips.
 */
@JsonClass(generateAdapter = true)
data class ChatToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?> = emptyMap()
)

/** Tool invocation requested by the model, either in a response or replayed in history. */
@JsonClass(generateAdapter = true)
data class ChatToolCall(
    // Defaults to "" for OpenAI-compatible servers that omit the id; callers substitute one.
    val id: String = "",
    val type: String = "function",
    val function: ChatToolCallFunction
)

@JsonClass(generateAdapter = true)
data class ChatToolCallFunction(
    val name: String,
    val arguments: String
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    val type: String
)

// Response models

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    val id: String,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
)

@JsonClass(generateAdapter = true)
data class ChatChoice(
    val index: Int? = 0,
    val message: ChatResponseMessage,
    @Json(name = "finish_reason")
    val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class ChatResponseMessage(
    val role: String? = null,
    // Null when the model only requested tool calls.
    val content: String? = null,
    @Json(name = "reasoning_content")
    val reasoningContent: String? = null,
    @Json(name = "tool_calls")
    val toolCalls: List<ChatToolCall>? = null
)

// Chat Completions usage (separate from Responses API Usage — different field names)
@JsonClass(generateAdapter = true)
data class ChatUsage(
    @Json(name = "prompt_tokens")
    val promptTokens: Int? = null,
    @Json(name = "completion_tokens")
    val completionTokens: Int? = null,
    @Json(name = "total_tokens")
    val totalTokens: Int? = null
)
