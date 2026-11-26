package com.slothspeak.harness

import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolRegistry
import com.slothspeak.tools.ToolTraceEntry
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class AgentResult(
    val answerText: String,
    val reasoningText: String?,
    val responseId: String,
    val toolTrace: List<ToolTraceEntry>
)

/**
 * Minimal ReAct-style agent loop: ask the LLM, run any tools it requests, feed the results
 * back, repeat until the LLM answers in text or [maxSteps] is reached.
 *
 * Tool failures never abort the loop — they are returned to the model as error results so
 * it can recover (retry, pick another tool, or answer anyway). Only cancellation of the
 * caller's scope and LLM/transport errors from [adapter] propagate out of [run].
 */
class AgentHarness(
    private val adapter: LlmChatAdapter,
    private val toolRegistry: ToolRegistry,
    private val maxSteps: Int = 8
) {

    /**
     * @param history previous `(question, answer)` pairs replayed as plain user/assistant turns.
     * @param confirmationHandler optional handler plugins use to ask the user before
     * sensitive side effects (writes, new hosts).
     * @param onToolCallStart invoked before each tool execution.
     * @param onToolCallFinish invoked after each tool execution with its trace entry.
     */
    suspend fun run(
        question: String,
        history: List<Pair<String, String>>,
        systemPrompt: String,
        confirmationHandler: ToolConfirmationHandler? = null,
        onToolCallStart: (ToolCall) -> Unit = {},
        onToolCallFinish: (ToolTraceEntry) -> Unit = {}
    ): AgentResult {
        val messages = buildInitialMessages(question, history, systemPrompt)
        val activeTools = toolRegistry.activeTools()
        val trace = mutableListOf<ToolTraceEntry>()
        val reasoningParts = mutableListOf<String>()
        var lastResponseId = ""

        repeat(maxSteps) {
            coroutineContext.ensureActive()
            val step = adapter.step(messages, activeTools)
            lastResponseId = step.responseId
            step.reasoningText?.let { reasoningParts.add(it) }

            if (step.toolCalls.isEmpty()) {
                return AgentResult(
                    answerText = step.answerText.orEmpty(),
                    reasoningText = joinReasoning(reasoningParts),
                    responseId = step.responseId,
                    toolTrace = trace
                )
            }

            messages.add(HarnessMessage.Assistant(content = step.answerText, toolCalls = step.toolCalls))
            for (call in step.toolCalls) {
                onToolCallStart(call)
                val startNanos = System.nanoTime()
                val toolResult = toolRegistry.execute(call, confirmationHandler)
                val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLIS

                val entry = ToolTraceEntry(
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    resultPreview = toolResult.content.take(RESULT_PREVIEW_CHARS),
                    isError = toolResult.isError,
                    durationMs = durationMs
                )
                trace.add(entry)
                onToolCallFinish(entry)

                messages.add(
                    HarnessMessage.Tool(
                        toolCallId = toolResult.toolCallId,
                        name = toolResult.name,
                        content = toolResult.content
                    )
                )
            }
        }

        // The model is still requesting tools after maxSteps: ask for a text-only synthesis.
        // Adapter failures (network/API errors) propagate; only a blank answer falls back.
        val finalStep = adapter.step(messages, emptyList())
        finalStep.reasoningText?.let { reasoningParts.add(it) }
        val answerText = finalStep.answerText?.takeIf { it.isNotBlank() } ?: STEP_LIMIT_FALLBACK
        return AgentResult(
            answerText = answerText,
            reasoningText = joinReasoning(reasoningParts),
            responseId = finalStep.responseId.ifBlank { lastResponseId },
            toolTrace = trace
        )
    }

    private fun buildInitialMessages(
        question: String,
        history: List<Pair<String, String>>,
        systemPrompt: String
    ): MutableList<HarnessMessage> {
        val messages = mutableListOf<HarnessMessage>(HarnessMessage.System(systemPrompt))
        for ((prevQuestion, prevAnswer) in history) {
            messages.add(HarnessMessage.User(prevQuestion))
            messages.add(HarnessMessage.Assistant(prevAnswer))
        }
        messages.add(HarnessMessage.User(question))
        return messages
    }

    /** Joins per-step reasoning with a blank line between them; null when there is none. */
    private fun joinReasoning(parts: List<String>): String? {
        return parts.map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }
    }

    private companion object {
        const val RESULT_PREVIEW_CHARS = 500
        const val NANOS_PER_MILLIS = 1_000_000L
        const val STEP_LIMIT_FALLBACK =
            "I ran out of tool-use steps before I could finish answering. Please try again."
    }
}
