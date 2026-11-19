package com.orchard.backend.conversation

import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_ARCHITECTURE
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_EXPERIENCE
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.stagedPlanHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory

@Serializable
enum class ProjectSetupStep(val capabilityId: String, val modelBacked: Boolean = false) {
    CLASSIFY(CAPABILITY_ADVANCE_PROJECT_GENESIS),
    DEFINE_EXPERIENCE(CAPABILITY_ADVANCE_PROJECT_GENESIS, true),
    CREATE_FIRST_OUTCOME(CAPABILITY_CREATE_WORK_ITEM),
    DEFINE_ARCHITECTURE(CAPABILITY_ADVANCE_PROJECT_GENESIS, true),
    DEFINE_BLUEPRINT(CAPABILITY_ADVANCE_PROJECT_GENESIS, true),
    ADMIT_GENESIS(CAPABILITY_ADMIT_PROJECT_GENESIS),
    START_DELIVERY(CAPABILITY_START_COMPANY),
}

@Serializable
enum class ProjectSetupStatus { READY, AWAITING_ADMISSION, DISPATCHING, HANDED_OFF, BLOCKED }

@Serializable
data class ProjectSetupState(
    val status: ProjectSetupStatus,
    val projectId: Int? = null,
    val phase: String? = null,
    val step: ProjectSetupStep? = null,
    val commandId: Long? = null,
    val commandHash: String? = null,
    val diagnostic: String = "",
)

@Serializable
data class ContinueProjectSetupRequest(val actor: String = "HUMAN")

sealed interface ProjectSetupProposal {
    data class Proposed(val interpretation: ConversationInterpretation) : ProjectSetupProposal
    data class Blocked(val diagnostic: String, val exhaustedBasisHash: String? = null) : ProjectSetupProposal
}

