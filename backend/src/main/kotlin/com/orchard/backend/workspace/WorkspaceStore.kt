package com.orchard.backend.workspace

import com.orchard.backend.api.DocumentIntent
import kotlinx.serialization.Serializable

const val ENTITY_PROJECT = 1
const val ENTITY_EPIC = 2
const val ENTITY_STORY = 3
const val ENTITY_TASK = 4
const val ENTITY_BUG = 5

const val ACTION_CREATE = 1

const val MESSAGE_READY = 0
const val MESSAGE_CREATED = 1
const val MESSAGE_REJECTED = 2
const val MESSAGE_OLLAMA_UNAVAILABLE = 3
const val MESSAGE_CLARIFY = 4
const val MESSAGE_UNSUPPORTED_ACTION = 5
const val MESSAGE_BUSY = 6
const val MESSAGE_WORKFLOW_PARENT_REQUIRED = 7
const val MESSAGE_WORKFLOW_HIERARCHY = 8
const val MESSAGE_BATCH_CREATED = 9
const val MESSAGE_STORAGE_UNAVAILABLE = 10
const val MESSAGE_REPOSITORY_BINDING = 11
const val MESSAGE_WORKFLOW_START = 12
const val MESSAGE_WORKFLOW_EVENT = 13

@Serializable
data class WorkspaceResource(
    val type: String,
    val path: String,
    val action: String,
)

@Serializable
data class WorkspaceSnapshot(
    val resources: Map<String, WorkspaceResource>,
    val repositories: Map<Int, RepositoryView> = emptyMap(),
    val workflowRuns: List<WorkflowRunView> = emptyList(),
)

enum class RepositoryBindStatus { BOUND, PROJECT_NOT_FOUND, INVALID_REPOSITORY, STORAGE_UNAVAILABLE }

data class RepositoryBindResult(val status: RepositoryBindStatus, val snapshot: WorkspaceSnapshot)

enum class WorkflowStartStatus {
    CREATED,
    WORK_ITEM_NOT_FOUND,
    UNSUPPORTED_ENTITY,
    REPOSITORY_UNAVAILABLE,
    REPOSITORY_DIRTY,
    ALREADY_STARTED,
    STORAGE_UNAVAILABLE,
}

data class WorkflowStartResult(val status: WorkflowStartStatus, val snapshot: WorkspaceSnapshot)

enum class WorkflowMutationStatus {
    RECORDED,
    RUN_NOT_FOUND,
    RUN_CLOSED,
    INVALID_RECORD,
    REVISION_INVALID,
    STORAGE_UNAVAILABLE,
}

data class WorkflowMutationResult(val status: WorkflowMutationStatus, val snapshot: WorkspaceSnapshot)

@Serializable
data class WorkspaceEntity(
    val id: Int,
    val type: Int,
    val workflowId: Long,
    val parentId: Int,
    val title: String,
    val content: String,
    val status: Int = 0,
)

