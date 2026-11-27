package com.slothspeak.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for ToolRegistry routing and hardening. No Android framework needed:
 * every coroutine scope is a runBlocking block that completes (or is cancelled-and-joined)
 * before the test returns.
 */
class ToolRegistryTest {

    // --- Fakes ---

    private class FakePlugin(
        override val id: String,
        private val configured: Boolean = true,
        private val enabled: Boolean = true,
        private val toolNames: List<String> = listOf("tool_$id"),
        private val result: suspend (ToolCall) -> String = { "ok:${it.name}" }
    ) : ToolPlugin {
        var executeCount = 0
        var lastHandler: ToolConfirmationHandler? = null
        var lastHandlerSeen = false

        override val displayName: String = "Plugin $id"
        override val description: String = "Fake plugin $id"

        override fun isConfigured(): Boolean = configured

        override fun isEnabled(): Boolean = enabled

        override fun tools(): List<ToolDefinition> = toolNames.map { name ->
            ToolDefinition(name = name, description = "desc $name", inputSchemaJson = """{"type":"object"}""")
        }

        override suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler?): ToolResult {
            executeCount++
            if (confirmationHandler != null) {
                lastHandler = confirmationHandler
                lastHandlerSeen = true
            }
            return ToolResult(toolCallId = call.id, name = call.name, content = result(call))
        }
    }

    private fun call(name: String, id: String = "call_1") = ToolCall(id = id, name = name, argumentsJson = "{}")

    // --- activeTools / getPlugins ---

    @Test
    fun activeTools_onlyIncludesEnabledAndConfiguredPlugins() {
        val live = FakePlugin("live")
        val unconfigured = FakePlugin("unconfigured", configured = false)
        val disabled = FakePlugin("disabled", enabled = false)
        val registry = ToolRegistry(listOf(live, unconfigured, disabled))

        assertEquals(listOf("tool_live"), registry.activeTools().map { it.name })
    }

    @Test
    fun activeTools_isEmptyWhenNoPluginsAreActive() {
        val registry = ToolRegistry(listOf(FakePlugin("a", enabled = false), FakePlugin("b", configured = false)))
        assertTrue(registry.activeTools().isEmpty())
    }

    @Test
    fun getPlugins_returnsEveryPluginRegardlessOfState() {
        val a = FakePlugin("a", enabled = false)
        val b = FakePlugin("b")
        val registry = ToolRegistry(listOf(a, b))
        assertEquals(listOf<Any>(a, b), registry.getPlugins())
    }

    // --- Routing ---

    @Test
    fun execute_routesToThePluginOwningTheTool() = runBlocking {
        val search = FakePlugin("search", toolNames = listOf("web_search", "news"))
        val http = FakePlugin("http", toolNames = listOf("http_request"))
        val registry = ToolRegistry(listOf(search, http))

        val result = registry.execute(call("news", id = "call_news"))

        assertFalse(result.isError)
        assertEquals("call_news", result.toolCallId)
        assertEquals("news", result.name)
        assertEquals("ok:news", result.content)
        assertEquals(1, search.executeCount)
        assertEquals(0, http.executeCount)
    }

    @Test
    fun execute_unknownToolReturnsErrorResultWithoutThrowing() = runBlocking {
        val plugin = FakePlugin("search")
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("does_not_exist", id = "call_x"))

        assertTrue(result.isError)
        assertEquals("call_x", result.toolCallId)
        assertEquals("does_not_exist", result.name)
        assertTrue(result.content.contains("does_not_exist"))
        assertEquals(0, plugin.executeCount)
    }

    @Test
    fun execute_toolOfDisabledPluginReturnsErrorResult() = runBlocking {
        val plugin = FakePlugin("search", enabled = false)
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("tool_search"))

        assertTrue(result.isError)
        assertEquals(0, plugin.executeCount)
    }

    @Test
    fun execute_toolOfUnconfiguredPluginReturnsErrorResult() = runBlocking {
        val plugin = FakePlugin("search", configured = false)
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("tool_search"))

        assertTrue(result.isError)
        assertEquals(0, plugin.executeCount)
    }

    @Test
    fun execute_passesConfirmationHandlerToPlugin() = runBlocking {
        val plugin = FakePlugin("http")
        val registry = ToolRegistry(listOf(plugin))
        val handler = ToolConfirmationHandler { ToolConfirmationDecision.ALLOW_ONCE }

        val result = registry.execute(call("tool_http"), handler)

        assertFalse(result.isError)
        assertTrue(plugin.lastHandlerSeen)
        assertSame(handler, plugin.lastHandler)
    }

    @Test
    fun execute_withoutConfirmationHandlerPluginReceivesNull() = runBlocking {
        val plugin = FakePlugin("http")
        val registry = ToolRegistry(listOf(plugin))

        registry.execute(call("tool_http"))

        assertTrue(plugin.lastHandlerSeen.not())
        assertNull(plugin.lastHandler)
    }

    @Test
    fun execute_preservesPluginIsErrorFlag() = runBlocking {
        val plugin = FakePlugin(
            "search",
            result = { throw IllegalStateException("nothing found") }
        )
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("tool_search"))

        assertTrue(result.isError)
        assertTrue(result.content.contains("nothing found"))
    }

    // --- Exception wrapping ---

    @Test
    fun execute_wrapsPluginExceptionIntoErrorResult() = runBlocking {
        val plugin = FakePlugin("search", result = { throw IllegalStateException("boom") })
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("tool_search", id = "call_err"))

        assertTrue(result.isError)
        assertEquals("call_err", result.toolCallId)
        assertEquals("tool_search", result.name)
        assertTrue(result.content.contains("boom"))
    }

    @Test
    fun execute_wrapsExceptionWithoutMessage() = runBlocking {
        val plugin = FakePlugin("search", result = { throw IllegalStateException() })
        val registry = ToolRegistry(listOf(plugin))

        val result = registry.execute(call("tool_search"))

        assertTrue(result.isError)
        assertTrue(result.content.contains("IllegalStateException"))
    }

    // --- Timeout ---

    @Test
    fun execute_timesOutSlowToolAndReturnsErrorResult() = runBlocking {
        val plugin = FakePlugin("slow", result = { delay(10_000); "never" })
        val registry = ToolRegistry(listOf(plugin), callTimeoutMs = 200L)

        val startedAt = System.nanoTime()
        val result = registry.execute(call("tool_slow", id = "call_slow"))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue(result.isError)
        assertEquals("call_slow", result.toolCallId)
        assertEquals("tool_slow", result.name)
        assertTrue(result.content.contains("timed out"))
        assertTrue("took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun execute_fastToolIsNotAffectedByTimeout() = runBlocking {
        val plugin = FakePlugin("fast", result = { delay(20); "done" })
        val registry = ToolRegistry(listOf(plugin), callTimeoutMs = 5_000L)

        val result = registry.execute(call("tool_fast"))

        assertFalse(result.isError)
        assertEquals("done", result.content)
    }

    @Test
    fun execute_rethrowsCallerCancellationInsteadOfReturningATimeout() = runBlocking {
        val plugin = FakePlugin("slow", result = { delay(10_000); "never" })
        val registry = ToolRegistry(listOf(plugin), callTimeoutMs = 30_000L)
        val started = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Throwable?>()

        val job = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                registry.execute(call("tool_slow"))
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
            }
        }

        started.await()
        job.cancelAndJoin()

        val failure = outcome.await()
        assertTrue("expected CancellationException but got $failure", failure is CancellationException)
    }

    // --- Truncation ---

    @Test
    fun execute_truncatesOutputLongerThanMaxOutputChars() = runBlocking {
        val plugin = FakePlugin("big", result = { "x".repeat(25) })
        val registry = ToolRegistry(listOf(plugin), maxOutputChars = 10)

        val result = registry.execute(call("tool_big"))

        assertFalse(result.isError)
        assertTrue(result.content.startsWith("x".repeat(10)))
        assertTrue(result.content.endsWith("\n... [truncated]"))
        assertEquals(10 + "\n... [truncated]".length, result.content.length)
    }

    @Test
    fun execute_doesNotTruncateOutputAtOrBelowLimit() = runBlocking {
        val exact = "y".repeat(10)
        val plugin = FakePlugin("big", result = { exact })
        val registry = ToolRegistry(listOf(plugin), maxOutputChars = 10)

        val result = registry.execute(call("tool_big"))

        assertEquals(exact, result.content)
    }
}
