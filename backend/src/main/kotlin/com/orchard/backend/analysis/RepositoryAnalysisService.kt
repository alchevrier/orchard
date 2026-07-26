package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingWorkerAttempt
import com.orchard.backend.agent.CodingWorkerAttemptStore
import com.orchard.backend.agent.CodingWorkerEvent
import com.orchard.backend.agent.CodingWorkerStore
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.CODING_ATTEMPT_BLOCKED
import com.orchard.backend.agent.CODING_EXECUTION_FAILED
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.agent.codingWorkerExecutions
import com.orchard.backend.agent.focusedContextExcerpt
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.company.RISK_HIGH
import com.orchard.backend.company.ROLE_ANALYST_DESIGNER
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ModelWorkPriority
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.RepositoryEvidenceSelector
import com.orchard.backend.workspace.REPOSITORY_EVIDENCE_AFFINE_TEST
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RepositoryAnalysisTickStatus {
    IDLE,
    BUSY,
    PLAN_CREATED,
    ARCHITECT_DECISION_REQUIRED,
    PLAN_STALE,
    CONTEXT_UNAVAILABLE,
    CONTEXT_BUDGET_EXCEEDED,
    NO_COMPATIBLE_MODEL,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_ANALYSIS,
    STORAGE_UNAVAILABLE,
    ATTEMPT_BLOCKED,
    RETRY_AUTHORIZED,
    CANCELLED,
}

@Serializable
data class RepositoryAnalysisTickResult(
    val status: RepositoryAnalysisTickStatus,
    val runId: Long? = null,
    val plan: RepositoryExecutionPlan? = null,
    val diagnostic: String = "",
)

@Serializable
private data class RequiredRepositoryEvidence(
    val path: String,
    val contentHash: String,
)

@Serializable
private data class RequiredEvidencePathGroup(
    val id: String,
    val paths: List<String>,
)

@Serializable
private data class RepositoryAnalysisTaskContext(
    val runId: Long,
    val title: String,
    val content: String,
    val requestedOutcome: String,
    val currentBehavior: String,
    val requiredBehavior: String,
    val nonGoals: List<String>,
    val constraints: List<String>,
    val reproduction: String,
    val regressionCriterion: String,
    val recalledEvidence: List<String>,
)

@Serializable
internal data class RepositoryForbiddenLiteralFact(
    val path: String,
    val literal: String,
    val count: Int,
)

@Serializable
private data class RepositoryAnalysisEnvelope(
    val executionProfileId: String,
    val baseRevision: String,
    val task: RepositoryAnalysisTaskContext,
    val repositoryContext: CodingRepositoryContext,
    val allowedDispositions: List<String>,
    val requiredOutputSchema: String,
    val requiredEvidence: List<RequiredRepositoryEvidence>,
    val requiredScope: List<String>,
    val requiredEvidencePathGroups: List<RequiredEvidencePathGroup>,
    val requiredScopeEvidencePathGroupIds: List<List<String>>,
    val forbiddenLiteralFacts: List<RepositoryForbiddenLiteralFact>,
    val priorRejectedAnalysisDiagnostic: String?,
    val priorRejectedCodingPlanDiagnostic: String?,
    val requiredAcceptanceCriteria: List<String>,
    val requiredVerificationCommands: List<String>,
)

