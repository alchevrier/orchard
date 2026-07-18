package com.orchard.backend.company

import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelCapabilityProfile
import com.orchard.backend.workspace.ProjectGenesisRevision
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.TransientRepositoryBindingStore
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceStore
import kotlinx.serialization.Serializable

const val PHASE_PORTFOLIO_PLANNING = "PORTFOLIO_PLANNING"
const val PHASE_DELIVERY_PLANNING = "DELIVERY_PLANNING"
const val PHASE_STAFFING = "STAFFING"
const val PHASE_IMPLEMENTATION = "IMPLEMENTATION"
const val PHASE_VERIFICATION = "VERIFICATION"
const val PHASE_AUDIT = "AUDIT"
const val PHASE_ARCHITECT_REVIEW = "ARCHITECT_REVIEW"
const val PHASE_LOCAL_PROMOTION = "LOCAL_PROMOTION"
const val PHASE_OBSERVATION = "OBSERVATION"

enum class CompanyMutationStatus {
    RECORDED,
    PROJECT_NOT_READY,
    RUN_NOT_FOUND,
    CANDIDATE_NOT_FOUND,
    RULES_NOT_COMPILED,
    ASSIGNMENT_NOT_FOUND,
    INDEPENDENCE_REQUIRED,
    EVIDENCE_STALE,
    AUDIT_VIOLATION,
    AUDIT_INCOMPLETE,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class InstalledStaff(
    val bindingFingerprint: String,
    val bindingId: String,
    val provider: String,
    val model: String,
    val roles: List<String>,
    val sampleCount: Int,
    val schemaValidityRate: Double,
    val confidence: Double,
)

@Serializable
data class AccountabilityLink(
    val from: String,
    val relation: String,
    val to: String,
)

@Serializable
data class CompanyProjectView(
    val projectId: Int,
    val phase: String,
    val health: String,
    val ruleSet: ArchitectureRuleSet? = null,
    val staff: List<InstalledStaff> = emptyList(),
    val assignments: List<StaffAssignment> = emptyList(),
    val audits: List<AuditJudgment> = emptyList(),
    val escalations: List<StaffEscalation> = emptyList(),
    val acceptances: List<CompanyAcceptance> = emptyList(),
    val promotions: List<LocalPromotion> = emptyList(),
    val accountability: List<AccountabilityLink> = emptyList(),
    val requiredDecision: String? = null,
)

data class CompanyMutationResult(
    val status: CompanyMutationStatus,
    val project: CompanyProjectView? = null,
)

class CompanyControlService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val store: CompanyControlStore = TransientCompanyControlStore(),
    private val repositories: RepositoryBindingStore = TransientRepositoryBindingStore,
) {
    init {
        store.loadEvents()
    }

    fun installedStaff(role: String? = null): List<InstalledStaff> {
        val executionProfileId = when (role) {
            ROLE_ANALYST_DESIGNER -> com.orchard.backend.vector.DefaultModelExecutionProfiles.broadRepositoryAnalysis.id
            ROLE_IMPLEMENTER -> com.orchard.backend.vector.DefaultModelExecutionProfiles.boundedCodingPatch.id
            ROLE_ARCHITECTURE_AUDITOR,
            ROLE_QUALITY_AUDITOR -> com.orchard.backend.vector.DefaultModelExecutionProfiles.boundedIndependentAudit.id
            else -> null
        }
        val profiles = workspace.modelProfiles()
            .filter { executionProfileId == null || it.executionProfileId == executionProfileId }
            .associateBy(ModelCapabilityProfile::bindingFingerprint)
        val executionProfile = executionProfileId?.let(com.orchard.backend.vector.DefaultModelExecutionProfiles::resolve)
        return modelProviders.filter { provider ->
            executionProfile == null || provider.bindingProfile().let { binding ->
                binding.contextWindowTokens >= executionProfile.inputBudgetTokens + executionProfile.outputBudgetTokens &&
                    binding.capabilities.containsAll(executionProfile.requiredCapabilities)
            }
        }.map { provider ->
            val binding = provider.bindingProfile()
            val fingerprint = modelBindingFingerprint(binding)
            val profile = profiles[fingerprint]
            InstalledStaff(
                bindingFingerprint = fingerprint,
                bindingId = binding.bindingId,
                provider = binding.provider,
                model = binding.model,
                roles = listOf(ROLE_ANALYST_DESIGNER, ROLE_IMPLEMENTER, ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR),
                sampleCount = profile?.sampleCount ?: 0,
                schemaValidityRate = profile?.schemaValidityRate ?: 0.0,
                confidence = profile?.confidence ?: 0.0,
            )
        }.sortedWith(
            compareByDescending<InstalledStaff> { it.confidence }
                .thenByDescending { it.schemaValidityRate }
                .thenBy { it.bindingId }
        )
    }

    @Synchronized
    fun compileRules(projectId: Int): CompanyMutationResult {
        val genesis = readyGenesis(projectId) ?: return CompanyMutationResult(CompanyMutationStatus.PROJECT_NOT_READY)
        val events = store.loadEvents()
        val current = events.mapNotNull { it.ruleSet }.lastOrNull { it.projectId == projectId }
        if (current?.genesisHash == genesis.hash) return CompanyMutationResult(
            CompanyMutationStatus.RECORDED,
            projectView(projectId),
        )
        val rules = buildList {
            genesis.components.forEach { component ->
                val draft = ArchitectureRule(
                    ruleId = "COMPONENT_${component.componentId.uppercase()}",
                    projectId = projectId,
                    genesisRevision = genesis.revision,
                    genesisHash = genesis.hash,
                    componentIds = listOf(component.componentId),
                    kind = "COMPONENT_BOUNDARY",
                    statement = "${component.name}: ${component.responsibility}",
                    repositoryPaths = component.repositoryPaths,
                    severity = RISK_HIGH,
                    requiresIndependentAudit = true,
                    hash = "",
                )
                add(draft.copy(hash = companyRecordHash(draft.toString())))
                component.dependsOn.forEach { dependency ->
                    val dependencyDraft = ArchitectureRule(
                        ruleId = "DEPENDENCY_${component.componentId.uppercase()}_${dependency.uppercase()}",
                        projectId = projectId,
                        genesisRevision = genesis.revision,
                        genesisHash = genesis.hash,
                        componentIds = listOf(component.componentId, dependency),
                        kind = "ALLOWED_DEPENDENCY",
                        statement = "${component.componentId} may depend on $dependency.",
                        repositoryPaths = component.repositoryPaths,
                        severity = RISK_HIGH,
                        requiresIndependentAudit = true,
                        hash = "",
                    )
                    add(dependencyDraft.copy(hash = companyRecordHash(dependencyDraft.toString())))
                }
            }
            genesis.decisions.forEach { decision ->
                val draft = ArchitectureRule(
                    ruleId = "DECISION_${decision.decisionId.uppercase()}",
                    projectId = projectId,
                    genesisRevision = genesis.revision,
                    genesisHash = genesis.hash,
                    sourceDecisionId = decision.decisionId,
                    componentIds = decision.componentIds,
                    kind = "ARCHITECTURE_DECISION",
                    statement = decision.decision,
                    repositoryPaths = genesis.components.filter { it.componentId in decision.componentIds }
                        .flatMap { it.repositoryPaths }.distinct().sorted(),
                    severity = RISK_CRITICAL,
                    requiresIndependentAudit = true,
                    hash = "",
                )
                add(draft.copy(hash = companyRecordHash(draft.toString())))
            }
            genesis.blueprint?.verificationCommands?.forEachIndexed { index, command ->
                val draft = ArchitectureRule(
                    ruleId = "VERIFICATION_${index + 1}",
                    projectId = projectId,
                    genesisRevision = genesis.revision,
                    genesisHash = genesis.hash,
                    kind = "VERIFICATION_COMMAND",
                    statement = command,
                    severity = RISK_HIGH,
                    requiresIndependentAudit = false,
                    hash = "",
                )
                add(draft.copy(hash = companyRecordHash(draft.toString())))
            }
        }
        if (rules.isEmpty()) return CompanyMutationResult(CompanyMutationStatus.PROJECT_NOT_READY)
        val draft = ArchitectureRuleSet(
            ruleSetId = events.size + 1L,
            projectId = projectId,
            revision = (current?.revision ?: 0) + 1,
            genesisRevision = genesis.revision,
            genesisHash = genesis.hash,
            rules = rules,
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.ruleSetId, ruleSet = draft.copy(hash = companyRecordHash(draft.toString()))),
            projectId,
        )
    }

    @Synchronized
    fun assign(runId: Long, role: String, risk: String): CompanyMutationResult {
        val run = run(runId) ?: return CompanyMutationResult(CompanyMutationStatus.RUN_NOT_FOUND)
        val events = store.loadEvents()
        val existing = events.mapNotNull { it.assignment }.lastOrNull { it.runId == runId && it.role == role }
        if (existing != null && events.mapNotNull { it.escalation }.none { it.fromAssignmentId == existing.assignmentId }) {
            return CompanyMutationResult(CompanyMutationStatus.RECORDED, projectView(run.context.projectId))
        }
        val escalatedBindings = events.mapNotNull { event ->
            event.escalation?.takeIf { it.runId == runId && it.requiredRole == role }?.let { escalation ->
                events.mapNotNull { it.assignment }.singleOrNull {
                    it.assignmentId == escalation.fromAssignmentId
                }?.bindingFingerprint
            }
        }.toSet()
        val ranked = installedStaff(role)
        val staff = ranked.firstOrNull { it.bindingFingerprint !in escalatedBindings } ?: ranked.firstOrNull()
            ?: return CompanyMutationResult(CompanyMutationStatus.ASSIGNMENT_NOT_FOUND)
        val implementer = events.mapNotNull { it.assignment }.lastOrNull { it.runId == runId && it.role == ROLE_IMPLEMENTER }
        val draft = StaffAssignment(
            assignmentId = events.size + 1L,
            projectId = run.context.projectId,
            runId = runId,
            role = role,
            risk = risk,
            bindingFingerprint = staff.bindingFingerprint,
            model = staff.model,
            rationale = if (staff.sampleCount == 0) {
                "Selected compatible installed staff with no comparable local outcomes; confidence is provisional."
            } else {
                "Selected the highest-confidence compatible staff from ${staff.sampleCount} comparable local executions."
            },
            evidenceSampleCount = staff.sampleCount,
            confidence = staff.confidence,
            independentFromAssignmentId = implementer?.assignmentId?.takeIf { role != ROLE_IMPLEMENTER },
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.assignmentId, assignment = draft.copy(hash = companyRecordHash(draft.toString()))),
            run.context.projectId,
        )
    }

    fun assignment(runId: Long, role: String): StaffAssignment? {
        val events = store.loadEvents()
        val escalated = events.mapNotNull { it.escalation }.mapTo(hashSetOf()) { it.fromAssignmentId }
        return events.mapNotNull { it.assignment }
            .lastOrNull { it.runId == runId && it.role == role && it.assignmentId !in escalated }
    }

    fun provider(assignment: StaffAssignment): ModelProvider? = modelProviders.singleOrNull {
        modelBindingFingerprint(it.bindingProfile()) == assignment.bindingFingerprint
    }

    @Synchronized
    fun escalate(runId: Long, role: String, reason: String): CompanyMutationResult {
        val run = run(runId) ?: return CompanyMutationResult(CompanyMutationStatus.RUN_NOT_FOUND)
        val assignment = assignment(runId, role) ?: return CompanyMutationResult(CompanyMutationStatus.ASSIGNMENT_NOT_FOUND)
        val events = store.loadEvents()
        val draft = StaffEscalation(
            escalationId = events.size + 1L,
            projectId = run.context.projectId,
            runId = runId,
            fromAssignmentId = assignment.assignmentId,
            requiredRole = role,
            reason = reason,
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.escalationId, escalation = draft.copy(hash = companyRecordHash(draft.toString()))),
            run.context.projectId,
        )
    }

    @Synchronized
    fun recordAudit(
        runId: Long,
        role: String,
        candidateRevision: String,
        candidateDiffHash: String,
        findings: List<AuditFinding>,
        rationale: String,
    ): CompanyMutationResult {
        val run = run(runId) ?: return CompanyMutationResult(CompanyMutationStatus.RUN_NOT_FOUND)
        val genesis = readyGenesis(run.context.projectId) ?: return CompanyMutationResult(CompanyMutationStatus.PROJECT_NOT_READY)
        val events = store.loadEvents()
        val ruleSet = events.mapNotNull { it.ruleSet }.lastOrNull { it.projectId == run.context.projectId }
            ?: return CompanyMutationResult(CompanyMutationStatus.RULES_NOT_COMPILED)
        if (ruleSet.genesisHash != genesis.hash) return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        val assignment = assignment(runId, role) ?: return CompanyMutationResult(CompanyMutationStatus.ASSIGNMENT_NOT_FOUND)
        if (role == ROLE_IMPLEMENTER || assignment.independentFromAssignmentId == null) {
            return CompanyMutationResult(CompanyMutationStatus.INDEPENDENCE_REQUIRED)
        }
        val candidateEvidence = run.evidence.filter { it.revision == candidateRevision }
        val sourceDiff = candidateEvidence.singleOrNull {
            it.kind == "SOURCE_DIFF" && it.passed && it.canonicalOutput && it.outputHash == candidateDiffHash
        } ?: return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        val evidenceIds = candidateEvidence.mapTo(hashSetOf()) { it.evidenceId }
        if (findings.any { it.evidenceIds.isEmpty() || it.evidenceIds.any { evidenceId -> evidenceId !in evidenceIds } }) {
            return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        }
        if (findings.none { sourceDiff.evidenceId in it.evidenceIds }) {
            return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        }
        val status = when {
            findings.any { it.status == AUDIT_EVIDENCE_STALE } -> AUDIT_EVIDENCE_STALE
            findings.any { it.status == AUDIT_VIOLATION } -> AUDIT_VIOLATION
            findings.isNotEmpty() && findings.all { it.status == AUDIT_CONFORMING } -> AUDIT_CONFORMING
            else -> AUDIT_NOT_ASSESSED
        }
        val draft = AuditJudgment(
            auditId = events.size + 1L,
            projectId = run.context.projectId,
            runId = runId,
            assignmentId = assignment.assignmentId,
            role = role,
            candidateRevision = candidateRevision,
            candidateDiffHash = candidateDiffHash,
            genesisHash = genesis.hash,
            ruleSetHash = ruleSet.hash,
            findings = findings,
            status = status,
            rationale = rationale,
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.auditId, audit = draft.copy(hash = companyRecordHash(draft.toString()))),
            run.context.projectId,
        ).copy(status = when (status) {
            AUDIT_CONFORMING -> CompanyMutationStatus.RECORDED
            AUDIT_EVIDENCE_STALE -> CompanyMutationStatus.EVIDENCE_STALE
            AUDIT_VIOLATION -> CompanyMutationStatus.AUDIT_VIOLATION
            else -> CompanyMutationStatus.AUDIT_INCOMPLETE
        })
    }

    @Synchronized
    fun accept(runId: Long, candidateRevision: String, candidateDiffHash: String, acceptedBy: String): CompanyMutationResult {
        val run = run(runId) ?: return CompanyMutationResult(CompanyMutationStatus.RUN_NOT_FOUND)
        val genesis = readyGenesis(run.context.projectId) ?: return CompanyMutationResult(CompanyMutationStatus.PROJECT_NOT_READY)
        val events = store.loadEvents()
        val requiredRoles = setOf(ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR)
        val audits = events.mapNotNull { it.audit }.filter {
            it.runId == runId && it.candidateRevision == candidateRevision && it.candidateDiffHash == candidateDiffHash &&
                it.genesisHash == genesis.hash && it.status == AUDIT_CONFORMING
        }.groupBy { it.role }.mapValues { (_, values) -> values.maxBy { it.auditId } }
        if (events.mapNotNull { it.audit }.any {
            it.runId == runId && it.candidateRevision == candidateRevision &&
                it.candidateDiffHash == candidateDiffHash && it.status != AUDIT_CONFORMING
            }
        ) return CompanyMutationResult(CompanyMutationStatus.AUDIT_VIOLATION)
        if (!audits.keys.containsAll(requiredRoles)) return CompanyMutationResult(CompanyMutationStatus.AUDIT_INCOMPLETE)
        val draft = CompanyAcceptance(
            acceptanceId = events.size + 1L,
            projectId = run.context.projectId,
            runId = runId,
            candidateRevision = candidateRevision,
            candidateDiffHash = candidateDiffHash,
            genesisHash = genesis.hash,
            auditIds = requiredRoles.map { requireNotNull(audits[it]).auditId }.sorted(),
            acceptedBy = acceptedBy,
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.acceptanceId, acceptance = draft.copy(hash = companyRecordHash(draft.toString()))),
            run.context.projectId,
        )
    }

    @Synchronized
    fun promote(runId: Long): CompanyMutationResult {
        val run = run(runId) ?: return CompanyMutationResult(CompanyMutationStatus.RUN_NOT_FOUND)
        val events = store.loadEvents()
        val acceptance = events.mapNotNull { it.acceptance }.lastOrNull { it.runId == runId }
            ?: return CompanyMutationResult(CompanyMutationStatus.AUDIT_INCOMPLETE)
        if (events.mapNotNull { it.promotion }.any { it.acceptanceId == acceptance.acceptanceId }) {
            return CompanyMutationResult(CompanyMutationStatus.RECORDED, projectView(run.context.projectId))
        }
        val genesis = readyGenesis(run.context.projectId) ?: return CompanyMutationResult(CompanyMutationStatus.PROJECT_NOT_READY)
        val ruleSet = events.mapNotNull { it.ruleSet }.lastOrNull { it.projectId == run.context.projectId }
            ?: return CompanyMutationResult(CompanyMutationStatus.RULES_NOT_COMPILED)
        if (acceptance.genesisHash != genesis.hash || ruleSet.genesisHash != genesis.hash) {
            return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        }
        val promoted = runCatching {
            repositories.promoteLocal(
                run.context.projectId,
                run.context.repository.commitHash,
                acceptance.candidateRevision,
                acceptance.candidateDiffHash,
            )
        }.getOrNull() ?: return CompanyMutationResult(CompanyMutationStatus.EVIDENCE_STALE)
        val draft = LocalPromotion(
            promotionId = events.size + 1L,
            projectId = run.context.projectId,
            runId = runId,
            acceptanceId = acceptance.acceptanceId,
            baseRevision = promoted.baseRevision,
            candidateRevision = promoted.candidateRevision,
            destinationRevision = promoted.destinationRevision,
            hash = "",
        )
        return append(
            CompanyControlEvent(draft.promotionId, promotion = draft.copy(hash = companyRecordHash(draft.toString()))),
            run.context.projectId,
        )
    }

    fun projectViews(): List<CompanyProjectView> = workspace.snapshot(MESSAGE_READY).projectGenesis
        .map { it.projectId }
        .distinct()
        .sorted()
        .map(::projectView)

    fun projectView(projectId: Int): CompanyProjectView {
        val snapshot = workspace.snapshot(MESSAGE_READY)
        val genesis = snapshot.projectGenesis.singleOrNull { it.projectId == projectId }
        val events = companyControlView(store.loadEvents())
        val assignments = events.assignments.filter { it.projectId == projectId }
        val audits = events.audits.filter { it.projectId == projectId }
        val escalations = events.escalations.filter { it.projectId == projectId }
        val acceptances = events.acceptances.filter { it.projectId == projectId }
        val promotions = events.promotions.filter { it.projectId == projectId }
        val ruleSet = events.ruleSets.lastOrNull { it.projectId == projectId }
        val runs = snapshot.workflowRuns.filter { it.context.projectId == projectId }
        val phase = when {
            genesis?.phase != GENESIS_READY -> PHASE_PORTFOLIO_PLANNING
            ruleSet == null || snapshot.stagedPlans.none { plan -> runs.any { it.context.workItemId in plan.nodes.map { node -> node.node.workItemId } } } -> PHASE_DELIVERY_PLANNING
            assignments.isEmpty() -> PHASE_STAFFING
            promotions.isNotEmpty() -> PHASE_OBSERVATION
            acceptances.isNotEmpty() -> PHASE_LOCAL_PROMOTION
            audits.any { it.status != AUDIT_CONFORMING } -> PHASE_ARCHITECT_REVIEW
            audits.isNotEmpty() -> PHASE_ARCHITECT_REVIEW
            runs.any { it.evidence.isNotEmpty() } -> PHASE_AUDIT
            runs.isNotEmpty() -> PHASE_IMPLEMENTATION
            else -> PHASE_STAFFING
        }
        val requiredDecision = when {
            audits.any { it.status == AUDIT_VIOLATION } -> "Review the architectural violation and admit repair or exception."
            audits.any { it.status == AUDIT_EVIDENCE_STALE } -> "Regenerate evidence against the current candidate revision."
            phase == PHASE_LOCAL_PROMOTION -> "Promote the accepted candidate into the local destination branch."
            else -> null
        }
        return CompanyProjectView(
            projectId = projectId,
            phase = phase,
            health = when {
                audits.any { it.status == AUDIT_VIOLATION } -> "AT_RISK"
                requiredDecision != null -> "DECISION_REQUIRED"
                genesis?.phase == GENESIS_READY -> "ACTIVE"
                else -> "FORMING"
            },
            ruleSet = ruleSet,
            staff = installedStaff(),
            assignments = assignments,
            audits = audits,
            escalations = escalations,
            acceptances = acceptances,
            promotions = promotions,
            accountability = accountability(projectId, ruleSet, runs, assignments, audits, acceptances, promotions),
            requiredDecision = requiredDecision,
        )
    }

    private fun accountability(
        projectId: Int,
        ruleSet: ArchitectureRuleSet?,
        runs: List<WorkflowRunView>,
        assignments: List<StaffAssignment>,
        audits: List<AuditJudgment>,
        acceptances: List<CompanyAcceptance>,
        promotions: List<LocalPromotion>,
    ): List<AccountabilityLink> = buildList {
        ruleSet?.let { rules ->
            add(AccountabilityLink("project:$projectId", "GOVERNED_BY", "rules:${rules.hash}"))
            rules.rules.forEach { rule -> add(AccountabilityLink("rules:${rules.hash}", "CONTAINS", "rule:${rule.ruleId}")) }
        }
        runs.forEach { run -> add(AccountabilityLink("project:$projectId", "DELIVERS", "run:${run.runId}")) }
        assignments.forEach { assignment ->
            add(AccountabilityLink("run:${assignment.runId}", "ASSIGNED_TO", "staff:${assignment.bindingFingerprint}:${assignment.role}"))
        }
        audits.forEach { audit ->
            add(AccountabilityLink("revision:${audit.candidateRevision}", "AUDITED_BY", "audit:${audit.auditId}"))
        }
        acceptances.forEach { acceptance ->
            acceptance.auditIds.forEach { auditId -> add(AccountabilityLink("audit:$auditId", "SUPPORTS", "acceptance:${acceptance.acceptanceId}")) }
        }
        promotions.forEach { promotion ->
            add(AccountabilityLink("acceptance:${promotion.acceptanceId}", "PROMOTED_AS", "revision:${promotion.destinationRevision}"))
        }
    }

    private fun readyGenesis(projectId: Int): ProjectGenesisRevision? = workspace.snapshot(MESSAGE_READY).projectGenesis
        .singleOrNull { it.projectId == projectId && it.phase == GENESIS_READY && it.revision?.admitted == true }
        ?.revision

    private fun run(runId: Long): WorkflowRunView? = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == runId }

    private fun append(event: CompanyControlEvent, projectId: Int): CompanyMutationResult = try {
        store.append(event)
        CompanyMutationResult(CompanyMutationStatus.RECORDED, projectView(projectId))
    } catch (_: Exception) {
        CompanyMutationResult(CompanyMutationStatus.STORAGE_UNAVAILABLE)
    }
}