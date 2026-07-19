package com.orchard.backend.agent

import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelBindingEvidence
import com.orchard.backend.vector.ModelExecutionProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProfileConfiguration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileSettingsStore
import com.orchard.backend.vector.ModelProfileUpdateResult
import com.orchard.backend.vector.ModelProfileUpdateStatus
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.effectiveModelExecutionProfile
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.resource.MachineResourceConfiguration
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.resource.MachineUsagePolicyUpdateResult
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.workspace.COLLABORATOR_LOCAL_LLM
import com.orchard.backend.workspace.DefinitionCollaborationStatus
import com.orchard.backend.workspace.DefinitionExecutionProvenance
import com.orchard.backend.workspace.ConversationCommandReference
import com.orchard.backend.workspace.DefinitionProposalContent
import com.orchard.backend.workspace.MESSAGE_DEFINITION_COLLABORATION
import com.orchard.backend.workspace.DefinitionStepContext
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ProposalGenerationStatus {
    CREATED,
    WORK_ITEM_NOT_FOUND,
    WORKFLOW_ALREADY_STARTED,
    BUSY,
    MODEL_UNAVAILABLE,
    NO_COMPATIBLE_MODEL,
    CONTEXT_BUDGET_EXCEEDED,
    INVALID_OUTPUT,
    STORAGE_UNAVAILABLE,
    RESOURCE_CAPACITY_UNAVAILABLE,
    RESOURCE_TELEMETRY_UNAVAILABLE,
}

data class ProposalGenerationResult(
    val status: ProposalGenerationStatus,
    val snapshot: WorkspaceSnapshot,
)

