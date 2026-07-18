package com.orchard.backend.standards

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_BUG
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CampaignResolutionProposalStatus {
    CREATED,
    CASE_NOT_FOUND,
    CASE_RESOLVED,
    STALE_EVALUATION,
    CONTEXT_BUDGET_EXCEEDED,
    NO_COMPATIBLE_MODEL,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_OUTPUT,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CampaignResolutionProposalResult(
    val status: CampaignResolutionProposalStatus,
    val proposal: CampaignResolutionProposal? = null,
    val diagnostic: String = "",
)

enum class CampaignResolutionAdmissionStatus {
    ADMITTED,
    PROPOSAL_NOT_FOUND,
    CASE_RESOLVED,
    STALE_EVALUATION,
    REPOSITORY_DRIFTED,
    CAPACITY_EXCEEDED,
    INVALID_BACKLOG,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CampaignResolutionAdmissionResult(
    val status: CampaignResolutionAdmissionStatus,
    val admission: CampaignResolutionAdmission? = null,
    val successorCampaign: RemediationCampaign? = null,
    val diagnostic: String = "",
)

@Serializable
private data class CampaignResolutionAnalysisEnvelope(
    val case: CampaignResolutionCase,
    val campaign: RemediationCampaign,
    val evaluation: RemediationCampaignEvaluation,
    val standard: EngineeringStandardRevision,
    val scan: RepositoryConformanceScan,
    val allowedActions: List<String>,
    val allowedVerificationCommands: List<String>,
    val maxBacklogNodes: Int,
    val requiredOutputSchema: String = "campaign-resolution-v1",
)

@Serializable
private data class CampaignResolutionOutput(
    val action: String,
    val rationale: String,
    val practiceIds: List<String>,
    val instructions: String,
    val proposedBacklog: List<CampaignResolutionBacklogNode> = emptyList(),
)

class CampaignResolutionService(
    private val workspace: WorkspaceStore,
    private val repositoryBindings: RepositoryBindingStore,
    private val standardsStore: EngineeringStandardsStore,
    private val campaignStore: RemediationCampaignStore,
    private val modelProviders: List<ModelProvider>,
    private val store: CampaignResolutionStore = TransientCampaignResolutionStore(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val standardsPolicy: StandardsPolicyService? = null,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val systemPrompt: String = loadPrompt(),
) {
    private val proposalMutexes = ConcurrentHashMap<Long, Mutex>()

    fun views(projectId: Int? = null): List<CampaignResolutionView> = campaignResolutionViews(store, projectId)

    @Synchronized
    fun reconcileCases(): Int {
        var created = 0
        remediationCampaignViews(campaignStore).forEach { view ->
            val evaluation = view.evaluations.lastOrNull() ?: return@forEach
            if (evaluation.state !in setOf(CAMPAIGN_BLOCKED, CAMPAIGN_ESCALATED) ||
                store.cases().any { it.evaluationHash == evaluation.hash }) return@forEach
            val affected = evaluation.practices.filter { practice ->
                !practice.resolved || practice.regressed ||
                    practice.currentDisposition in setOf(CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING)
            }.map { it.practiceId }.distinct()
            val case = newCampaignResolutionCase(
                CampaignResolutionCase(
                    caseId = (store.cases().maxOfOrNull { it.caseId } ?: 0L) + 1L,
                    campaignId = view.campaign.campaignId,
                    projectId = view.campaign.projectId,
                    evaluationId = evaluation.evaluationId,
                    evaluationHash = evaluation.hash,
                    repositoryRevision = evaluation.repositoryRevision,
                    cause = cause(evaluation),
                    practiceIds = affected,
                    openedAt = Instant.now().toString(),
                    hash = "",
                )
            )
            store.appendCase(case)
            created++
        }
        return created
    }

    @Synchronized
    fun reconcileSuccessors(): Int {
        var created = 0
        store.admissions().sortedBy { it.admissionId }.forEach { admission ->
            if (admission.admittedNodes.isEmpty() || campaignStore.campaigns().any {
                    it.successorSource?.resolutionAdmissionId == admission.admissionId
                }) return@forEach
            val case = store.cases().single { it.caseId == admission.caseId }
            val proposal = store.proposals().single { it.proposalId == admission.proposalId }
            val predecessor = campaignStore.campaigns().single { it.campaignId == case.campaignId }
            val evaluation = campaignStore.evaluations().single { it.evaluationId == case.evaluationId && it.hash == case.evaluationHash }
            val scan = standardsStore.scans().single { it.scanId == evaluation.scanId && it.hash == evaluation.scanHash }
            val entities = admission.admittedNodes.associate { it.nodeId to it.entityId }
            val findingByPractice = scan.findings.associateBy { it.practiceId }
            val links = proposal.practiceIds.map { practiceId ->
                val nodes = proposal.proposedBacklog.filter { practiceId in it.practiceIds }
                CampaignPracticeLink(
                    practiceId = practiceId,
                    seedFindingId = requireNotNull(findingByPractice[practiceId]).findingId,
                    backlogNodeIds = nodes.map { it.nodeId },
                    admittedEntityIds = nodes.map { requireNotNull(entities[it.nodeId]) },
                )
            }
            val campaign = newRemediationCampaign(
                RemediationCampaign(
                    campaignId = (campaignStore.campaigns().maxOfOrNull { it.campaignId } ?: 0L) + 1L,
                    projectId = predecessor.projectId,
                    standardId = predecessor.standardId,
                    standardRevision = predecessor.standardRevision,
                    standardHash = predecessor.standardHash,
                    seedScanId = scan.scanId,
                    seedScanHash = scan.hash,
                    seedAdmissionId = predecessor.seedAdmissionId,
                    seedAdmissionHash = predecessor.seedAdmissionHash,
                    seedRepositoryRevision = evaluation.repositoryRevision,
                    seedPractices = scan.findings.map { CampaignSeedPractice(it.practiceId, it.findingId, it.disposition) },
                    links = links,
                    successorSource = CampaignSuccessorSource(
                        predecessorCampaignId = predecessor.campaignId,
                        resolutionCaseId = case.caseId,
                        resolutionProposalId = proposal.proposalId,
                        resolutionProposalHash = proposal.hash,
                        resolutionAdmissionId = admission.admissionId,
                        resolutionAdmissionHash = admission.hash,
                        backlog = proposal.proposedBacklog,
                        admittedNodes = admission.admittedNodes,
                    ),
                    createdAt = Instant.now().toString(),
                    hash = "",
                )
            )
            campaignStore.appendCampaign(campaign)
            created++
        }
        return created
    }

    @Synchronized
    fun reconcileExceptionRequests(): Int {
        val policy = standardsPolicy ?: return 0
        var created = 0
        store.admissions().sortedBy { it.admissionId }.forEach { admission ->
            val proposal = store.proposals().single { it.proposalId == admission.proposalId }
            if (proposal.action != RESOLUTION_ACTION_EXCEPTION_REQUEST) return@forEach
            val case = store.cases().single { it.caseId == admission.caseId }
            val evaluation = campaignStore.evaluations().single { it.evaluationId == case.evaluationId && it.hash == case.evaluationHash }
            val scan = standardsStore.scans().single { it.scanId == evaluation.scanId && it.hash == evaluation.scanHash }
            if (policy.seedExceptionRequest(case, proposal, admission, scan).status == StandardsPolicyMutationStatus.RECORDED) created++
        }
        return created
    }

    suspend fun propose(caseId: Long): CampaignResolutionProposalResult {
        val proposalMutex = proposalMutexes.computeIfAbsent(caseId) { Mutex() }
        if (!proposalMutex.tryLock()) return CampaignResolutionProposalResult(
            CampaignResolutionProposalStatus.MODEL_FAILED,
            diagnostic = "Another campaign resolution proposal is being generated.",
        )
        return try {
            generateProposal(caseId)
        } finally {
            proposalMutex.unlock()
        }
    }

    @Synchronized
    fun admit(proposalId: Long, actor: String = "HUMAN"): CampaignResolutionAdmissionResult {
        val proposal = store.proposals().singleOrNull { it.proposalId == proposalId }
            ?: return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.PROPOSAL_NOT_FOUND)
        val case = store.cases().single { it.caseId == proposal.caseId }
        store.admissions().singleOrNull { it.caseId == case.caseId }?.let {
            return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.CASE_RESOLVED, it)
        }
        val current = currentTerminalEvaluation(case)
            ?: return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.STALE_EVALUATION)
        val head = repositoryBindings.resolveHead(case.projectId)
            ?: return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.REPOSITORY_DRIFTED)
        if (!head.clean || head.commitHash != current.repositoryRevision) {
            return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.REPOSITORY_DRIFTED)
        }
        val nodes = proposal.proposedBacklog
        val existingNodes = if (nodes.isEmpty()) emptyList() else existingEntityMappings(proposal)
        if (existingNodes == null && workspace.entityCount + nodes.size > MAX_WORKSPACE_ENTITIES) {
            return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.CAPACITY_EXCEEDED)
        }
        val admittedNodes = existingNodes ?: createEntities(case, proposal)
            ?: return CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.INVALID_BACKLOG)
        val admission = newCampaignResolutionAdmission(
            CampaignResolutionAdmission(
                admissionId = (store.admissions().maxOfOrNull { it.admissionId } ?: 0L) + 1L,
                caseId = case.caseId,
                proposalId = proposal.proposalId,
                proposalHash = proposal.hash,
                actor = actor.trim(),
                admittedAt = Instant.now().toString(),
                admittedNodes = admittedNodes,
                hash = "",
            )
        )
        return runCatching {
            store.appendAdmission(admission)
            reconcileSuccessors()
            reconcileExceptionRequests()
            val successor = campaignStore.campaigns().singleOrNull {
                it.successorSource?.resolutionAdmissionId == admission.admissionId
            }
            CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.ADMITTED, admission, successor)
        }.getOrElse {
            CampaignResolutionAdmissionResult(CampaignResolutionAdmissionStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty())
        }
    }

    private suspend fun generateProposal(caseId: Long): CampaignResolutionProposalResult {
        reconcileCases()
        val case = store.cases().singleOrNull { it.caseId == caseId }
            ?: return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.CASE_NOT_FOUND)
        if (store.admissions().any { it.caseId == caseId }) {
            return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.CASE_RESOLVED)
        }
        val evaluation = currentTerminalEvaluation(case)
            ?: return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.STALE_EVALUATION)
        val campaign = campaignStore.campaigns().single { it.campaignId == case.campaignId }
        val standard = standardsStore.standards().single {
            it.standardId == campaign.standardId && it.revision == campaign.standardRevision && it.hash == campaign.standardHash
        }
        val scan = standardsStore.scans().single { it.scanId == evaluation.scanId && it.hash == evaluation.scanHash }
        val commands = scan.findings.filter { it.practiceId in case.practiceIds }.flatMap { it.verificationCommands }.distinct()
        val envelope = CampaignResolutionAnalysisEnvelope(
            case,
            campaign,
            evaluation,
            standard,
            scan,
            RESOLUTION_ACTIONS,
            commands,
            MAX_BACKLOG_NODES,
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative campaign resolution envelope:\n$envelopeJson"
        val profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.CONTEXT_BUDGET_EXCEEDED)
        }
        val provider = runCatching { ModelProfileResolver.resolve(profile, modelProviders) }.getOrNull()
            ?: return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.NO_COMPATIBLE_MODEL)
        val resourceAdmission = resourceController.tryAcquire(provider.resourceDemand(profile))
        val lease = resourceAdmission.lease ?: return CampaignResolutionProposalResult(
            CampaignResolutionProposalStatus.RESOURCE_BLOCKED,
            diagnostic = resourceAdmission.evidence.reason,
        )
        val generation = try {
            lease.use { provider.executeRepositoryAnalysis(prompt, profile.outputBudgetTokens, profile.inputBudgetTokens + profile.outputBudgetTokens) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.MODEL_FAILED, diagnostic = error.message.orEmpty())
        }
        val output = generation.takeIf {
            it.promptTokens <= profile.inputBudgetTokens && it.completionTokens <= profile.outputBudgetTokens &&
                estimateModelTokens(it.text) <= profile.outputBudgetTokens
        }?.let { runCatching { json.decodeFromString<CampaignResolutionOutput>(it.text) }.getOrNull() }
            ?: return CampaignResolutionProposalResult(
                CampaignResolutionProposalStatus.INVALID_OUTPUT,
                diagnostic = "The Architect did not return valid strict JSON.",
            )
        validateOutput(case, commands, output)?.let {
            return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.INVALID_OUTPUT, diagnostic = it)
        }
        if (currentTerminalEvaluation(case)?.hash != evaluation.hash) {
            return CampaignResolutionProposalResult(CampaignResolutionProposalStatus.STALE_EVALUATION)
        }
        var proposal: CampaignResolutionProposal? = null
        return runCatching {
            proposal = store.appendNextProposal { proposalId ->
                newCampaignResolutionProposal(CampaignResolutionProposal(
                    proposalId = proposalId,
                    caseId = case.caseId,
                    evaluationHash = evaluation.hash,
                    action = output.action,
                    rationale = output.rationale.trim(),
                    practiceIds = output.practiceIds,
                    instructions = output.instructions.trim(),
                    proposedBacklog = output.proposedBacklog,
                    actor = "ARCHITECT",
                    modelBindingFingerprint = modelBindingFingerprint(provider.bindingProfile()),
                    promptHash = sha256(prompt),
                    outputHash = sha256(generation.text),
                    proposedAt = Instant.now().toString(),
                    hash = "",
                ))
            }
        }.fold(
            onSuccess = { CampaignResolutionProposalResult(CampaignResolutionProposalStatus.CREATED, proposal) },
            onFailure = { CampaignResolutionProposalResult(CampaignResolutionProposalStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    private fun validateOutput(
        case: CampaignResolutionCase,
        commands: List<String>,
        output: CampaignResolutionOutput,
    ): String? {
        if (output.action !in RESOLUTION_ACTIONS || output.rationale.isBlank() || output.instructions.isBlank()) {
            return "The resolution action, rationale, or instructions are invalid."
        }
        if (output.practiceIds.isEmpty() || output.practiceIds.distinct().size != output.practiceIds.size ||
            output.practiceIds.any { it !in case.practiceIds }) return "The resolution references practices outside the terminal case."
        if (output.proposedBacklog.size > MAX_BACKLOG_NODES) return "The resolution backlog exceeds the admitted limit."
        if (output.proposedBacklog.flatMap { it.verificationCommands }.any { it !in commands }) {
            return "The resolution invented a verification command."
        }
        return runCatching {
            newCampaignResolutionProposal(
                CampaignResolutionProposal(
                    1,
                    case.caseId,
                    case.evaluationHash,
                    output.action,
                    output.rationale,
                    output.practiceIds,
                    output.instructions,
                    output.proposedBacklog,
                    "ARCHITECT",
                    proposedAt = Instant.EPOCH.toString(),
                    hash = "",
                )
            )
        }.exceptionOrNull()?.message
    }

    private fun createEntities(
        case: CampaignResolutionCase,
        proposal: CampaignResolutionProposal,
    ): List<AdmittedBacklogNode>? {
        val entityIds = mutableMapOf<String, Int>()
        val created = mutableListOf<AdmittedBacklogNode>()
        workspace.beginBatch()
        try {
            proposal.proposedBacklog.forEach { node ->
                val parentId = node.parentNodeId?.let(entityIds::get)
                if (!workspace.applyIntent(backlogIntent(case, proposal, node, parentId, entityIds))) {
                    workspace.rollbackBatch()
                    return null
                }
                val entity = workspace.entityAt(workspace.entityCount - 1)
                entityIds[node.nodeId] = entity.id
                created += AdmittedBacklogNode(node.nodeId, entity.id)
            }
            workspace.commitBatch()
            return created
        } catch (error: Exception) {
            runCatching { workspace.rollbackBatch() }
            throw error
        }
    }

    private fun existingEntityMappings(proposal: CampaignResolutionProposal): List<AdmittedBacklogNode>? {
        val marker = "Campaign resolution proposal: ${proposal.hash}"
        val entities = (0 until workspace.entityCount).map(workspace::entityAt).filter { marker in it.content }
        if (entities.isEmpty()) return null
        require(entities.size == proposal.proposedBacklog.size &&
            entities.map { it.title } == proposal.proposedBacklog.map { it.title }) {
            "Workspace contains an incomplete or altered resolution admission batch."
        }
        return proposal.proposedBacklog.zip(entities) { node, entity ->
            AdmittedBacklogNode(node.nodeId, entity.id)
        }
    }

    private fun backlogIntent(
        case: CampaignResolutionCase,
        proposal: CampaignResolutionProposal,
        node: CampaignResolutionBacklogNode,
        parentId: Int?,
        entityIds: Map<String, Int>,
    ): DocumentIntent {
        val parentNode = node.parentNodeId?.let { id -> proposal.proposedBacklog.singleOrNull { it.nodeId == id } }
        val type = when (node.type) {
            BACKLOG_EPIC -> ENTITY_EPIC
            BACKLOG_STORY -> ENTITY_STORY
            BACKLOG_BUG -> ENTITY_BUG
            BACKLOG_TASK, BACKLOG_INVESTIGATION -> ENTITY_TASK
            else -> error("Unsupported resolution backlog node ${node.type}")
        }
        val epicId = when {
            node.type == BACKLOG_STORY -> parentId ?: 0
            node.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) ->
                parentNode?.parentNodeId?.let(entityIds::get) ?: 0
            else -> 0
        }
        return DocumentIntent(
            actionTypeId = ACTION_CREATE,
            entityTypeId = type,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
            projectId = case.projectId,
            epicId = epicId,
            storyId = if (type in setOf(ENTITY_TASK, ENTITY_BUG)) parentId ?: 0 else 0,
            title = node.title,
            content = buildString {
                appendLine(node.description)
                appendLine("Campaign resolution proposal: ${proposal.hash}")
                appendLine("Predecessor campaign: ${case.campaignId}")
                appendLine("Resolution practices: ${node.practiceIds.joinToString()}")
                if (node.acceptanceCriteria.isNotEmpty()) appendLine("Acceptance: ${node.acceptanceCriteria.joinToString(" | ")}")
                if (node.verificationCommands.isNotEmpty()) append("Verification: ${node.verificationCommands.joinToString(" | ")}")
            }.trim(),
        )
    }

    private fun currentTerminalEvaluation(case: CampaignResolutionCase): RemediationCampaignEvaluation? {
        val latest = campaignStore.evaluations().filter { it.campaignId == case.campaignId }.maxByOrNull { it.evaluationId }
        return latest?.takeIf {
            it.evaluationId == case.evaluationId && it.hash == case.evaluationHash &&
                it.state in setOf(CAMPAIGN_BLOCKED, CAMPAIGN_ESCALATED)
        }
    }

    private fun cause(evaluation: RemediationCampaignEvaluation): String = when {
        evaluation.practices.any { it.regressed } -> RESOLUTION_CAUSE_REGRESSION
        evaluation.practices.any { it.currentDisposition == CONFORMANCE_CONFLICTING } -> RESOLUTION_CAUSE_EVIDENCE_CONFLICT
        evaluation.practices.any { it.currentDisposition == CONFORMANCE_UNKNOWN } -> RESOLUTION_CAUSE_EVIDENCE_UNKNOWN
        else -> RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_BACKLOG_NODES = 24
        const val MAX_WORKSPACE_ENTITIES = 32
        val RESOLUTION_ACTIONS = listOf(
            RESOLUTION_ACTION_ADDITIONAL_REMEDIATION,
            RESOLUTION_ACTION_INVESTIGATION,
            RESOLUTION_ACTION_RESCAN,
            RESOLUTION_ACTION_EXCEPTION_REQUEST,
            RESOLUTION_ACTION_STANDARD_CLARIFICATION,
            RESOLUTION_ACTION_ABANDON,
        )

        fun loadPrompt(): String = requireNotNull(
            CampaignResolutionService::class.java.getResourceAsStream("/default-system-prompts/campaign_resolution_agent.md")
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
