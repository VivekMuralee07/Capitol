package com.capitol.app

import androidx.test.core.app.ApplicationProvider
import com.capitol.app.agent.AgentTools
import com.capitol.app.agent.CapitolAgent
import com.capitol.app.data.prefs.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the agent's streaming tool loop against a fake Messages API that speaks SSE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentStreamingTest {
    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" })

    private fun start(id: String, inTok: Int, cacheRead: Int = 0) = "message_start" to
        """{"type":"message_start","message":{"id":"$id","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":$inTok,"output_tokens":1,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":0}}}"""

    @Test fun streamsToolCallThenAnswer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(sse(
                start("msg_1", 1000),
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"list_indicators","input":{}}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":30}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ))
            server.enqueue(sse(
                start("msg_2", 200, cacheRead = 1500),
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Capitol supports "}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"21 indicators."}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ))
            val c = ApplicationProvider.getApplicationContext<CapitolApp>().container
            c.secrets.put(SecretStore.Key.ANTHROPIC_API_KEY, "sk-ant-test")
            val agent = CapitolAgent(c.secrets, AgentTools(c.market, c.watchlist, c.portfolio, c.indmoneyMcp), server.url("/").toString().trimEnd('/'))

            val steps = mutableListOf<CapitolAgent.Step>()
            val answer = agent.ask("Which indicators do you support?") { steps += it }

            assertEquals("Capitol supports 21 indicators.", answer.text)
            assertEquals(listOf("Capitol supports ", "21 indicators."), steps.filterIsInstance<CapitolAgent.Step.TextDelta>().map { it.text })
            assertTrue(steps.any { it is CapitolAgent.Step.ToolDone && it.ok })
            assertEquals(CapitolAgent.Usage(inputTokens = 1200, outputTokens = 42, cacheReadTokens = 1500), answer.usage)

            val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("claude-opus-5-5", first["model"]!!.jsonPrimitive.content)
            assertEquals("true", first["stream"].toString())
            assertTrue("cache_control" in first)
            assertTrue(first["tools"]!!.jsonArray.all { it.jsonObject["eager_input_streaming"].toString() == "true" })
            // Second request carries the assistant tool_use turn and our tool_result.
            val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val msgs = second["messages"]!!.jsonArray
            assertEquals(3, msgs.size)
            val result = msgs[2].jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
            assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
            assertTrue(result["content"].toString().contains("RSI"))
        }
    }
}