class RepositoryAnalysisService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val planStore: RepositoryExecutionPlanStore = TransientRepositoryExecutionPlanStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val systemPrompt: String = loadPrompt(),
    private val companyControl: CompanyControlService? = null,
    private val attemptStore: RepositoryAnalysisAttemptStore = TransientRepositoryAnalysisAttemptStore(),
    private val codingAttemptStore: CodingWorkerAttemptStore? = null,
    private val codingWorkerStore: CodingWorkerStore? = null,
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()

    init {
        planStore.load()
        attemptStore.load()
        bootstrapInterruptedRetryBlocks()
        bootstrapLegacyAttemptBlocks()
    }

    fun plans(): List<RepositoryExecutionPlan> = planStore.load()

    fun attempts(): List<RepositoryAnalysisAttempt> = attemptStore.load()

    private fun bootstrapInterruptedRetryBlocks() {
        val attempts = attemptStore.load()
        workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .forEach { run ->
                val baseRevision = workspaceGateway.currentRevision(requireNotNull(run.context.workspaceReservation).path)
                    ?: return@forEach
                val authorization = attempts.lastOrNull { it.runId == run.runId && it.baseRevision == baseRevision }
                    ?.takeIf { it.state == ANALYSIS_ATTEMPT_RETRY_AUTHORIZED }
                    ?: return@forEach
                val interrupted = workspace.modelExecutions(run.context.workItemId)
                    .filter {
                        it.workflowStepId == DefaultModelExecutionProfiles.broadRepositoryAnalysis.id &&
                            it.outputHash == null &&
                            Instant.parse(it.recordedAt).isAfter(Instant.parse(authorization.recordedAt))
                    }
                    .maxByOrNull { it.executionId }
                    ?: return@forEach
                attemptStore.appendNext { attemptId ->
                    RepositoryAnalysisAttempt(
                        attemptId = attemptId,
                        runId = run.runId,
                        baseRevision = baseRevision,
                        state = ANALYSIS_ATTEMPT_BLOCKED,
                        resultStatus = RepositoryAnalysisTickStatus.CANCELLED.name,
                        diagnostic = "Repository analysis execution was interrupted before producing an admissible plan.",
                        promptHash = interrupted.promptHash,
                    )
                }
            }
    }

    private fun bootstrapLegacyAttemptBlocks() {
        val plans = planStore.load()
        val attempts = attemptStore.load()
        workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .filter { run ->
                val baseRevision = workspaceGateway.currentRevision(requireNotNull(run.context.workspaceReservation).path)
                    ?: return@filter false
                plans.none { it.runId == run.runId && it.baseRevision == baseRevision } &&
                    attempts.none { it.runId == run.runId && it.baseRevision == baseRevision }
            }
            .forEach { run ->
                val baseRevision = workspaceGateway.currentRevision(requireNotNull(run.context.workspaceReservation).path)
                    ?: return@forEach
                val repeated = workspace.modelExecutions(run.context.workItemId)
                    .filter {
                        it.workflowStepId == DefaultModelExecutionProfiles.broadRepositoryAnalysis.id &&
                            it.outputHash != null
                    }
                    .groupBy { Triple(it.promptHash, it.outputHash, it.schemaValid) }
                    .values
                    .filter { it.size >= LEGACY_IDENTICAL_OUTCOME_BLOCK_THRESHOLD }
                    .maxByOrNull { executions -> executions.maxOf { it.executionId } }
                    ?: return@forEach
                attemptStore.appendNext { attemptId ->
                    RepositoryAnalysisAttempt(
                        attemptId = attemptId,
                        runId = run.runId,
                        baseRevision = baseRevision,
                        state = ANALYSIS_ATTEMPT_BLOCKED,
                        resultStatus = RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                        diagnostic = "Automatic analysis blocked after repeated identical historical model outcomes produced no admissible execution plan.",
                        promptHash = repeated.maxBy { it.executionId }.promptHash,
                    )
                }
            }
    }

    fun currentPlan(runId: Long): RepositoryExecutionPlan? {
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == runId } ?: return null
        val staticCandidates = planStore.load().filter { it.runId == runId && it.coversAcceptedScope(run) }
        if (staticCandidates.isEmpty()) return null
        val workspacePath = run.context.workspaceReservation?.path ?: return null
        val selectors = run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty()
        val context = runCatching { workspaceGateway.collectAnalysisContext(workspacePath, analysisQuery(run), selectors) }.getOrNull() ?: return null
        val complianceContext = runCatching { collectComplianceContext(workspacePath, run, selectors, context) }.getOrNull() ?: return null
        return staticCandidates
            .filter { it.coversAcceptedScope(run, context, complianceContext) }
            .maxByOrNull { it.revision }
    }

    suspend fun tick(): RepositoryAnalysisTickResult {
        val runId = eligibleRunIds().firstOrNull() ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE)
        return tick(runId)
    }

    fun eligibleRunIds(): List<Long> {
        val plans = planStore.load()
        val analysisAttempts = attemptStore.load()
        val codingAttempts = codingAttemptStore?.load().orEmpty()
        return workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .sortedBy { it.runId }
            .filter { candidate ->
                val workspacePath = requireNotNull(candidate.context.workspaceReservation).path
                val currentRevision = workspaceGateway.currentRevision(workspacePath)
                if (currentRevision != null && attemptStore.isBlocked(candidate.runId, currentRevision)) {
                    return@filter false
                }
                if (analysisAttempts.lastOrNull {
                        it.runId == candidate.runId && it.baseRevision == currentRevision
                    }?.state == ANALYSIS_ATTEMPT_RETRY_AUTHORIZED
                ) {
                    return@filter true
                }
                val staticCandidates = plans.filter {
                    it.runId == candidate.runId && it.baseRevision == currentRevision && it.coversAcceptedScope(candidate)
                }
                if (staticCandidates.isEmpty()) return@filter true
                val context = runCatching {
                    workspaceGateway.collectAnalysisContext(
                        workspacePath,
                        analysisQuery(candidate),
                        candidate.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
                    )
                }.getOrNull() ?: return@filter true
                val selectors = candidate.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty()
                val complianceContext = runCatching {
                    collectComplianceContext(workspacePath, candidate, selectors, context)
                }.getOrNull() ?: return@filter true
                val currentPlan = staticCandidates.filter {
                    it.coversAcceptedScope(candidate, context, complianceContext)
                }.maxByOrNull { it.revision }
                currentPlan == null || repositoryPlanRequiresRevision(currentPlan, codingAttempts)
            }
            .map { it.runId }
            .toList()
    }

    suspend fun tick(runId: Long): RepositoryAnalysisTickResult {
        val mutex = runMutexes.computeIfAbsent(runId) { Mutex() }
        if (!mutex.tryLock()) return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.BUSY, runId)
        return try {
            analyze(runId)
        } finally {
            mutex.unlock()
        }
    }

    fun authorizeRetry(runId: Long): RepositoryAnalysisTickResult {
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .singleOrNull { it.runId == runId }
            ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE, runId)
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        val baseRevision = workspaceGateway.currentRevision(workspacePath)
            ?: return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE,
                runId,
                diagnostic = "The reserved repository revision is unavailable.",
            )
        val blocked = attemptStore.blockedAttempt(runId, baseRevision)
            ?: return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.IDLE,
                runId,
                diagnostic = "The repository analysis run has no blocked attempt to retry.",
            )
        return runCatching {
            attemptStore.appendNext { attemptId ->
                RepositoryAnalysisAttempt(
                    attemptId = attemptId,
                    runId = runId,
                    baseRevision = baseRevision,
                    state = ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                    resultStatus = RepositoryAnalysisTickStatus.RETRY_AUTHORIZED.name,
                    diagnostic = "A human explicitly authorized one successor repository analysis attempt.",
                    promptHash = blocked.promptHash,
                )
            }
        }.fold(
            onSuccess = {
                RepositoryAnalysisTickResult(
                    RepositoryAnalysisTickStatus.RETRY_AUTHORIZED,
                    runId,
                    diagnostic = it.diagnostic,
                )
            },
            onFailure = {
                RepositoryAnalysisTickResult(
                    RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE,
                    runId,
                    diagnostic = it.message.orEmpty(),
                )
            },
        )
    }

    private suspend fun analyze(runId: Long): RepositoryAnalysisTickResult {
        val plans = planStore.load()
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .singleOrNull { it.runId == runId }
            ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE, runId)
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        val baseRevision = workspaceGateway.currentRevision(workspacePath)
            ?: return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE,
                run.runId,
                diagnostic = "The reserved repository revision is unavailable.",
            )
        attemptStore.blockedAttempt(run.runId, baseRevision)?.let { blocked ->
            return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED,
                run.runId,
                diagnostic = blocked.diagnostic,
            )
        }
        val query = analysisQuery(run)
        val selectors = run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty()
        val context = runCatching {
            workspaceGateway.collectAnalysisContext(
                workspacePath,
                query,
                selectors,
            )
        }.getOrElse {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = it.message.orEmpty())
        }
        if (context.files.isEmpty()) {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = "No repository evidence was selected.")
        }
        val complianceContext = runCatching {
            collectComplianceContext(workspacePath, run, selectors, context)
        }.getOrElse {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = it.message.orEmpty())
        }
        repositoryEvidenceSelectionDiagnostic(
            run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
            context,
        )?.let { diagnostic ->
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = diagnostic)
        }
        val currentPlan = plans.asSequence()
            .filter { it.runId == run.runId && it.baseRevision == baseRevision && it.coversAcceptedScope(run, context) }
            .maxByOrNull { it.revision }
        val codingWorkerEvents = codingWorkerStore?.loadEvents().orEmpty()
        val rejectedCodingPlanDiagnostic = listOfNotNull(
            currentPlan?.let { repositoryPlanRevisionDiagnostic(it, codingAttemptStore?.load().orEmpty()) },
            failedCandidateVerificationDiagnostic(baseRevision, codingWorkerEvents),
        ).distinct().joinToString("\n").ifBlank { null }
        if (currentPlan != null && rejectedCodingPlanDiagnostic == null) {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE, run.runId)
        }
        val profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis
        val assignment = companyControl?.let { company ->
            if (company.assign(run.runId, ROLE_ANALYST_DESIGNER, RISK_HIGH).status != CompanyMutationStatus.RECORDED) return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.NO_COMPATIBLE_MODEL,
                run.runId,
                diagnostic = "No analyst-designer could be assigned.",
            )
            company.assignment(run.runId, ROLE_ANALYST_DESIGNER)
        }
        val provider = assignment?.let { companyControl?.provider(it) }
            ?: runCatching { ModelProfileResolver.resolve(profile, modelProviders) }.getOrNull()
            ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.NO_COMPATIBLE_MODEL, run.runId)
        fun envelopeFor(candidate: CodingRepositoryContext) = RepositoryAnalysisEnvelope(
            profile.id,
            baseRevision,
            taskContext(run),
            candidate,
            DISPOSITIONS,
            OUTPUT_SCHEMA,
            candidate.files.map { RequiredRepositoryEvidence(it.path, it.contentHash) },
            run.workDefinition?.definition?.scope.orEmpty(),
            requiredRepositoryEvidencePathGroups(run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(), candidate),
            requiredRepositoryScopeEvidencePathGroupIds(
                run.workDefinition?.definition?.scope.orEmpty(),
                run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
            ),
            repositoryForbiddenLiteralFacts(
                run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
                complianceContext,
            ),
            attemptStore.retryDiagnostic(run.runId, baseRevision),
            rejectedCodingPlanDiagnostic,
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.verification }.orEmpty(),
        )
        val requiredEvidencePaths = requiredRepositoryEvidencePaths(
            run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
            context,
        ).toSet()
        val queryTokens = repositoryAnalysisTokens(query)
        val boundedContext = compactRepositoryContextToBudget(
            context,
            profile.inputBudgetTokens,
            requiredEvidencePaths,
            contentCompactor = { content, maxBytes -> focusedContextExcerpt(content, queryTokens, maxBytes) },
        ) { candidate ->
            "$systemPrompt\n\nAuthoritative repository analysis envelope:\n${json.encodeToString(envelopeFor(candidate))}"
        } ?: return RepositoryAnalysisTickResult(
            RepositoryAnalysisTickStatus.CONTEXT_BUDGET_EXCEEDED,
            run.runId,
            diagnostic = "The minimum repository evidence envelope exceeds the analysis model input budget.",
        )
        val envelope = envelopeFor(boundedContext)
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative repository analysis envelope:\n$envelopeJson"
        val binding = provider.bindingProfile()
        val admission = resourceController.acquire(
            provider.resourceDemand(profile, estimateModelTokens(prompt)),
            ModelWorkPriority.DELIVERY,
        )
        val lease = admission.lease ?: return RepositoryAnalysisTickResult(
            RepositoryAnalysisTickStatus.RESOURCE_BLOCKED,
            run.runId,
            diagnostic = admission.evidence.reason,
        )
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use { provider.executeRepositoryAnalysis(prompt, profile.outputBudgetTokens, profile.inputBudgetTokens + profile.outputBudgetTokens) }
        } catch (exception: CancellationException) {
            recordExecution(profile.id, profile, binding, run, envelopeJson, prompt, null, startedAt, false, admission.evidence)
            blockAttempt(
                run.runId,
                baseRevision,
                prompt,
                RepositoryAnalysisTickStatus.CANCELLED,
                "Repository analysis execution was cancelled before producing an admissible plan.",
            )
            throw exception
        } catch (error: Exception) {
            recordExecution(profile.id, profile, binding, run, envelopeJson, prompt, null, startedAt, false, admission.evidence)
                ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE, run.runId)
            return blockAttempt(
                run.runId,
                baseRevision,
                prompt,
                RepositoryAnalysisTickStatus.MODEL_FAILED,
                error.message.orEmpty().ifBlank { "The repository analysis model failed." },
            )
        }
        val boundedGeneration = generation.takeIf {
            repositoryAnalysisGenerationWithinBudget(it, profile.inputBudgetTokens, profile.outputBudgetTokens)
        }
        val decodedOutput = boundedGeneration?.let {
            runCatching { json.decodeFromString<RepositoryAnalysisPlanContent>(it.text) }
        }
        val output = decodedOutput?.getOrNull()
        val execution = recordExecution(
            profile.id, profile, binding, run, envelopeJson, prompt, generation, startedAt, output != null, admission.evidence
        ) ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE, run.runId)
        if (output == null) return blockAttempt(
            run.runId,
            baseRevision,
            prompt,
            RepositoryAnalysisTickStatus.INVALID_ANALYSIS,
            repositoryAnalysisDecodeDiagnostic(boundedGeneration, decodedOutput?.exceptionOrNull()),
        )
        repositoryAnalysisIdentityDiagnostic(boundedContext, output)?.let {
            return blockAttempt(run.runId, baseRevision, prompt, RepositoryAnalysisTickStatus.INVALID_ANALYSIS, it)
        }
        repositoryForbiddenLiteralComplianceDiagnostic(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            complianceContext,
            output,
        )?.let {
            return blockAttempt(run.runId, baseRevision, prompt, RepositoryAnalysisTickStatus.INVALID_ANALYSIS, it, output)
        }
        repositoryArchitectEscalationEvidenceDiagnostic(
            repositoryForbiddenLiteralFacts(
                run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
                complianceContext,
            ),
            output.scopeCoverage.flatMap { it.compliantEvidencePaths },
            output.unresolvedQuestions,
        )?.let {
            return blockAttempt(run.runId, baseRevision, prompt, RepositoryAnalysisTickStatus.INVALID_ANALYSIS, it, output)
        }
        if (output.unresolvedQuestions.isNotEmpty() || output.disposition == DISPOSITION_CONFLICTING) {
            return blockAttempt(
                run.runId,
                baseRevision,
                prompt,
                RepositoryAnalysisTickStatus.ARCHITECT_DECISION_REQUIRED,
                output.unresolvedQuestions.joinToString(" ").ifBlank { "Conflicting implementations require an architect decision." },
            )
        }
        val compiledOutput = compileRepositoryVerificationAuthority(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.verification }.orEmpty(),
            compileRepositoryScopeAuthority(
                run.workDefinition?.definition?.scope.orEmpty(),
                run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
                boundedContext,
                output,
            ),
        )
        val failedCandidatePaths = failedCandidateCorrectionPaths(
            baseRevision,
            codingWorkerEvents,
        )
        val invalid = validateOutput(
            run,
            boundedContext,
            complianceContext,
            compiledOutput,
            failedCandidatePaths,
            currentPlan?.content,
            rejectedCodingPlanDiagnostic,
        )
        if (invalid != null) return blockAttempt(
            run.runId,
            baseRevision,
            prompt,
            RepositoryAnalysisTickStatus.INVALID_ANALYSIS,
            invalid,
            compiledOutput,
        )
        if (workspaceGateway.currentRevision(workspacePath) != baseRevision) {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.PLAN_STALE, run.runId, diagnostic = "Repository changed during analysis.")
        }
        var plan: RepositoryExecutionPlan? = null
        return runCatching {
            plan = planStore.appendNext(run.runId) { planId, revision ->
                newRepositoryExecutionPlan(
                    planId = planId,
                    runId = run.runId,
                    revision = revision,
                    projectId = run.context.projectId,
                    baseRevision = baseRevision,
                    content = compiledOutput,
                    provenance = AnalysisExecutionProvenance(
                        executionProfileId = profile.id,
                        bindingFingerprint = modelBindingFingerprint(binding),
                        promptHash = sha256(prompt),
                        contextHash = sha256(envelopeJson),
                        outputHash = sha256(generation.text),
                        modelExecutionId = execution.executionId,
                    ),
                )
            }
        }.fold(
            onSuccess = { RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.PLAN_CREATED, run.runId, plan) },
            onFailure = { RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE, run.runId, diagnostic = it.message.orEmpty()) },
        )
    }

    private fun validateOutput(
        run: WorkflowRunView,
        context: CodingRepositoryContext,
        complianceContext: CodingRepositoryContext,
        output: RepositoryAnalysisPlanContent,
        failedCandidatePaths: Set<String>,
        rejectedPlan: RepositoryAnalysisPlanContent?,
        rejectedCodingPlanDiagnostic: String?,
    ): String? {
        repositoryScopeAuthorityDiagnostic(
            run.workDefinition?.definition?.scope.orEmpty(),
            run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
            context,
            output,
        )?.let { return it }
        repositoryForbiddenLiteralComplianceDiagnostic(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            complianceContext,
            output,
        )?.let { return it }
        repositorySourceOperationBudgetDiagnostic(output)?.let { return it }
        if (output.operations.map { it.order } != (1..output.operations.size).toList()) return "Execution operations are not strictly ordered."
        repositoryAcceptanceCoverageDiagnostic(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            output,
        )?.let { return it }
        repositoryOperationShapeDiagnostic(context, output)?.let { return it }
        failedCandidateCorrectionDiagnostic(failedCandidatePaths, output)?.let { return it }
        val admittedCommands = run.workDefinition?.definition?.acceptanceCriteria?.map { it.verification }?.toSet().orEmpty()
        if (output.verificationCommands.toSet() != admittedCommands) return "Execution plan verification differs from admitted commands."
        return null
    }

    private fun recordExecution(
        workflowStepId: String,
        profile: com.orchard.backend.vector.ModelExecutionProfile,
        binding: ModelBindingProfile,
        run: WorkflowRunView,
        envelopeJson: String,
        prompt: String,
        generation: ModelGeneration?,
        startedAt: Long,
        schemaValid: Boolean,
        admission: com.orchard.backend.resource.ResourceAdmissionEvidence,
    ) = workspace.recordModelExecution(
        ModelExecutionObservationDraft(
            profile = profile,
            binding = binding,
            workflowStepId = workflowStepId,
            workItemId = run.context.workItemId,
            envelopeHash = sha256(envelopeJson),
            promptHash = sha256(prompt),
            outputHash = generation?.text?.let(::sha256),
            inputTokens = generation?.promptTokens ?: estimateModelTokens(prompt),
            outputTokens = generation?.completionTokens ?: 0,
            latencyMillis = (System.nanoTime() - startedAt) / 1_000_000,
            schemaValid = schemaValid,
            resourceAdmission = admission,
        )
    )

    private fun blockAttempt(
        runId: Long,
        baseRevision: String,
        prompt: String,
        status: RepositoryAnalysisTickStatus,
        diagnostic: String,
        rejectedPlan: RepositoryAnalysisPlanContent? = null,
    ): RepositoryAnalysisTickResult = runCatching {
        attemptStore.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId = attemptId,
                runId = runId,
                baseRevision = baseRevision,
                state = ANALYSIS_ATTEMPT_BLOCKED,
                resultStatus = status.name,
                diagnostic = diagnostic,
                promptHash = sha256(prompt),
                rejectedPlan = rejectedPlan,
            )
        }
    }.fold(
        onSuccess = { RepositoryAnalysisTickResult(status, runId, diagnostic = diagnostic) },
        onFailure = {
            RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE,
                runId,
                diagnostic = it.message.orEmpty(),
            )
        },
    )

    private fun analysisQuery(run: WorkflowRunView): String = buildString {
        appendLine(run.context.title)
        appendLine(run.context.content)
        run.workDefinition?.definition?.let {
            appendLine(it.currentBehavior)
            appendLine(it.requiredBehavior)
            appendLine(it.scope.joinToString(" "))
            appendLine(it.constraints.joinToString(" "))
            it.acceptanceCriteria.forEach { criterion ->
                appendLine(criterion.description)
                appendLine(criterion.verification)
            }
        }
        run.context.recalledEpisodes.forEach { appendLine("${it.problem} ${it.resolution} ${it.evidenceSummary}") }
    }

    private fun collectComplianceContext(
        workspacePath: String,
        run: WorkflowRunView,
        selectors: List<RepositoryEvidenceSelector>,
        fallback: CodingRepositoryContext,
    ): CodingRepositoryContext {
        val query = forbiddenComplianceLiterals(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
        ).joinToString(" ")
        return if (query.isBlank()) fallback else workspaceGateway.collectAnalysisContext(workspacePath, query, selectors)
    }

    private fun taskContext(run: WorkflowRunView): RepositoryAnalysisTaskContext {
        val definition = run.workDefinition?.definition
        return RepositoryAnalysisTaskContext(
            runId = run.runId,
            title = run.context.title,
            content = run.context.content,
            requestedOutcome = definition?.requestedOutcome.orEmpty(),
            currentBehavior = definition?.currentBehavior.orEmpty(),
            requiredBehavior = definition?.requiredBehavior.orEmpty(),
            nonGoals = definition?.nonGoals.orEmpty(),
            constraints = definition?.constraints.orEmpty(),
            reproduction = definition?.reproduction.orEmpty(),
            regressionCriterion = definition?.regressionCriterion.orEmpty(),
            recalledEvidence = run.context.recalledEpisodes.map {
                "${it.problem} ${it.resolution} ${it.evidenceSummary}"
            },
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        val ACTIONABLE_STATES = setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED)
        val DISPOSITIONS = listOf(
            DISPOSITION_ABSENT,
            DISPOSITION_SCAFFOLD_ONLY,
            DISPOSITION_PARTIALLY_IMPLEMENTED,
            DISPOSITION_IMPLEMENTED_DIFFERENT_FORM,
            DISPOSITION_IMPLEMENTED_NONCONFORMING,
            DISPOSITION_COMPLETE,
            DISPOSITION_CONFLICTING,
        )
        const val OUTPUT_SCHEMA = "RepositoryAnalysisPlanContent(disposition, summary, evidence, reuse, preservedInvariants, nonGoals, scopeCoverage(scope, evidencePaths, operationOrders, compliantEvidencePaths), operations, verificationCommands, unresolvedQuestions)"
        const val LEGACY_IDENTICAL_OUTCOME_BLOCK_THRESHOLD = 2

        fun loadPrompt(): String = requireNotNull(
            RepositoryAnalysisService::class.java.classLoader.getResourceAsStream("default-system-prompts/repository_analysis_agent.md")
        ).bufferedReader().use { it.readText() }
    }
}

