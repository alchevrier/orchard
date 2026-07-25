package com.orchard.backend.company

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

const val AUDIT_ATTEMPT_BLOCKED = "BLOCKED"
const val AUDIT_ATTEMPT_RETRY_AUTHORIZED = "RETRY_AUTHORIZED"
const val AUDIT_ATTEMPT_RETRY_CONSUMED = "RETRY_CONSUMED"

@Serializable
data class CompanyAuditAttempt(
    val attemptId: Long,
    val runId: Long,
    val role: String,
    val candidateRevision: String,
    val candidateDiffHash: String,
    val state: String,
    val diagnostic: String,
    val recordedAt: String = Instant.now().toString(),
)

interface CompanyAuditAttemptStore {
    fun load(): List<CompanyAuditAttempt>
    fun appendNext(create: (attemptId: Long) -> CompanyAuditAttempt): CompanyAuditAttempt
}

class TransientCompanyAuditAttemptStore : CompanyAuditAttemptStore {
    private val attempts = mutableListOf<CompanyAuditAttempt>()

    @Synchronized
    override fun load(): List<CompanyAuditAttempt> = attempts.toList()

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> CompanyAuditAttempt): CompanyAuditAttempt {
        val attempt = create(attempts.size + 1L)
        validateCompanyAuditAttempt(attempt, attempts)
        attempts += attempt
        return attempt
    }
}

class FileCompanyAuditAttemptStore(private val directory: Path) : CompanyAuditAttemptStore {
    private val path = directory.resolve("company-audit-attempts.jsonl")
    private val lockPath = directory.resolve("company-audit-attempts.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CompanyAuditAttempt> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<CompanyAuditAttempt> = mutableListOf<CompanyAuditAttempt>().also { attempts ->
        loadRecoverableJsonl(path, "company-audit-attempts") { line, recordNumber ->
            val envelope = json.decodeFromString<CompanyAuditAttemptEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported company audit attempt format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in company audit attempt $recordNumber"
            }
            validateCompanyAuditAttempt(envelope.value, attempts)
            attempts += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (attemptId: Long) -> CompanyAuditAttempt): CompanyAuditAttempt {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val attempts = loadUnlocked()
                val attempt = create(attempts.size + 1L)
                validateCompanyAuditAttempt(attempt, attempts)
                val payload = json.encodeToString(attempt)
                val line = json.encodeToString(
                    CompanyAuditAttemptEnvelope(value = attempt, checksum = stagedPlanHash(payload))
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

fun CompanyAuditAttemptStore.attemptsFor(
    runId: Long,
    role: String,
    candidateRevision: String,
    candidateDiffHash: String,
): List<CompanyAuditAttempt> = load().filter {
    it.runId == runId && it.role == role && it.candidateRevision == candidateRevision &&
        it.candidateDiffHash == candidateDiffHash
}

private fun validateCompanyAuditAttempt(
    attempt: CompanyAuditAttempt,
    previous: List<CompanyAuditAttempt>,
) {
    require(attempt.attemptId == previous.size + 1L) { "Company audit attempt ID is not monotonic" }
    require(
        attempt.runId > 0 && attempt.role in setOf(ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR) &&
            attempt.candidateRevision.matches(Regex("[0-9a-f]{40}")) &&
            attempt.candidateDiffHash.matches(Regex("[0-9a-f]{64}"))
    ) { "Company audit attempt identity is invalid" }
    require(attempt.state in setOf(AUDIT_ATTEMPT_BLOCKED, AUDIT_ATTEMPT_RETRY_AUTHORIZED, AUDIT_ATTEMPT_RETRY_CONSUMED)) {
        "Company audit attempt state is invalid"
    }
    require(attempt.diagnostic.isNotBlank()) { "Company audit attempt diagnostic is blank" }
    val preceding = previous.lastOrNull {
        it.runId == attempt.runId && it.role == attempt.role &&
            it.candidateRevision == attempt.candidateRevision && it.candidateDiffHash == attempt.candidateDiffHash
    }
    when (attempt.state) {
        AUDIT_ATTEMPT_RETRY_AUTHORIZED -> require(preceding?.state == AUDIT_ATTEMPT_BLOCKED) {
            "Company audit retry requires a blocked attempt"
        }
        AUDIT_ATTEMPT_RETRY_CONSUMED -> require(preceding?.state == AUDIT_ATTEMPT_RETRY_AUTHORIZED) {
            "Company audit retry consumption requires authorization"
        }
    }
}

@Serializable
private data class CompanyAuditAttemptEnvelope(
    val version: Int = 1,
    val value: CompanyAuditAttempt,
    val checksum: String,
)