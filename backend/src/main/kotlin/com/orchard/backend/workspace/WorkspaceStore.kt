package com.orchard.backend.workspace

import com.orchard.backend.api.DocumentIntent
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
const val MESSAGE_STAGED_DELIVERY_PLAN = 16

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
    val stagedPlans: List<StagedDeliveryPlanView> = emptyList(),
    val circuitProposals: List<CircuitProposalView> = emptyList(),
    val stageWorkflows: List<StageExecutionWorkflowDefinition> = StageExecutionWorkflowRegistry.all(),
    val circuitDispatches: List<CircuitDispatchView> = emptyList(),
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
    STAGED_PLAN_BLOCKED,
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
    private val stagedPlanStore: StagedDeliveryPlanStore = TransientStagedDeliveryPlanStore(),
    private val circuitProposalStore: CircuitProposalStore = TransientCircuitProposalStore(),
    private val circuitDispatchStore: CircuitDispatchStore = TransientCircuitDispatchStore(),
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
    private val stagedPlans = mutableListOf<StagedDeliveryPlan>()
    private val circuitProposals = mutableListOf<CircuitProposal>()
    private val circuitDispatches = mutableListOf<CircuitDispatch>()
    private var nextRunId = 1L
    private var nextEventId = 1L
    private var nextDefinitionId = 1L
    private var nextCollaborationEventId = 1L
    private var nextModelExperienceEventId = 1L
    private var nextStagedPlanId = 1L
    private var nextCircuitProposalId = 1L
    private var nextCircuitDispatchId = 1L
    private var stagedPlanMessage = ""

    init {
        restore(repository.load())
        restoreModelExperience(modelExperienceStore.loadEvents())
        restoreCollaboration(collaborationStore.loadEvents())
        restoreDefinitions(definitionStore.load())
        restoreRuns(workflowMemory.loadRuns())
        restoreEvents(workflowMemory.loadEvents())
        validateRunReplacements()
        restoreStagedPlans(stagedPlanStore.load())
        restoreCircuitProposals(circuitProposalStore.load())
        restoreCircuitDispatches(circuitDispatchStore.load())
        reconcileCircuitDispatches()
        advanceCircuitDispatches()
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
        if (
            intent.entityTypeId in setOf(ENTITY_STORY, ENTITY_TASK, ENTITY_BUG) &&
            activeStagedPlan(parentId)?.stages?.flatMap { it.nodes }?.any { planNodeStarted(it.workItemId) } == true
        ) return false
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
        if (status == RepositoryBindStatus.BOUND) tryAdvanceCircuitDispatches()
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
        tryAdvanceCircuitDispatches()
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
    fun circuitSynthesisContext(scopeId: Int): CircuitSynthesisContext? {
        val scope = committedEntity(scopeId)
            ?.takeIf { it.type in setOf(ENTITY_EPIC, ENTITY_STORY) }
            ?: return null
        val active = activeStagedPlan(scope.id)
        return CircuitSynthesisContext(
            scopeId = scope.id,
            scopeType = scope.type,
            scopeTitle = scope.title,
            scopeContent = scope.content,
            members = directPlanChildren(scope).map { member ->
                CircuitSynthesisMember(
                    workItemId = member.id,
                    workItemType = member.type,
                    title = member.title,
                    content = member.content,
                    definition = workDefinitions.lastOrNull { it.workItemId == member.id },
                    artifactEvidenceKinds = if (member.type in setOf(ENTITY_TASK, ENTITY_BUG)) {
                        DefaultDeliveryWorkflow.resolve(
                            member.type,
                            workDefinitions.lastOrNull { it.workItemId == member.id },
                        ).evidenceContract.requirements.map { it.kind }
                    } else {
                        emptyList()
                    },
                )
            },
            activePlan = active,
            planLocked = active?.stages?.flatMap { it.nodes }?.any { planNodeStarted(it.workItemId) } == true,
            stageWorkflows = StageExecutionWorkflowRegistry.all(),
        )
    }

    @Synchronized
    fun recordCircuitProposal(
        content: CircuitProposalContent,
        provenance: CircuitExecutionProvenance,
        expectedContext: CircuitSynthesisContext? = null,
    ): CircuitProposalResult {
        val submission = content.plan
        val scope = committedEntity(submission.scopeId)
            ?: return circuitProposalFailure(CircuitProposalStatus.SCOPE_NOT_FOUND)
        if (scope.type !in setOf(ENTITY_EPIC, ENTITY_STORY)) {
            return circuitProposalFailure(CircuitProposalStatus.INVALID_SCOPE)
        }
        if (expectedContext != null && circuitSynthesisContext(scope.id) != expectedContext) {
            return circuitProposalFailure(CircuitProposalStatus.STALE_CONTEXT)
        }
        val current = activeStagedPlan(scope.id)
        if (current?.stages?.flatMap { it.nodes }?.any { planNodeStarted(it.workItemId) } == true) {
            return circuitProposalFailure(CircuitProposalStatus.PLAN_LOCKED)
        }
        if (
            submission.baseRevision != (current?.revision ?: 0) ||
            submission.baseHash != current?.hash ||
            submission.sourceProposal != null
        ) return circuitProposalFailure(CircuitProposalStatus.STALE_CONTEXT)
        val stages = normalizedPlanStages(submission)
            ?: return circuitProposalFailure(CircuitProposalStatus.INVALID_PROPOSAL)
        if (
            stages.flatMap { it.nodes }.mapTo(linkedSetOf()) { it.workItemId } !=
            directPlanChildren(scope).mapTo(linkedSetOf()) { it.id }
        ) return circuitProposalFailure(CircuitProposalStatus.INVALID_PROPOSAL)
        val normalizedContent = content.copy(
            plan = submission.copy(
                title = submission.title.trim(),
                stages = stages.map(::stageSubmission),
            ),
            observations = content.observations.map(String::trim),
            assumptions = content.assumptions.map(String::trim),
        )
        val proposal = newCircuitProposal(
            proposalId = nextCircuitProposalId,
            scopeId = scope.id,
            revision = circuitProposals.count { it.scopeId == scope.id } + 1,
            content = normalizedContent,
            provenance = provenance,
        )
        return try {
            circuitProposalStore.append(proposal)
            circuitProposals += proposal
            nextCircuitProposalId++
            CircuitProposalResult(CircuitProposalStatus.RECORDED, snapshot(MESSAGE_STAGED_DELIVERY_PLAN), proposal)
        } catch (_: Exception) {
            circuitProposalFailure(CircuitProposalStatus.STORAGE_UNAVAILABLE)
        }
    }

    @Synchronized
    fun acceptCircuitProposal(proposalId: Long): StagedPlanResult {
        val proposal = circuitProposals.singleOrNull { it.proposalId == proposalId }
            ?: return stagedPlanFailure(StagedPlanStatus.PROPOSAL_NOT_FOUND, "The circuit proposal does not exist.")
        return acceptStagedPlan(
            proposal.content.plan.copy(
                sourceProposal = CircuitProposalReference(proposal.proposalId, proposal.hash),
            )
        )
    }

    @Synchronized
    fun acceptStagedPlan(submission: StagedDeliveryPlanSubmission): StagedPlanResult {
        val scope = committedEntity(submission.scopeId)
            ?: return stagedPlanFailure(StagedPlanStatus.SCOPE_NOT_FOUND, "The staged-plan scope does not exist.")
        if (scope.type !in setOf(ENTITY_EPIC, ENTITY_STORY)) {
            return stagedPlanFailure(StagedPlanStatus.INVALID_SCOPE, "Only Epics and Stories can own staged plans.")
        }
        val current = stagedPlans.lastOrNull { it.scopeId == scope.id }
        if (current != null && current.stages.flatMap { it.nodes }.any { planNodeStarted(it.workItemId) }) {
            return stagedPlanFailure(StagedPlanStatus.PLAN_LOCKED, "The staged plan is pinned because one of its nodes has started.")
        }
        if (
            submission.baseRevision != (current?.revision ?: 0) ||
            submission.baseHash != current?.hash
        ) return stagedPlanFailure(
            StagedPlanStatus.STALE_PLAN,
            "The staged plan changed after this edit began. Reload the active circuit before revising it.",
        )
        val sourceProposal = submission.sourceProposal?.let { reference ->
            circuitProposals.singleOrNull {
                it.proposalId == reference.proposalId && it.hash == reference.proposalHash &&
                    it.scopeId == submission.scopeId &&
                    it.content.plan.baseRevision == submission.baseRevision &&
                    it.content.plan.baseHash == submission.baseHash
            } ?: return stagedPlanFailure(StagedPlanStatus.INVALID_PLAN, "The circuit proposal provenance is invalid.")
        }
        val stages = normalizedPlanStages(submission) ?: return stagedPlanFailure(
            StagedPlanStatus.INVALID_PLAN,
            "The staged plan hierarchy, dependencies, artifacts, or workflows are invalid.",
        )
        val expectedChildren = directPlanChildren(scope).mapTo(linkedSetOf()) { it.id }
        if (stages.flatMap { it.nodes }.mapTo(linkedSetOf()) { it.workItemId } != expectedChildren) {
            return stagedPlanFailure(
                StagedPlanStatus.INVALID_PLAN,
                "The staged plan must contain every current direct child exactly once.",
            )
        }
        val acceptedProposalUnchanged = sourceProposal?.let { proposal ->
            proposal.content.plan.title.trim() == submission.title.trim() &&
                normalizedPlanStages(proposal.content.plan) == stages
        } == true
        val revision = stagedPlans.count { it.scopeId == scope.id } + 1
        val acceptedAt = java.time.Instant.now().toString()
        val hashSource = Json.encodeToString(
            StagedPlanHashSource(
                nextStagedPlanId,
                revision,
                scope.id,
                scope.type,
                submission.title.trim(),
                stages,
                COLLABORATOR_HUMAN,
                acceptedAt,
                submission.sourceProposal,
                acceptedProposalUnchanged,
            )
        )
        val plan = StagedDeliveryPlan(
            planId = nextStagedPlanId,
            revision = revision,
            scopeId = scope.id,
            scopeType = scope.type,
            title = submission.title.trim(),
            stages = stages,
            acceptedAt = acceptedAt,
            hash = stagedPlanHash(hashSource),
            sourceProposal = submission.sourceProposal,
            acceptedProposalUnchanged = acceptedProposalUnchanged,
        )
        try {
            stagedPlanStore.append(plan)
        } catch (_: Exception) {
            return stagedPlanFailure(StagedPlanStatus.STORAGE_UNAVAILABLE, "The staged delivery circuit could not be saved.")
        }
        stagedPlans += plan
        nextStagedPlanId++
        tryAdvanceCircuitDispatches()
        stagedPlanMessage = "The staged delivery circuit was accepted and is now authoritative."
        return StagedPlanResult(StagedPlanStatus.ACCEPTED, snapshot(MESSAGE_STAGED_DELIVERY_PLAN))
    }

    @Synchronized
    fun startWorkflow(workItemId: Int): WorkflowStartResult {
        reconcileCircuitDispatches()
        val latest = circuitDispatches.lastOrNull { dispatch ->
            dispatch.workItemId == workItemId &&
                activeStagedPlan(dispatch.scopeId)?.planId == dispatch.planId
        }
        if (latest?.let(::dispatchRun)?.let { runState(it.runId) == RUN_STATE_CANCELLED } == true) {
            val plan = requireNotNull(activeStagedPlan(latest.scopeId))
            val stage = requireNotNull(plan.stages.singleOrNull { it.stageId == latest.stageId })
            val node = requireNotNull(stage.nodes.singleOrNull { it.nodeId == latest.nodeId })
            try {
                appendCircuitDispatch(plan, stage, node)
            } catch (_: Exception) {
                return workflowFailure(
                    WorkflowStartStatus.STORAGE_UNAVAILABLE,
                    "The replacement circuit dispatch could not be saved.",
                )
            }
        }
        val pending = circuitDispatches.lastOrNull { dispatch ->
            dispatch.workItemId == workItemId && dispatchRun(dispatch) == null &&
                activeStagedPlan(dispatch.scopeId)?.planId == dispatch.planId
        }
        return startWorkflow(workItemId, pending?.dispatchId)
    }

    private fun startWorkflow(workItemId: Int, circuitDispatchId: Long?): WorkflowStartResult {
        val workItem = committedEntity(workItemId)
        if (workItem == null) return workflowFailure(
            WorkflowStartStatus.WORK_ITEM_NOT_FOUND,
            "The selected work item does not exist.",
        )
        if (workItem.type != ENTITY_TASK && workItem.type != ENTITY_BUG) return workflowFailure(
            WorkflowStartStatus.UNSUPPORTED_ENTITY,
            "Only tasks and bugs can start an execution workflow.",
        )
        if (workflowRuns.lastOrNull { it.context.workItemId == workItemId }?.let { runState(it.runId) != RUN_STATE_CANCELLED } == true) return workflowFailure(
            WorkflowStartStatus.ALREADY_STARTED,
            "This work item already has an active workflow run.",
        )
        stagedPlanBlockReason(workItem)?.let { reason ->
            return workflowFailure(WorkflowStartStatus.STAGED_PLAN_BLOCKED, reason)
        }
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
        val dispatch = circuitDispatchId?.let { id -> circuitDispatches.singleOrNull { it.dispatchId == id } }
        if (circuitDispatchId != null && dispatch == null) return workflowFailure(
            WorkflowStartStatus.STORAGE_UNAVAILABLE,
            "The circuit dispatch authority is unavailable.",
        )
        val (executionRepository, workspaceReservation) = try {
            if (dispatch == null) head to null else {
                repositoryBindings.reserveWorkspace(project.id, dispatch.dispatchId, head, dispatch.integrationOwner)
            }
        } catch (_: Exception) {
            return workflowFailure(
                WorkflowStartStatus.REPOSITORY_UNAVAILABLE,
                "The isolated dispatch workspace could not be reserved.",
            )
        }

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
            repository = executionRepository,
            recalledEpisodes = recalls,
            hash = "",
            circuitDispatchId = circuitDispatchId,
            workspaceReservation = workspaceReservation,
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
        if (completed) {
            tryAdvanceCircuitDispatches()
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
            val run = workflowRuns.lastOrNull { it.context.workItemId == entity.id }
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
            stagedPlanViews(),
            circuitProposalViews(),
            StageExecutionWorkflowRegistry.all(),
            circuitDispatchViews(),
        )
    }

    @Synchronized
    fun dispatchEligible(): WorkspaceSnapshot {
        reconcileCircuitDispatches()
        advanceCircuitDispatches()
        return snapshot(MESSAGE_READY)
    }

    private fun tryAdvanceCircuitDispatches(): Boolean = runCatching {
        reconcileCircuitDispatches()
        advanceCircuitDispatches()
    }.isSuccess

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

    private fun restoreStagedPlans(restoredPlans: List<StagedDeliveryPlan>) {
        restoredPlans.forEach { plan ->
            require(plan.planId == nextStagedPlanId) { "Expected staged plan ID $nextStagedPlanId, found ${plan.planId}" }
            val scope = committedEntity(plan.scopeId)
            require(scope != null && scope.type == plan.scopeType && scope.type in setOf(ENTITY_EPIC, ENTITY_STORY)) {
                "Staged plan ${plan.planId} references an invalid scope"
            }
            require(plan.revision == stagedPlans.count { it.scopeId == plan.scopeId } + 1) {
                "Staged plan ${plan.planId} has an invalid revision"
            }
            val submission = StagedDeliveryPlanSubmission(
                plan.scopeId,
                plan.title,
                plan.stages.map { stage ->
                    StagedPlanStageSubmission(
                        stage.stageId,
                        stage.title,
                        stage.executionWorkflowId,
                        stage.executionWorkflowVersion,
                        stage.nodes.map { node ->
                            StagedPlanNodeSubmission(node.nodeId, node.workItemId, node.dependsOn, node.consumes, node.produces)
                        },
                    )
                },
            )
            val normalized = requireNotNull(normalizedPlanStages(submission)) { "Staged plan ${plan.planId} is invalid" }
            require(normalized == plan.stages) { "Staged plan ${plan.planId} is not normalized" }
            val expectedHash = stagedPlanHash(
                Json.encodeToString(
                    StagedPlanHashSource(
                        plan.planId,
                        plan.revision,
                        plan.scopeId,
                        plan.scopeType,
                        plan.title,
                        plan.stages,
                        plan.acceptedBy,
                        plan.acceptedAt,
                        plan.sourceProposal,
                        plan.acceptedProposalUnchanged,
                    )
                )
            )
            require(plan.hash == expectedHash) { "Staged plan ${plan.planId} hash is invalid" }
            stagedPlans += plan
            nextStagedPlanId++
        }
    }

    private fun restoreCircuitProposals(restoredProposals: List<CircuitProposal>) {
        restoredProposals.forEach { proposal ->
            require(proposal.proposalId == nextCircuitProposalId) {
                "Expected circuit proposal ID $nextCircuitProposalId, found ${proposal.proposalId}"
            }
            val scope = committedEntity(proposal.scopeId)
            require(scope?.type in setOf(ENTITY_EPIC, ENTITY_STORY)) {
                "Circuit proposal ${proposal.proposalId} references an invalid scope"
            }
            val execution = modelExperienceEvents.mapNotNull { it.execution }
                .singleOrNull { it.executionId == proposal.provenance.executionId }
            require(
                execution != null && execution.workItemId == proposal.scopeId &&
                    execution.profile.id == proposal.provenance.executionProfileId &&
                    com.orchard.backend.vector.modelBindingFingerprint(execution.binding) == proposal.provenance.bindingFingerprint &&
                    execution.promptHash == proposal.provenance.promptHash &&
                    execution.envelopeHash == proposal.provenance.contextHash &&
                    execution.outputHash == proposal.provenance.outputHash && execution.schemaValid
            ) { "Circuit proposal ${proposal.proposalId} references invalid model execution evidence" }
            circuitProposals += proposal
            nextCircuitProposalId++
        }
        stagedPlans.mapNotNull { plan -> plan.sourceProposal?.let { plan to it } }.forEach { (plan, reference) ->
            require(circuitProposals.any { it.proposalId == reference.proposalId && it.hash == reference.proposalHash }) {
                "Staged plan ${plan.planId} references an invalid circuit proposal"
            }
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
        stagedPlans.forEach { plan ->
            val reference = plan.sourceProposal ?: return@forEach
            val proposal = circuitProposals.singleOrNull {
                it.proposalId == reference.proposalId && it.hash == reference.proposalHash
            } ?: return@forEach
            add(
                ModelSatisfactionObservation(
                    satisfactionId = plan.planId,
                    executionId = proposal.provenance.executionId,
                    workItemId = proposal.scopeId,
                    proposalId = proposal.proposalId,
                    signal = if (plan.acceptedProposalUnchanged) {
                        MODEL_SATISFACTION_ACCEPTED_UNCHANGED
                    } else {
                        MODEL_SATISFACTION_ACCEPTED_AFTER_EDIT
                    },
                    humanRevisionFields = circuitRevisionFields(proposal.content.plan, plan),
                    recordedAt = plan.acceptedAt,
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

    private fun validateRunReplacements() {
        workflowRuns.groupBy { it.context.workItemId }.values.forEach { runs ->
            runs.sortedBy { it.runId }.dropLast(1).forEach { prior ->
                require(runState(prior.runId) == RUN_STATE_CANCELLED) {
                    "Work item ${prior.context.workItemId} has overlapping workflow runs"
                }
            }
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

    private fun circuitRevisionFields(before: StagedDeliveryPlanSubmission, after: StagedDeliveryPlan): Int {
        val beforeStages = before.stages
        return listOf(
            before.title.trim() != after.title,
            beforeStages.size != after.stages.size,
            beforeStages.map { it.stageId.trim() } != after.stages.map { it.stageId },
            beforeStages.map { it.title.trim() } != after.stages.map { it.title },
            beforeStages.map { it.executionWorkflowId.trim() to it.executionWorkflowVersion } !=
                after.stages.map { it.executionWorkflowId to it.executionWorkflowVersion },
            beforeStages.flatMap { it.nodes }.map { it.nodeId.trim() to it.workItemId } !=
                after.stages.flatMap { it.nodes }.map { it.nodeId to it.workItemId },
            beforeStages.flatMap { it.nodes }.map { it.dependsOn.map(String::trim) } !=
                after.stages.flatMap { it.nodes }.map { it.dependsOn },
            beforeStages.flatMap { it.nodes }.map { it.consumes } != after.stages.flatMap { it.nodes }.map { it.consumes },
            beforeStages.flatMap { it.nodes }.map { it.produces } != after.stages.flatMap { it.nodes }.map { it.produces },
        ).count { it }
    }

    private fun normalizedPlanStages(submission: StagedDeliveryPlanSubmission): List<StagedPlanStage>? {
        val scope = committedEntity(submission.scopeId) ?: return null
        if (
            scope.type !in setOf(ENTITY_EPIC, ENTITY_STORY) ||
            submission.title.isBlank() || submission.title.length > MAX_PLAN_TEXT ||
            submission.stages.isEmpty() || submission.stages.size > MAX_PLAN_STAGES
        ) return null
        val stageIds = submission.stages.map { it.stageId.trim() }
        if (stageIds.any { !it.matches(PLAN_ID) } || stageIds.distinct().size != stageIds.size) return null
        val submittedNodes = submission.stages.flatMap { it.nodes }
        if (
            submittedNodes.isEmpty() || submittedNodes.size > MAX_PLAN_NODES ||
            submittedNodes.map { it.nodeId.trim() }.distinct().size != submittedNodes.size ||
            submittedNodes.map { it.workItemId }.distinct().size != submittedNodes.size ||
            (scope.type == ENTITY_EPIC && submittedNodes.any { it.consumes.isNotEmpty() || it.produces.isNotEmpty() })
        ) return null
        val nodeStage = buildMap {
            submission.stages.forEachIndexed { index, stage ->
                if (stage.nodes.isEmpty() || stage.nodes.size > 26) return null
                stage.nodes.forEach { node ->
                    val nodeId = node.nodeId.trim()
                    if (!nodeId.matches(PLAN_ID)) return null
                    put(nodeId, index + 1)
                }
            }
        }
        val submittedById = submittedNodes.associateBy { it.nodeId.trim() }
        return submission.stages.mapIndexed { stageIndex, submittedStage ->
            val ordinal = stageIndex + 1
            val stageTitle = submittedStage.title.trim()
            val workflowId = submittedStage.executionWorkflowId.trim()
            if (
                stageTitle.isBlank() || stageTitle.length > MAX_PLAN_TEXT ||
                StageExecutionWorkflowRegistry.resolve(workflowId, submittedStage.executionWorkflowVersion) == null ||
                (workflowId == "integration-v1" && submittedStage.nodes.size != 1)
            ) return null
            val nodes = submittedStage.nodes.mapIndexed { nodeIndex, submittedNode ->
                val nodeId = submittedNode.nodeId.trim()
                val workItem = committedEntity(submittedNode.workItemId) ?: return null
                val validChild = workItem.parentId == scope.id && when (scope.type) {
                    ENTITY_EPIC -> workItem.type == ENTITY_STORY
                    ENTITY_STORY -> workItem.type in setOf(ENTITY_TASK, ENTITY_BUG)
                    else -> false
                }
                if (!validChild) return null
                val dependencies = submittedNode.dependsOn.map(String::trim)
                if (
                    dependencies.size > MAX_PLAN_EDGES_PER_NODE ||
                    dependencies.distinct().size != dependencies.size || dependencies.any { it == nodeId } ||
                    submittedNode.consumes.size > MAX_PLAN_ARTIFACTS_PER_NODE ||
                    submittedNode.produces.size > MAX_PLAN_ARTIFACTS_PER_NODE
                ) return null
                val dependencyStages = dependencies.map { dependency -> nodeStage[dependency] ?: return null }
                if (dependencyStages.any { it >= ordinal }) return null
                if ((dependencyStages.maxOrNull()?.plus(1) ?: 1) != ordinal) return null
                val produces = submittedNode.produces.map { artifact ->
                    artifact.copy(
                        kind = artifact.kind.trim(),
                        name = artifact.name.trim(),
                        evidenceKind = artifact.evidenceKind.trim(),
                    )
                }
                val validEvidenceKinds = if (workItem.type in setOf(ENTITY_TASK, ENTITY_BUG)) {
                    DefaultDeliveryWorkflow.resolve(
                        workItem.type,
                        workDefinitions.lastOrNull { it.workItemId == workItem.id },
                    ).evidenceContract.requirements.mapTo(linkedSetOf()) { it.kind }
                } else {
                    emptySet()
                }
                if (
                    produces.any {
                        it.kind.isBlank() || it.name.isBlank() ||
                            it.kind.length > MAX_PLAN_TEXT || it.name.length > MAX_PLAN_TEXT ||
                            it.evidenceKind !in validEvidenceKinds
                    } ||
                    produces.map { it.kind }.distinct().size != produces.size
                ) return null
                val consumes = submittedNode.consumes.map { requirement ->
                    requirement.copy(producerNodeId = requirement.producerNodeId.trim(), kind = requirement.kind.trim())
                }
                if (consumes.distinct().size != consumes.size || consumes.any { requirement ->
                        requirement.producerNodeId !in dependencies || requirement.kind.isBlank() ||
                            submittedById[requirement.producerNodeId]?.produces?.none {
                                it.kind.trim() == requirement.kind
                            } != false
                    }
                ) return null
                StagedPlanNode(
                    nodeId = nodeId,
                    label = "$ordinal${('a'.code + nodeIndex).toChar()}",
                    workItemId = workItem.id,
                    dependsOn = dependencies,
                    consumes = consumes,
                    produces = produces,
                )
            }
            StagedPlanStage(
                stageId = submittedStage.stageId.trim(),
                ordinal = ordinal,
                title = stageTitle,
                executionWorkflowId = workflowId,
                executionWorkflowVersion = submittedStage.executionWorkflowVersion,
                nodes = nodes,
            )
        }
    }

    private fun directPlanChildren(scope: WorkspaceEntity): List<WorkspaceEntity> = entities.take(committedEntityCount)
        .filter { child ->
            child.parentId == scope.id && when (scope.type) {
                ENTITY_EPIC -> child.type == ENTITY_STORY
                ENTITY_STORY -> child.type in setOf(ENTITY_TASK, ENTITY_BUG)
                else -> false
            }
        }

    private fun stageSubmission(stage: StagedPlanStage): StagedPlanStageSubmission = StagedPlanStageSubmission(
        stageId = stage.stageId,
        title = stage.title,
        executionWorkflowId = stage.executionWorkflowId,
        executionWorkflowVersion = stage.executionWorkflowVersion,
        nodes = stage.nodes.map { node ->
            StagedPlanNodeSubmission(
                nodeId = node.nodeId,
                workItemId = node.workItemId,
                dependsOn = node.dependsOn,
                consumes = node.consumes,
                produces = node.produces,
            )
        },
    )

    private fun activeStagedPlan(scopeId: Int): StagedDeliveryPlan? = stagedPlans.lastOrNull { it.scopeId == scopeId }

    private fun planNodeStarted(workItemId: Int): Boolean {
        val workItem = committedEntity(workItemId) ?: return false
        return when (workItem.type) {
            ENTITY_TASK, ENTITY_BUG -> workflowRuns.lastOrNull { it.context.workItemId == workItem.id }
                ?.let { runState(it.runId) != RUN_STATE_CANCELLED } == true
            ENTITY_STORY -> directPlanChildren(workItem).any { planNodeStarted(it.id) }
            else -> false
        }
    }

    private fun planNodeComplete(workItemId: Int): Boolean {
        val workItem = committedEntity(workItemId) ?: return false
        return when (workItem.type) {
            ENTITY_TASK, ENTITY_BUG -> workflowRuns.lastOrNull { it.context.workItemId == workItem.id }
                ?.let { runState(it.runId) == RUN_STATE_DONE } == true
            ENTITY_STORY -> directPlanChildren(workItem).takeIf { it.isNotEmpty() }
                ?.all { planNodeComplete(it.id) } == true
            else -> false
        }
    }

    private fun stagedPlanBlockReason(workItem: WorkspaceEntity): String? {
        val story = committedEntity(workItem.parentId, ENTITY_STORY) ?: return null
        val epic = committedEntity(story.parentId, ENTITY_EPIC) ?: return null
        return listOf(epic.id to story.id, story.id to workItem.id).firstNotNullOfOrNull { (scopeId, memberId) ->
            val plan = activeStagedPlan(scopeId) ?: return@firstNotNullOfOrNull null
            val nodes = plan.stages.flatMap { it.nodes }
            val node = nodes.firstOrNull { it.workItemId == memberId }
                ?: return@firstNotNullOfOrNull "This work item is not included in the active staged delivery circuit."
            stagedPlanNodeBlockReason(plan, node)
        }
    }

    private fun stagedPlanNodeBlockReason(plan: StagedDeliveryPlan, node: StagedPlanNode): String? {
        val scope = committedEntity(plan.scopeId)
            ?: return "The staged delivery circuit scope no longer exists."
        if (!planCoversCurrentHierarchy(plan, scope)) {
            return "The staged delivery circuit is stale because its hierarchy changed. Accept a covering revision."
        }
        val nodes = plan.stages.flatMap { it.nodes }
        val artifacts = stagedPlanArtifactInstances(plan)
        val stage = plan.stages.single { candidate -> candidate.nodes.any { it.nodeId == node.nodeId } }
        val previousStage = plan.stages.getOrNull(stage.ordinal - 2)
        if (previousStage != null) {
            val incomplete = previousStage.nodes.filterNot { planNodeComplete(it.workItemId) }
            if (incomplete.isNotEmpty()) {
                return "Waiting for stage ${previousStage.ordinal} to satisfy its exit policy."
            }
            val previousPolicy = requireNotNull(
                StageExecutionWorkflowRegistry.resolve(
                    previousStage.executionWorkflowId,
                    previousStage.executionWorkflowVersion,
                )
            )
            if (
                previousPolicy.exitPolicy == "ALL_STAGE_NODES_DONE_AND_OUTPUTS_ACCEPTED" &&
                previousStage.nodes.any { producer ->
                    producer.produces.any { output ->
                        artifacts.none { it.producerNodeId == producer.nodeId && it.kind == output.kind }
                    }
                }
            ) return "Waiting for stage ${previousStage.ordinal} output artifacts."
        }
        val policy = requireNotNull(
            StageExecutionWorkflowRegistry.resolve(stage.executionWorkflowId, stage.executionWorkflowVersion)
        )
        val priorNodes = plan.stages.filter { it.ordinal < stage.ordinal }.flatMap { it.nodes }
        if (
            policy.entryPolicy in setOf("ALL_PRIOR_STAGE_NODES_DONE", "ALL_PRIOR_STAGE_OUTPUTS_ACCEPTED") &&
            priorNodes.any { !planNodeComplete(it.workItemId) }
        ) return "Waiting for all prior circuit stages."
        if (
            policy.entryPolicy == "ALL_PRIOR_STAGE_OUTPUTS_ACCEPTED" &&
            priorNodes.any { producer ->
                producer.produces.any { output ->
                    artifacts.none { it.producerNodeId == producer.nodeId && it.kind == output.kind }
                }
            }
        ) return "Waiting for all prior stage output artifacts."
        val incomplete = node.dependsOn.mapNotNull { dependency -> nodes.firstOrNull { it.nodeId == dependency } }
            .filterNot { planNodeComplete(it.workItemId) }
        if (incomplete.isNotEmpty()) return incomplete.joinToString(
            prefix = "Waiting for circuit nodes ",
            postfix = ".",
        ) { it.label }
        return node.consumes.filterNot { requirement ->
            artifacts.any { it.producerNodeId == requirement.producerNodeId && it.kind == requirement.kind }
        }.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = "Waiting for accepted artifacts ",
            postfix = ".",
        ) { "${it.producerNodeId}:${it.kind}" }
    }

    private fun planCoversCurrentHierarchy(plan: StagedDeliveryPlan, scope: WorkspaceEntity): Boolean =
        directPlanChildren(scope).mapTo(linkedSetOf()) { it.id } ==
            plan.stages.flatMap { it.nodes }.mapTo(linkedSetOf()) { it.workItemId }

    private fun stagedPlanArtifactInstances(plan: StagedDeliveryPlan): List<StagedPlanArtifactInstance> =
        plan.stages.flatMap { it.nodes }.flatMap { node ->
            val run = workflowRuns.lastOrNull { it.context.workItemId == node.workItemId }
                ?.takeIf { runState(it.runId) == RUN_STATE_DONE }
                ?: return@flatMap emptyList()
            val events = eventsFor(run.runId)
            val completion = events.mapNotNull { it.decision }
                .lastOrNull { it.accepted && it.toState == RUN_STATE_DONE }
                ?: return@flatMap emptyList()
            val acceptedEvidence = events.mapNotNull { it.evidence }
                .filter { it.evidenceId in completion.evidenceIds }
                .associateBy { it.kind }
            node.produces.mapNotNull { artifact ->
                val evidence = acceptedEvidence[artifact.evidenceKind] ?: return@mapNotNull null
                val evidenceHash = stagedPlanHash(
                    "${evidence.evidenceId}:${evidence.kind}:${evidence.revision}:${evidence.outputHash}:${evidence.passed}"
                )
                StagedPlanArtifactInstance(
                    producerNodeId = node.nodeId,
                    workItemId = node.workItemId,
                    kind = artifact.kind,
                    name = artifact.name,
                    workflowRunId = run.runId,
                    evidenceId = evidence.evidenceId,
                    evidenceKind = evidence.kind,
                    revision = evidence.revision,
                    outputHash = evidence.outputHash,
                    evidenceHash = evidenceHash,
                )
            }
        }

    private fun stagedPlanViews(): List<StagedDeliveryPlanView> = stagedPlans.groupBy { it.scopeId }
        .values
        .map { revisions -> revisions.maxBy { it.revision } }
        .sortedBy { it.scopeId }
        .map { plan ->
            val nodes = plan.stages.flatMap { it.nodes }
            StagedDeliveryPlanView(plan, nodes.map { node ->
                val workItem = committedEntity(node.workItemId)
                val blockReason = stagedPlanNodeBlockReason(plan, node)
                when {
                    planNodeComplete(node.workItemId) -> StagedPlanNodeView(node, PLAN_NODE_DONE)
                    planNodeStarted(node.workItemId) -> StagedPlanNodeView(node, PLAN_NODE_RUNNING)
                    blockReason != null -> StagedPlanNodeView(node, PLAN_NODE_BLOCKED_DEPENDENCY, blockReason)
                    workItem?.type in setOf(ENTITY_TASK, ENTITY_BUG) &&
                        workDefinitions.lastOrNull { it.workItemId == node.workItemId }?.assessment?.status != DEFINITION_READY ->
                        StagedPlanNodeView(node, PLAN_NODE_BLOCKED_DEFINITION, "The Work Definition is not READY.")
                    else -> StagedPlanNodeView(node, PLAN_NODE_ELIGIBLE)
                }
            }, stagedPlanArtifactInstances(plan))
        }

    private fun restoreCircuitDispatches(restored: List<CircuitDispatch>) {
        restored.forEachIndexed { index, dispatch ->
            require(dispatch.dispatchId == index + 1L) { "Circuit dispatch IDs must be contiguous" }
            require(dispatch.state == CIRCUIT_DISPATCH_PENDING) { "Unknown circuit dispatch state ${dispatch.state}" }
            require(dispatch.priority > 0) { "Circuit dispatch priority must be positive" }
            val plan = stagedPlans.singleOrNull { it.planId == dispatch.planId }
            require(plan?.revision == dispatch.planRevision && plan.hash == dispatch.planHash) {
                "Circuit dispatch references unknown plan authority"
            }
            val stage = plan.stages.singleOrNull { it.stageId == dispatch.stageId }
            val node = stage?.nodes?.singleOrNull { it.nodeId == dispatch.nodeId }
            require(node?.workItemId == dispatch.workItemId) { "Circuit dispatch references unknown node authority" }
            require(
                dispatch.hash == circuitDispatchHash(
                    dispatch.dispatchId,
                    plan,
                    requireNotNull(stage),
                    requireNotNull(node),
                    dispatch.state,
                    dispatch.createdAt,
                    dispatch.priority,
                    dispatch.integrationOwner,
                )
            ) { "Circuit dispatch hash mismatch" }
            circuitDispatches += dispatch
        }
        nextCircuitDispatchId = (circuitDispatches.maxOfOrNull { it.dispatchId } ?: 0L) + 1L
    }

    private fun reconcileCircuitDispatches() {
        stagedPlans.groupBy { it.scopeId }.values.map { revisions -> revisions.maxBy { it.revision } }
            .flatMap { plan -> plan.stages.map { stage -> plan to stage } }
            .flatMap { (plan, stage) -> stage.nodes.map { node -> Triple(plan, stage, node) } }
            .filter { (plan, _, node) ->
                val workItem = committedEntity(node.workItemId)
                workItem?.type in setOf(ENTITY_TASK, ENTITY_BUG) &&
                    stagedPlanNodeBlockReason(plan, node) == null &&
                    !planNodeStarted(node.workItemId) &&
                    workDefinitions.lastOrNull { it.workItemId == node.workItemId }?.assessment?.status == DEFINITION_READY &&
                    circuitDispatches.none { it.planId == plan.planId && it.nodeId == node.nodeId }
            }
            .forEach { (plan, stage, node) ->
                appendCircuitDispatch(plan, stage, node)
            }
    }

    private fun appendCircuitDispatch(
        plan: StagedDeliveryPlan,
        stage: StagedPlanStage,
        node: StagedPlanNode,
    ): CircuitDispatch {
        val createdAt = java.time.Instant.now().toString()
        val priority = stage.ordinal * 1_000 + stage.nodes.indexOf(node) + 1
        val integrationOwner = stage.executionWorkflowId == "integration-v1"
        val dispatch = CircuitDispatch(
            dispatchId = nextCircuitDispatchId,
            planId = plan.planId,
            planRevision = plan.revision,
            planHash = plan.hash,
            scopeId = plan.scopeId,
            stageId = stage.stageId,
            nodeId = node.nodeId,
            workItemId = node.workItemId,
            priority = priority,
            integrationOwner = integrationOwner,
            createdAt = createdAt,
            hash = circuitDispatchHash(
                nextCircuitDispatchId,
                plan,
                stage,
                node,
                CIRCUIT_DISPATCH_PENDING,
                createdAt,
                priority,
                integrationOwner,
            ),
        )
        circuitDispatchStore.append(dispatch)
        circuitDispatches += dispatch
        nextCircuitDispatchId++
        return dispatch
    }

    private fun advanceCircuitDispatches() {
        circuitDispatches.filter { dispatch ->
            dispatch.state == CIRCUIT_DISPATCH_PENDING && dispatchRun(dispatch) == null &&
                activeStagedPlan(dispatch.scopeId)?.let { it.planId == dispatch.planId && it.hash == dispatch.planHash } == true
        }.sortedWith(compareBy<CircuitDispatch> { it.priority }.thenBy { it.dispatchId }).forEach { dispatch ->
            startWorkflow(dispatch.workItemId, dispatch.dispatchId)
        }
    }

    private fun dispatchRun(dispatch: CircuitDispatch): WorkflowRun? = workflowRuns.lastOrNull {
        it.context.circuitDispatchId == dispatch.dispatchId
    }

    private fun circuitDispatchViews(): List<CircuitDispatchView> = circuitDispatches.map { dispatch ->
        val run = dispatchRun(dispatch)
        val state = run?.let {
            when (runState(it.runId)) {
                RUN_STATE_DONE -> PLAN_NODE_DONE
                RUN_STATE_CANCELLED -> RUN_STATE_CANCELLED
                else -> PLAN_NODE_RUNNING
            }
        } ?: CIRCUIT_DISPATCH_PENDING
        CircuitDispatchView(dispatch, state, run?.runId)
    }

    private fun circuitDispatchHash(
        dispatchId: Long,
        plan: StagedDeliveryPlan,
        stage: StagedPlanStage,
        node: StagedPlanNode,
        state: String,
        createdAt: String,
        priority: Int,
        integrationOwner: Boolean,
    ): String = stagedPlanHash(
        "$dispatchId:${plan.planId}:${plan.revision}:${plan.hash}:${plan.scopeId}:${stage.stageId}:${node.nodeId}:${node.workItemId}:$priority:$integrationOwner:$state:$createdAt"
    )

    private fun circuitProposalViews(): List<CircuitProposalView> = circuitProposals.map { proposal ->
        val accepted = stagedPlans.lastOrNull {
            it.sourceProposal?.proposalId == proposal.proposalId &&
                it.sourceProposal.proposalHash == proposal.hash
        }
        CircuitProposalView(
            proposal = proposal,
            acceptedPlanId = accepted?.planId,
            acceptedUnchanged = accepted?.acceptedProposalUnchanged == true,
        )
    }

    private fun circuitProposalFailure(status: CircuitProposalStatus): CircuitProposalResult =
        CircuitProposalResult(status, snapshot(MESSAGE_STAGED_DELIVERY_PLAN))

    private fun stagedPlanFailure(status: StagedPlanStatus, message: String): StagedPlanResult {
        stagedPlanMessage = message
        return StagedPlanResult(status, snapshot(MESSAGE_STAGED_DELIVERY_PLAN))
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
        MESSAGE_WORK_DEFINITION -> workflowMessage
        MESSAGE_DEFINITION_COLLABORATION -> workflowMessage
        MESSAGE_STAGED_DELIVERY_PLAN -> stagedPlanMessage
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
        const val MAX_PLAN_TEXT = 256
        const val MAX_PLAN_STAGES = 16
        const val MAX_PLAN_NODES = 26
        const val MAX_PLAN_EDGES_PER_NODE = 26
        const val MAX_PLAN_ARTIFACTS_PER_NODE = 16
        val PLAN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val GIT_HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val TERMINAL_RUN_STATES = setOf(RUN_STATE_DONE, RUN_STATE_CANCELLED)
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class StagedPlanHashSource(
    val planId: Long,
    val revision: Int,
    val scopeId: Int,
    val scopeType: Int,
    val title: String,
    val stages: List<StagedPlanStage>,
    val acceptedBy: String,
    val acceptedAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sourceProposal: CircuitProposalReference? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptedProposalUnchanged: Boolean = false,
)