internal fun repositoryAcceptanceCoverageDiagnostic(
    acceptedCriteria: List<String>,
    output: RepositoryAnalysisPlanContent,
): String? {
    val required = acceptedCriteria.associateBy(::canonicalAuthorityText)
    if (required.isEmpty()) return "The workflow has no accepted criteria to compile."
    if (required.size != acceptedCriteria.size) return "The workflow has ambiguous accepted criteria."
    val actual = output.operations.flatMap { it.acceptanceCriteria }
    val actualKeys = actual.mapTo(hashSetOf(), ::canonicalAuthorityText)
    val missing = required.filterKeys { it !in actualKeys }.values
    val unexpected = actual.filter { canonicalAuthorityText(it) !in required }.distinct()
    if (missing.isEmpty() && unexpected.isEmpty()) return null
    return buildString {
        append("Execution operations must cover the exact acceptance criteria.")
        if (missing.isNotEmpty()) append(" Missing: ").append(missing.joinToString(" | "))
        if (unexpected.isNotEmpty()) append(" Unexpected: ").append(unexpected.joinToString(" | "))
    }
}

internal fun repositoryAnalysisGenerationWithinBudget(
    generation: ModelGeneration,
    inputBudgetTokens: Int,
    outputBudgetTokens: Int,
): Boolean = generation.promptTokens <= inputBudgetTokens && generation.completionTokens <= outputBudgetTokens

