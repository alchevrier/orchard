package com.orchard.backend.standards

import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.CRITERION_AUTOMATED
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.DesignCriterionSubmission
import com.orchard.backend.workspace.DesignGovernanceStatus
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.ENTITY_BUG
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RequirementSubmission
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanNodeSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.StagedPlanStatus
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceStore
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

const val CAMPAIGN_TICK_IDLE = "IDLE"
const val CAMPAIGN_TICK_RECORDED = "RECORDED"
const val CAMPAIGN_TICK_WAITING_FOR_PROMOTION = "WAITING_FOR_PROMOTION"
const val CAMPAIGN_TICK_SCAN_BLOCKED = "SCAN_BLOCKED"
const val CAMPAIGN_TICK_STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE"

@Serializable
data class RemediationCampaignTickResult(
    val status: String,
    val campaign: RemediationCampaignView? = null,
    val diagnostic: String = "",
)

class RemediationCampaignService(
    private val workspace: WorkspaceStore,
    private val repositoryBindings: RepositoryBindingStore,
    private val standardsStore: EngineeringStandardsStore,
    private val standardsService: EngineeringStandardsService,
    private val company: CompanyControlService,
    private val store: RemediationCampaignStore = TransientRemediationCampaignStore(),
) {
    private val tickMutex = Mutex()

    fun views(projectId: Int? = null): List<RemediationCampaignView> = remediationCampaignViews(store, projectId)

    @Synchronized
    fun reconcileAdmissions(): Int {
        var created = 0
        standardsStore.admissions().sortedBy { it.admissionId }.forEach { admission ->
            if (store.campaigns().any { it.seedAdmissionId == admission.admissionId }) return@forEach
            val scan = standardsStore.scans().singleOrNull { it.scanId == admission.scanId && it.hash == admission.scanHash }
                ?: return@forEach
            val nodeEntities = if (admission.admittedNodes.isNotEmpty()) {
                admission.admittedNodes.associate { it.nodeId to it.entityId }
            } else {
                scan.proposedBacklog.map { it.nodeId }.zip(admission.admittedEntityIds).toMap()
            }
            val links = scan.findings.filter(::actionable).map { finding ->
                val nodes = scan.proposedBacklog.filter { finding.findingId in it.findingIds }
                CampaignPracticeLink(
                    practiceId = finding.practiceId,
                    seedFindingId = finding.findingId,
                    backlogNodeIds = nodes.map { it.nodeId },
                    admittedEntityIds = nodes.map { requireNotNull(nodeEntities[it.nodeId]) },
                )
            }
            if (links.isEmpty()) return@forEach
            val campaign = newRemediationCampaign(
                RemediationCampaign(
                    campaignId = (store.campaigns().maxOfOrNull { it.campaignId } ?: 0L) + 1L,
                    projectId = scan.projectId,
                    standardId = scan.standardId,
                    standardRevision = scan.standardRevision,
                    standardHash = scan.standardHash,
                    seedScanId = scan.scanId,
                    seedScanHash = scan.hash,
                    seedAdmissionId = admission.admissionId,
                    seedAdmissionHash = admission.hash,
                    seedRepositoryRevision = scan.repositoryRevision,
                    seedPractices = scan.findings.map { CampaignSeedPractice(it.practiceId, it.findingId, it.disposition) },
                    links = links,
                    createdAt = Instant.now().toString(),
                    hash = "",
                )
            )
            store.appendCampaign(campaign)
            created++
        }
        return created
    }

    suspend fun tick(): RemediationCampaignTickResult {
        if (!tickMutex.tryLock()) return RemediationCampaignTickResult(CAMPAIGN_TICK_IDLE)
        return try {
            reconcileAdmissions()
            val campaign = views().firstOrNull(::requiresEvaluation)
                ?: return RemediationCampaignTickResult(CAMPAIGN_TICK_IDLE)
            evaluate(campaign)
        } finally {
            tickMutex.unlock()
        }
    }

    private suspend fun evaluate(view: RemediationCampaignView): RemediationCampaignTickResult {
        val campaign = view.campaign
        promoteAcceptedSlice(campaign)?.let { diagnostic ->
            return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, progressView(campaign), diagnostic)
        }
        val head = repositoryBindings.resolveHead(campaign.projectId)
            ?: return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, view, "The bound repository is unavailable.")
        if (!head.clean) return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, view, "The bound repository is dirty.")
        val standard = standardsStore.standards().singleOrNull {
            it.standardId == campaign.standardId && it.revision == campaign.standardRevision && it.hash == campaign.standardHash
        } ?: return RemediationCampaignTickResult(CAMPAIGN_TICK_STORAGE_UNAVAILABLE, view, "The campaign standard revision is unavailable.")
        val seedScan = standardsStore.scans().single { it.scanId == campaign.seedScanId && it.hash == campaign.seedScanHash }
        val targetScope = seedScan.effectiveStandard?.targetScope ?: StandardPolicyScope(STANDARD_SCOPE_PROJECT, campaign.projectId)
        val authorityHash = standardsService.currentAuthorityHash(campaign.projectId, standard, targetScope)
            ?: return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, view, "The current standards policy authority is unavailable or conflicting.")
        val policyAuthorityHash = authorityHash.takeUnless { it == campaign.standardHash }
        val key = campaignIdempotencyKey(campaign.campaignId, head.commitHash, policyAuthorityHash)
        store.evaluations().singleOrNull { it.idempotencyKey == key }?.let { evaluation ->
            if (evaluation.state == CAMPAIGN_IN_PROGRESS) {
                compileNextSlice(campaign)?.let { diagnostic ->
                    return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, progressView(campaign), diagnostic)
                }
                workspace.dispatchEligible()
                return RemediationCampaignTickResult(CAMPAIGN_TICK_WAITING_FOR_PROMOTION, progressView(campaign))
            }
            return RemediationCampaignTickResult(
                CAMPAIGN_TICK_RECORDED,
                views(campaign.projectId).single { candidate -> candidate.campaign.campaignId == campaign.campaignId },
            )
        }
        if (head.commitHash == campaign.seedRepositoryRevision) {
            compileNextSlice(campaign)?.let { diagnostic ->
                return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, progressView(campaign), diagnostic)
            }
            workspace.dispatchEligible()
            return RemediationCampaignTickResult(CAMPAIGN_TICK_WAITING_FOR_PROMOTION, progressView(campaign))
        }
        val project = company.projectView(campaign.projectId)
        val promotions = project.promotions.filter { promotion ->
            promotion.destinationRevision == head.commitHash && campaignWorkItemIds(campaign).any { workItemId ->
                workspace.snapshot(MESSAGE_READY).workflowRuns.any {
                    it.context.workItemId == workItemId && it.runId == promotion.runId && it.state == RUN_STATE_DONE
                }
            }
        }
        if (promotions.isEmpty()) return RemediationCampaignTickResult(
            CAMPAIGN_TICK_WAITING_FOR_PROMOTION,
            progressView(campaign),
            "Campaign closure requires an accepted local promotion for linked work.",
        )
        val existingScan = standardsStore.scans().singleOrNull {
            it.projectId == campaign.projectId && it.standardHash == campaign.standardHash && it.repositoryRevision == head.commitHash &&
                conformanceAuthorityHash(it.standardHash, it.effectiveStandard, it.appliedExceptions) == authorityHash
        }
        val scan = existingScan ?: standardsService.scanRevision(campaign.projectId, standard, targetScope).scan
            ?: return RemediationCampaignTickResult(CAMPAIGN_TICK_SCAN_BLOCKED, progressView(campaign), "The follow-up conformance scan did not complete.")
        val prior = view.evaluations.lastOrNull()?.practices?.associateBy { it.practiceId }
            ?: campaign.seedPractices.associate { seed ->
                seed.practiceId to CampaignPracticeEvaluation(seed.practiceId, seed.disposition, seed.disposition, seed.disposition in RESOLVED, false)
            }
        val linkedPracticeIds = campaign.links.map { it.practiceId }.toSet()
        val practices = scan.findings.map { finding ->
            val previous = requireNotNull(prior[finding.practiceId])
            val resolved = finding.disposition in RESOLVED
            CampaignPracticeEvaluation(
                practiceId = finding.practiceId,
                priorDisposition = previous.currentDisposition,
                currentDisposition = finding.disposition,
                resolved = resolved,
                regressed = previous.currentDisposition in RESOLVED && !resolved,
            )
        }
        val linkedPractices = practices.filter { it.practiceId in linkedPracticeIds }
        val state = when {
            linkedPractices.all { it.resolved } && practices.none { it.regressed } -> CAMPAIGN_CLOSED
            practices.any { it.regressed } || linkedPractices.any {
                it.currentDisposition in setOf(CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING)
            } -> CAMPAIGN_ESCALATED
            hasUncompiledLeaves(campaign) -> CAMPAIGN_IN_PROGRESS
            else -> CAMPAIGN_BLOCKED
        }
        val evaluation = newRemediationCampaignEvaluation(
            RemediationCampaignEvaluation(
                evaluationId = (store.evaluations().maxOfOrNull { it.evaluationId } ?: 0L) + 1L,
                campaignId = campaign.campaignId,
                scanId = scan.scanId,
                scanHash = scan.hash,
                repositoryRevision = head.commitHash,
                promotionIds = promotions.map { it.promotionId }.sorted(),
                practices = practices,
                state = state,
                idempotencyKey = key,
                recordedAt = Instant.now().toString(),
                hash = "",
                policyAuthorityHash = policyAuthorityHash,
            )
        )
        return runCatching { store.appendEvaluation(evaluation) }.fold(
            onSuccess = {
                RemediationCampaignTickResult(
                    CAMPAIGN_TICK_RECORDED,
                    views(campaign.projectId).single { candidate -> candidate.campaign.campaignId == campaign.campaignId },
                )
            },
            onFailure = { RemediationCampaignTickResult(CAMPAIGN_TICK_STORAGE_UNAVAILABLE, view, it.message.orEmpty()) },
        )
    }

    private fun requiresEvaluation(view: RemediationCampaignView): Boolean {
        if (view.state !in setOf(CAMPAIGN_CLOSED, CAMPAIGN_BLOCKED, CAMPAIGN_ESCALATED)) return true
        val campaign = view.campaign
        val standard = standardsStore.standards().singleOrNull {
            it.standardId == campaign.standardId && it.revision == campaign.standardRevision && it.hash == campaign.standardHash
        } ?: return false
        val seedScan = standardsStore.scans().singleOrNull { it.scanId == campaign.seedScanId && it.hash == campaign.seedScanHash }
            ?: return false
        val targetScope = seedScan.effectiveStandard?.targetScope ?: StandardPolicyScope(STANDARD_SCOPE_PROJECT, campaign.projectId)
        val authorityHash = standardsService.currentAuthorityHash(campaign.projectId, standard, targetScope) ?: return false
        val currentPolicyAuthorityHash = authorityHash.takeUnless { it == campaign.standardHash }
        return view.evaluations.lastOrNull()?.policyAuthorityHash != currentPolicyAuthorityHash
    }

    private fun progressView(campaign: RemediationCampaign): RemediationCampaignView {
        val view = views(campaign.projectId).single { it.campaign.campaignId == campaign.campaignId }
        if (view.evaluations.isNotEmpty()) return view
        val linked = campaignWorkItemIds(campaign)
        val runs = workspace.snapshot(MESSAGE_READY).workflowRuns.filter { it.context.workItemId in linked }
        val state = when {
            runs.any { it.state == RUN_STATE_DONE } -> CAMPAIGN_VERIFYING
            runs.isNotEmpty() -> CAMPAIGN_IN_PROGRESS
            else -> CAMPAIGN_ADMITTED
        }
        return view.copy(state = state)
    }

    private fun compileNextSlice(campaign: RemediationCampaign): String? {
        val source = workSource(campaign)
        val scan = source.scan
        val entityByNode = source.entityByNode
        val entities = (0 until workspace.entityCount).map(workspace::entityAt).associateBy { it.id }
        val leafNodes = source.backlog.filter { node -> node.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) }
        val snapshot = workspace.snapshot(MESSAGE_READY)
        val linkedEntityIds = leafNodes.mapNotNull { entityByNode[it.nodeId] }.toSet()
        val linkedRuns = snapshot.workflowRuns.filter { it.context.workItemId in linkedEntityIds }
        val promotedRunIds = company.projectView(campaign.projectId).promotions.mapTo(hashSetOf()) { it.runId }
        if (linkedRuns.any { it.runId !in promotedRunIds }) return null
        val nextNode = leafNodes.firstOrNull { node ->
            val entityId = requireNotNull(entityByNode[node.nodeId])
            snapshot.workDefinitions.none { it.workItemId == entityId }
        } ?: return null
        val entity = requireNotNull(entities[requireNotNull(entityByNode[nextNode.nodeId])])
        val findings = scan.findings.filter { it.findingId in nextNode.findingIds }
        val verification = (nextNode.verificationCommands + findings.flatMap { it.verificationCommands }).firstOrNull()
            ?: return "The next remediation slice has no admitted verification command."
        ensureGovernance(campaign.projectId)?.let { return it }
        val story = requireNotNull(entities[entity.parentId])
        val epic = requireNotNull(entities[story.parentId])
        listOf(epic, story, entity).forEach { workItem ->
            ensureDesign(campaign, workItem, findings, verification)?.let { return it }
        }
        val acceptance = findings.flatMap { it.acceptanceCriteria }.distinct().ifEmpty { nextNode.acceptanceCriteria }
        val definition = WorkDefinitionSubmission(
            requestedOutcome = nextNode.title,
            currentBehavior = findings.joinToString(" ") { it.summary },
            requiredBehavior = acceptance.joinToString(" ").ifBlank { nextNode.description },
            scope = findings.flatMap { it.affectedPaths }.distinct().ifEmpty { listOf("repository") },
            nonGoals = listOf("Do not change behavior outside the admitted conformance findings."),
            constraints = listOf("Preserve standard ${campaign.standardHash} and seed scan ${campaign.seedScanHash}."),
            acceptanceCriteria = acceptance.ifEmpty { listOf(nextNode.description) }.map { AcceptanceCriterion(it, verification) },
            reproduction = if (entity.type == ENTITY_BUG) findings.joinToString(" ") { it.summary } else "",
            regressionCriterion = if (entity.type == ENTITY_BUG) acceptance.firstOrNull() ?: nextNode.description else "",
        )
        if (workspace.submitWorkDefinition(entity.id, definition).status != WorkDefinitionStatus.RECORDED) {
            return "The next remediation work definition was rejected."
        }
        ensurePlans(campaign, source.backlog, entityByNode, entities)?.let { return it }
        if (company.compileRules(campaign.projectId).status !in setOf(
                CompanyMutationStatus.RECORDED,
                CompanyMutationStatus.PROJECT_NOT_READY,
            )) return "The company architecture rules could not be compiled."
        return null
    }

    private fun ensureGovernance(projectId: Int): String? {
        if (workspace.snapshot(MESSAGE_READY).projectGovernance.any { it.activation.projectId == projectId }) return null
        return when (workspace.activateDesignGovernance(projectId).status) {
            DesignGovernanceStatus.RECORDED,
            DesignGovernanceStatus.GOVERNANCE_ALREADY_ACTIVE -> null
            else -> "Project design governance could not be activated."
        }
    }

    private fun ensureDesign(
        campaign: RemediationCampaign,
        workItem: WorkspaceEntity,
        findings: List<ConformanceFinding>,
        verification: String,
    ): String? {
        val snapshot = workspace.snapshot(MESSAGE_READY)
        if (snapshot.designRevisions.any { it.design.workItemId == workItem.id && it.status == DESIGN_STATUS_ADMITTED }) return null
        val parentRequirementIds = when (workItem.type) {
            ENTITY_EPIC -> emptyList()
            else -> snapshot.designRevisions.lastOrNull { it.design.workItemId == workItem.parentId && it.status == DESIGN_STATUS_ADMITTED }
                ?.design?.content?.requirements?.map { it.requirementId }.orEmpty()
        }
        val requirementId = "CAMPAIGN_${campaign.campaignId}_${workItem.id}"
        val acceptance = findings.flatMap { it.acceptanceCriteria }.distinct().firstOrNull()
            ?: "The admitted conformance finding is resolved."
        val submission = DesignSubmission(
            workItemId = workItem.id,
            title = "Governed remediation ${workItem.title}",
            problem = findings.joinToString(" ") { it.summary }.ifBlank { workItem.content },
            scope = findings.flatMap { it.affectedPaths }.distinct().ifEmpty { listOf("repository") },
            assumptions = listOf("The seed scan evidence remains authoritative until a promoted follow-up scan supersedes it."),
            constraints = listOf("Remain within campaign ${campaign.campaignId} and standard ${campaign.standardRevision}."),
            alternatives = listOf("Escalate unresolved or conflicting evidence to the Architect."),
            architecture = listOf("Use the existing repository implementation and verification boundaries."),
            failureModes = listOf("The promoted revision remains nonconforming or regresses another seeded practice."),
            qualityAttributes = listOf("Evidence-bound", "Recoverable", "Deterministically verified"),
            securityImpact = "No new secret authority; preserve existing security boundaries.",
            complianceImpact = "Conformance is evaluated against the pinned engineering standard revision.",
            requirements = listOf(
                RequirementSubmission(
                    requirementId,
                    acceptance,
                    parentRequirementIds,
                    listOf(DesignCriterionSubmission(acceptance, verification, CRITERION_AUTOMATED)),
                )
            ),
        )
        val candidate = workspace.recordDesignCandidate(submission)
        val design = candidate.design ?: return "The remediation design for ${workItem.id} was rejected with ${candidate.status}."
        return if (workspace.admitDesign(design.designId).status == DesignGovernanceStatus.ADMITTED) null
        else "The remediation design for ${workItem.id} was not admitted."
    }

    private fun ensurePlans(
        campaign: RemediationCampaign,
        backlog: List<BacklogProposalNode>,
        entityByNode: Map<String, Int>,
        entities: Map<Int, WorkspaceEntity>,
    ): String? {
        val scopes = backlog.filter { it.type in setOf(BACKLOG_EPIC, BACKLOG_STORY) }
        scopes.forEach { scopeNode ->
            val scopeId = requireNotNull(entityByNode[scopeNode.nodeId])
            if (workspace.snapshot(MESSAGE_READY).stagedPlans.any { it.plan.scopeId == scopeId }) return@forEach
            val children = backlog.filter { it.parentNodeId == scopeNode.nodeId }
            val stages = children.mapIndexed { index, child ->
                val childId = requireNotNull(entityByNode[child.nodeId])
                StagedPlanStageSubmission(
                    stageId = "campaign-${campaign.campaignId}-${index + 1}",
                    title = child.title,
                    executionWorkflowId = "sequential-delivery-v1",
                    nodes = listOf(StagedPlanNodeSubmission(child.nodeId.lowercase(), childId)),
                )
            }
            require(children.mapNotNull { entityByNode[it.nodeId] }.all(entities::containsKey))
            val result = workspace.acceptStagedPlan(
                StagedDeliveryPlanSubmission(scopeId, "Conformance campaign ${campaign.campaignId}", stages)
            )
            if (result.status != StagedPlanStatus.ACCEPTED) return "The remediation circuit for ${scopeNode.nodeId} was rejected with ${result.status}."
        }
        return null
    }

    private fun promoteAcceptedSlice(campaign: RemediationCampaign): String? {
        val workItems = campaignWorkItemIds(campaign)
        val project = company.projectView(campaign.projectId)
        val promotedRunIds = project.promotions.mapTo(hashSetOf()) { it.runId }
        val acceptance = project.acceptances.lastOrNull { it.runId !in promotedRunIds && runWorkItemId(it.runId) in workItems }
            ?: return null
        return when (company.promote(acceptance.runId).status) {
            CompanyMutationStatus.RECORDED -> null
            CompanyMutationStatus.EVIDENCE_STALE -> "The accepted remediation candidate is stale and requires a new execution slice."
            else -> "The accepted remediation candidate could not be promoted."
        }
    }

    private fun runWorkItemId(runId: Long): Int? = workspace.snapshot(MESSAGE_READY).workflowRuns
        .singleOrNull { it.runId == runId }?.context?.workItemId

    private fun campaignWorkItemIds(campaign: RemediationCampaign): Set<Int> {
        val workItemTypes = setOf(com.orchard.backend.workspace.ENTITY_TASK, com.orchard.backend.workspace.ENTITY_BUG)
        return campaign.links.flatMap { it.admittedEntityIds }.filterTo(linkedSetOf()) { entityId ->
            (0 until workspace.entityCount).map(workspace::entityAt).any { it.id == entityId && it.type in workItemTypes }
        }
    }

    private fun hasUncompiledLeaves(campaign: RemediationCampaign): Boolean {
        val source = workSource(campaign)
        val defined = workspace.snapshot(MESSAGE_READY).workDefinitions.mapTo(hashSetOf()) { it.workItemId }
        return source.backlog.any {
            it.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) && source.entityByNode[it.nodeId] !in defined
        }
    }

    private fun workSource(campaign: RemediationCampaign): CampaignWorkSource {
        val scan = standardsStore.scans().single { it.scanId == campaign.seedScanId && it.hash == campaign.seedScanHash }
        val successor = campaign.successorSource
        if (successor != null) {
            val findingIdsByPractice = scan.findings.associate { it.practiceId to it.findingId }
            return CampaignWorkSource(
                scan = scan,
                backlog = successor.backlog.map { node ->
                    BacklogProposalNode(
                        nodeId = node.nodeId,
                        parentNodeId = node.parentNodeId,
                        type = node.type,
                        title = node.title,
                        description = node.description,
                        findingIds = node.practiceIds.map { requireNotNull(findingIdsByPractice[it]) },
                        acceptanceCriteria = node.acceptanceCriteria,
                        verificationCommands = node.verificationCommands,
                    )
                },
                entityByNode = successor.admittedNodes.associate { it.nodeId to it.entityId },
            )
        }
        val admission = standardsStore.admissions().single { it.admissionId == campaign.seedAdmissionId }
        return CampaignWorkSource(
            scan = scan,
            backlog = scan.proposedBacklog,
            entityByNode = if (admission.admittedNodes.isNotEmpty()) {
                admission.admittedNodes.associate { it.nodeId to it.entityId }
            } else scan.proposedBacklog.map { it.nodeId }.zip(admission.admittedEntityIds).toMap(),
        )
    }

    private fun actionable(finding: ConformanceFinding): Boolean = finding.disposition in setOf(
        CONFORMANCE_NONCONFORMING,
        CONFORMANCE_PARTIAL,
        CONFORMANCE_UNKNOWN,
        CONFORMANCE_CONFLICTING,
    )

    private companion object {
        val RESOLVED = setOf(CONFORMANCE_CONFORMING, CONFORMANCE_NOT_APPLICABLE, CONFORMANCE_EXCEPTION_ACTIVE)
    }

    private data class CampaignWorkSource(
        val scan: RepositoryConformanceScan,
        val backlog: List<BacklogProposalNode>,
        val entityByNode: Map<String, Int>,
    )
}
