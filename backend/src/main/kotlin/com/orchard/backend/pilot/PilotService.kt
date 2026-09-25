package com.orchard.backend.pilot

import com.orchard.backend.agent.CODING_ATTEMPT_BLOCKED
import com.orchard.backend.agent.CODING_ATTEMPT_RETRY_AUTHORIZED
import com.orchard.backend.agent.CODING_ATTEMPT_RETRY_CONSUMED
import com.orchard.backend.agent.CODING_ATTEMPT_SCOPE_ACCEPTED
import com.orchard.backend.agent.CODING_EXECUTION_BLOCKED
import com.orchard.backend.agent.CODING_EXECUTION_CLAIMED
import com.orchard.backend.agent.CODING_EXECUTION_COMPLETED
import com.orchard.backend.agent.CODING_EXECUTION_DEFERRED
import com.orchard.backend.agent.CODING_EXECUTION_FAILED
import com.orchard.backend.agent.CODING_EXECUTION_INTERRUPTED
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_BLOCKED
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_RETRY_AUTHORIZED
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_RUNNING
import com.orchard.backend.analysis.RepositoryAnalysisAttempt
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.RepositoryIntelligenceImporter
import com.orchard.backend.attention.PERSISTENCE_ABANDONED
import com.orchard.backend.attention.PERSISTENCE_ARCHITECTURE_REQUIRED
import com.orchard.backend.attention.PERSISTENCE_BUDGET_EXHAUSTED
import com.orchard.backend.attention.PERSISTENCE_CONTINUE
import com.orchard.backend.attention.PERSISTENCE_DEADLINE_BLOCKED
import com.orchard.backend.attention.PERSISTENCE_HUMAN_DECISION_REQUIRED
import com.orchard.backend.attention.PERSISTENCE_MODEL_CAPABILITY_LIMIT
import com.orchard.backend.attention.PERSISTENCE_POLICY_BLOCKED
import com.orchard.backend.attention.PERSISTENCE_RECURRENT_FAILURE
import com.orchard.backend.attention.PERSISTENCE_RESOURCE_DEFERRED
import com.orchard.backend.attention.PERSISTENCE_SKILL_REQUIRED
import com.orchard.backend.attention.PURPOSE_CLAIM_ACCEPTED
import com.orchard.backend.attention.PURPOSE_CLAIM_DEFINED
import com.orchard.backend.attention.PURPOSE_CLAIM_EVIDENCE_FAILED
import com.orchard.backend.attention.PURPOSE_CLAIM_EVIDENCE_PASSED
import com.orchard.backend.attention.PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED
import com.orchard.backend.conversation.COMMAND_ADMITTED
import com.orchard.backend.conversation.COMMAND_CORRELATED
import com.orchard.backend.conversation.COMMAND_DISPATCHED
import com.orchard.backend.conversation.COMMAND_FAILED
import com.orchard.backend.conversation.COMMAND_PROPOSED
import com.orchard.backend.conversation.COMMAND_REJECTED
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.ConversationObjectiveRevision
import com.orchard.backend.conversation.OBJECTIVE_ACTIVE
import com.orchard.backend.conversation.OBJECTIVE_AWAITING_ADMISSION
import com.orchard.backend.conversation.OBJECTIVE_BLOCKED
import com.orchard.backend.conversation.OBJECTIVE_CANCELLED
import com.orchard.backend.conversation.OBJECTIVE_CANDIDATE
import com.orchard.backend.conversation.OBJECTIVE_COMPLETED
import com.orchard.backend.conversation.OBJECTIVE_PAUSED
import com.orchard.backend.conversation.OBJECTIVE_READY
import com.orchard.backend.conversation.OBJECTIVE_SUPERSEDED
import com.orchard.backend.config.OrchardOperationMode
import com.orchard.backend.resource.MachineResourceConfiguration
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.ModelProviderAuditEvent
import com.orchard.backend.vector.ModelProviderAuditLog
import com.orchard.backend.workspace.DEFINITION_NEEDS_CLARIFICATION
import com.orchard.backend.workspace.DEFINITION_NEEDS_INVESTIGATION
import com.orchard.backend.workspace.DEFINITION_NEEDS_SPLIT
import com.orchard.backend.workspace.DEFINITION_READY
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_ARCHITECTURE
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_EXPERIENCE
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PLAN_NODE_BLOCKED_DEFINITION
import com.orchard.backend.workspace.PLAN_NODE_BLOCKED_DEPENDENCY
import com.orchard.backend.workspace.PLAN_NODE_CANCELLED
import com.orchard.backend.workspace.PLAN_NODE_DONE
import com.orchard.backend.workspace.PLAN_NODE_ELIGIBLE
import com.orchard.backend.workspace.PLAN_NODE_RUNNING
import com.orchard.backend.workspace.RUN_STATE_CANCELLED
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.exactRepositoryScopePaths
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PilotObjectiveStatus(
    val objectiveId: Long,
    val projectId: Int? = null,
    val title: String,
    val state: String,
    val priority: Int,
)

@Serializable
data class PilotRunStatus(
    val runId: Long,
    val projectId: Int,
    val workItemId: Int,
    val title: String,
    val state: String,
    val workflowId: String,
    val repositoryRevision: String,
)

@Serializable
data class PilotOperationStatus(
    val type: String,
    val state: String,
    val attemptId: Long? = null,
    val diagnostic: String,
    val elapsedMillis: Long? = null,
    val promptHash: String? = null,
)

