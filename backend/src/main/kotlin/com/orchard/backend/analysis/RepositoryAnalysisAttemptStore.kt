package com.orchard.backend.analysis

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

const val ANALYSIS_ATTEMPT_BLOCKED = "BLOCKED"
const val ANALYSIS_ATTEMPT_RETRY_AUTHORIZED = "RETRY_AUTHORIZED"

@Serializable
data class RepositoryAnalysisAttempt(
    val attemptId: Long,
    val runId: Long,
    val baseRevision: String,
    val state: String,
    val resultStatus: String,
    val diagnostic: String,
    val promptHash: String? = null,
    val recordedAt: String = Instant.now().toString(),
)

interface RepositoryAnalysisAttemptStore {
    fun load(): List<RepositoryAnalysisAttempt>
    fun appendNext(create: (attemptId: Long) -> RepositoryAnalysisAttempt): RepositoryAnalysisAttempt
}

class TransientRepositoryAnalysisAttemptStore : RepositoryAnalysisAttemptStore {
    private val attempts = mutableListOf<RepositoryAnalysisAttempt>()

    @Synchronized
    override fun load(): List<RepositoryAnalysisAttempt> = attempts.toList()

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> RepositoryAnalysisAttempt): RepositoryAnalysisAttempt {
        val attempt = create(attempts.size + 1L)
        validateRepositoryAnalysisAttempt(attempt, attempts)
        attempts += attempt
        return attempt
    }
}

class FileRepositoryAnalysisAttemptStore(private val directory: Path) : RepositoryAnalysisAttemptStore {
    private val path = directory.resolve("repository-analysis-attempts.jsonl")
    private val lockPath = directory.resolve("repository-analysis-attempts.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<RepositoryAnalysisAttempt> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<RepositoryAnalysisAttempt> = mutableListOf<RepositoryAnalysisAttempt>().also { attempts ->
        com.orchard.backend.workspace.loadRecoverableJsonl(path, "repository-analysis-attempts") { line, recordNumber ->
            val envelope = json.decodeFromString<RepositoryAnalysisAttemptEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported repository analysis attempt format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in repository analysis attempt $recordNumber"
            }
            validateRepositoryAnalysisAttempt(envelope.value, attempts)
            attempts += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> RepositoryAnalysisAttempt): RepositoryAnalysisAttempt {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val attempts = loadUnlocked()
                val attempt = create(attempts.size + 1L)
                validateRepositoryAnalysisAttempt(attempt, attempts)
                val payload = json.encodeToString(attempt)
                val line = json.encodeToString(
                    RepositoryAnalysisAttemptEnvelope(value = attempt, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                attempt
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun RepositoryAnalysisAttemptStore.isBlocked(runId: Long, baseRevision: String): Boolean =
    blockedAttempt(runId, baseRevision) != null

fun RepositoryAnalysisAttemptStore.blockedAttempt(runId: Long, baseRevision: String): RepositoryAnalysisAttempt? =
    load().lastOrNull { it.runId == runId && it.baseRevision == baseRevision }
        ?.takeIf { it.state == ANALYSIS_ATTEMPT_BLOCKED }

private fun validateRepositoryAnalysisAttempt(
    attempt: RepositoryAnalysisAttempt,
    previous: List<RepositoryAnalysisAttempt>,
) {
    require(attempt.attemptId == previous.size + 1L) { "Repository analysis attempt ID is not monotonic" }
    require(attempt.runId > 0 && attempt.baseRevision.matches(Regex("[0-9a-f]{40}"))) {
        "Repository analysis attempt identity is invalid"
    }
    require(attempt.state in setOf(ANALYSIS_ATTEMPT_BLOCKED, ANALYSIS_ATTEMPT_RETRY_AUTHORIZED)) {
        "Repository analysis attempt state is invalid"
    }
    require(attempt.resultStatus.isNotBlank() && attempt.diagnostic.isNotBlank()) {
        "Repository analysis attempt outcome is incomplete"
    }
    require(attempt.promptHash == null || attempt.promptHash.matches(Regex("[0-9a-f]{64}"))) {
        "Repository analysis attempt prompt hash is invalid"
    }
    if (attempt.state == ANALYSIS_ATTEMPT_RETRY_AUTHORIZED) {
        require(previous.lastOrNull { it.runId == attempt.runId && it.baseRevision == attempt.baseRevision }?.state == ANALYSIS_ATTEMPT_BLOCKED) {
            "Repository analysis retry requires a blocked attempt"
        }
    }
}

@Serializable
private data class RepositoryAnalysisAttemptEnvelope(
    val version: Int = 1,
    val value: RepositoryAnalysisAttempt,
    val checksum: String,
)