package com.slothspeak.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Routes [ToolCall]s to the active [ToolPlugin] that owns the tool, and hardens every
 * invocation: unknown/disabled tools, exceptions and timeouts all come back as
 * `isError` [ToolResult]s so the agent loop never crashes on a misbehaving tool.
 */
class ToolRegistry(
    private val plugins: List<ToolPlugin>,
    private val maxOutputChars: Int = 16_000,
    private val callTimeoutMs: Long = 30_000L
) {

    /** All registered plugins, regardless of state. */
    fun getPlugins(): List<ToolPlugin> = plugins

    /** Plugins that are both user-enabled and fully configured. */
    fun activePlugins(): List<ToolPlugin> = plugins.filter { it.isEnabled() && it.isConfigured() }

    /** Tool definitions advertised to the LLM — only from active plugins. */
    fun activeTools(): List<ToolDefinition> = activePlugins().flatMap { it.tools() }

    /**
     * Executes [call] on the active plugin owning `call.name`.
     *
     * Never throws for tool-level failures: unknown/disabled tools, exceptions and
     * timeouts are returned as `isError` results. A [CancellationException] of the
     * caller's own scope is rethrown so cooperative cancellation keeps working.
     */
    suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler? = null): ToolResult {
        val plugin = activePlugins().firstOrNull { candidate ->
            candidate.tools().any { it.name == call.name }
        } ?: return ToolResult(
            toolCallId = call.id,
            name = call.name,
            content = "Unknown or disabled tool: ${call.name}",
            isError = true
        )

        return try {
            val result = withTimeout(callTimeoutMs) {
                plugin.execute(call, confirmationHandler)
            }
            result.copy(content = truncate(result.content))
        } catch (e: TimeoutCancellationException) {
            // If the *caller's* scope was cancelled (not our timeout) propagate that instead.
            currentCoroutineContext().ensureActive()
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = "Tool ${call.name} timed out after ${callTimeoutMs}ms",
                isError = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(
                toolCallId = call.id,
                name = call.name,
                content = "Tool ${call.name} failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true
            )
        }
    }

    private fun truncate(content: String): String {
        if (content.length <= maxOutputChars) return content
        return content.take(maxOutputChars.coerceAtLeast(0)) + "\n... [truncated]"
    }
}
