package com.slothspeak.harness

import com.slothspeak.tools.ToolCall
import com.slothspeak.tools.ToolConfirmationDecision
import com.slothspeak.tools.ToolConfirmationHandler
import com.slothspeak.tools.ToolDefinition
import com.slothspeak.tools.ToolPlugin
import com.slothspeak.tools.ToolRegistry
import com.slothspeak.tools.ToolResult
import com.slothspeak.tools.ToolTraceEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for the AgentHarness loop. The LLM is scripted and the tools are fakes,
 * so every test is a single runBlocking block that completes before returning.
 */
class AgentHarnessTest {

    // --- Fakes ---

    /** Scripted LLM: [script] receives the 0-based step index and the offered tools. */
    private class ScriptedAdapter(
        private val script: (stepIndex: Int, tools: List<ToolDefinition>) -> ChatStepResult
    ) : LlmChatAdapter {
        data class RecordedStep(
            val index: Int,
            val messages: List<HarnessMessage>,
            val tools: List<ToolDefinition>
        )

        val recorded = mutableListOf<RecordedStep>()

        override suspend fun step(messages: List<HarnessMessage>, tools: List<ToolDefinition>): ChatStepResult {
            val index = recorded.size
            recorded.add(RecordedStep(index, messages.toList(), tools.toList()))
            return script(index, tools)
        }
    }

