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

@Serializable
data class CircuitExecutionProvenance(
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
data class CircuitProposalContent(
    val plan: StagedDeliveryPlanSubmission,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

@Serializable
data class CircuitProposal(
    val proposalId: Long,
    val scopeId: Int,
    val revision: Int,
    val actor: String = COLLABORATOR_LOCAL_LLM,
    val content: CircuitProposalContent,
    val provenance: CircuitExecutionProvenance,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CircuitProposalReference(
    val proposalId: Long,
    val proposalHash: String,
)

@Serializable
data class CircuitProposalView(
    val proposal: CircuitProposal,
    val acceptedPlanId: Long? = null,
    val acceptedUnchanged: Boolean = false,
)

@Serializable
data class CircuitSynthesisMember(
    val workItemId: Int,
    val workItemType: Int,
    val title: String,
    val content: String,
    val definition: WorkDefinitionManifest? = null,
    val artifactEvidenceKinds: List<String> = emptyList(),
)

@Serializable
data class CircuitSynthesisContext(
    val scopeId: Int,
    val scopeType: Int,
    val scopeTitle: String,
    val scopeContent: String,
    val members: List<CircuitSynthesisMember>,
    val activePlan: StagedDeliveryPlan? = null,
    val planLocked: Boolean = false,
    val stageWorkflows: List<StageExecutionWorkflowDefinition>,
)

enum class CircuitProposalStatus {
    RECORDED,
    SCOPE_NOT_FOUND,
    INVALID_SCOPE,
    PLAN_LOCKED,
    STALE_CONTEXT,
    INVALID_PROPOSAL,
    PROPOSAL_NOT_FOUND,
    STORAGE_UNAVAILABLE,
}

data class CircuitProposalResult(
    val status: CircuitProposalStatus,
    val snapshot: WorkspaceSnapshot,
    val proposal: CircuitProposal? = null,
)

interface CircuitProposalStore {
    fun load(): List<CircuitProposal>
    fun append(proposal: CircuitProposal)
}

class TransientCircuitProposalStore : CircuitProposalStore {
    private val proposals = mutableListOf<CircuitProposal>()

    @Synchronized
    override fun load(): List<CircuitProposal> = proposals.toList()

    @Synchronized
    override fun append(proposal: CircuitProposal) {
        validateCircuitProposal(proposal, proposals)
        proposals += proposal
    }
}

class FileCircuitProposalStore(private val directory: Path) : CircuitProposalStore {
    private val path = directory.resolve("circuit-proposals.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CircuitProposal> {
        val proposals = mutableListOf<CircuitProposal>()
        return loadRecoverableJsonl(path, "circuit-proposals") { line, recordNumber ->
            val envelope = runCatching { json.decodeFromString<CircuitProposalEnvelope>(line) }
                .getOrElse { throw IllegalStateException("Cannot decode circuit proposal $recordNumber", it) }
            require(envelope.version == FORMAT_VERSION) { "Unsupported circuit proposal format ${envelope.version}" }
            require(envelope.checksum == circuitProposalHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in circuit proposal $recordNumber"
            }
            validateCircuitProposal(envelope.value, proposals)
            proposals += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun append(proposal: CircuitProposal) {
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = load()
                validateCircuitProposal(proposal, existing)
                val payload = json.encodeToString(proposal)
                val line = json.encodeToString(
                    CircuitProposalEnvelope(value = proposal, checksum = circuitProposalHash(payload))
                ) + "\n"
                channel.position(channel.size())
                val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun newCircuitProposal(
    proposalId: Long,
    scopeId: Int,
    revision: Int,
    content: CircuitProposalContent,
    provenance: CircuitExecutionProvenance,
): CircuitProposal {
    val unsigned = CircuitProposal(
        proposalId = proposalId,
        scopeId = scopeId,
        revision = revision,
        content = content,
        provenance = provenance,
        hash = "",
    )
    return unsigned.copy(hash = circuitProposalHash(circuitProposalJson.encodeToString(unsigned)))
}

private fun validateCircuitProposal(proposal: CircuitProposal, previous: List<CircuitProposal>) {
    require(proposal.proposalId == previous.size + 1L) { "Circuit proposal ID is not monotonic" }
    require(proposal.scopeId > 0 && proposal.content.plan.scopeId == proposal.scopeId) { "Circuit proposal scope is invalid" }
    require(proposal.revision == previous.count { it.scopeId == proposal.scopeId } + 1) {
        "Circuit proposal revision is not monotonic"
    }
    require(proposal.actor == COLLABORATOR_LOCAL_LLM) { "Circuit proposal actor is invalid" }
    require(proposal.content.observations.size <= MAX_NOTES && proposal.content.assumptions.size <= MAX_NOTES) {
        "Circuit proposal notes exceed their bounds"
    }
    require((proposal.content.observations + proposal.content.assumptions).all { it.isNotBlank() && it.length <= MAX_NOTE_TEXT }) {
        "Circuit proposal notes are invalid"
    }
    val provenance = proposal.provenance
    require(
        provenance.executor.isNotBlank() && provenance.model.isNotBlank() &&
            provenance.executionProfileId.isNotBlank() && provenance.bindingFingerprint.matches(SHA256) &&
            provenance.promptVersion > 0 && provenance.executionId > 0 &&
            provenance.promptHash.matches(SHA256) && provenance.contextHash.matches(SHA256) &&
            provenance.outputHash.matches(SHA256)
    ) { "Circuit proposal provenance is invalid" }
    require(proposal.hash == circuitProposalHash(circuitProposalJson.encodeToString(proposal.copy(hash = "")))) {
        "Circuit proposal hash is invalid"
    }
}

fun circuitProposalHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

@Serializable
private data class CircuitProposalEnvelope(
    val version: Int = 1,
    val value: CircuitProposal,
    val checksum: String,
)

private const val MAX_NOTES = 16
private const val MAX_NOTE_TEXT = 1_024
private val SHA256 = Regex("[0-9a-f]{64}")
private val circuitProposalJson = Json { encodeDefaults = true }
