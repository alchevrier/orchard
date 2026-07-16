package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val COLLABORATOR_HUMAN = "HUMAN"
const val COLLABORATOR_LOCAL_LLM = "LOCAL_LLM"

@Serializable
data class DefinitionProposalContent(
    val definition: WorkDefinitionSubmission,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

@Serializable
data class DefinitionExecutionProvenance(
    val executor: String,
    val model: String,
    val executionProfileId: String,
    val bindingFingerprint: String,
    val promptVersion: Int,
    val promptHash: String,
    val contextHash: String,
    val outputHash: String,
    val executionId: Long,
)

@Serializable
data class DefinitionProposal(
    val proposalId: Long,
    val workItemId: Int,
    val revision: Int,
    val parentProposalId: Long? = null,
    val actor: String,
    val content: DefinitionProposalContent,
    val provenance: DefinitionExecutionProvenance? = null,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class DefinitionFeedback(
    val feedbackId: Long,
    val proposalId: Long,
    val actor: String,
    val content: String,
    val createdAt: String = Instant.now().toString(),
)

@Serializable
data class DefinitionCollaborationEvent(
    val eventId: Long,
    val proposal: DefinitionProposal? = null,
    val feedback: DefinitionFeedback? = null,
)

@Serializable
data class DefinitionProposalReference(
    val proposalId: Long,
    val proposalHash: String,
)

@Serializable
data class DefinitionProposalView(
    val proposal: DefinitionProposal,
    val feedback: List<DefinitionFeedback>,
    val acceptedDefinitionId: Long? = null,
)

@Serializable
data class DefinitionStepContext(
    val workItemId: Int,
    val workItemType: Int,
    val projectTitle: String,
    val epicTitle: String,
    val storyTitle: String,
    val title: String,
    val content: String,
    val systemWorkflow: SystemWorkflow,
    val currentDefinition: WorkDefinitionManifest? = null,
    val proposals: List<DefinitionProposalView> = emptyList(),
    val feedback: List<DefinitionFeedback> = emptyList(),
)

enum class DefinitionCollaborationStatus {
    RECORDED,
    WORK_ITEM_NOT_FOUND,
    PROPOSAL_NOT_FOUND,
    WORKFLOW_ALREADY_STARTED,
    INVALID_RECORD,
    STORAGE_UNAVAILABLE,
}

data class DefinitionCollaborationResult(
    val status: DefinitionCollaborationStatus,
    val snapshot: WorkspaceSnapshot,
    val proposal: DefinitionProposal? = null,
)

interface DefinitionCollaborationStore {
    fun loadEvents(): List<DefinitionCollaborationEvent>
    fun appendEvent(event: DefinitionCollaborationEvent)
}

class TransientDefinitionCollaborationStore : DefinitionCollaborationStore {
    private val events = mutableListOf<DefinitionCollaborationEvent>()

    @Synchronized
    override fun loadEvents(): List<DefinitionCollaborationEvent> = events.toList()

    @Synchronized
    override fun appendEvent(event: DefinitionCollaborationEvent) {
        validateCollaborationEvent(event, events.size + 1L, events)
        events += event
    }
}

class FileDefinitionCollaborationStore(private val directory: Path) : DefinitionCollaborationStore {
    private val path = directory.resolve("definition-collaboration.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<DefinitionCollaborationEvent> {
        val events = mutableListOf<DefinitionCollaborationEvent>()
        return loadRecoverableJsonl(path, "definition-collaboration") { line, recordNumber ->
            val envelope = runCatching { json.decodeFromString<CollaborationEnvelope>(line) }
                .getOrElse { throw IllegalStateException("Cannot decode collaboration event $recordNumber", it) }
            require(envelope.version == FORMAT_VERSION) { "Unsupported collaboration format ${envelope.version}" }
            require(envelope.checksum == collaborationHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in collaboration event $recordNumber"
            }
            validateCollaborationEvent(envelope.value, recordNumber.toLong(), events)
            events += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendEvent(event: DefinitionCollaborationEvent) {
        val events = loadEvents()
        validateCollaborationEvent(event, events.size + 1L, events)
        val payload = json.encodeToString(event)
        val line = json.encodeToString(
            CollaborationEnvelope(value = event, checksum = collaborationHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun newDefinitionProposal(
    proposalId: Long,
    workItemId: Int,
    revision: Int,
    parentProposalId: Long?,
    actor: String,
    content: DefinitionProposalContent,
    provenance: DefinitionExecutionProvenance?,
): DefinitionProposal {
    val unsigned = DefinitionProposal(
        proposalId = proposalId,
        workItemId = workItemId,
        revision = revision,
        parentProposalId = parentProposalId,
        actor = actor,
        content = content,
        provenance = provenance,
        hash = "",
    )
    return unsigned.copy(hash = definitionProposalHash(unsigned))
}

fun definitionProposalHash(proposal: DefinitionProposal): String = collaborationHash(
    collaborationJson.encodeToString(proposal.copy(hash = ""))
)

private fun validateCollaborationEvent(
    event: DefinitionCollaborationEvent,
    expectedEventId: Long,
    previousEvents: List<DefinitionCollaborationEvent>,
) {
    require(event.eventId == expectedEventId) { "Expected collaboration event ID $expectedEventId" }
    require(listOfNotNull(event.proposal, event.feedback).size == 1) {
        "A collaboration event must contain exactly one mutation"
    }
    event.proposal?.let { proposal ->
        require(proposal.proposalId == event.eventId) { "Proposal ID must match its event ID" }
        require(proposal.workItemId > 0 && proposal.revision > 0) { "Proposal identity is invalid" }
        require(proposal.actor == COLLABORATOR_HUMAN || proposal.actor == COLLABORATOR_LOCAL_LLM) {
            "Proposal actor is invalid"
        }
        require(proposal.actor != COLLABORATOR_LOCAL_LLM || proposal.provenance != null) {
            "Local LLM proposals require execution provenance"
        }
        proposal.provenance?.let { provenance ->
            require(
                provenance.executor.isNotBlank() && provenance.model.isNotBlank() &&
                    provenance.executionProfileId.isNotBlank() && provenance.bindingFingerprint.matches(SHA256) &&
                    provenance.executionId > 0 && provenance.promptVersion > 0 &&
                    provenance.promptHash.matches(SHA256) && provenance.contextHash.matches(SHA256) &&
                    provenance.outputHash.matches(SHA256)
            ) { "Proposal execution provenance is invalid" }
        }
        require(proposal.hash == definitionProposalHash(proposal)) { "Proposal hash mismatch" }
        proposal.parentProposalId?.let { parentId ->
            val parent = previousEvents.mapNotNull { it.proposal }.singleOrNull { it.proposalId == parentId }
            require(parent != null && parent.workItemId == proposal.workItemId) { "Proposal parent is invalid" }
        }
    }
    event.feedback?.let { feedback ->
        require(feedback.feedbackId == event.eventId) { "Feedback ID must match its event ID" }
        require(feedback.actor == COLLABORATOR_HUMAN) { "Feedback actor is invalid" }
        require(feedback.content.isNotBlank()) { "Feedback content is required" }
        require(previousEvents.mapNotNull { it.proposal }.any { it.proposalId == feedback.proposalId }) {
            "Feedback proposal is invalid"
        }
    }
}

private fun collaborationHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

@Serializable
private data class CollaborationEnvelope(
    val version: Int = 1,
    val value: DefinitionCollaborationEvent,
    val checksum: String,
)

private val collaborationJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")