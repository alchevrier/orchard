package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
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
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
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
private data class RepositoryAnalysisEnvelope(
    val executionProfileId: String,
    val baseRevision: String,
    val run: WorkflowRunView,
    val repositoryContext: CodingRepositoryContext,
    val allowedDispositions: List<String>,
    val requiredOutputSchema: String,
    val requiredEvidence: List<RequiredRepositoryEvidence>,
    val requiredScope: List<String>,
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
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()

    init {
        planStore.load()
    }

    fun plans(): List<RepositoryExecutionPlan> = planStore.load()

    fun currentPlan(runId: Long): RepositoryExecutionPlan? {
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == runId } ?: return null
        return planStore.load()
            .filter { it.runId == runId && it.coversAcceptedScope(run) }
            .maxByOrNull { it.revision }
    }

    suspend fun tick(): RepositoryAnalysisTickResult {
        val runId = eligibleRunIds().firstOrNull() ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE)
        return tick(runId)
    }

    fun eligibleRunIds(): List<Long> {
        val plans = planStore.load()
        return workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .sortedBy { it.runId }
            .filter { candidate ->
                val currentRevision = workspaceGateway.currentRevision(requireNotNull(candidate.context.workspaceReservation).path)
                plans.none { it.runId == candidate.runId && it.baseRevision == currentRevision && it.coversAcceptedScope(candidate) }
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

    private suspend fun analyze(runId: Long): RepositoryAnalysisTickResult {
        val plans = planStore.load()
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in ACTIONABLE_STATES && it.context.workspaceReservation != null }
            .singleOrNull { candidate ->
                candidate.runId == runId && run {
                val currentRevision = workspaceGateway.currentRevision(requireNotNull(candidate.context.workspaceReservation).path)
                plans.none { it.runId == candidate.runId && it.baseRevision == currentRevision && it.coversAcceptedScope(candidate) }
                }
            } ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.IDLE, runId)
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        val baseRevision = workspaceGateway.currentRevision(workspacePath)
            ?: return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE,
                run.runId,
                diagnostic = "The reserved repository revision is unavailable.",
            )
        val context = runCatching {
            workspaceGateway.collectAnalysisContext(workspacePath, analysisQuery(run))
        }.getOrElse {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = it.message.orEmpty())
        }
        if (context.files.isEmpty()) {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, run.runId, diagnostic = "No repository evidence was selected.")
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
            run,
            candidate,
            DISPOSITIONS,
            OUTPUT_SCHEMA,
            candidate.files.map { RequiredRepositoryEvidence(it.path, it.contentHash) },
            run.workDefinition?.definition?.scope.orEmpty(),
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.verification }.orEmpty(),
        )
        val boundedContext = compactRepositoryContextToBudget(context, profile.inputBudgetTokens) { candidate ->
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
        val admission = resourceController.acquire(provider.resourceDemand(profile), ModelWorkPriority.DELIVERY)
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
            throw exception
        } catch (error: Exception) {
            recordExecution(profile.id, profile, binding, run, envelopeJson, prompt, null, startedAt, false, admission.evidence)
                ?: return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE, run.runId)
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.MODEL_FAILED, run.runId, diagnostic = error.message.orEmpty())
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
        if (output == null) return RepositoryAnalysisTickResult(
            RepositoryAnalysisTickStatus.INVALID_ANALYSIS,
            run.runId,
            diagnostic = repositoryAnalysisDecodeDiagnostic(boundedGeneration, decodedOutput?.exceptionOrNull()),
        )
        repositoryAnalysisIdentityDiagnostic(boundedContext, output)?.let {
            return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, run.runId, diagnostic = it)
        }
        if (output.unresolvedQuestions.isNotEmpty() || output.disposition == DISPOSITION_CONFLICTING) {
            return RepositoryAnalysisTickResult(
                RepositoryAnalysisTickStatus.ARCHITECT_DECISION_REQUIRED,
                run.runId,
                diagnostic = output.unresolvedQuestions.joinToString(" ").ifBlank { "Conflicting implementations require an architect decision." },
            )
        }
        val invalid = validateOutput(run, boundedContext, output)
        if (invalid != null) return RepositoryAnalysisTickResult(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, run.runId, diagnostic = invalid)
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
                    content = output,
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
        output: RepositoryAnalysisPlanContent,
    ): String? {
        repositoryScopeCoverageDiagnostic(run.workDefinition?.definition?.scope.orEmpty(), output)?.let { return it }
        if (output.operations.map { it.order } != (1..output.operations.size).toList()) return "Execution operations are not strictly ordered."
        repositoryAcceptanceCoverageDiagnostic(
            run.workDefinition?.definition?.acceptanceCriteria?.map { it.description }.orEmpty(),
            output,
        )?.let { return it }
        repositoryOperationShapeDiagnostic(context, output)?.let { return it }
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

    private fun analysisQuery(run: WorkflowRunView): String = buildString {
        appendLine(run.context.title)
        appendLine(run.context.content)
        run.workDefinition?.definition?.let {
            appendLine(it.currentBehavior)
            appendLine(it.requiredBehavior)
            appendLine(it.scope.joinToString(" "))
            appendLine(it.constraints.joinToString(" "))
        }
        run.context.recalledEpisodes.forEach { appendLine("${it.problem} ${it.resolution} ${it.evidenceSummary}") }
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
        const val OUTPUT_SCHEMA = "RepositoryAnalysisPlanContent(disposition, summary, evidence, reuse, preservedInvariants, nonGoals, scopeCoverage, operations, verificationCommands, unresolvedQuestions)"

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

internal fun repositoryScopeCoverageDiagnostic(
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
    val evidencePaths = output.evidence.mapTo(hashSetOf()) { it.path }
    val operations = output.operations.associateBy { it.order }
    val createdPaths = output.operations.filter { it.action == PLAN_OPERATION_CREATE }.mapTo(hashSetOf()) { it.path }
    val sourceOperationPaths = output.operations.filter { it.action != PLAN_OPERATION_VERIFY }.mapTo(hashSetOf()) { it.path }
    output.scopeCoverage.forEachIndexed { index, coverage ->
        if (coverage.evidencePaths.isEmpty() || coverage.evidencePaths.any { it !in evidencePaths && it !in createdPaths }) {
            return "Scope coverage ${index + 1} does not cite pinned evidence or a planned creation."
        }
        if (coverage.operationOrders.any { it !in operations }) {
            return "Scope coverage ${index + 1} references an unavailable operation."
        }
        if (requiresSourceOperation(coverage.scope) && coverage.evidencePaths.any { it !in sourceOperationPaths }) {
            return "Scope coverage ${index + 1} cites a path without a corresponding source operation."
        }
    }
    return null
}

private fun canonicalAuthorityText(value: String): String = value
    .replace(Regex("[\\u2010-\\u2015\\u2212]"), "-")
    .replace('\u00a0', ' ')
    .trim()
    .replace(Regex("\\s+"), " ")

private fun requiresSourceOperation(scope: String): Boolean = canonicalAuthorityText(scope)
    .substringBefore(' ')
    .lowercase() !in setOf("inspect", "analyze", "audit")

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
    promptFor: (CodingRepositoryContext) -> String,
): CodingRepositoryContext? {
    if (context.files.isEmpty()) return null
    var lower = 1
    var upper = context.files.size
    var best: CodingRepositoryContext? = null
    while (lower <= upper) {
        val retained = (lower + upper) / 2
        val candidate = context.copy(
            files = context.files.take(retained),
            omittedFileCount = context.omittedFileCount + context.files.size - retained,
        )
        if (estimateModelTokens(promptFor(candidate)) <= inputBudgetTokens) {
            best = candidate
            lower = retained + 1
        } else {
            upper = retained - 1
        }
    }
    return best
}