@Serializable
data class PilotModelStatus(
    val profileId: String? = null,
    val providerFingerprint: String? = null,
    val endpointId: String? = null,
    val bindingId: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val latestPhase: String? = null,
    val providerPhases: List<String> = emptyList(),
    val providerElapsedMillis: Long? = null,
    val streamFrames: Int? = null,
    val promptEvalCount: Int? = null,
    val evalCount: Int? = null,
)

@Serializable
data class PilotEvidenceStatus(
    val present: List<String>,
    val missing: List<String>,
)

@Serializable
data class PilotResourceStatus(
    val activeLeases: Int,
    val reservedMemoryBytes: Long,
    val reservedCpuUnits: Int,
    val queuedRequests: Int,
    val lastDecision: String? = null,
    val lastReason: String? = null,
)

@Serializable
data class PilotAction(
    val id: String,
    val method: String,
    val path: String,
    val costClass: String,
    val reason: String,
)

@Serializable
data class PilotStatus(
    val operationMode: OrchardOperationMode,
    val state: String,
    val activeObjective: PilotObjectiveStatus? = null,
    val currentRun: PilotRunStatus? = null,
    val currentOperation: PilotOperationStatus? = null,
    val model: PilotModelStatus? = null,
    val evidence: PilotEvidenceStatus = PilotEvidenceStatus(emptyList(), emptyList()),
    val resources: PilotResourceStatus? = null,
    val authorizedActions: List<PilotAction> = emptyList(),
    val disallowedActions: List<PilotAction> = emptyList(),
    val atlasDomains: List<String> = emptyList(),
    val observedAt: String,
)

@Serializable
data class PilotTransition(
    val from: String? = null,
    val to: String,
    val trigger: String,
    val authority: String,
    val evidence: List<String> = emptyList(),
    val resourcePredicate: String? = null,
    val retryOrStop: String? = null,
    val projection: String,
    val operatorMeaning: String,
    val terminal: Boolean = false,
)

@Serializable
data class PilotStateDomain(
    val id: String,
    val states: List<String>,
    val transitions: List<PilotTransition>,
)

class PilotService(
    private val workspace: WorkspaceStore,
    private val repositoryAnalysis: RepositoryAnalysisService? = null,
    private val conversationConductor: ConversationConductorService? = null,
    private val resourceController: MachineResourceController? = null,
    private val repositoryIntelligenceImporter: RepositoryIntelligenceImporter? = null,
    private val operationMode: OrchardOperationMode = OrchardOperationMode.AUTONOMOUS,
    private val providerEvents: () -> List<ModelProviderAuditEvent> = { ModelProviderAuditLog.recent() },
    private val now: () -> Instant = Instant::now,
) {
    fun status(): PilotStatus = compilePilotStatus(
        snapshot = workspace.snapshot(MESSAGE_READY),
        analysisAttempts = repositoryAnalysis?.attempts().orEmpty(),
        analysisPlans = repositoryAnalysis?.plans().orEmpty(),
        providerEvents = providerEvents(),
        resources = runCatching { resourceController?.configuration() }.getOrNull(),
        objectives = activeObjectives(),
        operationMode = operationMode,
        intelligenceReady = { run ->
            val reservation = run.context.workspaceReservation
            repositoryIntelligenceImporter == null || (reservation != null &&
                repositoryIntelligenceImporter.compatible(run.context.projectId, reservation.baseRevision) != null)
        },
        coordinatesReady = { run ->
            if (repositoryIntelligenceImporter == null) {
                true
            } else {
                val reservation = run.context.workspaceReservation
                val graph = reservation?.let {
                    repositoryIntelligenceImporter.compatible(run.context.projectId, it.baseRevision)
                }
                val coordinates = exactRepositoryScopePaths(run.workDefinition?.definition?.scope.orEmpty())
                graph != null && coordinates.isNotEmpty() && coordinates.all { coordinate ->
                    graph.nodes.any { it.path == coordinate }
                }
            }
        },
        now = now(),
    )

    fun atlas(): List<PilotStateDomain> = PilotStateAtlas.domains

    private fun activeObjectives(): List<ConversationObjectiveRevision> = conversationConductor?.list().orEmpty()
        .mapNotNull { conversationConductor?.projection(it.conversation.conversationId) }
        .flatMap { it.objectives }
}

