package com.orchard.backend.agent

import com.orchard.backend.analysis.DISPOSITION_COMPLETE
import com.orchard.backend.analysis.PLAN_OPERATION_CREATE
import com.orchard.backend.analysis.PLAN_OPERATION_DELETE
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.company.RISK_HIGH
import com.orchard.backend.company.ROLE_IMPLEMENTER
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ModelWorkPriority
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.DefaultModelExecutionProfiles
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
import com.orchard.backend.workspace.CRITERION_HUMAN
import com.orchard.backend.workspace.EvidenceRequirement
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.admittedAcceptanceVerification
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CodingWorkerTickStatus {
    IDLE,
    BUSY,
    RETRY_AUTHORIZED,
    INTERRUPTED_RECOVERED,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_PROPOSAL,
    APPLICATION_FAILED,
    VERIFICATION_FAILED,
    CANDIDATE_COMPLETED,
    ANALYSIS_REQUIRED,
    PLAN_STALE,
    PLAN_BLOCKED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CodingWorkerTickResult(
    val status: CodingWorkerTickStatus,
    val execution: CodingWorkerExecutionView? = null,
    val diagnostic: String? = null,
)

@Serializable
private data class CodingWorkerModelEnvelope(
    val executionProfile: ModelExecutionProfile,
    val workflowStepId: String,
    val allowedActions: List<String>,
    val forbiddenActions: List<String>,
    val requiredOutputSchema: String,
    val run: WorkflowRunView,
    val executionPlan: RepositoryExecutionPlan? = null,
    val priorRejectedCodingDiagnostic: String? = null,
    val repositoryContext: CodingRepositoryContext,
)

class CodingWorkerService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val workerStore: CodingWorkerStore = TransientCodingWorkerStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
    private val retryBudget: Int = DEFAULT_RETRY_BUDGET,
    private val companyControl: CompanyControlService? = null,
    private val repositoryAnalysis: RepositoryAnalysisService? = null,
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
    private val attemptStore: CodingWorkerAttemptStore = TransientCodingWorkerAttemptStore(),
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()
    private val strictOutputJson = Json { encodeDefaults = true }

    init {
        require(retryBudget in 1..MAX_RETRY_BUDGET) { "Coding worker retry budget is invalid" }
        workerStore.loadEvents()
        attemptStore.load()
        bootstrapFailedCandidateRestorations()
        bootstrapLegacyAttemptBlocks()
        bootstrapApplicationFailureBlocks()
        bootstrapTerminalPlanBlocks()
        bootstrapRecurrentRetryBlocks()
    }

    fun executions(): List<CodingWorkerExecutionView> = codingWorkerExecutions(workerStore.loadEvents())

    fun attempts(): List<CodingWorkerAttempt> = attemptStore.load()

    suspend fun tick(): CodingWorkerTickResult {
        val executions = codingWorkerExecutions(workerStore.loadEvents())
        val runId = interruptedRunIds(executions).firstOrNull()
            ?: candidateRuns(executions).firstOrNull()?.runId
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.IDLE)
        return tick(runId)
    }

    fun interruptedRunIds(): List<Long> = interruptedRunIds(codingWorkerExecutions(workerStore.loadEvents()))

    fun eligibleRunIds(): List<Long> = candidateRuns(codingWorkerExecutions(workerStore.loadEvents())).map { it.runId }

    private fun interruptedRunIds(executions: List<CodingWorkerExecutionView>): List<Long> = executions.asSequence()
        .filter { it.result == null }
        .sortedBy { it.claim.executionId }
        .map { it.claim.runId }
        .toList()

    fun authorizeRetry(runId: Long): CodingWorkerTickResult {
        val executions = codingWorkerExecutions(workerStore.loadEvents())
        if (executions.any { it.claim.runId == runId && it.result == null }) {
            return CodingWorkerTickResult(
                CodingWorkerTickStatus.BUSY,
                diagnostic = "The coding run already has an active execution.",
            )
        }
        val plan = repositoryAnalysis?.currentPlan(runId)
            ?: return CodingWorkerTickResult(
                CodingWorkerTickStatus.IDLE,
                diagnostic = "The coding run has no current accepted execution plan.",
            )
        val retryBasis = runCatching {
            attemptStore.retryBasisForTerminalFailure(executions, runId, plan)
        }.getOrElse {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty())
        }
        if (retryBasis?.state !in setOf(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_CONSUMED)) {
            return CodingWorkerTickResult(
                CodingWorkerTickStatus.IDLE,
                diagnostic = "The coding run has no blocked attempt to retry.",
            )
        }
        return runCatching {
            attemptStore.appendNext { attemptId ->
                CodingWorkerAttempt(
                    attemptId = attemptId,
                    runId = runId,
                    executionPlanId = plan.planId,
                    executionPlanHash = plan.hash,
                    state = CODING_ATTEMPT_RETRY_AUTHORIZED,
                    resultStatus = CodingWorkerTickStatus.RETRY_AUTHORIZED.name,
                    diagnostic = "A human explicitly authorized one successor coding attempt.",
                    proposalHash = requireNotNull(retryBasis).proposalHash,
                )
            }
        }.fold(
            onSuccess = {
                CodingWorkerTickResult(
                    CodingWorkerTickStatus.RETRY_AUTHORIZED,
                    diagnostic = "One successor coding attempt is authorized.",
                )
            },
            onFailure = {
                CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty())
            },
        )
    }

    suspend fun tick(runId: Long): CodingWorkerTickResult {
        val mutex = runMutexes.computeIfAbsent(runId) { Mutex() }
        if (!mutex.tryLock()) return CodingWorkerTickResult(CodingWorkerTickStatus.BUSY)
        return try {
            executeTick(runId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeTick(runId: Long): CodingWorkerTickResult {
        val events = workerStore.loadEvents()
        val executions = codingWorkerExecutions(events)
        executions.singleOrNull { it.claim.runId == runId && it.result == null }?.let { interrupted ->
            val result = terminalResult(
                interrupted.claim.executionId,
                CODING_EXECUTION_INTERRUPTED,
                diagnostic = "The process stopped before this execution recorded a terminal result.",
            )
            return appendResult(events, interrupted.claim, result, CodingWorkerTickStatus.INTERRUPTED_RECOVERED)
        }
        val run = candidateRuns(executions).singleOrNull { it.runId == runId }
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.IDLE)
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        val restorationDiagnostic = restoreLatestFailedCandidate(run.runId, workspacePath, executions)
        if (restorationDiagnostic != null) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }
        val executionPlan = repositoryAnalysis?.currentPlan(run.runId)
        if (repositoryAnalysis != null && executionPlan == null) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.ANALYSIS_REQUIRED)
        }
        if (executionPlan != null && workspaceGateway.currentRevision(workspacePath) != executionPlan.baseRevision) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.PLAN_STALE)
        }
        if (executionPlan?.content?.disposition == DISPOSITION_COMPLETE) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.PLAN_BLOCKED)
        }
        val priorRejectedCodingDiagnostic = executionPlan?.let { plan ->
            attemptStore.retryDiagnostic(run.runId, plan.planId, plan.hash)
        }
        val defaultProfile = DefaultModelExecutionProfiles.boundedCodingPatch
        val profileOverride = runCatching { profileSettingsStore.load() }.getOrElse {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, profileOverride)
        val assignment = companyControl?.let { company ->
            if (company.compileRules(run.context.projectId).status != CompanyMutationStatus.RECORDED) {
                return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
            }
            val priorFailures = executions.count { it.claim.runId == run.runId && it.result?.status == CODING_EXECUTION_FAILED }
            val current = company.assignment(run.runId, ROLE_IMPLEMENTER)
            if (current != null && priorFailures > 0 && current.assignmentId == executions.lastOrNull { it.claim.runId == run.runId }?.claim?.assignmentId) {
                company.escalate(run.runId, ROLE_IMPLEMENTER, "The previous coding attempt failed verification or candidate application.")
            }
            if (company.assign(run.runId, ROLE_IMPLEMENTER, RISK_HIGH).status != CompanyMutationStatus.RECORDED) {
                return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
            }
            company.assignment(run.runId, ROLE_IMPLEMENTER)
                ?: return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
        }
        val modelProvider = assignment?.let { companyControl?.provider(it) }
            ?: resolveProvider(profile, profileOverride)
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
        val binding = modelProvider.bindingProfile()
        val toolchainResolution = runCatching { workspaceGateway.resolveToolchainPolicy(workspacePath) }
        val toolchainPolicy = toolchainResolution.getOrNull()
        val claim = try {
            requireNotNull(workerStore.appendNext { eventId, preceding ->
                val currentExecutions = codingWorkerExecutions(preceding)
                val claim = newClaim(eventId, currentExecutions, run, binding, toolchainPolicy, assignment, executionPlan)
                CodingWorkerEvent(eventId = eventId, claim = claim)
            }.claim)
        } catch (_: Exception) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }
        if (priorRejectedCodingDiagnostic != null) {
            val consumed = runCatching {
                attemptStore.appendNext { attemptId ->
                    CodingWorkerAttempt(
                        attemptId = attemptId,
                        runId = run.runId,
                        executionPlanId = requireNotNull(executionPlan).planId,
                        executionPlanHash = executionPlan.hash,
                        state = CODING_ATTEMPT_RETRY_CONSUMED,
                        resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                        diagnostic = "The explicitly authorized successor coding attempt was consumed.",
                    )
                }
            }
            if (consumed.isFailure) {
                return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    consumed.exceptionOrNull()?.message,
                )
            }
        }
        toolchainResolution.exceptionOrNull()?.let { error ->
            return finish(
                claim,
                CODING_EXECUTION_DEFERRED,
                CodingWorkerTickStatus.APPLICATION_FAILED,
                "Toolchain policy catalog is temporarily unreadable: ${error.message.orEmpty()}",
                retryAfter = Instant.now().plus(TRANSIENT_RETRY_DELAY).toString(),
            )
        }
        if (toolchainPolicy == null) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.APPLICATION_FAILED,
            "No valid toolchain policy matches the reserved repository.",
        )

        val contextQuery = codingContextQuery(run, executionPlan)
        val planPaths = executionPlan?.content?.operations
            ?.filter { it.action != "VERIFY" }
            ?.map { it.path }
            .orEmpty()
        fun envelope(
            repositoryContext: CodingRepositoryContext,
            retryDiagnostic: String? = priorRejectedCodingDiagnostic,
        ) = CodingWorkerModelEnvelope(
            executionProfile = profile,
            workflowStepId = CODING_WORKFLOW_STEP_ID,
            allowedActions = listOf(CODING_FILE_WRITE, CODING_FILE_REPLACE, CODING_FILE_DELETE),
            forbiddenActions = listOf("EXECUTE_COMMAND", "APPROVE_CRITERION", "COMPLETE_WORKFLOW", "PUSH", "MERGE"),
            requiredOutputSchema = CODING_PROPOSAL_SCHEMA,
            run = codingWorkerRunProjection(run),
            executionPlan = executionPlan,
            priorRejectedCodingDiagnostic = retryDiagnostic,
            repositoryContext = repositoryContext,
        )
        fun prompt(
            repositoryContext: CodingRepositoryContext,
            retryDiagnostic: String? = priorRejectedCodingDiagnostic,
        ): String {
            val envelopeJson = json.encodeToString(envelope(repositoryContext, retryDiagnostic))
            return "$systemPrompt\n\nAuthoritative coding execution envelope:\n$envelopeJson"
        }
        val planContextBudget = executionPlan?.let {
            val emptyContext = CodingRepositoryContext(emptyList(), 0)
            profile.inputBudgetTokens - estimateModelTokens(prompt(emptyContext)) +
                estimateModelTokens(json.encodeToString(emptyContext)) -
                if (priorRejectedCodingDiagnostic == null) 0 else SOURCE_GROUNDING_CONTEXT_RESERVE_BYTES
        }
        if (planContextBudget != null && planContextBudget <= 0) {
            return finish(claim, CODING_EXECUTION_BLOCKED, CodingWorkerTickStatus.INVALID_PROPOSAL, "Coding envelope exceeds the model input budget.")
        }
        val repositoryContext = runCatching {
            executionPlan?.let { plan ->
                workspaceGateway.collectPlanContext(
                    workspacePath = workspacePath,
                    repositoryRevision = plan.baseRevision,
                    paths = planPaths,
                    query = contextQuery,
                    maxSerializedBytes = requireNotNull(planContextBudget),
                )
            } ?: workspaceGateway.collectContext(workspacePath, contextQuery)
        }.getOrElse { error ->
            return finish(claim, CODING_EXECUTION_BLOCKED, CodingWorkerTickStatus.APPLICATION_FAILED, error.message)
        }
        if (executionPlan != null && repositoryContext.omittedFileCount != 0) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.APPLICATION_FAILED,
            "Accepted execution-plan paths are missing from the pinned coding context.",
        )
        val groundedRetryDiagnostic = sourceGroundedRetryDiagnostic(priorRejectedCodingDiagnostic, repositoryContext)
        val envelope = envelope(repositoryContext, groundedRetryDiagnostic)
        val envelopeJson = json.encodeToString(envelope)
        val prompt = prompt(repositoryContext, groundedRetryDiagnostic)
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return finish(claim, CODING_EXECUTION_BLOCKED, CodingWorkerTickStatus.INVALID_PROPOSAL, "Coding context exceeds the model input budget.")
        }
        val admission = resourceController.acquire(
            modelProvider.resourceDemand(profile, estimateModelTokens(prompt)),
            ModelWorkPriority.DELIVERY,
        )
        val lease = admission.lease
        if (lease == null) {
            val execution = recordModelExecution(profile, binding, run, envelopeJson, prompt, null, 0, false, admission.evidence)
            return finish(
                claim,
                CODING_EXECUTION_DEFERRED,
                CodingWorkerTickStatus.RESOURCE_BLOCKED,
                admission.evidence.reason,
                modelExecutionId = execution?.executionId,
                retryAfter = Instant.now().plus(TRANSIENT_RETRY_DELAY).toString(),
            )
        }
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use {
                modelProvider.executeCodingPatch(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            val execution = recordModelExecution(
                profile, binding, run, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence
            )
            finish(
                claim,
                CODING_EXECUTION_INTERRUPTED,
                CodingWorkerTickStatus.MODEL_FAILED,
                "Coding model execution was cancelled.",
                modelExecutionId = execution?.executionId,
            )
            throw exception
        } catch (error: Exception) {
            val execution = recordModelExecution(
                profile, binding, run, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence
            )
            return finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.MODEL_FAILED,
                error.message,
                modelExecutionId = execution?.executionId,
            )
        }
        val outputWithinBudget = generation.promptTokens <= profile.inputBudgetTokens &&
            generation.completionTokens <= profile.outputBudgetTokens &&
            estimateModelTokens(generation.text) <= profile.outputBudgetTokens
        val proposal = if (outputWithinBudget) {
            runCatching { strictOutputJson.decodeFromString<CodingPatchProposal>(generation.text) }.getOrNull()
        } else null
        val modelExecution = recordModelExecution(
            profile,
            binding,
            run,
            envelopeJson,
            prompt,
            generation,
            elapsedMillis(startedAt),
            proposal != null,
            admission.evidence,
        ) ?: return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
            "Model execution provenance could not be saved.",
        )
        if (proposal == null) return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.INVALID_PROPOSAL,
            "The coding model returned invalid or oversized proposal JSON.",
            modelExecutionId = modelExecution.executionId,
        )
        val proposalHash = sha256(strictOutputJson.encodeToString(proposal))
        if (executionPlan != null) {
            val rejectedAnchorDiagnostic = runCatching {
                codingRejectedAnchorDiagnostic(
                    proposal = proposal,
                    attempts = attemptStore.load(),
                    runId = run.runId,
                    planId = executionPlan.planId,
                    planHash = executionPlan.hash,
                    repositoryContext = repositoryContext,
                )
            }.getOrElse { error ->
                return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    error.message,
                    modelExecution.executionId,
                    proposalHash,
                )
            }
            val authorizationDiagnostic = codingProposalShapeDiagnostic(proposal)
                ?: codingProposalAuthorizationDiagnostic(proposal, executionPlan)
                ?: rejectedAnchorDiagnostic
            if (authorizationDiagnostic != null) {
                val storageDiagnostic = recordCorrectiveRejection(
                    run.runId,
                    executionPlan.planId,
                    executionPlan.hash,
                    proposalHash,
                    authorizationDiagnostic,
                )
                if (storageDiagnostic != null) return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    storageDiagnostic,
                    modelExecution.executionId,
                    proposalHash,
                )
                return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.PLAN_BLOCKED,
                    authorizationDiagnostic,
                    modelExecution.executionId,
                    proposalHash,
                )
            }
            if (priorRejectedCodingDiagnostic != null) {
                val accepted = runCatching {
                    attemptStore.appendNext { attemptId ->
                        CodingWorkerAttempt(
                            attemptId = attemptId,
                            runId = run.runId,
                            executionPlanId = executionPlan.planId,
                            executionPlanHash = executionPlan.hash,
                            state = CODING_ATTEMPT_SCOPE_ACCEPTED,
                            resultStatus = CodingWorkerTickStatus.CANDIDATE_COMPLETED.name,
                            diagnostic = "The successor proposal satisfies the accepted execution-plan action and path scope.",
                            proposalHash = proposalHash,
                        )
                    }
                }
                if (accepted.isFailure) return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    accepted.exceptionOrNull()?.message,
                    modelExecution.executionId,
                    proposalHash,
                )
            }
        }
        if (!runStillActionable(run)) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.APPLICATION_FAILED,
            "The workflow run changed or closed before candidate mutation.",
            modelExecution.executionId,
            proposalHash,
        )
        val candidate = runCatching {
            workspaceGateway.applyAndCommit(
                requireNotNull(run.context.workspaceReservation).path,
                proposal,
                claim.executionId,
            )
        }.getOrElse { error ->
            val applicationDiagnostic = "The coding proposal could not be applied: ${error.message.orEmpty()}"
            if (executionPlan != null) {
                val storageDiagnostic = recordCorrectiveRejection(
                    run.runId,
                    executionPlan.planId,
                    executionPlan.hash,
                    proposalHash,
                    applicationDiagnostic,
                )
                if (storageDiagnostic != null) return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    storageDiagnostic,
                    modelExecution.executionId,
                    proposalHash,
                )
            }
            return finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.APPLICATION_FAILED,
                applicationDiagnostic,
                modelExecution.executionId,
                proposalHash,
            )
        }
        val evidenceResult = submitEvidence(run, candidate, toolchainPolicy)
        return if (evidenceResult == null) {
            finish(
                claim,
                CODING_EXECUTION_COMPLETED,
                CodingWorkerTickStatus.CANDIDATE_COMPLETED,
                "Candidate revision was committed and all executable evidence was submitted.",
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
        } else {
            val failed = finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.VERIFICATION_FAILED,
                evidenceResult,
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
            if (failed.status != CodingWorkerTickStatus.STORAGE_UNAVAILABLE) {
                runCatching {
                    workspaceGateway.revertCandidate(
                        requireNotNull(run.context.workspaceReservation).path,
                        candidate.revision,
                        claim.executionId,
                    )
                }
            }
            failed
        }
    }

    private fun restoreLatestFailedCandidate(
        runId: Long,
        workspacePath: String,
        executions: List<CodingWorkerExecutionView>,
    ): String? {
        val failed = executions.lastOrNull {
            it.claim.runId == runId &&
                it.result?.status == CODING_EXECUTION_FAILED &&
                it.result.revision != null
        } ?: return null
        val candidateRevision = requireNotNull(failed.result?.revision)
        if (workspaceGateway.currentRevision(workspacePath) != candidateRevision) return null
        return runCatching {
            workspaceGateway.revertCandidate(workspacePath, candidateRevision, failed.claim.executionId)
        }.exceptionOrNull()?.message ?: return null
    }

    private fun candidateRuns(executions: List<CodingWorkerExecutionView>): List<WorkflowRunView> {
        val codingAttempts = attemptStore.load()
        return workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED) }
            .filter { run ->
                run.context.circuitDispatchId != null &&
                    run.context.workspaceReservation?.mode in setOf("ISOLATED", "INTEGRATION")
            }
            .filter { run ->
                codingRunCanExecute(
                    executions = executions.filter { it.claim.runId == run.runId },
                    attempts = codingAttempts.filter { it.runId == run.runId },
                    currentPlan = repositoryAnalysis?.currentPlan(run.runId),
                    bindToCurrentPlan = repositoryAnalysis != null,
                    retryBudget = retryBudget,
                )
            }
            .sortedBy { it.runId }
            .toList()
    }

    private fun newClaim(
        executionId: Long,
        executions: List<CodingWorkerExecutionView>,
        run: WorkflowRunView,
        binding: ModelBindingProfile,
        toolchainPolicy: ResolvedToolchainPolicy?,
        assignment: com.orchard.backend.company.StaffAssignment?,
        executionPlan: RepositoryExecutionPlan?,
    ): CodingWorkerClaim {
        val draft = CodingWorkerClaim(
            executionId = executionId,
            runId = run.runId,
            attempt = executions.count { it.claim.runId == run.runId } + 1,
            contextHash = run.context.hash,
            workspacePath = requireNotNull(run.context.workspaceReservation).path,
            bindingFingerprint = modelBindingFingerprint(binding),
            assignmentId = assignment?.assignmentId,
            staffRole = assignment?.role,
            riskClass = assignment?.risk,
            executionPlanId = executionPlan?.planId,
            executionPlanHash = executionPlan?.hash,
            toolchainPackId = toolchainPolicy?.packId,
            toolchainPackVersion = toolchainPolicy?.packVersion,
            toolchainProfileId = toolchainPolicy?.profileId,
            toolchainPolicyHash = toolchainPolicy?.policyHash,
            hash = "",
        )
        return draft.copy(hash = codingWorkerClaimHash(draft))
    }

    private fun resolveProvider(profile: ModelExecutionProfile, override: ModelProfileOverride?): ModelProvider? {
        val eligible = override?.preferredBindingId?.let { preferred ->
            modelProviders.filter { it.bindingProfile().bindingId == preferred }
        } ?: modelProviders
        return runCatching { ModelProfileResolver.resolve(profile, eligible) }.getOrNull()
    }

    private fun recordCorrectiveRejection(
        runId: Long,
        planId: Long,
        planHash: String,
        proposalHash: String,
        diagnostic: String,
    ): String? {
        val attempts = attemptStore.load()
        val repeatedRejection = codingRejectionIsRepeated(
            attempts,
            runId,
            planId,
            planHash,
            proposalHash,
            diagnostic,
        )
        return runCatching {
            attemptStore.appendNext { attemptId ->
                CodingWorkerAttempt(
                    attemptId = attemptId,
                    runId = runId,
                    executionPlanId = planId,
                    executionPlanHash = planHash,
                    state = CODING_ATTEMPT_BLOCKED,
                    resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                    diagnostic = diagnostic,
                    proposalHash = proposalHash,
                )
            }
            if (!repeatedRejection && automaticCodingCorrectionAvailable(attempts, runId, planId, planHash)) {
                attemptStore.appendNext { attemptId ->
                    CodingWorkerAttempt(
                        attemptId = attemptId,
                        runId = runId,
                        executionPlanId = planId,
                        executionPlanHash = planHash,
                        state = CODING_ATTEMPT_RETRY_AUTHORIZED,
                        resultStatus = CodingWorkerTickStatus.RETRY_AUTHORIZED.name,
                        diagnostic = "The controller authorized one corrective successor for a novel coding rejection.",
                        proposalHash = proposalHash,
                    )
                }
            }
        }.exceptionOrNull()?.message.orEmpty().ifBlank { null }
    }

    private fun submitEvidence(
        run: WorkflowRunView,
        candidate: CodingCandidate,
        toolchainPolicy: ResolvedToolchainPolicy,
    ): String? {
        val requirements = run.workflow.evidenceContract.requirements
        for (requirement in requirements) {
            if (requirement.gate == CRITERION_HUMAN) continue
            if (run.evidence.any { it.kind == requirement.kind && it.revision == candidate.revision && it.passed }) continue
            val observation = if (requirement.kind == "SOURCE_DIFF") {
                VerificationObservation("", 0, sha256(candidate.changedPaths.joinToString("\n")), "Candidate source diff was committed.")
            } else {
                val command = runCatching {
                    verificationCommand(
                        requirement,
                        toolchainPolicy,
                        run.workDefinition?.definition?.acceptanceCriteria?.map { it.verification }.orEmpty(),
                    )
                }
                    .getOrElse {
                        return "Evidence ${requirement.kind} has an invalid admitted verification command: ${it.message.orEmpty()}"
                    }
                    ?: return "Evidence ${requirement.kind} has no admitted or repository verification command."
                runCatching {
                    workspaceGateway.executeVerification(
                        requireNotNull(run.context.workspaceReservation).path,
                        command.command,
                        command.evidenceCommand,
                    )
                }.getOrElse { return "Verification ${requirement.kind} could not run: ${it.message.orEmpty()}" }
            }
            val result = workspace.submitEvidence(
                run.runId,
                EvidenceSubmission(
                    kind = requirement.kind,
                    revision = candidate.revision,
                    command = observation.command,
                    exitCode = observation.exitCode,
                    outputHash = observation.outputHash,
                    summary = observation.summary,
                    producer = CODING_EVIDENCE_PRODUCER,
                ),
            )
            if (result.status != WorkflowMutationStatus.RECORDED) {
                return "Evidence ${requirement.kind} was rejected with ${result.status}."
            }
            val recorded = result.snapshot.workflowRuns.single { it.runId == run.runId }.evidence
                .last { it.kind == requirement.kind && it.revision == candidate.revision }
            if (!recorded.passed) return "Verification ${requirement.kind} failed: ${recorded.summary}"
        }
        return null
    }

    private fun runStillActionable(expected: WorkflowRunView): Boolean =
        workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == expected.runId }?.let { current ->
            current.state in setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED) &&
                current.context.hash == expected.context.hash &&
                current.context.circuitDispatchId == expected.context.circuitDispatchId &&
                current.context.workspaceReservation == expected.context.workspaceReservation
        } == true

    private fun verificationCommand(
        requirement: EvidenceRequirement,
        toolchainPolicy: ResolvedToolchainPolicy,
        acceptanceVerifications: List<String>,
    ): VerificationInvocation? = (requirement.verification?.takeIf(String::isNotBlank)
        ?: admittedAcceptanceVerification(acceptanceVerifications).takeIf { requirement.kind == "ACCEPTANCE" })?.let { admitted ->
        VerificationInvocation(workspaceGateway.parseVerificationCommand(admitted), admitted)
    } ?: toolchainPolicy.commands[
        when (requirement.kind) {
            "REGRESSION_TEST" -> "TEST"
            else -> requirement.kind
        }
    ]?.let { command -> VerificationInvocation(command, command.canonical()) }

    private data class VerificationInvocation(
        val command: VerificationCommand,
        val evidenceCommand: String,
    )

    private fun recordModelExecution(
        profile: ModelExecutionProfile,
        binding: ModelBindingProfile,
        run: WorkflowRunView,
        envelopeJson: String,
        prompt: String,
        generation: ModelGeneration?,
        latencyMillis: Long,
        schemaValid: Boolean,
        admission: com.orchard.backend.resource.ResourceAdmissionEvidence,
    ) = workspace.recordModelExecution(
        ModelExecutionObservationDraft(
            profile = profile,
            binding = binding,
            workflowStepId = CODING_WORKFLOW_STEP_ID,
            workItemId = run.context.workItemId,
            envelopeHash = sha256(envelopeJson),
            promptHash = sha256(prompt),
            outputHash = generation?.text?.let(::sha256),
            inputTokens = generation?.promptTokens ?: estimateModelTokens(prompt),
            outputTokens = generation?.completionTokens ?: 0,
            latencyMillis = latencyMillis,
            schemaValid = schemaValid,
            resourceAdmission = admission,
        )
    )

    private fun finish(
        claim: CodingWorkerClaim,
        status: String,
        tickStatus: CodingWorkerTickStatus,
        diagnostic: String?,
        modelExecutionId: Long? = null,
        proposalHash: String? = null,
        candidate: CodingCandidate? = null,
        retryAfter: String? = null,
    ): CodingWorkerTickResult {
        val result = terminalResult(
            claim.executionId,
            status,
            diagnostic = diagnostic?.take(MAX_DIAGNOSTIC_LENGTH).orEmpty().ifBlank { "Coding execution failed without a diagnostic." },
            modelExecutionId = modelExecutionId,
            proposalHash = proposalHash,
            candidate = candidate,
            retryAfter = retryAfter,
        )
        val blockStorageDiagnostic = recordTerminalPlanBlock(claim, result)
        return appendResult(
            workerStore.loadEvents(),
            claim,
            result,
            if (blockStorageDiagnostic == null) tickStatus else CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
        )
    }

    private fun terminalResult(
        executionId: Long,
        status: String,
        diagnostic: String,
        modelExecutionId: Long? = null,
        proposalHash: String? = null,
        candidate: CodingCandidate? = null,
        retryAfter: String? = null,
    ): CodingWorkerResult {
        val draft = CodingWorkerResult(
            executionId = executionId,
            status = status,
            modelExecutionId = modelExecutionId,
            proposalHash = proposalHash,
            changedPaths = candidate?.changedPaths.orEmpty(),
            revision = candidate?.revision,
            diagnostic = diagnostic,
            retryAfter = retryAfter,
            hash = "",
        )
        return draft.copy(hash = codingWorkerResultHash(draft))
    }

    private fun appendResult(
        events: List<CodingWorkerEvent>,
        claim: CodingWorkerClaim,
        result: CodingWorkerResult,
        status: CodingWorkerTickStatus,
    ): CodingWorkerTickResult = try {
        workerStore.appendNext { eventId, _ -> CodingWorkerEvent(eventId = eventId, result = result) }
        CodingWorkerTickResult(status, CodingWorkerExecutionView(claim, result))
    } catch (_: Exception) {
        CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE, CodingWorkerExecutionView(claim))
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun bootstrapFailedCandidateRestorations() {
        val executions = codingWorkerExecutions(workerStore.loadEvents())
        val runs = workspace.snapshot(MESSAGE_READY).workflowRuns.associateBy { it.runId }
        executions.asSequence()
            .filter { it.result?.status == CODING_EXECUTION_FAILED && it.result.revision != null }
            .groupBy { it.claim.runId }
            .values
            .map { it.last() }
            .forEach { failed ->
                val workspacePath = runs[failed.claim.runId]?.context?.workspaceReservation?.path ?: return@forEach
                restoreLatestFailedCandidate(failed.claim.runId, workspacePath, executions)
            }
    }

    private fun bootstrapLegacyAttemptBlocks() {
        val attempts = attemptStore.load()
        codingWorkerExecutions(workerStore.loadEvents())
            .filter { execution ->
                execution.claim.executionPlanId != null &&
                    execution.claim.executionPlanHash != null &&
                    execution.result?.status == CODING_EXECUTION_FAILED &&
                    execution.result.proposalHash != null &&
                    execution.result.diagnostic == LEGACY_PLAN_SCOPE_DIAGNOSTIC
            }
            .groupBy { execution ->
                listOf(
                    execution.claim.runId.toString(),
                    execution.claim.executionPlanId.toString(),
                    execution.claim.executionPlanHash,
                    execution.result?.proposalHash,
                )
            }
            .values
            .filter { it.size >= LEGACY_IDENTICAL_OUTCOME_BLOCK_THRESHOLD }
            .forEach { repeated ->
                val latest = repeated.maxBy { it.claim.executionId }
                val planId = requireNotNull(latest.claim.executionPlanId)
                val planHash = requireNotNull(latest.claim.executionPlanHash)
                if (attempts.none {
                        it.runId == latest.claim.runId &&
                            it.executionPlanId == planId &&
                            it.executionPlanHash == planHash
                    }
                ) {
                    attemptStore.appendNext { attemptId ->
                        CodingWorkerAttempt(
                            attemptId = attemptId,
                            runId = latest.claim.runId,
                            executionPlanId = planId,
                            executionPlanHash = planHash,
                            state = CODING_ATTEMPT_BLOCKED,
                            resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                            diagnostic = "Automatic coding blocked after repeated identical historical proposals exceeded the accepted execution-plan scope.",
                            proposalHash = latest.result?.proposalHash,
                        )
                    }
                }
            }
    }

    private fun bootstrapApplicationFailureBlocks() {
        val attempts = attemptStore.load()
        codingWorkerExecutions(workerStore.loadEvents())
            .filter { it.result != null }
            .groupBy { it.claim.runId }
            .values
            .mapNotNull { executions -> executions.maxByOrNull { it.claim.executionId } }
            .filter { execution ->
                execution.claim.executionPlanId != null &&
                    execution.claim.executionPlanHash != null &&
                    execution.result?.status == CODING_EXECUTION_FAILED &&
                    execution.result.proposalHash != null &&
                    execution.result.changedPaths.isEmpty() &&
                    execution.result.revision == null
            }
            .forEach { execution ->
                val planId = requireNotNull(execution.claim.executionPlanId)
                val planHash = requireNotNull(execution.claim.executionPlanHash)
                val proposalHash = requireNotNull(execution.result?.proposalHash)
                val latest = attempts.lastOrNull {
                    it.runId == execution.claim.runId &&
                        it.executionPlanId == planId &&
                        it.executionPlanHash == planHash
                }
                if (latest?.state == CODING_ATTEMPT_SCOPE_ACCEPTED && latest.proposalHash == proposalHash) {
                    recordCorrectiveRejection(
                        execution.claim.runId,
                        planId,
                        planHash,
                        proposalHash,
                        "The coding proposal could not be applied: ${requireNotNull(execution.result).diagnostic}",
                    )
                }
            }
    }

    private fun bootstrapTerminalPlanBlocks() {
        codingWorkerExecutions(workerStore.loadEvents())
            .filter { it.claim.executionPlanId != null && it.claim.executionPlanHash != null }
            .groupBy { Triple(it.claim.runId, it.claim.executionPlanId, it.claim.executionPlanHash) }
            .values
            .mapNotNull { executions -> executions.maxByOrNull { it.claim.executionId } }
            .filter { it.result?.status == CODING_EXECUTION_BLOCKED }
            .forEach { execution -> recordTerminalPlanBlock(execution.claim, requireNotNull(execution.result)) }
    }

    private fun recordTerminalPlanBlock(claim: CodingWorkerClaim, result: CodingWorkerResult): String? {
        val planId = claim.executionPlanId ?: return null
        val planHash = claim.executionPlanHash ?: return null
        if (result.status != CODING_EXECUTION_BLOCKED) return null
        val latest = attemptStore.latestAttempt(claim.runId, planId, planHash)
        if (latest?.state in setOf(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_AUTHORIZED)) return null
        return runCatching {
            attemptStore.appendNext { attemptId ->
                CodingWorkerAttempt(
                    attemptId = attemptId,
                    runId = claim.runId,
                    executionPlanId = planId,
                    executionPlanHash = planHash,
                    state = CODING_ATTEMPT_BLOCKED,
                    resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                    diagnostic = result.diagnostic,
                    proposalHash = result.proposalHash,
                )
            }
        }.exceptionOrNull()?.message.orEmpty().ifBlank { null }
    }

    private fun bootstrapRecurrentRetryBlocks() {
        attemptStore.load()
            .groupBy { Triple(it.runId, it.executionPlanId, it.executionPlanHash) }
            .values
            .forEach { attempts ->
                if (attempts.lastOrNull()?.state != CODING_ATTEMPT_RETRY_AUTHORIZED) return@forEach
                val blocked = attempts.dropLast(1).filter { it.state == CODING_ATTEMPT_BLOCKED }
                val current = blocked.lastOrNull() ?: return@forEach
                if (blocked.dropLast(1).none { it.diagnostic == current.diagnostic }) return@forEach
                attemptStore.appendNext { attemptId ->
                    CodingWorkerAttempt(
                        attemptId = attemptId,
                        runId = current.runId,
                        executionPlanId = current.executionPlanId,
                        executionPlanHash = current.executionPlanHash,
                        state = CODING_ATTEMPT_BLOCKED,
                        resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                        diagnostic = current.diagnostic,
                        proposalHash = current.proposalHash,
                    )
                }
            }
    }

    private companion object {
        const val CODING_WORKFLOW_STEP_ID = "DELIVER_CHANGE:CODING_PATCH"
        const val CODING_PROPOSAL_SCHEMA = "coding-patch-proposal-v2"
        const val CODING_EVIDENCE_PRODUCER = "orchard-coding-worker-v1"
        const val DEFAULT_RETRY_BUDGET = 3
        const val MAX_RETRY_BUDGET = 10
        const val MAX_DIAGNOSTIC_LENGTH = 4_096
        const val LEGACY_PLAN_SCOPE_DIAGNOSTIC = "The coding proposal exceeds the accepted execution-plan path or action scope."
        const val LEGACY_IDENTICAL_OUTCOME_BLOCK_THRESHOLD = 2
        val TRANSIENT_RETRY_DELAY: Duration = Duration.ofSeconds(30)

        fun loadPrompt(): String {
            val stream = requireNotNull(
                CodingWorkerService::class.java.getResourceAsStream("/default-system-prompts/coding_worker_agent.md")
            ) { "Missing coding worker prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

internal fun codingExecutionBlockRemains(executionStatus: String?, authorityState: String?): Boolean =
    executionStatus == CODING_EXECUTION_BLOCKED && authorityState != CODING_ATTEMPT_RETRY_AUTHORIZED

internal fun codingWorkerRunProjection(run: WorkflowRunView): WorkflowRunView = run.copy(
    evidence = emptyList(),
    attempts = emptyList(),
    decisions = emptyList(),
    judgments = emptyList(),
)

internal fun codingRunCanExecute(
    executions: List<CodingWorkerExecutionView>,
    attempts: List<CodingWorkerAttempt>,
    currentPlan: RepositoryExecutionPlan?,
    bindToCurrentPlan: Boolean,
    retryBudget: Int,
    now: Instant = Instant.now(),
): Boolean {
    if (executions.any { it.result == null }) return false
    val relevantExecutions = if (bindToCurrentPlan) {
        currentPlan?.let { plan ->
            executions.filter {
                it.claim.executionPlanId == plan.planId && it.claim.executionPlanHash == plan.hash
            }
        }.orEmpty()
    } else {
        executions
    }
    val latestExecution = relevantExecutions.maxByOrNull { it.claim.executionId }
    val planId = currentPlan?.planId ?: latestExecution?.claim?.executionPlanId
    val planHash = currentPlan?.hash ?: latestExecution?.claim?.executionPlanHash
    val planAttempts = if (planId != null && planHash != null) {
        attempts.filter { it.executionPlanId == planId && it.executionPlanHash == planHash }
    } else {
        emptyList()
    }
    val authority = planAttempts.lastOrNull()
    if (codingExecutionBlockRemains(latestExecution?.result?.status, authority?.state)) return false
    val deferred = latestExecution?.result?.takeIf {
        it.status == CODING_EXECUTION_DEFERRED && Instant.parse(requireNotNull(it.retryAfter)).isAfter(now)
    }
    if (deferred != null) return false
    if (authority?.state in setOf(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_CONSUMED)) return false
    val scopeAcceptedAt = planAttempts.lastOrNull { it.state == CODING_ATTEMPT_SCOPE_ACCEPTED }
        ?.recordedAt
        ?.let(Instant::parse)
    val repairCount = relevantExecutions.count { execution ->
        codingExecutionConsumesRepairBudget(execution.result?.status) &&
            (scopeAcceptedAt == null || Instant.parse(requireNotNull(execution.result).completedAt) >= scopeAcceptedAt)
    }
    return repairCount < retryBudget || authority?.state == CODING_ATTEMPT_RETRY_AUTHORIZED
}

internal fun codingExecutionConsumesRepairBudget(status: String?): Boolean =
    status == CODING_EXECUTION_COMPLETED || status == CODING_EXECUTION_FAILED

internal fun codingContextQuery(run: WorkflowRunView, executionPlan: RepositoryExecutionPlan?): String = buildString {
    appendLine(run.context.title)
    appendLine(run.context.content)
    append(codingPlanContextQuery(executionPlan))
}

internal fun codingPlanContextQuery(executionPlan: RepositoryExecutionPlan?): String = buildString {
    executionPlan?.content?.let { plan ->
        appendLine(plan.summary)
        plan.evidence.forEach { evidence ->
            evidence.symbol?.let(::appendLine)
            appendLine(evidence.observation)
        }
        plan.operations.forEach { operation ->
            operation.symbol?.let(::appendLine)
            appendLine(operation.instruction)
            operation.acceptanceCriteria.forEach(::appendLine)
        }
    }
}

internal fun codingRetryableTerminalFailure(
    executions: List<CodingWorkerExecutionView>,
    runId: Long,
    planId: Long,
    planHash: String,
): CodingWorkerExecutionView? = executions.lastOrNull {
    it.claim.runId == runId &&
        it.claim.executionPlanId == planId &&
        it.claim.executionPlanHash == planHash &&
        it.result?.status == CODING_EXECUTION_FAILED
}

internal fun CodingWorkerAttemptStore.retryBasisForTerminalFailure(
    executions: List<CodingWorkerExecutionView>,
    runId: Long,
    plan: RepositoryExecutionPlan,
): CodingWorkerAttempt? {
    latestAttempt(runId, plan.planId, plan.hash)?.let { return it }
    val failure = codingRetryableTerminalFailure(executions, runId, plan.planId, plan.hash) ?: return null
    return appendNext { attemptId ->
        CodingWorkerAttempt(
            attemptId = attemptId,
            runId = runId,
            executionPlanId = plan.planId,
            executionPlanHash = plan.hash,
            state = CODING_ATTEMPT_BLOCKED,
            resultStatus = CodingWorkerTickStatus.MODEL_FAILED.name,
            diagnostic = requireNotNull(failure.result).diagnostic,
            proposalHash = failure.result.proposalHash,
        )
    }
}

internal fun codingRejectionIsRepeated(
    attempts: List<CodingWorkerAttempt>,
    runId: Long,
    planId: Long,
    planHash: String,
    proposalHash: String,
    diagnostic: String,
): Boolean = attempts.any {
    it.runId == runId &&
        it.executionPlanId == planId &&
        it.executionPlanHash == planHash &&
        it.state == CODING_ATTEMPT_BLOCKED &&
        (it.proposalHash == proposalHash || it.diagnostic == diagnostic)
}

internal fun automaticCodingCorrectionAvailable(
    attempts: List<CodingWorkerAttempt>,
    runId: Long,
    planId: Long,
    planHash: String,
): Boolean = attempts.none {
    it.runId == runId &&
        it.executionPlanId == planId &&
        it.executionPlanHash == planHash &&
        it.state == CODING_ATTEMPT_RETRY_AUTHORIZED
}

internal fun codingProposalShapeDiagnostic(proposal: CodingPatchProposal): String? {
    val malformed = proposal.operations.mapNotNull { operation ->
        val reason = when (operation.action) {
            CODING_FILE_WRITE -> when {
                operation.content == null -> "requires complete content"
                operation.replacements.isNotEmpty() -> "forbids replacements"
                else -> null
            }
            CODING_FILE_REPLACE -> when {
                operation.content != null -> "forbids complete content"
                operation.replacements.isEmpty() -> "requires at least one bounded replacement"
                operation.replacements.any { it.old.isEmpty() } -> "requires non-empty old text"
                else -> null
            }
            CODING_FILE_DELETE -> when {
                operation.content != null -> "forbids content"
                operation.replacements.isNotEmpty() -> "forbids replacements"
                else -> null
            }
            else -> null
        }
        reason?.let { "${operation.action} ${operation.path} $it" }
    }
    return malformed.takeIf { it.isNotEmpty() }?.let {
        "The coding proposal contains malformed operation payloads: ${it.distinct().sorted().joinToString(" | ")}."
    }
}

internal fun codingRejectedAnchorDiagnostic(
    proposal: CodingPatchProposal,
    attempts: List<CodingWorkerAttempt>,
    runId: Long,
    planId: Long,
    planHash: String,
    repositoryContext: CodingRepositoryContext,
): String? {
    val rejected = attempts.filter {
        it.runId == runId &&
            it.executionPlanId == planId &&
            it.executionPlanHash == planHash &&
            it.state == CODING_ATTEMPT_BLOCKED
    }
    proposal.operations.forEach { operation ->
        operation.replacements.forEachIndexed { index, replacement ->
            val fingerprint = rejectedReplacementAnchor(replacement.old)
            val pathMarker = "REPLACE ${operation.path} "
            if (rejected.any { pathMarker in it.diagnostic && fingerprint in it.diagnostic }) {
                val contextFile = repositoryContext.files.singleOrNull { it.path == operation.path }
                val declarations = contextFile?.matchedDeclarations.orEmpty().take(MAX_REJECTED_ANCHOR_DECLARATIONS)
                val anchors = contextFile?.let(::sourceBackedDeclarationAnchors).orEmpty()
                return buildString {
                    append("The coding proposal reuses a previously rejected source anchor: REPLACE ")
                    append(operation.path).append(" replacement ").append(index + 1).append("; ")
                    append(fingerprint).append(". Select a different exact anchor from the supplied source.")
                    if (anchors.isNotEmpty()) {
                        append(" Exact contiguous source text near matched declarations: ")
                        append(anchors.joinToString(" | ") { Json.encodeToString(it) })
                        append('.')
                    }
                    if (declarations.isNotEmpty()) {
                        append(" Source-backed declarations available for this path: ")
                        append(declarations.joinToString(" | "))
                        append('.')
                    }
                }
            }
        }
    }
    return null
}

internal fun sourceBackedDeclarationAnchors(contextFile: CodingContextFile): List<String> {
    val sourceLines = contextFile.content.lineSequence()
        .filterNot { it.startsWith("[Orchard excerpt lines ") }
        .toList()
    val pathTokens = CAMEL_CASE_TOKEN.findAll(contextFile.path.substringAfterLast('/').substringBeforeLast('.'))
        .map { it.value.lowercase() }
        .filter { it.length >= MIN_SOURCE_ANCHOR_TOKEN_LENGTH }
        .toSet()
    return contextFile.matchedDeclarations.withIndex().sortedWith(
        compareByDescending<IndexedValue<String>> { (_, declaration) ->
            pathTokens.count { it in declaration.lowercase() }
        }.thenBy { it.index },
    ).asSequence().mapNotNull { (_, declaration) ->
        val declarationIndex = sourceLines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed == declaration || trimmed.startsWith(declaration)
        }
        declarationIndex.takeIf { it >= 0 }?.let { index ->
            sourceLines.subList(index, minOf(index + SOURCE_ANCHOR_LINES, sourceLines.size))
                .joinToString("\n")
                .trimEnd()
                .let { takeUtf8Prefix(it, MAX_SOURCE_ANCHOR_BYTES) }
        }
    }.filter(String::isNotEmpty)
        .distinct()
        .take(MAX_REJECTED_ANCHOR_EXAMPLES)
        .toList()
}

internal fun sourceGroundedRetryDiagnostic(
    diagnostic: String?,
    repositoryContext: CodingRepositoryContext,
): String? {
    if (diagnostic == null || SOURCE_GROUNDED_RETRY_MARKER in diagnostic) return diagnostic
    val anchors = repositoryContext.files.asSequence()
        .filter { it.path in diagnostic }
        .mapNotNull { contextFile ->
            sourceBackedDeclarationAnchors(contextFile).firstOrNull()?.let { contextFile.path to it }
        }
        .take(1)
        .toList()
    if (anchors.isEmpty()) return diagnostic
    return buildString {
        append(diagnostic).append(' ').append(SOURCE_GROUNDED_RETRY_MARKER).append(": ")
        append(anchors.joinToString(" | ") { (path, anchor) -> "$path ${Json.encodeToString(anchor)}" })
        append('.')
    }
}

private fun takeUtf8Prefix(value: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < value.length) {
        val codePoint = value.codePointAt(end)
        val charCount = Character.charCount(codePoint)
        val encodedBytes = value.substring(end, end + charCount).encodeToByteArray().size
        if (bytes + encodedBytes > maxBytes) break
        bytes += encodedBytes
        end += charCount
    }
    return value.substring(0, end)
}

internal fun codingProposalAuthorizationDiagnostic(
    proposal: CodingPatchProposal,
    plan: RepositoryExecutionPlan,
): String? {
    val authority = plan.content.operations.filter { it.action != "VERIFY" }.associate { it.path to it.action }
    val unauthorized = proposal.operations.filter { operation ->
        when (operation.action) {
            CODING_FILE_WRITE -> authority[operation.path] != PLAN_OPERATION_CREATE
            CODING_FILE_REPLACE -> authority[operation.path] != PLAN_OPERATION_MODIFY
            CODING_FILE_DELETE -> authority[operation.path] != PLAN_OPERATION_DELETE
            else -> true
        }
    }
    val proposedPaths = proposal.operations.mapTo(mutableSetOf()) { it.path }
    val missing = authority.filterKeys { it !in proposedPaths }
    if (proposal.operations.isNotEmpty() && unauthorized.isEmpty() && missing.isEmpty()) return null
    return buildString {
        append("The coding proposal does not exactly cover the accepted execution-plan path and action scope.")
        if (proposal.operations.isEmpty()) {
            append(" The proposal contains no coding operations.")
        } else if (unauthorized.isNotEmpty()) {
            append(" Unauthorized coding operations: ")
            append(unauthorized.map { "${it.action} ${it.path}" }.distinct().sorted().joinToString(" | "))
            append('.')
        }
        if (missing.isNotEmpty()) {
            append(" Missing required execution-plan operations: ")
            append(missing.entries.sortedBy { it.key }.joinToString(" | ") { (path, action) -> "$action $path" })
            append('.')
        }
        append(" Required execution-plan operations: ")
        append(
            authority.entries.sortedBy { it.key }.joinToString(" | ") { (path, action) ->
                val codingActions = when (action) {
                    PLAN_OPERATION_CREATE -> CODING_FILE_WRITE
                    PLAN_OPERATION_MODIFY -> CODING_FILE_REPLACE
                    PLAN_OPERATION_DELETE -> CODING_FILE_DELETE
                    else -> "none"
                }
                "$action $path permits $codingActions"
            }
        )
        append('.')
    }
}

private const val MAX_REJECTED_ANCHOR_DECLARATIONS = 5
private const val MAX_REJECTED_ANCHOR_EXAMPLES = 2
private const val SOURCE_ANCHOR_LINES = 4
private const val SOURCE_GROUNDED_RETRY_MARKER = "Exact contiguous source text for this correction"
private const val MAX_SOURCE_ANCHOR_BYTES = 1_024
private const val SOURCE_GROUNDING_CONTEXT_RESERVE_BYTES = 4_096
private const val MIN_SOURCE_ANCHOR_TOKEN_LENGTH = 4
private val CAMEL_CASE_TOKEN = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+")
