package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DESIGN_STATUS_CANDIDATE = "CANDIDATE"
const val DESIGN_STATUS_REJECTED = "REJECTED"
const val DESIGN_STATUS_ADMITTED = "ADMITTED"
const val DESIGN_STATUS_SUPERSEDED = "SUPERSEDED"
const val CRITERION_AUTOMATED = "AUTOMATED"
const val CRITERION_HUMAN = "HUMAN"

@Serializable
data class DesignCriterionSubmission(
    val description: String,
    val verification: String = "",
    val gate: String = CRITERION_AUTOMATED,
)

@Serializable
data class RequirementSubmission(
    val requirementId: String,
    val statement: String,
    val parentRequirementIds: List<String> = emptyList(),
    val criteria: List<DesignCriterionSubmission>,
)

@Serializable
data class DesignSubmission(
    val workItemId: Int,
    val title: String,
    val problem: String,
    val scope: List<String>,
    val assumptions: List<String>,
    val constraints: List<String>,
    val alternatives: List<String>,
    val architecture: List<String>,
    val failureModes: List<String>,
    val qualityAttributes: List<String>,
    val securityImpact: String,
    val complianceImpact: String,
    val requirements: List<RequirementSubmission>,
    val baseRevision: Int = 0,
    val baseHash: String? = null,
)

@Serializable
data class DesignAuthorityReference(
    val workItemId: Int,
    val designId: Long,
    val revision: Int,
    val hash: String,
)

@Serializable
data class DesignRevision(
    val designId: Long,
    val revision: Int,
    val workItemId: Int,
    val workItemType: Int,
    val requirementLevel: String,
    val content: DesignSubmission,
    val actor: String,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class DesignFinding(
    val code: String,
    val message: String,
    val requirementId: String? = null,
)

@Serializable
data class ContractCriterion(
    val criterionId: String,
    val requirementId: String,
    val description: String,
    val verification: String,
    val gate: String,
)

@Serializable
data class AcceptanceContract(
    val contractId: Long,
    val design: DesignAuthorityReference,
    val parentDesigns: List<DesignAuthorityReference>,
    val inheritedRequirementIds: List<String>,
    val criteria: List<ContractCriterion>,
    val compiledAt: String,
    val hash: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class DesignAdmissionDecision(
    val decisionId: Long,
    val designId: Long,
    val status: String,
    val reviewer: String,
    val findings: List<DesignFinding>,
    val decidedAt: String = Instant.now().toString(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contract: AcceptanceContract? = null,
    val hash: String,
)

@Serializable
data class ProjectGovernanceActivation(
    val activationId: Long,
    val projectId: Int,
    val activatedBy: String,
    val activatedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class DesignGovernanceEvent(
    val eventId: Long,
    val design: DesignRevision? = null,
    val decision: DesignAdmissionDecision? = null,
    val activation: ProjectGovernanceActivation? = null,
)

@Serializable
data class DesignRevisionView(
    val design: DesignRevision,
    val status: String,
    val decision: DesignAdmissionDecision? = null,
)

@Serializable
data class ProjectGovernanceView(
    val activation: ProjectGovernanceActivation,
    val blockingFindings: List<DesignFinding> = emptyList(),
)

interface DesignGovernanceStore {
    fun loadEvents(): List<DesignGovernanceEvent>
    fun append(event: DesignGovernanceEvent)
}

class TransientDesignGovernanceStore : DesignGovernanceStore {
    private val events = mutableListOf<DesignGovernanceEvent>()

    @Synchronized
    override fun loadEvents(): List<DesignGovernanceEvent> = events.toList()

    @Synchronized
    override fun append(event: DesignGovernanceEvent) {
        events += event
    }
}

class FileDesignGovernanceStore(private val directory: Path) : DesignGovernanceStore {
    private val path = directory.resolve("design-governance.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<DesignGovernanceEvent> =
        loadRecoverableJsonl(path, "design-governance") { line, recordNumber ->
            val envelope = json.decodeFromString<DesignGovernanceEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) {
                "Unsupported design governance format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in design governance event $recordNumber"
            }
            envelope.value
        }

    @Synchronized
    override fun append(event: DesignGovernanceEvent) {
        val payload = json.encodeToString(event)
        val line = json.encodeToString(
            DesignGovernanceEnvelope(value = event, checksum = stagedPlanHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = Files.readAllLines(path, Charsets.UTF_8).filter(String::isNotBlank)
                val expectedEventId = existing.lastOrNull()?.let { persisted ->
                    json.decodeFromString<DesignGovernanceEnvelope>(persisted).value.eventId + 1
                } ?: 1L
                require(event.eventId == expectedEventId) { "Expected design governance event ID $expectedEventId" }
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

@Serializable
private data class DesignGovernanceEnvelope(
    val version: Int = 1,
    val value: DesignGovernanceEvent,
    val checksum: String,
)

enum class DesignGovernanceStatus {
    RECORDED,
    ADMITTED,
    REJECTED,
    WORK_ITEM_NOT_FOUND,
    INVALID_SCOPE,
    INVALID_DESIGN,
    STALE_DESIGN,
    DESIGN_NOT_FOUND,
    ALREADY_DECIDED,
    GOVERNANCE_ALREADY_ACTIVE,
    WORKFLOW_ALREADY_STARTED,
    STORAGE_UNAVAILABLE,
}

data class DesignGovernanceResult(
    val status: DesignGovernanceStatus,
    val snapshot: WorkspaceSnapshot,
    val design: DesignRevision? = null,
    val decision: DesignAdmissionDecision? = null,
)
