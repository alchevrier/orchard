package com.orchard.backend.agent

import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CODING_EXECUTION_CLAIMED = "CLAIMED"
const val CODING_EXECUTION_COMPLETED = "COMPLETED"
const val CODING_EXECUTION_DEFERRED = "DEFERRED"
const val CODING_EXECUTION_BLOCKED = "BLOCKED"
const val CODING_EXECUTION_FAILED = "FAILED"
const val CODING_EXECUTION_INTERRUPTED = "INTERRUPTED"

@Serializable
data class CodingWorkerClaim(
    val executionId: Long,
    val runId: Long,
    val attempt: Int,
    val contextHash: String,
    val workspacePath: String,
    val bindingFingerprint: String,
    val assignmentId: Long? = null,
    val staffRole: String? = null,
    val riskClass: String? = null,
    val executionPlanId: Long? = null,
    val executionPlanHash: String? = null,
    val toolchainPackId: String? = null,
    val toolchainPackVersion: Int? = null,
    val toolchainProfileId: String? = null,
    val toolchainPolicyHash: String? = null,
    val claimedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CodingWorkerResult(
    val executionId: Long,
    val status: String,
    val modelExecutionId: Long? = null,
    val proposalHash: String? = null,
    val changedPaths: List<String> = emptyList(),
    val revision: String? = null,
    val diagnostic: String,
    val retryAfter: String? = null,
    val completedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CodingWorkerEvent(
    val eventId: Long,
    val claim: CodingWorkerClaim? = null,
    val result: CodingWorkerResult? = null,
)

@Serializable
data class CodingWorkerExecutionView(
    val claim: CodingWorkerClaim,
    val result: CodingWorkerResult? = null,
)

interface CodingWorkerStore {
    fun loadEvents(): List<CodingWorkerEvent>
    fun append(event: CodingWorkerEvent)
}

class TransientCodingWorkerStore : CodingWorkerStore {
    private val events = mutableListOf<CodingWorkerEvent>()

    @Synchronized
    override fun loadEvents(): List<CodingWorkerEvent> = events.toList()

    @Synchronized
    override fun append(event: CodingWorkerEvent) {
        validateCodingWorkerEvent(event, events.size + 1L, events)
        events += event
    }
}

class FileCodingWorkerStore(private val directory: Path) : CodingWorkerStore {
    private val path = directory.resolve("coding-worker.jsonl")
    private val lockPath = directory.resolve("coding-worker.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<CodingWorkerEvent> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use { loadEventsUnlocked() }
        }
    }

    private fun loadEventsUnlocked(): List<CodingWorkerEvent> = mutableListOf<CodingWorkerEvent>().let { events ->
        loadRecoverableJsonl(path, "coding-worker") { line, recordNumber ->
            val envelope = json.decodeFromString<CodingWorkerEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported coding worker format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in coding worker event $recordNumber"
            }
            require(envelope.value.eventId == recordNumber.toLong()) {
                "Expected coding worker event ID $recordNumber"
            }
            validateCodingWorkerEvent(envelope.value, recordNumber.toLong(), events)
            events += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun append(event: CodingWorkerEvent) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val existing = loadEventsUnlocked()
                validateCodingWorkerEvent(event, existing.size + 1L, existing)
                val payload = json.encodeToString(event)
                val line = json.encodeToString(
                    CodingWorkerEnvelope(value = event, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
private data class CodingWorkerEnvelope(
    val version: Int = 1,
    val value: CodingWorkerEvent,
    val checksum: String,
)

internal fun codingWorkerClaimHash(claim: CodingWorkerClaim): String = stagedPlanHash(
    "${claim.executionId}:${claim.runId}:${claim.attempt}:${claim.contextHash}:${claim.workspacePath}:" +
        "${claim.bindingFingerprint}:${claim.assignmentId}:${claim.staffRole.orEmpty()}:${claim.riskClass.orEmpty()}:" +
        "${claim.executionPlanId}:${claim.executionPlanHash.orEmpty()}:" +
        "${claim.toolchainPackId.orEmpty()}:${claim.toolchainPackVersion}:" +
        "${claim.toolchainProfileId.orEmpty()}:${claim.toolchainPolicyHash.orEmpty()}:${claim.claimedAt}"
)

private fun prePlanCodingWorkerClaimHash(claim: CodingWorkerClaim): String = stagedPlanHash(
    "${claim.executionId}:${claim.runId}:${claim.attempt}:${claim.contextHash}:${claim.workspacePath}:" +
        "${claim.bindingFingerprint}:${claim.assignmentId}:${claim.staffRole.orEmpty()}:${claim.riskClass.orEmpty()}:" +
        "${claim.toolchainPackId.orEmpty()}:${claim.toolchainPackVersion}:" +
        "${claim.toolchainProfileId.orEmpty()}:${claim.toolchainPolicyHash.orEmpty()}:${claim.claimedAt}"
)

private fun preCompanyCodingWorkerClaimHash(claim: CodingWorkerClaim): String = stagedPlanHash(
    "${claim.executionId}:${claim.runId}:${claim.attempt}:${claim.contextHash}:${claim.workspacePath}:" +
        "${claim.bindingFingerprint}:${claim.toolchainPackId.orEmpty()}:${claim.toolchainPackVersion}:" +
        "${claim.toolchainProfileId.orEmpty()}:${claim.toolchainPolicyHash.orEmpty()}:${claim.claimedAt}"
)

    private fun legacyCodingWorkerClaimHash(claim: CodingWorkerClaim): String = stagedPlanHash(
        "${claim.executionId}:${claim.runId}:${claim.attempt}:${claim.contextHash}:${claim.workspacePath}:" +
        "${claim.bindingFingerprint}:${claim.claimedAt}"
    )

internal fun codingWorkerResultHash(result: CodingWorkerResult): String = stagedPlanHash(
    "${result.executionId}:${result.status}:${result.modelExecutionId}:${result.proposalHash.orEmpty()}:" +
        "${result.changedPaths.joinToString("\u0000")}:" +
        "${result.revision.orEmpty()}:${result.diagnostic}:${result.retryAfter.orEmpty()}:${result.completedAt}"
)

internal fun codingWorkerExecutions(events: List<CodingWorkerEvent>): List<CodingWorkerExecutionView> {
    val results = events.mapNotNull { it.result }.associateBy { it.executionId }
    return events.mapNotNull { event -> event.claim?.let { CodingWorkerExecutionView(it, results[it.executionId]) } }
}

private fun validateCodingWorkerEvent(
    event: CodingWorkerEvent,
    expectedEventId: Long,
    preceding: List<CodingWorkerEvent>,
) {
    require(event.eventId == expectedEventId) { "Expected coding worker event ID $expectedEventId" }
    require(listOfNotNull(event.claim, event.result).size == 1) { "Coding worker event must have exactly one payload" }
    val executions = codingWorkerExecutions(preceding)
    event.claim?.let { claim ->
        require(claim.executionId == event.eventId && claim.runId > 0 && claim.attempt > 0) {
            "Coding worker claim identity is invalid"
        }
        require(claim.contextHash.matches(SHA256) && claim.bindingFingerprint.matches(SHA256)) {
            "Coding worker claim authority hashes are invalid"
        }
        require(claim.workspacePath.isNotBlank() && claim.claimedAt.isNotBlank()) {
            "Coding worker claim context is incomplete"
        }
        val toolchainAuthority = listOfNotNull(
            claim.toolchainPackId,
            claim.toolchainPackVersion,
            claim.toolchainProfileId,
            claim.toolchainPolicyHash,
        )
        val staffingAuthority = listOfNotNull(claim.assignmentId, claim.staffRole, claim.riskClass)
        val planAuthority = listOfNotNull(claim.executionPlanId, claim.executionPlanHash)
        require(staffingAuthority.isEmpty() || staffingAuthority.size == 3) {
            "Coding worker claim has partial staffing authority"
        }
        if (staffingAuthority.isNotEmpty()) {
            require(requireNotNull(claim.assignmentId) > 0 && claim.staffRole == "IMPLEMENTER") {
                "Coding worker staffing authority is invalid"
            }
            require(claim.riskClass in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                "Coding worker risk authority is invalid"
            }
        }
        require(planAuthority.isEmpty() || planAuthority.size == 2) { "Coding worker claim has partial execution-plan authority" }
        if (planAuthority.isNotEmpty()) {
            require(requireNotNull(claim.executionPlanId) > 0 && requireNotNull(claim.executionPlanHash).matches(SHA256)) {
                "Coding worker execution-plan authority is invalid"
            }
        }
        require(toolchainAuthority.isEmpty() || toolchainAuthority.size == 4) {
            "Coding worker claim has partial toolchain authority"
        }
        if (toolchainAuthority.isNotEmpty()) {
            require(
                requireNotNull(claim.toolchainPackId).matches(POLICY_ID) &&
                    requireNotNull(claim.toolchainPackVersion) > 0 &&
                    requireNotNull(claim.toolchainProfileId).matches(POLICY_ID) &&
                    requireNotNull(claim.toolchainPolicyHash).matches(SHA256)
            ) { "Coding worker claim toolchain authority is invalid" }
        }
        val currentHash = codingWorkerClaimHash(claim.copy(hash = ""))
        val prePlanHash = if (planAuthority.isEmpty()) prePlanCodingWorkerClaimHash(claim.copy(hash = "")) else null
        val preCompanyHash = if (staffingAuthority.isEmpty()) preCompanyCodingWorkerClaimHash(claim.copy(hash = "")) else null
        val legacyHash = if (toolchainAuthority.isEmpty()) legacyCodingWorkerClaimHash(claim.copy(hash = "")) else null
        require(claim.hash == currentHash || claim.hash == prePlanHash || claim.hash == preCompanyHash || claim.hash == legacyHash) {
            "Coding worker claim hash mismatch"
        }
        require(executions.none { it.result == null }) { "Another coding worker execution is still active" }
        val previous = executions.filter { it.claim.runId == claim.runId }
        require(claim.attempt == previous.size + 1) { "Coding worker attempt sequence is invalid" }
    }
    event.result?.let { result ->
        require(result.status in TERMINAL_STATUSES && result.diagnostic.isNotBlank()) {
            "Coding worker result is invalid"
        }
        require(result.hash == codingWorkerResultHash(result.copy(hash = ""))) { "Coding worker result hash mismatch" }
        val execution = executions.singleOrNull { it.claim.executionId == result.executionId }
            ?: throw IllegalArgumentException("Coding worker result has no claim")
        require(execution.result == null) { "Coding worker execution already has a result" }
        require(result.modelExecutionId == null || result.modelExecutionId > 0) { "Model execution identity is invalid" }
        require(result.proposalHash == null || result.proposalHash.matches(SHA256)) { "Proposal hash is invalid" }
        require(result.revision == null || result.revision.matches(GIT_HASH)) { "Candidate revision is invalid" }
        require(result.changedPaths.distinct().size == result.changedPaths.size) { "Changed paths must be unique" }
        if (result.status == CODING_EXECUTION_DEFERRED) {
            requireNotNull(result.retryAfter).let(Instant::parse)
        } else {
            require(result.retryAfter == null) { "Only deferred coding executions can declare retry timing" }
        }
        if (result.status == CODING_EXECUTION_COMPLETED) {
            require(result.modelExecutionId != null && result.proposalHash != null && result.revision != null) {
                "Completed coding execution lacks authority references"
            }
            require(result.changedPaths.isNotEmpty()) { "Completed coding execution has no changed paths" }
        }
    }
}

private val TERMINAL_STATUSES = setOf(
    CODING_EXECUTION_COMPLETED,
    CODING_EXECUTION_DEFERRED,
    CODING_EXECUTION_BLOCKED,
    CODING_EXECUTION_FAILED,
    CODING_EXECUTION_INTERRUPTED,
)
private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_HASH = Regex("[0-9a-fA-F]{40}")
private val POLICY_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