class WorkspaceStore(
    private val repository: WorkspaceRepository = TransientWorkspaceRepository,
    private val repositoryBindings: RepositoryBindingStore = TransientRepositoryBindingStore,
    private val workflowMemory: WorkflowMemoryStore = TransientWorkflowMemoryStore(),
) {
    private val entities = mutableListOf<WorkspaceEntity>()
    private var nextEntityId = 1
    private var batchEntityCount = 0
    private var batchNextEntityId = 1

    @get:Synchronized
    val entityCount: Int get() = entities.size
    var lastCreatedId: Int = 0
        private set
    var lastCreatedType: Int = 0
        private set
    var lastWorkflowResult: Int = WORKFLOW_ACCEPTED
        private set
    var lastRejectedType: Int = 0
        private set
    var lastBatchCreatedCount: Int = 0
        private set
    var lastRejectedOperation: Int = 0
        private set
    var lastPlanRejectionReason: Int = 0
        private set

    private var batchLastCreatedId = 0
    private var batchLastCreatedType = 0
    private var batchActive = false
    private var committedEntityCount = 0
    private var committedLastCreatedId = 0
    private var committedLastCreatedType = 0
    private var repositoryMessage = ""
    private var workflowMessage = ""
    private val workflowRuns = mutableListOf<WorkflowRun>()
    private val workflowEvents = mutableListOf<WorkflowEvent>()
    private var nextRunId = 1L
    private var nextEventId = 1L

    init {
        restore(repository.load())
        restoreRuns(workflowMemory.loadRuns())
        restoreEvents(workflowMemory.loadEvents())
    }

    @Synchronized
    fun beginBatch() {
        check(!batchActive) { "A workspace batch is already active" }
        batchEntityCount = entities.size
        batchNextEntityId = nextEntityId
        batchLastCreatedId = lastCreatedId
        batchLastCreatedType = lastCreatedType
        batchActive = true
    }

    @Synchronized
    fun commitBatch() {
        check(batchActive) { "No workspace batch is active" }
        repository.commit(
            newEntities = entities.subList(batchEntityCount, entities.size).toList(),
            allEntities = entities.toList(),
        )
        committedEntityCount = entities.size
        committedLastCreatedId = lastCreatedId
        committedLastCreatedType = lastCreatedType
        batchActive = false
    }

    @Synchronized
    fun rollbackBatch() {
        check(batchActive) { "No workspace batch is active" }
        while (entities.size > batchEntityCount) entities.removeLast()
        nextEntityId = batchNextEntityId
        lastCreatedId = batchLastCreatedId
        lastCreatedType = batchLastCreatedType
        batchActive = false
    }

    @Synchronized
    fun markBatchCreated(count: Int) {
        lastBatchCreatedCount = count
    }

    @Synchronized
    fun markPlanRejected(operation: Int, reason: Int) {
        lastRejectedOperation = operation
        lastPlanRejectionReason = reason
    }

    @Synchronized
    fun createDefaultEpic(projectId: Int): Boolean {
        check(batchActive) { "Workspace mutations require an active batch" }
        lastRejectedType = ENTITY_EPIC
        if (entities.size >= MAX_ENTITIES) return false
        if (projectId == 0) {
            lastWorkflowResult = WORKFLOW_PARENT_REQUIRED
            return false
        }
        if (entity(projectId, ENTITY_PROJECT) == null) {
            lastWorkflowResult = WORKFLOW_PARENT_NOT_FOUND
            return false
        }
        lastWorkflowResult = WORKFLOW_ACCEPTED
        return appendEntity(ENTITY_EPIC, projectId, "General", "", DEFAULT_DELIVERY_WORKFLOW_ID)
    }

    @Synchronized
    fun applyIntent(intent: DocumentIntent): Boolean {
        check(batchActive) { "Workspace mutations require an active batch" }
        lastRejectedType = intent.entityTypeId
        if (entities.size >= MAX_ENTITIES || intent.actionTypeId != ACTION_CREATE) return false
        if (intent.boundWorkflowId != DEFAULT_DELIVERY_WORKFLOW_ID) {
            lastWorkflowResult = WORKFLOW_UNSUPPORTED_ENTITY
            return false
        }

        val parentId = validateWorkflowHierarchy(intent)
        if (lastWorkflowResult != WORKFLOW_ACCEPTED) return false
        return appendEntity(
            intent.entityTypeId,
            parentId,
            intent.title,
            intent.content,
            intent.boundWorkflowId,
        )
    }

    @Synchronized
    fun entityAt(index: Int): WorkspaceEntity = entities[index]

    @Synchronized
    fun bindRepository(projectId: Int, path: String): RepositoryBindResult {
        if (entity(projectId, ENTITY_PROJECT) == null) {
            repositoryMessage = "The selected project does not exist."
            return RepositoryBindResult(RepositoryBindStatus.PROJECT_NOT_FOUND, snapshot(MESSAGE_REPOSITORY_BINDING))
        }
        val status = try {
            repositoryBindings.bind(projectId, path)
            repositoryMessage = "Repository bound to the selected project."
            RepositoryBindStatus.BOUND
        } catch (_: IllegalArgumentException) {
            repositoryMessage = "Select an existing directory inside a Git repository."
            RepositoryBindStatus.INVALID_REPOSITORY
        } catch (_: Exception) {
            repositoryMessage = "The repository binding could not be saved."
            RepositoryBindStatus.STORAGE_UNAVAILABLE
        }
        return RepositoryBindResult(status, snapshot(MESSAGE_REPOSITORY_BINDING))
    }

    @Synchronized
    fun startWorkflow(workItemId: Int): WorkflowStartResult {
        val workItem = committedEntity(workItemId)
        if (workItem == null) return workflowFailure(
            WorkflowStartStatus.WORK_ITEM_NOT_FOUND,
            "The selected work item does not exist.",
        )
        if (workItem.type != ENTITY_TASK && workItem.type != ENTITY_BUG) return workflowFailure(
            WorkflowStartStatus.UNSUPPORTED_ENTITY,
            "Only tasks and bugs can start an execution workflow.",
        )
        if (workflowRuns.any { it.context.workItemId == workItemId }) return workflowFailure(
            WorkflowStartStatus.ALREADY_STARTED,
            "This work item already has an active workflow run.",
        )

        val story = committedEntity(workItem.parentId, ENTITY_STORY)
            ?: return workflowFailure(WorkflowStartStatus.WORK_ITEM_NOT_FOUND, "The work item hierarchy is incomplete.")
        val epic = committedEntity(story.parentId, ENTITY_EPIC)
            ?: return workflowFailure(WorkflowStartStatus.WORK_ITEM_NOT_FOUND, "The work item hierarchy is incomplete.")
        val project = committedEntity(epic.parentId, ENTITY_PROJECT)
            ?: return workflowFailure(WorkflowStartStatus.WORK_ITEM_NOT_FOUND, "The work item hierarchy is incomplete.")
        val head = runCatching { repositoryBindings.resolveHead(project.id) }.getOrNull()
            ?: return workflowFailure(
                WorkflowStartStatus.REPOSITORY_UNAVAILABLE,
                "Bind an available repository with at least one commit before starting work.",
            )
        if (!head.clean) return workflowFailure(
            WorkflowStartStatus.REPOSITORY_DIRTY,
            "Commit or discard existing repository changes before pinning workflow context.",
        )

        val workflow = DefaultDeliveryWorkflow.resolve(workItem.type)
        val recalls = try {
            workflowMemory.recallEpisodes(
                EpisodeQuery(project.id, workItem.type, workflow.id, workItem.title, workItem.content)
            )
        } catch (_: Exception) {
            return workflowFailure(
                WorkflowStartStatus.STORAGE_UNAVAILABLE,
                "Past work could not be recalled, so no workflow run was started.",
            )
        }
        val unsignedManifest = ContextManifest(
            projectId = project.id,
            epicId = epic.id,
            storyId = story.id,
            workItemId = workItem.id,
            workItemType = workItem.type,
            title = workItem.title,
            content = workItem.content,
            workflowId = workflow.id,
            workflowVersion = workflow.version,
            repository = head,
            recalledEpisodes = recalls,
            hash = "",
        )
        val run = WorkflowRun(
            runId = nextRunId,
            createdAt = java.time.Instant.now().toString(),
            state = RUN_STATE_CONTEXT_READY,
            context = unsignedManifest.copy(hash = manifestHash(unsignedManifest)),
            workflow = workflow,
        )
        try {
            workflowMemory.appendRun(run)
        } catch (_: Exception) {
            return workflowFailure(
                WorkflowStartStatus.STORAGE_UNAVAILABLE,
                "The workflow context could not be saved. No run was started.",
            )
        }
        workflowRuns += run
        nextRunId++
        workflowMessage = buildString {
            append("Context recalled and ${workflow.id} resolved at ${head.commitHash.take(8)}.")
            if (recalls.isNotEmpty()) append(" Recalled ${recalls.size} related work episode${if (recalls.size == 1) "" else "s"}.")
        }
        return WorkflowStartResult(WorkflowStartStatus.CREATED, snapshot(MESSAGE_WORKFLOW_START))
    }

    @Synchronized
    fun recordAttempt(runId: Long, submission: AttemptSubmission): WorkflowMutationResult {
        val run = workflowRuns.firstOrNull { it.runId == runId }
            ?: return workflowMutationFailure(WorkflowMutationStatus.RUN_NOT_FOUND, "The workflow run does not exist.")
        if (runState(runId) in TERMINAL_RUN_STATES) return workflowMutationFailure(
            WorkflowMutationStatus.RUN_CLOSED,
            "The workflow run is already closed.",
        )
        if (
            submission.description.isBlank() || submission.description.length > MAX_ATTEMPT_TEXT ||
            submission.outcome.isBlank() || submission.outcome.length > MAX_ATTEMPT_TEXT ||
            !submission.diagnosticHash.matches(SHA256)
        ) return workflowMutationFailure(WorkflowMutationStatus.INVALID_RECORD, "The attempt record is invalid.")

        val now = java.time.Instant.now().toString()
        val event = WorkflowEvent(
            eventId = nextEventId,
            runId = run.runId,
            attempt = AttemptRecord(
                attemptId = nextEventId,
                description = submission.description.trim(),
                outcome = submission.outcome.trim(),
                diagnosticHash = submission.diagnosticHash.lowercase(),
                successful = submission.successful,
                recordedAt = now,
            ),
        )
        if (!appendWorkflowEvent(event)) return workflowMutationFailure(
            WorkflowMutationStatus.STORAGE_UNAVAILABLE,
            "The attempt could not be saved.",
        )
        workflowMessage = "Recorded ${if (submission.successful) "a successful" else "a failed"} approach for this run."
        return WorkflowMutationResult(WorkflowMutationStatus.RECORDED, snapshot(MESSAGE_WORKFLOW_EVENT))
    }

    @Synchronized
    fun submitEvidence(runId: Long, submission: EvidenceSubmission): WorkflowMutationResult {
        val run = workflowRuns.firstOrNull { it.runId == runId }
            ?: return workflowMutationFailure(WorkflowMutationStatus.RUN_NOT_FOUND, "The workflow run does not exist.")
        val currentState = runState(runId)
        if (currentState in TERMINAL_RUN_STATES) return workflowMutationFailure(
            WorkflowMutationStatus.RUN_CLOSED,
            "The workflow run is already closed.",
        )
        val kind = submission.kind.trim().uppercase()
        val requirement = run.workflow.evidenceContract.requirements.firstOrNull { it.kind == kind }
            ?: return workflowMutationFailure(
                WorkflowMutationStatus.INVALID_RECORD,
                "Evidence kind $kind is not required by this workflow.",
            )
        if (
            !submission.revision.matches(GIT_HASH) || !submission.outputHash.matches(SHA256) ||
            submission.summary.isBlank() || submission.summary.length > MAX_EVIDENCE_SUMMARY ||
            submission.producer.isBlank() || submission.producer.length > MAX_PRODUCER_LENGTH ||
            submission.command.length > MAX_COMMAND_LENGTH ||
            (kind != "SOURCE_DIFF" && submission.command.isBlank())
        ) return workflowMutationFailure(WorkflowMutationStatus.INVALID_RECORD, "The evidence record is invalid.")

        val revision = runCatching {
            repositoryBindings.validateRevision(
                run.context.projectId,
                run.context.repository.commitHash,
                submission.revision,
            )
        }.getOrNull() ?: return workflowMutationFailure(
            WorkflowMutationStatus.REVISION_INVALID,
            "The evidence revision is not a descendant of the pinned repository context.",
        )
        val passed = if (kind == "SOURCE_DIFF") revision.changedFromBase else submission.exitCode == 0
        val now = java.time.Instant.now().toString()
        val evidence = EvidenceRecord(
            evidenceId = nextEventId,
            kind = requirement.kind,
            revision = revision.commitHash,
            command = if (kind == "SOURCE_DIFF") {
                "git diff --quiet ${run.context.repository.commitHash} ${revision.commitHash}"
            } else {
                submission.command.trim()
            },
            exitCode = submission.exitCode,
            outputHash = submission.outputHash.lowercase(),
            summary = submission.summary.trim(),
            producer = submission.producer.trim(),
            passed = passed,
            recordedAt = now,
        )
        val evidenceForRevision = (eventsFor(runId).mapNotNull { it.evidence } + evidence)
            .filter { it.revision == revision.commitHash }
            .groupBy { it.kind }
            .mapValues { (_, records) -> records.maxBy { it.evidenceId } }
        val satisfied = run.workflow.evidenceContract.requirements.mapNotNull { required ->
            evidenceForRevision[required.kind]?.takeIf { it.passed }
        }
        val completed = satisfied.size == run.workflow.evidenceContract.requirements.size
        val decision = when {
            completed -> TransitionDecision(
                decisionId = nextEventId,
                fromState = currentState,
                toState = RUN_STATE_DONE,
                accepted = true,
                reason = "All ${satisfied.size} evidence gates passed for revision ${revision.commitHash}.",
                evidenceIds = satisfied.map { it.evidenceId }.sorted(),
                decidedAt = now,
            )
            !passed -> TransitionDecision(
                decisionId = nextEventId,
                fromState = currentState,
                toState = RUN_STATE_DONE,
                accepted = false,
                reason = "${requirement.kind} evidence failed deterministic validation.",
                evidenceIds = listOf(evidence.evidenceId),
                decidedAt = now,
            )
            else -> null
        }
        val episode = if (completed) completionEpisode(run, revision.commitHash, satisfied, now) else null
        val event = WorkflowEvent(nextEventId, runId, evidence, decision = decision, producedEpisode = episode)
        if (!appendWorkflowEvent(event)) return workflowMutationFailure(
            WorkflowMutationStatus.STORAGE_UNAVAILABLE,
            "The evidence and transition decision could not be saved.",
        )
        workflowMessage = when {
            completed -> "All evidence gates passed. The work item is done and its work episode is available for future recall."
            passed -> "${requirement.kind} evidence passed. ${run.workflow.evidenceContract.requirements.size - satisfied.size} gate${if (run.workflow.evidenceContract.requirements.size - satisfied.size == 1) "" else "s"} remain."
            else -> "${requirement.kind} evidence failed. The workflow remains blocked until passing evidence is submitted."
        }
        return WorkflowMutationResult(WorkflowMutationStatus.RECORDED, snapshot(MESSAGE_WORKFLOW_EVENT))
    }

    @Synchronized
    fun cancelWorkflow(runId: Long): WorkflowMutationResult {
        val run = workflowRuns.firstOrNull { it.runId == runId }
            ?: return workflowMutationFailure(WorkflowMutationStatus.RUN_NOT_FOUND, "The workflow run does not exist.")
        val currentState = runState(runId)
        if (currentState in TERMINAL_RUN_STATES) return workflowMutationFailure(
            WorkflowMutationStatus.RUN_CLOSED,
            "The workflow run is already closed.",
        )
        val now = java.time.Instant.now().toString()
        val event = WorkflowEvent(
            eventId = nextEventId,
            runId = run.runId,
            decision = TransitionDecision(
                decisionId = nextEventId,
                fromState = currentState,
                toState = RUN_STATE_CANCELLED,
                accepted = true,
                reason = "The workflow run was cancelled by an explicit workspace command.",
                evidenceIds = emptyList(),
                decidedAt = now,
            ),
        )
        if (!appendWorkflowEvent(event)) return workflowMutationFailure(
            WorkflowMutationStatus.STORAGE_UNAVAILABLE,
            "The cancellation could not be saved.",
        )
        workflowMessage = "The workflow run was cancelled."
        return WorkflowMutationResult(WorkflowMutationStatus.RECORDED, snapshot(MESSAGE_WORKFLOW_EVENT))
    }

    @Synchronized
    fun snapshot(messageCode: Int): WorkspaceSnapshot {
        val resources = linkedMapOf<String, WorkspaceResource>()
        resources["focus"] = WorkspaceResource(
            "FOCUS",
            committedLastCreatedId.toString(),
            committedLastCreatedType.toString(),
        )
        resources["message"] = WorkspaceResource("MESSAGE", message(messageCode), "none")
        entities.take(committedEntityCount).forEach { entity ->
            val run = workflowRuns.firstOrNull { it.context.workItemId == entity.id }
            val projectedStatus = when (run?.let { runState(it.runId) }) {
                RUN_STATE_DONE -> 3
                RUN_STATE_CANCELLED, null -> entity.status
                else -> 1
            }
            resources["entity-${entity.id}"] = WorkspaceResource(
                type = typeLabel(entity.type),
                path = entity.title,
                action = "id=${entity.id};parent=${entity.parentId};status=$projectedStatus",
            )
        }
        val projectIds = entities.take(committedEntityCount)
            .filter { it.type == ENTITY_PROJECT }
            .mapTo(linkedSetOf()) { it.id }
        return WorkspaceSnapshot(resources, repositoryBindings.views(projectIds), workflowRuns.map(::runView))
    }

    private fun appendEntity(entityType: Int, parentId: Int, title: String, content: String, workflowId: Long): Boolean {
        val entity = WorkspaceEntity(
            id = nextEntityId++,
            type = entityType,
            workflowId = workflowId,
            parentId = parentId,
            title = title,
            content = content,
        )
        entities += entity
        lastCreatedId = entity.id
        lastCreatedType = entity.type
        return true
    }

    private fun restore(restoredEntities: List<WorkspaceEntity>) {
        require(restoredEntities.size <= MAX_ENTITIES) { "Workspace contains more than $MAX_ENTITIES entities" }
        restoredEntities.forEach { restored ->
            require(restored.id == nextEntityId) { "Expected workspace entity ID $nextEntityId, found ${restored.id}" }
            require(restored.type in ENTITY_PROJECT..ENTITY_BUG) { "Unsupported persisted entity type ${restored.type}" }
            require(restored.workflowId == DEFAULT_DELIVERY_WORKFLOW_ID) { "Unsupported persisted workflow ${restored.workflowId}" }
            val requiredParentType = DefaultDeliveryWorkflow.requiredParentType(restored.type)
            if (requiredParentType == 0) {
                require(restored.parentId == 0) { "Project ${restored.id} cannot have a parent" }
            } else {
                require(entity(restored.parentId, requiredParentType) != null) {
                    "Entity ${restored.id} has invalid parent ${restored.parentId}"
                }
            }
            entities += restored
            nextEntityId = restored.id + 1
            lastCreatedId = restored.id
            lastCreatedType = restored.type
        }
        committedEntityCount = entities.size
        committedLastCreatedId = lastCreatedId
        committedLastCreatedType = lastCreatedType
    }

    private fun restoreRuns(restoredRuns: List<WorkflowRun>) {
        restoredRuns.forEach { run ->
            require(run.runId == nextRunId) { "Expected workflow run ID $nextRunId, found ${run.runId}" }
            val workItem = committedEntity(run.context.workItemId)
            require(workItem != null && workItem.type == run.context.workItemType) {
                "Workflow run ${run.runId} references an invalid work item"
            }
            val story = committedEntity(run.context.storyId, ENTITY_STORY)
            val epic = committedEntity(run.context.epicId, ENTITY_EPIC)
            val project = committedEntity(run.context.projectId, ENTITY_PROJECT)
            require(
                workItem.parentId == story?.id &&
                    story.parentId == epic?.id &&
                    epic.parentId == project?.id
            ) { "Workflow run ${run.runId} references an invalid hierarchy" }
            require(
                run.context.repository.projectId == project.id &&
                    run.context.workflowId == run.workflow.id &&
                    run.context.workflowVersion == run.workflow.version &&
                    run.workflow == DefaultDeliveryWorkflow.resolve(workItem.type)
            ) { "Workflow run ${run.runId} references an invalid workflow context" }
            require(workflowRuns.none { it.context.workItemId == run.context.workItemId }) {
                "Work item ${run.context.workItemId} has multiple active workflow runs"
            }
            workflowRuns += run
            nextRunId++
        }
    }

    private fun restoreEvents(restoredEvents: List<WorkflowEvent>) {
        restoredEvents.forEach { event ->
            require(event.eventId == nextEventId) { "Expected workflow event ID $nextEventId, found ${event.eventId}" }
            val run = workflowRuns.firstOrNull { it.runId == event.runId }
            require(run != null) { "Workflow event ${event.eventId} references an invalid run" }
            event.producedEpisode?.let { episode ->
                require(
                    episode.projectId == run.context.projectId &&
                        episode.workItemType == run.context.workItemType &&
                        episode.workflowId == run.workflow.id
                ) { "Workflow event ${event.eventId} produced an invalid episode" }
            }
            workflowEvents += event
            nextEventId++
        }
    }

    private fun completionEpisode(
        run: WorkflowRun,
        revision: String,
        evidence: List<EvidenceRecord>,
        recordedAt: String,
    ): WorkEpisode {
        val attempts = eventsFor(run.runId).mapNotNull { it.attempt }
        val failedApproaches = buildList {
            attempts.filterNot { it.successful }.forEach { add("${it.description}: ${it.outcome}") }
            eventsFor(run.runId).mapNotNull { it.evidence }.filterNot { it.passed }.forEach { add(it.summary) }
        }
        val resolution = attempts.lastOrNull { it.successful }?.outcome
            ?: evidence.firstOrNull { it.kind == "SOURCE_DIFF" }?.summary
            ?: "Completed the resolved workflow evidence contract."
        return WorkEpisode(
            episodeId = workflowMemory.nextEpisodeId(),
            projectId = run.context.projectId,
            workItemType = run.context.workItemType,
            workflowId = run.workflow.id,
            title = run.context.title,
            problem = run.context.content.ifBlank { run.context.title },
            failedApproaches = failedApproaches,
            resolution = resolution,
            evidenceSummary = evidence.sortedBy { it.kind }.joinToString(" | ") { "${it.kind}: ${it.summary}" },
            sourceRevision = revision,
            recordedAt = recordedAt,
        )
    }

    private fun appendWorkflowEvent(event: WorkflowEvent): Boolean = try {
        workflowMemory.appendEvent(event)
        workflowEvents += event
        nextEventId++
        true
    } catch (_: Exception) {
        false
    }

    private fun eventsFor(runId: Long): List<WorkflowEvent> = workflowEvents.filter { it.runId == runId }

    private fun runState(runId: Long): String {
        val events = eventsFor(runId)
        val accepted = events.mapNotNull { it.decision }.lastOrNull { it.accepted }
        if (accepted != null) return accepted.toState
        if (events.mapNotNull { it.decision }.lastOrNull()?.accepted == false) return RUN_STATE_EVIDENCE_BLOCKED
        return if (events.any { it.evidence != null }) RUN_STATE_EVIDENCE_PENDING else RUN_STATE_CONTEXT_READY
    }

    private fun runView(run: WorkflowRun): WorkflowRunView {
        val events = eventsFor(run.runId)
        return WorkflowRunView(
            runId = run.runId,
            createdAt = run.createdAt,
            state = runState(run.runId),
            context = run.context,
            workflow = run.workflow,
            evidence = events.mapNotNull { it.evidence },
            attempts = events.mapNotNull { it.attempt },
            decisions = events.mapNotNull { it.decision },
        )
    }

    private fun workflowFailure(status: WorkflowStartStatus, message: String): WorkflowStartResult {
        workflowMessage = message
        return WorkflowStartResult(status, snapshot(MESSAGE_WORKFLOW_START))
    }

    private fun workflowMutationFailure(
        status: WorkflowMutationStatus,
        message: String,
    ): WorkflowMutationResult {
        workflowMessage = message
        return WorkflowMutationResult(status, snapshot(MESSAGE_WORKFLOW_EVENT))
    }

    private fun committedEntity(id: Int, type: Int? = null): WorkspaceEntity? = entities
        .take(committedEntityCount)
        .firstOrNull { it.id == id && (type == null || it.type == type) }

    private fun validateWorkflowHierarchy(intent: DocumentIntent): Int {
        val requiredParentType = DefaultDeliveryWorkflow.requiredParentType(intent.entityTypeId)
        if (intent.entityTypeId !in ENTITY_PROJECT..ENTITY_BUG) {
            lastWorkflowResult = WORKFLOW_UNSUPPORTED_ENTITY
            return 0
        }
        if (requiredParentType == 0) {
            lastWorkflowResult = WORKFLOW_ACCEPTED
            return 0
        }

        val requestedParentId = when (intent.entityTypeId) {
            ENTITY_EPIC -> intent.projectId
            ENTITY_STORY -> intent.epicId
            else -> intent.storyId
        }
        if (requestedParentId == 0) {
            lastWorkflowResult = WORKFLOW_PARENT_REQUIRED
            return 0
        }
        val parent = entity(requestedParentId, requiredParentType)
        if (parent == null) {
            lastWorkflowResult = WORKFLOW_PARENT_NOT_FOUND
            return 0
        }

        val hierarchyMatches = when (intent.entityTypeId) {
            ENTITY_STORY -> epicHierarchyMatches(parent, intent.projectId)
            ENTITY_TASK, ENTITY_BUG -> taskHierarchyMatches(parent, intent.epicId, intent.projectId)
            else -> true
        }
        lastWorkflowResult = if (hierarchyMatches) WORKFLOW_ACCEPTED else WORKFLOW_HIERARCHY_MISMATCH
        return if (hierarchyMatches) requestedParentId else 0
    }

    private fun taskHierarchyMatches(story: WorkspaceEntity, requestedEpicId: Int, requestedProjectId: Int): Boolean {
        val epic = entity(story.parentId, ENTITY_EPIC) ?: return false
        if (requestedEpicId != 0 && requestedEpicId != epic.id) return false
        val project = entity(epic.parentId, ENTITY_PROJECT) ?: return false
        return requestedProjectId == 0 || requestedProjectId == project.id
    }

    private fun epicHierarchyMatches(epic: WorkspaceEntity, requestedProjectId: Int): Boolean {
        val project = entity(epic.parentId, ENTITY_PROJECT) ?: return false
        return requestedProjectId == 0 || requestedProjectId == project.id
    }

    private fun entity(id: Int, type: Int): WorkspaceEntity? = entities.firstOrNull { it.id == id && it.type == type }

    private fun message(messageCode: Int): String = when (messageCode) {
        MESSAGE_CREATED -> "Created ${typeLabel(lastCreatedType)}: ${entities.lastOrNull()?.title.orEmpty()}"
        MESSAGE_REJECTED -> "The Architect rejected operation $lastRejectedOperation: ${planRejectionLabel(lastPlanRejectionReason)}."
        MESSAGE_OLLAMA_UNAVAILABLE -> "The local Ollama planner is unavailable. Check that phi3:mini is running."
        MESSAGE_CLARIFY -> "I could not identify both the action and item. Include project, epic, story, task, or bug plus what you want done."
        MESSAGE_UNSUPPORTED_ACTION -> "I understood the request, but this MVP currently supports creation only. Update, delete, and query actions are classified but not yet applied."
        MESSAGE_BUSY -> "The Architect is still processing the previous request. Please send this again when it completes."
        MESSAGE_WORKFLOW_PARENT_REQUIRED -> "The Default Delivery workflow requires this ${typeLabel(lastRejectedType)} to reference an existing ${typeLabel(DefaultDeliveryWorkflow.requiredParentType(lastRejectedType))} ID."
        MESSAGE_WORKFLOW_HIERARCHY -> "The Default Delivery workflow rejected the parent reference because the complete Project, Epic, and Story hierarchy does not exist or does not match."
        MESSAGE_BATCH_CREATED -> "Created $lastBatchCreatedCount governed items from the Architect plan."
        MESSAGE_STORAGE_UNAVAILABLE -> "The workspace could not be saved. No changes were applied."
        MESSAGE_REPOSITORY_BINDING -> repositoryMessage
        MESSAGE_WORKFLOW_START -> workflowMessage
        MESSAGE_WORKFLOW_EVENT -> workflowMessage
        else -> "Describe a project, epic, story, task, or bug to the Architect."
    }

    private fun typeLabel(entityType: Int): String = when (entityType) {
        ENTITY_PROJECT -> "PROJECT"
        ENTITY_EPIC -> "EPIC"
        ENTITY_STORY -> "STORY"
        ENTITY_TASK -> "TASK"
        ENTITY_BUG -> "BUG"
        else -> "UNKNOWN"
    }

    private fun planRejectionLabel(reason: Int): String = when (reason) {
        1 -> "unsupported action"
        2 -> "missing or unsupported entity type"
        3 -> "missing title"
        4 -> "required parent could not be resolved"
        6 -> "workflow validation failed"
        else -> "malformed operation"
    }

    private companion object {
        const val MAX_ENTITIES = 32
        const val MAX_ATTEMPT_TEXT = 4096
        const val MAX_EVIDENCE_SUMMARY = 4096
        const val MAX_PRODUCER_LENGTH = 128
        const val MAX_COMMAND_LENGTH = 2048
        val GIT_HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val TERMINAL_RUN_STATES = setOf(RUN_STATE_DONE, RUN_STATE_CANCELLED)
    }
}