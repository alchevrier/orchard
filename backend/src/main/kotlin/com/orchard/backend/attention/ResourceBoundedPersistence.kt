package com.orchard.backend.attention

import com.orchard.backend.workspace.stagedPlanHash
import com.orchard.backend.workspace.loadRecoverableJsonl
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

const val PERSISTENCE_CONTINUE = "CONTINUE"
const val PERSISTENCE_RESOURCE_DEFERRED = "RESOURCE_DEFERRED"
const val PERSISTENCE_DEADLINE_BLOCKED = "DEADLINE_BLOCKED"
const val PERSISTENCE_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
const val PERSISTENCE_RECURRENT_FAILURE = "RECURRENT_FAILURE"
const val PERSISTENCE_MODEL_CAPABILITY_LIMIT = "MODEL_CAPABILITY_LIMIT"
const val PERSISTENCE_SKILL_REQUIRED = "SKILL_REQUIRED"
const val PERSISTENCE_ARCHITECTURE_REQUIRED = "ARCHITECTURE_REQUIRED"
const val PERSISTENCE_HUMAN_DECISION_REQUIRED = "HUMAN_DECISION_REQUIRED"
const val PERSISTENCE_POLICY_BLOCKED = "POLICY_BLOCKED"
const val PERSISTENCE_ABANDONED = "ABANDONED"

@Serializable
data class PersistenceBudget(
    val maxAttempts: Int,
    val maxInputTokens: Long,
    val maxOutputTokens: Long,
    val maxInferenceMillis: Long,
    val maxWallClockMillis: Long,
    val maxEquivalentFailures: Int,
)

@Serializable
data class AttemptBasis(
    val claimId: String,
    val authorityHash: String,
    val repositoryRevision: String,
    val attentionFrameHash: String,
    val skillId: String? = null,
    val methodId: String,
    val failureClass: String? = null,
    val hypothesis: String,
)

@Serializable
data class PersistenceAttemptObservation(
    val basisFingerprint: String,
    val failureClass: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val inferenceMillis: Long,
    val producedEvidence: Boolean,
)

@Serializable
data class PersistenceDecision(
    val outcome: String,
    val diagnostic: String,
    val attemptsUsed: Int,
    val inputTokensUsed: Long,
    val outputTokensUsed: Long,
    val inferenceMillisUsed: Long,
    val basisFingerprint: String,
)

@Serializable
data class PersistenceStopRecord(
    val stopId: Long,
    val runId: Long,
    val claimId: String,
    val authorityHash: String,
    val outcome: String,
    val diagnostic: String,
    val decision: PersistenceDecision,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

interface PersistenceStopStore {
    fun load(): List<PersistenceStopRecord>
    fun appendNext(create: (stopId: Long) -> PersistenceStopRecord): PersistenceStopRecord
}

class TransientPersistenceStopStore : PersistenceStopStore {
    private val stops = mutableListOf<PersistenceStopRecord>()

    @Synchronized
    override fun load(): List<PersistenceStopRecord> = stops.toList()

    @Synchronized
    override fun appendNext(create: (stopId: Long) -> PersistenceStopRecord): PersistenceStopRecord {
        val stop = create(stops.size + 1L)
        validatePersistenceStop(stop, stops)
        stops += stop
        return stop
    }
}

class FilePersistenceStopStore(private val directory: Path) : PersistenceStopStore {
    private val path = directory.resolve("persistence-stops.jsonl")
    private val lockPath = directory.resolve("persistence-stops.lock")
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<PersistenceStopRecord> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(create: (stopId: Long) -> PersistenceStopRecord): PersistenceStopRecord {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val stops = loadUnlocked()
                val stop = create(stops.size + 1L)
                validatePersistenceStop(stop, stops)
                val payload = json.encodeToString(PersistenceStopRecord.serializer(), stop)
                val line = json.encodeToString(PersistenceStopEnvelope.serializer(), PersistenceStopEnvelope(value = stop, checksum = stagedPlanHash(payload))) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                stop
            }
        }
    }

    private fun loadUnlocked(): List<PersistenceStopRecord> = mutableListOf<PersistenceStopRecord>().also { stops ->
        loadRecoverableJsonl(path, "persistence-stops") { line, recordNumber ->
            val envelope = json.decodeFromString(PersistenceStopEnvelope.serializer(), line)
            require(envelope.version == PERSISTENCE_STOP_STORE_VERSION) { "Unsupported persistence stop format ${envelope.version}." }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(PersistenceStopRecord.serializer(), envelope.value))) {
                "Checksum mismatch in persistence stop $recordNumber."
            }
            validatePersistenceStop(envelope.value, stops)
            stops += envelope.value
            envelope.value
        }
    }
}

fun newPersistenceStopRecord(
    stopId: Long,
    runId: Long,
    claimId: String,
    authorityHash: String,
    decision: PersistenceDecision,
): PersistenceStopRecord {
    require(decision.outcome != PERSISTENCE_CONTINUE) { "A continuation decision cannot produce a stop record." }
    val draft = PersistenceStopRecord(
        stopId,
        runId,
        claimId,
        authorityHash,
        decision.outcome,
        decision.diagnostic,
        decision,
        hash = "",
    )
    return draft.copy(hash = persistenceStopHash(draft))
}

