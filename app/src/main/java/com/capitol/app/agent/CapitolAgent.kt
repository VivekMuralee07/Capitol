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
    /** Override for tests (a local fake API); null uses api.anthropic.com. */
    private val baseUrl: String? = null,
) {
    sealed interface Step {
        data class ToolCall(val name: String, val summary: String) : Step
        data class ToolDone(val name: String, val ok: Boolean) : Step
        data class TextDelta(val text: String) : Step
    }

    class AgentException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Full conversation, including tool calls, so follow-up questions keep context. */
    private val history = mutableListOf<MessageParam>()

    val isConfigured get() = secrets.has(SecretStore.Key.ANTHROPIC_API_KEY)

    fun reset() { history.clear(); transcript.clear(); lastInputTokens = 0 }

    private fun client(): AnthropicClient {
        val key = secrets.get(SecretStore.Key.ANTHROPIC_API_KEY)
            ?: throw AgentException("Add your Anthropic API key in Settings → AI Agent to enable the agent.")
        if (client == null || clientKey != key) {
            client?.close()
            client = AnthropicOkHttpClient.builder().apiKey(key).apply { baseUrl?.let { baseUrl(it) } }.build()
            clientKey = key
        }
        return client!!
    }

    private val baseToolDefs: List<Tool> by lazy {
        tools.specs.map { spec ->
            Tool.builder()
                .eagerInputStreaming(true)
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
            .eagerInputStreaming(true)
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
        .maxTokens(32000L)
        .system(SYSTEM_PROMPT)
        // Automatic prompt caching: the system prompt, tools and earlier turns are re-read at 5% of the price.
        .cacheControl(com.anthropic.models.messages.CacheControlEphemeral.builder().build())
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        // Server-side refusal fallback: if the primary model declines, the API retries on a fallback model.
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .apply { toolDefs.forEach { addTool(it) } }

    /** Result of one question: the answer text plus what it cost. */
    data class Answer(val text: String, val usage: Usage, val condensed: Boolean)

    /** Token usage and estimated cost (USD) at Claude Opus 5.5 list prices. */
    @kotlinx.serialization.Serializable
    data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0) {
        operator fun plus(o: Usage) = Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens, cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens)
        val costUsd: Double get() = (inputTokens * PRICE_IN + outputTokens * PRICE_OUT + cacheReadTokens * PRICE_CACHE_READ + cacheWriteTokens * PRICE_CACHE_WRITE) / 1_000_000.0
        companion object {
            // USD per million tokens (Claude Opus 5.5). Cache writes assume the 5-minute cache (1.25× input).
            const val PRICE_IN = 4.0; const val PRICE_OUT = 20.0; const val PRICE_CACHE_READ = 0.20; const val PRICE_CACHE_WRITE = 5.0
        }
    }

    /** Everything needed to resume a conversation later (saved chats). */
    @kotlinx.serialization.Serializable
    data class Session(val apiHistory: List<String>, val transcript: List<Pair<String, String>>, val lastInputTokens: Long)

    /** User questions and final answers (text only), used to build a recap when condensing. */
    private val transcript = mutableListOf<Pair<String, String>>()
    private var lastInputTokens = 0L

    fun exportSession() = Session(history.map { com.anthropic.core.jsonMapper().writeValueAsString(it) }, transcript.toList(), lastInputTokens)

    fun restore(session: Session?) {
        history.clear(); transcript.clear(); lastInputTokens = 0
        session ?: return
        runCatching { session.apiHistory.map { com.anthropic.core.jsonMapper().readValue(it, MessageParam::class.java) } }
            .onSuccess { history += it; transcript += session.transcript; lastInputTokens = session.lastInputTokens }
    }

    /**
     * Sends [userText] and runs the tool loop until Claude produces a final answer, streaming
     * text as it arrives. [onStep] is invoked (on the IO dispatcher) for live progress in the UI.
     */
    suspend fun ask(userText: String, onStep: (Step) -> Unit = {}): Answer = withContext(Dispatchers.IO) {
        val c = client()
        // Long conversations get expensive (every turn resends the history). Past the threshold, start a
        // fresh conversation seeded with a short recap instead of editing the old one.
        val condensed = history.isNotEmpty() && lastInputTokens > CONDENSE_AT_TOKENS
        if (condensed) {
            val recap = transcript.takeLast(4).joinToString("\n\n") { (q, a) -> "User asked: ${q.take(300)}\nYou answered: ${a.take(700)}" }
            history.clear()
            history += MessageParam.ofUser("Recap of our earlier conversation (condensed to save cost):\n\n$recap\n\n---\nNew question: $userText")
        } else {
            history += MessageParam.ofUser(userText)
        }
        val checkpoint = if (condensed) 0 else history.size - 1
        val toolDefs = baseToolDefs + externalToolDefs()
        val required = tools.specs.associate { it.name to it.required }
        var usage = Usage()
        try {
            repeat(MAX_TURNS) {
                val response: Message = streamTurn(c, baseParams(toolDefs).messages(history).build(), onStep)
                response.usage().let { u ->
                    usage += Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L))
                    lastInputTokens = u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L)
                }
                history += response.toParam()

                when (response.stopReason().orElse(null)) {
                    StopReason.TOOL_USE -> {
                        val results = mutableListOf<ContentBlockParam>()
                        for (block in response.content()) {
                            val use = block.toolUse().orElse(null) ?: continue
                            val raw = use._input().toJsonString()
                            val input = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                            val missing = input?.let { inp -> required[use.name()].orEmpty().filter { it !in inp } }.orEmpty()
                            val (text, isError) = when {
                                // Eager input streaming means inputs aren't validated by the API: check them here.
                                input == null -> "{\"INVALID_JSON\": ${JsonValue.from(raw).toJsonString()}}" to true
                                missing.isNotEmpty() -> "Missing required argument(s): ${missing.joinToString()}" to true
                                else -> {
                                    onStep(Step.ToolCall(use.name(), describe(use.name(), input)))
                                    try { tools.execute(use.name(), input) to false } catch (e: Exception) { "Error: ${e.message}" to true }
                                        .also { onStep(Step.ToolDone(use.name(), !it.second)) }
                                }
                            }
                            results += ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(isError).build()
                            )
                        }
                        history += MessageParam.ofUserOfContentBlockParams(results)
                    }
                    StopReason.REFUSAL -> return@withContext finish(userText, "I can't help with that request.", usage, condensed)
                    StopReason.MAX_TOKENS -> return@withContext finish(userText, textOf(response) + "\n\n_(Response truncated.)_", usage, condensed)
                    else -> return@withContext finish(userText, textOf(response).ifBlank { "Done." }, usage, condensed)
                }
            }
            finish(userText, "I ran out of steps before finishing. Try a narrower question.", usage, condensed)
        } catch (e: UnauthorizedException) {
            rollback(checkpoint); throw AgentException("Your Anthropic API key was rejected. Check it in Settings.")
        } catch (e: RateLimitException) {
            rollback(checkpoint); throw AgentException("Rate limited by the Claude API. Please try again in a moment.")
        } catch (e: AnthropicServiceException) {
            rollback(checkpoint); throw AgentException("Claude API error (${e.statusCode()}): ${e.message}")
        }
    }

    private fun finish(q: String, a: String, usage: Usage, condensed: Boolean): Answer {
        transcript += q to a
        return Answer(a, usage, condensed)
    }

    /** Streams one model turn, forwarding text deltas, and returns the accumulated message. */
    private fun streamTurn(c: AnthropicClient, params: MessageCreateParams, onStep: (Step) -> Unit): Message {
        val acc = com.anthropic.helpers.MessageAccumulator.create()
        c.messages().createStreaming(params).use { stream ->
            stream.stream().forEach { event ->
                acc.accumulate(event)
                event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onStep(Step.TextDelta(it.text())) }
            }
        }
        return acc.message()
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
            "get_watchlist" -> "Reading your watchlists"
            "get_calendar" -> "Checking earnings & dividend dates"
            "plan_trade" -> "Planning a trade on ${s("symbol")}"
            "run_screener" -> "Screening ${s("universe")}"
            "get_portfolio_analytics" -> "Analysing your portfolio"
            "get_fundamentals" -> "Fetching fundamentals for ${s("symbol")}"
            "get_news" -> "Reading news on ${s("symbol")}"
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
        /** Start a condensed conversation once a turn's input exceeds this many tokens. */
        const val CONDENSE_AT_TOKENS = 60_000L

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
