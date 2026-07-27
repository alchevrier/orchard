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

@Serializable
data class CandidatePullRequestCorrection(
    val correctionId: Long,
    val reviewId: Long,
    val reviewHash: String,
    val pullRequestId: Long,
    val pullRequestHash: String,
    val runId: Long,
    val workPackageId: Long,
    val workPackageHash: String,
    val correctionTarget: String,
    val findings: List<CandidatePullRequestReviewFinding>,
    val compiledAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestCorrectionStore {
    fun load(): List<CandidatePullRequestCorrection>
    fun appendNext(create: (correctionId: Long) -> CandidatePullRequestCorrection): CandidatePullRequestCorrection
}

class TransientCandidatePullRequestCorrectionStore : CandidatePullRequestCorrectionStore {
    private val corrections = mutableListOf<CandidatePullRequestCorrection>()

    @Synchronized
    override fun load(): List<CandidatePullRequestCorrection> = corrections.toList()

    @Synchronized
    override fun appendNext(create: (correctionId: Long) -> CandidatePullRequestCorrection): CandidatePullRequestCorrection {
        val correction = create(corrections.size + 1L)
        validateCandidatePullRequestCorrection(correction, corrections)
        corrections += correction
        return correction
    }
}

class FileCandidatePullRequestCorrectionStore(private val directory: Path) : CandidatePullRequestCorrectionStore {
    private val path = directory.resolve("candidate-pull-request-corrections.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-corrections.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestCorrection> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(create: (correctionId: Long) -> CandidatePullRequestCorrection): CandidatePullRequestCorrection {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val corrections = loadUnlocked()
                val correction = create(corrections.size + 1L)
                validateCandidatePullRequestCorrection(correction, corrections)
                appendUnlocked(correction)
                correction
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestCorrection> = mutableListOf<CandidatePullRequestCorrection>().also { corrections ->
        loadRecoverableJsonl(path, "candidate-pull-request-corrections") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestCorrectionEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported candidate PR correction format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate PR correction $recordNumber"
            }
            validateCandidatePullRequestCorrection(envelope.value, corrections)
            corrections += envelope.value
            envelope.value
        }
    }

    private fun appendUnlocked(correction: CandidatePullRequestCorrection) {
        val payload = json.encodeToString(correction)
        val line = json.encodeToString(
            CandidatePullRequestCorrectionEnvelope(value = correction, checksum = stagedPlanHash(payload)),
        ) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

fun compileCandidatePullRequestCorrections(
    correctionId: Long,
    review: CandidatePullRequestReview,
    pullRequest: CandidatePullRequest,
): List<CandidatePullRequestCorrection> = review.findings
    .groupBy { it.correctionTarget }
    .toSortedMap()
    .entries
    .mapIndexed { index, (correctionTarget, findings) ->
        newCandidatePullRequestCorrection(correctionId + index, review, pullRequest, correctionTarget, findings)
    }

fun newCandidatePullRequestCorrection(
    correctionId: Long,
    review: CandidatePullRequestReview,
    pullRequest: CandidatePullRequest,
    correctionTarget: String,
    findings: List<CandidatePullRequestReviewFinding>,
): CandidatePullRequestCorrection {
    val draft = CandidatePullRequestCorrection(
        correctionId = correctionId,
        reviewId = review.reviewId,
        reviewHash = review.hash,
        pullRequestId = pullRequest.pullRequestId,
        pullRequestHash = pullRequest.hash,
        runId = pullRequest.runId,
        workPackageId = pullRequest.workPackageId,
        workPackageHash = pullRequest.workPackageHash,
        correctionTarget = correctionTarget,
        findings = findings,
        hash = "",
    )
    return draft.copy(hash = candidatePullRequestCorrectionHash(draft))
}

fun candidatePullRequestCorrectionHash(correction: CandidatePullRequestCorrection): String = stagedPlanHash(
    candidatePullRequestCorrectionJson.encodeToString(correction.copy(hash = "")),
)

private fun validateCandidatePullRequestCorrection(
    correction: CandidatePullRequestCorrection,
    previous: List<CandidatePullRequestCorrection>,
) {
    require(correction.correctionId == previous.size + 1L && correction.reviewId > 0 && correction.pullRequestId > 0 &&
        correction.runId > 0 && correction.workPackageId > 0) { "Candidate PR correction identity is invalid" }
    require(listOf(correction.reviewHash, correction.pullRequestHash, correction.workPackageHash).all { it.matches(SHA256) }) {
        "Candidate PR correction authority is invalid"
    }
    require(correction.correctionTarget in REVIEW_CORRECTION_TARGETS && correction.findings.isNotEmpty() &&
        correction.findings.all { it.correctionTarget == correction.correctionTarget }
    ) { "Candidate PR correction findings are invalid" }
    require(previous.none { it.reviewId == correction.reviewId && it.correctionTarget == correction.correctionTarget }) {
        "Candidate PR correction already exists for this review target"
    }
    require(correction.hash == candidatePullRequestCorrectionHash(correction)) { "Candidate PR correction hash is invalid" }
}

@Serializable
private data class CandidatePullRequestCorrectionEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequestCorrection,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val candidatePullRequestCorrectionJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")