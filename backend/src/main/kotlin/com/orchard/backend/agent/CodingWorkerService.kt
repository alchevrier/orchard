package com.orchard.backend.agent

import com.orchard.backend.analysis.DISPOSITION_COMPLETE
import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.ExecutableWorkPackageStore
import com.orchard.backend.analysis.PLAN_OPERATION_CREATE
import com.orchard.backend.analysis.PLAN_OPERATION_DELETE
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.PLAN_OPERATION_VERIFY
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.TransientExecutableWorkPackageStore
import com.orchard.backend.analysis.WorkPackageEvidenceAuthority
import com.orchard.backend.analysis.compileExecutableWorkPackage
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
    val currentRevision: String,
    val run: WorkflowRunView,
    val executionPlan: RepositoryExecutionPlan? = null,
    val workPackage: ExecutableWorkPackage? = null,
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
    private val workPackageStore: ExecutableWorkPackageStore = TransientExecutableWorkPackageStore(),
    private val pullRequestStore: CandidatePullRequestStore = TransientCandidatePullRequestStore(),
    private val dispositionService: CandidatePullRequestDispositionService? = null,
    private val designInvalidationStore: WorkPackageDesignInvalidationStore = TransientWorkPackageDesignInvalidationStore(),
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()
    private val strictOutputJson = Json { encodeDefaults = true }

    init {
        require(retryBudget in 1..MAX_RETRY_BUDGET) { "Coding worker retry budget is invalid" }
        workerStore.loadEvents()
        attemptStore.load()
        workPackageStore.load()
        pullRequestStore.load()
        bootstrapFailedCandidateRestorations()
        bootstrapLegacyAttemptBlocks()
        bootstrapApplicationFailureBlocks()
        bootstrapTerminalPlanBlocks()
        bootstrapRecurrentRetryBlocks()
    }

    fun executions(): List<CodingWorkerExecutionView> = codingWorkerExecutions(workerStore.loadEvents())

    fun attempts(): List<CodingWorkerAttempt> = attemptStore.load()

    fun pullRequests(): List<CandidatePullRequest> = pullRequestStore.load()

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
        val newestPlan = repositoryAnalysis?.plans()
            ?.filter { it.runId == runId }
            ?.maxByOrNull { it.revision }
        executions.singleOrNull { it.claim.runId == runId && it.result == null }
            ?.takeIf { interrupted ->
                repositoryAnalysis == null || newestPlan?.let { plan ->
                    interrupted.claim.executionPlanId == plan.planId &&
                        interrupted.claim.executionPlanHash == plan.hash
                } == true
            }
            ?.let { interrupted ->
            val result = terminalResult(
                interrupted.claim.executionId,
                CODING_EXECUTION_INTERRUPTED,
                diagnostic = "The process stopped before this execution recorded a terminal result.",
            )
            return appendResult(events, interrupted.claim, result, CodingWorkerTickStatus.INTERRUPTED_RECOVERED)
        }
        executions.singleOrNull { it.claim.runId == runId && it.result == null }
            ?.let { superseded ->
                val result = terminalResult(
                    superseded.claim.executionId,
                    CODING_EXECUTION_BLOCKED,
                    diagnostic = "The interrupted coding execution belongs to a superseded execution plan.",
                )
                return appendResult(events, superseded.claim, result, CodingWorkerTickStatus.PLAN_STALE)
            }
        val run = candidateRuns(executions).singleOrNull { it.runId == runId }
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.IDLE)
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        val latestFailedExecution = executions.lastOrNull {
            it.claim.runId == run.runId && it.result?.status == CODING_EXECUTION_FAILED && it.result.revision != null
        }
        val latestAuditRepairExecution = executions.lastOrNull {
            run.state == RUN_STATE_EVIDENCE_BLOCKED && it.claim.runId == run.runId &&
                it.result?.status == CODING_EXECUTION_COMPLETED && it.result.revision != null && it.claim.workPackageId != null
        }
        val retainedExecution = listOfNotNull(latestFailedExecution, latestAuditRepairExecution)
            .maxByOrNull { it.claim.executionId }
        val executionPlan = repositoryAnalysis?.currentPlan(run.runId)
        if (repositoryAnalysis != null && executionPlan == null) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.ANALYSIS_REQUIRED)
        }
        val restorationDiagnostic = restoreLatestFailedCandidate(
            run.runId,
            workspacePath,
            executions,
            requireNotNull(run.context.workspaceReservation).baseRevision,
        )
        if (restorationDiagnostic != null) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }
        val currentRevision = workspaceGateway.currentRevision(workspacePath)
        val retainedCandidateIsCurrent = retainedExecution?.let {
            it.claim.executionPlanId == executionPlan?.planId && it.claim.executionPlanHash == executionPlan?.hash &&
                it.result?.revision == currentRevision && it.claim.workPackageId != null
        } == true
        val planTreeMatches = executionPlan != null && currentRevision != null &&
            (workspaceGateway.treeMatches(workspacePath, currentRevision, executionPlan.baseRevision) ||
                workspaceGateway.revisionCompatible(
                    workspacePath,
                    executionPlan.baseRevision,
                    currentRevision,
                    codingPlanContextPaths(executionPlan),
                ))
        if (executionPlan != null && currentRevision != executionPlan.baseRevision && !planTreeMatches && !retainedCandidateIsCurrent) {
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
        val modelProvider = resolveProvider(profile, profileOverride)
            ?: assignment?.let { companyControl?.provider(it) }
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
        val binding = modelProvider.bindingProfile()
        val toolchainResolution = runCatching { workspaceGateway.resolveToolchainPolicy(workspacePath) }
        val toolchainPolicy = toolchainResolution.getOrNull()
        val workPackage = executionPlan?.let { plan ->
            val definition = run.workDefinition
                ?: return CodingWorkerTickResult(CodingWorkerTickStatus.PLAN_BLOCKED, diagnostic = "The accepted plan has no work-definition authority.")
            val restrictedCorrectionPath = executions.asReversed().firstNotNullOfOrNull { execution ->
                execution.result?.takeIf { it.status == CODING_EXECUTION_FAILED }?.diagnostic
                    ?.let { diagnostic -> firstFailingKotlinTestPath(plan, diagnostic) }
            }
            runCatching {
                workPackageStore.load().lastOrNull {
                    it.runId == run.runId && it.design.executionPlanId == plan.planId &&
                        it.design.executionPlanHash == plan.hash && it.intent.definitionId == definition.definitionId &&
                        it.intent.definitionHash == definition.hash &&
                        (restrictedCorrectionPath == null || it.ownership.paths == listOf(restrictedCorrectionPath))
                } ?: run {
                    val packagePaths = restrictedCorrectionPath?.let(::listOf) ?: codingWorkPackageContextPaths(plan)
                    val packageContexts = packagePaths.map { path ->
                        workspaceGateway.collectIntelligenceContext(
                            workspacePath,
                            requireNotNull(currentRevision),
                            listOf(path),
                        )
                    }
                    val packageContext = CodingRepositoryContext(
                        files = packageContexts.flatMap { it.files },
                        omittedFileCount = packageContexts.sumOf { it.omittedFileCount },
                    )
                    workPackageStore.appendNext(run.runId) { packageId, revision ->
                        compileExecutableWorkPackage(
                            packageId,
                            revision,
                            definition,
                            plan,
                            packageContext,
                            repositoryRevision = requireNotNull(currentRevision),
                            restrictedPaths = restrictedCorrectionPath?.let(::setOf).orEmpty(),
                        )
                    }
                }
            }.getOrElse { error ->
                return CodingWorkerTickResult(
                    CodingWorkerTickStatus.PLAN_BLOCKED,
                    diagnostic = "Executable work-package admission failed: ${error.message.orEmpty()}",
                )
            }
        }
        if (workPackage != null && designInvalidationStore.load().any {
                it.packageId == workPackage.packageId && it.packageHash == workPackage.hash
            }) {
            return CodingWorkerTickResult(
                CodingWorkerTickStatus.PLAN_BLOCKED,
                diagnostic = "The executable work package is invalidated by a newer admitted design revision.",
            )
        }
        val claim = try {
            requireNotNull(workerStore.appendNext { eventId, preceding ->
                val currentExecutions = codingWorkerExecutions(preceding)
                val claim = newClaim(eventId, currentExecutions, run, binding, toolchainPolicy, assignment, executionPlan, workPackage)
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
        val contextPaths = workPackage?.ownership?.paths ?: codingPlanContextPaths(executionPlan)
        fun envelope(
            repositoryContext: CodingRepositoryContext,
            retryDiagnostic: String? = priorRejectedCodingDiagnostic,
        ) = CodingWorkerModelEnvelope(
            executionProfile = profile,
            workflowStepId = CODING_WORKFLOW_STEP_ID,
            allowedActions = workPackage?.ownership?.allowedActions
                ?: listOf(CODING_FILE_WRITE, CODING_FILE_REPLACE, CODING_FILE_DELETE),
            forbiddenActions = listOf("EXECUTE_COMMAND", "APPROVE_CRITERION", "COMPLETE_WORKFLOW", "PUSH", "MERGE"),
            requiredOutputSchema = if (workPackage == null) CODING_PROPOSAL_SCHEMA else BOUNDED_TOOL_BATCH_SCHEMA,
            currentRevision = requireNotNull(currentRevision),
            run = codingWorkerRunProjection(run),
            executionPlan = if (workPackage == null) executionPlan?.let(::codingExecutionPlanProjection) else null,
            workPackage = workPackage?.let(::codingWorkPackageProjection),
            priorRejectedCodingDiagnostic = retryDiagnostic,
            repositoryContext = repositoryContext,
        )
        fun prompt(
            repositoryContext: CodingRepositoryContext,
            retryDiagnostic: String? = priorRejectedCodingDiagnostic,
        ): String {
            val envelopeJson = json.encodeToString(envelope(repositoryContext, retryDiagnostic))
            val promptPolicy = if (workPackage == null) systemPrompt else BOUNDED_TOOL_SYSTEM_PROMPT
            val literalOnlyMarker = workPackage?.ownership?.paths
                ?.takeIf { it.isNotEmpty() && it.all(::isCandidateTestSourcePath) }
                ?.let { "\nREQUIRE_LITERAL_REPLACEMENTS" }
                .orEmpty()
            return "$promptPolicy$literalOnlyMarker\n\nAuthoritative coding execution envelope:\n$envelopeJson"
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
                    repositoryRevision = requireNotNull(currentRevision),
                    paths = contextPaths,
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
        val proposalDecode = if (outputWithinBudget && workPackage == null) {
            runCatching { strictOutputJson.decodeFromString<CodingPatchProposal>(generation.text) }
        } else null
        val toolBatchDecode = if (outputWithinBudget && workPackage != null) {
            runCatching { strictOutputJson.decodeFromString<BoundedCodingToolBatch>(generation.text) }
        } else null
        val proposal = proposalDecode?.getOrNull()
        val toolBatch = toolBatchDecode?.getOrNull()
        val schemaValid = proposal != null || toolBatch != null
        val modelExecution = recordModelExecution(
            profile,
            binding,
            run,
            envelopeJson,
            prompt,
            generation,
            elapsedMillis(startedAt),
            schemaValid,
            admission.evidence,
        ) ?: return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
            "Model execution provenance could not be saved.",
        )
        if (!schemaValid) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.INVALID_PROPOSAL,
            codingModelOutputDiagnostic(
                generation = generation,
                profile = profile,
                expectedSchema = if (workPackage == null) CODING_PROPOSAL_SCHEMA else BOUNDED_TOOL_BATCH_SCHEMA,
                decodeFailure = (proposalDecode ?: toolBatchDecode)?.exceptionOrNull(),
            ),
            modelExecutionId = modelExecution.executionId,
        )
        val proposalHash = sha256(
            proposal?.let { strictOutputJson.encodeToString(it) }
                ?: strictOutputJson.encodeToString(requireNotNull(toolBatch))
        )
        if (executionPlan != null && proposal != null) {
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
                ?: codingProposalBehaviorDiagnostic(
                    proposal,
                    run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
                    repositoryContext,
                )
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
            if (toolBatch != null) {
                workspaceGateway.applyBoundedToolBatch(
                    requireNotNull(run.context.workspaceReservation).path,
                    requireNotNull(workPackage),
                    toolBatch,
                    claim.executionId,
                )
            } else {
                workspaceGateway.applyAndCommit(
                    requireNotNull(run.context.workspaceReservation).path,
                    requireNotNull(proposal),
                    claim.executionId,
                )
            }
        }.getOrElse { error ->
            val applicationDiagnostic = proposal?.let { codingApplicationDiagnostic(error.message.orEmpty(), it) }
                ?: "The bounded coding tool batch could not be applied: ${error.message.orEmpty()}"
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
                if (toolBatch == null) CodingWorkerTickStatus.APPLICATION_FAILED else CodingWorkerTickStatus.PLAN_BLOCKED,
                applicationDiagnostic,
                modelExecution.executionId,
                proposalHash,
            )
        }
        val semanticDiagnostic = executionPlan?.let { plan ->
            val productionPaths = plan.content.scopeCoverage
                .flatMap { it.evidencePaths }
                .filterNot(::isCandidateTestSourcePath)
                .filter { it in candidate.changedPaths }
                .distinct()
            val candidateContext = runCatching {
                workspaceGateway.collectIntelligenceContext(
                    requireNotNull(run.context.workspaceReservation).path,
                    candidate.revision,
                    productionPaths,
                )
            }.getOrElse { error ->
                return@let "Candidate semantic verification could not inspect scoped production paths: ${error.message.orEmpty()}"
            }
            candidateForbiddenLiteralDiagnostic(
                run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
                candidateContext,
            )
        }
        if (semanticDiagnostic != null) {
            val correctionStorageDiagnostic = executionPlan?.let { plan ->
                recordCorrectiveRejection(
                    run.runId,
                    plan.planId,
                    plan.hash,
                    proposalHash,
                    semanticDiagnostic,
                )
            }
            val failed = finish(
                claim,
                CODING_EXECUTION_FAILED,
                if (correctionStorageDiagnostic == null) {
                    CodingWorkerTickStatus.VERIFICATION_FAILED
                } else {
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE
                },
                correctionStorageDiagnostic ?: semanticDiagnostic,
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
            if (failed.status != CodingWorkerTickStatus.STORAGE_UNAVAILABLE && workPackage == null) {
                runCatching {
                    workspaceGateway.revertCandidate(
                        requireNotNull(run.context.workspaceReservation).path,
                        candidate.revision,
                        claim.executionId,
                    )
                }
            }
            return failed
        }
        val evidenceResult = submitEvidence(run, candidate, toolchainPolicy)
        return if (evidenceResult == null) {
            if (workPackage != null) {
                val pullRequest = runCatching {
                    val priorPullRequests = pullRequestStore.load()
                    priorPullRequests.lastOrNull {
                        it.runId == run.runId && it.candidateRevision == candidate.revision
                    } ?: pullRequestStore.appendNext { pullRequestId ->
                        val evidence = workspace.snapshot(MESSAGE_READY).workflowRuns.single { it.runId == run.runId }.evidence
                        newCandidatePullRequest(
                            pullRequestId,
                            workPackage,
                            candidate,
                            evidence,
                            parentPullRequestId = priorPullRequests.lastOrNull { it.runId == run.runId }?.pullRequestId,
                        )
                    }.also { created ->
                        dispositionService?.record(
                            created.pullRequestId,
                            CANDIDATE_DISPOSITION_REVIEW_REQUIRED,
                            "Candidate was frozen and awaits independent review.",
                        )
                        created.parentPullRequestId?.let { parentPullRequestId ->
                            dispositionService?.record(
                                parentPullRequestId,
                                CANDIDATE_DISPOSITION_SUPERSEDED,
                                "Corrective candidate ${created.pullRequestId} was frozen.",
                            )
                        }
                    }
                }
                if (pullRequest.isFailure) return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    pullRequest.exceptionOrNull()?.message,
                    modelExecution.executionId,
                    proposalHash,
                    candidate,
                )
            }
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
            if (workPackage != null) {
                val plan = requireNotNull(executionPlan)
                val storageDiagnostic = recordCorrectiveRejection(
                    run.runId,
                    plan.planId,
                    plan.hash,
                    proposalHash,
                    evidenceResult,
                )
                if (storageDiagnostic != null) return finish(
                    claim,
                    CODING_EXECUTION_FAILED,
                    CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
                    storageDiagnostic,
                    modelExecution.executionId,
                    proposalHash,
                    candidate,
                )
            }
            val failed = finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.VERIFICATION_FAILED,
                evidenceResult,
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
            if (failed.status != CodingWorkerTickStatus.STORAGE_UNAVAILABLE && workPackage == null) {
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
        baseRevision: String?,
    ): String? {
        val failed = executions.lastOrNull {
            it.claim.runId == runId &&
                it.result?.status == CODING_EXECUTION_FAILED &&
                it.result.revision != null
        } ?: return null
        val pinnedBaseRevision = baseRevision ?: return "Failed candidate recovery requires the execution plan base revision."
        val candidateRevision = requireNotNull(failed.result?.revision)
        if (workspaceGateway.currentRevision(workspacePath) != candidateRevision) return null
        return runCatching {
            workspaceGateway.restoreTree(workspacePath, candidateRevision, pinnedBaseRevision, runId)
        }.exceptionOrNull()?.message ?: return null
    }

    private fun candidateRuns(executions: List<CodingWorkerExecutionView>): List<WorkflowRunView> {
        val codingAttempts = attemptStore.load()
        val repositoryPlans = repositoryAnalysis?.plans().orEmpty()
        return workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED) }
            .filter { run ->
                run.context.circuitDispatchId != null &&
                    run.context.workspaceReservation?.mode in setOf("ISOLATED", "INTEGRATION")
            }
            .filter { run ->
                val hasRepositoryPlan = repositoryPlans.any { it.runId == run.runId }
                val currentPlan = if (hasRepositoryPlan) repositoryAnalysis?.currentPlan(run.runId) else null
                hasRepositoryPlan && codingRunCanExecute(
                    executions = executions.filter { it.claim.runId == run.runId },
                    attempts = codingAttempts.filter { it.runId == run.runId },
                    currentPlan = currentPlan,
                    bindToCurrentPlan = repositoryAnalysis != null,
                    retryBudget = retryBudget,
                ) || run.state == RUN_STATE_EVIDENCE_BLOCKED && executions.lastOrNull {
                    it.claim.runId == run.runId
                }?.let { execution ->
                    currentPlan?.let { plan ->
                        execution.claim.executionPlanId == plan.planId &&
                            execution.claim.executionPlanHash == plan.hash &&
                            execution.claim.workPackageId != null &&
                            execution.result?.status == CODING_EXECUTION_COMPLETED
                    } == true
                } == true
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
        workPackage: ExecutableWorkPackage?,
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
            workPackageId = workPackage?.packageId,
            workPackageHash = workPackage?.hash,
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
        authorizeCorrection: Boolean = true,
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
            if (
                authorizeCorrection &&
                !repeatedRejection &&
                automaticCodingCorrectionAvailable(attempts, runId, planId, planHash)
            ) {
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
                val reservation = runs[failed.claim.runId]?.context?.workspaceReservation ?: return@forEach
                restoreLatestFailedCandidate(failed.claim.runId, reservation.path, executions, reservation.baseRevision)
            }
        executions.groupBy { it.claim.runId }.forEach { (runId, runExecutions) ->
            if (runExecutions.none { it.result?.revision != null } || runExecutions.any { it.result?.status == CODING_EXECUTION_COMPLETED }) {
                return@forEach
            }
            val reservation = runs[runId]?.context?.workspaceReservation ?: return@forEach
            val currentRevision = workspaceGateway.currentRevision(reservation.path) ?: return@forEach
            runCatching {
                workspaceGateway.restoreTree(reservation.path, currentRevision, reservation.baseRevision, runId)
            }
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
                        authorizeCorrection = false,
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
                .filter { execution -> execution.result?.let(::codingTerminalPlanBlockRequired) == true }
            .forEach { execution -> recordTerminalPlanBlock(execution.claim, requireNotNull(execution.result)) }
    }

    private fun recordTerminalPlanBlock(claim: CodingWorkerClaim, result: CodingWorkerResult): String? {
        val planId = claim.executionPlanId ?: return null
        val planHash = claim.executionPlanHash ?: return null
        if (!codingTerminalPlanBlockRequired(result)) return null
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
        const val BOUNDED_TOOL_BATCH_SCHEMA = "bounded-coding-tool-batch-v1"
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

        private val BOUNDED_TOOL_SYSTEM_PROMPT = """
            You are Orchard's bounded implementation worker.

            Return exactly one compact JSON object matching bounded-coding-tool-batch-v1:
            {"summary":"short implementation description","expectedRevision":"40-character current revision from the envelope","operations":[{"action":"REWRITE_FILE|CREATE_FILE|DELETE_FILE|REPLACE_LITERAL","path":"authorized relative path","content":null,"expectedLiteral":null,"replacement":null,"expectedCount":null}]}

            Treat workPackage as complete intent, design, ownership, source, check, and escalation authority. Implement the required behavior without redesigning it. Use only paths inside workPackage.ownership.paths and only actions allowed by workPackage.ownership.allowedActions. REWRITE_FILE is valid only when content is a non-null complete resulting UTF-8 file; never emit a REWRITE_FILE with null content. For localized edits, prefer REPLACE_LITERAL, which requires non-null expectedLiteral, replacement, and exact expectedCount. CREATE_FILE is valid only for workPackage.ownership.createPaths. Use expectedRevision from the current repository context. Do not emit exact source anchors, commands, Markdown, approvals, evidence, Git actions, or claims that checks passed.
            The operations array must contain only operation objects. Every array element must be an object with an action and path; never put a string, source excerpt, explanation, or nested array in operations. Do not append prose before or after the JSON object. Before responding, validate that the complete response is one parseable JSON object, that operations is an array of objects, and that every operation matches one of the allowed payload shapes.
            Keep summary short, omit optional JSON whitespace, and include no fields beyond the schema. Prefer the smallest complete set of localized replacements; do not repeat unchanged source or include explanations inside operation fields.
            If a prior rejection says a WRITE appears truncated, do not emit WRITE or REWRITE_FILE for that path. Emit only bounded REPLACE_LITERAL operations with exact anchors from the current source. A source anchor is one contiguous substring: copy expectedLiteral exactly from one quoted source window and never combine separate windows, prefixes, or suffixes.
        """.trimIndent()
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

internal fun codingApplicationDiagnostic(error: String, proposal: CodingPatchProposal): String {
    val base = "The coding proposal could not be applied: $error"
    if ("git diff --check" !in error) return base
    val proposalJson = Json.encodeToString(proposal)
    if (proposalJson.encodeToByteArray().size > MAX_RETRY_PROPOSAL_BYTES) return base
    return "$base Prior proposal JSON to correct without redesign: $proposalJson"
}

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

internal fun codingPlanContextPaths(executionPlan: RepositoryExecutionPlan?): List<String> = executionPlan?.content?.let { plan ->
    (plan.operations.filter { it.action != "VERIFY" }.map { it.path } +
        plan.scopeCoverage.flatMap { it.evidencePaths })
        .distinct()
        .take(8)
}.orEmpty()

internal fun codingWorkPackageContextPaths(executionPlan: RepositoryExecutionPlan): List<String> =
    (executionPlan.content.operations.filter { it.action != "VERIFY" }.map { it.path } +
        executionPlan.content.scopeCoverage.flatMap { it.evidencePaths })
        .distinct()

internal fun codingExecutionPlanProjection(plan: RepositoryExecutionPlan): RepositoryExecutionPlan {
    val implementationPaths = plan.content.operations
        .filter { it.action != "VERIFY" }
        .mapTo(hashSetOf()) { it.path }
    return plan.copy(
        content = plan.content.copy(
            operations = plan.content.operations.filter { it.action != "VERIFY" },
            evidence = plan.content.evidence.filter { it.path in implementationPaths },
            scopeCoverage = plan.content.scopeCoverage.map { coverage ->
                coverage.copy(
                    evidencePaths = coverage.evidencePaths.filter { it in implementationPaths },
                    compliantEvidencePaths = coverage.compliantEvidencePaths.filter { it in implementationPaths },
                )
            },
        ),
    )
}

internal fun codingWorkPackageProjection(workPackage: ExecutableWorkPackage): ExecutableWorkPackage =
    workPackage.copy(
        evidence = WorkPackageEvidenceAuthority(),
        sources = workPackage.sources.map { source ->
            source.copy(content = "")
        },
    )

internal fun codingTerminalPlanBlockRequired(result: CodingWorkerResult): Boolean =
    result.status in setOf(CODING_EXECUTION_BLOCKED, CODING_EXECUTION_INTERRUPTED) ||
        (result.status == CODING_EXECUTION_FAILED && (
            result.revision != null || result.diagnostic?.startsWith(CODING_OUTPUT_REJECTED_PREFIX) == true
        ))

internal fun candidateForbiddenLiteralDiagnostic(
    acceptanceCriteria: List<String>,
    context: CodingRepositoryContext,
): String? {
    if (context.omittedFileCount != 0) return "Candidate semantic verification is missing scoped production paths."
    val forbiddenLiterals = acceptanceCriteria.flatMap { criterion ->
        Regex(
            "\\bnone\\b[^.]*?\\bcontains\\s+([A-Za-z_][A-Za-z0-9_.]*)",
            RegexOption.IGNORE_CASE,
        ).findAll(criterion).map { it.groupValues[1] }.toList()
    }.distinct()
    context.files.forEach { file ->
        forbiddenLiterals.forEach { literal ->
            val count = Regex(Regex.escape(literal), RegexOption.IGNORE_CASE).findAll(file.content).count()
            if (count > 0) {
                return "Candidate retains forbidden literal $literal $count time${if (count == 1) "" else "s"} in ${file.path}." +
                    ambiguousReplacementAnchorDiagnostic(file.content, literal)
            }
        }
    }
    return null
}

private fun isCandidateTestSourcePath(path: String): Boolean {
    val normalized = path.replace('\\', '/').lowercase()
    return "/test/" in normalized || normalized.substringAfterLast('/').contains("test.")
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
): Boolean = attempts.count {
    it.runId == runId &&
        it.executionPlanId == planId &&
        it.executionPlanHash == planHash &&
        it.state == CODING_ATTEMPT_RETRY_AUTHORIZED
} < MAX_AUTOMATIC_CODING_CORRECTIONS

private const val MAX_AUTOMATIC_CODING_CORRECTIONS = 3

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
                val literalAnchors = contextFile?.let { ambiguousReplacementAnchorDiagnostic(it.content, replacement.old) }.orEmpty()
                return buildString {
                    append("The coding proposal reuses a previously rejected source anchor: REPLACE ")
                    append(operation.path).append(" replacement ").append(index + 1).append("; ")
                    append(fingerprint).append(". Select a different exact anchor from the supplied source.")
                    append(literalAnchors)
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

internal fun firstFailingKotlinTestPath(
    plan: RepositoryExecutionPlan,
    diagnostic: String?,
): String? {
    if (diagnostic == null || !diagnostic.contains("Unresolved reference")) return null
    return plan.content.operations.asSequence()
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .map { it.path }
        .filter { it.endsWith("Test.kt") && diagnostic.contains(it) }
        .firstOrNull()
}

internal fun sourceGroundedRetryDiagnostic(
    diagnostic: String?,
    repositoryContext: CodingRepositoryContext,
): String? {
    if (diagnostic == null || SOURCE_GROUNDED_RETRY_MARKER in diagnostic) return diagnostic
    val anchors = repositoryContext.files.asSequence()
        .filter { it.path in diagnostic }
        .flatMap { contextFile ->
            val sourceLines = contextFile.content.lineSequence()
                .filterNot { it.startsWith("[Orchard excerpt lines ") }
                .toList()
            val compilerWindows = compilerDiagnosticLineNumbers(diagnostic, contextFile.path).mapNotNull { lineNumber ->
                sourceLines.getOrNull(lineNumber - 1)?.let {
                    sourceLines.subList(
                        maxOf(0, lineNumber - RETRY_COMPILER_WINDOW_RADIUS - 1),
                        minOf(sourceLines.size, lineNumber + RETRY_COMPILER_WINDOW_RADIUS),
                    ).joinToString("\n")
                }
            }
            val sourcePrefix = sourceLines
                .joinToString("\n")
                .let { takeUtf8Prefix(it, MAX_RETRY_SOURCE_PREFIX_BYTES) }
            val sourceSuffix = sourceLines
                .asReversed()
                .runningFold(emptyList<String>()) { lines, line -> listOf(line) + lines }
                .takeWhile { it.joinToString("\n").encodeToByteArray().size <= MAX_RETRY_SOURCE_SUFFIX_BYTES }
                .lastOrNull()
                .orEmpty()
                .joinToString("\n")
            val literalAnchors = RETRY_DIAGNOSTIC_LITERAL.findAll(diagnostic)
                .map { it.value }
                .filter { literal -> Regex(Regex.escape(literal)).findAll(contextFile.content).count() > 1 }
                .distinct()
                .map { ambiguousReplacementAnchorDiagnostic(contextFile.content, it) }
                .filter(String::isNotEmpty)
                .map { takeUtf8Prefix(it.substringAfter(": "), MAX_RETRY_LITERAL_ANCHOR_BYTES) }
                .take(1)
                .toList()
            (literalAnchors + compilerWindows.ifEmpty { listOf(sourcePrefix, sourceSuffix) })
                .filter(String::isNotEmpty)
                .distinct()
                .map { contextFile.path to it }
        }
        .take(MAX_RETRY_SOURCE_ANCHORS)
        .toList()
    if (anchors.isEmpty()) return diagnostic
    return buildString {
        append(diagnostic).append(' ').append(SOURCE_GROUNDED_RETRY_MARKER).append(": ")
        append(anchors.joinToString(" | ") { (path, anchor) -> "$path ${Json.encodeToString(anchor)}" })
        append('.')
    }
}

private fun compilerDiagnosticLineNumbers(diagnostic: String, path: String): List<Int> =
    Regex("${Regex.escape(path)}:(\\d+):").findAll(diagnostic)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .distinct()
        .take(MAX_RETRY_COMPILER_WINDOWS)
        .toList()

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

internal fun codingProposalBehaviorDiagnostic(
    proposal: CodingPatchProposal,
    acceptanceCriteria: List<String>,
    context: CodingRepositoryContext,
): String? {
    val files = context.files.associateBy { it.path }
    val forbiddenLiterals = acceptanceCriteria.flatMap { criterion ->
        Regex("\\bnone\\b[^.]*?\\bcontains\\s+([A-Za-z_][A-Za-z0-9_.]*)", RegexOption.IGNORE_CASE)
            .findAll(criterion)
            .map { it.groupValues[1] }
            .toList()
    }.distinct()
    proposal.operations.filter { it.action in setOf(CODING_FILE_REPLACE, CODING_FILE_WRITE) }.forEach { operation ->
        val original = files[operation.path]?.content ?: return@forEach
        var candidate = when (operation.action) {
            CODING_FILE_WRITE -> operation.content ?: return "WRITE ${operation.path} must include file content."
            else -> original
        }
        if (operation.action == CODING_FILE_REPLACE) {
            operation.replacements.forEach { replacement ->
                candidate = candidate.replaceFirst(replacement.old, replacement.new)
            }
        }
        forbiddenLiterals.forEach { literal ->
            val before = Regex(Regex.escape(literal), RegexOption.IGNORE_CASE).findAll(original).count()
            val after = Regex(Regex.escape(literal), RegexOption.IGNORE_CASE).findAll(candidate).count()
            if (before > 0 && after >= before) {
                return "REPLACE ${operation.path} must reduce forbidden literal $literal from its pinned count of $before; proposed count is $after."
            }
        }
        if (isCandidateTestSourcePath(operation.path) &&
            TAUTOLOGICAL_TRUE_ASSERTION.containsMatchIn(candidate) &&
            !TAUTOLOGICAL_TRUE_ASSERTION.containsMatchIn(original)
        ) {
            return "REPLACE ${operation.path} introduces a tautological constant-true assertion; required test assertions must depend on governed production behavior or source."
        }
        if (isCandidateTestSourcePath(operation.path) &&
            NULLABLE_ASSERT_TRUE.containsMatchIn(candidate) &&
            !NULLABLE_ASSERT_TRUE.containsMatchIn(original)
        ) {
            return "REPLACE ${operation.path} introduces assertTrue with a nullable condition; assert the nullable value explicitly before testing its property."
        }
        if (isCandidateTestSourcePath(operation.path) &&
            ASSERT_NOT_NULL_CALL.containsMatchIn(candidate) &&
            !ASSERT_NOT_NULL_CALL.containsMatchIn(original) &&
            !KOTLIN_TEST_ASSERT_NOT_NULL_IMPORT.containsMatchIn(candidate)
        ) {
            return "${operation.action} ${operation.path} introduces assertNotNull without importing kotlin.test.assertNotNull."
        }
        if (isCandidateTestSourcePath(operation.path)) {
            operation.replacements.forEach { replacement ->
                val replacedEndpoints = CLIENT_ENDPOINT_CALL.findAll(replacement.old).map { it.groupValues[1] }.toSet()
                val replacementEndpoints = CLIENT_ENDPOINT_CALL.findAll(replacement.new).map { it.groupValues[1] }.toSet()
                val introducedReplacementEndpoints = replacementEndpoints - replacedEndpoints
                if (replacedEndpoints.isNotEmpty() && introducedReplacementEndpoints.isNotEmpty()) {
                    return "REPLACE ${operation.path} adds unrelated client endpoint call${if (introducedReplacementEndpoints.size == 1) "" else "s"} " +
                        "${introducedReplacementEndpoints.sorted().joinToString()} to an existing endpoint test; extend its current endpoint behavior instead."
                }
                val introducedLocals = KOTLIN_LOCAL_DECLARATION.findAll(replacement.new)
                    .map { it.groupValues[1] }
                    .toSet() - KOTLIN_LOCAL_DECLARATION.findAll(replacement.old).map { it.groupValues[1] }.toSet()
                val replacementOffset = original.indexOf(replacement.old)
                if (replacementOffset >= 0 && introducedLocals.isNotEmpty()) {
                    val followingSource = original.substring((replacementOffset + replacement.old.length).coerceAtMost(original.length))
                    val followingLocals = KOTLIN_LOCAL_DECLARATION.findAll(followingSource)
                        .map { it.groupValues[1] }
                        .toSet()
                    val shadowedFollowingLocals = introducedLocals intersect followingLocals
                    if (shadowedFollowingLocals.isNotEmpty()) {
                        return "REPLACE ${operation.path} introduces local declaration${if (shadowedFollowingLocals.size == 1) "" else "s"} " +
                            "${shadowedFollowingLocals.sorted().joinToString()} before an existing declaration in the same test; preserve the existing local names."
                    }
                }
            }
            val originalEndpoints = CLIENT_ENDPOINT_CALL.findAll(original).map { it.groupValues[1] }.toSet()
            val candidateEndpoints = CLIENT_ENDPOINT_CALL.findAll(candidate).map { it.groupValues[1] }.toSet()
            val unrelatedEndpoints = candidateEndpoints - originalEndpoints
            if (originalEndpoints.isNotEmpty() && unrelatedEndpoints.isNotEmpty()) {
                return "REPLACE ${operation.path} adds unrelated client endpoint call${if (unrelatedEndpoints.size == 1) "" else "s"} " +
                    "${unrelatedEndpoints.sorted().joinToString()}; extend the existing endpoint behavior or create a separately scoped test."
            }
            val originalDuplicates = duplicateKotlinLocalDeclarations(original)
            val candidateDuplicates = duplicateKotlinLocalDeclarations(candidate)
            val introducedDuplicates = candidateDuplicates - originalDuplicates
            if (introducedDuplicates.isNotEmpty()) {
                return "REPLACE ${operation.path} introduces duplicate local declaration${if (introducedDuplicates.size == 1) "" else "s"} " +
                    introducedDuplicates.sorted().joinToString() + "; preserve the existing test's local names."
            }
        }
    }
    return null
}

private fun duplicateKotlinLocalDeclarations(source: String): Set<String> {
    val duplicates = linkedSetOf<String>()
    kotlinFunctionBodies(source).forEach { body ->
        val declarations = KOTLIN_LOCAL_DECLARATION.findAll(body)
            .map { it.groupValues[1] }
            .toList()
        declarations.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach(duplicates::add)
    }
    return duplicates
}

private fun kotlinFunctionBodies(source: String): List<String> = buildList {
    KOTLIN_FUNCTION_START.findAll(source).forEach { function ->
        val openingBrace = function.range.last
        var depth = 1
        var index = openingBrace + 1
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            index += 1
        }
        if (depth == 0) add(source.substring(openingBrace + 1, index - 1))
    }
}

private const val MAX_REJECTED_ANCHOR_DECLARATIONS = 5
private const val MAX_REJECTED_ANCHOR_EXAMPLES = 2
private const val SOURCE_ANCHOR_LINES = 4
private const val SOURCE_GROUNDED_RETRY_MARKER = "Exact contiguous source text for this correction"
private const val MAX_SOURCE_ANCHOR_BYTES = 1_024
private const val MAX_RETRY_SOURCE_PREFIX_BYTES = 512
private const val MAX_RETRY_SOURCE_SUFFIX_BYTES = 512
private const val MAX_RETRY_LITERAL_ANCHOR_BYTES = 2_048
private const val MAX_RETRY_SOURCE_ANCHORS = 4
private const val MAX_RETRY_COMPILER_WINDOWS = 2
private const val RETRY_COMPILER_WINDOW_RADIUS = 6
private const val MAX_RETRY_PROPOSAL_BYTES = 16 * 1024
private val CLIENT_ENDPOINT_CALL = Regex("\\bclient\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
private val KOTLIN_FUNCTION_START = Regex("\\bfun\\b[^\\n{]*\\{")
private val KOTLIN_LOCAL_DECLARATION = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
private val NULLABLE_ASSERT_TRUE = Regex("\\bassertTrue\\s*\\([^\\n]*\\?\\.")
private val ASSERT_NOT_NULL_CALL = Regex("\\bassertNotNull\\s*\\(")
private val KOTLIN_TEST_ASSERT_NOT_NULL_IMPORT = Regex("(?m)^import\\s+kotlin\\.test\\.assertNotNull\\s*$")
internal fun codingModelOutputDiagnostic(
    generation: ModelGeneration,
    profile: ModelExecutionProfile,
    expectedSchema: String,
    decodeFailure: Throwable?,
): String {
    val budgetFailure = when {
        generation.promptTokens > profile.inputBudgetTokens ->
            "prompt tokens ${generation.promptTokens} exceed input budget ${profile.inputBudgetTokens}"
        generation.completionTokens > profile.outputBudgetTokens ->
            "completion tokens ${generation.completionTokens} exceed output budget ${profile.outputBudgetTokens}"
        estimateModelTokens(generation.text) > profile.outputBudgetTokens ->
            "serialized response estimate ${estimateModelTokens(generation.text)} exceeds output budget ${profile.outputBudgetTokens}"
        else -> null
    }
    if (budgetFailure != null) {
        return "Coding model output rejected for $expectedSchema: $budgetFailure."
    }
    val parserFailure = decodeFailure?.message
        ?.replace(Regex("\\s+"), " ")
        ?.take(512)
    return if (parserFailure.isNullOrBlank()) {
        "Coding model output rejected for $expectedSchema: response was not valid strict JSON."
    } else {
        "Coding model output rejected for $expectedSchema: strict JSON decoding failed: $parserFailure"
    }
}

private const val CODING_OUTPUT_REJECTED_PREFIX = "Coding model output rejected for "
private const val SOURCE_GROUNDING_CONTEXT_RESERVE_BYTES = 4_096
private const val MIN_SOURCE_ANCHOR_TOKEN_LENGTH = 4
private val CAMEL_CASE_TOKEN = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+")
private val RETRY_DIAGNOSTIC_LITERAL = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
private val TAUTOLOGICAL_TRUE_ASSERTION = Regex("\\b(?:assertTrue\\s*\\(\\s*true\\s*\\)|assertEquals\\s*\\(\\s*true\\s*,\\s*true\\s*\\))")
