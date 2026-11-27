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
 * back, repeat until the LLM answers in text or the tool-call budget ([maxToolCalls]) is spent.
 *
 * The budget counts EXECUTED tool calls, not LLM steps: a step that requests three calls
 * costs three. Calls the model requests beyond the remaining budget are not executed —
 * they get an error result so every `tool_call` keeps its paired `role=tool` message — and
 * once the last allowed call has run, a notice tells the model to conclude with what it has
 * before a final text-only synthesis is requested.
 *
 * Tool failures never abort the loop — they are returned to the model as error results so
 * it can recover (retry, pick another tool, or answer anyway). Only cancellation of the
 * caller's scope and LLM/transport errors from [adapter] propagate out of [run].
 */
class AgentHarness(
    private val adapter: LlmChatAdapter,
    private val toolRegistry: ToolRegistry,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS
) {

    /** Budget actually enforced: never below 1 so the loop always makes progress. */
    private val budget = maxToolCalls.coerceAtLeast(1)

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
        var executedCalls = 0

        // Each round either returns (text answer) or executes at least one call, so at most
        // `budget` rounds run before the loop breaks for the final synthesis.
        while (true) {
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
                // Cancellation requested while a previous call was running must stop here,
                // before anything else leaves the device.
                coroutineContext.ensureActive()
                if (executedCalls >= budget) {
                    // Surplus call in a step that already spent the budget: never executed,
                    // but still answered so the tool_call ↔ tool_result pairing stays valid.
                    messages.add(
                        HarnessMessage.Tool(
                            toolCallId = call.id,
                            name = call.name,
                            content = budgetExhaustedResult(executedCalls, budget)
                        )
                    )
                    continue
                }

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
                executedCalls++

                messages.add(
                    HarnessMessage.Tool(
                        toolCallId = toolResult.toolCallId,
                        name = toolResult.name,
                        content = toolResult.content
                    )
                )
            }

            if (executedCalls >= budget) {
                // The last allowed call has run: tell the model to wrap up before the
                // text-only synthesis below.
                messages.add(HarnessMessage.User(lastToolCallNotice(executedCalls, budget)))
                break
            }
        }

        // The model is still requesting tools after the budget is spent: ask for a text-only
        // synthesis. Adapter failures (network/API errors) propagate; only a blank answer
        // falls back.
        coroutineContext.ensureActive()
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

    companion object {
        /** Default per-prompt tool-call budget (issue #13). */
        const val DEFAULT_MAX_TOOL_CALLS = 15
        const val RESULT_PREVIEW_CHARS = 500
        private const val NANOS_PER_MILLIS = 1_000_000L
        const val STEP_LIMIT_FALLBACK =
            "I ran out of tool-use steps before I could finish answering. Please try again."

        /**
         * Sent as a user turn after the last allowed tool call so the model concludes with
         * the information it already has — and says what is left when more calls would be
         * needed. The user can then send another prompt to get a fresh budget.
         */
        internal fun lastToolCallNotice(executed: Int, budget: Int): String =
            "This was your last available tool call ($executed/$budget). " +
                "Conclude now using the information you already have. " +
                "If completing a specific part of the request would require more tool calls, " +
                "say explicitly what remains and what the user should ask next. " +
                "The user can send another prompt to continue with more tool calls."

        /** Error result returned to the model for a requested call that was not executed. */
        internal fun budgetExhaustedResult(executed: Int, budget: Int): String =
            "Tool call budget exhausted ($executed/$budget): this call was not executed. " +
                "Conclude with the information you already have."
    }
}
