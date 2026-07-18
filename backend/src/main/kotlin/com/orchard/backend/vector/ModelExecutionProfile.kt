package com.orchard.backend.vector

import java.security.MessageDigest
import kotlinx.serialization.Serializable

const val MODEL_CAPABILITY_STRICT_JSON = "STRICT_JSON"

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