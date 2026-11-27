package com.slothspeak.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Moshi codegen (no reflection) wire format for OpenAI function calling:
 * `tools` / `tool_choice` are omitted when null, nested JSON Schema maps serialise
 * correctly, and provider responses with `tool_calls` + null `content` + `reasoning_content`
 * parse. Plain JVM test — Moshi codegen adapters need no Android framework.
 */
class ChatCompletionsToolsJsonTest {

    private val moshi = Moshi.Builder().build()
    private val objectAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val requestAdapter = moshi.adapter(ChatCompletionRequest::class.java)
    private val responseAdapter = moshi.adapter(ChatCompletionResponse::class.java)
    private val messageAdapter = moshi.adapter(ChatRequestMessage::class.java)

    private val schemaJson = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "Search query"},
            "count": {"type": "integer", "minimum": 1, "default": 5},
            "safe": {"type": "boolean"},
            "scope": {"type": "string", "enum": ["web", "news"]},
            "nested": {"type": "object", "properties": {"deep": {"type": "array"}}}
          },
          "required": ["query"],
          "additionalProperties": false
        }
    """.trimIndent()

    // --- Helpers ---

    private fun parseObject(json: String): Map<String, Any?> = requireNotNull(objectAdapter.fromJson(json))

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun arr(value: Any?): List<Any?> = value as List<Any?>

    private val searchTool = ChatTool(
        function = ChatToolFunction(
            name = "web_search",
            description = "Search the web",
            parameters = requireNotNull(objectAdapter.fromJson(schemaJson))
        )
    )

    private fun plainMessages() = listOf(
        ChatRequestMessage(role = "system", content = "You are helpful"),
        ChatRequestMessage(role = "user", content = "Previous question"),
        ChatRequestMessage(role = "assistant", content = "Previous answer"),
        ChatRequestMessage(role = "user", content = "Current question")
    )

    // --- Request: no tools (legacy shape preserved) ---

    @Test
    fun request_withoutTools_omitsToolsAndToolChoice() {
        val request = ChatCompletionRequest(
            model = "glm-5.3",
            messages = plainMessages(),
            thinking = ThinkingConfig(type = "enabled"),
            reasoningEffort = "high",
            maxTokens = 8192,
            stream = null
        )

        val json = requestAdapter.toJson(request)
        val root = parseObject(json)

        assertEquals("glm-5.3", root["model"])
        assertEquals(mapOf("type" to "enabled"), root["thinking"])
        assertEquals("high", root["reasoning_effort"])
        assertEquals(8192.0, (root["max_tokens"] as Number).toDouble(), 0.0)
        assertFalse("tools must be omitted", root.containsKey("tools"))
        assertFalse("tool_choice must be omitted", root.containsKey("tool_choice"))
        assertFalse("stream must be omitted", root.containsKey("stream"))
    }

    @Test
    fun request_historyMessages_carryOnlyRoleAndContent() {
        val json = requestAdapter.toJson(ChatCompletionRequest(model = "m", messages = plainMessages()))
        val messages = arr(parseObject(json)["messages"])

        assertEquals(4, messages.size)
        assertEquals(setOf("role", "content"), obj(messages[1]).keys)
        assertEquals("user", obj(messages[1])["role"])
        assertEquals(setOf("role", "content"), obj(messages[2]).keys)
        assertEquals("assistant", obj(messages[2])["role"])
        assertFalse(json.contains("reasoning_content"))
    }

    // --- Request: with tools ---

    @Test
    fun request_withTools_serializesOpenAiFunctionToolJson() {
        val request = ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = plainMessages(),
            tools = listOf(searchTool),
            toolChoice = "auto"
        )

        val root = parseObject(requestAdapter.toJson(request))

        assertEquals("auto", root["tool_choice"])
        val tools = arr(root["tools"])
        assertEquals(1, tools.size)

        val tool = obj(tools[0])
        assertEquals("function", tool["type"])
        val function = obj(tool["function"])
        assertEquals("web_search", function["name"])
        assertEquals("Search the web", function["description"])

        // The JSON Schema survives as a nested JSON object with every value type intact.
        val parameters = obj(function["parameters"])
        assertEquals("object", parameters["type"])
        assertEquals(false, parameters["additionalProperties"])
        assertEquals(listOf("query"), parameters["required"])
        val properties = obj(parameters["properties"])
        assertEquals("string", obj(properties["query"])["type"])
        assertEquals("Search query", obj(properties["query"])["description"])
        assertEquals("integer", obj(properties["count"])["type"])
        assertEquals(1.0, (obj(properties["count"])["minimum"] as Number).toDouble(), 0.0)
        assertEquals("boolean", obj(properties["safe"])["type"])
        assertEquals(listOf("web", "news"), obj(properties["scope"])["enum"])
        val nested = obj(properties["nested"])
        assertEquals("object", nested["type"])
        assertEquals("array", obj(obj(nested["properties"])["deep"])["type"])
        assertEquals(requireNotNull(objectAdapter.fromJson(schemaJson)), parameters)
    }

    @Test
    fun request_multipleTools_keepOrder() {
        val tools = listOf(
            searchTool,
            ChatTool(function = ChatToolFunction(name = "http_request", description = "Do HTTP", parameters = emptyMap()))
        )
        val root = parseObject(
            requestAdapter.toJson(ChatCompletionRequest(model = "m", messages = plainMessages(), tools = tools, toolChoice = "auto"))
        )

        val serialized = arr(root["tools"]).map { obj(obj(it)["function"])["name"] }
        assertEquals(listOf("web_search", "http_request"), serialized)
        // A tool without arguments still advertises an (empty) schema object.
        val httpFunction = obj(obj(arr(root["tools"])[1])["function"])
        assertEquals(emptyMap<String, Any?>(), httpFunction["parameters"])
    }

    @Test
    fun request_roundTrip_withoutTools() {
        val request = ChatCompletionRequest(
            model = "glm-5.3",
            messages = plainMessages(),
            thinking = ThinkingConfig(type = "disabled"),
            reasoningEffort = null,
            maxTokens = null
        )

        assertEquals(request, requestAdapter.fromJson(requestAdapter.toJson(request)))
    }

    @Test
    fun request_roundTrip_withTools() {
        val request = ChatCompletionRequest(
            model = "deepseek-v4-flash",
            messages = plainMessages(),
            tools = listOf(searchTool),
            toolChoice = "auto"
        )

        assertEquals(request, requestAdapter.fromJson(requestAdapter.toJson(request)))
    }

    // --- Request messages: tool calls / tool results ---

    @Test
    fun requestMessage_assistantToolCalls_useOpenAiFieldNames() {
        val message = ChatRequestMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ChatToolCall(
                    id = "call_abc123",
                    function = ChatToolCallFunction(name = "web_search", arguments = """{"query":"weather in Paris"}""")
                )
            )
        )

        val root = parseObject(messageAdapter.toJson(message))

        assertEquals("assistant", root["role"])
        assertFalse("null content is omitted", root.containsKey("content"))
        assertEquals(setOf("role", "tool_calls"), root.keys)

        val toolCall = obj(arr(root["tool_calls"]).single())
        assertEquals("call_abc123", toolCall["id"])
        assertEquals("function", toolCall["type"])
        val function = obj(toolCall["function"])
        assertEquals("web_search", function["name"])
        assertEquals("""{"query":"weather in Paris"}""", function["arguments"])
    }

    @Test
    fun requestMessage_toolResult_carriesToolCallIdAndNoName() {
        val message = ChatRequestMessage(role = "tool", content = "sunny, 21°C", toolCallId = "call_abc123")

        val root = parseObject(messageAdapter.toJson(message))

        assertEquals("tool", root["role"])
        assertEquals("sunny, 21°C", root["content"])
        assertEquals("call_abc123", root["tool_call_id"])
        assertEquals(setOf("role", "content", "tool_call_id"), root.keys)
    }

    @Test
    fun requestMessage_roundTrip_toolExchange() {
        val messages = listOf(
            ChatRequestMessage(role = "user", content = "weather?"),
            ChatRequestMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ChatToolCall(id = "call_1", function = ChatToolCallFunction("web_search", """{"q":"x"}""")))
            ),
            ChatRequestMessage(role = "tool", content = "sunny", toolCallId = "call_1", name = "web_search")
        )

        val adapter = moshi.adapter<List<ChatRequestMessage>>(
            Types.newParameterizedType(List::class.java, ChatRequestMessage::class.java)
        )
        assertEquals(messages, adapter.fromJson(adapter.toJson(messages)))
    }

    // --- Response ---

    @Test
    fun response_withToolCalls_nullContent_andReasoningContent_parses() {
        val json = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "reasoning_content": "The user wants the weather; I should search.",
                    "tool_calls": [
                      {
                        "id": "call_abc123",
                        "type": "function",
                        "function": {
                          "name": "web_search",
                          "arguments": "{\"query\":\"weather in Paris\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {"prompt_tokens": 42, "completion_tokens": 17, "total_tokens": 59}
            }
        """.trimIndent()

        val response = requireNotNull(responseAdapter.fromJson(json))

        assertEquals("chatcmpl-123", response.id)
        assertEquals(1, response.choices.size)
        val choice = response.choices[0]
        assertEquals("tool_calls", choice.finishReason)
        assertNull(choice.message.content)
        assertEquals("The user wants the weather; I should search.", choice.message.reasoningContent)
        val toolCall = requireNotNull(choice.message.toolCalls).single()
        assertEquals("call_abc123", toolCall.id)
        assertEquals("function", toolCall.type)
        assertEquals("web_search", toolCall.function.name)
        assertEquals("""{"query":"weather in Paris"}""", toolCall.function.arguments)
        assertEquals(42, response.usage?.promptTokens)
        assertEquals(17, response.usage?.completionTokens)
        assertEquals(59, response.usage?.totalTokens)
    }

    @Test
    fun response_plainText_withoutToolCalls_stillParses() {
        val json = """
            {
              "id": "chatcmpl-456",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "It is sunny.", "reasoning_content": ""}, "finish_reason": "stop"}
              ]
            }
        """.trimIndent()

        val response = requireNotNull(responseAdapter.fromJson(json))

        assertEquals("It is sunny.", response.choices[0].message.content)
        assertNull(response.choices[0].message.toolCalls)
        assertEquals("", response.choices[0].message.reasoningContent)
        assertNull(response.usage)
    }

    @Test
    fun response_toolCallWithoutId_tolerantDefault() {
        val json = """
            {"id": "x", "choices": [{"index": 0, "message": {"role": "assistant", "tool_calls": [
              {"type": "function", "function": {"name": "web_search", "arguments": "{}"}}
            ]}}]}
        """.trimIndent()

        val toolCall = requireNotNull(responseAdapter.fromJson(json)).choices[0].message.toolCalls!!.single()

        assertEquals("", toolCall.id)
        assertEquals("web_search", toolCall.function.name)
    }

    @Test
    fun response_roundTrip() {
        val response = ChatCompletionResponse(
            id = "chatcmpl-789",
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatResponseMessage(
                        role = "assistant",
                        content = null,
                        reasoningContent = "thinking",
                        toolCalls = listOf(ChatToolCall(id = "call_1", function = ChatToolCallFunction("lookup", """{"q":"x"}""")))
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = ChatUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3)
        )

        assertEquals(response, responseAdapter.fromJson(responseAdapter.toJson(response)))
        assertTrue(responseAdapter.toJson(response).contains("\"tool_calls\""))
        assertTrue(responseAdapter.toJson(response).contains("\"reasoning_content\""))
    }
}
