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
const val MESSAGE_WORK_DEFINITION = 14
const val MESSAGE_DEFINITION_COLLABORATION = 15

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
    val workDefinitions: List<WorkDefinitionManifest> = emptyList(),
    val definitionProposals: List<DefinitionProposalView> = emptyList(),
    val modelProfiles: List<ModelCapabilityProfile> = emptyList(),
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
    WORK_DEFINITION_NOT_READY,
    STORAGE_UNAVAILABLE,
}

data class WorkflowStartResult(val status: WorkflowStartStatus, val snapshot: WorkspaceSnapshot)

enum class WorkDefinitionStatus {
    RECORDED,
    WORK_ITEM_NOT_FOUND,
    UNSUPPORTED_ENTITY,
    WORKFLOW_ALREADY_STARTED,
    INVALID_DEFINITION,
    STORAGE_UNAVAILABLE,
}

data class WorkDefinitionResult(val status: WorkDefinitionStatus, val snapshot: WorkspaceSnapshot)

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
    private val definitionStore: WorkDefinitionStore = TransientWorkDefinitionStore(),
    private val collaborationStore: DefinitionCollaborationStore = TransientDefinitionCollaborationStore(),
    private val modelExperienceStore: ModelExperienceStore = TransientModelExperienceStore(),
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
    private val workDefinitions = mutableListOf<WorkDefinitionManifest>()
    private val collaborationEvents = mutableListOf<DefinitionCollaborationEvent>()
    private val modelExperienceEvents = mutableListOf<ModelExperienceEvent>()
    private var nextRunId = 1L
    private var nextEventId = 1L
    private var nextDefinitionId = 1L
    private var nextCollaborationEventId = 1L
    private var nextModelExperienceEventId = 1L

    init {
        restore(repository.load())
        restoreModelExperience(modelExperienceStore.loadEvents())
        restoreCollaboration(collaborationStore.loadEvents())
        restoreDefinitions(definitionStore.load())
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
    fun submitWorkDefinition(workItemId: Int, submission: WorkDefinitionSubmission): WorkDefinitionResult {
        val proposal = recordDefinitionProposal(
            workItemId,
            COLLABORATOR_HUMAN,
            DefinitionProposalContent(submission, emptyList(), emptyList()),
        )
        if (proposal.status != DefinitionCollaborationStatus.RECORDED || proposal.proposal == null) {
            return definitionFailureFromCollaboration(proposal)
        }
        return acceptDefinitionProposal(proposal.proposal.proposalId)
    }

    private fun persistWorkDefinition(
        workItemId: Int,
        submission: WorkDefinitionSubmission,
        sourceProposal: DefinitionProposalReference?,
    ): WorkDefinitionResult {
        val workItem = committedEntity(workItemId)
            ?: return definitionFailure(WorkDefinitionStatus.WORK_ITEM_NOT_FOUND, "The selected work item does not exist.")
        if (workItem.type != ENTITY_TASK && workItem.type != ENTITY_BUG) return definitionFailure(
            WorkDefinitionStatus.UNSUPPORTED_ENTITY,
            "Only tasks and bugs have a work-definition workflow.",
        )
        if (workflowRuns.any { it.context.workItemId == workItemId }) return definitionFailure(
            WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED,
            "The work definition cannot change after delivery has started.",
        )
        val definition = normalizeDefinition(submission)
        if (!validDefinitionSize(definition)) return definitionFailure(
            WorkDefinitionStatus.INVALID_DEFINITION,
            "The work definition exceeds the supported size or contains an empty entry.",
        )
        val workflow = DefaultSystemWorkflow.resolve(workItem.type)
        check(
            WorkflowStepEngine.canStart(workflow.stepDefinitions.single(), setOf(FACT_WORK_ITEM_EXISTS))
        ) { "The work-definition step start condition is inconsistent with workspace admission" }
        val assessment = DefaultSystemWorkflow.assess(workItem.type, definition)
        val manifest = newWorkDefinitionManifest(
            definitionId = nextDefinitionId,
            revision = workDefinitions.count { it.workItemId == workItemId } + 1,
            workItemId = workItemId,
            workflow = workflow,
            definition = definition,
            assessment = assessment,
            sourceProposal = sourceProposal,
        )
        try {
            definitionStore.append(manifest)
        } catch (_: Exception) {
            return definitionFailure(
                WorkDefinitionStatus.STORAGE_UNAVAILABLE,
                "The work definition could not be saved.",
            )
        }
        workDefinitions += manifest
        nextDefinitionId++
        workflowMessage = when (assessment.status) {
            DEFINITION_READY -> "The work definition is ready for delivery admission."
            DEFINITION_NEEDS_CLARIFICATION -> "The work definition has unresolved questions."
            DEFINITION_NEEDS_SPLIT -> "The requested outcomes should be split into separate work items."
            else -> "The work definition needs more observable facts before delivery can start."
        }
        return WorkDefinitionResult(WorkDefinitionStatus.RECORDED, snapshot(MESSAGE_WORK_DEFINITION))
    }

    @Synchronized
    fun definitionStepContext(workItemId: Int): DefinitionStepContext? {
        val workItem = committedEntity(workItemId) ?: return null
        if (workItem.type != ENTITY_TASK && workItem.type != ENTITY_BUG) return null
        val story = committedEntity(workItem.parentId, ENTITY_STORY) ?: return null
        val epic = committedEntity(story.parentId, ENTITY_EPIC) ?: return null
        val project = committedEntity(epic.parentId, ENTITY_PROJECT) ?: return null
        return DefinitionStepContext(
            workItemId = workItem.id,
            workItemType = workItem.type,
            projectTitle = project.title,
            epicTitle = epic.title,
            storyTitle = story.title,
            title = workItem.title,
            content = workItem.content,
            systemWorkflow = DefaultSystemWorkflow.resolve(workItem.type),
            currentDefinition = workDefinitions.lastOrNull { it.workItemId == workItemId },
            proposals = definitionProposalViews().filter { it.proposal.workItemId == workItemId },
            feedback = collaborationEvents.mapNotNull { it.feedback }
                .filter { feedback ->
                    collaborationEvents.mapNotNull { it.proposal }
                        .any { it.workItemId == workItemId && it.proposalId == feedback.proposalId }
                }
        )
    }

    @Synchronized
    fun recordDefinitionProposal(
        workItemId: Int,
        actor: String,
        content: DefinitionProposalContent,
        provenance: DefinitionExecutionProvenance? = null,
        parentProposalId: Long? = null,
    ): DefinitionCollaborationResult {
        val workItem = committedEntity(workItemId)
            ?: return collaborationFailure(DefinitionCollaborationStatus.WORK_ITEM_NOT_FOUND, "The work item does not exist.")
        if (workItem.type != ENTITY_TASK && workItem.type != ENTITY_BUG) return collaborationFailure(
            DefinitionCollaborationStatus.WORK_ITEM_NOT_FOUND,
            "Only tasks and bugs support definition collaboration.",
        )
        if (workflowRuns.any { it.context.workItemId == workItemId }) return collaborationFailure(
            DefinitionCollaborationStatus.WORKFLOW_ALREADY_STARTED,
            "Definition collaboration is closed after delivery starts.",
        )
        val definition = normalizeDefinition(content.definition)
        if (
            actor !in setOf(COLLABORATOR_HUMAN, COLLABORATOR_LOCAL_LLM) ||
            (actor == COLLABORATOR_LOCAL_LLM && provenance == null) ||
            !validDefinitionSize(definition) ||
            content.observations.size > MAX_DEFINITION_ENTRIES ||
            content.assumptions.size > MAX_DEFINITION_ENTRIES ||
            (content.observations + content.assumptions).any { it.isBlank() || it.length > MAX_DEFINITION_TEXT }
        ) return collaborationFailure(
            DefinitionCollaborationStatus.INVALID_RECORD,
            "The definition proposal is invalid.",
        )
        if (actor == COLLABORATOR_LOCAL_LLM) {
            val execution = provenance?.executionId?.let { executionId ->
                modelExperienceEvents.mapNotNull { it.execution }.singleOrNull { it.executionId == executionId }
            }
            if (execution == null || !provenanceMatchesExecution(workItemId, requireNotNull(provenance), execution)) {
                return collaborationFailure(
                    DefinitionCollaborationStatus.INVALID_RECORD,
                    "The local model proposal does not reference its exact execution evidence.",
                )
            }
        }
        val proposals = collaborationEvents.mapNotNull { it.proposal }.filter { it.workItemId == workItemId }
        val action = if (proposals.isEmpty()) ACTION_PROPOSE else ACTION_REVISE
        if (!WorkflowStepEngine.canPerform(DefaultSystemWorkflow.resolve(workItem.type).stepDefinitions.single(), actor, action)) {
            return collaborationFailure(
                DefinitionCollaborationStatus.INVALID_RECORD,
                "$actor is not authorized to $action this definition.",
            )
        }
        val proposal = newDefinitionProposal(
            proposalId = nextCollaborationEventId,
            workItemId = workItemId,
            revision = proposals.size + 1,
            parentProposalId = parentProposalId ?: proposals.lastOrNull()?.proposalId,
            actor = actor,
            content = content.copy(
                definition = definition,
                observations = content.observations.map(String::trim),
                assumptions = content.assumptions.map(String::trim),
            ),
            provenance = provenance,
        )
        val event = DefinitionCollaborationEvent(nextCollaborationEventId, proposal = proposal)
        if (!appendCollaborationEvent(event)) return collaborationFailure(
            DefinitionCollaborationStatus.STORAGE_UNAVAILABLE,
            "The definition proposal could not be saved.",
        )
        workflowMessage = "Recorded ${if (actor == COLLABORATOR_HUMAN) "a human" else "a local LLM"} definition proposal."
        return DefinitionCollaborationResult(
            DefinitionCollaborationStatus.RECORDED,
            snapshot(MESSAGE_DEFINITION_COLLABORATION),
            proposal,
        )
    }

    @Synchronized
    fun recordDefinitionFeedback(proposalId: Long, content: String): DefinitionCollaborationResult {
        val proposal = collaborationEvents.mapNotNull { it.proposal }.firstOrNull { it.proposalId == proposalId }
            ?: return collaborationFailure(DefinitionCollaborationStatus.PROPOSAL_NOT_FOUND, "The proposal does not exist.")
        if (workflowRuns.any { it.context.workItemId == proposal.workItemId }) return collaborationFailure(
            DefinitionCollaborationStatus.WORKFLOW_ALREADY_STARTED,
            "Definition collaboration is closed after delivery starts.",
        )
        val workItem = requireNotNull(committedEntity(proposal.workItemId))
        if (!WorkflowStepEngine.canPerform(
                DefaultSystemWorkflow.resolve(workItem.type).stepDefinitions.single(),
                COLLABORATOR_HUMAN,
                ACTION_FEEDBACK,
            )
        ) return collaborationFailure(DefinitionCollaborationStatus.INVALID_RECORD, "Human feedback is not authorized.")
        val normalized = content.trim()
        if (normalized.isEmpty() || normalized.length > MAX_DEFINITION_TEXT) return collaborationFailure(
            DefinitionCollaborationStatus.INVALID_RECORD,
            "Feedback must contain bounded text.",
        )
        val event = DefinitionCollaborationEvent(
            eventId = nextCollaborationEventId,
            feedback = DefinitionFeedback(nextCollaborationEventId, proposalId, COLLABORATOR_HUMAN, normalized),
        )
        if (!appendCollaborationEvent(event)) return collaborationFailure(
            DefinitionCollaborationStatus.STORAGE_UNAVAILABLE,
            "The feedback could not be saved.",
        )
        workflowMessage = "Recorded human feedback for proposal revision ${proposal.revision}."
        return DefinitionCollaborationResult(
            DefinitionCollaborationStatus.RECORDED,
            snapshot(MESSAGE_DEFINITION_COLLABORATION),
            proposal,
        )
    }

    @Synchronized
    fun acceptDefinitionProposal(
        proposalId: Long,
        editedDefinition: WorkDefinitionSubmission? = null,
    ): WorkDefinitionResult {
        val proposal = collaborationEvents.mapNotNull { it.proposal }.firstOrNull { it.proposalId == proposalId }
            ?: return definitionFailure(WorkDefinitionStatus.WORK_ITEM_NOT_FOUND, "The proposal does not exist.")
        val workItem = committedEntity(proposal.workItemId)
            ?: return definitionFailure(WorkDefinitionStatus.WORK_ITEM_NOT_FOUND, "The proposal work item does not exist.")
        if (!WorkflowStepEngine.canPerform(
                DefaultSystemWorkflow.resolve(workItem.type).stepDefinitions.single(),
                COLLABORATOR_HUMAN,
                ACTION_ACCEPT,
            )
        ) return definitionFailure(WorkDefinitionStatus.INVALID_DEFINITION, "Human acceptance is not authorized.")
        var acceptedProposal = proposal
        val normalizedEdit = editedDefinition?.let(::normalizeDefinition)
        val humanRevisionFields = normalizedEdit?.let { definitionRevisionFields(proposal.content.definition, it) } ?: 0
        if (normalizedEdit != null && normalizedEdit != proposal.content.definition) {
            val revised = recordDefinitionProposal(
                proposal.workItemId,
                COLLABORATOR_HUMAN,
            proposal.content.copy(definition = normalizedEdit),
                parentProposalId = proposal.proposalId,
            )
            if (revised.status != DefinitionCollaborationStatus.RECORDED || revised.proposal == null) {
                return definitionFailureFromCollaboration(revised)
            }
            acceptedProposal = revised.proposal
        }
        val result = persistWorkDefinition(
            acceptedProposal.workItemId,
            acceptedProposal.content.definition,
            DefinitionProposalReference(acceptedProposal.proposalId, acceptedProposal.hash),
        )
        if (result.status == WorkDefinitionStatus.RECORDED) return result.copy(snapshot = snapshot(MESSAGE_WORK_DEFINITION))
        return result
    }

    @Synchronized
    fun recordModelExecution(draft: ModelExecutionObservationDraft): ModelExecutionObservation? {
        val observation = ModelExecutionObservation(
            executionId = nextModelExperienceEventId,
            profile = draft.profile,
            binding = draft.binding,
            workflowStepId = draft.workflowStepId,
            workItemId = draft.workItemId,
            envelopeHash = draft.envelopeHash,
            promptHash = draft.promptHash,
            outputHash = draft.outputHash,
            inputTokens = draft.inputTokens,
            outputTokens = draft.outputTokens,
            latencyMillis = draft.latencyMillis,
            schemaValid = draft.schemaValid,
            resourceAdmission = draft.resourceAdmission,
        )
        val event = ModelExperienceEvent(nextModelExperienceEventId, execution = observation)
        return if (appendModelExperienceEvent(event)) observation else null
    }

    @Synchronized
    fun modelProfiles(): List<ModelCapabilityProfile> =
        modelCapabilityProfiles(modelExperienceEvents, authoritativeModelSatisfaction())

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
        val workDefinition = workDefinitions.lastOrNull { it.workItemId == workItemId }
        if (workDefinition?.assessment?.status != DEFINITION_READY) return workflowFailure(
            WorkflowStartStatus.WORK_DEFINITION_NOT_READY,
            "Complete the system work-definition workflow before starting delivery.",
        )
        val source = workDefinition.sourceProposal
        val acceptedProposal = source?.let { reference ->
            collaborationEvents.mapNotNull { it.proposal }.singleOrNull {
                it.proposalId == reference.proposalId && it.hash == reference.proposalHash
            }
        }
        if (acceptedProposal?.workItemId != workItemId) return workflowFailure(
            WorkflowStartStatus.WORK_DEFINITION_NOT_READY,
            "Accept a source-pinned definition proposal before starting delivery.",
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

        val workflow = DefaultDeliveryWorkflow.resolve(workItem.type, workDefinition)
        val deliveryStep = workflow.stepDefinitions.single()
        check(
            WorkflowStepEngine.canStart(
                deliveryStep,
                setOf(FACT_WORK_DEFINITION_READY, FACT_REPOSITORY_AVAILABLE, FACT_REPOSITORY_CLEAN),
            )
        ) { "The delivery step start condition is inconsistent with workflow admission" }
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
        check(
            WorkflowStepEngine.hasRequiredContext(
                deliveryStep,
                setOf(
                    CONTEXT_TICKET,
                    CONTEXT_SYSTEM_POLICY,
                    CONTEXT_WORK_DEFINITION,
                    CONTEXT_REPOSITORY_REVISION,
                    CONTEXT_EPISODIC_MEMORY,
                ),
            )
        ) { "The delivery context does not satisfy its step contract" }
        val run = WorkflowRun(
            runId = nextRunId,
            createdAt = java.time.Instant.now().toString(),
            state = RUN_STATE_CONTEXT_READY,
            context = unsignedManifest.copy(hash = manifestHash(unsignedManifest)),
            workflow = workflow,
            workDefinition = workDefinition,
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
        val step = deliveryStep(run)
        val requirement = step.evidenceContract.requirements.firstOrNull { it.kind == kind }
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
        val satisfied = step.evidenceContract.requirements.mapNotNull { required ->
            evidenceForRevision[required.kind]?.takeIf { it.passed }
        }
        val completed = satisfied.size == step.evidenceContract.requirements.size
        val signalName = when {
            completed -> SIGNAL_COMPLETED
            passed -> SIGNAL_EVIDENCE_ACCEPTED
            else -> SIGNAL_EVIDENCE_REJECTED
        }
        val signal = WorkflowStepEngine.resolveSignal(step, signalName)
        val decision = TransitionDecision(
            decisionId = nextEventId,
            fromState = currentState,
            toState = signal.target,
            accepted = signal.accepted,
            reason = when (signalName) {
                SIGNAL_COMPLETED -> "All ${satisfied.size} evidence gates passed for revision ${revision.commitHash}."
                SIGNAL_EVIDENCE_ACCEPTED -> "${requirement.kind} evidence passed deterministic validation."
                else -> "${requirement.kind} evidence failed deterministic validation."
            },
            evidenceIds = if (completed) satisfied.map { it.evidenceId }.sorted() else listOf(evidence.evidenceId),
            decidedAt = now,
            signal = signal.signal,
        )
        val episode = if (completed) completionEpisode(run, revision.commitHash, satisfied, now) else null
        val event = WorkflowEvent(nextEventId, runId, evidence, decision = decision, producedEpisode = episode)
        if (!appendWorkflowEvent(event)) return workflowMutationFailure(
            WorkflowMutationStatus.STORAGE_UNAVAILABLE,
            "The evidence and transition decision could not be saved.",
        )
        workflowMessage = when {
            completed -> "All evidence gates passed. The work item is done and its work episode is available for future recall."
            passed -> "${requirement.kind} evidence passed. ${step.evidenceContract.requirements.size - satisfied.size} gate${if (step.evidenceContract.requirements.size - satisfied.size == 1) "" else "s"} remain."
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
        val signal = WorkflowStepEngine.resolveSignal(deliveryStep(run), SIGNAL_CANCELLED)
        val event = WorkflowEvent(
            eventId = nextEventId,
            runId = run.runId,
            decision = TransitionDecision(
                decisionId = nextEventId,
                fromState = currentState,
                toState = signal.target,
                accepted = signal.accepted,
                reason = "The workflow run was cancelled by an explicit workspace command.",
                evidenceIds = emptyList(),
                decidedAt = now,
                signal = signal.signal,
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
        val latestDefinitions = workDefinitions.groupBy { it.workItemId }
            .values
            .map { revisions -> revisions.maxBy { it.revision } }
            .sortedBy { it.workItemId }
        return WorkspaceSnapshot(
            resources,
            repositoryBindings.views(projectIds),
            workflowRuns.map(::runView),
            latestDefinitions,
            definitionProposalViews(),
            modelProfiles(),
        )
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
                    DefaultDeliveryWorkflow.isCompatible(run.workflow, workItem.type, run.workDefinition)
            ) { "Workflow run ${run.runId} references an invalid workflow context" }
            run.workDefinition?.let { definition ->
                require(
                    definition.assessment.status == DEFINITION_READY &&
                        definition.workItemId == workItem.id &&
                        workDefinitions.any { it.definitionId == definition.definitionId && it == definition }
                ) { "Workflow run ${run.runId} references an invalid work definition" }
            }
            require(workflowRuns.none { it.context.workItemId == run.context.workItemId }) {
                "Work item ${run.context.workItemId} has multiple active workflow runs"
            }
            workflowRuns += run
            nextRunId++
        }
    }

    private fun restoreDefinitions(restoredDefinitions: List<WorkDefinitionManifest>) {
        restoredDefinitions.forEach { manifest ->
            require(manifest.definitionId == nextDefinitionId) {
                "Expected work definition ID $nextDefinitionId, found ${manifest.definitionId}"
            }
            val workItem = committedEntity(manifest.workItemId)
            require(workItem != null && workItem.type == manifest.systemWorkflow.workItemType) {
                "Work definition ${manifest.definitionId} references an invalid work item"
            }
            val expectedRevision = workDefinitions.count { it.workItemId == manifest.workItemId } + 1
            require(manifest.revision == expectedRevision) {
                "Expected work definition revision $expectedRevision for item ${manifest.workItemId}"
            }
            require(DefaultSystemWorkflow.isCompatible(manifest.systemWorkflow)) {
                "Work definition ${manifest.definitionId} references an invalid system workflow"
            }
            require(manifest.assessment == DefaultSystemWorkflow.assess(workItem.type, manifest.definition)) {
                "Work definition ${manifest.definitionId} has an invalid assessment"
            }
            manifest.sourceProposal?.let { source ->
                val proposal = collaborationEvents.mapNotNull { it.proposal }
                    .singleOrNull { it.proposalId == source.proposalId }
                require(proposal != null && proposal.workItemId == workItem.id && proposal.hash == source.proposalHash) {
                    "Work definition ${manifest.definitionId} references an invalid proposal"
                }
            }
            workDefinitions += manifest
            nextDefinitionId++
        }
    }

    private fun restoreCollaboration(restoredEvents: List<DefinitionCollaborationEvent>) {
        restoredEvents.forEach { event ->
            require(event.eventId == nextCollaborationEventId) {
                "Expected collaboration event ID $nextCollaborationEventId, found ${event.eventId}"
            }
            event.proposal?.let { proposal ->
                val workItem = committedEntity(proposal.workItemId)
                require(workItem != null && workItem.type in setOf(ENTITY_TASK, ENTITY_BUG)) {
                    "Proposal ${proposal.proposalId} references an invalid work item"
                }
                require(proposal.revision == collaborationEvents.mapNotNull { it.proposal }
                    .count { it.workItemId == proposal.workItemId } + 1) {
                    "Proposal ${proposal.proposalId} has an invalid revision"
                }
                proposal.provenance?.let { provenance ->
                    val execution = modelExperienceEvents.mapNotNull { it.execution }
                        .singleOrNull { it.executionId == provenance.executionId }
                    require(execution != null && provenanceMatchesExecution(proposal.workItemId, provenance, execution)) {
                        "Proposal ${proposal.proposalId} references an invalid model execution"
                    }
                }
            }
            collaborationEvents += event
            nextCollaborationEventId++
        }
    }

    private fun restoreModelExperience(restoredEvents: List<ModelExperienceEvent>) {
        restoredEvents.forEach { event ->
            require(event.eventId == nextModelExperienceEventId) {
                "Expected model experience event ID $nextModelExperienceEventId, found ${event.eventId}"
            }
            modelExperienceEvents += event
            nextModelExperienceEventId++
        }
    }

    private fun appendCollaborationEvent(event: DefinitionCollaborationEvent): Boolean = try {
        collaborationStore.appendEvent(event)
        collaborationEvents += event
        nextCollaborationEventId++
        true
    } catch (_: Exception) {
        false
    }

    private fun appendModelExperienceEvent(event: ModelExperienceEvent): Boolean = try {
        modelExperienceStore.appendEvent(event)
        modelExperienceEvents += event
        nextModelExperienceEventId++
        true
    } catch (_: Exception) {
        false
    }

    private fun authoritativeModelSatisfaction(): List<ModelSatisfactionObservation> = buildList {
        val proposals = collaborationEvents.mapNotNull { it.proposal }.associateBy { it.proposalId }
        collaborationEvents.mapNotNull { it.feedback }.forEach { feedback ->
            val proposal = proposals[feedback.proposalId] ?: return@forEach
            val executionId = proposal.provenance?.executionId ?: return@forEach
            add(
                ModelSatisfactionObservation(
                    satisfactionId = feedback.feedbackId,
                    executionId = executionId,
                    workItemId = proposal.workItemId,
                    proposalId = proposal.proposalId,
                    signal = MODEL_SATISFACTION_REVISION_REQUESTED,
                )
            )
        }
        workDefinitions.forEach { manifest ->
            val accepted = manifest.sourceProposal?.proposalId?.let(proposals::get) ?: return@forEach
            val modelProposal = if (accepted.provenance != null) accepted else accepted.parentProposalId?.let(proposals::get)
            val executionId = modelProposal?.provenance?.executionId ?: return@forEach
            val changedFields = definitionRevisionFields(modelProposal.content.definition, accepted.content.definition)
            add(
                ModelSatisfactionObservation(
                    satisfactionId = manifest.definitionId,
                    executionId = executionId,
                    workItemId = accepted.workItemId,
                    proposalId = modelProposal.proposalId,
                    signal = if (changedFields == 0) {
                        MODEL_SATISFACTION_ACCEPTED_UNCHANGED
                    } else {
                        MODEL_SATISFACTION_ACCEPTED_AFTER_EDIT
                    },
                    humanRevisionFields = changedFields,
                )
            )
        }
    }

    private fun provenanceMatchesExecution(
        workItemId: Int,
        provenance: DefinitionExecutionProvenance,
        execution: ModelExecutionObservation,
    ): Boolean = execution.workItemId == workItemId &&
        execution.binding.model == provenance.model &&
        execution.profile.id == provenance.executionProfileId &&
        com.orchard.backend.vector.modelBindingFingerprint(execution.binding) == provenance.bindingFingerprint &&
        execution.envelopeHash == provenance.contextHash &&
        execution.promptHash == provenance.promptHash &&
        execution.outputHash == provenance.outputHash

    private fun definitionProposalViews(): List<DefinitionProposalView> {
        val feedback = collaborationEvents.mapNotNull { it.feedback }.groupBy { it.proposalId }
        val accepted = workDefinitions.mapNotNull { definition ->
            definition.sourceProposal?.proposalId?.let { it to definition.definitionId }
        }.toMap()
        return collaborationEvents.mapNotNull { it.proposal }.map { proposal ->
            DefinitionProposalView(proposal, feedback[proposal.proposalId].orEmpty(), accepted[proposal.proposalId])
        }
    }

    private fun restoreEvents(restoredEvents: List<WorkflowEvent>) {
        restoredEvents.forEach { event ->
            require(event.eventId == nextEventId) { "Expected workflow event ID $nextEventId, found ${event.eventId}" }
            val run = workflowRuns.firstOrNull { it.runId == event.runId }
            require(run != null) { "Workflow event ${event.eventId} references an invalid run" }
            event.decision?.takeIf { it.signal.isNotBlank() }?.let { decision ->
                val declared = WorkflowStepEngine.resolveSignal(deliveryStep(run), decision.signal)
                require(declared.target == decision.toState && declared.accepted == decision.accepted) {
                    "Workflow event ${event.eventId} contains an invalid transition signal"
                }
            }
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
        val latestDecision = events.mapNotNull { it.decision }.lastOrNull()
        if (latestDecision != null) {
            if (!latestDecision.accepted && latestDecision.toState == RUN_STATE_DONE) {
                return RUN_STATE_EVIDENCE_BLOCKED
            }
            return latestDecision.toState
        }
        return if (events.any { it.evidence != null }) RUN_STATE_EVIDENCE_PENDING else RUN_STATE_CONTEXT_READY
    }

    private fun deliveryStep(run: WorkflowRun): WorkflowStepDefinition =
        run.workflow.stepDefinitions.singleOrNull()
            ?: DefaultDeliveryWorkflow.resolve(run.context.workItemType, run.workDefinition).stepDefinitions.single()

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
            workDefinition = run.workDefinition,
        )
    }

    private fun workflowFailure(status: WorkflowStartStatus, message: String): WorkflowStartResult {
        workflowMessage = message
        return WorkflowStartResult(status, snapshot(MESSAGE_WORKFLOW_START))
    }

    private fun definitionFailure(status: WorkDefinitionStatus, message: String): WorkDefinitionResult {
        workflowMessage = message
        return WorkDefinitionResult(status, snapshot(MESSAGE_WORK_DEFINITION))
    }

    private fun collaborationFailure(
        status: DefinitionCollaborationStatus,
        message: String,
    ): DefinitionCollaborationResult {
        workflowMessage = message
        return DefinitionCollaborationResult(status, snapshot(MESSAGE_DEFINITION_COLLABORATION))
    }

    private fun definitionFailureFromCollaboration(result: DefinitionCollaborationResult): WorkDefinitionResult {
        val status = when (result.status) {
            DefinitionCollaborationStatus.WORK_ITEM_NOT_FOUND,
            DefinitionCollaborationStatus.PROPOSAL_NOT_FOUND -> WorkDefinitionStatus.WORK_ITEM_NOT_FOUND
            DefinitionCollaborationStatus.WORKFLOW_ALREADY_STARTED -> WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED
            DefinitionCollaborationStatus.INVALID_RECORD -> WorkDefinitionStatus.INVALID_DEFINITION
            DefinitionCollaborationStatus.STORAGE_UNAVAILABLE -> WorkDefinitionStatus.STORAGE_UNAVAILABLE
            DefinitionCollaborationStatus.RECORDED -> WorkDefinitionStatus.STORAGE_UNAVAILABLE
        }
        return WorkDefinitionResult(status, result.snapshot)
    }

    private fun normalizeDefinition(submission: WorkDefinitionSubmission): WorkDefinitionSubmission = submission.copy(
        requestedOutcome = submission.requestedOutcome.trim(),
        currentBehavior = submission.currentBehavior.trim(),
        requiredBehavior = submission.requiredBehavior.trim(),
        scope = submission.scope.map(String::trim),
        nonGoals = submission.nonGoals.map(String::trim),
        constraints = submission.constraints.map(String::trim),
        acceptanceCriteria = submission.acceptanceCriteria.map {
            it.copy(description = it.description.trim(), verification = it.verification.trim())
        },
        unresolvedQuestions = submission.unresolvedQuestions.map(String::trim),
        proposedSplitTitles = submission.proposedSplitTitles.map(String::trim),
        reproduction = submission.reproduction.trim(),
        regressionCriterion = submission.regressionCriterion.trim(),
    )

    private fun validDefinitionSize(definition: WorkDefinitionSubmission): Boolean {
        val collections = listOf(
            definition.scope,
            definition.nonGoals,
            definition.constraints,
            definition.unresolvedQuestions,
            definition.proposedSplitTitles,
        )
        val scalarValues = listOf(
            definition.requestedOutcome,
            definition.currentBehavior,
            definition.requiredBehavior,
            definition.reproduction,
            definition.regressionCriterion,
        )
        return collections.all { it.size <= MAX_DEFINITION_ENTRIES && it.all { value -> value.length <= MAX_DEFINITION_TEXT } } &&
            scalarValues.all { it.length <= MAX_DEFINITION_TEXT } &&
            definition.acceptanceCriteria.size <= MAX_DEFINITION_ENTRIES &&
            definition.acceptanceCriteria.all {
                it.description.length <= MAX_DEFINITION_TEXT && it.verification.length <= MAX_DEFINITION_TEXT
            }
    }

    private fun definitionRevisionFields(before: WorkDefinitionSubmission, after: WorkDefinitionSubmission): Int =
        listOf(
            before.requestedOutcome != after.requestedOutcome,
            before.currentBehavior != after.currentBehavior,
            before.requiredBehavior != after.requiredBehavior,
            before.scope != after.scope,
            before.nonGoals != after.nonGoals,
            before.constraints != after.constraints,
            before.acceptanceCriteria != after.acceptanceCriteria,
            before.unresolvedQuestions != after.unresolvedQuestions,
            before.proposedSplitTitles != after.proposedSplitTitles,
            before.reproduction != after.reproduction,
            before.regressionCriterion != after.regressionCriterion,
        ).count { it }

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
        MESSAGE_WORK_DEFINITION -> workflowMessage
        MESSAGE_DEFINITION_COLLABORATION -> workflowMessage
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
        const val MAX_DEFINITION_ENTRIES = 16
        const val MAX_DEFINITION_TEXT = 4096
        val GIT_HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val TERMINAL_RUN_STATES = setOf(RUN_STATE_DONE, RUN_STATE_CANCELLED)
    }
}