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
    val stream: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class ChatRequestMessage(
    val role: String,
    val content: String
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
    val content: String? = null,
    @Json(name = "reasoning_content")
    val reasoningContent: String? = null
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
