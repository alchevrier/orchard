package com.orchard.backend.agent

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingEvidence
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelExecutionProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProfileSettingsStore
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.effectiveModelExecutionProfile
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.CircuitExecutionProvenance
import com.orchard.backend.workspace.ConversationCommandReference
import com.orchard.backend.workspace.CircuitProposalContent
import com.orchard.backend.workspace.CircuitProposalStatus
import com.orchard.backend.workspace.CircuitSynthesisContext
import com.orchard.backend.workspace.MESSAGE_STAGED_DELIVERY_PLAN
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CircuitGenerationStatus {
    CREATED,
    SCOPE_NOT_FOUND,
    INVALID_SCOPE,
    PLAN_LOCKED,
    BUSY,
    NO_COMPATIBLE_MODEL,
    CONTEXT_BUDGET_EXCEEDED,
    INVALID_OUTPUT,
    STALE_CONTEXT,
    MODEL_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    RESOURCE_CAPACITY_UNAVAILABLE,
    RESOURCE_TELEMETRY_UNAVAILABLE,
}

data class CircuitGenerationResult(
    val status: CircuitGenerationStatus,
    val snapshot: WorkspaceSnapshot,
)

class CircuitIntelligenceService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
) {
    private val scopeMutexes = ConcurrentHashMap<Int, Mutex>()
    private val strictOutputJson = Json { encodeDefaults = true }

    constructor(
        workspace: WorkspaceStore,
        modelProvider: ModelProvider,
        systemPrompt: String = loadPrompt(),
    ) : this(workspace, listOf(modelProvider), systemPrompt = systemPrompt)

    suspend fun propose(scopeId: Int, conversationCommand: ConversationCommandReference? = null): CircuitGenerationResult {
        val mutex = scopeMutexes.computeIfAbsent(scopeId) { Mutex() }
        if (!mutex.tryLock()) return result(CircuitGenerationStatus.BUSY)
        return try {
            generate(scopeId, conversationCommand)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun generate(scopeId: Int, conversationCommand: ConversationCommandReference?): CircuitGenerationResult {
        val context = workspace.circuitSynthesisContext(scopeId)
            ?: return result(CircuitGenerationStatus.SCOPE_NOT_FOUND)
        if (context.members.isEmpty()) return result(CircuitGenerationStatus.INVALID_SCOPE)
        if (context.planLocked) return result(CircuitGenerationStatus.PLAN_LOCKED)
        val defaultProfile = DefaultModelExecutionProfiles.boundedCircuitSynthesis
        val override = runCatching { profileSettingsStore.load() }.getOrElse {
            return result(CircuitGenerationStatus.STORAGE_UNAVAILABLE)
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, override)
        val provider = resolveProvider(profile, override)
            ?: return result(CircuitGenerationStatus.NO_COMPATIBLE_MODEL)
        val binding = provider.bindingProfile()
        val envelope = CircuitSynthesisEnvelope(
            profile = profile,
            allowedActions = listOf("PROPOSE_STAGES", "PROPOSE_DEPENDENCIES", "PROPOSE_ARTIFACT_SIGNALS"),
            forbiddenActions = listOf("ACCEPT_PLAN", "START_WORKFLOW", "CREATE_WORK_ITEM", "CHANGE_HIERARCHY"),
            context = context,
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative circuit synthesis envelope:\n$envelopeJson"
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return result(CircuitGenerationStatus.CONTEXT_BUDGET_EXCEEDED)
        }
        val admission = resourceController.tryAcquire(provider.resourceDemand(profile))
        val lease = admission.lease ?: return result(
            if (admission.evidence.decision == ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE) {
                CircuitGenerationStatus.RESOURCE_TELEMETRY_UNAVAILABLE
            } else {
                CircuitGenerationStatus.RESOURCE_CAPACITY_UNAVAILABLE
            }
        )
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use {
                provider.executeCircuitSynthesis(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            recordExecution(profile, binding, scopeId, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence)
            throw exception
        } catch (_: Exception) {
            recordExecution(profile, binding, scopeId, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence)
                ?: return result(CircuitGenerationStatus.STORAGE_UNAVAILABLE)
            return result(CircuitGenerationStatus.MODEL_UNAVAILABLE)
        }
        val output = if (
            generation.promptTokens <= profile.inputBudgetTokens &&
            generation.completionTokens <= profile.outputBudgetTokens &&
            estimateModelTokens(generation.text) <= profile.outputBudgetTokens
        ) {
            runCatching { strictOutputJson.decodeFromString<CircuitProposalOutput>(generation.text) }.getOrNull()
        } else null
        val execution = recordExecution(
            profile,
            binding,
            scopeId,
            envelopeJson,
            prompt,
            generation,
            elapsedMillis(startedAt),
            output != null,
            admission.evidence,
        ) ?: return result(CircuitGenerationStatus.STORAGE_UNAVAILABLE)
        if (output == null) return result(CircuitGenerationStatus.INVALID_OUTPUT)
        val submission = StagedDeliveryPlanSubmission(
            scopeId = scopeId,
            title = output.title,
            stages = output.stages,
            baseRevision = context.activePlan?.revision ?: 0,
            baseHash = context.activePlan?.hash,
        )
        val recorded = workspace.recordCircuitProposal(
            CircuitProposalContent(submission, output.observations, output.assumptions),
            CircuitExecutionProvenance(
                executor = "profile:${profile.id}",
                model = binding.model,
                executionProfileId = profile.id,
                bindingFingerprint = modelBindingFingerprint(binding),
                promptVersion = PROMPT_VERSION,
                promptHash = sha256(prompt),
                contextHash = sha256(envelopeJson),
                outputHash = sha256(generation.text),
                executionId = execution.executionId,
            ),
            expectedContext = context,
            conversationCommand = conversationCommand,
        )
        return when (recorded.status) {
            CircuitProposalStatus.RECORDED -> CircuitGenerationResult(CircuitGenerationStatus.CREATED, recorded.snapshot)
            CircuitProposalStatus.SCOPE_NOT_FOUND -> result(CircuitGenerationStatus.SCOPE_NOT_FOUND)
            CircuitProposalStatus.INVALID_SCOPE -> result(CircuitGenerationStatus.INVALID_SCOPE)
            CircuitProposalStatus.PLAN_LOCKED -> result(CircuitGenerationStatus.PLAN_LOCKED)
            CircuitProposalStatus.STALE_CONTEXT -> result(CircuitGenerationStatus.STALE_CONTEXT)
            CircuitProposalStatus.INVALID_PROPOSAL -> result(CircuitGenerationStatus.INVALID_OUTPUT)
            CircuitProposalStatus.STORAGE_UNAVAILABLE -> result(CircuitGenerationStatus.STORAGE_UNAVAILABLE)
            CircuitProposalStatus.PROPOSAL_NOT_FOUND -> result(CircuitGenerationStatus.INVALID_OUTPUT)
        }
    }

    private fun resolveProvider(profile: ModelExecutionProfile, override: ModelProfileOverride?): ModelProvider? {
        val evidence = workspace.modelProfiles().filter {
            it.executionProfileId == profile.id && it.executionProfileVersion == profile.version &&
                it.inputBudgetTokens == profile.inputBudgetTokens && it.outputBudgetTokens == profile.outputBudgetTokens
        }.map {
            ModelBindingEvidence(
                modelBindingFingerprint(it.binding),
                it.inputBudgetTokens,
                it.outputBudgetTokens,
                it.sampleCount,
                it.schemaValidityRate,
                it.acceptedUnchangedCount,
                it.acceptedAfterEditCount,
                it.revisionRequestedCount,
                it.medianLatencyMillis,
            )
        }
        val eligible = override?.preferredBindingId?.let { preferred ->
            modelProviders.filter { it.bindingProfile().bindingId == preferred }
        } ?: modelProviders
        return runCatching { ModelProfileResolver.resolve(profile, eligible, evidence) }.getOrNull()
    }

    private fun recordExecution(
        profile: ModelExecutionProfile,
        binding: ModelBindingProfile,
        scopeId: Int,
        envelopeJson: String,
        prompt: String,
        generation: ModelGeneration?,
        latencyMillis: Long,
        schemaValid: Boolean,
        resourceAdmission: com.orchard.backend.resource.ResourceAdmissionEvidence,
    ) = workspace.recordModelExecution(
        ModelExecutionObservationDraft(
            profile = profile,
            binding = binding,
            workflowStepId = "SYNTHESIZE_CIRCUIT",
            workItemId = scopeId,
            envelopeHash = sha256(envelopeJson),
            promptHash = sha256(prompt),
            outputHash = generation?.text?.let(::sha256),
            inputTokens = generation?.promptTokens ?: estimateModelTokens(prompt),
            outputTokens = generation?.completionTokens ?: 0,
            latencyMillis = latencyMillis,
            schemaValid = schemaValid,
            resourceAdmission = resourceAdmission,
        )
    )

    private fun result(status: CircuitGenerationStatus): CircuitGenerationResult =
        CircuitGenerationResult(status, workspace.snapshot(MESSAGE_STAGED_DELIVERY_PLAN))

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private companion object {
        const val PROMPT_VERSION = 1

        fun loadPrompt(): String {
            val stream = requireNotNull(
                CircuitIntelligenceService::class.java.getResourceAsStream(
                    "/default-system-prompts/circuit_synthesis_agent.md"
                )
            ) { "Missing circuit synthesis agent prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

@Serializable
private data class CircuitSynthesisEnvelope(
    val envelopeVersion: Int = 1,
    val profile: ModelExecutionProfile,
    val allowedActions: List<String>,
    val forbiddenActions: List<String>,
    val requiredOutputSchema: String = "staged-circuit-proposal-v1",
    val context: CircuitSynthesisContext,
)

@Serializable
private data class CircuitProposalOutput(
    val title: String,
    val stages: List<StagedPlanStageSubmission>,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