internal fun compilePilotStatus(
    snapshot: WorkspaceSnapshot,
    analysisAttempts: List<RepositoryAnalysisAttempt>,
    analysisPlans: List<RepositoryExecutionPlan>,
    providerEvents: List<ModelProviderAuditEvent>,
    resources: MachineResourceConfiguration?,
    objectives: List<ConversationObjectiveRevision>,
    operationMode: OrchardOperationMode = OrchardOperationMode.AUTONOMOUS,
    intelligenceReady: (WorkflowRunView) -> Boolean = { true },
    coordinatesReady: (WorkflowRunView) -> Boolean = { true },
    now: Instant,
): PilotStatus {
    val activeRuns = snapshot.workflowRuns.filter { it.state !in TERMINAL_RUN_STATES }
    val latestActiveAttempt = analysisAttempts.asReversed().firstOrNull { attempt ->
        activeRuns.any { it.runId == attempt.runId }
    }
    val run = latestActiveAttempt?.let { attempt -> activeRuns.singleOrNull { it.runId == attempt.runId } }
        ?: activeRuns.maxByOrNull { it.runId }
    val attempt = run?.let { selected -> analysisAttempts.lastOrNull { it.runId == selected.runId } }
    val plan = run?.let { selected -> analysisPlans.lastOrNull { it.runId == selected.runId } }
    val objective = selectObjective(objectives, run)
    val providerCycle = currentProviderCycle(providerEvents, attempt?.promptHash)
    val operation = compileOperation(run, attempt, plan, now)
    val evidence = compileEvidence(run)
    val actions = compileActions(
        run,
        attempt,
        plan,
        run?.let(intelligenceReady) ?: true,
        run?.let(coordinatesReady) ?: true,
    )
    val state = when {
        run == null -> "IDLE"
        attempt?.state == ANALYSIS_ATTEMPT_RUNNING -> "RUNNING"
        attempt?.state == ANALYSIS_ATTEMPT_BLOCKED -> "BLOCKED"
        attempt?.state == ANALYSIS_ATTEMPT_RETRY_AUTHORIZED -> "ACTION_REQUIRED"
        run.state == RUN_STATE_EVIDENCE_BLOCKED -> "BLOCKED"
        plan != null -> "READY"
        else -> "ACTION_REQUIRED"
    }
    return PilotStatus(
        operationMode = operationMode,
        state = state,
        activeObjective = objective?.let {
            PilotObjectiveStatus(it.objectiveId, it.projectId, it.title, it.state, it.priority)
        },
        currentRun = run?.let {
            val pinnedRevision = it.context.workspaceReservation?.baseRevision ?: it.context.repository.commitHash
            PilotRunStatus(
                it.runId,
                it.context.projectId,
                it.context.workItemId,
                it.context.title,
                it.state,
                it.workflow.id,
                pinnedRevision,
            )
        },
        currentOperation = operation,
        model = compileModel(attempt, providerCycle),
        evidence = evidence,
        resources = resources?.let {
            PilotResourceStatus(
                activeLeases = it.activeLeases,
                reservedMemoryBytes = it.reservedMemoryBytes,
                reservedCpuUnits = it.reservedCpuUnits,
                queuedRequests = it.queuedInteractiveRequests + it.queuedDeliveryRequests + it.queuedMaintenanceRequests,
                lastDecision = it.lastAdmission?.decision?.name,
                lastReason = it.lastAdmission?.reason,
            )
        },
        authorizedActions = actions.first,
        disallowedActions = actions.second,
        atlasDomains = PilotStateAtlas.domains.map { it.id },
        observedAt = now.toString(),
    )
}

private fun selectObjective(
    objectives: List<ConversationObjectiveRevision>,
    run: WorkflowRunView?,
): ConversationObjectiveRevision? = objectives
    .filter { it.state in ACTIVE_OBJECTIVE_STATES }
    .filter { run == null || it.projectId == null || it.projectId == run.context.projectId }
    .sortedWith(compareByDescending<ConversationObjectiveRevision> { it.priority }.thenByDescending { it.objectiveId })
    .firstOrNull()

private fun compileOperation(
    run: WorkflowRunView?,
    attempt: RepositoryAnalysisAttempt?,
    plan: RepositoryExecutionPlan?,
    now: Instant,
): PilotOperationStatus? = when {
    run == null -> null
    attempt != null -> PilotOperationStatus(
        type = "REPOSITORY_ANALYSIS",
        state = attempt.state,
        attemptId = attempt.attemptId,
        diagnostic = attempt.diagnostic,
        elapsedMillis = runCatching { Duration.between(Instant.parse(attempt.recordedAt), now).toMillis().coerceAtLeast(0) }.getOrNull(),
        promptHash = attempt.promptHash,
    )
    plan != null -> PilotOperationStatus(
        type = "REPOSITORY_ANALYSIS",
        state = "PLAN_READY",
        diagnostic = "Execution plan ${plan.planId} revision ${plan.revision} is available.",
    )
    else -> PilotOperationStatus(
        type = "REPOSITORY_ANALYSIS",
        state = "READY",
        diagnostic = "The workflow run is ready for repository analysis.",
    )
}

private fun currentProviderCycle(
    events: List<ModelProviderAuditEvent>,
    promptHash: String?,
): List<ModelProviderAuditEvent> {
    val correlated = if (promptHash == null) events else events.filter { it.promptHash == promptHash }
    val start = correlated.indexOfLast { it.phase == "REQUEST_STARTED" }
    return if (start < 0) emptyList() else correlated.drop(start)
}

private fun compileModel(
    attempt: RepositoryAnalysisAttempt?,
    events: List<ModelProviderAuditEvent>,
): PilotModelStatus? {
    val latest = events.lastOrNull()
    if (attempt?.executionProfileId == null && latest == null) return null
    return PilotModelStatus(
        profileId = attempt?.executionProfileId,
        providerFingerprint = attempt?.providerFingerprint,
        endpointId = latest?.endpointId,
        bindingId = latest?.bindingId,
        model = latest?.model,
        promptTokens = attempt?.inputTokens ?: latest?.promptTokens,
        contextWindowTokens = latest?.contextWindowTokens,
        maxOutputTokens = latest?.maxOutputTokens,
        latestPhase = latest?.phase,
        providerPhases = events.map { it.phase }.distinct(),
        providerElapsedMillis = latest?.elapsedMillis,
        streamFrames = latest?.streamFrames,
        promptEvalCount = events.asReversed().firstNotNullOfOrNull { it.promptEvalCount },
        evalCount = events.asReversed().firstNotNullOfOrNull { it.evalCount },
    )
}

