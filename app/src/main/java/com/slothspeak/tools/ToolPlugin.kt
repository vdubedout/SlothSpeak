package com.slothspeak.tools

/**
 * A unit of tool capability (e.g. "Brave Search", "HTTP request").
 *
 * A plugin only participates in the agent loop when both [isEnabled] (user toggle) and
 * [isConfigured] (required credentials/settings present) are true.
 */
interface ToolPlugin {
    val id: String
    val displayName: String
    val description: String

    /** True when the plugin has everything it needs to run (e.g. API key present). */
    fun isConfigured(): Boolean

    /** True when the user has enabled the plugin. */
    fun isEnabled(): Boolean

    /** Tool definitions exposed to the LLM when this plugin is active. */
    fun tools(): List<ToolDefinition>

    /**
     * Executes one tool call. Exceptions are caught by [ToolRegistry] and converted to
     * `isError` results, so implementations may simply throw.
     *
     * @param confirmationHandler optional handler used to ask the user before performing
     * sensitive side effects (writes, new hosts).
     */
    suspend fun execute(call: ToolCall, confirmationHandler: ToolConfirmationHandler? = null): ToolResult
}