    private class FakeToolPlugin(
        override val id: String,
        private val behaviors: Map<String, suspend (ToolCall) -> String>
    ) : ToolPlugin {
        val executed = mutableListOf<ToolCall>()

        override val displayName: String = "Fake $id"
        override val description: String = "Fake tool plugin $id"

        override fun isConfigured(): Boolean = true

        override fun isEnabled(): Boolean = true

        override fun tools(): List<ToolDefinition> = behaviors.keys.map { name ->
            ToolDefinition(name = name, description = "desc $name", inputSchemaJson = """{"type":"object"}""")
        }

        override suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler?): ToolResult {
            executed.add(call)
            return ToolResult(toolCallId = call.id, name = call.name, content = behaviors.getValue(call.name)(call))
        }
    }

    private companion object {
        const val SYSTEM_PROMPT = "You are a test assistant."
        const val QUESTION = "What is the weather in Paris?"

        val ACTIVE_TOOL_NAMES = listOf("web_search", "lookup")

        fun call(name: String, id: String, args: String = """{"q":"x"}""") = ToolCall(id = id, name = name, argumentsJson = args)

        fun textStep(text: String?, id: String = "resp-text", reasoning: String? = null) = ChatStepResult(
            answerText = text,
            reasoningText = reasoning,
            toolCalls = emptyList(),
            responseId = id
        )

        fun toolStep(vararg calls: ToolCall, content: String? = null, reasoning: String? = null, id: String = "resp-tools") =
            ChatStepResult(
                answerText = content,
                reasoningText = reasoning,
                toolCalls = calls.toList(),
                responseId = id
            )

        fun defaultPlugin() = FakeToolPlugin(
            id = "search",
            behaviors = mapOf<String, suspend (ToolCall) -> String>(
                "web_search" to { "sunny, 21°C" },
                "lookup" to { call -> delay(30); "lookup-result:${call.id}" }
            )
        )
    }

    // --- 0-tool pass-through ---

    @Test
    fun run_directAnswer_passesThroughTextReasoningAndResponseId() = runBlocking {
        val plugin = defaultPlugin()
        var starts = 0
        var finishes = 0
        val adapter = ScriptedAdapter { _, _ -> textStep("It is sunny and 21°C.", id = "resp-1", reasoning = "thinking a") }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))

        val result = harness.run(
            question = QUESTION,
            history = listOf("Previous question" to "Previous answer"),
            systemPrompt = SYSTEM_PROMPT,
            onToolCallStart = { starts++ },
            onToolCallFinish = { finishes++ }
        )

        assertEquals("It is sunny and 21°C.", result.answerText)
        assertEquals("thinking a", result.reasoningText)
        assertEquals("resp-1", result.responseId)
        assertTrue(result.toolTrace.isEmpty())
        assertEquals(0, starts)
        assertEquals(0, finishes)
        assertTrue(plugin.executed.isEmpty())

        // Initial messages: System, history pairs, User — and the active tools are offered.
        assertEquals(1, adapter.recorded.size)
        val first = adapter.recorded[0]
        assertEquals(
            listOf(
                HarnessMessage.System(SYSTEM_PROMPT),
                HarnessMessage.User("Previous question"),
                HarnessMessage.Assistant("Previous answer"),
                HarnessMessage.User(QUESTION)
            ),
            first.messages
        )
        assertEquals(ACTIVE_TOOL_NAMES, first.tools.map { it.name })
    }

    @Test
    fun run_withoutHistory_sendsOnlySystemAndUserMessages() = runBlocking {
        val adapter = ScriptedAdapter { _, _ -> textStep("ok") }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())))

        harness.run(QUESTION, history = emptyList(), systemPrompt = SYSTEM_PROMPT)

        assertEquals(
            listOf(HarnessMessage.System(SYSTEM_PROMPT), HarnessMessage.User(QUESTION)),
            adapter.recorded[0].messages
        )
    }

    @Test
    fun run_blankReasoningYieldsNullReasoningText() = runBlocking {
        val adapter = ScriptedAdapter { _, _ -> textStep("ok", reasoning = "   ") }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())))

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertNull(result.reasoningText)
    }

    @Test
    fun run_noActivePlugins_offersNoTools() = runBlocking {
        val adapter = ScriptedAdapter { _, _ -> textStep("ok") }
        val harness = AgentHarness(adapter, ToolRegistry(emptyList()))

        harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertTrue(adapter.recorded[0].tools.isEmpty())
    }

    // --- Multi-step loop ---

    @Test
    fun run_multiStep_executesToolsFeedsResultsBackAndRecordsTrace() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { index, _ ->
            when (index) {
                0 -> toolStep(call("web_search", "call_1"), reasoning = " r1 ")
                1 -> toolStep(call("lookup", "call_2"), reasoning = "r2")
                else -> textStep("It is sunny and 21°C in Paris.", id = "resp-3")
            }
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))
        val started = mutableListOf<ToolCall>()
        val finished = mutableListOf<ToolTraceEntry>()

        val result = harness.run(
            question = QUESTION,
            history = emptyList(),
            systemPrompt = SYSTEM_PROMPT,
            onToolCallStart = { started.add(it) },
            onToolCallFinish = { finished.add(it) }
        )

        // Final answer assembled from the last step plus accumulated reasoning.
        assertEquals("It is sunny and 21°C in Paris.", result.answerText)
        assertEquals("r1\n\nr2", result.reasoningText)
        assertEquals("resp-3", result.responseId)

        // Three LLM steps, tools still offered on every step.
        assertEquals(3, adapter.recorded.size)
        assertTrue(adapter.recorded.all { it.tools.map { t -> t.name } == ACTIVE_TOOL_NAMES })

        // Step 2 history: assistant tool request + tool result.
        assertEquals(
            listOf(
                HarnessMessage.System(SYSTEM_PROMPT),
                HarnessMessage.User(QUESTION),
                HarnessMessage.Assistant(content = null, toolCalls = listOf(call("web_search", "call_1"))),
                HarnessMessage.Tool("call_1", "web_search", "sunny, 21°C")
            ),
            adapter.recorded[1].messages
        )

        // Step 3 history: previous + second tool exchange.
        assertEquals(
            listOf(
                HarnessMessage.System(SYSTEM_PROMPT),
                HarnessMessage.User(QUESTION),
                HarnessMessage.Assistant(content = null, toolCalls = listOf(call("web_search", "call_1"))),
                HarnessMessage.Tool("call_1", "web_search", "sunny, 21°C"),
                HarnessMessage.Assistant(content = null, toolCalls = listOf(call("lookup", "call_2"))),
                HarnessMessage.Tool("call_2", "lookup", "lookup-result:call_2")
            ),
            adapter.recorded[2].messages
        )

        // Trace.
        assertEquals(2, result.toolTrace.size)
        val first = result.toolTrace[0]
        assertEquals("web_search", first.name)
        assertEquals("""{"q":"x"}""", first.argumentsJson)
        assertEquals("sunny, 21°C", first.resultPreview)
        assertFalse(first.isError)
        assertTrue(first.durationMs >= 0)
        val second = result.toolTrace[1]
        assertEquals("lookup", second.name)
        assertEquals("lookup-result:call_2", second.resultPreview)
        assertFalse(second.isError)
        assertTrue("durationMs=${second.durationMs}", second.durationMs >= 20)

        // Callbacks fire once per tool call, with the same trace entries.
        assertEquals(listOf(call("web_search", "call_1"), call("lookup", "call_2")), started)
        assertEquals(result.toolTrace, finished)
        assertSame(first, finished[0])
        assertEquals(listOf(call("web_search", "call_1"), call("lookup", "call_2")), plugin.executed)
    }

    @Test
    fun run_multipleToolCallsInOneStep_areExecutedInOrderAndAllFedBack() = runBlocking {
        val plugin = FakeToolPlugin(
            id = "multi",
            behaviors = mapOf<String, suspend (ToolCall) -> String>("a" to { "A-result" }, "b" to { "B-result" })
        )
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("a", "c1"), call("b", "c2")) else textStep("done")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(listOf("c1", "c2"), plugin.executed.map { it.id })
        assertEquals(listOf("a", "b"), result.toolTrace.map { it.name })
        assertEquals("done", result.answerText)
        assertEquals(
            listOf(
                HarnessMessage.System(SYSTEM_PROMPT),
                HarnessMessage.User(QUESTION),
                HarnessMessage.Assistant(null, listOf(call("a", "c1"), call("b", "c2"))),
                HarnessMessage.Tool("c1", "a", "A-result"),
                HarnessMessage.Tool("c2", "b", "B-result")
            ),
            adapter.recorded[1].messages
        )
    }

    @Test
    fun run_assistantPrefaceText_isKeptInHistory() = runBlocking {
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("web_search", "c1"), content = "Let me check that.") else textStep("done")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())))

        harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        val assistant = adapter.recorded[1].messages[2] as HarnessMessage.Assistant
        assertEquals("Let me check that.", assistant.content)
        assertEquals(listOf(call("web_search", "c1")), assistant.toolCalls)
    }

    @Test
    fun run_tracePreview_isTruncatedTo500Chars() = runBlocking {
        val plugin = FakeToolPlugin(
            "big",
            behaviors = mapOf<String, suspend (ToolCall) -> String>("big" to { "z".repeat(2_000) })
        )
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("big", "c1")) else textStep("done")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(500, result.toolTrace.single().resultPreview.length)
        // The model still receives the full (registry-limited) content.
        val toolMessage = adapter.recorded[1].messages[3] as HarnessMessage.Tool
        assertEquals(2_000, toolMessage.content.length)
    }

    // --- Tool error recovery ---

    @Test
    fun run_toolError_isFedBackToModelAndLoopContinues() = runBlocking {
        val plugin = FakeToolPlugin(
            id = "flaky",
            behaviors = mapOf<String, suspend (ToolCall) -> String>("flaky" to { throw IllegalStateException("boom") })
        )
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("flaky", "c1"), call("ghost_tool", "c2")) else textStep("recovered", id = "resp-2")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals("recovered", result.answerText)
        assertEquals("resp-2", result.responseId)
        assertEquals(2, result.toolTrace.size)
        assertTrue(result.toolTrace[0].isError)
        assertTrue(result.toolTrace[0].resultPreview.contains("boom"))
        assertTrue(result.toolTrace[1].isError)
        assertTrue(result.toolTrace[1].resultPreview.contains("ghost_tool"))

        val toolMessages = adapter.recorded[1].messages.filterIsInstance<HarnessMessage.Tool>()
        assertEquals(listOf("c1", "c2"), toolMessages.map { it.toolCallId })
        assertEquals(listOf("flaky", "ghost_tool"), toolMessages.map { it.name })
        assertTrue(toolMessages[0].content.contains("boom"))
        assertTrue(toolMessages[1].content.contains("ghost_tool"))
    }

    @Test
    fun run_confirmationHandler_isForwardedToToolRegistry() = runBlocking {
        val handler = ToolConfirmationHandler { ToolConfirmationDecision.DENY }
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("web_search", "c1")) else textStep("done")
        }
        // Registry is the component that forwards the handler; verify by using a plugin that
        // records it via a subclass-free wrapper.
        val recording = object : ToolPlugin by plugin {
            var seen: ToolConfirmationHandler? = null
            override suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler?): ToolResult {
                seen = confirmationHandler
                return plugin.execute(call, confirmationHandler)
            }
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(recording)))

        harness.run(QUESTION, emptyList(), SYSTEM_PROMPT, confirmationHandler = handler)

        assertSame(handler, recording.seen)
    }

    // --- Tool-call budget exhaustion (issue #13) ---

    /** Adapter that keeps requesting one `web_search` per step while tools are offered. */
    private fun oneCallPerStepAdapter() = ScriptedAdapter { index, tools ->
        if (tools.isNotEmpty()) toolStep(call("web_search", "call_$index"), id = "resp-$index")
        else textStep("Final synthesis.", id = "resp-final")
    }

    @Test
    fun run_defaultBudget_is15ToolCalls() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = oneCallPerStepAdapter()
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)))

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(AgentHarness.DEFAULT_MAX_TOOL_CALLS, 15)
        // 15 tool rounds + 1 text-only synthesis.
        assertEquals(16, adapter.recorded.size)
        assertEquals(15, plugin.executed.size)
        assertEquals(15, result.toolTrace.size)
        assertEquals("Final synthesis.", result.answerText)
    }

    @Test
    fun run_configurableBudget_executesExactlyNCalls() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = oneCallPerStepAdapter()
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 5)

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(5, plugin.executed.size)
        assertEquals((0 until 5).map { "call_$it" }, plugin.executed.map { it.id })
        assertEquals(5, result.toolTrace.size)
        assertEquals(6, adapter.recorded.size)
    }

    @Test
    fun run_budgetExhausted_finalStepDropsToolsAndReturnsSynthesis() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = oneCallPerStepAdapter()
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 3)

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        // 3 tool rounds + 1 text-only synthesis.
        assertEquals(4, adapter.recorded.size)
        assertEquals(ACTIVE_TOOL_NAMES, adapter.recorded[0].tools.map { it.name })
        assertEquals(ACTIVE_TOOL_NAMES, adapter.recorded[2].tools.map { it.name })
        assertTrue(adapter.recorded[3].tools.isEmpty())
        assertEquals(listOf("call_0", "call_1", "call_2"), plugin.executed.map { it.id })
        assertEquals(3, result.toolTrace.size)
        assertEquals("Final synthesis.", result.answerText)
        assertEquals("resp-final", result.responseId)
        // The final step sees all three exchanges plus the last-call notice.
        assertEquals(1 + 1 + 2 * 3 + 1, adapter.recorded[3].messages.size)
    }

    @Test
    fun run_budgetExhausted_lastCallNoticeIsAppendedBeforeFinalSynthesis() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = oneCallPerStepAdapter()
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 2)

        harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        val finalMessages = adapter.recorded.last().messages
        val notice = finalMessages.last() as HarnessMessage.User
        assertEquals(AgentHarness.lastToolCallNotice(2, 2), notice.content)
        assertTrue(notice.content.contains("(2/2)"))
        assertTrue(notice.content.contains("Conclude now"))
        assertTrue(notice.content.contains("what the user should ask next"))
        // It comes right after the last tool result, after a complete assistant/tool exchange.
        val lastTool = finalMessages[finalMessages.size - 2] as HarnessMessage.Tool
        assertEquals("call_1", lastTool.toolCallId)
        // No notice on the earlier tool rounds.
        assertTrue(adapter.recorded[0].messages.none { it is HarnessMessage.User && it.content.startsWith("This was your last") })
        assertTrue(adapter.recorded[1].messages.none { it is HarnessMessage.User && it.content.startsWith("This was your last") })
    }

    @Test
    fun run_budgetExhausted_modelAnsweringBeforeBudget_getsNoNoticeOrExtraStep() = runBlocking {
        val adapter = ScriptedAdapter { index, _ ->
            if (index == 0) toolStep(call("web_search", "call_0")) else textStep("done", id = "resp-1")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())), maxToolCalls = 15)

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(2, adapter.recorded.size)
        assertEquals("done", result.answerText)
        assertEquals("resp-1", result.responseId)
        assertTrue(adapter.recorded.all { step -> step.messages.none { it is HarnessMessage.User && it.content.startsWith("This was your last") } })
    }

    @Test
    fun run_surplusCallsInSameStep_areDeniedWithoutExecutionAndPairingStaysValid() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { index, tools ->
            when {
                index == 0 -> toolStep(call("web_search", "call_0"), id = "resp-0")
                tools.isNotEmpty() -> toolStep(
                    call("web_search", "call_1"),
                    call("lookup", "call_2"),
                    call("web_search", "call_3"),
                    id = "resp-1"
                )
                else -> textStep("wrapped up", id = "resp-final")
            }
        }
        // Budget 2: the second step requests three calls but only one is left.
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 2)
        val started = mutableListOf<ToolCall>()
        val finished = mutableListOf<ToolTraceEntry>()

        val result = harness.run(
            QUESTION,
            emptyList(),
            SYSTEM_PROMPT,
            onToolCallStart = { started.add(it) },
            onToolCallFinish = { finished.add(it) }
        )

        // Only the two budgeted calls reach the plugin; the surplus ones never execute.
        assertEquals(listOf("call_0", "call_1"), plugin.executed.map { it.id })
        assertEquals(listOf("call_0", "call_1"), started.map { it.id })
        assertEquals(result.toolTrace, finished)
        assertEquals(2, result.toolTrace.size)

        // The final synthesis still gets every tool_call answered: 3 requested, 3 tool messages.
        val finalMessages = adapter.recorded.last().messages
        val toolMessages = finalMessages.filterIsInstance<HarnessMessage.Tool>()
        assertEquals(listOf("call_0", "call_1", "call_2", "call_3"), toolMessages.map { it.toolCallId })
        assertEquals(listOf("web_search", "web_search", "lookup", "web_search"), toolMessages.map { it.name })
        assertEquals("sunny, 21°C", toolMessages[1].content)
        assertEquals(AgentHarness.budgetExhaustedResult(2, 2), toolMessages[2].content)
        assertEquals(AgentHarness.budgetExhaustedResult(2, 2), toolMessages[3].content)
        assertTrue(toolMessages[2].content.contains("not executed"))
        // Tool results follow their assistant turn, then the last-call notice.
        val assistant = finalMessages[finalMessages.indexOf(toolMessages[1]) - 1] as HarnessMessage.Assistant
        assertEquals(listOf("call_1", "call_2", "call_3"), assistant.toolCalls.map { it.id })
        val notice = finalMessages.last() as HarnessMessage.User
        assertEquals(AgentHarness.lastToolCallNotice(2, 2), notice.content)
        assertTrue(finalMessages.indexOf(notice) > finalMessages.indexOf(toolMessages[3]))

        assertEquals("wrapped up", result.answerText)
        assertEquals("resp-final", result.responseId)
        assertEquals(3, adapter.recorded.size)
        assertTrue(adapter.recorded[2].tools.isEmpty())
    }

    @Test
    fun run_budgetOfOne_firstCallIsTheLastAndNoticeFollows() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { index, tools ->
            if (tools.isNotEmpty()) toolStep(call("web_search", "only"), id = "resp-0") else textStep("ok", id = "resp-final")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 1)

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(listOf("only"), plugin.executed.map { it.id })
        assertEquals(
            listOf(
                HarnessMessage.System(SYSTEM_PROMPT),
                HarnessMessage.User(QUESTION),
                HarnessMessage.Assistant(null, listOf(call("web_search", "only"))),
                HarnessMessage.Tool("only", "web_search", "sunny, 21°C"),
                HarnessMessage.User(AgentHarness.lastToolCallNotice(1, 1))
            ),
            adapter.recorded[1].messages
        )
        assertEquals(1, result.toolTrace.size)
        assertEquals("ok", result.answerText)
    }

    @Test
    fun run_nonPositiveBudget_isTreatedAsOne() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = oneCallPerStepAdapter()
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 0)

        harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertEquals(1, plugin.executed.size)
        assertEquals(2, adapter.recorded.size)
    }

    @Test
    fun run_budgetExhausted_blankFinalAnswer_fallsBackGracefully() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { index, tools ->
            if (tools.isNotEmpty()) toolStep(call("web_search", "call_$index"), reasoning = "r$index")
            else textStep("   ", id = "resp-final")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 2)

        val result = harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)

        assertTrue(result.answerText.isNotBlank())
        assertEquals(AgentHarness.STEP_LIMIT_FALLBACK, result.answerText)
        assertEquals("resp-final", result.responseId)
        assertEquals(2, result.toolTrace.size)
        assertEquals("r0\n\nr1", result.reasoningText)
    }

    @Test
    fun run_budgetExhausted_finalStepFailure_propagates() = runBlocking {
        val plugin = defaultPlugin()
        val adapter = ScriptedAdapter { _, tools ->
            if (tools.isNotEmpty()) toolStep(call("web_search", "call_x"))
            else throw IllegalStateException("llm unavailable")
        }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(plugin)), maxToolCalls = 1)

        try {
            harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)
            fail("expected the final-step failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("llm unavailable", e.message)
        }
    }

    // --- Cancellation / error propagation from the LLM ---

    @Test
    fun run_llmException_propagatesToCaller() = runBlocking {
        val adapter = ScriptedAdapter { _, _ -> throw IllegalStateException("transport down") }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())))

        try {
            harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)
            fail("expected the LLM exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("transport down", e.message)
        }
    }

    @Test
    fun run_cancellationFromAdapter_isNotSwallowed() = runBlocking {
        val adapter = ScriptedAdapter { _, _ -> throw CancellationException("stop") }
        val harness = AgentHarness(adapter, ToolRegistry(listOf(defaultPlugin())))

        try {
            harness.run(QUESTION, emptyList(), SYSTEM_PROMPT)
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertNotNull(e)
        }
    }
}
