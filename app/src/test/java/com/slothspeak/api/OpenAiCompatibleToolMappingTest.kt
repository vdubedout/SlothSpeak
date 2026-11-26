package com.slothspeak.api

import com.slothspeak.api.models.ChatCompletionRequest
import com.slothspeak.api.models.ChatRequestMessage
import com.slothspeak.api.models.ChatToolCall
import com.slothspeak.api.models.ChatToolCallFunction
import com.slothspeak.tools.ToolDefinition
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolDefinition -> OpenAI `tools[]` and ChatToolCall -> ToolCall mappings used by
 * [OpenAiCompatibleClient.createChatStep]. Plain JVM test.
 */
class OpenAiCompatibleToolMappingTest {

    private val moshi = Moshi.Builder().build()
    private val schemaAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val objectAdapter = schemaAdapter

    private val schemaJson = """
        {"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"}},"required":["query"]}
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    @Test
    fun toolDefinition_withNestedSchema_becomesOpenAiFunctionTool() {
        val definition = ToolDefinition(name = "web_search", description = "Search the web", inputSchemaJson = schemaJson)

        val tool = definition.toChatTool(schemaAdapter)

        assertEquals("function", tool.type)
        assertEquals("web_search", tool.function.name)
        assertEquals("Search the web", tool.function.description)
        assertEquals(schemaAdapter.fromJson(schemaJson), tool.function.parameters)

        // End-to-end through the generated request adapter: the schema lands in `parameters`.
        val request = ChatCompletionRequest(
            model = "m",
            messages = listOf(ChatRequestMessage(role = "user", content = "hi")),
            tools = listOf(tool),
            toolChoice = "auto"
        )
        val root = requireNotNull(objectAdapter.fromJson(moshi.adapter(ChatCompletionRequest::class.java).toJson(request)))
        val function = root["tools"].let { tools -> (tools as List<*>).single() } as Map<String, Any?>
        assertEquals(schemaJson.trim().let { schemaAdapter.fromJson(it) }, function["function"].let { (it as Map<*, *>)["parameters"] })
    }

    @Test
    fun toolDefinition_withEmptyObjectSchema_serializesEmptyParameters() {
        val tool = ToolDefinition("ping", "Ping", "{}").toChatTool(schemaAdapter)

        assertEquals(emptyMap<String, Any?>(), tool.function.parameters)
    }

    @Test
    fun toolDefinition_withMalformedSchema_fallsBackToEmptyParameters() {
        val malformed = listOf("not json", "{\"type\":", "[1,2]", "\"just a string\"", "")

        malformed.forEach { bad ->
            val tool = ToolDefinition("broken", "Broken schema", bad).toChatTool(schemaAdapter)

            assertEquals("tool is still advertised for input: $bad", "broken", tool.function.name)
            assertEquals("input: $bad", emptyMap<String, Any?>(), tool.function.parameters)
        }
    }

    @Test
    fun toolCall_withIdAndArguments_isPreserved() {
        val toolCall = ChatToolCall(id = "call_123", function = ChatToolCallFunction("lookup", """{"q":"x"}"""))

        val mapped = toolCall.toToolCall(index = 3)

        assertEquals("call_123", mapped.id)
        assertEquals("lookup", mapped.name)
        assertEquals("""{"q":"x"}""", mapped.argumentsJson)
    }

    @Test
    fun toolCall_withoutId_getsAGeneratedUniqueId() {
        val toolCall = ChatToolCall(function = ChatToolCallFunction("web_search", "{}"))

        val first = toolCall.toToolCall(index = 0)
        val second = toolCall.toToolCall(index = 0)

        assertTrue(first.id.isNotBlank())
        assertTrue(second.id.isNotBlank())
        assertNotEquals(first.id, second.id)
        assertEquals("web_search", first.name)
        assertEquals("{}", first.argumentsJson)
    }

    @Test
    fun toolCall_withBlankArguments_isNormalizedToEmptyJsonObject() {
        val toolCall = ChatToolCall(id = "call_1", function = ChatToolCallFunction("no_args", arguments = ""))

        val mapped = toolCall.toToolCall(index = 0)

        assertEquals("call_1", mapped.id)
        assertEquals("{}", mapped.argumentsJson)
        assertFalse(mapped.argumentsJson.isBlank())
    }
}
