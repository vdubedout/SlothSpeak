package com.slothspeak.harness

import com.slothspeak.api.models.ChatRequestMessage
import com.slothspeak.api.models.ChatToolCall
import com.slothspeak.api.models.ChatToolCallFunction
import com.slothspeak.tools.ToolCall
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire mapping from provider-agnostic [HarnessMessage]s to OpenAI Chat Completions request
 * messages. Plain JVM test — exercises the Moshi codegen adapters only.
 */
class LlmChatAdapterMappingTest {

    private val moshi = Moshi.Builder().build()
    private val messageAdapter = moshi.adapter(ChatRequestMessage::class.java)
    private val objectAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    @Suppress("UNCHECKED_CAST")
    private fun keysOf(message: HarnessMessage): Set<String> =
        requireNotNull(objectAdapter.fromJson(messageAdapter.toJson(message.toChatRequestMessage()))).keys

    @Test
    fun systemAndUser_mapToRoleAndContentOnly() {
        val system = HarnessMessage.System("Be brief.").toChatRequestMessage()
        assertEquals("system", system.role)
        assertEquals("Be brief.", system.content)
        assertNull(system.toolCalls)
        assertNull(system.toolCallId)
        assertNull(system.name)

        val user = HarnessMessage.User("Hello?").toChatRequestMessage()
        assertEquals("user", user.role)
        assertEquals("Hello?", user.content)
        assertNull(user.toolCalls)
    }

    @Test
    fun assistantHistoryTurn_hasNoToolCalls() {
        val message = HarnessMessage.Assistant("Previous answer").toChatRequestMessage()

        assertEquals("assistant", message.role)
        assertEquals("Previous answer", message.content)
        assertNull(message.toolCalls)
        assertEquals(setOf("role", "content"), keysOf(HarnessMessage.Assistant("Previous answer")))
    }

    @Test
    fun assistantToolCallTurn_replaysToolCallsAndEmptyContent() {
        val calls = listOf(
            ToolCall(id = "call_1", name = "web_search", argumentsJson = """{"q":"weather"}"""),
            ToolCall(id = "call_2", name = "lookup", argumentsJson = """{"q":"paris"}""")
        )

        val message = HarnessMessage.Assistant(content = null, toolCalls = calls).toChatRequestMessage()

        assertEquals("assistant", message.role)
        // Empty string is the most portable "no text" for strict OpenAI-compatible servers.
        assertEquals("", message.content)
        assertEquals(
            listOf(
                ChatToolCall(id = "call_1", function = ChatToolCallFunction("web_search", """{"q":"weather"}""")),
                ChatToolCall(id = "call_2", function = ChatToolCallFunction("lookup", """{"q":"paris"}"""))
            ),
            message.toolCalls
        )
        assertEquals(setOf("role", "content", "tool_calls"), keysOf(HarnessMessage.Assistant(null, calls)))
    }

    @Test
    fun toolResult_mapsToRoleToolWithToolCallId() {
        val message = HarnessMessage.Tool(toolCallId = "call_1", name = "web_search", content = "sunny")
            .toChatRequestMessage()

        assertEquals("tool", message.role)
        assertEquals("sunny", message.content)
        assertEquals("call_1", message.toolCallId)
        assertNull("name is not part of the OpenAI tool-message schema", message.name)
        assertNull(message.toolCalls)
        assertEquals(
            setOf("role", "content", "tool_call_id"),
            keysOf(HarnessMessage.Tool("call_1", "web_search", "sunny"))
        )
    }

    @Test
    fun noMessageShape_everEchoesReasoningContent() {
        val messages = listOf(
            HarnessMessage.System("s"),
            HarnessMessage.User("u"),
            HarnessMessage.Assistant("a"),
            HarnessMessage.Assistant(null, listOf(ToolCall("call_1", "web_search", "{}"))),
            HarnessMessage.Tool("call_1", "web_search", "result")
        )

        val adapter = moshi.adapter<List<ChatRequestMessage>>(
            Types.newParameterizedType(List::class.java, ChatRequestMessage::class.java)
        )
        val json = adapter.toJson(messages.map { it.toChatRequestMessage() })

        assertFalse(json.contains("reasoning_content"))
        val root = requireNotNull(objectAdapter.fromJson("""{"messages":$json}"""))
        assertEquals(messages.size, (root["messages"] as List<*>).size)
    }
}