private fun compileEvidence(run: WorkflowRunView?): PilotEvidenceStatus {
    if (run == null) return PilotEvidenceStatus(emptyList(), emptyList())
    val present = run.evidence.filter { it.passed }.map { it.kind }.distinct().sorted()
    val required = run.workflow.evidenceContract.requirements.map { it.kind }.distinct()
    return PilotEvidenceStatus(present, required.filterNot { it in present }.sorted())
}

private fun compileActions(
    run: WorkflowRunView?,
    attempt: RepositoryAnalysisAttempt?,
    plan: RepositoryExecutionPlan?,
    intelligenceReady: Boolean,
    coordinatesReady: Boolean,
): Pair<List<PilotAction>, List<PilotAction>> {
    if (run == null) return emptyList<PilotAction>() to emptyList()
    val authorized = when {
        attempt?.state == ANALYSIS_ATTEMPT_RUNNING -> listOf(
            PilotAction("inspect-provider-progress", "GET", "/api/model-providers/audit-events?limit=20", "READ_ONLY", "A model call owns this run; inspect progress without starting another inference."),
        )
        !intelligenceReady -> listOf(
            PilotAction("ensure-repository-intelligence", "POST", "/api/repository-intelligence/runs/${run.runId}/ensure", "DETERMINISTIC", "Build or reuse compatible repository intelligence before repository analysis."),
        )
        !coordinatesReady -> listOf(
            PilotAction("inspect-work-definition-coordinates", "GET", "/api/workspace", "READ_ONLY", "Repository analysis requires exact repository-relative scope paths in a successor work definition before model admission."),
        )
        attempt?.state == ANALYSIS_ATTEMPT_RETRY_AUTHORIZED -> listOf(
            PilotAction("retry-repository-analysis", "POST", "/api/repository-analysis/runs/${run.runId}/tick", "MODEL_DELIVERY", attempt.diagnostic),
        )
        attempt?.state == ANALYSIS_ATTEMPT_BLOCKED -> listOf(
            PilotAction("authorize-repository-analysis-retry", "POST", "/api/repository-analysis/runs/${run.runId}/retry", "HUMAN_AUTHORITY", "The blocked attempt requires explicit retry authority or a different intervention."),
        )
        plan != null -> listOf(
            PilotAction("run-coding-worker", "POST", "/api/coding-worker/runs/${run.runId}/tick", "MODEL_DELIVERY", "An admitted execution plan is ready for implementation."),
        )
        else -> listOf(
            PilotAction("run-repository-analysis", "POST", "/api/repository-analysis/tick", "MODEL_DELIVERY", "The workflow has no repository execution plan."),
        )
    }
    val disallowed = buildList {
        if (attempt?.state == ANALYSIS_ATTEMPT_RUNNING) add(
            PilotAction("repeat-repository-analysis", "POST", "/api/repository-analysis/tick", "MODEL_DELIVERY", "The current model call already owns the run and resource lease."),
        )
        if (attempt?.state == ANALYSIS_ATTEMPT_BLOCKED) add(
            PilotAction("retry-without-authority", "POST", "/api/repository-analysis/tick", "MODEL_DELIVERY", "The latest blocked attempt has no admitted successor basis."),
        )
    }
    return authorized to disallowed
}

