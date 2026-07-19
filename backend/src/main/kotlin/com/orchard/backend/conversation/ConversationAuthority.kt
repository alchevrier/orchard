package com.orchard.backend.conversation

import com.orchard.backend.workspace.loadRecoverableJsonl
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MESSAGE_ROLE_USER = "USER"
const val MESSAGE_ROLE_ASSISTANT = "ASSISTANT"
const val MESSAGE_ROLE_SYSTEM = "SYSTEM"

const val OBJECTIVE_CANDIDATE = "CANDIDATE"
const val OBJECTIVE_AWAITING_ADMISSION = "AWAITING_ADMISSION"
const val OBJECTIVE_READY = "READY"
const val OBJECTIVE_ACTIVE = "ACTIVE"
const val OBJECTIVE_PAUSED = "PAUSED"
const val OBJECTIVE_BLOCKED = "BLOCKED"
const val OBJECTIVE_COMPLETED = "COMPLETED"
const val OBJECTIVE_CANCELLED = "CANCELLED"
const val OBJECTIVE_SUPERSEDED = "SUPERSEDED"

const val COMMAND_PROPOSED = "PROPOSED"
const val COMMAND_ADMITTED = "ADMITTED"
const val COMMAND_DISPATCHED = "DISPATCHED"
const val COMMAND_CORRELATED = "CORRELATED"
const val COMMAND_FAILED = "FAILED"
const val COMMAND_REJECTED = "REJECTED"

const val ACTIVITY_INFO = "INFO"
const val ACTIVITY_ATTENTION = "ATTENTION"
const val ACTIVITY_TERMINAL = "TERMINAL"