fun persistenceStopHash(stop: PersistenceStopRecord): String = stagedPlanHash(
    kotlinx.serialization.json.Json.encodeToString(PersistenceStopRecord.serializer(), stop.copy(hash = "")),
)

fun evaluatePersistence(
    budget: PersistenceBudget,
    workflowStartedAt: String,
    basis: AttemptBasis,
    previous: List<PersistenceAttemptObservation>,
    now: Instant = Instant.now(),
): PersistenceDecision {
    require(budget.maxAttempts > 0 && budget.maxInputTokens > 0 && budget.maxOutputTokens > 0 &&
        budget.maxInferenceMillis > 0 && budget.maxWallClockMillis > 0 && budget.maxEquivalentFailures > 0
    ) { "Persistence budget is invalid." }
    require(basis.claimId.isNotBlank() && basis.authorityHash.matches(SHA256) &&
        basis.repositoryRevision.matches(GIT_REVISION) && basis.attentionFrameHash.matches(SHA256) &&
        basis.methodId.isNotBlank() && basis.hypothesis.isNotBlank()
    ) { "Attempt basis is incomplete." }
    val fingerprint = attemptBasisFingerprint(basis)
    val inputTokens = previous.sumOf { it.inputTokens.toLong() }
    val outputTokens = previous.sumOf { it.outputTokens.toLong() }
    val inferenceMillis = previous.sumOf { it.inferenceMillis }
    fun decision(outcome: String, diagnostic: String) = PersistenceDecision(
        outcome, diagnostic, previous.size, inputTokens, outputTokens, inferenceMillis, fingerprint,
    )
    if (Duration.between(Instant.parse(workflowStartedAt), now).toMillis() >= budget.maxWallClockMillis) {
        return decision(PERSISTENCE_DEADLINE_BLOCKED, "The purpose-linked workflow exceeded its wall-clock budget.")
    }
    if (previous.size >= budget.maxAttempts || inputTokens >= budget.maxInputTokens ||
        outputTokens >= budget.maxOutputTokens || inferenceMillis >= budget.maxInferenceMillis
    ) {
        return decision(PERSISTENCE_BUDGET_EXHAUSTED, "The purpose-linked workflow exhausted its cumulative inference budget.")
    }
    val equivalentFailures = previous.count {
        it.basisFingerprint == fingerprint && it.failureClass == basis.failureClass
    }
    if (equivalentFailures >= budget.maxEquivalentFailures) {
        return decision(
            PERSISTENCE_RECURRENT_FAILURE,
            "A materially equivalent attempt repeated without producing new evidence; architecture or method revision is required.",
        )
    }
    return decision(PERSISTENCE_CONTINUE, "The attempt has remaining budget and no recurrent-failure stop condition.")
}

fun attemptBasisFingerprint(basis: AttemptBasis): String = stagedPlanHash(
    listOf(
        basis.claimId,
        basis.authorityHash,
        basis.repositoryRevision,
        basis.attentionFrameHash,
        basis.skillId.orEmpty(),
        basis.methodId,
        basis.failureClass.orEmpty(),
        basis.hypothesis.trim(),
    ).joinToString("\u0000"),
)

private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_REVISION = Regex("[0-9a-f]{40}")
private const val PERSISTENCE_STOP_STORE_VERSION = 1
private val STOP_OUTCOMES = setOf(
    PERSISTENCE_RESOURCE_DEFERRED,
    PERSISTENCE_DEADLINE_BLOCKED,
    PERSISTENCE_BUDGET_EXHAUSTED,
    PERSISTENCE_RECURRENT_FAILURE,
    PERSISTENCE_MODEL_CAPABILITY_LIMIT,
    PERSISTENCE_SKILL_REQUIRED,
    PERSISTENCE_ARCHITECTURE_REQUIRED,
    PERSISTENCE_HUMAN_DECISION_REQUIRED,
    PERSISTENCE_POLICY_BLOCKED,
    PERSISTENCE_ABANDONED,
)

@Serializable
private data class PersistenceStopEnvelope(
    val version: Int = PERSISTENCE_STOP_STORE_VERSION,
    val value: PersistenceStopRecord,
    val checksum: String,
)

private fun validatePersistenceStop(stop: PersistenceStopRecord, previous: List<PersistenceStopRecord>) {
    require(stop.stopId == previous.size + 1L && stop.runId > 0 && stop.claimId.isNotBlank() &&
        stop.authorityHash.matches(SHA256) && stop.outcome in STOP_OUTCOMES && stop.diagnostic.isNotBlank() &&
        stop.outcome == stop.decision.outcome && stop.hash == persistenceStopHash(stop)
    ) { "Persistence stop authority is invalid." }
    require(previous.none { it.runId == stop.runId && it.decision.basisFingerprint == stop.decision.basisFingerprint }) {
        "Persistence stop was already recorded for this attempt basis."
    }
}