object PilotStateAtlas {
    val domains: List<PilotStateDomain> = listOf(
        domain(
            "ORCHARD_OPERATION_MODE",
            OrchardOperationMode.entries.map { it.name },
            transition(null, OrchardOperationMode.AUTONOMOUS.name, "runtime.start", "PROCESS_CONFIGURATION", projection = "pilot operationMode", meaning = "Eligible background model work may dispatch under existing authority.", terminal = true),
            transition(null, OrchardOperationMode.PILOTED.name, "runtime.start", "PROCESS_CONFIGURATION", projection = "pilot operationMode", meaning = "Model-backed work waits for an explicit pilot action.", terminal = true),
        ),
        domain(
            "CONVERSATION_OBJECTIVE",
            listOf(OBJECTIVE_CANDIDATE, OBJECTIVE_AWAITING_ADMISSION, OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_PAUSED, OBJECTIVE_BLOCKED, OBJECTIVE_COMPLETED, OBJECTIVE_CANCELLED, OBJECTIVE_SUPERSEDED),
            transition(OBJECTIVE_CANDIDATE, OBJECTIVE_AWAITING_ADMISSION, "objective.propose", "MODEL_PROPOSAL", projection = "objective revision awaits admission", meaning = "Review the bounded objective before granting authority."),
            transition(OBJECTIVE_AWAITING_ADMISSION, OBJECTIVE_READY, "objective.admit", "HUMAN", projection = "objective revision admitted", meaning = "The objective may now own admitted commands."),
            transition(OBJECTIVE_READY, OBJECTIVE_ACTIVE, "workflow.activity", "ORCHARD_PROJECTION", projection = "active workflow correlated", meaning = "Monitor the workflow and its evidence."),
            transition(OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED, "workflow.blocked", "ORCHARD_PROJECTION", projection = "objective blocked", meaning = "Inspect the blocker and admitted interventions."),
            transition(OBJECTIVE_BLOCKED, OBJECTIVE_READY, "objective.resume", "HUMAN", projection = "objective resumed", meaning = "Continue only after the blocking basis changes."),
            transition(OBJECTIVE_ACTIVE, OBJECTIVE_PAUSED, "objective.pause", "HUMAN", projection = "objective paused", meaning = "Do not dispatch new work until resumed."),
            transition(OBJECTIVE_PAUSED, OBJECTIVE_READY, "objective.resume", "HUMAN", projection = "objective ready", meaning = "Dispatch may continue."),
            transition(OBJECTIVE_ACTIVE, OBJECTIVE_COMPLETED, "promotion.completed", "ORCHARD_PROJECTION", projection = "objective completed", meaning = "No further action is required.", terminal = true),
            transition(null, OBJECTIVE_CANCELLED, "objective.cancel", "HUMAN", projection = "objective cancelled", meaning = "Stop dependent work.", terminal = true),
        ),
        domain(
            "CONVERSATION_COMMAND",
            listOf(COMMAND_PROPOSED, COMMAND_ADMITTED, COMMAND_DISPATCHED, COMMAND_CORRELATED, COMMAND_FAILED, COMMAND_REJECTED),
            transition(null, COMMAND_PROPOSED, "command.propose", "MODEL_OR_HUMAN", projection = "command proposal", meaning = "Inspect payload and authority."),
            transition(COMMAND_PROPOSED, COMMAND_ADMITTED, "command.admit", "HUMAN", projection = "command admission", meaning = "Execution is authorized."),
            transition(COMMAND_PROPOSED, COMMAND_REJECTED, "command.reject", "HUMAN", projection = "command rejected", meaning = "Do not execute this proposal.", terminal = true),
            transition(COMMAND_ADMITTED, COMMAND_DISPATCHED, "capability.execute", "ORCHARD", projection = "command dispatched", meaning = "Monitor downstream correlation."),
            transition(COMMAND_DISPATCHED, COMMAND_CORRELATED, "capability.correlate", "OWNING_SERVICE", projection = "downstream authority linked", meaning = "Continue from the downstream state.", terminal = true),
            transition(COMMAND_DISPATCHED, COMMAND_FAILED, "capability.fail", "OWNING_SERVICE", projection = "command failed", meaning = "Inspect the deterministic diagnostic.", terminal = true),
        ),
        domain(
            "PROJECT_GENESIS",
            listOf(GENESIS_CLASSIFICATION, GENESIS_EXPERIENCE, GENESIS_ARCHITECTURE, GENESIS_BLUEPRINT, GENESIS_ADMISSION, GENESIS_READY),
            transition(GENESIS_CLASSIFICATION, GENESIS_EXPERIENCE, "genesis.classify", "ADMITTED_COMMAND", projection = "classification recorded", meaning = "Define audience and product promise."),
            transition(GENESIS_EXPERIENCE, GENESIS_ARCHITECTURE, "genesis.define-experience", "ADMITTED_COMMAND", projection = "experience recorded", meaning = "Define components and decisions."),
            transition(GENESIS_ARCHITECTURE, GENESIS_BLUEPRINT, "genesis.define-architecture", "ADMITTED_COMMAND", projection = "architecture recorded", meaning = "Define repository blueprint."),
            transition(GENESIS_BLUEPRINT, GENESIS_ADMISSION, "genesis.define-blueprint", "ADMITTED_COMMAND", projection = "blueprint recorded", meaning = "Review genesis authority."),
            transition(GENESIS_ADMISSION, GENESIS_READY, "genesis.admit", "HUMAN_OR_ADMITTED_COMMAND", projection = "genesis admitted", meaning = "Implementation workflows may start.", terminal = true),
        ),
        domain(
            "WORK_DEFINITION",
            listOf(DEFINITION_NEEDS_INVESTIGATION, DEFINITION_NEEDS_CLARIFICATION, DEFINITION_NEEDS_SPLIT, DEFINITION_READY),
            transition(null, DEFINITION_NEEDS_INVESTIGATION, "definition.assess", "DETERMINISTIC_POLICY", projection = "missing observable facts", meaning = "Investigate the listed missing fields."),
            transition(null, DEFINITION_NEEDS_CLARIFICATION, "definition.assess", "DETERMINISTIC_POLICY", projection = "unresolved questions", meaning = "Obtain bounded clarification."),
            transition(null, DEFINITION_NEEDS_SPLIT, "definition.assess", "DETERMINISTIC_POLICY", projection = "split required", meaning = "Create smaller work items."),
            transition(null, DEFINITION_READY, "definition.accept", "HUMAN", evidence = listOf("source-pinned proposal"), projection = "definition ready", meaning = "Delivery admission may proceed.", terminal = true),
        ),
        domain(
            "STAGED_DELIVERY_NODE",
            listOf(PLAN_NODE_BLOCKED_DEPENDENCY, PLAN_NODE_BLOCKED_DEFINITION, PLAN_NODE_ELIGIBLE, PLAN_NODE_RUNNING, PLAN_NODE_DONE, PLAN_NODE_CANCELLED),
            transition(PLAN_NODE_BLOCKED_DEFINITION, PLAN_NODE_ELIGIBLE, "definition.ready", "WORKSPACE", projection = "node eligible", meaning = "Dependencies and definition are satisfied."),
            transition(PLAN_NODE_BLOCKED_DEPENDENCY, PLAN_NODE_ELIGIBLE, "dependency.done", "WORKSPACE", evidence = listOf("required artifacts"), projection = "node eligible", meaning = "The node may dispatch."),
            transition(PLAN_NODE_ELIGIBLE, PLAN_NODE_RUNNING, "dispatch.start", "STAGED_PLAN", projection = "workflow run created", meaning = "Monitor the correlated run."),
            transition(PLAN_NODE_RUNNING, PLAN_NODE_DONE, "workflow.done", "EVIDENCE_CONTRACT", evidence = listOf("required workflow evidence"), projection = "node done", meaning = "Advance dependent nodes.", terminal = true),
            transition(null, PLAN_NODE_CANCELLED, "workflow.cancel", "HUMAN_OR_POLICY", projection = "node cancelled", meaning = "Do not dispatch this node.", terminal = true),
        ),
        domain(
            "WORKFLOW_RUN",
            listOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED, RUN_STATE_DONE, RUN_STATE_CANCELLED),
            transition(null, RUN_STATE_CONTEXT_READY, "workflow.start", "ADMITTED_WORK_DEFINITION", evidence = listOf("clean repository revision"), projection = "run context pinned", meaning = "Compile and execute the delivery plan."),
            transition(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, "evidence.accepted", "DETERMINISTIC_POLICY", evidence = listOf("partial evidence"), projection = "evidence pending", meaning = "Produce remaining evidence."),
            transition(RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED, "evidence.rejected", "DETERMINISTIC_POLICY", projection = "evidence blocked", meaning = "Repair the rejected evidence basis."),
            transition(RUN_STATE_EVIDENCE_PENDING, RUN_STATE_DONE, "completion.accepted", "EVIDENCE_CONTRACT", evidence = listOf("all required evidence"), projection = "run done", meaning = "Continue audit and promotion.", terminal = true),
            transition(null, RUN_STATE_CANCELLED, "workflow.cancel", "HUMAN_OR_POLICY", projection = "run cancelled", meaning = "Stop execution.", terminal = true),
        ),
        domain(
            "REPOSITORY_ANALYSIS_ATTEMPT",
            listOf(ANALYSIS_ATTEMPT_RUNNING, ANALYSIS_ATTEMPT_BLOCKED, ANALYSIS_ATTEMPT_RETRY_AUTHORIZED),
            transition(null, ANALYSIS_ATTEMPT_RUNNING, "repository-analysis.tick", "WORKFLOW_OR_RETRY_AUTHORITY", resource = "model lease admitted", projection = "provider REQUEST_STARTED", meaning = "Watch provider phases; do not start a duplicate call."),
            transition(ANALYSIS_ATTEMPT_RUNNING, ANALYSIS_ATTEMPT_BLOCKED, "analysis.rejected-or-failed", "DETERMINISTIC_VALIDATOR", evidence = listOf("prompt hash", "provider audit", "diagnostic"), retry = "retry requires a novel or explicitly authorized basis", projection = "blocked attempt", meaning = "Use the diagnostic to correct the next attempt."),
            transition(ANALYSIS_ATTEMPT_BLOCKED, ANALYSIS_ATTEMPT_RETRY_AUTHORIZED, "analysis.retry-authorized", "HUMAN_OR_POLICY", projection = "retry authority", meaning = "One successor attempt may run."),
            transition(ANALYSIS_ATTEMPT_RETRY_AUTHORIZED, ANALYSIS_ATTEMPT_RUNNING, "repository-analysis.tick", "RETRY_AUTHORITY", resource = "model lease admitted", projection = "provider REQUEST_STARTED", meaning = "Monitor the authorized correction."),
        ),
        domain(
            "CODING_EXECUTION",
            listOf(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_DEFERRED, CODING_EXECUTION_BLOCKED, CODING_EXECUTION_COMPLETED, CODING_EXECUTION_FAILED, CODING_EXECUTION_INTERRUPTED),
            transition(null, CODING_EXECUTION_CLAIMED, "coding.tick", "EXECUTION_PLAN", projection = "coding claim", meaning = "The worker owns the plan slice."),
            transition(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_DEFERRED, "resource.defer", "RESOURCE_POLICY", projection = "execution deferred", meaning = "Wait for capacity."),
            transition(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_BLOCKED, "proposal.rejected", "DETERMINISTIC_VALIDATOR", projection = "execution blocked", meaning = "Inspect correction or stop authority."),
            transition(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_COMPLETED, "candidate.verified", "EVIDENCE_CONTRACT", evidence = listOf("candidate revision", "verification evidence"), projection = "candidate completed", meaning = "Continue review and audit.", terminal = true),
            transition(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_FAILED, "execution.failed", "TOOL_OR_MODEL", projection = "execution failed", meaning = "Classify failure before retrying.", terminal = true),
            transition(CODING_EXECUTION_CLAIMED, CODING_EXECUTION_INTERRUPTED, "process.interrupted", "RUNTIME", projection = "execution interrupted", meaning = "Recover or authorize a successor.", terminal = true),
        ),
        domain(
            "CODING_ATTEMPT",
            listOf(CODING_ATTEMPT_SCOPE_ACCEPTED, CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_AUTHORIZED, CODING_ATTEMPT_RETRY_CONSUMED),
            transition(null, CODING_ATTEMPT_SCOPE_ACCEPTED, "scope.validate", "DETERMINISTIC_VALIDATOR", projection = "scope accepted", meaning = "The bounded implementation may proceed."),
            transition(null, CODING_ATTEMPT_BLOCKED, "attempt.reject", "DETERMINISTIC_VALIDATOR", projection = "attempt blocked", meaning = "Inspect the exact rejection."),
            transition(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_AUTHORIZED, "retry.authorize", "HUMAN_OR_POLICY", projection = "retry authorized", meaning = "One corrective successor may run."),
            transition(CODING_ATTEMPT_RETRY_AUTHORIZED, CODING_ATTEMPT_RETRY_CONSUMED, "coding.tick", "CODING_WORKER", projection = "retry consumed", meaning = "Do not reuse this authority.", terminal = true),
        ),
        domain(
            "MODEL_PROVIDER_EXECUTION",
            listOf("REQUEST_STARTED", "RESPONSE_HEADERS_RECEIVED", "STREAM_FRAME", "STREAM_TERMINAL_FRAME", "STREAM_RECONCILED", "STREAM_RECONCILE_FAILED", "REQUEST_FAILED"),
            transition(null, "REQUEST_STARTED", "provider.execute", "MODEL_LEASE", projection = "provider audit event", meaning = "The provider owns the admitted model call."),
            transition("REQUEST_STARTED", "RESPONSE_HEADERS_RECEIVED", "http.headers", "PROVIDER", projection = "provider audit event", meaning = "The provider accepted and began the response."),
            transition("RESPONSE_HEADERS_RECEIVED", "STREAM_FRAME", "stream.frame", "PROVIDER", projection = "provider audit event", meaning = "Generation is making observable progress."),
            transition("STREAM_FRAME", "STREAM_TERMINAL_FRAME", "stream.done", "PROVIDER", projection = "provider audit event", meaning = "The provider reported completion."),
            transition("STREAM_TERMINAL_FRAME", "STREAM_RECONCILED", "stream.validate", "PROVIDER_ADAPTER", projection = "provider audit event", meaning = "The output may enter deterministic validation.", terminal = true),
            transition("STREAM_FRAME", "STREAM_RECONCILE_FAILED", "stream.invalid", "PROVIDER_ADAPTER", projection = "provider audit event", meaning = "Retry only if fallback or retry authority permits it.", terminal = true),
            transition(null, "REQUEST_FAILED", "http.failure", "PROVIDER_ADAPTER", projection = "provider audit event", meaning = "Classify provider failure before retrying.", terminal = true),
        ),
        domain(
            "RESOURCE_ADMISSION",
            ResourceAdmissionDecision.entries.map { it.name },
            transition(null, ResourceAdmissionDecision.ADMITTED.name, "resource.acquire", "RESOURCE_POLICY", projection = "resource admission evidence", meaning = "Execution may consume the lease.", terminal = true),
            transition(null, ResourceAdmissionDecision.POLICY_CAPACITY_EXCEEDED.name, "resource.acquire", "RESOURCE_POLICY", projection = "resource rejection", meaning = "Reduce demand or change policy.", terminal = true),
            transition(null, ResourceAdmissionDecision.LIVE_CAPACITY_EXCEEDED.name, "resource.acquire", "LIVE_TELEMETRY", projection = "resource rejection", meaning = "Defer until capacity changes.", terminal = true),
            transition(null, ResourceAdmissionDecision.CONCURRENCY_EXCEEDED.name, "resource.acquire", "RESOURCE_POLICY", projection = "resource rejection", meaning = "Wait for the active lease; do not duplicate work.", terminal = true),
            transition(null, ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE.name, "resource.acquire", "RESOURCE_POLICY", projection = "resource rejection", meaning = "Restore telemetry before model execution.", terminal = true),
        ),
        domain(
            "PURPOSE_CLAIM",
            listOf(PURPOSE_CLAIM_DEFINED, PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, PURPOSE_CLAIM_EVIDENCE_FAILED, PURPOSE_CLAIM_EVIDENCE_PASSED, PURPOSE_CLAIM_ACCEPTED),
            transition(PURPOSE_CLAIM_DEFINED, PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, "implementation.recorded", "WORKFLOW", projection = "claim implemented", meaning = "Produce claim-bound evidence."),
            transition(PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, PURPOSE_CLAIM_EVIDENCE_FAILED, "evidence.failed", "VERIFICATION", projection = "claim evidence failed", meaning = "Repair implementation or evidence method."),
            transition(PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, PURPOSE_CLAIM_EVIDENCE_PASSED, "evidence.passed", "VERIFICATION", projection = "claim evidence passed", meaning = "Obtain acceptance authority."),
            transition(PURPOSE_CLAIM_EVIDENCE_FAILED, PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, "implementation.revised", "WORKFLOW", projection = "claim revised", meaning = "Re-run the admitted verification."),
            transition(PURPOSE_CLAIM_EVIDENCE_PASSED, PURPOSE_CLAIM_ACCEPTED, "claim.accept", "ACCEPTANCE_AUTHORITY", evidence = listOf("claim-bound passed evidence"), projection = "claim accepted", meaning = "The claim contributes to project completion.", terminal = true),
        ),
        domain(
            "PERSISTENCE_DECISION",
            listOf(PERSISTENCE_CONTINUE, PERSISTENCE_RESOURCE_DEFERRED, PERSISTENCE_DEADLINE_BLOCKED, PERSISTENCE_BUDGET_EXHAUSTED, PERSISTENCE_RECURRENT_FAILURE, PERSISTENCE_MODEL_CAPABILITY_LIMIT, PERSISTENCE_SKILL_REQUIRED, PERSISTENCE_ARCHITECTURE_REQUIRED, PERSISTENCE_HUMAN_DECISION_REQUIRED, PERSISTENCE_POLICY_BLOCKED, PERSISTENCE_ABANDONED),
            transition(null, PERSISTENCE_CONTINUE, "persistence.evaluate", "STOP_AUTHORITY", evidence = listOf("novel attempt basis", "remaining budget"), projection = "continuation authorized", meaning = "Run only the admitted bounded successor.", terminal = true),
            transition(null, PERSISTENCE_RESOURCE_DEFERRED, "persistence.evaluate", "STOP_AUTHORITY", projection = "resource deferred", meaning = "Wait without treating deferral as model failure.", terminal = true),
            transition(null, PERSISTENCE_DEADLINE_BLOCKED, "persistence.evaluate", "STOP_AUTHORITY", projection = "deadline blocked", meaning = "Escalate or revise the deadline.", terminal = true),
            transition(null, PERSISTENCE_BUDGET_EXHAUSTED, "persistence.evaluate", "STOP_AUTHORITY", projection = "budget exhausted", meaning = "Stop inference.", terminal = true),
            transition(null, PERSISTENCE_RECURRENT_FAILURE, "persistence.evaluate", "STOP_AUTHORITY", projection = "recurrent failure", meaning = "Do not repeat an equivalent attempt.", terminal = true),
            transition(null, PERSISTENCE_MODEL_CAPABILITY_LIMIT, "persistence.evaluate", "STOP_AUTHORITY", projection = "model capability limit", meaning = "Route only with evidence of a compatible model.", terminal = true),
            transition(null, PERSISTENCE_SKILL_REQUIRED, "persistence.evaluate", "STOP_AUTHORITY", projection = "skill required", meaning = "Acquire the named bounded skill.", terminal = true),
            transition(null, PERSISTENCE_ARCHITECTURE_REQUIRED, "persistence.evaluate", "STOP_AUTHORITY", projection = "architecture required", meaning = "Return to design authority.", terminal = true),
            transition(null, PERSISTENCE_HUMAN_DECISION_REQUIRED, "persistence.evaluate", "STOP_AUTHORITY", projection = "human decision required", meaning = "Request explicit judgment.", terminal = true),
            transition(null, PERSISTENCE_POLICY_BLOCKED, "persistence.evaluate", "STOP_AUTHORITY", projection = "policy blocked", meaning = "Do not continue while policy applies.", terminal = true),
            transition(null, PERSISTENCE_ABANDONED, "persistence.evaluate", "PROJECT_AUTHORITY", projection = "outcome abandoned", meaning = "End pursuit of the claim.", terminal = true),
        ),
        domain(
            "EXTERNAL_OPERATOR_ACTION",
            listOf(COMMAND_PROPOSED, COMMAND_ADMITTED, COMMAND_DISPATCHED, COMMAND_CORRELATED, COMMAND_FAILED, COMMAND_REJECTED),
            transition(null, COMMAND_PROPOSED, "operator.recommend", "EXTERNAL_OPERATOR", projection = "operator proposal", meaning = "Orchard must validate the recommendation."),
            transition(COMMAND_PROPOSED, COMMAND_ADMITTED, "operator-action.admit", "HUMAN_OR_POLICY", projection = "operator action admitted", meaning = "Execution may proceed through the owning service."),
            transition(COMMAND_ADMITTED, COMMAND_DISPATCHED, "operator-action.execute", "ORCHARD", projection = "operator action dispatched", meaning = "Monitor downstream evidence."),
            transition(COMMAND_DISPATCHED, COMMAND_CORRELATED, "operator-action.correlate", "OWNING_SERVICE", projection = "operator result correlated", meaning = "Continue from Orchard authority.", terminal = true),
            transition(COMMAND_DISPATCHED, COMMAND_FAILED, "operator-action.fail", "OWNING_SERVICE", projection = "operator action failed", meaning = "Inspect evidence before another proposal.", terminal = true),
            transition(COMMAND_PROPOSED, COMMAND_REJECTED, "operator-action.reject", "HUMAN_OR_POLICY", projection = "operator action rejected", meaning = "Do not execute it.", terminal = true),
        ),
    )

    private fun domain(id: String, states: List<String>, vararg transitions: PilotTransition) =
        PilotStateDomain(id, states.distinct(), transitions.toList())

    private fun transition(
        from: String?,
        to: String,
        trigger: String,
        authority: String,
        evidence: List<String> = emptyList(),
        resource: String? = null,
        retry: String? = null,
        projection: String,
        meaning: String,
        terminal: Boolean = false,
    ) = PilotTransition(from, to, trigger, authority, evidence, resource, retry, projection, meaning, terminal)
}

private val ACTIVE_OBJECTIVE_STATES = setOf(
    OBJECTIVE_AWAITING_ADMISSION,
    OBJECTIVE_READY,
    OBJECTIVE_ACTIVE,
    OBJECTIVE_PAUSED,
    OBJECTIVE_BLOCKED,
)

private val TERMINAL_RUN_STATES = setOf(RUN_STATE_DONE, RUN_STATE_CANCELLED)