internal fun repositoryAnalysisDecodeDiagnostic(generation: ModelGeneration?, error: Throwable?): String = when {
    generation == null -> "The analysis model output exceeded the admitted token budget."
    error == null -> "The analysis model did not return valid strict JSON."
    else -> "The analysis model did not return valid strict JSON: ${error.message.orEmpty().replace(Regex("\\s+"), " ").take(512)}"
}

internal fun repositoryAnalysisIdentityDiagnostic(
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    if (output.disposition !in VALID_ANALYSIS_DISPOSITIONS || output.summary.isBlank() || output.evidence.isEmpty()) {
        return "Analysis identity is incomplete."
    }
    return repositoryEvidenceDiagnostic(context, output)
}

private fun RepositoryExecutionPlan.coversAcceptedScope(run: WorkflowRunView): Boolean {
    return repositoryScopeCoverageDiagnostic(run.workDefinition?.definition?.scope.orEmpty(), content) == null
}

internal fun repositoryPlanRequiresRevision(
    currentPlan: RepositoryExecutionPlan,
    codingAttempts: List<CodingWorkerAttempt>,
): Boolean = repositoryPlanRevisionDiagnostic(currentPlan, codingAttempts) != null

internal fun failedCandidateCorrectionPaths(
    baseRevision: String,
    codingWorkerEvents: List<CodingWorkerEvent>,
): Set<String> = failedCandidateExecution(baseRevision, codingWorkerEvents)?.result?.changedPaths.orEmpty().toSet()