class DefinitionIntelligenceService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
) {
    private val workItemMutexes = ConcurrentHashMap<Int, Mutex>()
    private val strictOutputJson = Json { encodeDefaults = true }

    constructor(
        workspace: WorkspaceStore,
        modelProvider: ModelProvider,
        json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        systemPrompt: String = loadPrompt(),
    ) : this(
        workspace,
        listOf(modelProvider),
        TransientModelProfileSettingsStore(),
        MachineResourceController.unrestricted(),
        json,
        systemPrompt,
    )

    fun machineResourceConfiguration(): MachineResourceConfiguration = resourceController.configuration()

    fun updateMachineUsagePolicy(policy: MachineUsagePolicy): MachineUsagePolicyUpdateResult =
        resourceController.updatePolicy(policy)

    fun profileConfigurations(): List<ModelProfileConfiguration> {
        val overrides = profileSettingsStore.load().associateBy { it.profileId }
        val bindings = modelProviders.map(ModelProvider::bindingProfile).sortedBy { it.bindingId }
        return DefaultModelExecutionProfiles.all().map { defaultProfile ->
            val override = overrides[defaultProfile.id]
            val effective = effectiveModelExecutionProfile(defaultProfile, override)
            ModelProfileConfiguration(
                defaultProfile = defaultProfile,
                effectiveProfile = effective,
                override = override,
                installedBindings = bindings,
                compatibleBindingIds = compatibleProviders(effective).map { it.bindingProfile().bindingId }.sorted(),
            )
        }
    }

    @Synchronized
    fun updateProfile(override: ModelProfileOverride): ModelProfileUpdateResult {
        val currentConfigurations = runCatching { profileConfigurations() }.getOrElse {
            return ModelProfileUpdateResult(ModelProfileUpdateStatus.STORAGE_UNAVAILABLE, emptyList())
        }
        val defaultProfile = runCatching { DefaultModelExecutionProfiles.resolve(override.profileId) }.getOrNull()
            ?: return ModelProfileUpdateResult(ModelProfileUpdateStatus.PROFILE_NOT_FOUND, currentConfigurations)
        if (
            override.inputBudgetTokens !in MIN_INPUT_TOKENS..MAX_PROFILE_TOKENS ||
            override.outputBudgetTokens !in MIN_OUTPUT_TOKENS..MAX_PROFILE_TOKENS
        ) return ModelProfileUpdateResult(ModelProfileUpdateStatus.INVALID_BUDGET, currentConfigurations)
        val effective = effectiveModelExecutionProfile(defaultProfile, override)
        val compatible = compatibleProviders(effective)
        if (compatible.isEmpty() || (
                override.preferredBindingId != null &&
                    compatible.none { it.bindingProfile().bindingId == override.preferredBindingId }
                )
        ) return ModelProfileUpdateResult(ModelProfileUpdateStatus.NO_COMPATIBLE_BINDING, currentConfigurations)
        return try {
            val existing = profileSettingsStore.load().filterNot { it.profileId == override.profileId }
            profileSettingsStore.save(existing + override)
            val updatedConfigurations = runCatching { profileConfigurations() }.getOrElse {
                return ModelProfileUpdateResult(ModelProfileUpdateStatus.STORAGE_UNAVAILABLE, currentConfigurations)
            }
            ModelProfileUpdateResult(ModelProfileUpdateStatus.UPDATED, updatedConfigurations)
        } catch (_: Exception) {
            ModelProfileUpdateResult(ModelProfileUpdateStatus.STORAGE_UNAVAILABLE, currentConfigurations)
        }
    }

    @Synchronized
    fun replaceProfiles(overrides: List<ModelProfileOverride>): ModelProfileUpdateResult {
        val currentConfigurations = runCatching { profileConfigurations() }.getOrElse {
            return ModelProfileUpdateResult(ModelProfileUpdateStatus.STORAGE_UNAVAILABLE, emptyList())
        }
        val defaults = DefaultModelExecutionProfiles.all().associateBy { it.id }
        if (overrides.size != defaults.size || overrides.map { it.profileId }.toSet() != defaults.keys) {
            return ModelProfileUpdateResult(ModelProfileUpdateStatus.PROFILE_NOT_FOUND, currentConfigurations)
        }
        val valid = overrides.all { override ->
            override.inputBudgetTokens in MIN_INPUT_TOKENS..MAX_PROFILE_TOKENS &&
                override.outputBudgetTokens in MIN_OUTPUT_TOKENS..MAX_PROFILE_TOKENS &&
                compatibleProviders(effectiveModelExecutionProfile(defaults.getValue(override.profileId), override)).let { providers ->
                    providers.isNotEmpty() && (
                        override.preferredBindingId == null ||
                            providers.any { it.bindingProfile().bindingId == override.preferredBindingId }
                        )
                }
        }
        if (!valid) return ModelProfileUpdateResult(ModelProfileUpdateStatus.NO_COMPATIBLE_BINDING, currentConfigurations)
        return try {
            profileSettingsStore.save(overrides)
            ModelProfileUpdateResult(ModelProfileUpdateStatus.UPDATED, profileConfigurations())
        } catch (_: Exception) {
            ModelProfileUpdateResult(ModelProfileUpdateStatus.STORAGE_UNAVAILABLE, currentConfigurations)
        }
    }

    suspend fun propose(workItemId: Int, conversationCommand: ConversationCommandReference? = null): ProposalGenerationResult {
        val mutex = workItemMutexes.computeIfAbsent(workItemId) { Mutex() }
        if (!mutex.tryLock()) return result(ProposalGenerationStatus.BUSY)
        return try {
            generate(workItemId, conversationCommand)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun generate(workItemId: Int, conversationCommand: ConversationCommandReference?): ProposalGenerationResult {
        val context = workspace.definitionStepContext(workItemId)
            ?: return result(ProposalGenerationStatus.WORK_ITEM_NOT_FOUND)
        val step = context.systemWorkflow.stepDefinitions.single()
        val defaultProfile = runCatching {
            DefaultModelExecutionProfiles.resolve(requireNotNull(step.modelExecutionProfileId))
        }.getOrElse { return result(ProposalGenerationStatus.NO_COMPATIBLE_MODEL) }
        val override = runCatching { profileSettingsStore.load() }.getOrElse {
            return result(ProposalGenerationStatus.STORAGE_UNAVAILABLE)
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, override)
        val routingEvidence = workspace.modelProfiles().filter {
            it.executionProfileId == profile.id &&
                it.executionProfileVersion == profile.version &&
                it.inputBudgetTokens == profile.inputBudgetTokens &&
                it.outputBudgetTokens == profile.outputBudgetTokens
        }.map {
            ModelBindingEvidence(
                bindingFingerprint = modelBindingFingerprint(it.binding),
                inputBudgetTokens = it.inputBudgetTokens,
                outputBudgetTokens = it.outputBudgetTokens,
                sampleCount = it.sampleCount,
                schemaValidityRate = it.schemaValidityRate,
                acceptedUnchangedCount = it.acceptedUnchangedCount,
                acceptedAfterEditCount = it.acceptedAfterEditCount,
                revisionRequestedCount = it.revisionRequestedCount,
                medianLatencyMillis = it.medianLatencyMillis,
            )
        }
        val eligibleProviders = override?.preferredBindingId?.let { preferred ->
            modelProviders.filter { it.bindingProfile().bindingId == preferred }
        } ?: modelProviders
        val modelProvider = runCatching { ModelProfileResolver.resolve(profile, eligibleProviders, routingEvidence) }
            .getOrElse { return result(ProposalGenerationStatus.NO_COMPATIBLE_MODEL) }
        val binding = modelProvider.bindingProfile()
        val envelope = DefinitionWorkflowStepEnvelope(
            profile = profile,
            workflowStepId = step.id,
            workflowStepVersion = step.version,
            allowedActions = listOf("PROPOSE_DEFINITION", "REVISE_DEFINITION"),
            forbiddenActions = listOf("ACCEPT_DEFINITION", "SIGNAL_READY", "CHANGE_SCOPE_SILENTLY"),
            requiredOutputSchema = "work-definition-proposal-v1",
            context = context,
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative workflow step envelope:\n$envelopeJson"
        val estimatedInputTokens = estimateModelTokens(prompt)
        if (estimatedInputTokens > profile.inputBudgetTokens) {
            return result(ProposalGenerationStatus.CONTEXT_BUDGET_EXCEEDED)
        }
        val admission = resourceController.tryAcquire(modelProvider.resourceDemand(profile))
        val lease = admission.lease ?: return result(
            if (admission.evidence.decision == ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE) {
                ProposalGenerationStatus.RESOURCE_TELEMETRY_UNAVAILABLE
            } else {
                ProposalGenerationStatus.RESOURCE_CAPACITY_UNAVAILABLE
            }
        )
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use {
                modelProvider.executeWorkDefinition(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            recordExecution(
                profile,
                binding,
                step.id,
                workItemId,
                envelopeJson,
                prompt,
                generation = null,
                latencyMillis = elapsedMillis(startedAt),
                schemaValid = false,
                resourceAdmission = admission.evidence,
            )
            throw exception
        } catch (_: Exception) {
            val recorded = recordExecution(
                profile,
                binding,
                step.id,
                workItemId,
                envelopeJson,
                prompt,
                generation = null,
                latencyMillis = elapsedMillis(startedAt),
                schemaValid = false,
                resourceAdmission = admission.evidence,
            ) ?: return result(ProposalGenerationStatus.STORAGE_UNAVAILABLE)
            check(recorded.outputHash == null)
            return result(ProposalGenerationStatus.MODEL_UNAVAILABLE)
        }
        val outputWithinBudget = generation.promptTokens <= profile.inputBudgetTokens &&
            generation.completionTokens <= profile.outputBudgetTokens &&
            estimateModelTokens(generation.text) <= profile.outputBudgetTokens
        val output = if (outputWithinBudget) {
            runCatching { strictOutputJson.decodeFromString<DefinitionProposalOutput>(generation.text) }.getOrNull()
        } else null
        val execution = recordExecution(
            profile,
            binding,
            step.id,
            workItemId,
            envelopeJson,
            prompt,
            generation,
            elapsedMillis(startedAt),
            output != null,
            admission.evidence,
        ) ?: return result(ProposalGenerationStatus.STORAGE_UNAVAILABLE)
        if (output == null) return result(ProposalGenerationStatus.INVALID_OUTPUT)
        val recorded = workspace.recordDefinitionProposal(
            workItemId = workItemId,
            actor = COLLABORATOR_LOCAL_LLM,
            content = DefinitionProposalContent(output.definition, output.observations, output.assumptions),
            provenance = DefinitionExecutionProvenance(
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
            conversationCommand = conversationCommand,
        )
        return when (recorded.status) {
            DefinitionCollaborationStatus.RECORDED -> ProposalGenerationResult(
                ProposalGenerationStatus.CREATED,
                recorded.snapshot,
            )
            DefinitionCollaborationStatus.WORKFLOW_ALREADY_STARTED -> result(ProposalGenerationStatus.WORKFLOW_ALREADY_STARTED)
            DefinitionCollaborationStatus.WORK_ITEM_NOT_FOUND -> result(ProposalGenerationStatus.WORK_ITEM_NOT_FOUND)
            DefinitionCollaborationStatus.STORAGE_UNAVAILABLE -> result(ProposalGenerationStatus.STORAGE_UNAVAILABLE)
            DefinitionCollaborationStatus.PROPOSAL_NOT_FOUND,
            DefinitionCollaborationStatus.INVALID_RECORD -> result(ProposalGenerationStatus.INVALID_OUTPUT)
        }
    }

    private fun result(status: ProposalGenerationStatus): ProposalGenerationResult =
        ProposalGenerationResult(status, workspace.snapshot(MESSAGE_DEFINITION_COLLABORATION))

    private fun recordExecution(
        profile: ModelExecutionProfile,
        binding: ModelBindingProfile,
        workflowStepId: String,
        workItemId: Int,
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
            workflowStepId = workflowStepId,
            workItemId = workItemId,
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

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun compatibleProviders(profile: ModelExecutionProfile): List<ModelProvider> = modelProviders.filter { provider ->
        val binding = provider.bindingProfile()
        binding.contextWindowTokens.toLong() >=
            profile.inputBudgetTokens.toLong() + profile.outputBudgetTokens.toLong() &&
            binding.capabilities.containsAll(profile.requiredCapabilities)
    }

    private companion object {
        const val PROMPT_VERSION = 1
        const val MIN_INPUT_TOKENS = 1_024
        const val MIN_OUTPUT_TOKENS = 256
        const val MAX_PROFILE_TOKENS = 1_000_000

        fun loadPrompt(): String {
            val stream = requireNotNull(
                DefinitionIntelligenceService::class.java.getResourceAsStream(
                    "/default-system-prompts/work_definition_agent.md"
                )
            ) { "Missing Work Definition agent prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

@Serializable
private data class DefinitionWorkflowStepEnvelope(
    val envelopeVersion: Int = 1,
    val profile: ModelExecutionProfile,
    val workflowStepId: String,
    val workflowStepVersion: Int,
    val allowedActions: List<String>,
    val forbiddenActions: List<String>,
    val requiredOutputSchema: String,
    val context: DefinitionStepContext,
)

@Serializable
private data class DefinitionProposalOutput(
    val definition: com.orchard.backend.workspace.WorkDefinitionSubmission,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }