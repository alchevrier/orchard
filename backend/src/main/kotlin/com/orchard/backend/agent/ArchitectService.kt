package com.orchard.backend.agent

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.MESSAGE_BATCH_CREATED
import com.orchard.backend.workspace.MESSAGE_BUSY
import com.orchard.backend.workspace.MESSAGE_CLARIFY
import com.orchard.backend.workspace.MESSAGE_CREATED
import com.orchard.backend.workspace.MESSAGE_OLLAMA_UNAVAILABLE
import com.orchard.backend.workspace.MESSAGE_REJECTED
import com.orchard.backend.workspace.MESSAGE_UNSUPPORTED_ACTION
import com.orchard.backend.workspace.MESSAGE_WORKFLOW_HIERARCHY
import com.orchard.backend.workspace.MESSAGE_WORKFLOW_PARENT_REQUIRED
import com.orchard.backend.workspace.WORKFLOW_HIERARCHY_MISMATCH
import com.orchard.backend.workspace.WORKFLOW_PARENT_NOT_FOUND
import com.orchard.backend.workspace.WORKFLOW_PARENT_REQUIRED
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class ArchitectChatRequest(val prompt: String)

data class ArchitectResult(val statusCode: Int, val snapshot: WorkspaceSnapshot)

class ArchitectService(
    private val workspace: WorkspaceStore,
    private val modelProvider: ModelProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val requestMutex = Mutex()

    suspend fun submit(request: ArchitectChatRequest): ArchitectResult {
        if (request.prompt.encodeToByteArray().size > MAX_PROMPT_BYTES) {
            return ArchitectResult(400, workspace.snapshot(MESSAGE_REJECTED))
        }
        if (!requestMutex.tryLock()) return ArchitectResult(409, workspace.snapshot(MESSAGE_BUSY))
        return try {
            process(request.prompt)
        } finally {
            requestMutex.unlock()
        }
    }

    private suspend fun process(prompt: String): ArchitectResult {
        var actionType = classifyAction(prompt)
        var entityType = classifyEntityType(prompt, actionType)
        val triageJson = try {
            modelProvider.triage(prompt)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Ollama triage request failed", exception)
            return ArchitectResult(503, workspace.snapshot(MESSAGE_OLLAMA_UNAVAILABLE))
        }
        val triage = try {
            json.decodeFromString<TriageResult>(triageJson)
        } catch (exception: Exception) {
            logger.warn("Ollama returned invalid triage JSON: {}", triageJson.take(512), exception)
            return ArchitectResult(422, workspace.snapshot(MESSAGE_CLARIFY))
        }
        val intentCount = if (triage.isBatch == 1) 2 else triage.intentCount.coerceIn(1, MAX_PLAN_OPERATIONS)
        if (actionType == ACTION_UNKNOWN && triage.actionTypeId in ACTION_CREATE..ACTION_QUERY) actionType = triage.actionTypeId
        if (intentCount > 1) {
            entityType = 0
        } else if (entityType == 0 && triage.entityTypeId in 1..5) {
            entityType = triage.entityTypeId
        }
        if (actionType == ACTION_UNKNOWN || (intentCount == 1 && entityType == 0)) {
            return ArchitectResult(422, workspace.snapshot(MESSAGE_CLARIFY))
        }
        if (actionType != ACTION_CREATE) {
            return ArchitectResult(422, workspace.snapshot(MESSAGE_UNSUPPORTED_ACTION))
        }

        val planJson = try {
            modelProvider.plan(prompt, actionType, entityType, workspace)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warn("Ollama planning request failed", exception)
            return ArchitectResult(503, workspace.snapshot(MESSAGE_OLLAMA_UNAVAILABLE))
        }
        val decodedPlan = try {
            json.decodeFromString<ArchitectPlan>(planJson)
        } catch (exception: Exception) {
            logger.warn("Ollama returned invalid planning JSON: {}", planJson.take(512), exception)
            return ArchitectResult(422, workspace.snapshot(MESSAGE_CLARIFY))
        }
        val plan = if (intentCount == 1) {
            val matchingOperation = decodedPlan.operations.firstOrNull { operation ->
                (operation.actionTypeId ?: symbolicActionType(operation.action)) == actionType &&
                    (operation.entityTypeId ?: symbolicEntityType(operation.entity)) == entityType
            } ?: return ArchitectResult(422, workspace.snapshot(MESSAGE_CLARIFY))
            ArchitectPlan(listOf(matchingOperation))
        } else {
            decodedPlan
        }
        if (plan.operations.isEmpty() || plan.operations.size > MAX_PLAN_OPERATIONS) {
            return ArchitectResult(422, workspace.snapshot(MESSAGE_CLARIFY))
        }
        return applyPlan(plan, prompt, entityType)
    }

    private fun applyPlan(plan: ArchitectPlan, prompt: String, explicitEntityType: Int): ArchitectResult {
        val createdIds = IntArray(plan.operations.size)
        val createdTypes = IntArray(plan.operations.size)
        val promptTitle = explicitTitle(prompt)
        val promptContent = labeledValue(prompt, "description:", "details:", consumeRemainder = true)
        val pendingProjectId = explicitReferenceId(prompt, "project")
        val pendingEpicId = explicitReferenceId(prompt, "epic")
        val pendingStoryId = explicitReferenceId(prompt, "story")
        var batchProjectId = 0
        var batchEpicId = 0
        var batchStoryId = 0
        var createdCount = 0

        workspace.beginBatch()
        plan.operations.forEachIndexed { index, operation ->
            val actionType = operation.actionTypeId ?: symbolicActionType(operation.action)
            if (actionType != ACTION_CREATE) return reject(index, REJECTION_ACTION)
            val plannedEntityType = operation.entityTypeId ?: symbolicEntityType(operation.entity)
            val entityType = if (plan.operations.size == 1 && explicitEntityType != 0) explicitEntityType else plannedEntityType
            if (entityType !in 1..5) return reject(index, REJECTION_ENTITY)
            val title = if (plan.operations.size == 1 && promptTitle != null) promptTitle else operation.title
            if (title.isBlank()) {
                if (entityType != 2 || batchProjectId == 0 || batchEpicId != 0 || !workspace.createDefaultEpic(batchProjectId)) {
                    return reject(index, REJECTION_TITLE)
                }
                batchEpicId = workspace.lastCreatedId
                createdIds[index] = batchEpicId
                createdTypes[index] = 2
                createdCount++
                return@forEachIndexed
            }
            if (entityType == 2 && batchEpicId != 0 && batchStoryId != 0 && title.equals("General", true)) {
                createdIds[index] = batchEpicId
                createdTypes[index] = 2
                return@forEachIndexed
            }

            val parentOperationIndex = operation.parentOperationIndex
            fun operationParent(expectedType: Int): Int {
                if (parentOperationIndex == null || parentOperationIndex == -1) return 0
                if (parentOperationIndex !in 0 until index || createdTypes[parentOperationIndex] != expectedType) return INVALID_PARENT
                return createdIds[parentOperationIndex]
            }

            var projectId = 0
            var epicId = 0
            var storyId = 0
            when (entityType) {
                2 -> {
                    projectId = operationParent(1)
                    if (projectId == INVALID_PARENT) return reject(index, REJECTION_PARENT)
                    if (projectId == 0) projectId = batchProjectId.takeIf { it != 0 } ?: pendingProjectId
                }
                3 -> {
                    epicId = operationParent(2)
                    if (epicId == INVALID_PARENT) return reject(index, REJECTION_PARENT)
                    if (epicId == 0) epicId = batchEpicId.takeIf { it != 0 } ?: pendingEpicId
                    if (epicId == 0 && batchProjectId != 0) {
                        if (!workspace.createDefaultEpic(batchProjectId)) return reject(index, REJECTION_WORKFLOW)
                        batchEpicId = workspace.lastCreatedId
                        epicId = batchEpicId
                        createdCount++
                    }
                }
                4, 5 -> {
                    storyId = operationParent(3)
                    if (storyId == INVALID_PARENT) return reject(index, REJECTION_PARENT)
                    if (storyId == 0) storyId = batchStoryId.takeIf { it != 0 } ?: pendingStoryId
                }
            }
            if (entityType != 1 && projectId == 0 && epicId == 0 && storyId == 0) return reject(index, REJECTION_PARENT)

            val accepted = workspace.applyIntent(
                DocumentIntent(
                    actionTypeId = actionType,
                    entityTypeId = entityType,
                    boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
                    projectId = projectId,
                    epicId = epicId,
                    storyId = storyId,
                    title = title,
                    content = if (plan.operations.size == 1) promptContent ?: operation.content else operation.content,
                )
            )
            if (!accepted) return reject(index, REJECTION_WORKFLOW)
            createdIds[index] = workspace.lastCreatedId
            createdTypes[index] = entityType
            when (entityType) {
                1 -> batchProjectId = workspace.lastCreatedId
                2 -> batchEpicId = workspace.lastCreatedId
                3 -> batchStoryId = workspace.lastCreatedId
            }
            createdCount++
        }

        workspace.markBatchCreated(createdCount)
        val message = if (plan.operations.size > 1) MESSAGE_BATCH_CREATED else MESSAGE_CREATED
        return ArchitectResult(200, workspace.snapshot(message))
    }

    private fun reject(operationIndex: Int, reason: Int): ArchitectResult {
        workspace.rollbackBatch()
        workspace.markPlanRejected(operationIndex + 1, reason)
        val message = when (workspace.lastWorkflowResult) {
            WORKFLOW_PARENT_REQUIRED -> MESSAGE_WORKFLOW_PARENT_REQUIRED
            WORKFLOW_PARENT_NOT_FOUND, WORKFLOW_HIERARCHY_MISMATCH -> MESSAGE_WORKFLOW_HIERARCHY
            else -> MESSAGE_REJECTED
        }
        return ArchitectResult(422, workspace.snapshot(message))
    }

    private fun classifyAction(prompt: String): Int = nearestWord(
        prompt,
        listOf(
            ACTION_CREATE to listOf("create", "add", "make", "build", "new", "need", "want"),
            ACTION_UPDATE to listOf("update", "modify", "change", "rename", "move"),
            ACTION_DELETE to listOf("delete", "remove", "archive"),
            ACTION_QUERY to listOf("show", "list", "find"),
        ),
    )

    private fun classifyEntityType(prompt: String, actionType: Int): Int {
        val labeled = Regex("(?i)(?:type|entity)\\s*:\\s*(project|epic|story|task|bug|defect)").find(prompt)
        if (labeled != null) return symbolicEntityType(labeled.groupValues[1])
        val actionWords = when (actionType) {
            ACTION_UPDATE -> listOf("update", "modify", "change", "rename", "move")
            ACTION_DELETE -> listOf("delete", "remove", "archive")
            ACTION_QUERY -> listOf("show", "list", "find")
            else -> listOf("create", "add", "make", "build", "new", "need", "want")
        }
        val action = actionWords.mapNotNull { word -> wordIndex(prompt, word).takeIf { it >= 0 } }.minOrNull() ?: return 0
        return nearestWord(
            prompt.substring(action),
            listOf(1 to listOf("project"), 2 to listOf("epic"), 3 to listOf("story"), 4 to listOf("task"), 5 to listOf("bug", "defect")),
        )
    }

    private fun nearestWord(prompt: String, groups: List<Pair<Int, List<String>>>): Int = groups
        .flatMap { (type, words) -> words.map { type to wordIndex(prompt, it) } }
        .filter { it.second >= 0 }
        .minByOrNull { it.second }
        ?.first ?: 0

    private fun wordIndex(value: String, word: String): Int = Regex("(?i)\\b${Regex.escape(word)}\\b").find(value)?.range?.first ?: -1

    private fun explicitTitle(prompt: String): String? {
        Regex("(?i)\\b(?:named|called)\\s+(.+?)(?=\\s+in\\s+(?:project|epic|story)\\b|[\\r\\n]|$)")
            .find(prompt)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let { return it }
        return labeledValue(prompt, "name:", "title:")
    }

    private fun labeledValue(prompt: String, primary: String, secondary: String, consumeRemainder: Boolean = false): String? {
        val marker = listOf(primary, secondary).mapNotNull { label ->
            prompt.indexOf(label, ignoreCase = true).takeIf { it >= 0 }?.let { it to label.length }
        }.minByOrNull { it.first } ?: return null
        val remainder = prompt.substring(marker.first + marker.second).trimStart(' ', '\t')
        val value = if (consumeRemainder) remainder else remainder.lineSequence().firstOrNull().orEmpty()
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun explicitReferenceId(prompt: String, entity: String): Int =
        Regex("(?i)\\b${Regex.escape(entity)}\\s*(?:id)?\\s*[:#=\\-]?\\s*(\\d+)")
            .find(prompt)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun symbolicActionType(action: String?): Int = when (action?.uppercase()) {
        "CREATE" -> ACTION_CREATE
        "UPDATE" -> ACTION_UPDATE
        "DELETE" -> ACTION_DELETE
        "QUERY" -> ACTION_QUERY
        else -> ACTION_UNKNOWN
    }

    private fun symbolicEntityType(entity: String?): Int = when (entity?.uppercase()) {
        "PROJECT" -> 1
        "EPIC" -> 2
        "STORY" -> 3
        "TASK" -> 4
        "BUG", "DEFECT" -> 5
        else -> 0
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ArchitectService::class.java)
        const val MAX_PROMPT_BYTES = 4092
        const val MAX_PLAN_OPERATIONS = 8
        const val ACTION_UNKNOWN = 0
        const val ACTION_UPDATE = 2
        const val ACTION_DELETE = 3
        const val ACTION_QUERY = 4
        const val INVALID_PARENT = -1
        const val REJECTION_ACTION = 1
        const val REJECTION_ENTITY = 2
        const val REJECTION_TITLE = 3
        const val REJECTION_PARENT = 4
        const val REJECTION_WORKFLOW = 6
    }
}

@Serializable
private data class TriageResult(
    val actionTypeId: Int = 0,
    val entityTypeId: Int = 0,
    val intentCount: Int = 1,
    val isBatch: Int = 0,
)

@Serializable
private data class ArchitectPlan(val operations: List<PlannedOperation> = emptyList())

@Serializable
private data class PlannedOperation(
    val actionTypeId: Int? = null,
    val entityTypeId: Int? = null,
    val action: String? = null,
    val entity: String? = null,
    val title: String = "",
    val content: String = "",
    val parentOperationIndex: Int? = null,
)