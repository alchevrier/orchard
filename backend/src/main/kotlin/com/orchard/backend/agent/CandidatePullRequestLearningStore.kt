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
data class CandidatePullRequestLearningEpisode(
    val episodeId: Long,
    val pullRequestId: Long,
    val pullRequestHash: String,
    val runId: Long,
    val candidateRevision: String,
    val workPackageHash: String,
    val dispositionHash: String,
    val outcome: String,
    val reviewHashes: List<String>,
    val correctionHashes: List<String>,
    val observations: List<String>,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestLearningStore {
    fun load(): List<CandidatePullRequestLearningEpisode>
    fun appendNext(create: (episodeId: Long) -> CandidatePullRequestLearningEpisode): CandidatePullRequestLearningEpisode
}

class TransientCandidatePullRequestLearningStore : CandidatePullRequestLearningStore {
    private val episodes = mutableListOf<CandidatePullRequestLearningEpisode>()

    @Synchronized
    override fun load(): List<CandidatePullRequestLearningEpisode> = episodes.toList()

    @Synchronized
    override fun appendNext(create: (episodeId: Long) -> CandidatePullRequestLearningEpisode): CandidatePullRequestLearningEpisode {
        val episode = create(episodes.size + 1L)
        validateCandidatePullRequestLearningEpisode(episode, episodes)
        episodes += episode
        return episode
    }
}

class FileCandidatePullRequestLearningStore(private val directory: Path) : CandidatePullRequestLearningStore {
    private val path = directory.resolve("candidate-pull-request-learning.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-learning.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestLearningEpisode> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(create: (episodeId: Long) -> CandidatePullRequestLearningEpisode): CandidatePullRequestLearningEpisode {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val episodes = loadUnlocked()
                val episode = create(episodes.size + 1L)
                validateCandidatePullRequestLearningEpisode(episode, episodes)
                val payload = json.encodeToString(episode)
                val line = json.encodeToString(CandidatePullRequestLearningEnvelope(episode, stagedPlanHash(payload))) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                episode
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestLearningEpisode> = mutableListOf<CandidatePullRequestLearningEpisode>().also { episodes ->
        loadRecoverableJsonl(path, "candidate-pull-request-learning") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestLearningEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported candidate learning format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate learning episode $recordNumber"
            }
            validateCandidatePullRequestLearningEpisode(envelope.value, episodes)
            episodes += envelope.value
            envelope.value
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

class CandidatePullRequestLearningService(
    private val pullRequestStore: CandidatePullRequestStore,
    private val reviewStore: CandidatePullRequestReviewStore,
    private val correctionStore: CandidatePullRequestCorrectionStore,
    private val dispositionStore: CandidatePullRequestDispositionStore,
    private val learningStore: CandidatePullRequestLearningStore = TransientCandidatePullRequestLearningStore(),
) {
    fun episodes(): List<CandidatePullRequestLearningEpisode> = learningStore.load()

    @Synchronized
    fun reconcile(): List<CandidatePullRequestLearningEpisode> {
        val pullRequests = pullRequestStore.load().associateBy { it.pullRequestId }
        val reviews = reviewStore.load().groupBy { it.pullRequestId }
        val corrections = correctionStore.load().groupBy { it.pullRequestId }
        val existing = learningStore.load().mapTo(hashSetOf()) { it.dispositionHash }
        return dispositionStore.load().asSequence()
            .filter { it.status in LEARNABLE_CANDIDATE_OUTCOMES && it.hash !in existing }
            .mapNotNull { disposition ->
                val pullRequest = pullRequests[disposition.pullRequestId]
                    ?.takeIf { it.hash == disposition.pullRequestHash }
                    ?: return@mapNotNull null
                learningStore.appendNext { episodeId ->
                    newCandidatePullRequestLearningEpisode(
                        episodeId,
                        pullRequest,
                        disposition,
                        reviews[pullRequest.pullRequestId].orEmpty(),
                        corrections[pullRequest.pullRequestId].orEmpty(),
                    )
                }
            }
            .toList()
    }

    fun recall(query: String, limit: Int = 3): List<CandidatePullRequestLearningEpisode> {
        val terms = learningTerms(query)
        if (terms.isEmpty() || limit <= 0) return emptyList()
        return learningStore.load().asSequence()
            .map { it to terms.intersect(learningTerms(it.observations.joinToString(" "))).size }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<CandidatePullRequestLearningEpisode, Int>> { it.second }.thenBy { it.first.episodeId })
            .take(limit)
            .map { it.first }
            .toList()
    }
}

fun newCandidatePullRequestLearningEpisode(
    episodeId: Long,
    pullRequest: CandidatePullRequest,
    disposition: CandidatePullRequestDisposition,
    reviews: List<CandidatePullRequestReview>,
    corrections: List<CandidatePullRequestCorrection>,
): CandidatePullRequestLearningEpisode {
    val observations = (pullRequest.implementationClaims + reviews.flatMap { review ->
        review.findings.map { "${review.kind}: ${it.criterion}: ${it.observation}" }
    }).distinct().sorted()
    val draft = CandidatePullRequestLearningEpisode(
        episodeId, pullRequest.pullRequestId, pullRequest.hash, pullRequest.runId, pullRequest.candidateRevision,
        pullRequest.workPackageHash, disposition.hash, disposition.status, reviews.map { it.hash }.sorted(),
        corrections.map { it.hash }.sorted(), observations, hash = "",
    )
    return draft.copy(hash = candidatePullRequestLearningHash(draft))
}

fun candidatePullRequestLearningHash(episode: CandidatePullRequestLearningEpisode): String = stagedPlanHash(
    candidatePullRequestLearningJson.encodeToString(episode.copy(hash = "")),
)

private fun validateCandidatePullRequestLearningEpisode(
    episode: CandidatePullRequestLearningEpisode,
    previous: List<CandidatePullRequestLearningEpisode>,
) {
    require(episode.episodeId == previous.size + 1L && episode.pullRequestId > 0 && episode.runId > 0) {
        "Candidate learning episode identity is invalid"
    }
    require(episode.pullRequestHash.matches(SHA256) && episode.workPackageHash.matches(SHA256) &&
        episode.dispositionHash.matches(SHA256) && episode.candidateRevision.matches(GIT_HASH) && episode.hash == candidatePullRequestLearningHash(episode)
    ) { "Candidate learning episode authority is invalid" }
    require(episode.outcome in LEARNABLE_CANDIDATE_OUTCOMES && episode.observations.isNotEmpty() &&
        episode.reviewHashes.all { it.matches(SHA256) } && episode.correctionHashes.all { it.matches(SHA256) } &&
        previous.none { it.dispositionHash == episode.dispositionHash }
    ) { "Candidate learning episode outcome is invalid" }
}

@Serializable
private data class CandidatePullRequestLearningEnvelope(
    val value: CandidatePullRequestLearningEpisode,
    val checksum: String,
    val version: Int = 1,
)

private fun learningTerms(value: String): Set<String> = Regex("[a-z0-9]+")
    .findAll(value.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()

private val LEARNABLE_CANDIDATE_OUTCOMES = setOf(
    CANDIDATE_DISPOSITION_ACCEPTED,
    CANDIDATE_DISPOSITION_BLOCKED,
    CANDIDATE_DISPOSITION_SUPERSEDED,
    CANDIDATE_DISPOSITION_ABANDONED,
)
private val candidatePullRequestLearningJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_HASH = Regex("[0-9a-f]{40}")