internal fun failedCandidateVerificationDiagnostic(
    baseRevision: String,
    codingWorkerEvents: List<CodingWorkerEvent>,
): String? = failedCandidateExecution(baseRevision, codingWorkerEvents)?.result?.diagnostic

private fun failedCandidateExecution(
    baseRevision: String,
    codingWorkerEvents: List<CodingWorkerEvent>,
) = codingWorkerExecutions(codingWorkerEvents).asReversed().firstOrNull { execution ->
    execution.result?.status == CODING_EXECUTION_FAILED && execution.result.revision == baseRevision
}

internal fun failedCandidateCorrectionDiagnostic(
    requiredPaths: Set<String>,
    output: RepositoryAnalysisPlanContent,
): String? {
    val sourceOperationPaths = output.operations.asSequence()
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .mapTo(hashSetOf()) { it.path }
    val missingPaths = (requiredPaths - sourceOperationPaths).sorted()
    return missingPaths.takeIf { it.isNotEmpty() }?.let {
        "A verification-failed candidate requires corrective source operations for every changed path: ${it.joinToString(", ")}."
    }
}

private fun repositoryPlanRevisionDiagnostic(
    currentPlan: RepositoryExecutionPlan,
    codingAttempts: List<CodingWorkerAttempt>,
): String? = codingAttempts.lastOrNull {
    it.runId == currentPlan.runId &&
        it.executionPlanId == currentPlan.planId &&
        it.executionPlanHash == currentPlan.hash
}?.takeIf { it.state == CODING_ATTEMPT_BLOCKED }?.diagnostic

private fun RepositoryExecutionPlan.coversAcceptedScope(
    run: WorkflowRunView,
    context: CodingRepositoryContext,
    complianceContext: CodingRepositoryContext = context,
): Boolean = coversAcceptedScope(run) && repositoryUniversalScopeCoverageDiagnostic(
    run.workDefinition?.definition?.scope.orEmpty(),
    run.workDefinition?.definition?.repositoryEvidenceSelectors.orEmpty(),
    context,
    content,
) == null && repositoryForbiddenLiteralComplianceDiagnostic(
    run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
    complianceContext,
    content,
) == null

internal fun repositoryScopeCoverageDiagnostic(
    acceptedScope: List<String>,
    output: RepositoryAnalysisPlanContent,
): String? {
    repositoryScopeIdentityDiagnostic(acceptedScope, output)?.let { return it }
    val evidencePaths = output.evidence.mapTo(hashSetOf()) { it.path }
    val operations = output.operations.associateBy { it.order }
    val sourceOperationPaths = output.operations.filter { it.action != PLAN_OPERATION_VERIFY }.mapTo(hashSetOf()) { it.path }
    output.scopeCoverage.forEachIndexed { index, coverage ->
        if (coverage.evidencePaths.isEmpty() || coverage.evidencePaths.any { it !in evidencePaths && it !in sourceOperationPaths }) {
            return "Scope coverage ${index + 1} does not cite pinned evidence or a concrete source operation."
        }
        if (coverage.operationOrders.any { it !in operations }) {
            return "Scope coverage ${index + 1} references an unavailable operation."
        }
        if (!requiresSourceOperation(coverage.scope) && coverage.compliantEvidencePaths.isNotEmpty()) {
            return "Scope coverage ${index + 1} uses compliant evidence classification for evidence-only scope."
        }
        if (requiresSourceOperation(coverage.scope)) {
            val linkedSourceOperations = coverage.operationOrders.asSequence()
                .mapNotNull(operations::get)
                .filter { it.action != PLAN_OPERATION_VERIFY }
                .toList()
            val linkedSourcePaths = linkedSourceOperations.asSequence()
                .mapTo(hashSetOf()) { it.path }
            val unpinnedCompliantPaths = coverage.compliantEvidencePaths.filter { it !in evidencePaths }.distinct().sorted()
            if (unpinnedCompliantPaths.isNotEmpty()) {
                return "Scope coverage ${index + 1} marks paths compliant without pinned evidence: " +
                    "${unpinnedCompliantPaths.joinToString(", ")}."
            }
            val conflictingPaths = coverage.compliantEvidencePaths.filter { it in sourceOperationPaths }.distinct().sorted()
            if (conflictingPaths.isNotEmpty()) {
                return "Scope coverage ${index + 1} marks source-operation paths as already compliant: " +
                    "${conflictingPaths.joinToString(", ")}."
            }
            val requiredTestPaths = coverage.evidencePaths.filter(::isTestSourcePath).distinct().sorted()
            val changedTestPaths = linkedSourceOperations.asSequence()
                .filter { it.action in setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY) }
                .mapTo(hashSetOf()) { it.path }
            if (requiresTestSource(coverage.scope) && requiredTestPaths.none { it in changedTestPaths }) {
                return testSourceOperationDiagnostic(index, requiredTestPaths)
            }
            val unsatisfiedPaths = coverage.evidencePaths
                .filter { it !in linkedSourcePaths && it !in coverage.compliantEvidencePaths }
                .distinct()
                .sorted()
            if (unsatisfiedPaths.isNotEmpty()) {
                val linked = linkedSourcePaths.sorted().joinToString(", ").ifBlank { "<none>" }
                return "Scope coverage ${index + 1} cites paths without source operations or explicit compliant evidence: " +
                    "${unsatisfiedPaths.joinToString(", ")}. Linked source operation paths: $linked."
            }
        }
    }
    return null
}

internal fun repositoryForbiddenLiteralComplianceDiagnostic(
    acceptanceCriteria: List<String>,
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    val forbiddenLiterals = forbiddenComplianceLiterals(acceptanceCriteria)
    if (forbiddenLiterals.isEmpty()) return null
    val files = context.files.associateBy { it.path }
    val mutationPaths = output.operations
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .map { it.path }
        .toSet()
    val scopedPaths = output.scopeCoverage.flatMap { it.evidencePaths }.toSet()
    files.filterKeys { it in scopedPaths }.forEach { (path, file) ->
        forbiddenLiterals.forEach { literal ->
            val count = lexicalEvidenceCount(file.content, literal)
            if (count > 0 && path !in mutationPaths) {
                val selectedPaths = mutationPaths.sorted().joinToString(", ").ifBlank { "<none>" }
                return "Pinned evidence contains forbidden literal $literal $count time${if (count == 1) "" else "s"} in $path, " +
                    "so the execution plan must include a source mutation on that exact path. " +
                    "Currently selected source mutation paths: $selectedPaths. " +
                    "If the source-operation budget is full, replace a selected mutation rather than omitting $path."
            }
        }
    }
    output.scopeCoverage.forEach { coverage ->
        coverage.compliantEvidencePaths.forEach { path ->
            val content = files[path]?.content ?: return "Scope '${coverage.scope}' marks $path compliant, but pinned evidence is unavailable."
            forbiddenLiterals.forEach { literal ->
                val count = lexicalEvidenceCount(content, literal)
                if (count > 0) {
                    return "Scope '${coverage.scope}' marks $path compliant, but pinned evidence contains forbidden literal " +
                        "$literal $count time${if (count == 1) "" else "s"}."
                }
            }
        }
    }
    return null
}

