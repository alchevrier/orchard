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

const val CANDIDATE_REVIEW_CODE = "CODE"
const val CANDIDATE_REVIEW_INTENT = "INTENT"
const val CANDIDATE_REVIEW_DESIGN = "DESIGN"
const val CANDIDATE_REVIEW_INTEGRATION = "INTEGRATION"

const val CANDIDATE_REVIEW_CONFORMING = "CONFORMING"
const val CANDIDATE_REVIEW_REPAIR_REQUIRED = "REPAIR_REQUIRED"

const val REVIEW_CORRECTION_CANDIDATE_REPAIR = "CANDIDATE_REPAIR"
const val REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE = "WORK_PACKAGE_RECOMPILE"
const val REVIEW_CORRECTION_DESIGN_REVISION = "DESIGN_REVISION"
const val REVIEW_CORRECTION_CLARIFICATION = "CLARIFICATION"
const val REVIEW_CORRECTION_ESCALATION = "ESCALATION"

@Serializable
data class CandidatePullRequestReviewFinding(
    val criterion: String,
    val observation: String,
    val severity: String,
    val correctionTarget: String,
    val evidenceHashes: List<String>,
)

@Serializable
data class CandidatePullRequestReview(
    val reviewId: Long,
    val pullRequestId: Long,
    val pullRequestHash: String,
    val kind: String,
    val reviewer: String,
    val findings: List<CandidatePullRequestReviewFinding>,
    val status: String,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestReviewStore {
    fun load(): List<CandidatePullRequestReview>
    fun appendNext(create: (reviewId: Long) -> CandidatePullRequestReview): CandidatePullRequestReview
}

class TransientCandidatePullRequestReviewStore : CandidatePullRequestReviewStore {
    private val reviews = mutableListOf<CandidatePullRequestReview>()

    @Synchronized
    override fun load(): List<CandidatePullRequestReview> = reviews.toList()

    @Synchronized
    override fun appendNext(create: (reviewId: Long) -> CandidatePullRequestReview): CandidatePullRequestReview {
        val review = create(reviews.size + 1L)
        validateCandidatePullRequestReview(review, reviews)
        reviews += review
        return review
    }
}

class FileCandidatePullRequestReviewStore(private val directory: Path) : CandidatePullRequestReviewStore {
    private val path = directory.resolve("candidate-pull-request-reviews.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-reviews.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestReview> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(create: (reviewId: Long) -> CandidatePullRequestReview): CandidatePullRequestReview {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val reviews = loadUnlocked()
                val review = create(reviews.size + 1L)
                validateCandidatePullRequestReview(review, reviews)
                appendUnlocked(review)
                review
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestReview> = mutableListOf<CandidatePullRequestReview>().also { reviews ->
        loadRecoverableJsonl(path, "candidate-pull-request-reviews") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestReviewEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported candidate PR review format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate PR review $recordNumber"
            }
            validateCandidatePullRequestReview(envelope.value, reviews)
            reviews += envelope.value
            envelope.value
        }
    }

    private fun appendUnlocked(review: CandidatePullRequestReview) {
        val payload = json.encodeToString(review)
        val line = json.encodeToString(
            CandidatePullRequestReviewEnvelope(value = review, checksum = stagedPlanHash(payload)),
        ) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

fun newCandidatePullRequestReview(
    reviewId: Long,
    pullRequest: CandidatePullRequest,
    kind: String,
    reviewer: String,
    findings: List<CandidatePullRequestReviewFinding>,
): CandidatePullRequestReview {
    val status = if (findings.isEmpty()) CANDIDATE_REVIEW_CONFORMING else CANDIDATE_REVIEW_REPAIR_REQUIRED
    val draft = CandidatePullRequestReview(
        reviewId = reviewId,
        pullRequestId = pullRequest.pullRequestId,
        pullRequestHash = pullRequest.hash,
        kind = kind,
        reviewer = reviewer,
        findings = findings,
        status = status,
        hash = "",
    )
    return draft.copy(hash = candidatePullRequestReviewHash(draft))
}

fun candidatePullRequestReviewHash(review: CandidatePullRequestReview): String = stagedPlanHash(
    candidatePullRequestReviewJson.encodeToString(review.copy(hash = "")),
)

private fun validateCandidatePullRequestReview(
    review: CandidatePullRequestReview,
    previous: List<CandidatePullRequestReview>,
) {
    require(review.reviewId == previous.size + 1L && review.pullRequestId > 0 && review.pullRequestHash.matches(SHA256)) {
        "Candidate PR review identity is invalid"
    }
    require(review.kind in REVIEW_KINDS && review.reviewer.isNotBlank() && review.status in REVIEW_STATUSES) {
        "Candidate PR review authority is invalid"
    }
    require((review.status == CANDIDATE_REVIEW_CONFORMING) == review.findings.isEmpty()) {
        "Candidate PR review status does not match findings"
    }
    require(review.findings.all { finding ->
        finding.criterion.isNotBlank() && finding.observation.isNotBlank() && finding.severity in REVIEW_SEVERITIES &&
            finding.correctionTarget in REVIEW_CORRECTION_TARGETS && finding.evidenceHashes.isNotEmpty() &&
            finding.evidenceHashes.all { it.matches(SHA256) }
    }) { "Candidate PR review findings are invalid" }
    require(previous.none { it.pullRequestId == review.pullRequestId && it.kind == review.kind }) {
        "Candidate PR already has this review kind"
    }
    require(review.hash == candidatePullRequestReviewHash(review)) { "Candidate PR review hash is invalid" }
}

@Serializable
private data class CandidatePullRequestReviewEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequestReview,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val candidatePullRequestReviewJson = Json { encodeDefaults = true }
private val REVIEW_KINDS = setOf(
    CANDIDATE_REVIEW_CODE,
    CANDIDATE_REVIEW_INTENT,
    CANDIDATE_REVIEW_DESIGN,
    CANDIDATE_REVIEW_INTEGRATION,
)
private val REVIEW_STATUSES = setOf(CANDIDATE_REVIEW_CONFORMING, CANDIDATE_REVIEW_REPAIR_REQUIRED)
private val REVIEW_SEVERITIES = setOf("INFO", "WARNING", "BLOCKER")
internal val REVIEW_CORRECTION_TARGETS = setOf(
    REVIEW_CORRECTION_CANDIDATE_REPAIR,
    REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
    REVIEW_CORRECTION_DESIGN_REVISION,
    REVIEW_CORRECTION_CLARIFICATION,
    REVIEW_CORRECTION_ESCALATION,
)
private val SHA256 = Regex("[0-9a-f]{64}")