@Serializable
data class Conversation(
    val conversationId: Long,
    val title: String,
    val actor: String,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class ConversationMessage(
    val messageId: Long,
    val conversationId: Long,
    val sequence: Long,
    val clientMessageId: String,
    val role: String,
    val content: String,
    val objectiveId: Long? = null,
    val replyToMessageId: Long? = null,
    val actor: String,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class ConversationObjectiveRevision(
    val objectiveId: Long,
    val revision: Int,
    val conversationId: Long,
    val projectId: Int? = null,
    val title: String,
    val outcome: String,
    val constraints: List<String> = emptyList(),
    val priority: Int = 50,
    val dependencyObjectiveIds: List<Long> = emptyList(),
    val state: String,
    val sourceMessageId: Long,
    val sourceMessageHash: String,
    val actor: String,
    val createdAt: String,
    val previousHash: String? = null,
    val hash: String,
)

@Serializable
data class ConversationCommandProposal(
    val commandId: Long,
    val conversationId: Long,
    val objectiveId: Long? = null,
    val sourceMessageId: Long,
    val sourceMessageHash: String,
    val capabilityId: String,
    val payloadJson: String,
    val mutation: Boolean,
    val proposedAt: String,
    val hash: String,
)

@Serializable
data class ConversationCommandAdmission(
    val admissionId: Long,
    val commandId: Long,
    val commandHash: String,
    val actor: String,
    val admittedAt: String,
    val hash: String,
)

@Serializable
data class ConversationCommandExecution(
    val executionId: Long,
    val commandId: Long,
    val commandHash: String,
    val state: String,
    val downstreamType: String? = null,
    val downstreamId: String? = null,
    val downstreamHash: String? = null,
    val repositoryRevision: String? = null,
    val diagnostic: String = "",
    val recordedAt: String,
    val hash: String,
)

@Serializable
data class ConversationSummaryRevision(
    val summaryId: Long,
    val revision: Int,
    val conversationId: Long,
    val objectiveId: Long? = null,
    val content: String,
    val sourceMessageIds: List<Long>,
    val sourceAuthorityHashes: List<String> = emptyList(),
    val createdAt: String,
    val previousHash: String? = null,
    val hash: String,
)

@Serializable
data class ConversationModelProvenance(
    val executionProfileId: String,
    val executionProfileVersion: Int,
    val providerFingerprint: String,
    val bindingId: String,
    val provider: String,
    val model: String,
    val modelDigest: String? = null,
    val configurationHash: String,
    val promptHash: String,
    val outputHash: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMillis: Long,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val resourceDecision: String,
)

@Serializable
data class ConversationActivity(
    val activityId: Long,
    val conversationId: Long,
    val objectiveId: Long? = null,
    val kind: String,
    val summary: String,
    val authorityType: String? = null,
    val authorityId: String? = null,
    val authorityHash: String? = null,
    val recordedAt: String,
    val hash: String,
    val modelProvenance: ConversationModelProvenance? = null,
)

@Serializable
data class ConversationEvent(
    val version: Int = 1,
    val eventId: Long,
    val conversation: Conversation? = null,
    val message: ConversationMessage? = null,
    val objective: ConversationObjectiveRevision? = null,
    val command: ConversationCommandProposal? = null,
    val admission: ConversationCommandAdmission? = null,
    val execution: ConversationCommandExecution? = null,
    val summary: ConversationSummaryRevision? = null,
    val activity: ConversationActivity? = null,
    val checksum: String,
)

interface ConversationStore {
    fun events(): List<ConversationEvent>
    fun appendConversation(conversation: Conversation)
    fun appendMessage(message: ConversationMessage)
    fun appendObjective(objective: ConversationObjectiveRevision)
    fun appendCommand(command: ConversationCommandProposal)
    fun appendAdmission(admission: ConversationCommandAdmission)
    fun appendExecution(execution: ConversationCommandExecution)
    fun appendSummary(summary: ConversationSummaryRevision)
    fun appendActivity(activity: ConversationActivity)
}

class TransientConversationStore : ConversationStore {
    private val records = mutableListOf<ConversationEvent>()

    override fun events() = records.toList()
    override fun appendConversation(conversation: Conversation) = append(conversation = conversation)
    override fun appendMessage(message: ConversationMessage) = append(message = message)
    override fun appendObjective(objective: ConversationObjectiveRevision) = append(objective = objective)
    override fun appendCommand(command: ConversationCommandProposal) = append(command = command)
    override fun appendAdmission(admission: ConversationCommandAdmission) = append(admission = admission)
    override fun appendExecution(execution: ConversationCommandExecution) = append(execution = execution)
    override fun appendSummary(summary: ConversationSummaryRevision) = append(summary = summary)
    override fun appendActivity(activity: ConversationActivity) = append(activity = activity)

    private fun append(
        conversation: Conversation? = null,
        message: ConversationMessage? = null,
        objective: ConversationObjectiveRevision? = null,
        command: ConversationCommandProposal? = null,
        admission: ConversationCommandAdmission? = null,
        execution: ConversationCommandExecution? = null,
        summary: ConversationSummaryRevision? = null,
        activity: ConversationActivity? = null,
    ) {
        val event = newConversationEvent(
            records.size.toLong() + 1L, conversation, message, objective, command, admission, execution, summary, activity,
        )
        validateConversationEvent(records, event)
        records += event
    }
}

class FileConversationStore(private val directory: Path) : ConversationStore {
    private val path = directory.resolve("conversations.jsonl")
    private val lockPath = directory.resolve("conversations.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized override fun events() = load()
    @Synchronized override fun appendConversation(conversation: Conversation) = append(conversation = conversation)
    @Synchronized override fun appendMessage(message: ConversationMessage) = append(message = message)
    @Synchronized override fun appendObjective(objective: ConversationObjectiveRevision) = append(objective = objective)
    @Synchronized override fun appendCommand(command: ConversationCommandProposal) = append(command = command)
    @Synchronized override fun appendAdmission(admission: ConversationCommandAdmission) = append(admission = admission)
    @Synchronized override fun appendExecution(execution: ConversationCommandExecution) = append(execution = execution)
    @Synchronized override fun appendSummary(summary: ConversationSummaryRevision) = append(summary = summary)
    @Synchronized override fun appendActivity(activity: ConversationActivity) = append(activity = activity)

    private fun append(
        conversation: Conversation? = null,
        message: ConversationMessage? = null,
        objective: ConversationObjectiveRevision? = null,
        command: ConversationCommandProposal? = null,
        admission: ConversationCommandAdmission? = null,
        execution: ConversationCommandExecution? = null,
        summary: ConversationSummaryRevision? = null,
        activity: ConversationActivity? = null,
    ) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val existing = load()
                val event = newConversationEvent(
                    existing.size.toLong() + 1L, conversation, message, objective, command, admission, execution, summary, activity,
                )
                validateConversationEvent(existing, event)
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap((json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
    }

    private fun load(): List<ConversationEvent> = loadRecoverableJsonl(path, "conversation authority") { line, _ ->
        json.decodeFromString<ConversationEvent>(line)
    }.also { events ->
        val accepted = mutableListOf<ConversationEvent>()
        events.forEach { event -> validateConversationEvent(accepted, event); accepted += event }
    }
}

fun newConversation(conversation: Conversation): Conversation =
    conversation.copy(hash = conversationHash(conversation.copy(hash = "")))

fun newConversationMessage(message: ConversationMessage): ConversationMessage =
    message.copy(hash = conversationHash(message.copy(hash = "")))

fun newConversationObjective(objective: ConversationObjectiveRevision): ConversationObjectiveRevision =
    objective.copy(hash = conversationHash(objective.copy(hash = "")))

fun newConversationCommand(command: ConversationCommandProposal): ConversationCommandProposal =
    command.copy(hash = conversationHash(command.copy(hash = "")))

fun newConversationAdmission(admission: ConversationCommandAdmission): ConversationCommandAdmission =
    admission.copy(hash = conversationHash(admission.copy(hash = "")))

fun newConversationExecution(execution: ConversationCommandExecution): ConversationCommandExecution =
    execution.copy(hash = conversationHash(execution.copy(hash = "")))

fun newConversationSummary(summary: ConversationSummaryRevision): ConversationSummaryRevision =
    summary.copy(hash = conversationHash(summary.copy(hash = "")))

fun newConversationActivity(activity: ConversationActivity): ConversationActivity =
    activity.copy(hash = conversationHash(activity.copy(hash = "")))

private fun newConversationEvent(
    eventId: Long,
    conversation: Conversation?,
    message: ConversationMessage?,
    objective: ConversationObjectiveRevision?,
    command: ConversationCommandProposal?,
    admission: ConversationCommandAdmission?,
    execution: ConversationCommandExecution?,
    summary: ConversationSummaryRevision?,
    activity: ConversationActivity?,
): ConversationEvent {
    val event = ConversationEvent(
        eventId = eventId,
        conversation = conversation,
        message = message,
        objective = objective,
        command = command,
        admission = admission,
        execution = execution,
        summary = summary,
        activity = activity,
        checksum = "",
    )
    return event.copy(checksum = conversationHash(event))
}

private fun validateConversationEvent(existing: List<ConversationEvent>, event: ConversationEvent) {
    require(event.version == 1 && event.eventId == existing.size.toLong() + 1L) { "Conversation event sequence is invalid" }
    require(listOfNotNull(
        event.conversation, event.message, event.objective, event.command, event.admission, event.execution, event.summary, event.activity,
    ).size == 1) { "Conversation event shape is invalid" }
    require(event.checksum == conversationHash(event.copy(checksum = ""))) { "Conversation event checksum is invalid" }

    val conversations = existing.mapNotNull { it.conversation }
    val messages = existing.mapNotNull { it.message }
    val objectives = existing.mapNotNull { it.objective }
    val commands = existing.mapNotNull { it.command }
    val admissions = existing.mapNotNull { it.admission }
    val executions = existing.mapNotNull { it.execution }
    val summaries = existing.mapNotNull { it.summary }
    val activities = existing.mapNotNull { it.activity }

    event.conversation?.let { value ->
        require(value.conversationId > 0 && value.title.isNotBlank() && value.actor.isNotBlank() && value.createdAt.isNotBlank()) {
            "Conversation identity is invalid"
        }
        require(conversations.none { it.conversationId == value.conversationId || it.hash == value.hash }) { "Conversation already exists" }
        require(value.hash == newConversation(value.copy(hash = "")).hash) { "Conversation hash is invalid" }
    }
    event.message?.let { value ->
        val conversation = conversations.singleOrNull { it.conversationId == value.conversationId }
        require(conversation != null && value.messageId > 0 && value.sequence > 0 && value.clientMessageId.matches(CLIENT_KEY) &&
            value.role in MESSAGE_ROLES && value.content.isNotBlank() && value.content.encodeToByteArray().size <= MAX_MESSAGE_BYTES &&
            value.actor.isNotBlank() && value.createdAt.isNotBlank()) { "Conversation message is invalid" }
        require(value.sequence == messages.count { it.conversationId == value.conversationId } + 1L) { "Conversation message sequence is invalid" }
        require(messages.none { it.messageId == value.messageId ||
            (it.conversationId == value.conversationId && it.clientMessageId == value.clientMessageId) }) { "Conversation message already exists" }
        value.replyToMessageId?.let { replyId -> require(messages.any { it.conversationId == value.conversationId && it.messageId == replyId }) }
        value.objectiveId?.let { objectiveId -> require(objectives.any { it.conversationId == value.conversationId && it.objectiveId == objectiveId }) }
        require(value.hash == newConversationMessage(value.copy(hash = "")).hash) { "Conversation message hash is invalid" }
    }
    event.objective?.let { value ->
        val source = messages.singleOrNull { it.messageId == value.sourceMessageId && it.conversationId == value.conversationId }
        val prior = objectives.filter { it.objectiveId == value.objectiveId }.maxByOrNull { it.revision }
        require(conversations.any { it.conversationId == value.conversationId } && source?.hash == value.sourceMessageHash &&
            value.objectiveId > 0 && value.revision == (prior?.revision ?: 0) + 1 && value.previousHash == prior?.hash &&
            value.title.isNotBlank() && value.outcome.isNotBlank() && value.constraints.all(String::isNotBlank) &&
            value.priority in 0..100 && value.state in OBJECTIVE_STATES && value.actor.isNotBlank() && value.createdAt.isNotBlank()) {
            "Conversation objective revision is invalid"
        }
        require(value.dependencyObjectiveIds.distinct().size == value.dependencyObjectiveIds.size && value.objectiveId !in value.dependencyObjectiveIds) {
            "Conversation objective dependencies are invalid"
        }
        require(value.dependencyObjectiveIds.all { dependency -> objectives.any { it.conversationId == value.conversationId && it.objectiveId == dependency } }) {
            "Conversation objective dependency is unavailable"
        }
        val latest = (objectives + value).groupBy { it.objectiveId }.mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
        require(!hasDependencyCycle(latest)) { "Conversation objective dependency cycle is invalid" }
        require(value.hash == newConversationObjective(value.copy(hash = "")).hash) { "Conversation objective hash is invalid" }
    }
    event.command?.let { value ->
        val source = messages.singleOrNull { it.messageId == value.sourceMessageId && it.conversationId == value.conversationId }
        require(conversations.any { it.conversationId == value.conversationId } && source?.hash == value.sourceMessageHash &&
            value.commandId > 0 && value.capabilityId.matches(CAPABILITY_ID) && value.payloadJson.isNotBlank() && value.proposedAt.isNotBlank()) {
            "Conversation command is invalid"
        }
        value.objectiveId?.let { objectiveId -> require(objectives.any { it.conversationId == value.conversationId && it.objectiveId == objectiveId }) }
        require(commands.none { it.commandId == value.commandId || it.hash == value.hash }) { "Conversation command already exists" }
        require(value.hash == newConversationCommand(value.copy(hash = "")).hash) { "Conversation command hash is invalid" }
    }
    event.admission?.let { value ->
        val command = commands.singleOrNull { it.commandId == value.commandId }
        require(command?.hash == value.commandHash && command.mutation && value.admissionId > 0 && value.actor.isNotBlank() && value.admittedAt.isNotBlank()) {
            "Conversation command admission is invalid"
        }
        require(admissions.none { it.admissionId == value.admissionId || it.commandId == value.commandId }) { "Conversation command already admitted" }
        require(value.hash == newConversationAdmission(value.copy(hash = "")).hash) { "Conversation admission hash is invalid" }
    }
    event.execution?.let { value ->
        val command = commands.singleOrNull { it.commandId == value.commandId }
        val prior = executions.filter { it.commandId == value.commandId }.maxByOrNull { it.executionId }
        require(command?.hash == value.commandHash && value.executionId > (prior?.executionId ?: 0L) && value.state in COMMAND_STATES &&
            value.recordedAt.isNotBlank() && (value.state != COMMAND_CORRELATED ||
                !value.downstreamType.isNullOrBlank() && !value.downstreamId.isNullOrBlank() && value.downstreamHash?.matches(SHA256) == true)) {
            "Conversation command execution is invalid"
        }
        require(executions.none { it.executionId == value.executionId }) { "Conversation execution already exists" }
        require(value.hash == newConversationExecution(value.copy(hash = "")).hash) { "Conversation execution hash is invalid" }
    }
    event.summary?.let { value ->
        val prior = summaries.filter { it.summaryId == value.summaryId }.maxByOrNull { it.revision }
        require(conversations.any { it.conversationId == value.conversationId } && value.summaryId > 0 &&
            value.revision == (prior?.revision ?: 0) + 1 && value.previousHash == prior?.hash && value.content.isNotBlank() &&
            value.sourceMessageIds.isNotEmpty() && value.sourceMessageIds.distinct().size == value.sourceMessageIds.size &&
            value.sourceMessageIds.all { id -> messages.any { it.conversationId == value.conversationId && it.messageId == id } } &&
            value.sourceAuthorityHashes.all(SHA256::matches) && value.createdAt.isNotBlank()) { "Conversation summary is invalid" }
        value.objectiveId?.let { objectiveId -> require(objectives.any { it.conversationId == value.conversationId && it.objectiveId == objectiveId }) }
        require(value.hash == newConversationSummary(value.copy(hash = "")).hash) { "Conversation summary hash is invalid" }
    }
    event.activity?.let { value ->
        require(conversations.any { it.conversationId == value.conversationId } && value.activityId > 0 && value.kind in ACTIVITY_KINDS &&
            value.summary.isNotBlank() && value.recordedAt.isNotBlank() &&
            (value.authorityHash == null || value.authorityHash.matches(SHA256))) { "Conversation activity is invalid" }
        value.modelProvenance?.let { provenance ->
            require(provenance.executionProfileId.isNotBlank() && provenance.executionProfileVersion > 0 &&
                provenance.providerFingerprint.matches(SHA256) && provenance.bindingId.isNotBlank() &&
                provenance.provider.isNotBlank() && provenance.model.isNotBlank() && provenance.configurationHash.matches(SHA256) &&
                provenance.promptHash.matches(SHA256) && provenance.outputHash.matches(SHA256) &&
                provenance.promptTokens in 0..provenance.inputBudgetTokens &&
                provenance.completionTokens in 0..provenance.outputBudgetTokens && provenance.latencyMillis >= 0 &&
                provenance.resourceDecision.isNotBlank()) { "Conversation model provenance is invalid" }
        }
        value.objectiveId?.let { objectiveId -> require(objectives.any { it.conversationId == value.conversationId && it.objectiveId == objectiveId }) }
        require(activities.none { it.activityId == value.activityId || it.hash == value.hash }) { "Conversation activity already exists" }
        require(value.hash == newConversationActivity(value.copy(hash = "")).hash) { "Conversation activity hash is invalid" }
    }
}

private fun hasDependencyCycle(latest: Map<Long, ConversationObjectiveRevision>): Boolean {
    val visiting = mutableSetOf<Long>()
    val visited = mutableSetOf<Long>()
    fun visit(id: Long): Boolean {
        if (id in visiting) return true
        if (!visited.add(id)) return false
        visiting += id
        val cyclic = latest[id]?.dependencyObjectiveIds.orEmpty().any(::visit)
        visiting -= id
        return cyclic
    }
    return latest.keys.any(::visit)
}

private fun conversationHash(value: Any): String {
    val serialized = when (value) {
        is Conversation -> Json.encodeToString(value)
        is ConversationMessage -> Json.encodeToString(value)
        is ConversationObjectiveRevision -> Json.encodeToString(value)
        is ConversationCommandProposal -> Json.encodeToString(value)
        is ConversationCommandAdmission -> Json.encodeToString(value)
        is ConversationCommandExecution -> Json.encodeToString(value)
        is ConversationSummaryRevision -> Json.encodeToString(value)
        is ConversationActivity -> Json.encodeToString(value)
        is ConversationEvent -> Json.encodeToString(value)
        else -> error("Unsupported conversation hash payload")
    }
    return MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private val CLIENT_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
private val CAPABILITY_ID = Regex("[A-Z][A-Z0-9_]{2,63}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val MESSAGE_ROLES = setOf(MESSAGE_ROLE_USER, MESSAGE_ROLE_ASSISTANT, MESSAGE_ROLE_SYSTEM)
private val OBJECTIVE_STATES = setOf(
    OBJECTIVE_CANDIDATE, OBJECTIVE_AWAITING_ADMISSION, OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_PAUSED,
    OBJECTIVE_BLOCKED, OBJECTIVE_COMPLETED, OBJECTIVE_CANCELLED, OBJECTIVE_SUPERSEDED,
)
private val COMMAND_STATES = setOf(COMMAND_PROPOSED, COMMAND_ADMITTED, COMMAND_DISPATCHED, COMMAND_CORRELATED, COMMAND_FAILED, COMMAND_REJECTED)
private val ACTIVITY_KINDS = setOf(ACTIVITY_INFO, ACTIVITY_ATTENTION, ACTIVITY_TERMINAL)
private const val MAX_MESSAGE_BYTES = 65_536