class ProjectSetupWorkflow(
    private val workspace: WorkspaceStore,
    private val architect: GenesisIntelligenceService,
    private val json: Json = Json { encodeDefaults = true },
) {
    fun generationBasisHash(objective: ConversationObjectiveRevision): String {
        val state = state(objective.projectId)
        val revision = workspace.snapshot(MESSAGE_READY).projectGenesis.singleOrNull { it.projectId == objective.projectId }?.revision
        return stagedPlanHash(json.encodeToString(listOf(
            objective.hash, state.phase.orEmpty(), state.step?.name.orEmpty(), revision?.hash.orEmpty(),
            architect.bindingFingerprint(), "genesis-final-channel-recovery-v1",
        )))
    }

    internal fun isCurrentCommandBasis(state: ProjectSetupState, command: ConversationCommandProposal): Boolean {
        if (command.capabilityId != state.step?.capabilityId) return false
        val payload = try {
            json.parseToJsonElement(command.payloadJson) as? JsonObject ?: return false
        } catch (error: SerializationException) {
            LoggerFactory.getLogger(ProjectSetupWorkflow::class.java)
                .warn("Failed command {} has invalid JSON; it cannot represent the current setup basis.", command.commandId, error)
            return false
        }
        if ((payload["projectId"] as? JsonPrimitive)?.intOrNull != state.projectId) return false
        val genesis = workspace.snapshot(MESSAGE_READY).projectGenesis.singleOrNull { it.projectId == state.projectId }
            ?: return false
        return when (state.step) {
            ProjectSetupStep.CLASSIFY, ProjectSetupStep.DEFINE_EXPERIENCE,
            ProjectSetupStep.DEFINE_ARCHITECTURE, ProjectSetupStep.DEFINE_BLUEPRINT -> {
                val submission = payload["submission"] as? JsonObject ?: return false
                (submission["baseRevision"] as? JsonPrimitive)?.intOrNull == (genesis.revision?.revision ?: 0) &&
                    (submission["baseHash"] as? JsonPrimitive)?.contentOrNull == genesis.revision?.hash
            }
            ProjectSetupStep.CREATE_FIRST_OUTCOME ->
                (payload["firstOutcomeGenesisHash"] as? JsonPrimitive)?.contentOrNull == genesis.revision?.hash
            ProjectSetupStep.ADMIT_GENESIS, ProjectSetupStep.START_DELIVERY ->
                (payload["genesisHash"] as? JsonPrimitive)?.contentOrNull == genesis.revision?.hash
        }
    }

    fun state(projectId: Int?): ProjectSetupState {
        val snapshot = workspace.snapshot(MESSAGE_READY)
        val genesis = snapshot.projectGenesis.singleOrNull { it.projectId == projectId }
            ?: return ProjectSetupState(ProjectSetupStatus.BLOCKED, projectId,
                diagnostic = "Select an active project before continuing setup.")
        if (genesis.revision?.classification?.let { it != PROJECT_GREENFIELD_LOCAL } == true ||
            genesis.revision == null && snapshot.repositories.containsKey(projectId)) {
            return ProjectSetupState(ProjectSetupStatus.BLOCKED, projectId, genesis.phase,
                diagnostic = "This workflow owns greenfield local setup, not existing-repository onboarding.")
        }
        val step = when (genesis.phase) {
            GENESIS_CLASSIFICATION -> ProjectSetupStep.CLASSIFY
            GENESIS_EXPERIENCE -> ProjectSetupStep.DEFINE_EXPERIENCE
            GENESIS_ARCHITECTURE -> if (workspace.entities().any { it.type == ENTITY_EPIC && it.parentId == projectId }) {
                ProjectSetupStep.DEFINE_ARCHITECTURE
            } else ProjectSetupStep.CREATE_FIRST_OUTCOME
            GENESIS_BLUEPRINT -> ProjectSetupStep.DEFINE_BLUEPRINT
            GENESIS_ADMISSION -> ProjectSetupStep.ADMIT_GENESIS
            GENESIS_READY -> {
                if (snapshot.workflowRuns.any { it.context.projectId == projectId }) {
                    return ProjectSetupState(ProjectSetupStatus.HANDED_OFF, projectId, genesis.phase,
                        diagnostic = "Setup has handed off to the existing delivery workflow.")
                }
                ProjectSetupStep.START_DELIVERY
            }
            else -> return ProjectSetupState(ProjectSetupStatus.BLOCKED, projectId, genesis.phase,
                diagnostic = "The current Genesis phase has no supported setup transition.")
        }
        return ProjectSetupState(ProjectSetupStatus.READY, projectId, genesis.phase, step)
    }

    suspend fun propose(
        objective: ConversationObjectiveRevision,
        originalRequirement: String,
    ): ProjectSetupProposal {
        val state = state(objective.projectId)
        if (state.status != ProjectSetupStatus.READY) return ProjectSetupProposal.Blocked(state.diagnostic)
        val projectId = requireNotNull(state.projectId)
        val genesis = workspace.snapshot(MESSAGE_READY).projectGenesis.single { it.projectId == projectId }
        val content = (listOf(objective.outcome) + objective.constraints).joinToString("\n")
        val step = requireNotNull(state.step)
        val payload = when (step) {
            ProjectSetupStep.CLASSIFY -> json.encodeToString(AdvanceProjectGenesisCapabilityPayload(
                projectId,
                ProjectGenesisSubmission(classification = PROJECT_GREENFIELD_LOCAL, productIntent = content,
                    baseRevision = 0),
            ))
            ProjectSetupStep.CREATE_FIRST_OUTCOME -> json.encodeToString(CreateWorkItemCapabilityPayload(
                entityTypeId = ENTITY_EPIC,
                projectId = projectId,
                title = "Deliver the first working outcome",
                content = content,
                firstOutcomeGenesisHash = requireNotNull(genesis.revision).hash,
            ))
            ProjectSetupStep.DEFINE_EXPERIENCE,
            ProjectSetupStep.DEFINE_ARCHITECTURE,
            ProjectSetupStep.DEFINE_BLUEPRINT -> {
                val basis = generationBasisHash(objective)
                val result = architect.propose(projectId, GenesisProposalRequest(originalRequirement, recoverInvalidOutput = true))
                val proposal = result.proposal
                    ?: return ProjectSetupProposal.Blocked("${result.status}: ${result.diagnostic}",
                        basis.takeIf { result.recoveryExhausted })
                if (proposal.phase != genesis.phase ||
                    proposal.baseRevision != (genesis.revision?.revision ?: 0) ||
                    proposal.baseHash != genesis.revision?.hash) {
                    return ProjectSetupProposal.Blocked("Genesis authority changed while the phase proposal was being generated.")
                }
                json.encodeToString(AdvanceProjectGenesisCapabilityPayload(projectId, proposal.submission))
            }
            ProjectSetupStep.ADMIT_GENESIS,
            ProjectSetupStep.START_DELIVERY -> json.encodeToString(ProjectCapabilityPayload(projectId, requireNotNull(genesis.revision).hash))
        }
        return ProjectSetupProposal.Proposed(ConversationInterpretation(
            speechAct = SPEECH_PROPOSE_DOMAIN_ACTION,
            response = "Project setup selected ${step.name}; the exact command awaits admission.",
            capabilityId = step.capabilityId,
            payloadJson = payload,
        ))
    }
}
