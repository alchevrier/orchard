package com.orchard.backend.company

import com.orchard.backend.agent.CODING_EXECUTION_COMPLETED
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ModelWorkPriority
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileSettingsStore
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.effectiveModelExecutionProfile
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CompanyAuditTickStatus {
    IDLE,
    BUSY,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_JUDGMENT,
    RETRY_AUTHORIZED,
    VIOLATION,
    ACCEPTED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CompanyAuditTickResult(
    val status: CompanyAuditTickStatus,
    val runId: Long? = null,
    val role: String? = null,
    val mutationStatus: CompanyMutationStatus? = null,
    val diagnostic: String = "",
)

@Serializable
private data class AuditEnvelope(
    val role: String,
    val candidateRevision: String,
    val changedPaths: List<String>,
    val rules: List<ArchitectureRule>,
    val objectiveEvidence: List<AuditEvidence>,
    val priorRejectedAuditDiagnostic: String? = null,
    val repositoryContext: com.orchard.backend.agent.CodingRepositoryContext,
)

@Serializable
private data class AuditEvidence(
    val evidenceId: Long,
    val kind: String,
    val revision: String,
    val passed: Boolean,
    val summary: String,
)

@Serializable
private data class AuditProposal(
    val findings: List<AuditFindingProposal>,
    val rationale: String,
)

@Serializable
private data class AuditFindingProposal(
    val ruleId: String,
    val status: String,
    val summary: String,
)

class CompanyAuditService(
    private val workspace: WorkspaceStore,
    private val codingWorker: CodingWorkerService,
    private val company: CompanyControlService,
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
    private val attemptStore: CompanyAuditAttemptStore = TransientCompanyAuditAttemptStore(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun tick(): CompanyAuditTickResult {
        val runId = eligibleRunIds().firstOrNull() ?: return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE)
        return tick(runId)
    }

    fun eligibleRunIds(): List<Long> = candidateExecutions(company.projectViews()).map { it.claim.runId }

    fun attempts(): List<CompanyAuditAttempt> = attemptStore.load()

    @Synchronized
    fun authorizeRetry(runId: Long): CompanyAuditTickResult {
        val completed = candidateExecutions(company.projectViews()).singleOrNull { it.claim.runId == runId }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE, runId, diagnostic = "No auditable candidate is awaiting correction.")
        val result = requireNotNull(completed.result)
        val revision = requireNotNull(result.revision)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == runId }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, runId)
        val sourceDiff = run.evidence.singleOrNull { it.kind == "SOURCE_DIFF" && it.revision == revision && it.passed }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, runId, diagnostic = "Candidate source diff is unavailable.")
        val project = company.projectView(run.context.projectId)
        val ruleSet = project.ruleSet
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, runId, diagnostic = "Compiled audit rules are unavailable.")
        val completedRoles = project.audits.filter {
            it.runId == runId && it.candidateRevision == revision && it.candidateDiffHash == sourceDiff.outputHash &&
                it.genesisHash == ruleSet.genesisHash && it.ruleSetHash == ruleSet.hash
        }.mapTo(hashSetOf()) { it.role }
        val role = listOf(ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR).firstOrNull { it !in completedRoles }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE, runId, diagnostic = "All required audit roles already completed.")
        val latest = runCatching {
            attemptStore.attemptsFor(runId, role, revision, sourceDiff.outputHash).lastOrNull()
        }.getOrElse { return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, runId, role) }
        if (latest?.state != AUDIT_ATTEMPT_BLOCKED) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE, runId, role, diagnostic = "The audit is not blocked for retry authority.")
        }
        val authorized = runCatching {
            attemptStore.appendNext { attemptId ->
                CompanyAuditAttempt(
                    attemptId,
                    runId,
                    role,
                    revision,
                    sourceDiff.outputHash,
                    AUDIT_ATTEMPT_RETRY_AUTHORIZED,
                    "An explicit audit correction was authorized for the rejected judgment.",
                )
            }
        }
        return if (authorized.isSuccess) {
            CompanyAuditTickResult(CompanyAuditTickStatus.RETRY_AUTHORIZED, runId, role, diagnostic = latest.diagnostic)
        } else {
            CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, runId, role)
        }
    }

    suspend fun tick(runId: Long): CompanyAuditTickResult {
        val mutex = runMutexes.computeIfAbsent(runId) { Mutex() }
        if (!mutex.tryLock()) return CompanyAuditTickResult(CompanyAuditTickStatus.BUSY)
        return try {
            executeTick(runId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeTick(runId: Long): CompanyAuditTickResult {
        val companyViews = company.projectViews()
        val completed = candidateExecutions(companyViews).singleOrNull { it.claim.runId == runId }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE)
        val result = requireNotNull(completed.result)
        val revision = requireNotNull(result.revision)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == completed.claim.runId }
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, completed.claim.runId)

        val sourceDiff = run.evidence.singleOrNull { it.kind == "SOURCE_DIFF" && it.revision == revision && it.passed }
            ?: return CompanyAuditTickResult(
                CompanyAuditTickStatus.INVALID_JUDGMENT,
                run.runId,
                diagnostic = "Candidate $revision has no unique passing source diff: ${run.evidence.filter { it.kind == "SOURCE_DIFF" }.joinToString { "${it.evidenceId}:${it.revision}:${it.passed}:${it.outputHash}" }}",
            )
        val project = company.projectView(run.context.projectId)
        val ruleSet = project.ruleSet ?: return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId)
        val completedRoles = project.audits.filter {
            it.runId == run.runId && it.candidateRevision == revision && it.candidateDiffHash == sourceDiff.outputHash &&
                it.genesisHash == ruleSet.genesisHash && it.ruleSetHash == ruleSet.hash
        }.mapTo(hashSetOf()) { it.role }
        val role = listOf(ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR).firstOrNull { it !in completedRoles }
        if (role == null) {
            val acceptance = company.accept(run.runId, revision, sourceDiff.outputHash, "ORCHARD_COMPANY_CIRCUIT")
            return CompanyAuditTickResult(
                if (acceptance.status == CompanyMutationStatus.RECORDED) CompanyAuditTickStatus.ACCEPTED
                else CompanyAuditTickStatus.STORAGE_UNAVAILABLE,
                run.runId,
            )
        }
        val attempts = runCatching {
            attemptStore.attemptsFor(run.runId, role, revision, sourceDiff.outputHash)
        }.getOrElse { return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role) }
        if (attempts.lastOrNull()?.state == AUDIT_ATTEMPT_BLOCKED) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE, run.runId, role)
        }
        val priorRejectedAuditDiagnostic = attempts.takeIf {
            it.lastOrNull()?.state == AUDIT_ATTEMPT_RETRY_AUTHORIZED
        }?.lastOrNull { it.state == AUDIT_ATTEMPT_BLOCKED }?.diagnostic
        if (company.assign(run.runId, role, RISK_HIGH).status != CompanyMutationStatus.RECORDED) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        }
        val assignment = company.assignment(run.runId, role)
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        val provider = company.provider(assignment)
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.MODEL_FAILED, run.runId, role)
        val defaultProfile = DefaultModelExecutionProfiles.boundedIndependentAudit
        val profileOverride = runCatching { profileSettingsStore.load() }.getOrElse {
            return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, profileOverride)
        if (provider.bindingProfile().contextWindowTokens < profile.inputBudgetTokens + profile.outputBudgetTokens) {
            return CompanyAuditTickResult(
                CompanyAuditTickStatus.MODEL_FAILED,
                run.runId,
                role,
                diagnostic = "Assigned audit model cannot satisfy the configured context aperture.",
            )
        }
        fun envelope(repositoryContext: CodingRepositoryContext) = AuditEnvelope(
            role = role,
            candidateRevision = revision,
            changedPaths = result.changedPaths,
            rules = ruleSet.rules,
            objectiveEvidence = run.evidence.filter { it.revision == revision }.map {
                AuditEvidence(it.evidenceId, it.kind, it.revision, it.passed, it.summary)
            },
            priorRejectedAuditDiagnostic = priorRejectedAuditDiagnostic,
            repositoryContext = repositoryContext,
        )
        fun prompt(repositoryContext: CodingRepositoryContext): String =
            "$SYSTEM_PROMPT\n\nAuthoritative audit envelope:\n${json.encodeToString(envelope(repositoryContext))}"
        val emptyContext = CodingRepositoryContext(emptyList(), 0)
        val contextBudget = profile.inputBudgetTokens - estimateModelTokens(prompt(emptyContext)) +
            estimateModelTokens(json.encodeToString(emptyContext))
        if (contextBudget <= 0) {
            return CompanyAuditTickResult(
                CompanyAuditTickStatus.INVALID_JUDGMENT,
                run.runId,
                role,
                diagnostic = "Audit envelope exceeds the configured model input budget.",
            )
        }
        val repositoryContext = runCatching {
            workspaceGateway.collectPlanContext(
                workspacePath = requireNotNull(run.context.workspaceReservation).path,
                repositoryRevision = revision,
                paths = result.changedPaths,
                query = ruleSet.rules.joinToString("\n") { it.statement },
                maxSerializedBytes = contextBudget,
            )
        }.getOrElse { return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Repository audit context is unavailable.") }
        if (repositoryContext.omittedFileCount != 0) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Candidate paths are missing from the pinned audit context.")
        }
        val envelope = envelope(repositoryContext)
        val prompt = prompt(repositoryContext)
        val promptTokens = estimateModelTokens(prompt)
        if (promptTokens > profile.inputBudgetTokens) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Audit context exceeds the model input budget.")
        }
        val admission = resourceController.acquire(provider.resourceDemand(profile, promptTokens), ModelWorkPriority.DELIVERY)
        val lease = admission.lease ?: return CompanyAuditTickResult(
            CompanyAuditTickStatus.RESOURCE_BLOCKED,
            run.runId,
            role,
            diagnostic = admission.evidence.reason,
        )
        if (priorRejectedAuditDiagnostic != null) {
            val consumed = runCatching {
                attemptStore.appendNext { attemptId ->
                    CompanyAuditAttempt(
                        attemptId,
                        run.runId,
                        role,
                        revision,
                        sourceDiff.outputHash,
                        AUDIT_ATTEMPT_RETRY_CONSUMED,
                        "The authorized audit correction was consumed.",
                    )
                }
            }
            if (consumed.isFailure) {
                lease.close()
                return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
            }
        }
        val startedAt = System.nanoTime()
        val generation: ModelGeneration = runCatching {
            lease.use {
                provider.executeCircuitSynthesis(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        }.getOrElse { failure ->
            val diagnostic = "Audit model generation failed: ${failure.message.orEmpty().take(512)}"
            if (!blockInvalidAttempt(run.runId, role, revision, sourceDiff.outputHash, diagnostic)) {
                return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
            }
            return CompanyAuditTickResult(CompanyAuditTickStatus.MODEL_FAILED, run.runId, role, diagnostic = diagnostic)
        }
        val proposal = runCatching { json.decodeFromString<AuditProposal>(generation.text) }.getOrNull()
        val execution = workspace.recordModelExecution(
            ModelExecutionObservationDraft(
                profile = profile,
                binding = provider.bindingProfile(),
                workflowStepId = "INDEPENDENT_AUDIT:$role",
                workItemId = run.context.workItemId,
                envelopeHash = sha256(json.encodeToString(envelope)),
                promptHash = sha256(prompt),
                outputHash = sha256(generation.text),
                inputTokens = generation.promptTokens,
                outputTokens = generation.completionTokens,
                latencyMillis = (System.nanoTime() - startedAt) / 1_000_000,
                schemaValid = proposal != null,
                resourceAdmission = admission.evidence,
            )
        ) ?: return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        if (proposal == null) {
            val diagnostic = "Audit output is not valid strict JSON."
            if (!blockInvalidAttempt(run.runId, role, revision, sourceDiff.outputHash, diagnostic)) {
                return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
            }
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = diagnostic)
        }
        invalidProposalDiagnostic(proposal, ruleSet)?.let { diagnostic ->
            if (!blockInvalidAttempt(run.runId, role, revision, sourceDiff.outputHash, diagnostic)) {
                return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
            }
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = diagnostic)
        }
        val candidateEvidenceIds = run.evidence.filter { it.revision == revision }
            .map { it.evidenceId }
            .distinct()
            .sorted()
        val findings = proposal.findings.map {
            AuditFinding(it.ruleId, it.status, it.summary, candidateEvidenceIds)
        }
        val recorded = company.recordAudit(
            run.runId,
            role,
            revision,
            sourceDiff.outputHash,
            findings,
            proposal.rationale,
        )
        if (recorded.status == CompanyMutationStatus.AUDIT_VIOLATION) {
            val reopened = workspace.requireAuditRepair(
                run.runId,
                "Independent $role audit found a revision-pinned architectural violation: ${proposal.rationale}",
            )
            if (reopened.status != com.orchard.backend.workspace.WorkflowMutationStatus.RECORDED) {
                return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
            }
            company.escalate(run.runId, ROLE_IMPLEMENTER, "Independent audit requires a repaired candidate revision.")
        }
        return CompanyAuditTickResult(
            when (recorded.status) {
                CompanyMutationStatus.RECORDED -> CompanyAuditTickStatus.ACCEPTED
                CompanyMutationStatus.AUDIT_VIOLATION -> CompanyAuditTickStatus.VIOLATION
                CompanyMutationStatus.STORAGE_UNAVAILABLE -> CompanyAuditTickStatus.STORAGE_UNAVAILABLE
                else -> CompanyAuditTickStatus.INVALID_JUDGMENT
            },
            run.runId,
            role,
            recorded.status,
            "Audit judgment resolved as ${recorded.status}.",
        )
    }

    private fun candidateExecutions(companyViews: List<CompanyProjectView>) =
        codingWorker.executions().asSequence()
            .filter { it.result?.status == CODING_EXECUTION_COMPLETED }
            .groupBy { it.claim.runId }
            .values
            .mapNotNull { executions -> executions.maxByOrNull { it.claim.executionId } }
            .sortedBy { it.claim.executionId }
            .filter { execution ->
                val result = requireNotNull(execution.result)
                companyViews.none { view ->
                    view.acceptances.any { it.runId == execution.claim.runId && it.candidateRevision == result.revision } ||
                        view.audits.any {
                            it.runId == execution.claim.runId && it.candidateRevision == result.revision &&
                                it.status != AUDIT_CONFORMING
                        }
                }
            }
            .toList()

    private fun invalidProposalDiagnostic(proposal: AuditProposal, ruleSet: ArchitectureRuleSet): String? {
        if (proposal.rationale.isBlank()) return "Audit rationale is blank."
        if (proposal.findings.isEmpty()) return "Audit findings are empty."
        val required = ruleSet.rules.mapTo(linkedSetOf()) { it.ruleId }
        val actual = proposal.findings.mapTo(linkedSetOf()) { it.ruleId }
        if (actual != required) {
            return "Audit finding rule IDs do not exactly match compiled rules; required=${required.sorted()}, actual=${actual.sorted()}."
        }
        proposal.findings.firstOrNull {
            it.status !in setOf(AUDIT_CONFORMING, AUDIT_VIOLATION, AUDIT_EVIDENCE_STALE)
        }?.let { return "Audit finding ${it.ruleId} has invalid status ${it.status}." }
        proposal.findings.firstOrNull { it.summary.isBlank() }
            ?.let { return "Audit finding ${it.ruleId} has a blank summary." }
        return null
    }

    private fun blockInvalidAttempt(
        runId: Long,
        role: String,
        candidateRevision: String,
        candidateDiffHash: String,
        diagnostic: String,
    ): Boolean = runCatching {
        val priorBlocked = attemptStore.attemptsFor(runId, role, candidateRevision, candidateDiffHash)
            .count { it.state == AUDIT_ATTEMPT_BLOCKED }
        attemptStore.appendNext { attemptId ->
            CompanyAuditAttempt(
                attemptId,
                runId,
                role,
                candidateRevision,
                candidateDiffHash,
                AUDIT_ATTEMPT_BLOCKED,
                diagnostic,
            )
        }
        if (priorBlocked == 0) {
            attemptStore.appendNext { attemptId ->
                CompanyAuditAttempt(
                    attemptId,
                    runId,
                    role,
                    candidateRevision,
                    candidateDiffHash,
                    AUDIT_ATTEMPT_RETRY_AUTHORIZED,
                    "One automatic audit correction is authorized for the rejected judgment.",
                )
            }
        }
    }.isSuccess

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val SYSTEM_PROMPT = """You are an independent read-only software company auditor.
Return strict JSON with exactly: findings and rationale.
Return exactly one finding for every supplied ruleId.
Each finding has exactly ruleId, status, and summary. Orchard binds revision-pinned evidence IDs deterministically.
Status must be CONFORMING, VIOLATION, or EVIDENCE_STALE.
Use only supplied repository context and objective evidence. Never claim to run tools or mutate files.
Any failed, missing, or revision-mismatched objective evidence is EVIDENCE_STALE.
Any architectural nonconformance is VIOLATION."""
    }
}