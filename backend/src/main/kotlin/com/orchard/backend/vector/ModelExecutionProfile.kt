package com.orchard.backend.vector

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val MODEL_CAPABILITY_STRICT_JSON = "STRICT_JSON"

data class RepositoryAnalysisOutputDomain(
    val sourcePaths: Set<String>,
    val evidencePaths: Set<String>,
) {
    init {
        require(evidencePaths.containsAll(sourcePaths)) { "Analysis source choices must be supplied evidence" }
    }
}

enum class ModelOutputContract {
    JSON_OBJECT,
    REPOSITORY_ANALYSIS_CANDIDATE,
    BOUNDED_CODING_TOOL_BATCH,
    BOUNDED_LITERAL_REPLACEMENTS;

    val versionedId: String
        get() = when (this) {
            JSON_OBJECT -> "json-object-v1"
            REPOSITORY_ANALYSIS_CANDIDATE -> "repository-analysis-candidate-v3"
            BOUNDED_CODING_TOOL_BATCH -> "bounded-coding-tool-batch-v1"
            BOUNDED_LITERAL_REPLACEMENTS -> "bounded-literal-replacements-v1"
        }

    val arrayLimits: Map<String, Int>
        get() = when (this) {
            REPOSITORY_ANALYSIS_CANDIDATE -> mapOf("evidence" to 3, "reuse" to 2, "preservedInvariants" to 2, "nonGoals" to 2, "sourcePaths" to 12)
            BOUNDED_CODING_TOOL_BATCH, BOUNDED_LITERAL_REPLACEMENTS -> mapOf("operations" to 12)
            JSON_OBJECT -> emptyMap()
        }

    val instruction: String
        get() = instructionFor(null)

    fun instructionFor(outputDomain: RepositoryAnalysisOutputDomain?): String =
        "Output contract $versionedId. Maximum array lengths: ${arrayLimits.entries.joinToString { "${it.key}=${it.value}" }}. " +
            when (this) {
                REPOSITORY_ANALYSIS_CANDIDATE -> "Return JSON matching ${schemaFor(outputDomain)}. Each evidence contentHash must be copied from that path's pinned repository source hash, not its excerpt hash. Admitted ownership and action limits still apply."
                BOUNDED_LITERAL_REPLACEMENTS -> "Every operation must use REPLACE_LITERAL. Admitted ownership and action limits still apply."
                else -> "Admitted ownership and action limits still apply."
            }

    internal val structuredSchema: JsonObject?
        get() = schemaFor(null)

    internal fun schemaFor(outputDomain: RepositoryAnalysisOutputDomain?): JsonObject? {
        if (this != REPOSITORY_ANALYSIS_CANDIDATE) return null
        val schema = serializedOutputSchema(com.orchard.backend.analysis.RepositoryAnalysisCandidate.serializer().descriptor, arrayLimits)
        if (outputDomain == null) return schema
        val properties = schema.getValue("properties").jsonObject.toMutableMap()
        val sources = properties.getValue("sourcePaths").jsonObject
        properties["sourcePaths"] = if (outputDomain.sourcePaths.isEmpty()) JsonObject(sources + ("maxItems" to JsonPrimitive(0))) else {
            JsonObject(sources + ("items" to JsonObject(sources.getValue("items").jsonObject + ("enum" to JsonArray(outputDomain.sourcePaths.sorted().map(::JsonPrimitive))))))
        }
        val evidence = properties.getValue("evidence").jsonObject
        properties["evidence"] = if (outputDomain.evidencePaths.isEmpty()) JsonObject(evidence + ("maxItems" to JsonPrimitive(0))) else {
            val citation = evidence.getValue("items").jsonObject
            val fields = citation.getValue("properties").jsonObject
            val path = JsonObject(fields.getValue("path").jsonObject + ("enum" to JsonArray(outputDomain.evidencePaths.sorted().map(::JsonPrimitive))))
            JsonObject(evidence + ("items" to JsonObject(citation + ("properties" to JsonObject(fields + ("path" to path))))))
        }
        return JsonObject(schema + ("properties" to JsonObject(properties)))
    }

