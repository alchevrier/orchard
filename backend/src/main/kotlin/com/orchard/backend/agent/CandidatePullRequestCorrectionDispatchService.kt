package com.orchard.backend.agent

import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.WorkspaceStore
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

const val CANDIDATE_CORRECTION_DISPATCHED = "DISPATCHED"
const val CANDIDATE_CORRECTION_REJECTED = "REJECTED"

enum class CandidateCorrectionDispatchOutcome {
    DISPATCHED,
    DEFERRED,
    REJECTED,
}

@Serializable
data class CandidatePullRequestCorrectionDispatch(
    val dispatchId: Long,
    val correctionId: Long,
    val correctionHash: String,
    val correctionTarget: String,
    val runId: Long,
    val status: String,
    val diagnostic: String,
    val dispatchedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestCorrectionDispatchStore {
    fun load(): List<CandidatePullRequestCorrectionDispatch>
    fun appendNext(create: (dispatchId: Long) -> CandidatePullRequestCorrectionDispatch): CandidatePullRequestCorrectionDispatch
}

class TransientCandidatePullRequestCorrectionDispatchStore : CandidatePullRequestCorrectionDispatchStore {
    private val dispatches = mutableListOf<CandidatePullRequestCorrectionDispatch>()

    @Synchronized
    override fun load(): List<CandidatePullRequestCorrectionDispatch> = dispatches.toList()

    @Synchronized
    override fun appendNext(
        create: (dispatchId: Long) -> CandidatePullRequestCorrectionDispatch,
    ): CandidatePullRequestCorrectionDispatch {
        val dispatch = create(dispatches.size + 1L)
        validateCandidatePullRequestCorrectionDispatch(dispatch, dispatches)
        dispatches += dispatch
        return dispatch
    }
}

class FileCandidatePullRequestCorrectionDispatchStore(private val directory: Path) : CandidatePullRequestCorrectionDispatchStore {
    private val path = directory.resolve("candidate-pull-request-correction-dispatches.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-correction-dispatches.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestCorrectionDispatch> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(
        create: (dispatchId: Long) -> CandidatePullRequestCorrectionDispatch,
    ): CandidatePullRequestCorrectionDispatch {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val dispatches = loadUnlocked()
                val dispatch = create(dispatches.size + 1L)
                validateCandidatePullRequestCorrectionDispatch(dispatch, dispatches)
                appendUnlocked(dispatch)
                dispatch
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestCorrectionDispatch> = mutableListOf<CandidatePullRequestCorrectionDispatch>().also { dispatches ->
        loadRecoverableJsonl(path, "candidate-pull-request-correction-dispatches") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestCorrectionDispatchEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported candidate PR correction dispatch format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in candidate PR correction dispatch $recordNumber"
            }
            validateCandidatePullRequestCorrectionDispatch(envelope.value, dispatches)
            dispatches += envelope.value
            envelope.value
        }
    }

    private fun appendUnlocked(dispatch: CandidatePullRequestCorrectionDispatch) {
        val payload = json.encodeToString(dispatch)
        val line = json.encodeToString(
            CandidatePullRequestCorrectionDispatchEnvelope(value = dispatch, checksum = stagedPlanHash(payload)),
        ) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

fun interface CandidateCorrectionRepairGateway {
    fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome
}

class WorkspaceCandidateCorrectionRepairGateway(private val workspace: WorkspaceStore) : CandidateCorrectionRepairGateway {
    override fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome {
        val run = workspace.snapshot(0).workflowRuns.singleOrNull { it.runId == correction.runId }
            ?: return CandidateCorrectionDispatchOutcome.REJECTED
        if (run.state != RUN_STATE_DONE) return CandidateCorrectionDispatchOutcome.DEFERRED
        val reason = "Candidate PR ${correction.pullRequestId} review ${correction.reviewId} requires repair: " +
            correction.findings.joinToString("; ") { it.observation }.take(MAX_REPAIR_REASON_LENGTH)
        return when (workspace.requireAuditRepair(correction.runId, reason).status) {
            WorkflowMutationStatus.RECORDED -> CandidateCorrectionDispatchOutcome.DISPATCHED
            WorkflowMutationStatus.RUN_NOT_FOUND,
            WorkflowMutationStatus.RUN_CLOSED -> CandidateCorrectionDispatchOutcome.REJECTED
            WorkflowMutationStatus.INVALID_RECORD -> CandidateCorrectionDispatchOutcome.DEFERRED
            WorkflowMutationStatus.REVISION_INVALID,
            WorkflowMutationStatus.STORAGE_UNAVAILABLE -> CandidateCorrectionDispatchOutcome.DEFERRED
        }
    }
}

class CandidatePullRequestCorrectionDispatchService(
    private val correctionStore: CandidatePullRequestCorrectionStore,
    private val dispatchStore: CandidatePullRequestCorrectionDispatchStore,
    private val repairGateway: CandidateCorrectionRepairGateway,
) {
    @Synchronized
    fun dispatches(): List<CandidatePullRequestCorrectionDispatch> = dispatchStore.load()

    @Synchronized
    fun tick(): CandidatePullRequestCorrectionDispatch? {
        val dispatchedCorrectionIds = dispatchStore.load().mapTo(hashSetOf()) { it.correctionId }
        val corrections = correctionStore.load().filter {
            it.correctionTarget == REVIEW_CORRECTION_CANDIDATE_REPAIR && it.correctionId !in dispatchedCorrectionIds
        }
        corrections.forEach { correction ->
            val outcome = repairGateway.dispatch(correction)
            when (outcome) {
                CandidateCorrectionDispatchOutcome.DEFERRED -> Unit
                CandidateCorrectionDispatchOutcome.DISPATCHED,
                CandidateCorrectionDispatchOutcome.REJECTED -> return appendDispatch(
                    correction,
                    if (outcome == CandidateCorrectionDispatchOutcome.DISPATCHED) {
                        CANDIDATE_CORRECTION_DISPATCHED
                    } else {
                        CANDIDATE_CORRECTION_REJECTED
                    },
                )
            }
        }
        return null
    }

    private fun appendDispatch(
        correction: CandidatePullRequestCorrection,
        status: String,
    ): CandidatePullRequestCorrectionDispatch = dispatchStore.appendNext { dispatchId ->
        val draft = CandidatePullRequestCorrectionDispatch(
            dispatchId = dispatchId,
            correctionId = correction.correctionId,
            correctionHash = correction.hash,
            correctionTarget = correction.correctionTarget,
            runId = correction.runId,
            status = status,
            diagnostic = if (status == CANDIDATE_CORRECTION_DISPATCHED) {
                "Candidate repair authority was dispatched."
            } else {
                "Candidate repair authority could not be applied to its workflow run."
            },
            hash = "",
        )
        draft.copy(hash = candidatePullRequestCorrectionDispatchHash(draft))
    }
}

fun candidatePullRequestCorrectionDispatchHash(dispatch: CandidatePullRequestCorrectionDispatch): String = stagedPlanHash(
    candidatePullRequestCorrectionDispatchJson.encodeToString(dispatch.copy(hash = "")),
)

private fun validateCandidatePullRequestCorrectionDispatch(
    dispatch: CandidatePullRequestCorrectionDispatch,
    previous: List<CandidatePullRequestCorrectionDispatch>,
) {
    require(dispatch.dispatchId == previous.size + 1L && dispatch.correctionId > 0 && dispatch.runId > 0 &&
        dispatch.correctionHash.matches(SHA256) && dispatch.correctionTarget in REVIEW_CORRECTION_TARGETS &&
        dispatch.status in setOf(CANDIDATE_CORRECTION_DISPATCHED, CANDIDATE_CORRECTION_REJECTED) &&
        dispatch.diagnostic.isNotBlank()
    ) { "Candidate PR correction dispatch is invalid" }
    require(previous.none { it.correctionId == dispatch.correctionId }) { "Candidate PR correction was already dispatched" }
    require(dispatch.hash == candidatePullRequestCorrectionDispatchHash(dispatch)) { "Candidate PR correction dispatch hash is invalid" }
}

@Serializable
private data class CandidatePullRequestCorrectionDispatchEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequestCorrectionDispatch,
    val checksum: String,
)

private const val STORE_VERSION = 1
private const val MAX_REPAIR_REASON_LENGTH = 1_000
private val candidatePullRequestCorrectionDispatchJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")