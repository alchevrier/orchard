package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EvidenceRequirement(
    val kind: String,
    val description: String,
)

@Serializable
data class EvidenceContract(
    val id: String,
    val version: Int,
    val requirements: List<EvidenceRequirement>,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ResolvedWorkflow(
    val id: String,
    val version: Int,
    val workItemType: Int,
    val steps: List<String>,
    val evidenceContract: EvidenceContract,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stepDefinitions: List<WorkflowStepDefinition> = emptyList(),
)

@Serializable
data class RepositoryHead(
    val projectId: Int,
    val path: String,
    val commitHash: String,
    val branch: String,
    val remote: String,
    val clean: Boolean,
)

@Serializable
data class DispatchWorkspaceReservation(
    val mode: String,
    val owner: String,
    val path: String,
    val branch: String,
    val baseRevision: String,
)

@Serializable
data class WorkEpisode(
    val episodeId: Long,
    val projectId: Int,
    val workItemType: Int,
    val workflowId: String,
    val title: String,
    val problem: String,
    val failedApproaches: List<String>,
    val resolution: String,
    val evidenceSummary: String,
    val sourceRevision: String,
    val recordedAt: String = Instant.now().toString(),
)

@Serializable
data class EpisodeRecall(
    val episodeId: Long,
    val score: Int,
    val problem: String,
    val failedApproaches: List<String>,
    val resolution: String,
    val evidenceSummary: String,
    val sourceRevision: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ContextManifest(
    val projectId: Int,
    val epicId: Int,
    val storyId: Int,
    val workItemId: Int,
    val workItemType: Int,
    val title: String,
    val content: String,
    val workflowId: String,
    val workflowVersion: Int,
    val repository: RepositoryHead,
    val recalledEpisodes: List<EpisodeRecall>,
    val hash: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val circuitDispatchId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val workspaceReservation: DispatchWorkspaceReservation? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class WorkflowRun(
    val runId: Long,
    val createdAt: String,
    val state: String,
    val context: ContextManifest,
    val workflow: ResolvedWorkflow,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val workDefinition: WorkDefinitionManifest? = null,
)

@Serializable
data class EvidenceRecord(
    val evidenceId: Long,
    val kind: String,
    val revision: String,
    val command: String,
    val exitCode: Int,
    val outputHash: String,
    val summary: String,
    val producer: String,
    val passed: Boolean,
    val recordedAt: String,
)

@Serializable
data class AttemptRecord(
    val attemptId: Long,
    val description: String,
    val outcome: String,
    val diagnosticHash: String,
    val successful: Boolean,
    val recordedAt: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class TransitionDecision(
    val decisionId: Long,
    val fromState: String,
    val toState: String,
    val accepted: Boolean,
    val reason: String,
    val evidenceIds: List<Long>,
    val decidedAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val signal: String = "",
)

@Serializable
data class WorkflowEvent(
    val eventId: Long,
    val runId: Long,
    val evidence: EvidenceRecord? = null,
    val attempt: AttemptRecord? = null,
    val decision: TransitionDecision? = null,
    val producedEpisode: WorkEpisode? = null,
)

@Serializable
data class WorkflowRunView(
    val runId: Long,
    val createdAt: String,
    val state: String,
    val context: ContextManifest,
    val workflow: ResolvedWorkflow,
    val evidence: List<EvidenceRecord>,
    val attempts: List<AttemptRecord>,
    val decisions: List<TransitionDecision>,
    val workDefinition: WorkDefinitionManifest? = null,
)

interface WorkflowMemoryStore {
    fun loadRuns(): List<WorkflowRun>
    fun appendRun(run: WorkflowRun)
    fun loadEvents(): List<WorkflowEvent>
    fun appendEvent(event: WorkflowEvent)
    fun recallEpisodes(query: EpisodeQuery): List<EpisodeRecall>
    fun appendEpisode(episode: WorkEpisode)
    fun nextEpisodeId(): Long
}

data class EpisodeQuery(
    val projectId: Int,
    val workItemType: Int,
    val workflowId: String,
    val title: String,
    val content: String,
)

@Serializable
data class EvidenceSubmission(
    val kind: String,
    val revision: String,
    val command: String,
    val exitCode: Int,
    val outputHash: String,
    val summary: String,
    val producer: String,
)

@Serializable
data class AttemptSubmission(
    val description: String,
    val outcome: String,
    val diagnosticHash: String,
    val successful: Boolean,
)

class TransientWorkflowMemoryStore : WorkflowMemoryStore {
    private val runs = mutableListOf<WorkflowRun>()
    private val episodes = mutableListOf<WorkEpisode>()
    private val events = mutableListOf<WorkflowEvent>()

    @Synchronized
    override fun loadRuns(): List<WorkflowRun> = runs.toList()

    @Synchronized
    override fun appendRun(run: WorkflowRun) {
        runs += run
    }

    @Synchronized
    override fun loadEvents(): List<WorkflowEvent> = events.toList()

    @Synchronized
    override fun appendEvent(event: WorkflowEvent) {
        events += event
    }

    @Synchronized
    override fun recallEpisodes(query: EpisodeQuery): List<EpisodeRecall> =
        recall(episodes + events.mapNotNull { it.producedEpisode }, query)

    @Synchronized
    override fun appendEpisode(episode: WorkEpisode) {
        episodes += episode
    }

    @Synchronized
    override fun nextEpisodeId(): Long = (episodes + events.mapNotNull { it.producedEpisode })
        .maxOfOrNull { it.episodeId }
        ?.plus(1) ?: 1L
}

class FileWorkflowMemoryStore(private val directory: Path) : WorkflowMemoryStore {
    private val runsPath = directory.resolve("workflow-runs.jsonl")
    private val episodesPath = directory.resolve("work-episodes.jsonl")
    private val eventsPath = directory.resolve("workflow-events.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadRuns(): List<WorkflowRun> = readRecords<WorkflowRun>(runsPath).also { runs ->
        runs.forEachIndexed { index, run ->
            require(run.runId == index + 1L) { "Expected workflow run ID ${index + 1L}, found ${run.runId}" }
            require(run.state == RUN_STATE_CONTEXT_READY) { "Unsupported workflow run state ${run.state}" }
            require(run.context.repository.commitHash.matches(GIT_HASH)) { "Invalid repository revision in run ${run.runId}" }
            require(run.context.hash == manifestHash(run.context.copy(hash = ""))) {
                "Context manifest checksum mismatch in run ${run.runId}"
            }
        }
    }

    @Synchronized
    override fun appendRun(run: WorkflowRun) {
        val existing = readRecords<WorkflowRun>(runsPath)
        require(run.runId == existing.size + 1L) { "Workflow run IDs must be monotonic" }
        append(runsPath, run)
    }

    @Synchronized
    override fun loadEvents(): List<WorkflowEvent> = readRecords<WorkflowEvent>(eventsPath).also { events ->
        events.forEachIndexed { index, event ->
            require(event.eventId == index + 1L) { "Expected workflow event ID ${index + 1L}, found ${event.eventId}" }
            require(listOfNotNull(event.evidence, event.attempt, event.decision).isNotEmpty()) {
                "Workflow event ${event.eventId} has no mutation"
            }
            event.evidence?.let { validateEvidence(it, event.eventId) }
            event.attempt?.let { validateAttempt(it, event.eventId) }
            event.decision?.let { validateDecision(it, event.eventId) }
            event.producedEpisode?.let(::validateEpisode)
        }
    }

    @Synchronized
    override fun appendEvent(event: WorkflowEvent) {
        val existing = loadEvents()
        require(event.eventId == existing.size + 1L) { "Workflow event IDs must be monotonic" }
        event.evidence?.let { validateEvidence(it, event.eventId) }
        event.attempt?.let { validateAttempt(it, event.eventId) }
        event.decision?.let { validateDecision(it, event.eventId) }
        event.producedEpisode?.let(::validateEpisode)
        append(eventsPath, event)
    }

    @Synchronized
    override fun recallEpisodes(query: EpisodeQuery): List<EpisodeRecall> =
        recall(readEpisodes() + loadEvents().mapNotNull { it.producedEpisode }, query)

    @Synchronized
    override fun appendEpisode(episode: WorkEpisode) {
        validateEpisode(episode)
        val existing = readEpisodes()
        require(episode.episodeId == existing.size + 1L) { "Work episode IDs must be monotonic" }
        append(episodesPath, episode)
    }

    @Synchronized
    override fun nextEpisodeId(): Long = (readEpisodes() + loadEvents().mapNotNull { it.producedEpisode })
        .maxOfOrNull { it.episodeId }
        ?.plus(1) ?: 1L

    private fun readEpisodes(): List<WorkEpisode> = readRecords<WorkEpisode>(episodesPath).also { episodes ->
        episodes.forEachIndexed { index, episode ->
            require(episode.episodeId == index + 1L) { "Expected work episode ID ${index + 1L}, found ${episode.episodeId}" }
            validateEpisode(episode)
        }
    }

    private fun validateEpisode(episode: WorkEpisode) {
        require(episode.projectId > 0) { "Work episode project ID must be positive" }
        require(episode.workItemType == ENTITY_TASK || episode.workItemType == ENTITY_BUG) {
            "Work episodes must describe tasks or bugs"
        }
        require(episode.workflowId.isNotBlank()) { "Work episode workflow ID is required" }
        require(episode.problem.isNotBlank()) { "Work episode problem is required" }
        require(episode.resolution.isNotBlank()) { "Work episode resolution is required" }
        require(episode.sourceRevision.matches(GIT_HASH)) { "Work episode source revision is invalid" }
    }

    private fun validateEvidence(evidence: EvidenceRecord, eventId: Long) {
        require(evidence.evidenceId == eventId) { "Evidence ID must match its event ID" }
        require(evidence.kind.isNotBlank()) { "Evidence kind is required" }
        require(evidence.revision.matches(GIT_HASH)) { "Evidence revision is invalid" }
        require(evidence.outputHash.matches(SHA256)) { "Evidence output hash is invalid" }
        require(evidence.producer.isNotBlank()) { "Evidence producer is required" }
    }

    private fun validateAttempt(attempt: AttemptRecord, eventId: Long) {
        require(attempt.attemptId == eventId) { "Attempt ID must match its event ID" }
        require(attempt.description.isNotBlank()) { "Attempt description is required" }
        require(attempt.outcome.isNotBlank()) { "Attempt outcome is required" }
        require(attempt.diagnosticHash.matches(SHA256)) { "Attempt diagnostic hash is invalid" }
    }

    private fun validateDecision(decision: TransitionDecision, eventId: Long) {
        require(decision.decisionId == eventId) { "Decision ID must match its event ID" }
        require(decision.fromState.isNotBlank() && decision.toState.isNotBlank()) { "Decision states are required" }
        require(decision.reason.isNotBlank()) { "Decision reason is required" }
    }

    private inline fun <reified T> readRecords(path: Path): List<T> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path, Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                val envelope = runCatching { json.decodeFromString<RecordEnvelope<T>>(line) }
                    .getOrElse { throw IllegalStateException("Cannot decode ${path.fileName} record ${index + 1}", it) }
                require(envelope.version == FORMAT_VERSION) { "Unsupported ${path.fileName} version ${envelope.version}" }
                val payload = json.encodeToString(envelope.value)
                require(envelope.checksum == sha256(payload)) { "Checksum mismatch in ${path.fileName} record ${index + 1}" }
                envelope.value
            }
    }

    private inline fun <reified T> append(path: Path, value: T) {
        Files.createDirectories(directory)
        val payload = json.encodeToString(value)
        val line = json.encodeToString(RecordEnvelope(value = value, checksum = sha256(payload))) + "\n"
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        val GIT_HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

@Serializable
private data class RecordEnvelope<T>(
    val version: Int = 1,
    val value: T,
    val checksum: String,
)

const val RUN_STATE_CONTEXT_READY = "CONTEXT_READY"
const val RUN_STATE_EVIDENCE_PENDING = "EVIDENCE_PENDING"
const val RUN_STATE_EVIDENCE_BLOCKED = "EVIDENCE_BLOCKED"
const val RUN_STATE_DONE = "DONE"
const val RUN_STATE_CANCELLED = "CANCELLED"

fun manifestHash(manifest: ContextManifest): String = sha256(
    manifestJson.encodeToString(manifest.copy(hash = ""))
)

private fun recall(episodes: List<WorkEpisode>, query: EpisodeQuery): List<EpisodeRecall> {
    val queryTokens = tokens("${query.title} ${query.content}")
    if (queryTokens.isEmpty()) return emptyList()
    return episodes.asSequence()
        .filter {
            it.projectId == query.projectId &&
                it.workItemType == query.workItemType &&
                it.workflowId == query.workflowId
        }
        .map { episode -> episode to similarity(queryTokens, tokens("${episode.title} ${episode.problem} ${episode.resolution}")) }
        .filter { (_, score) -> score >= MIN_RECALL_SCORE }
        .sortedWith(compareByDescending<Pair<WorkEpisode, Int>> { it.second }.thenBy { it.first.episodeId })
        .take(MAX_RECALLED_EPISODES)
        .map { (episode, score) ->
            EpisodeRecall(
                episodeId = episode.episodeId,
                score = score,
                problem = episode.problem,
                failedApproaches = episode.failedApproaches,
                resolution = episode.resolution,
                evidenceSummary = episode.evidenceSummary,
                sourceRevision = episode.sourceRevision,
            )
        }
        .toList()
}

private fun tokens(value: String): Set<String> = TOKEN.findAll(value.lowercase())
    .map { it.value }
    .filter { it.length > 2 }
    .toSet()

private fun similarity(left: Set<String>, right: Set<String>): Int {
    if (right.isEmpty()) return 0
    return (100 * left.intersect(right).size) / left.union(right).size
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val TOKEN = Regex("[a-z0-9]+")
private val manifestJson = Json { encodeDefaults = true }
private const val MIN_RECALL_SCORE = 15
private const val MAX_RECALLED_EPISODES = 3