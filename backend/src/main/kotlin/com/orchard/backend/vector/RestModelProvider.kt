package com.orchard.backend.vector

import com.orchard.backend.resource.ModelResourceDemand
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration

@Serializable
data class ModelEndpointInspection(
    val endpointId: String,
    val reachable: Boolean,
    val discoveredModels: List<String> = emptyList(),
    val diagnostic: String = "",
)

fun interface CredentialResolver {
    fun resolve(reference: String): String?
}

object EnvironmentCredentialResolver : CredentialResolver {
    override fun resolve(reference: String): String? = reference.takeIf { it.startsWith("env:") }
        ?.removePrefix("env:")
        ?.let(System::getenv)
        ?.takeIf(String::isNotBlank)
}

class CatalogModelProvider(
    private val endpoint: ModelEndpointDefinition,
    private val binding: CatalogModelBinding,
    private val providerPolicy: String = if (endpoint.locality == PROVIDER_LOCALITY_LOCAL) PROVIDER_POLICY_LOCAL_ONLY else PROVIDER_POLICY_CLOUD_ALLOWED,
    private val credentialResolver: CredentialResolver = EnvironmentCredentialResolver,
    engine: HttpClientEngine? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val providerDiagnosticsEnabled: Boolean = System.getenv("ORCHARD_PROVIDER_DIAGNOSTICS") == "1",
    private val diagnosticSink: (String) -> Unit = ::println,
    private val nanoTime: () -> Long = System::nanoTime,
) : ModelProvider {
    private val client = if (engine == null) HttpClient(CIO) { configure() } else HttpClient(engine) { configure() }
    private val triagePrompt = loadPrompt("architect_phase0_triage.md")
    private val planningPrompt = loadPrompt("architect_phase2_planning.md")
    @Volatile
    private var ollamaResidentUntilNanos = 0L

    init {
        validateModelProviderCatalog(
            ModelProviderCatalog(
                policy = if (endpoint.locality == PROVIDER_LOCALITY_LOCAL) PROVIDER_POLICY_LOCAL_ONLY else PROVIDER_POLICY_CLOUD_ALLOWED,
                endpoints = listOf(endpoint),
                bindings = listOf(binding),
            )
        )
        require(endpoint.enabled && binding.endpointId == endpoint.endpointId) { "Catalog model provider authority is invalid" }
    }

    override suspend fun triage(prompt: String): String = generate("$triagePrompt\nUser request:\n$prompt", null, ARCHITECT_CONTEXT).text

    override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String {
        val workspaceText = buildString {
            repeat(workspace.entityCount) { index ->
                val entity = workspace.entityAt(index)
                append("[${entity.type} id=${entity.id} parent=${entity.parentId} title=${entity.title}] ")
            }
        }
        return generate(
            "$planningPrompt\nFirst-pass actionTypeId=$actionType, entityTypeId=$entityType.\nWorkspace: $workspaceText\nUser request:\n$prompt",
            null,
            ARCHITECT_CONTEXT,
        ).text
    }

    override suspend fun proposeWorkDefinition(prompt: String): String = generate(prompt, null, ARCHITECT_CONTEXT).text

    override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, binding.contextWindowTokens)

    override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeCircuitSynthesis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeRepositoryAnalysis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeCodingPatch(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        generate(prompt, maxOutputTokens, contextWindowTokens)

    override fun modelIdentity(): String = binding.model

    override fun bindingProfile(): ModelBindingProfile = ModelBindingProfile(
        bindingId = binding.bindingId,
        provider = endpoint.endpointId,
        model = binding.model,
        contextWindowTokens = binding.contextWindowTokens,
        capabilities = binding.capabilities,
        configuration = binding.configuration + mapOf(
            "protocol" to endpoint.protocol,
            "locality" to endpoint.locality,
            "providerPolicy" to providerPolicy,
        ),
        modelDigest = binding.modelDigest,
    )

    override fun resourceDemand(profile: ModelExecutionProfile): ModelResourceDemand =
        resourceDemand(profile, profile.inputBudgetTokens)

    override fun resourceDemand(profile: ModelExecutionProfile, inputTokens: Int): ModelResourceDemand = if (endpoint.locality == PROVIDER_LOCALITY_LOCAL) {
        require(inputTokens in 0..profile.inputBudgetTokens) { "Model input token demand exceeds the execution profile" }
        val residentDemand = if (ollamaModelRecentlyLoaded()) 0 else binding.residentMemoryBytes
        ModelResourceDemand(
            residentDemand + (inputTokens + profile.outputBudgetTokens).toLong() * KV_CACHE_BYTES_PER_TOKEN,
            binding.cpuUnits,
        )
    } else {
        ModelResourceDemand(0, 1)
    }

    override fun architectResourceDemand(): ModelResourceDemand = resourceDemand(
        ModelExecutionProfile("architect-chat", 1, "ARCHITECT_CHAT", ARCHITECT_CONTEXT, 2_048, binding.capabilities)
    )

    suspend fun inspect(): ModelEndpointInspection = runCatching {
        val response = when (endpoint.protocol) {
            PROVIDER_PROTOCOL_OLLAMA_NATIVE -> client.get(url("/api/tags")) { authorize() }
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE -> client.get(url("/v1/models")) { authorize() }
            else -> error("Unsupported provider protocol ${endpoint.protocol}")
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        check(body.encodeToByteArray().size <= MAX_DISCOVERY_BYTES) { "Discovery response exceeded limit" }
        val models = when (endpoint.protocol) {
            PROVIDER_PROTOCOL_OLLAMA_NATIVE -> json.decodeFromString<OllamaModelsResponse>(body).models.map { it.name }
            else -> json.decodeFromString<OpenAiModelsResponse>(body).data.map { it.id }
        }.distinct().sorted()
        ModelEndpointInspection(endpoint.endpointId, true, models)
    }.getOrElse { error ->
        ModelEndpointInspection(endpoint.endpointId, false, diagnostic = error.message.orEmpty().take(512))
    }

    private suspend fun generate(prompt: String, maxOutputTokens: Int?, contextWindowTokens: Int): ModelGeneration {
        require(contextWindowTokens <= binding.contextWindowTokens) { "Requested context exceeds binding capacity" }
        if (endpoint.protocol == PROVIDER_PROTOCOL_OLLAMA_NATIVE) {
            val structured = generateOllama(prompt, maxOutputTokens, contextWindowTokens, structured = true)
            val completed = if (structured.response.isBlank()) {
                generateOllama(prompt, maxOutputTokens, contextWindowTokens, structured = false)
            } else {
                structured
            }
            check(completed.response.isNotBlank()) {
                "Provider ${endpoint.endpointId} returned an empty response after structured and plain retries " +
                    "(structured=${structured.doneReason.orEmpty()}, plain=${completed.doneReason.orEmpty()})"
            }
            return ModelGeneration(
                completed.response,
                completed.promptEvalCount ?: estimateModelTokens(prompt),
                completed.evalCount ?: estimateModelTokens(completed.response),
            )
        }
        val response = when (endpoint.protocol) {
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE -> client.post(url("/v1/chat/completions")) {
                authorize()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(
                    OpenAiChatRequest(
                        model = binding.model,
                        messages = listOf(OpenAiMessage("user", prompt)),
                        maxTokens = maxOutputTokens,
                        temperature = binding.configuration["temperature"]?.toDoubleOrNull() ?: 0.0,
                    )
                )
            }
            else -> error("Unsupported provider protocol ${endpoint.protocol}")
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Provider ${endpoint.endpointId} returned HTTP ${response.status.value}: ${body.take(512)}" }
        check(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Provider response exceeded $MAX_RESPONSE_BYTES bytes" }
        return json.decodeFromString<OpenAiChatResponse>(body).let { decoded ->
            val text = decoded.choices.singleOrNull()?.message?.content
                ?: error("Provider returned no single completion")
            ModelGeneration(text, decoded.usage?.promptTokens ?: estimateModelTokens(prompt), decoded.usage?.completionTokens ?: estimateModelTokens(text))
        }
    }

    private suspend fun generateOllama(
        prompt: String,
        maxOutputTokens: Int?,
        contextWindowTokens: Int,
        structured: Boolean,
    ): OllamaCatalogResponse {
        val options = OllamaCatalogOptions(
            temperature = binding.configuration["temperature"]?.toDoubleOrNull() ?: 0.0,
            seed = binding.configuration["seed"]?.toIntOrNull() ?: 42,
            numPredict = maxOutputTokens,
            numContext = contextWindowTokens,
            numThread = binding.cpuUnits,
        )
        val response = client.post(url("/api/generate")) {
            authorize()
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            if (structured) {
                setBody(OllamaCatalogRequest(binding.model, prompt, options = options))
            } else {
                setBody(OllamaCatalogPlainRequest(binding.model, prompt, options = options))
            }
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Provider ${endpoint.endpointId} returned HTTP ${response.status.value}: ${body.take(512)}" }
        check(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Provider response exceeded $MAX_RESPONSE_BYTES bytes" }
        return json.decodeFromString<OllamaCatalogResponse>(body).also { decoded ->
            ollamaResidentUntilNanos = nanoTime() + OLLAMA_RESIDENCY_WINDOW_NANOS
            if (providerDiagnosticsEnabled) {
                diagnosticSink(
                    "ORCHARD_PROVIDER_DIAGNOSTIC " + json.encodeToString(
                        OllamaAttemptDiagnostic(
                            endpointId = endpoint.endpointId,
                            model = binding.model,
                            httpStatus = response.status.value,
                            responseBodyBytes = body.encodeToByteArray().size,
                            responseLength = decoded.response.length,
                            thinkingLength = decoded.thinking.orEmpty().length,
                            done = decoded.done,
                            doneReason = decoded.doneReason,
                            promptEvalCount = decoded.promptEvalCount,
                            evalCount = decoded.evalCount,
                            numContext = contextWindowTokens,
                            numPredict = maxOutputTokens,
                            formatPresent = structured,
                            think = false,
                        )
                    )
                )
            }
        }
    }

    private fun HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
        install(HttpRedirect) { checkHttpMethod = true; allowHttpsDowngrade = false }
        followRedirects = false
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        endpoint.credentialReference?.let { reference ->
            val credential = credentialResolver.resolve(reference)
                ?: error("Credential reference $reference is unavailable")
            header(HttpHeaders.Authorization, "Bearer $credential")
        }
    }

    private fun url(path: String): String = endpoint.baseUrl.trimEnd('/') + path

    private fun ollamaModelRecentlyLoaded(): Boolean {
        val residentUntil = ollamaResidentUntilNanos
        return endpoint.protocol == PROVIDER_PROTOCOL_OLLAMA_NATIVE &&
            residentUntil != 0L && residentUntil - nanoTime() > 0
    }

    private fun loadPrompt(name: String): String = requireNotNull(
        CatalogModelProvider::class.java.getResourceAsStream("/default-system-prompts/$name")
    ).bufferedReader().use { it.readText() }

    override fun close() = client.close()

    private companion object {
        const val ARCHITECT_CONTEXT = 8_192
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_DISCOVERY_BYTES = 512 * 1024
        const val KV_CACHE_BYTES_PER_TOKEN = 393_216L
        val OLLAMA_RESIDENCY_WINDOW_NANOS = Duration.ofMinutes(4).toNanos()
    }
}

class ModelProviderRegistry(
    private val store: ModelProviderCatalogStore,
    private val credentialResolver: CredentialResolver = EnvironmentCredentialResolver,
) : ModelProvider {
    @Volatile
    private var activeProviders = build(store.load())
    private val providerView = object : AbstractList<ModelProvider>() {
        override val size: Int
            get() = activeProviders.size

        override fun get(index: Int): ModelProvider = activeProviders[index]

        override fun iterator(): Iterator<ModelProvider> = activeProviders.iterator()
    }

    @Synchronized
    fun catalog(): ModelProviderCatalog = store.load()

    @Synchronized
    fun providers(): List<ModelProvider> = providerView

    @Synchronized
    fun update(catalog: ModelProviderCatalog) {
        validateModelProviderCatalog(catalog)
        val replacement = build(catalog)
        store.save(catalog)
        val previous = activeProviders
        activeProviders = replacement
        previous.forEach(ModelProvider::close)
    }

    suspend fun inspect(): List<ModelEndpointInspection> = activeProviders
        .filterIsInstance<CatalogModelProvider>()
        .distinctBy { it.bindingProfile().provider }
        .map { it.inspect() }

    private fun build(catalog: ModelProviderCatalog): List<ModelProvider> {
        val endpoints = catalog.endpoints.filter { it.enabled }.associateBy { it.endpointId }
        return catalog.bindings.mapNotNull { binding ->
            endpoints[binding.endpointId]?.let { CatalogModelProvider(it, binding, catalog.policy, credentialResolver) }
        }
    }

    override suspend fun triage(prompt: String): String = primary().triage(prompt)

    override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String =
        primary().plan(prompt, actionType, entityType, workspace)

    override suspend fun proposeWorkDefinition(prompt: String): String = primary().proposeWorkDefinition(prompt)

    override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): ModelGeneration =
        primary().executeWorkDefinition(prompt, maxOutputTokens)

    override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        primary().executeWorkDefinition(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeCircuitSynthesis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        primary().executeCircuitSynthesis(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeRepositoryAnalysis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        primary().executeRepositoryAnalysis(prompt, maxOutputTokens, contextWindowTokens)

    override suspend fun executeCodingPatch(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration =
        primary().executeCodingPatch(prompt, maxOutputTokens, contextWindowTokens)

    override fun modelIdentity(): String = primary().modelIdentity()

    override fun bindingProfile(): ModelBindingProfile = primary().bindingProfile()

    override fun resourceDemand(profile: ModelExecutionProfile): ModelResourceDemand = primary().resourceDemand(profile)

    override fun resourceDemand(profile: ModelExecutionProfile, inputTokens: Int): ModelResourceDemand =
        primary().resourceDemand(profile, inputTokens)

    override fun architectResourceDemand(): ModelResourceDemand = primary().architectResourceDemand()

    @Synchronized
    private fun primary(): ModelProvider = activeProviders.firstOrNull() ?: error("No enabled model provider binding is configured")

    @Synchronized
    override fun close() = activeProviders.forEach(ModelProvider::close)
}

@Serializable
private data class OllamaCatalogRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val format: String = "json",
    val think: Boolean = false,
    val options: OllamaCatalogOptions,
)

@Serializable
private data class OllamaCatalogPlainRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val think: Boolean = false,
    val options: OllamaCatalogOptions,
)

@Serializable
private data class OllamaCatalogOptions(
    val temperature: Double,
    val seed: Int,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("num_ctx") val numContext: Int,
    @SerialName("num_thread") val numThread: Int,
)

@Serializable
private data class OllamaCatalogResponse(
    val response: String,
    val thinking: String? = null,
    val done: Boolean? = null,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
)

@Serializable
private data class OllamaAttemptDiagnostic(
    val endpointId: String,
    val model: String,
    val httpStatus: Int,
    val responseBodyBytes: Int,
    val responseLength: Int,
    val thinkingLength: Int,
    val done: Boolean? = null,
    val doneReason: String? = null,
    val promptEvalCount: Int? = null,
    val evalCount: Int? = null,
    val numContext: Int,
    val numPredict: Int? = null,
    val formatPresent: Boolean,
    val think: Boolean,
)

@Serializable
private data class OllamaModelsResponse(val models: List<OllamaModel>)

@Serializable
private data class OllamaModel(val name: String)

@Serializable
private data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormat = OpenAiResponseFormat(),
)

@Serializable
private data class OpenAiResponseFormat(val type: String = "json_object")

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiChatResponse(
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null,
)

@Serializable
private data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
private data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
)

@Serializable
private data class OpenAiModelsResponse(val data: List<OpenAiModel>)

@Serializable
private data class OpenAiModel(val id: String)