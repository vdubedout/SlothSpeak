package com.slothspeak.harness

import com.slothspeak.api.OpenAiCompatibleClient
import com.slothspeak.api.models.ChatRequestMessage
import com.slothspeak.api.models.ChatToolCall
import com.slothspeak.api.models.ChatToolCallFunction
import com.slothspeak.data.CompatibleProviderConfig
import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolDefinition

/** Provider-agnostic conversation state consumed by [LlmChatAdapter]. */
sealed class HarnessMessage {
    data class System(val content: String) : HarnessMessage()

    data class User(val content: String) : HarnessMessage()

    /** Model turn. Carries [toolCalls] when the model asked for tools instead of answering. */
    data class Assistant(
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList()
    ) : HarnessMessage()

    /** Tool outcome fed back to the model, paired with the originating [ToolCall.id]. */
    data class Tool(
        val toolCallId: String,
        val name: String,
        val content: String
    ) : HarnessMessage()
}

/** Result of one LLM round-trip. */
data class ChatStepResult(
    val answerText: String?,
    val reasoningText: String?,
    val toolCalls: List<ToolCall>,
    val responseId: String
)

/** Abstraction over a chat backend so the harness is unit-testable without network I/O. */
interface LlmChatAdapter {
    /**
     * Performs one chat step. [tools] may be empty, in which case the model must answer in text.
     * Implementations throw on transport/API errors.
     */
    suspend fun step(messages: List<HarnessMessage>, tools: List<ToolDefinition>): ChatStepResult
}

/**
 * [LlmChatAdapter] for OpenAI Chat Completions-compatible providers (Z.ai, DeepSeek, custom).
 *
 * Mapping notes:
 * - Assistant tool-call turns are replayed with `content = ""` when the model produced no
 *   text — the most portable form across strict OpenAI-compatible servers.
 * - Tool results are sent as `role=tool` with `tool_call_id` only. The `name` field is left
 *   out: it is not part of the OpenAI tool-message schema and some servers reject extras.
 * - `reasoning_content` is never echoed back (DeepSeek rejects it, others waste tokens).
 */
class OpenAiCompatibleChatAdapter(
    private val client: OpenAiCompatibleClient,
    private val provider: CompatibleProviderConfig
) : LlmChatAdapter {

    override suspend fun step(
        messages: List<HarnessMessage>,
        tools: List<ToolDefinition>
    ): ChatStepResult {
        val result = client.createChatStep(
            messages = messages.map { it.toChatRequestMessage() },
            tools = tools,
            provider = provider
        )
        return ChatStepResult(
            answerText = result.answerText,
            reasoningText = result.reasoningText,
            toolCalls = result.toolCalls,
            responseId = result.responseId
        )
    }
}

/** Internal so the wire mapping can be unit-tested without a network client. */
internal fun HarnessMessage.toChatRequestMessage(): ChatRequestMessage = when (this) {
    is HarnessMessage.System -> ChatRequestMessage(role = ROLE_SYSTEM, content = content)
    is HarnessMessage.User -> ChatRequestMessage(role = ROLE_USER, content = content)
    is HarnessMessage.Assistant -> ChatRequestMessage(
        role = ROLE_ASSISTANT,
        content = content ?: "",
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
            ChatToolCall(
                id = call.id,
                function = ChatToolCallFunction(name = call.name, arguments = call.argumentsJson)
            )
        }
    )
    is HarnessMessage.Tool -> ChatRequestMessage(
        role = ROLE_TOOL,
        content = content,
        toolCallId = toolCallId
    )
}

private const val ROLE_SYSTEM = "system"
private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val ROLE_TOOL = "tool"
