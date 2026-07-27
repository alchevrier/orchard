@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.agent

import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.workspace.EvidenceRecord
import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CandidatePullRequestEvidence(
    val kind: String,
    val command: String,
    val passed: Boolean,
    val outputHash: String,
    val summary: String,
)

@Serializable
data class CandidatePullRequest(
    val pullRequestId: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentPullRequestId: Long? = null,
    val runId: Long,
    val workPackageId: Long,
    val workPackageHash: String,
    val baseRevision: String,
    val candidateRevision: String,
    val changedPaths: List<String>,
    val implementationClaims: List<String>,
    val checks: List<String>,
    val evidence: List<CandidatePullRequestEvidence>,
    val deviations: List<String>,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestStore {
    fun load(): List<CandidatePullRequest>
    fun appendNext(create: (pullRequestId: Long) -> CandidatePullRequest): CandidatePullRequest
}

class TransientCandidatePullRequestStore : CandidatePullRequestStore {
    private val pullRequests = mutableListOf<CandidatePullRequest>()

    override fun load(): List<CandidatePullRequest> = pullRequests.toList()

    override fun appendNext(create: (pullRequestId: Long) -> CandidatePullRequest): CandidatePullRequest {
        val pullRequest = create(pullRequests.size + 1L)
        validateCandidatePullRequest(pullRequest, pullRequests)
        pullRequests += pullRequest
        return pullRequest
    }
}

class FileCandidatePullRequestStore(private val directory: Path) : CandidatePullRequestStore {
    private val path = directory.resolve("candidate-pull-requests.jsonl")
    private val lockPath = directory.resolve("candidate-pull-requests.lock")
    private val json = Json { encodeDefaults = true }

    override fun load(): List<CandidatePullRequest> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    override fun appendNext(create: (pullRequestId: Long) -> CandidatePullRequest): CandidatePullRequest {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val existing = loadUnlocked()
                val pullRequest = create(existing.size + 1L)
                validateCandidatePullRequest(pullRequest, existing)
                val payload = json.encodeToString(pullRequest)
                val line = json.encodeToString(
                    CandidatePullRequestEnvelope(value = pullRequest, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                pullRequest
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequest> = mutableListOf<CandidatePullRequest>().also { pullRequests ->
        loadRecoverableJsonl(path, "candidate-pull-requests") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported candidate PR format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate PR $recordNumber"
            }
            validateCandidatePullRequest(envelope.value, pullRequests)
            pullRequests += envelope.value
            envelope.value
        }
    }
}

fun newCandidatePullRequest(
    pullRequestId: Long,
    packageAuthority: ExecutableWorkPackage,
    candidate: CodingCandidate,
    evidence: List<EvidenceRecord>,
    deviations: List<String> = emptyList(),
    parentPullRequestId: Long? = null,
): CandidatePullRequest {
    val draft = CandidatePullRequest(
        pullRequestId = pullRequestId,
        parentPullRequestId = parentPullRequestId,
        runId = packageAuthority.runId,
        workPackageId = packageAuthority.packageId,
        workPackageHash = packageAuthority.hash,
        baseRevision = packageAuthority.repositoryRevision,
        candidateRevision = candidate.revision,
        changedPaths = candidate.changedPaths.sorted(),
        implementationClaims = packageAuthority.expectedBehavior,
        checks = packageAuthority.checks.map { it.command },
        evidence = evidence.filter { it.revision == candidate.revision }.map {
            CandidatePullRequestEvidence(it.kind, it.command, it.passed, it.outputHash, it.summary)
        },
        deviations = deviations,
        hash = "",
    )
    return draft.copy(hash = candidatePullRequestHash(draft))
}

fun candidatePullRequestHash(pullRequest: CandidatePullRequest): String = stagedPlanHash(
    candidatePullRequestJson.encodeToString(pullRequest.copy(hash = ""))
)

private fun validateCandidatePullRequest(pullRequest: CandidatePullRequest, previous: List<CandidatePullRequest>) {
    require(pullRequest.pullRequestId == previous.size + 1L && pullRequest.runId > 0 && pullRequest.workPackageId > 0) {
        "Candidate PR identity is invalid"
    }
    require(pullRequest.workPackageHash.matches(SHA256) && pullRequest.baseRevision.matches(GIT_HASH) &&
        pullRequest.candidateRevision.matches(GIT_HASH)) { "Candidate PR authority is invalid" }
    require(pullRequest.changedPaths.isNotEmpty() && pullRequest.changedPaths.distinct().size == pullRequest.changedPaths.size) {
        "Candidate PR changed paths are invalid"
    }
    require(pullRequest.implementationClaims.isNotEmpty() && pullRequest.checks.isNotEmpty() && pullRequest.evidence.isNotEmpty()) {
        "Candidate PR review content is incomplete"
    }
    require(pullRequest.evidence.all { it.kind.isNotBlank() && it.outputHash.matches(SHA256) && it.summary.isNotBlank() }) {
        "Candidate PR evidence is invalid"
    }
    require(previous.none { it.runId == pullRequest.runId && it.candidateRevision == pullRequest.candidateRevision }) {
        "Candidate PR already exists for this revision"
    }
    pullRequest.parentPullRequestId?.let { parentPullRequestId ->
        require(parentPullRequestId < pullRequest.pullRequestId && previous.any {
            it.pullRequestId == parentPullRequestId && it.runId == pullRequest.runId
        }) { "Candidate PR parent is invalid" }
    }
    require(pullRequest.hash == candidatePullRequestHash(pullRequest)) { "Candidate PR hash is invalid" }
}

@Serializable
private data class CandidatePullRequestEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequest,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val candidatePullRequestJson = Json { encodeDefaults = true }
private val GIT_HASH = Regex("[0-9a-f]{40}")
private val SHA256 = Regex("[0-9a-f]{64}")