private fun forbiddenComplianceLiterals(acceptanceCriteria: List<String>): List<String> = acceptanceCriteria.flatMap { criterion ->
    FORBIDDEN_CONTAINS_LITERAL.findAll(criterion).map { it.groupValues[1] }.toList()
}.distinct()

internal fun repositoryForbiddenLiteralFacts(
    acceptanceCriteria: List<String>,
    context: CodingRepositoryContext,
): List<RepositoryForbiddenLiteralFact> = context.files.flatMap { file ->
    forbiddenComplianceLiterals(acceptanceCriteria).map { literal ->
        RepositoryForbiddenLiteralFact(file.path, literal, lexicalEvidenceCount(file.content, literal))
    }
}

internal fun repositoryArchitectEscalationEvidenceDiagnostic(
    facts: List<RepositoryForbiddenLiteralFact>,
    compliantEvidencePaths: List<String>,
    unresolvedQuestions: List<String>,
): String? {
    facts.firstNotNullOfOrNull { fact ->
        if (fact.count != 0) return@firstNotNullOfOrNull null
        val basename = fact.path.substringAfterLast('/')
        val unsupported = unresolvedQuestions.firstOrNull { question ->
            (question.contains(fact.path, ignoreCase = true) || question.contains(basename, ignoreCase = true)) &&
                question.contains(fact.literal, ignoreCase = true) &&
                question.contains(Regex("\\bcontains?\\b", RegexOption.IGNORE_CASE))
        } ?: return@firstNotNullOfOrNull null
        "Architect escalation contradicts pinned evidence: ${fact.path} contains ${fact.literal} 0 times, but unresolvedQuestions claims: $unsupported"
    }?.let { return it }
    return compliantEvidencePaths.distinct().firstNotNullOfOrNull { path ->
        val basename = path.substringAfterLast('/')
        val unsupported = unresolvedQuestions.firstOrNull { question ->
            (question.contains(path, ignoreCase = true) || question.contains(basename, ignoreCase = true)) &&
                question.contains(Regex("\\b(?:modify|mutation)\\b", RegexOption.IGNORE_CASE)) &&
                question.contains(Regex("\\b(?:require|required|requires|needed)\\b", RegexOption.IGNORE_CASE))
        } ?: return@firstNotNullOfOrNull null
        "Architect escalation contradicts compliant pinned evidence for $path: $unsupported"
    }
}

internal fun repositorySourceOperationBudgetDiagnostic(output: RepositoryAnalysisPlanContent): String? {
    val sourceOperations = output.operations.count { it.action != PLAN_OPERATION_VERIFY }
    return if (sourceOperations > MAX_SOURCE_OPERATIONS_PER_PLAN) {
        "Execution plan has $sourceOperations source operations; at most $MAX_SOURCE_OPERATIONS_PER_PLAN are allowed per bounded coding slice. " +
            "Classify unchanged pinned paths as compliant evidence and defer additional mutations to a successor plan."
    } else null
}

internal fun compileRepositoryVerificationAuthority(
    requiredCommands: List<String>,
    output: RepositoryAnalysisPlanContent,
): RepositoryAnalysisPlanContent = output.copy(verificationCommands = requiredCommands)

private fun lexicalEvidenceCount(content: String, literal: String): Int {
    val summary = content.substringBefore(']')
    val terminalToken = literal.lowercase().split(Regex("[^a-z0-9_]+")).last()
    val summarized = sequenceOf(literal, terminalToken).mapNotNull { token ->
        Regex("(?:^|[,: ]+)${Regex.escape(token)}=(\\d+)", RegexOption.IGNORE_CASE)
            .find(summary)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }.firstOrNull()
    return summarized ?: Regex(Regex.escape(literal), RegexOption.IGNORE_CASE).findAll(content).count()
}

