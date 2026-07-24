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

const val CODING_ATTEMPT_BLOCKED = "BLOCKED"
const val CODING_ATTEMPT_RETRY_AUTHORIZED = "RETRY_AUTHORIZED"
const val CODING_ATTEMPT_RETRY_CONSUMED = "RETRY_CONSUMED"
const val CODING_ATTEMPT_SCOPE_ACCEPTED = "SCOPE_ACCEPTED"

@Serializable
data class CodingWorkerAttempt(
    val attemptId: Long,
    val runId: Long,
    val executionPlanId: Long,
    val executionPlanHash: String,
    val state: String,
    val resultStatus: String,
    val diagnostic: String,
    val proposalHash: String? = null,
    val recordedAt: String = Instant.now().toString(),
)

interface CodingWorkerAttemptStore {
    fun load(): List<CodingWorkerAttempt>
    fun appendNext(create: (attemptId: Long) -> CodingWorkerAttempt): CodingWorkerAttempt
}

class TransientCodingWorkerAttemptStore : CodingWorkerAttemptStore {
    private val attempts = mutableListOf<CodingWorkerAttempt>()

    @Synchronized
    override fun load(): List<CodingWorkerAttempt> = attempts.toList()

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> CodingWorkerAttempt): CodingWorkerAttempt {
        val attempt = create(attempts.size + 1L)
        validateCodingWorkerAttempt(attempt, attempts)
        attempts += attempt
        return attempt
    }
}

class FileCodingWorkerAttemptStore(private val directory: Path) : CodingWorkerAttemptStore {
    private val path = directory.resolve("coding-worker-attempts.jsonl")
    private val lockPath = directory.resolve("coding-worker-attempts.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CodingWorkerAttempt> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<CodingWorkerAttempt> = mutableListOf<CodingWorkerAttempt>().also { attempts ->
        loadRecoverableJsonl(path, "coding-worker-attempts") { line, recordNumber ->
            val envelope = json.decodeFromString<CodingWorkerAttemptEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported coding worker attempt format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in coding worker attempt $recordNumber"
            }
            validateCodingWorkerAttempt(envelope.value, attempts)
            attempts += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> CodingWorkerAttempt): CodingWorkerAttempt {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val attempts = loadUnlocked()
                val attempt = create(attempts.size + 1L)
                validateCodingWorkerAttempt(attempt, attempts)
                val payload = json.encodeToString(attempt)
                val line = json.encodeToString(
                    CodingWorkerAttemptEnvelope(value = attempt, checksum = stagedPlanHash(payload))
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

fun CodingWorkerAttemptStore.latestAttempt(
    runId: Long,
    executionPlanId: Long,
    executionPlanHash: String,
): CodingWorkerAttempt? = load().lastOrNull {
    it.runId == runId && it.executionPlanId == executionPlanId && it.executionPlanHash == executionPlanHash
}

fun CodingWorkerAttemptStore.retryDiagnostic(
    runId: Long,
    executionPlanId: Long,
    executionPlanHash: String,
): String? {
    val attempts = load().filter {
        it.runId == runId && it.executionPlanId == executionPlanId && it.executionPlanHash == executionPlanHash
    }
    if (attempts.lastOrNull()?.state != CODING_ATTEMPT_RETRY_AUTHORIZED) return null
    val blockedDiagnostics = attempts.dropLast(1)
        .filter { it.state == CODING_ATTEMPT_BLOCKED }
        .map { it.diagnostic }
    val current = blockedDiagnostics.lastOrNull() ?: return null
    val previous = blockedDiagnostics.distinct().filter { it != current }
    return buildString {
        append("Current coding rejection (")
        append(blockedDiagnostics.count { it == current })
        append(" occurrence")
        if (blockedDiagnostics.count { it == current } != 1) append('s')
        append("): ").append(current)
        if (previous.isNotEmpty()) {
            append("\nPreviously rejected coding defects that must remain fixed:")
            previous.forEach { append("\n- ").append(it) }
        }
    }
}

private fun validateCodingWorkerAttempt(
    attempt: CodingWorkerAttempt,
    previous: List<CodingWorkerAttempt>,
) {
    require(attempt.attemptId == previous.size + 1L) { "Coding worker attempt ID is not monotonic" }
    require(attempt.runId > 0 && attempt.executionPlanId > 0 && attempt.executionPlanHash.matches(Regex("[0-9a-f]{64}"))) {
        "Coding worker attempt identity is invalid"
    }
    require(
        attempt.state in setOf(
            CODING_ATTEMPT_BLOCKED,
            CODING_ATTEMPT_RETRY_AUTHORIZED,
            CODING_ATTEMPT_RETRY_CONSUMED,
            CODING_ATTEMPT_SCOPE_ACCEPTED,
        )
    ) { "Coding worker attempt state is invalid" }
    require(attempt.resultStatus.isNotBlank() && attempt.diagnostic.isNotBlank()) {
        "Coding worker attempt outcome is incomplete"
    }
    require(attempt.proposalHash == null || attempt.proposalHash.matches(Regex("[0-9a-f]{64}"))) {
        "Coding worker attempt proposal hash is invalid"
    }
    val preceding = previous.lastOrNull {
        it.runId == attempt.runId &&
            it.executionPlanId == attempt.executionPlanId &&
            it.executionPlanHash == attempt.executionPlanHash
    }
    when (attempt.state) {
        CODING_ATTEMPT_RETRY_AUTHORIZED -> require(preceding?.state in setOf(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_CONSUMED)) {
            "Coding worker retry requires a blocked or consumed attempt"
        }
        CODING_ATTEMPT_RETRY_CONSUMED -> require(preceding?.state == CODING_ATTEMPT_RETRY_AUTHORIZED) {
            "Coding worker retry consumption requires explicit authorization"
        }
        CODING_ATTEMPT_SCOPE_ACCEPTED -> require(preceding?.state in setOf(CODING_ATTEMPT_RETRY_CONSUMED, CODING_ATTEMPT_SCOPE_ACCEPTED)) {
            "Coding worker scope acceptance requires a consumed retry or prior accepted scope"
        }
    }
}

@Serializable
private data class CodingWorkerAttemptEnvelope(
    val version: Int = 1,
    val value: CodingWorkerAttempt,
    val checksum: String,
)