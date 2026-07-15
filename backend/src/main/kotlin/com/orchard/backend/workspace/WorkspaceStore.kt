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

@Serializable
data class WorkspaceResource(
    val type: String,
    val path: String,
    val action: String,
)

@Serializable
data class WorkspaceSnapshot(val resources: Map<String, WorkspaceResource>)

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

    init {
        restore(repository.load())
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
    fun snapshot(messageCode: Int): WorkspaceSnapshot {
        val resources = linkedMapOf<String, WorkspaceResource>()
        resources["focus"] = WorkspaceResource(
            "FOCUS",
            committedLastCreatedId.toString(),
            committedLastCreatedType.toString(),
        )
        resources["message"] = WorkspaceResource("MESSAGE", message(messageCode), "none")
        entities.take(committedEntityCount).forEach { entity ->
            resources["entity-${entity.id}"] = WorkspaceResource(
                type = typeLabel(entity.type),
                path = entity.title,
                action = "id=${entity.id};parent=${entity.parentId};status=${entity.status}",
            )
        }
        return WorkspaceSnapshot(resources)
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
    }
}