internal fun repositoryScopeAuthorityDiagnostic(
    acceptedScope: List<String>,
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? = repositoryScopeIdentityDiagnostic(acceptedScope, output)
    ?: repositoryRequiredScopeEvidencePathsDiagnostic(acceptedScope, selectors, context, output)
    ?: repositoryUniversalScopeCoverageDiagnostic(acceptedScope, selectors, context, output)
    ?: repositoryScopeCoverageDiagnostic(acceptedScope, output)

internal fun repositoryScopeIdentityDiagnostic(
    acceptedScope: List<String>,
    output: RepositoryAnalysisPlanContent,
): String? {
    val required = acceptedScope.associateBy(::canonicalAuthorityText)
    if (required.isEmpty()) return "The workflow has no accepted implementation scope to compile."
    if (required.size != acceptedScope.size) return "The workflow has ambiguous accepted implementation scope."
    val scopeCounts = output.scopeCoverage.groupingBy { canonicalAuthorityText(it.scope) }.eachCount()
    val missing = required.filterKeys { scopeCounts[it] == null }.values
    val duplicated = required.filterKeys { (scopeCounts[it] ?: 0) > 1 }.values
    val unexpected = output.scopeCoverage.map { it.scope }.filter { canonicalAuthorityText(it) !in required }
    if (missing.isNotEmpty() || duplicated.isNotEmpty() || unexpected.isNotEmpty()) {
        return buildString {
            append("Execution plan scope coverage must map every accepted scope clause exactly once.")
            if (missing.isNotEmpty()) append(" Missing: ").append(missing.joinToString(" | "))
            if (duplicated.isNotEmpty()) append(" Duplicated: ").append(duplicated.joinToString(" | "))
            if (unexpected.isNotEmpty()) append(" Unexpected: ").append(unexpected.joinToString(" | "))
        }
    }
    return null
}

internal fun repositoryUniversalScopeCoverageDiagnostic(
    acceptedScope: List<String>,
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    val requiredPaths = requiredRepositoryEvidencePaths(selectors, context).toSortedSet()
    if (requiredPaths.isEmpty()) return null
    val citedPaths = output.evidence.mapTo(hashSetOf()) { it.path }
    val missingEvidence = requiredPaths - citedPaths
    val sourceOperationPaths = output.operations.asSequence()
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .mapTo(hashSetOf()) { it.path }
    val changedTestPaths = output.operations.asSequence()
        .filter { it.action in setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY) && isTestSourcePath(it.path) }
        .mapTo(hashSetOf()) { it.path }
    val compliantEvidencePaths = output.scopeCoverage.flatMapTo(hashSetOf()) { it.compliantEvidencePaths }
    val unsatisfiedPaths = requiredPaths - sourceOperationPaths - compliantEvidencePaths
    acceptedScope.forEachIndexed { index, scope ->
        if (!requiresTestSource(scope)) return@forEachIndexed
        val requiredTestPaths = requiredRepositoryScopeEvidencePathGroupIds(acceptedScope, selectors)[index]
            .flatMap { requiredRepositoryPathsBySelector(selectors, context)[it].orEmpty() }
            .filter(::isTestSourcePath)
            .distinct()
            .sorted()
        if (requiredTestPaths.none { it in changedTestPaths }) return testSourceOperationDiagnostic(index, requiredTestPaths)
    }
    val diagnostics = listOfNotNull(
        missingEvidence.takeIf { it.isNotEmpty() }?.let {
            "Required repository paths omit evidence: ${it.joinToString(", ")}."
        },
        unsatisfiedPaths.takeIf { it.isNotEmpty() }?.let {
            "Required repository paths omit source operations or explicit compliant evidence: ${it.joinToString(", ")}."
        },
    )
    return diagnostics.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun testSourceOperationDiagnostic(scopeIndex: Int, requiredTestPaths: List<String>): String {
    val paths = requiredTestPaths.joinToString(", ").ifBlank { "<none selected>" }
    return "Scope coverage ${scopeIndex + 1} requires a CREATE or MODIFY operation for its pinned test source path: $paths. " +
        "DELETE and compliant evidence cannot satisfy regression scope."
}

internal fun requiredRepositoryEvidencePaths(
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
): List<String> = requiredRepositoryPathsBySelector(selectors, context).values.flatten().distinct().sorted()

private fun requiredRepositoryEvidencePathGroups(
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
): List<RequiredEvidencePathGroup> = requiredRepositoryPathsBySelector(selectors, context)
    .map { (id, paths) -> RequiredEvidencePathGroup(id, paths) }

private fun requiredRepositoryScopeEvidencePathGroupIds(
    acceptedScope: List<String>,
    selectors: List<RepositoryEvidenceSelector>,
): List<List<String>> = acceptedScope.indices.map { scopeIndex ->
    selectors.filter { scopeIndex in it.scopeIndexes }.map { it.selectorId }
}

private fun requiredRepositoryPathsBySelector(
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
): Map<String, List<String>> {
    val direct = selectors.associate { selector ->
        selector.selectorId to context.files.filter { selector.selectorId in it.matchedEvidenceSelectorIds }.map { it.path }
    }
    return selectors.associate { selector ->
        val paths = if (selector.selection == REPOSITORY_EVIDENCE_AFFINE_TEST) {
            val owners = direct[selector.affinitySelectorId].orEmpty()
            listOfNotNull(direct[selector.selectorId].orEmpty().maxByOrNull { candidate ->
                owners.maxOfOrNull { owner -> commonPathPrefixLength(candidate, owner) } ?: 0
            })
        } else {
            direct[selector.selectorId].orEmpty()
        }
        selector.selectorId to paths.distinct().sorted()
    }
}

internal fun repositoryEvidenceSelectionDiagnostic(
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
): String? {
    val selected = requiredRepositoryPathsBySelector(selectors, context)
    val unmatched = selectors.filter { selected[it.selectorId].isNullOrEmpty() }.map { it.selectorId }
    return unmatched.takeIf { it.isNotEmpty() }?.let {
        "Repository evidence selectors matched no required paths: ${it.joinToString(", ")}."
    }
}

internal fun repositoryRequiredScopeEvidencePathsDiagnostic(
    acceptedScope: List<String>,
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    val groups = requiredRepositoryEvidencePathGroups(selectors, context).associate { it.id to it.paths }
    val groupIds = requiredRepositoryScopeEvidencePathGroupIds(acceptedScope, selectors)
    if (selectors.isEmpty()) return null
    val actual = output.scopeCoverage.associateBy { canonicalAuthorityText(it.scope) }
    val mismatches = acceptedScope.mapIndexedNotNull { index, scope ->
        val coverage = actual[canonicalAuthorityText(scope)] ?: return@mapIndexedNotNull null
        val requiredPaths = groupIds[index].flatMap { groups[it].orEmpty() }.toSet()
        if (coverage.evidencePaths.toSet() != requiredPaths.toSet()) {
            val expected = requiredPaths.sorted().joinToString(", ").ifBlank { "<none>" }
            val supplied = coverage.evidencePaths.distinct().sorted().joinToString(", ").ifBlank { "<none>" }
            "Scope coverage ${index + 1} paths differ from deterministic scope authority. Expected: $expected. Actual: $supplied."
        } else null
    }
    return mismatches.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun compileRepositoryScopeAuthority(
    acceptedScope: List<String>,
    selectors: List<RepositoryEvidenceSelector>,
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): RepositoryAnalysisPlanContent {
    if (selectors.isEmpty() || repositoryScopeIdentityDiagnostic(acceptedScope, output) != null) return output
    val pathsBySelector = requiredRepositoryPathsBySelector(selectors, context)
    val selectorIdsByScope = requiredRepositoryScopeEvidencePathGroupIds(acceptedScope, selectors)
    val coverageByScope = output.scopeCoverage.associateBy { canonicalAuthorityText(it.scope) }
    val compiledOperations = (
        output.operations.filter { it.action != PLAN_OPERATION_VERIFY } +
            output.operations.filter { it.action == PLAN_OPERATION_VERIFY }
        ).mapIndexed { index, operation -> operation.copy(order = index + 1) }
    val verificationOrderMap = output.operations.filter { it.action == PLAN_OPERATION_VERIFY }
        .zip(compiledOperations.filter { it.action == PLAN_OPERATION_VERIFY })
        .associate { (original, compiled) -> original.order to compiled.order }
    return output.copy(
        operations = compiledOperations,
        scopeCoverage = acceptedScope.mapIndexed { index, scope ->
            val coverage = coverageByScope.getValue(canonicalAuthorityText(scope))
            val evidencePaths = selectorIdsByScope[index]
                .flatMap { pathsBySelector[it].orEmpty() }
                .distinct()
                .sorted()
            val sourceOperationOrders = compiledOperations.asSequence()
                .filter { it.action != PLAN_OPERATION_VERIFY && it.path in evidencePaths }
                .map { it.order }
            val sourceOperationPaths = compiledOperations.asSequence()
                .filter { it.action != PLAN_OPERATION_VERIFY }
                .mapTo(hashSetOf()) { it.path }
            val verificationOperationOrders = coverage.operationOrders.asSequence()
                .mapNotNull(verificationOrderMap::get)
            coverage.copy(
                scope = scope,
                evidencePaths = evidencePaths,
                operationOrders = (sourceOperationOrders + verificationOperationOrders).distinct().sorted().toList(),
                compliantEvidencePaths = evidencePaths.filter { it !in sourceOperationPaths },
            )
        },
    )
}

private fun commonPathPrefixLength(first: String, second: String): Int = first
    .replace('\\', '/')
    .split('/')
    .zip(second.replace('\\', '/').split('/'))
    .takeWhile { (left, right) -> left == right }
    .size

private val FORBIDDEN_CONTAINS_LITERAL = Regex(
    "\\bnone\\b[^.]*?\\bcontains\\s+([A-Za-z_][A-Za-z0-9_.]*)",
    RegexOption.IGNORE_CASE,
)

private const val MAX_SOURCE_OPERATIONS_PER_PLAN = 2

private fun canonicalAuthorityText(value: String): String = value
    .replace(Regex("[\\u2010-\\u2015\\u2212]"), "-")
    .replace('\u00a0', ' ')
    .trim()
    .replace(Regex("\\s+"), " ")
    .removeSuffix(".")

private fun requiresSourceOperation(scope: String): Boolean = canonicalAuthorityText(scope)
    .substringBefore(' ')
    .lowercase() !in setOf("inspect", "analyze", "audit")

private fun requiresTestSource(scope: String): Boolean {
    val normalized = canonicalAuthorityText(scope).lowercase()
    return "test" in normalized || "regression" in normalized
}

private fun isTestSourcePath(path: String): Boolean {
    val normalized = path.replace('\\', '/').lowercase()
    return "/test/" in normalized || normalized.substringAfterLast('/').contains("test.")
}


private val VALID_ANALYSIS_DISPOSITIONS = setOf(
    DISPOSITION_ABSENT,
    DISPOSITION_SCAFFOLD_ONLY,
    DISPOSITION_PARTIALLY_IMPLEMENTED,
    DISPOSITION_IMPLEMENTED_DIFFERENT_FORM,
    DISPOSITION_IMPLEMENTED_NONCONFORMING,
    DISPOSITION_COMPLETE,
    DISPOSITION_CONFLICTING,
)

internal fun repositoryEvidenceDiagnostic(
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    val files = context.files.associateBy { it.path }
    output.evidence.forEachIndexed { index, citation ->
        val file = files[citation.path]
            ?: return "Repository evidence citation ${index + 1} uses unavailable path ${citation.path}."
        if (file.contentHash != citation.contentHash) {
            return "Repository evidence citation ${index + 1} has the wrong content hash for ${citation.path}."
        }
        if (citation.observation.isBlank()) return "Repository evidence citation ${index + 1} has no observation."
    }
    return null
}

internal fun repositoryOperationShapeDiagnostic(
    context: CodingRepositoryContext,
    output: RepositoryAnalysisPlanContent,
): String? {
    val observedPaths = context.files.mapTo(hashSetOf()) { it.path }
    val createdPaths = hashSetOf<String>()
    output.operations.forEach { operation ->
        if (operation.action !in setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY, PLAN_OPERATION_DELETE, PLAN_OPERATION_VERIFY)) {
            return "Execution operation ${operation.order} uses unsupported action ${operation.action}."
        }
        if (operation.instruction.isBlank()) return "Execution operation ${operation.order} has no instruction."
        if (operation.acceptanceCriteria.isEmpty()) return "Execution operation ${operation.order} has no acceptance criteria."
        if (operation.action == PLAN_OPERATION_CREATE) {
            if (operation.path in observedPaths || !createdPaths.add(operation.path)) {
                return "Execution operation ${operation.order} cannot CREATE existing path ${operation.path}."
            }
        }
        if (operation.action in setOf(PLAN_OPERATION_MODIFY, PLAN_OPERATION_DELETE) && operation.path !in observedPaths) {
            return "Execution operation ${operation.order} cannot ${operation.action} unobserved path ${operation.path}."
        }
        if (operation.action == PLAN_OPERATION_VERIFY && operation.path != "." && operation.path !in observedPaths && operation.path !in createdPaths) {
            return "Execution operation ${operation.order} cannot VERIFY unavailable path ${operation.path}."
        }
    }
    return null
}

internal fun compactRepositoryContextToBudget(
    context: CodingRepositoryContext,
    inputBudgetTokens: Int,
    requiredPaths: Set<String> = emptySet(),
    contentCompactor: ((String, Int) -> String)? = null,
    promptFor: (CodingRepositoryContext) -> String,
): CodingRepositoryContext? {
    if (context.files.isEmpty()) return null
    val availablePaths = context.files.mapTo(hashSetOf()) { it.path }
    if (!availablePaths.containsAll(requiredPaths)) return null
    val optionalFiles = context.files.filter { it.path !in requiredPaths }
    var declarationLimit = context.files.maxOfOrNull { it.matchedDeclarations.size } ?: 0
    var contentByteLimit: Int? = null
    if (requiredPaths.isNotEmpty()) {
        val requiredWithNoDeclarations = compactRepositoryContext(
            context,
            requiredPaths,
            optionalFiles = emptyList(),
            declarationLimit = 0,
        )
        if (estimateModelTokens(promptFor(requiredWithNoDeclarations)) > inputBudgetTokens) {
            val compactContent = contentCompactor ?: return null
            var lowerContentBytes = 1
            var upperContentBytes = context.files.filter { it.path in requiredPaths }
                .maxOf { it.content.encodeToByteArray().size }
            var fittedContentBytes: Int? = null
            while (lowerContentBytes <= upperContentBytes) {
                val candidateBytes = (lowerContentBytes + upperContentBytes) / 2
                val candidate = compactRepositoryContext(
                    context,
                    requiredPaths,
                    optionalFiles = emptyList(),
                    declarationLimit = 0,
                    contentByteLimit = candidateBytes,
                    contentCompactor = compactContent,
                )
                if (candidate.files.all { it.content.isNotEmpty() } && estimateModelTokens(promptFor(candidate)) <= inputBudgetTokens) {
                    fittedContentBytes = candidateBytes
                    lowerContentBytes = candidateBytes + 1
                } else {
                    upperContentBytes = candidateBytes - 1
                }
            }
            contentByteLimit = fittedContentBytes ?: return null
        }
        var lowerDeclarations = 0
        var upperDeclarations = declarationLimit
        var fittedDeclarationLimit: Int? = null
        while (lowerDeclarations <= upperDeclarations) {
            val candidateLimit = (lowerDeclarations + upperDeclarations) / 2
            val candidate = compactRepositoryContext(
                context,
                requiredPaths,
                optionalFiles = emptyList(),
                declarationLimit = candidateLimit,
                contentByteLimit = contentByteLimit,
                contentCompactor = contentCompactor,
            )
            if (estimateModelTokens(promptFor(candidate)) <= inputBudgetTokens) {
                fittedDeclarationLimit = candidateLimit
                lowerDeclarations = candidateLimit + 1
            } else {
                upperDeclarations = candidateLimit - 1
            }
        }
        declarationLimit = fittedDeclarationLimit ?: return null
    }
    var lower = if (requiredPaths.isEmpty()) 1 else 0
    var upper = optionalFiles.size
    var best: CodingRepositoryContext? = null
    while (lower <= upper) {
        val retainedOptional = (lower + upper) / 2
        val candidate = compactRepositoryContext(
            context,
            requiredPaths,
            optionalFiles.take(retainedOptional),
            declarationLimit,
            contentByteLimit,
            contentCompactor,
        )
        if (estimateModelTokens(promptFor(candidate)) <= inputBudgetTokens) {
            best = candidate
            lower = retainedOptional + 1
        } else {
            upper = retainedOptional - 1
        }
    }
    return best
}

private fun compactRepositoryContext(
    context: CodingRepositoryContext,
    requiredPaths: Set<String>,
    optionalFiles: List<CodingContextFile>,
    declarationLimit: Int,
    contentByteLimit: Int? = null,
    contentCompactor: ((String, Int) -> String)? = null,
): CodingRepositoryContext {
    val selectedPaths = requiredPaths + optionalFiles.map { it.path }
    return context.copy(
        files = context.files.filter { it.path in selectedPaths }.map { file ->
            file.copy(
                content = contentByteLimit?.let { requireNotNull(contentCompactor)(file.content, it) } ?: file.content,
                matchedDeclarations = file.matchedDeclarations.take(declarationLimit),
            )
        },
        omittedFileCount = context.omittedFileCount + context.files.size - selectedPaths.size,
    )
}

private fun repositoryAnalysisTokens(value: String): Set<String> = value.lowercase()
    .split(Regex("[^a-z0-9_]+"))
    .filter { it.length >= 3 }
    .toSet()
