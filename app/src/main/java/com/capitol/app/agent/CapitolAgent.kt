package com.capitol.app.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.capitol.app.data.prefs.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Capitol's AI analyst. A manual tool-use loop on the Claude Messages API:
 * Claude decides which on-device tools to call (analysis engine, watchlist,
 * portfolio), we execute them locally and feed results back until it answers.
 *
 * The user's own Anthropic API key is stored encrypted in [SecretStore].
 */
class CapitolAgent(
    private val secrets: SecretStore,
    private val tools: AgentTools,
) {
    sealed interface Step {
        data class ToolCall(val name: String, val summary: String) : Step
        data class ToolDone(val name: String, val ok: Boolean) : Step
    }

    class AgentException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Full conversation, including tool calls, so follow-up questions keep context. */
    private val history = mutableListOf<MessageParam>()

    val isConfigured get() = secrets.has(SecretStore.Key.ANTHROPIC_API_KEY)

    fun reset() = history.clear()

    private fun client(): AnthropicClient {
        val key = secrets.get(SecretStore.Key.ANTHROPIC_API_KEY)
            ?: throw AgentException("Add your Anthropic API key in Settings → AI Agent to enable the agent.")
        if (client == null || clientKey != key) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    private val baseToolDefs: List<Tool> by lazy {
        tools.specs.map { spec ->
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder().apply {
                                spec.properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                            }.build()
                        )
                        .required(spec.required)
                        .build()
                )
                .build()
        }
    }

    /** INDmoney MCP tools (if connected) converted to Claude tool definitions. */
    private suspend fun externalToolDefs(): List<Tool> = tools.externalTools().map { ext ->
        val schema = ext.tool.inputSchema
        val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        Tool.builder()
            .name(ext.claudeName)
            .description("[INDmoney account, read via INDmoney's official MCP server] " + ext.tool.description.ifBlank { ext.tool.name })
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply {
                        props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(toPlain(v))) }
                    }.build())
                    .required(required)
                    .build()
            )
            .build()
    }

    private fun baseParams(toolDefs: List<Tool>) = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(16000L)
        .system(SYSTEM_PROMPT)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        // Server-side refusal fallback: if the primary model declines, the API retries on a fallback model.
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .apply { toolDefs.forEach { addTool(it) } }

    /**
     * Sends [userText] and runs the tool loop until Claude produces a final answer.
     * [onStep] is invoked (on the IO dispatcher) for live progress in the UI.
     */
    suspend fun ask(userText: String, onStep: (Step) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val c = client()
        history += MessageParam.ofUser(userText)
        val checkpoint = history.size - 1
        val toolDefs = baseToolDefs + externalToolDefs()
        try {
            repeat(MAX_TURNS) {
                val response: Message = c.messages().create(baseParams(toolDefs).messages(history).build())
                history += response.toParam()

                when (response.stopReason().orElse(null)) {
                    StopReason.TOOL_USE -> {
                        val results = mutableListOf<ContentBlockParam>()
                        for (block in response.content()) {
                            val use = block.toolUse().orElse(null) ?: continue
                            val input: JsonObject = runCatching {
                                json.parseToJsonElement(use._input().toJsonString()).jsonObject
                            }.getOrDefault(JsonObject(emptyMap()))
                            onStep(Step.ToolCall(use.name(), describe(use.name(), input)))
                            val (text, isError) = try {
                                tools.execute(use.name(), input) to false
                            } catch (e: Exception) {
                                "Error: ${e.message}" to true
                            }
                            onStep(Step.ToolDone(use.name(), !isError))
                            results += ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(isError).build()
                            )
                        }
                        history += MessageParam.ofUserOfContentBlockParams(results)
                    }
                    StopReason.REFUSAL -> return@withContext "I can't help with that request."
                    StopReason.MAX_TOKENS -> return@withContext textOf(response) + "\n\n_(Response truncated.)_"
                    else -> return@withContext textOf(response).ifBlank { "Done." }
                }
            }
            "I ran out of steps before finishing. Try a narrower question."
        } catch (e: UnauthorizedException) {
            rollback(checkpoint); throw AgentException("Your Anthropic API key was rejected. Check it in Settings.")
        } catch (e: RateLimitException) {
            rollback(checkpoint); throw AgentException("Rate limited by the Claude API. Please try again in a moment.")
        } catch (e: AnthropicServiceException) {
            rollback(checkpoint); throw AgentException("Claude API error (${e.statusCode()}): ${e.message}")
        }
    }

    /** One-shot digest for the background autopilot (no tools). */
    suspend fun digest(report: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        runCatching {
            val resp = client().messages().create(
                MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(2000L)
                    .system("You are Capitol's market autopilot. Summarise watchlist technical changes in at most 3 short sentences for a phone notification. No investment advice, no markdown.")
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .addUserMessage(report)
                    .build()
            )
            if (resp.stopReason().orElse(null) == StopReason.REFUSAL) null else textOf(resp).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Drop a failed turn so the next request starts from a valid conversation. */
    private fun rollback(toSize: Int) { while (history.size > toSize) history.removeAt(history.size - 1) }

    private fun textOf(m: Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    private fun describe(name: String, input: JsonObject): String {
        fun s(k: String) = input[k]?.toString()?.trim('"')
        return when (name) {
            "search_symbol" -> "Searching for “${s("query")}”"
            "run_technical_analysis" -> "Analysing ${s("symbol")}" + (input["indicators"]?.let { " with $it" } ?: "")
            "get_price_summary" -> "Fetching price history for ${s("symbol")}"
            "get_watchlist" -> "Reading your watchlist"
            "scan_watchlist" -> "Scanning your whole watchlist"
            "add_to_watchlist" -> "Adding ${s("symbol")} to watchlist"
            "remove_from_watchlist" -> "Removing ${s("symbol")} from watchlist"
            "get_portfolio" -> "Reading your broker holdings"
            else -> if (name.startsWith(AgentTools.INDMONEY_PREFIX))
                "Asking INDmoney: ${name.removePrefix(AgentTools.INDMONEY_PREFIX).replace('_', ' ')}" else name
        }
    }

    /** kotlinx JSON → plain Kotlin values, for the SDK's JsonValue.from(). */
    private fun toPlain(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { toPlain(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { toPlain(it) }
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" || e.content == "false" -> e.content.toBoolean()
            else -> e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
        }
    }

    private fun JsonValue.toJsonString(): String =
        com.anthropic.core.jsonMapper().writeValueAsString(this)

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_TURNS = 12

        private val SYSTEM_PROMPT = """
            You are Capitol's AI market analyst inside an Android app. You help users understand the technical picture of stocks, indices and ETFs using the app's own tools, which fetch Yahoo Finance data (the same source as the yfinance library) and compute indicators on-device.

            How to work:
            - Always ground statements in tool output. Run run_technical_analysis (with the indicators the user asked about, or a sensible mix of trend + momentum + volume if they didn't) before giving an opinion on a symbol.
            - If the user gives a company name rather than a ticker, call search_symbol first. Indian stocks use .NS (NSE) or .BO (BSE) suffixes.
            - When the user picks specific indicators, explain the result through those indicators specifically: what each one measures, what its current value says, and how they agree or conflict.
            - For portfolio or watchlist questions, use get_portfolio / scan_watchlist, then analyse the most relevant names.
            - Tools whose names start with indmoney__ read the user's INDmoney account (including US stocks) through INDmoney's official MCP server. Use them for INDmoney holdings questions; US tickers have no suffix (e.g. AAPL) when you pass them to run_technical_analysis.
            - Only modify the watchlist when the user explicitly asks.

            How to answer:
            - The user reads on a phone. Lead with a one-line verdict, then short sections with bullet points. Use markdown bold for key numbers and levels. Keep it under about 300 words unless asked for depth.
            - Mention concrete support/resistance levels and what would invalidate the view.
            - This is educational technical analysis, not personalised investment advice; say so briefly once at the end, and never tell the user to buy or sell a specific quantity.
        """.trimIndent()
    }
}
