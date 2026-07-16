package com.orchard.backend.vector

import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.resource.ModelResourceDemand
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

interface ModelProvider : AutoCloseable {
    suspend fun triage(prompt: String): String
    suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String
    suspend fun proposeWorkDefinition(prompt: String): String = error("Work-definition proposals are unsupported")
    fun modelIdentity(): String = "unknown"
    fun bindingProfile(): ModelBindingProfile = ModelBindingProfile(
        bindingId = modelIdentity(),
        provider = "unknown",
        model = modelIdentity(),
        contextWindowTokens = 8_192,
        capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )
    suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): ModelGeneration {
        val output = proposeWorkDefinition(prompt)
        return ModelGeneration(output, estimateModelTokens(prompt), estimateModelTokens(output))
    }
    suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        executeWorkDefinition(prompt, maxOutputTokens)
    suspend fun executeCircuitSynthesis(
        prompt: String,
        maxOutputTokens: Int,
        contextWindowTokens: Int,
    ): ModelGeneration = executeWorkDefinition(prompt, maxOutputTokens, contextWindowTokens)
    fun resourceDemand(profile: ModelExecutionProfile): ModelResourceDemand = ModelResourceDemand(0, 1)
    fun architectResourceDemand(): ModelResourceDemand = ModelResourceDemand(0, 1)
    override fun close() = Unit
}

class OllamaClient(
    private val endpoint: String = "http://127.0.0.1:11434/api/generate",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }
    private val triagePrompt = loadArchitectPrompt("architect_phase0_triage.md")
    private val planningPrompt = loadArchitectPrompt("architect_phase2_planning.md")

    override suspend fun triage(prompt: String): String = generate("$triagePrompt\nUser request:\n$prompt").text

    override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String {
        val workspaceText = buildString {
            repeat(workspace.entityCount) { index ->
                val entity = workspace.entityAt(index)
                append("[${entity.type} id=${entity.id} parent=${entity.parentId} title=${entity.title}] ")
            }
        }
        return generate(
            "$planningPrompt\nFirst-pass actionTypeId=$actionType, entityTypeId=$entityType.\n" +
                "Workspace: $workspaceText\nUser request:\n$prompt"
        ).text
    }

    override suspend fun proposeWorkDefinition(prompt: String): String = generate(prompt).text

    override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, CONTEXT_WINDOW_TOKENS)

    override suspend fun executeWorkDefinition(
        prompt: String,
        maxOutputTokens: Int,
        contextWindowTokens: Int,
    ): ModelGeneration = generate(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeCircuitSynthesis(
        prompt: String,
        maxOutputTokens: Int,
        contextWindowTokens: Int,
    ): ModelGeneration = generate(prompt, maxOutputTokens, contextWindowTokens)

    override fun resourceDemand(profile: ModelExecutionProfile): ModelResourceDemand = ModelResourceDemand(
        memoryBytes = MODEL_RESIDENT_MEMORY_BYTES +
            (profile.inputBudgetTokens + profile.outputBudgetTokens).toLong() * KV_CACHE_BYTES_PER_TOKEN,
        cpuUnits = MODEL_CPU_UNITS,
    )

    override fun architectResourceDemand(): ModelResourceDemand = ModelResourceDemand(
        memoryBytes = MODEL_RESIDENT_MEMORY_BYTES +
            DEFAULT_ARCHITECT_CONTEXT_TOKENS.toLong() * KV_CACHE_BYTES_PER_TOKEN,
        cpuUnits = MODEL_CPU_UNITS,
    )

    override fun modelIdentity(): String = MODEL

    override fun bindingProfile(): ModelBindingProfile = ModelBindingProfile(
        bindingId = "ollama:$MODEL:json:t0:s42",
        provider = "ollama",
        model = MODEL,
        contextWindowTokens = CONTEXT_WINDOW_TOKENS,
        capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        configuration = mapOf(
            "format" to "json",
            "temperature" to "0",
            "seed" to "42",
            "num_thread" to MODEL_CPU_UNITS.toString(),
        ),
    )

    private suspend fun generate(
        prompt: String,
        maxOutputTokens: Int? = null,
        contextWindowTokens: Int = DEFAULT_ARCHITECT_CONTEXT_TOKENS,
    ): ModelGeneration {
        val response = client.post(endpoint) {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                OllamaGenerateRequest(
                    model = MODEL,
                    prompt = prompt,
                    stream = false,
                    format = "json",
                    options = OllamaOptions(
                        temperature = 0,
                        seed = 42,
                        numPredict = maxOutputTokens,
                        numContext = contextWindowTokens,
                        numThread = MODEL_CPU_UNITS,
                    ),
                )
            )
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Ollama returned HTTP ${response.status.value}: ${body.take(512)}" }
        check(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Ollama response exceeded $MAX_RESPONSE_BYTES bytes" }
        val decoded = json.decodeFromString<OllamaGenerateResponse>(body)
        return ModelGeneration(
            text = decoded.response,
            promptTokens = decoded.promptEvalCount ?: estimateModelTokens(prompt),
            completionTokens = decoded.evalCount ?: estimateModelTokens(decoded.response),
        )
    }

    override fun close() {
        client.close()
    }

    private fun loadArchitectPrompt(name: String): String {
        val stream = requireNotNull(OllamaClient::class.java.getResourceAsStream("/default-system-prompts/$name")) {
            "Missing Architect prompt resource: $name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MODEL = "phi3:mini"
        const val CONTEXT_WINDOW_TOKENS = 131_072
        const val DEFAULT_ARCHITECT_CONTEXT_TOKENS = 8_192
        const val MODEL_RESIDENT_MEMORY_BYTES = 2_400_000_000L
        const val KV_CACHE_BYTES_PER_TOKEN = 393_216L
        const val MODEL_CPU_UNITS = 1
    }
}

@Serializable
internal data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean,
    val format: String,
    val options: OllamaOptions,
)

@Serializable
internal data class OllamaOptions(
    val temperature: Int,
    val seed: Int,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("num_ctx") val numContext: Int? = null,
    @SerialName("num_thread") val numThread: Int? = null,
)

@Serializable
private data class OllamaGenerateResponse(
    val response: String,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
)