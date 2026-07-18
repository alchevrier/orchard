package com.orchard.backend.company

import com.orchard.backend.agent.CODING_EXECUTION_COMPLETED
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
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
    val findings: List<AuditFinding>,
    val rationale: String,
)

class CompanyAuditService(
    private val workspace: WorkspaceStore,
    private val codingWorker: CodingWorkerService,
    private val company: CompanyControlService,
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    private val mutex = Mutex()

    suspend fun tick(): CompanyAuditTickResult {
        if (!mutex.tryLock()) return CompanyAuditTickResult(CompanyAuditTickStatus.BUSY)
        return try {
            executeTick()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeTick(): CompanyAuditTickResult {
        val companyViews = company.projectViews()
        val completed = codingWorker.executions().asSequence()
            .filter { it.result?.status == CODING_EXECUTION_COMPLETED }
            .groupBy { it.claim.runId }
            .values
            .mapNotNull { executions -> executions.maxByOrNull { it.claim.executionId } }
            .sortedBy { it.claim.executionId }
            .firstOrNull { execution ->
                val result = requireNotNull(execution.result)
                companyViews.none { view ->
                    view.acceptances.any { it.runId == execution.claim.runId && it.candidateRevision == result.revision } ||
                        view.audits.any {
                            it.runId == execution.claim.runId && it.candidateRevision == result.revision &&
                                it.status != AUDIT_CONFORMING
                        }
                }
            } ?: return CompanyAuditTickResult(CompanyAuditTickStatus.IDLE)
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
        if (company.assign(run.runId, role, RISK_HIGH).status != CompanyMutationStatus.RECORDED) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        }
        val assignment = company.assignment(run.runId, role)
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.STORAGE_UNAVAILABLE, run.runId, role)
        val provider = company.provider(assignment)
            ?: return CompanyAuditTickResult(CompanyAuditTickStatus.MODEL_FAILED, run.runId, role)
        val repositoryContext = runCatching {
            workspaceGateway.collectContext(
                requireNotNull(run.context.workspaceReservation).path,
                ruleSet.rules.joinToString("\n") { it.statement },
            )
        }.getOrElse { return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Repository audit context is unavailable.") }
        val envelope = AuditEnvelope(
            role = role,
            candidateRevision = revision,
            changedPaths = result.changedPaths,
            rules = ruleSet.rules,
            objectiveEvidence = run.evidence.filter { it.revision == revision }.map {
                AuditEvidence(it.evidenceId, it.kind, it.revision, it.passed, it.summary)
            },
            repositoryContext = repositoryContext,
        )
        val prompt = "$SYSTEM_PROMPT\n\nAuthoritative audit envelope:\n${json.encodeToString(envelope)}"
        if (estimateModelTokens(prompt) > MAX_INPUT_TOKENS) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Audit context exceeds the model input budget.")
        }
        val profile = DefaultModelExecutionProfiles.boundedIndependentAudit
        val admission = resourceController.tryAcquire(provider.resourceDemand(profile))
        val lease = admission.lease ?: return CompanyAuditTickResult(CompanyAuditTickStatus.RESOURCE_BLOCKED, run.runId, role)
        val startedAt = System.nanoTime()
        val generation: ModelGeneration = runCatching {
            lease.use { provider.executeCircuitSynthesis(prompt, MAX_OUTPUT_TOKENS, MAX_INPUT_TOKENS + MAX_OUTPUT_TOKENS) }
        }.getOrElse { return CompanyAuditTickResult(CompanyAuditTickStatus.MODEL_FAILED, run.runId, role) }
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
        if (proposal == null) return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Audit output is not valid strict JSON.")
        if (!validProposal(proposal, ruleSet)) {
            return CompanyAuditTickResult(CompanyAuditTickStatus.INVALID_JUDGMENT, run.runId, role, diagnostic = "Audit findings do not cover every compiled rule with evidence.")
        }
        val recorded = company.recordAudit(
            run.runId,
            role,
            revision,
            sourceDiff.outputHash,
            proposal.findings,
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

    private fun validProposal(proposal: AuditProposal, ruleSet: ArchitectureRuleSet): Boolean {
        if (proposal.rationale.isBlank() || proposal.findings.isEmpty()) return false
        val required = ruleSet.rules.mapTo(linkedSetOf()) { it.ruleId }
        return proposal.findings.mapTo(linkedSetOf()) { it.ruleId } == required &&
            proposal.findings.all {
                it.status in setOf(AUDIT_CONFORMING, AUDIT_VIOLATION, AUDIT_EVIDENCE_STALE) &&
                    it.evidenceIds.isNotEmpty()
            }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_INPUT_TOKENS = 64_000
        const val MAX_OUTPUT_TOKENS = 4_000
        const val SYSTEM_PROMPT = """You are an independent read-only software company auditor.
Return strict JSON with exactly: findings and rationale.
Return exactly one finding for every supplied ruleId.
Each finding has ruleId, status, summary, and evidenceIds.
Status must be CONFORMING, VIOLATION, or EVIDENCE_STALE.
Use only supplied repository context and objective evidence. Never claim to run tools or mutate files.
Any failed, missing, or revision-mismatched objective evidence is EVIDENCE_STALE.
Any architectural nonconformance is VIOLATION."""
    }
}