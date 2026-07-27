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

const val CANDIDATE_DISPOSITION_REVIEW_REQUIRED = "REVIEW_REQUIRED"
const val CANDIDATE_DISPOSITION_REPAIR_REQUIRED = "REPAIR_REQUIRED"
const val CANDIDATE_DISPOSITION_ACCEPTED = "ACCEPTED"
const val CANDIDATE_DISPOSITION_BLOCKED = "BLOCKED"
const val CANDIDATE_DISPOSITION_SUPERSEDED = "SUPERSEDED"
const val CANDIDATE_DISPOSITION_ABANDONED = "ABANDONED"

@Serializable
data class CandidatePullRequestDisposition(
    val dispositionId: Long,
    val pullRequestId: Long,
    val pullRequestHash: String,
    val status: String,
    val reason: String,
    val correctionId: Long? = null,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestDispositionStore {
    fun load(): List<CandidatePullRequestDisposition>
    fun appendNext(create: (dispositionId: Long) -> CandidatePullRequestDisposition): CandidatePullRequestDisposition
}

class TransientCandidatePullRequestDispositionStore : CandidatePullRequestDispositionStore {
    private val dispositions = mutableListOf<CandidatePullRequestDisposition>()

    @Synchronized
    override fun load(): List<CandidatePullRequestDisposition> = dispositions.toList()

    @Synchronized
    override fun appendNext(
        create: (dispositionId: Long) -> CandidatePullRequestDisposition,
    ): CandidatePullRequestDisposition {
        val disposition = create(dispositions.size + 1L)
        validateCandidatePullRequestDisposition(disposition, dispositions)
        dispositions += disposition
        return disposition
    }
}

class FileCandidatePullRequestDispositionStore(private val directory: Path) : CandidatePullRequestDispositionStore {
    private val path = directory.resolve("candidate-pull-request-dispositions.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-dispositions.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestDisposition> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(
        create: (dispositionId: Long) -> CandidatePullRequestDisposition,
    ): CandidatePullRequestDisposition {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val dispositions = loadUnlocked()
                val disposition = create(dispositions.size + 1L)
                validateCandidatePullRequestDisposition(disposition, dispositions)
                val payload = json.encodeToString(disposition)
                val line = json.encodeToString(
                    CandidatePullRequestDispositionEnvelope(value = disposition, checksum = stagedPlanHash(payload)),
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                disposition
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestDisposition> = mutableListOf<CandidatePullRequestDisposition>().also { dispositions ->
        loadRecoverableJsonl(path, "candidate-pull-request-dispositions") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestDispositionEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported candidate PR disposition format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate PR disposition $recordNumber"
            }
            validateCandidatePullRequestDisposition(envelope.value, dispositions)
            dispositions += envelope.value
            envelope.value
        }
    }
}

class CandidatePullRequestDispositionService(
    private val pullRequestStore: CandidatePullRequestStore,
    private val dispositionStore: CandidatePullRequestDispositionStore = TransientCandidatePullRequestDispositionStore(),
) {
    fun dispositions(pullRequestId: Long? = null): List<CandidatePullRequestDisposition> = dispositionStore.load()
        .filter { pullRequestId == null || it.pullRequestId == pullRequestId }

    @Synchronized
    fun record(
        pullRequestId: Long,
        status: String,
        reason: String,
        correctionId: Long? = null,
    ): CandidatePullRequestDisposition? {
        val pullRequest = pullRequestStore.load().singleOrNull { it.pullRequestId == pullRequestId } ?: return null
        val previous = dispositions(pullRequestId).lastOrNull()
        if (!isCandidateDispositionTransitionAllowed(previous?.status, status)) return null
        return dispositionStore.appendNext { dispositionId ->
            newCandidatePullRequestDisposition(dispositionId, pullRequest, status, reason, correctionId)
        }
    }
}

fun newCandidatePullRequestDisposition(
    dispositionId: Long,
    pullRequest: CandidatePullRequest,
    status: String,
    reason: String,
    correctionId: Long? = null,
): CandidatePullRequestDisposition {
    val draft = CandidatePullRequestDisposition(
        dispositionId = dispositionId,
        pullRequestId = pullRequest.pullRequestId,
        pullRequestHash = pullRequest.hash,
        status = status,
        reason = reason.trim(),
        correctionId = correctionId,
        hash = "",
    )
    return draft.copy(hash = candidatePullRequestDispositionHash(draft))
}

fun candidatePullRequestDispositionHash(disposition: CandidatePullRequestDisposition): String = stagedPlanHash(
    candidatePullRequestDispositionJson.encodeToString(disposition.copy(hash = "")),
)

fun isCandidateDispositionTransitionAllowed(previous: String?, next: String): Boolean = when (previous) {
    null -> next in setOf(
        CANDIDATE_DISPOSITION_REVIEW_REQUIRED,
        CANDIDATE_DISPOSITION_REPAIR_REQUIRED,
        CANDIDATE_DISPOSITION_BLOCKED,
        CANDIDATE_DISPOSITION_ABANDONED,
    )
    CANDIDATE_DISPOSITION_REVIEW_REQUIRED -> next in setOf(
        CANDIDATE_DISPOSITION_REPAIR_REQUIRED,
        CANDIDATE_DISPOSITION_SUPERSEDED,
        CANDIDATE_DISPOSITION_ACCEPTED,
        CANDIDATE_DISPOSITION_BLOCKED,
        CANDIDATE_DISPOSITION_ABANDONED,
    )
    CANDIDATE_DISPOSITION_REPAIR_REQUIRED -> next in setOf(
        CANDIDATE_DISPOSITION_SUPERSEDED,
        CANDIDATE_DISPOSITION_BLOCKED,
        CANDIDATE_DISPOSITION_ABANDONED,
    )
    CANDIDATE_DISPOSITION_ACCEPTED -> next == CANDIDATE_DISPOSITION_BLOCKED
    else -> false
}

private fun validateCandidatePullRequestDisposition(
    disposition: CandidatePullRequestDisposition,
    previous: List<CandidatePullRequestDisposition>,
) {
    require(disposition.dispositionId == previous.size + 1L && disposition.pullRequestId > 0 &&
        disposition.pullRequestHash.matches(SHA256) && disposition.status in CANDIDATE_DISPOSITIONS &&
        disposition.reason.isNotBlank() && disposition.correctionId?.let { it > 0 } != false
    ) { "Candidate PR disposition is invalid" }
    val priorStatus = previous.lastOrNull { it.pullRequestId == disposition.pullRequestId }?.status
    require(isCandidateDispositionTransitionAllowed(priorStatus, disposition.status)) {
        "Candidate PR disposition transition is invalid"
    }
    require(disposition.hash == candidatePullRequestDispositionHash(disposition)) { "Candidate PR disposition hash is invalid" }
}

@Serializable
private data class CandidatePullRequestDispositionEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequestDisposition,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val CANDIDATE_DISPOSITIONS = setOf(
    CANDIDATE_DISPOSITION_REVIEW_REQUIRED,
    CANDIDATE_DISPOSITION_REPAIR_REQUIRED,
    CANDIDATE_DISPOSITION_ACCEPTED,
    CANDIDATE_DISPOSITION_BLOCKED,
    CANDIDATE_DISPOSITION_SUPERSEDED,
    CANDIDATE_DISPOSITION_ABANDONED,
)
private val candidatePullRequestDispositionJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")