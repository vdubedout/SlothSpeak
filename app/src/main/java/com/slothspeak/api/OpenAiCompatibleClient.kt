package com.slothspeak.api

import com.slothspeak.api.models.ChatCompletionRequest
import com.slothspeak.api.models.ChatCompletionResponse
import com.slothspeak.api.models.ChatChoice
import com.slothspeak.api.models.ChatRequestMessage
import com.slothspeak.api.models.ChatTool
import com.slothspeak.api.models.ChatToolCall
import com.slothspeak.api.models.ChatToolCallFunction
import com.slothspeak.api.models.ChatToolFunction
import com.slothspeak.api.models.ThinkingConfig
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.data.CompatibleProviderManager
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolDefinition
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class CompatibleLlmResult(
    val answerText: String,
    val reasoningText: String?,
    val responseId: String
)

/**
 * One Chat Completions round-trip for the agent loop.
 *
 * Exactly one of [answerText] / [toolCalls] is non-empty: a model either answers in text
 * or requests tool calls (in which case [answerText] may still carry a short preface).
 */
data class CompatibleChatStepResult(
    val answerText: String?,
    val reasoningText: String?,
    val toolCalls: List<ToolCall>,
    val responseId: String
)

class OpenAiCompatibleClient(
    private val compatibleProviderManager: CompatibleProviderManager
) {

    private val moshi = Moshi.Builder().build()
    private val schemaAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val httpClientCache = mutableMapOf<String, OkHttpClient>()
    private val serviceCache = mutableMapOf<String, ChatCompletionsService>()

    private fun getOrCreateHttpClient(baseUrl: String, apiKey: String?): OkHttpClient {
        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        return httpClientCache.getOrPut(cacheKey) {
            val authInterceptor = Interceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                if (!apiKey.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                chain.proceed(requestBuilder.build())
            }

            OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(1500, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun getOrCreateService(baseUrl: String, apiKey: String?, displayName: String): ChatCompletionsService {
        val httpClient = getOrCreateHttpClient(baseUrl, apiKey)
        val cacheKey = "${baseUrl}_${apiKey ?: ""}"
        return try {
            serviceCache.getOrPut(cacheKey) {
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(httpClient)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(ChatCompletionsService::class.java)
            }
        } catch (e: IllegalArgumentException) {
            throw ApiException(
                "Invalid base URL for $displayName. Please check the URL in Settings.",
                0,
                false
            )
        }
    }

    private fun buildThinkingConfig(provider: CompatibleProviderConfig): ThinkingConfig? {
        if (!provider.sendThinkingParams) return null
        return if (provider.thinkingEnabled) {
            ThinkingConfig(type = "enabled")
        } else {
            ThinkingConfig(type = "disabled")
        }
    }

    private fun buildReasoningEffort(provider: CompatibleProviderConfig): String? {
        return if (provider.sendThinkingParams && provider.thinkingEnabled) {
            provider.reasoningEffort
        } else {
            null
        }
    }

    private fun buildRequest(
        provider: CompatibleProviderConfig,
        messages: List<ChatRequestMessage>,
        chatTools: List<ChatTool>?,
        toolChoice: String?
    ): ChatCompletionRequest {
        return ChatCompletionRequest(
            model = provider.modelId,
            messages = messages,
            thinking = buildThinkingConfig(provider),
            reasoningEffort = buildReasoningEffort(provider),
            maxTokens = provider.maxTokens,
            stream = null,  // never sent
            tools = chatTools,
            toolChoice = toolChoice
        )
    }

    private suspend fun executeRequest(
        service: ChatCompletionsService,
        request: ChatCompletionRequest,
        provider: CompatibleProviderConfig
    ): ChatCompletionResponse {
        val response = service.createChatCompletion(request)
        if (response.isSuccessful) {
            return response.body() ?: throw ApiException(
                "Empty response from ${provider.displayName}",
                0,
                false
            )
        }
        val errorBody = response.errorBody()?.string()
        throw parseApiError(response.code(), errorBody, provider.displayName)
    }

    private fun firstChoice(
        body: ChatCompletionResponse,
        provider: CompatibleProviderConfig
    ): ChatChoice {
        return body.choices.firstOrNull()
            ?: throw ApiException(
                "No choices in ${provider.displayName} response",
                0,
                true
            )
    }

    private fun newResponseId(body: ChatCompletionResponse, provider: CompatibleProviderConfig): String {
        return body.id.ifBlank { "${provider.id}-${Random.nextLong()}" }
    }

    suspend fun createResponse(
        question: String,
        history: List<Pair<String, String>>,
        provider: CompatibleProviderConfig,
        systemPrompt: String
    ): CompatibleLlmResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        val apiKey = compatibleProviderManager.getProviderKey(provider.id)
        val service = getOrCreateService(baseUrl, apiKey, provider.displayName)

        // Build messages: system prompt first, then history pairs, then current question
        val messages = buildHistoryMessages(question, history, systemPrompt)
        val request = buildRequest(provider, messages, chatTools = null, toolChoice = null)

        val body = executeRequest(service, request, provider)
        val choice = firstChoice(body, provider)
        val answerText = choice.message.content?.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                "No text content found in ${provider.displayName} response",
                0,
                true
            )
        val reasoningText = choice.message.reasoningContent?.takeIf { it.isNotBlank() }

        return CompatibleLlmResult(
            answerText = answerText,
            reasoningText = reasoningText,
            responseId = newResponseId(body, provider)
        )
    }

    /**
     * Single agent-loop step: sends the full message list (including assistant `tool_calls`
     * and `role=tool` results) plus the available [tools]. Unlike [createResponse], a
     * response with no text but non-empty `tool_calls` is a valid outcome.
     *
     * Request messages never carry `reasoning_content` (see docs/compat-providers.md).
     */
    suspend fun createChatStep(
        messages: List<ChatRequestMessage>,
        tools: List<ToolDefinition>,
        provider: CompatibleProviderConfig
    ): CompatibleChatStepResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        val apiKey = compatibleProviderManager.getProviderKey(provider.id)
        val service = getOrCreateService(baseUrl, apiKey, provider.displayName)

        val chatTools: List<ChatTool>? = tools.takeIf { it.isNotEmpty() }?.map { it.toChatTool(schemaAdapter) }
        val request = buildRequest(
            provider = provider,
            messages = messages,
            chatTools = chatTools,
            toolChoice = if (chatTools != null) TOOL_CHOICE_AUTO else null
        )

        val body = executeRequest(service, request, provider)
        val choice = firstChoice(body, provider)
        val message = choice.message

        val toolCalls = message.toolCalls.orEmpty().mapIndexed { index, toolCall ->
            toolCall.toToolCall(index)
        }
        val answerText = message.content?.takeIf { it.isNotBlank() }
        if (answerText == null && toolCalls.isEmpty()) {
            throw ApiException(
                "No text content found in ${provider.displayName} response",
                0,
                true
            )
        }
        val reasoningText = message.reasoningContent?.takeIf { it.isNotBlank() }

        return CompatibleChatStepResult(
            answerText = answerText,
            reasoningText = reasoningText,
            toolCalls = toolCalls,
            responseId = newResponseId(body, provider)
        )
    }

    // --- Helpers ---

    private fun buildHistoryMessages(
        question: String,
        history: List<Pair<String, String>>,
        systemPrompt: String
    ): List<ChatRequestMessage> {
        val messages = mutableListOf<ChatRequestMessage>()
        messages.add(ChatRequestMessage(role = "system", content = systemPrompt))

        for ((prevQuestion, prevAnswer) in history) {
            messages.add(ChatRequestMessage(role = "user", content = prevQuestion))
            messages.add(ChatRequestMessage(role = "assistant", content = prevAnswer))
        }

        messages.add(ChatRequestMessage(role = "user", content = question))
        return messages
    }

    private fun parseApiError(code: Int, errorBody: String?, displayName: String): ApiException {
        return when (code) {
            400 -> ApiException(
                "Bad request to $displayName. Please check your configuration.",
                code,
                false
            )
            401, 403 -> ApiException(
                "Invalid $displayName API key. Please check your key in Settings.",
                code,
                true
            )
            429 -> ApiException(
                "Rate limited by $displayName. Please wait and try again.",
                code,
                true
            )
            in 500..599 -> ApiException(
                "$displayName server error. Please try again later.",
                code,
                true
            )
            else -> ApiException(
                "$displayName API request failed (HTTP $code)",
                code,
                true
            )
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) throw ApiException("Base URL is blank for compatible provider", 0, false)
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    companion object {
        private const val TOOL_CHOICE_AUTO = "auto"
    }
}

/** Parses the JSON Schema string into the generic map Moshi serialises as `parameters`. */
internal fun ToolDefinition.toChatTool(schemaAdapter: JsonAdapter<Map<String, Any?>>): ChatTool {
    val parameters: Map<String, Any?> = try {
        schemaAdapter.fromJson(inputSchemaJson) ?: emptyMap()
    } catch (_: Exception) {
        // Malformed schema: still advertise the tool, but with an empty schema ("{}").
        emptyMap()
    }
    return ChatTool(
        function = ChatToolFunction(
            name = name,
            description = description,
            parameters = parameters
        )
    )
}

/**
 * Some OpenAI-compatible servers omit `id` on tool calls (or emit empty `arguments`);
 * substitute a unique id / `"{}"` so the harness can still pair tool results with tool
 * calls and plugins always receive a JSON object string.
 */
internal fun ChatToolCall.toToolCall(index: Int): ToolCall {
    val callId = id.ifBlank { "call_${index}_${Random.nextLong().toULong().toString(16)}" }
    return ToolCall(
        id = callId,
        name = function.name,
        argumentsJson = function.arguments.ifBlank { "{}" }
    )
}
