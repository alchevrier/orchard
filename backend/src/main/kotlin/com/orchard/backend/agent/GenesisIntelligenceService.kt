package com.orchard.backend.agent

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.ProjectGenesisView
import com.orchard.backend.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class GenesisProposalStatus {
    CREATED,
    PROJECT_NOT_FOUND,
    PHASE_NOT_PROPOSABLE,
    BUSY,
    MODEL_UNAVAILABLE,
    INVALID_OUTPUT,
    CONTEXT_BUDGET_EXCEEDED,
    RESOURCE_CAPACITY_UNAVAILABLE,
    RESOURCE_TELEMETRY_UNAVAILABLE,
}

@Serializable
data class GenesisProposalRequest(val prompt: String)

@Serializable
data class GenesisProposal(
    val projectId: Int,
    val phase: String,
    val baseRevision: Int,
    val baseHash: String? = null,
    val submission: ProjectGenesisSubmission,
    val observations: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val model: String,
)

data class GenesisProposalResult(
    val status: GenesisProposalStatus,
    val proposal: GenesisProposal? = null,
)

class GenesisIntelligenceService(
    private val workspace: WorkspaceStore,
    private val modelProvider: ModelProvider,
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
) {
    private val mutex = Mutex()
    private val strictJson = Json { encodeDefaults = true }

    suspend fun propose(projectId: Int, request: GenesisProposalRequest): GenesisProposalResult {
        if (!mutex.tryLock()) return GenesisProposalResult(GenesisProposalStatus.BUSY)
        return try {
            generate(projectId, request)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun generate(projectId: Int, request: GenesisProposalRequest): GenesisProposalResult {
        if (request.prompt.isBlank() || request.prompt.encodeToByteArray().size > MAX_PROMPT_BYTES) {
            return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        }
        val genesis = workspace.snapshot(0).projectGenesis.singleOrNull { it.projectId == projectId }
            ?: return GenesisProposalResult(GenesisProposalStatus.PROJECT_NOT_FOUND)
        if (genesis.phase in setOf(GENESIS_ADMISSION, GENESIS_READY)) {
            return GenesisProposalResult(GenesisProposalStatus.PHASE_NOT_PROPOSABLE)
        }
        val envelope = GenesisProposalEnvelope(
            current = genesis,
            userMessage = request.prompt.trim(),
            requiredSubmissionShape = requiredShape(genesis.phase),
            availableFirstEpics = (0 until workspace.entityCount).map(workspace::entityAt)
                .filter { it.type == ENTITY_EPIC && it.parentId == projectId }
                .map { GenesisEpicOption(it.id, it.title) },
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative genesis envelope:\n$envelopeJson"
        val profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return GenesisProposalResult(GenesisProposalStatus.CONTEXT_BUDGET_EXCEEDED)
        }
        val admission = resourceController.tryAcquire(modelProvider.resourceDemand(profile))
        val lease = admission.lease ?: return GenesisProposalResult(
            if (admission.evidence.decision == ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE) {
                GenesisProposalStatus.RESOURCE_TELEMETRY_UNAVAILABLE
            } else {
                GenesisProposalStatus.RESOURCE_CAPACITY_UNAVAILABLE
            }
        )
        val generation = try {
            lease.use {
                modelProvider.executeWorkDefinition(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return GenesisProposalResult(GenesisProposalStatus.MODEL_UNAVAILABLE)
        }
        if (
            generation.promptTokens > profile.inputBudgetTokens ||
            generation.completionTokens > profile.outputBudgetTokens ||
            estimateModelTokens(generation.text) > profile.outputBudgetTokens
        ) return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        val output = runCatching { strictJson.decodeFromString<GenesisProposalOutput>(generation.text) }.getOrNull()
            ?: return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        val pinned = output.submission.copy(
            baseRevision = genesis.revision?.revision ?: 0,
            baseHash = genesis.revision?.hash,
        )
        if (!matchesPhase(genesis.phase, pinned)) {
            return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        }
        return GenesisProposalResult(
            GenesisProposalStatus.CREATED,
            GenesisProposal(
                projectId = projectId,
                phase = genesis.phase,
                baseRevision = pinned.baseRevision,
                baseHash = pinned.baseHash,
                submission = pinned,
                observations = output.observations.take(MAX_LIST_ENTRIES),
                unresolvedQuestions = output.unresolvedQuestions.take(MAX_LIST_ENTRIES),
                model = modelProvider.bindingProfile().model,
            ),
        )
    }

    private fun matchesPhase(phase: String, submission: ProjectGenesisSubmission): Boolean = when (phase) {
        GENESIS_CLASSIFICATION -> submission.classification != null && submission.productIntent != null &&
            submission.experience == null && submission.components == null && submission.decisions == null &&
            submission.firstEpicId == null && submission.blueprint == null
        "EXPERIENCE" -> submission.classification == null && submission.experience != null &&
            submission.components == null && submission.decisions == null && submission.firstEpicId == null &&
            submission.blueprint == null
        "ARCHITECTURE" -> submission.classification == null && submission.productIntent == null &&
            submission.experience == null && submission.components != null && submission.decisions != null &&
            submission.firstEpicId != null && submission.blueprint == null
        GENESIS_BLUEPRINT -> submission.classification == null && submission.productIntent == null &&
            submission.experience == null && submission.components == null && submission.decisions == null &&
            submission.firstEpicId == null && submission.blueprint != null
        else -> false
    }

    private fun requiredShape(phase: String): String = when (phase) {
        GENESIS_CLASSIFICATION -> "classification and productIntent only"
        "EXPERIENCE" -> "experience only; include audience, promise, journey, principles, qualities, exclusions, accessibility"
        "ARCHITECTURE" -> "components, decisions, and firstEpicId only; IDs must correlate"
        GENESIS_BLUEPRINT -> "blueprint only; include root name, toolchain, modules, verification commands, policy pack IDs"
        else -> "no proposal"
    }

    private companion object {
        const val MAX_PROMPT_BYTES = 16_384
        const val MAX_LIST_ENTRIES = 16

        fun loadPrompt(): String {
            val stream = requireNotNull(
                GenesisIntelligenceService::class.java.getResourceAsStream(
                    "/default-system-prompts/genesis_architect_agent.md"
                )
            ) { "Missing genesis architect agent prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

@Serializable
private data class GenesisProposalEnvelope(
    val envelopeVersion: Int = 1,
    val allowedAction: String = "PROPOSE_CURRENT_GENESIS_TRANSITION",
    val forbiddenActions: List<String> = listOf(
        "ADMIT_GENESIS",
        "START_IMPLEMENTATION",
        "CREATE_REPOSITORY",
        "CHANGE_PRIOR_AUTHORITY",
    ),
    val current: ProjectGenesisView,
    val userMessage: String,
    val requiredSubmissionShape: String,
    val availableFirstEpics: List<GenesisEpicOption>,
)

@Serializable
private data class GenesisEpicOption(val epicId: Int, val title: String)

@Serializable
private data class GenesisProposalOutput(
    val submission: ProjectGenesisSubmission,
    val observations: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
)