    fun diagnostic(output: JsonObject): String? {
        for ((field, limit) in arrayLimits) {
            val values = output[field] as? JsonArray ?: continue
            if (values.size > limit) return "$versionedId: $field has ${values.size} items; at most $limit are allowed."
        }
        if (this == BOUNDED_LITERAL_REPLACEMENTS && (output["operations"] as? JsonArray).orEmpty().any {
                (it as? JsonObject)?.get("action")?.jsonPrimitive?.content != "REPLACE_LITERAL"
            }) return "$versionedId requires REPLACE_LITERAL operations."
        return null
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun serializedOutputSchema(descriptor: SerialDescriptor, arrayLimits: Map<String, Int> = emptyMap()): JsonObject = buildJsonObject {
    val type = when (descriptor.kind) {
        PrimitiveKind.STRING -> "string"
        StructureKind.CLASS -> "object"
        StructureKind.LIST -> "array"
        else -> error("Unsupported output contract descriptor: ${descriptor.serialName}")
    }
    put("type", if (descriptor.isNullable) JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null"))) else JsonPrimitive(type))
    when (descriptor.kind) {
        StructureKind.CLASS -> {
            put("additionalProperties", false)
            put("required", buildJsonArray {
                for (index in 0 until descriptor.elementsCount) {
                    if (!descriptor.isElementOptional(index)) add(JsonPrimitive(descriptor.getElementName(index)))
                }
            })
            put("properties", buildJsonObject {
                for (index in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(index)
                    val property = serializedOutputSchema(descriptor.getElementDescriptor(index))
                    put(name, arrayLimits[name]?.let { JsonObject(property + ("maxItems" to JsonPrimitive(it))) } ?: property)
                }
            })
        }
        StructureKind.LIST -> put("items", serializedOutputSchema(descriptor.getElementDescriptor(0)))
        else -> Unit
    }
}

@Serializable
data class ModelExecutionProfile(
    val id: String,
    val version: Int,
    val reasoningClass: String,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val requiredCapabilities: Set<String>,
)

@Serializable
data class ModelBindingProfile(
    val bindingId: String,
    val provider: String,
    val model: String,
    val contextWindowTokens: Int,
    val capabilities: Set<String>,
    val configuration: Map<String, String> = emptyMap(),
    val modelDigest: String? = null,
)

data class ModelGeneration(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

data class ModelBindingEvidence(
    val bindingFingerprint: String,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val sampleCount: Int,
    val schemaValidityRate: Double,
    val acceptedUnchangedCount: Int,
    val acceptedAfterEditCount: Int,
    val revisionRequestedCount: Int,
    val medianLatencyMillis: Long,
) {
    val satisfactionCount: Int
        get() = acceptedUnchangedCount + acceptedAfterEditCount + revisionRequestedCount
    val acceptanceRate: Double
        get() = if (satisfactionCount == 0) 0.0
        else (acceptedUnchangedCount + acceptedAfterEditCount).toDouble() / satisfactionCount
    val unchangedAcceptanceRate: Double
        get() = if (satisfactionCount == 0) 0.0 else acceptedUnchangedCount.toDouble() / satisfactionCount
}

object DefaultModelExecutionProfiles {
    val boundedConversationConductor = ModelExecutionProfile(
        id = "bounded-conversation-conductor-v1",
        version = 1,
        reasoningClass = "BOUNDED_CONVERSATION_CONDUCTOR",
        inputBudgetTokens = 48_000,
        outputBudgetTokens = 4_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    val boundedDefinitionReasoning = ModelExecutionProfile(
        id = "bounded-definition-reasoning-v1",
        version = 1,
        reasoningClass = "BOUNDED_DEFINITION",
        inputBudgetTokens = 12_000,
        outputBudgetTokens = 2_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    val boundedCircuitSynthesis = ModelExecutionProfile(
        id = "bounded-circuit-synthesis-v1",
        version = 1,
        reasoningClass = "BOUNDED_CIRCUIT_SYNTHESIS",
        inputBudgetTokens = 12_000,
        outputBudgetTokens = 3_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    val boundedCodingPatch = ModelExecutionProfile(
        id = "bounded-coding-patch-v1",
        version = 1,
        reasoningClass = "BOUNDED_CODING_PATCH",
        inputBudgetTokens = 24_000,
        outputBudgetTokens = 8_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    val broadRepositoryAnalysis = ModelExecutionProfile(
        id = "broad-repository-analysis-v1",
        version = 1,
        reasoningClass = "BROAD_REPOSITORY_ANALYSIS",
        inputBudgetTokens = 88_000,
        outputBudgetTokens = 8_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    val boundedIndependentAudit = ModelExecutionProfile(
        id = "bounded-independent-audit-v1",
        version = 1,
        reasoningClass = "BOUNDED_INDEPENDENT_AUDIT",
        inputBudgetTokens = 64_000,
        outputBudgetTokens = 4_000,
        requiredCapabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
    )

    fun resolve(id: String): ModelExecutionProfile = when (id) {
        boundedConversationConductor.id -> boundedConversationConductor
        boundedDefinitionReasoning.id -> boundedDefinitionReasoning
        boundedCircuitSynthesis.id -> boundedCircuitSynthesis
        boundedCodingPatch.id -> boundedCodingPatch
        broadRepositoryAnalysis.id -> broadRepositoryAnalysis
        boundedIndependentAudit.id -> boundedIndependentAudit
        else -> throw IllegalArgumentException("Unknown model execution profile $id")
    }

    fun all(): List<ModelExecutionProfile> = listOf(
        boundedConversationConductor,
        boundedDefinitionReasoning,
        boundedCircuitSynthesis,
        broadRepositoryAnalysis,
        boundedCodingPatch,
        boundedIndependentAudit,
    )
}

fun effectiveModelExecutionProfile(
    defaultProfile: ModelExecutionProfile,
    override: ModelProfileOverride?,
): ModelExecutionProfile = if (override == null) defaultProfile else defaultProfile.copy(
    inputBudgetTokens = override.inputBudgetTokens,
    outputBudgetTokens = override.outputBudgetTokens,
)

object ModelProfileResolver {
    fun resolve(
        profile: ModelExecutionProfile,
        providers: List<ModelProvider>,
        evidence: List<ModelBindingEvidence> = emptyList(),
    ): ModelProvider {
        val compatible = providers
            .filter { provider ->
                val binding = provider.bindingProfile()
                binding.contextWindowTokens >= profile.inputBudgetTokens + profile.outputBudgetTokens &&
                    binding.capabilities.containsAll(profile.requiredCapabilities)
            }
                    .let(::applyProviderPolicy)
        if (compatible.isEmpty()) throw IllegalStateException("No installed model satisfies execution profile ${profile.id}")
        val proven = compatible.mapNotNull { provider ->
            evidence.singleOrNull {
                it.bindingFingerprint == modelBindingFingerprint(provider.bindingProfile()) &&
                    it.inputBudgetTokens == profile.inputBudgetTokens &&
                    it.outputBudgetTokens == profile.outputBudgetTokens &&
                    it.sampleCount >= MIN_ROUTING_SAMPLES &&
                    it.schemaValidityRate >= MIN_SCHEMA_VALIDITY
            }?.let { provider to it }
        }
        return proven.maxWithOrNull(
            compareBy<Pair<ModelProvider, ModelBindingEvidence>> { it.second.schemaValidityRate }
                .thenBy { it.second.acceptanceRate }
                .thenBy { it.second.unchangedAcceptanceRate }
                .thenByDescending { it.second.medianLatencyMillis }
        )?.first ?: compatible.minBy { it.bindingProfile().contextWindowTokens }
    }

    private const val MIN_ROUTING_SAMPLES = 3
    private const val MIN_SCHEMA_VALIDITY = 0.8

    private fun applyProviderPolicy(providers: List<ModelProvider>): List<ModelProvider> {
        val local = providers.filter { it.bindingProfile().configuration["locality"] == PROVIDER_LOCALITY_LOCAL }
        val requiresLocalPreference = providers.any {
            it.bindingProfile().configuration["providerPolicy"] in setOf(
                PROVIDER_POLICY_LOCAL_PREFERRED,
                PROVIDER_POLICY_CLOUD_ESCALATION_ONLY,
            )
        }
        return if (requiresLocalPreference && local.isNotEmpty()) local else providers
    }
}

fun estimateModelTokens(value: String): Int = value.encodeToByteArray().size

const val MODEL_TOKEN_COUNT_TOKENIZER = "TOKENIZER_WITH_OVERHEAD_RESERVE"
const val MODEL_TOKEN_COUNT_BYTE_FALLBACK = "UTF8_BYTE_UPPER_BOUND"
const val MODEL_PROVIDER_OVERHEAD_RESERVE_TOKENS = 0
const val MODEL_TOKEN_COUNT_UNVERIFIED_FORMAT = "UNVERIFIED_PROVIDER_TEMPLATE"
const val MODEL_INPUT_FORMAT_GPT_OSS_HARMONY = "gpt-oss-harmony-v1"
const val MODEL_TOKEN_COUNT_FORMATTED_UPPER_BOUND = "FORMATTED_INPUT_TOKEN_UPPER_BOUND"
private const val GPT_OSS_OPTIONAL_BOUNDARY_TOKENS = 2

internal data class FormattedModelInput(val prompt: String, val providerFormat: String)

internal fun formatModelInput(value: String, binding: ModelBindingProfile): FormattedModelInput? {
    val protocol = binding.configuration["protocol"]
    if (protocol == null || protocol == "OLLAMA_NATIVE" && binding.configuration["input.format"] == "raw") {
        return FormattedModelInput(value, "raw-input-v1")
    }
    if (protocol != "OLLAMA_NATIVE" || binding.configuration["input.format"] != MODEL_INPUT_FORMAT_GPT_OSS_HARMONY ||
        binding.model !in setOf("gpt-oss:120b", "gpt-oss:20b")) return null
    val date = binding.configuration["input.format.date"]?.let {
        runCatching { java.time.LocalDate.parse(it).toString() }.getOrNull()
    } ?: return null
    return FormattedModelInput(
        "<|start|>system<|message|>You are ChatGPT, a large language model trained by OpenAI.\n" +
            "Knowledge cutoff: 2024-06\nCurrent date: $date\n\nReasoning: low\n\n" +
            "# Valid channels: analysis, commentary, final. Channel must be included for every message.<|end|>" +
            "<|start|>user<|message|>$value<|end|><|start|>assistant",
        MODEL_INPUT_FORMAT_GPT_OSS_HARMONY,
    )
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ModelTokenAccounting(
    val contentBytes: Int,
    val contentTokens: Int,
    val providerOverheadTokens: Int,
    val totalTokens: Int,
    val method: String,
    val tokenizerId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val providerFormat: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capacityEstablished: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val serializedInputBytes: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val serializedInputHash: String? = null,
)

fun accountModelInput(value: String, binding: ModelBindingProfile): ModelTokenAccounting {
    val content = accountModelText(value, binding, 0)
    val formatted = formatModelInput(value, binding)
    if (formatted?.providerFormat == MODEL_INPUT_FORMAT_GPT_OSS_HARMONY) {
        val rendered = accountModelText(formatted.prompt, binding, 0)
        val bytes = formatted.prompt.encodeToByteArray().size
        val total = if (rendered.method == MODEL_TOKEN_COUNT_BYTE_FALLBACK) bytes else rendered.totalTokens
        val boundedTotal = (maxOf(total, content.totalTokens).toLong() + GPT_OSS_OPTIONAL_BOUNDARY_TOKENS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return content.copy(
            providerOverheadTokens = boundedTotal - content.totalTokens,
            totalTokens = boundedTotal,
            method = if (rendered.method == MODEL_TOKEN_COUNT_BYTE_FALLBACK) MODEL_TOKEN_COUNT_BYTE_FALLBACK else MODEL_TOKEN_COUNT_FORMATTED_UPPER_BOUND,
            providerFormat = formatted.providerFormat,
            capacityEstablished = true,
            serializedInputBytes = bytes,
            serializedInputHash = com.orchard.backend.workspace.stagedPlanHash(formatted.prompt),
        )
    }
    return content.copy(
        method = if (formatted != null) content.method else MODEL_TOKEN_COUNT_UNVERIFIED_FORMAT,
        providerFormat = formatted?.providerFormat ?: "unverified-provider-template-v1",
        capacityEstablished = formatted != null,
        serializedInputBytes = formatted?.prompt?.encodeToByteArray()?.size,
        serializedInputHash = formatted?.prompt?.let { com.orchard.backend.workspace.stagedPlanHash(it) },
    )
}

fun accountModelOutput(value: String, binding: ModelBindingProfile): ModelTokenAccounting =
    accountModelText(value, binding, 0)

private fun accountModelText(value: String, binding: ModelBindingProfile, overheadTokens: Int): ModelTokenAccounting {
    val declaredEncoding = if (binding.configuration["tokenizer.model"]?.let { it != binding.model } == true) null else binding.configuration["tokenizer.encoding"] ?: when {
        binding.model.startsWith("gpt-oss") || binding.model.startsWith("gpt-4o") || binding.model.startsWith("gpt-4.1") -> "o200k_base"
        binding.model.startsWith("gpt-3.5-turbo") || binding.model.startsWith("gpt-4-turbo") -> "cl100k_base"
        else -> null
    }
    val encodingType = when (declaredEncoding) {
        "o200k_base" -> EncodingType.O200K_BASE
        "cl100k_base" -> EncodingType.CL100K_BASE
        else -> null
    }
    val bytes = value.encodeToByteArray().size
    val tokens = encodingType?.let { modelEncodingRegistry.getEncoding(it).countTokensOrdinary(value) } ?: bytes
    return ModelTokenAccounting(
        contentBytes = bytes,
        contentTokens = tokens,
        providerOverheadTokens = overheadTokens,
        totalTokens = (tokens.toLong() + overheadTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        method = if (encodingType == null) MODEL_TOKEN_COUNT_BYTE_FALLBACK else MODEL_TOKEN_COUNT_TOKENIZER,
        tokenizerId = encodingType?.let { "jtokkit:1.1.0:$declaredEncoding" },
    )
}

private val modelEncodingRegistry by lazy { Encodings.newDefaultEncodingRegistry() }

fun modelBindingFingerprint(binding: ModelBindingProfile): String {
    val canonical = buildString {
        append(binding.bindingId).append('\n')
        append(binding.provider).append('\n')
        append(binding.model).append('\n')
        append(binding.modelDigest.orEmpty()).append('\n')
        append(binding.contextWindowTokens).append('\n')
        binding.capabilities.sorted().forEach { append(it).append('\n') }
        binding.configuration.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}