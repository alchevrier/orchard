package com.orchard.backend.agent

import com.orchard.backend.workspace.DesignAuthorityReference
import com.orchard.backend.workspace.DesignCorrectionAuthorization
import com.orchard.backend.workspace.DesignGovernanceResult
import com.orchard.backend.workspace.DesignSubmission
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

@Serializable
data class CandidatePullRequestDesignRevisionRequest(
    val requestId: Long,
    val correctionId: Long,
    val correctionHash: String,
    val pullRequestId: Long,
    val runId: Long,
    val design: DesignAuthorityReference,
    val findings: List<CandidatePullRequestReviewFinding>,
    val requestedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CandidatePullRequestDesignRevisionRequestStore {
    fun load(): List<CandidatePullRequestDesignRevisionRequest>
    fun appendNext(create: (requestId: Long) -> CandidatePullRequestDesignRevisionRequest): CandidatePullRequestDesignRevisionRequest
}

class TransientCandidatePullRequestDesignRevisionRequestStore : CandidatePullRequestDesignRevisionRequestStore {
    private val requests = mutableListOf<CandidatePullRequestDesignRevisionRequest>()

    @Synchronized
    override fun load(): List<CandidatePullRequestDesignRevisionRequest> = requests.toList()

    @Synchronized
    override fun appendNext(
        create: (requestId: Long) -> CandidatePullRequestDesignRevisionRequest,
    ): CandidatePullRequestDesignRevisionRequest {
        val request = create(requests.size + 1L)
        validateCandidatePullRequestDesignRevisionRequest(request, requests)
        requests += request
        return request
    }
}

class FileCandidatePullRequestDesignRevisionRequestStore(private val directory: Path) : CandidatePullRequestDesignRevisionRequestStore {
    private val path = directory.resolve("candidate-pull-request-design-revision-requests.jsonl")
    private val lockPath = directory.resolve("candidate-pull-request-design-revision-requests.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CandidatePullRequestDesignRevisionRequest> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(
        create: (requestId: Long) -> CandidatePullRequestDesignRevisionRequest,
    ): CandidatePullRequestDesignRevisionRequest {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val requests = loadUnlocked()
                val request = create(requests.size + 1L)
                validateCandidatePullRequestDesignRevisionRequest(request, requests)
                val payload = json.encodeToString(request)
                val line = json.encodeToString(
                    CandidatePullRequestDesignRevisionRequestEnvelope(value = request, checksum = stagedPlanHash(payload)),
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                request
            }
        }
    }

    private fun loadUnlocked(): List<CandidatePullRequestDesignRevisionRequest> = mutableListOf<CandidatePullRequestDesignRevisionRequest>().also { requests ->
        loadRecoverableJsonl(path, "candidate-pull-request-design-revision-requests") { line, recordNumber ->
            val envelope = json.decodeFromString<CandidatePullRequestDesignRevisionRequestEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported design revision request format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in design revision request $recordNumber"
            }
            validateCandidatePullRequestDesignRevisionRequest(envelope.value, requests)
            requests += envelope.value
            envelope.value
        }
    }
}

fun interface DesignRevisionCorrectionGateway {
    fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome
}

class WorkspaceDesignRevisionCorrectionGateway(
    private val workspace: WorkspaceStore,
    private val requestStore: CandidatePullRequestDesignRevisionRequestStore,
) : DesignRevisionCorrectionGateway {
    fun request(requestId: Long): CandidatePullRequestDesignRevisionRequest? =
        requestStore.load().singleOrNull { it.requestId == requestId }

    fun recordSuccessor(
        request: CandidatePullRequestDesignRevisionRequest,
        submission: DesignSubmission,
    ): DesignGovernanceResult = workspace.recordCorrectiveDesignCandidate(
        submission,
        DesignCorrectionAuthorization(
            requestId = request.requestId,
            correctionId = request.correctionId,
            correctionHash = request.correctionHash,
            runId = request.runId,
            design = request.design,
        ),
    )

    override fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome {
        requestStore.load().singleOrNull { it.correctionId == correction.correctionId }?.let {
            return CandidateCorrectionDispatchOutcome.DISPATCHED
        }
        val run = workspace.snapshot(0).workflowRuns.singleOrNull { it.runId == correction.runId }
            ?: return CandidateCorrectionDispatchOutcome.REJECTED
        val design = run.context.acceptanceContract?.design ?: return CandidateCorrectionDispatchOutcome.REJECTED
        return runCatching {
            requestStore.appendNext { requestId ->
                newCandidatePullRequestDesignRevisionRequest(requestId, correction, design)
            }
        }.fold(
            onSuccess = { CandidateCorrectionDispatchOutcome.DISPATCHED },
            onFailure = { CandidateCorrectionDispatchOutcome.DEFERRED },
        )
    }
}

class CandidatePullRequestDesignRevisionService(
    private val correctionStore: CandidatePullRequestCorrectionStore,
    private val dispatchStore: CandidatePullRequestCorrectionDispatchStore,
    private val gateway: DesignRevisionCorrectionGateway,
    private val dispositionService: CandidatePullRequestDispositionService? = null,
) {
    fun submitSuccessor(requestId: Long, submission: DesignSubmission): DesignGovernanceResult? {
        val request = (gateway as? WorkspaceDesignRevisionCorrectionGateway)?.request(requestId) ?: return null
        return (gateway as? WorkspaceDesignRevisionCorrectionGateway)?.recordSuccessor(request, submission)
    }

    @Synchronized
    fun tick(): CandidatePullRequestCorrectionDispatch? {
        val dispatchedCorrectionIds = dispatchStore.load().mapTo(hashSetOf()) { it.correctionId }
        val corrections = correctionStore.load().filter {
            it.correctionTarget == REVIEW_CORRECTION_DESIGN_REVISION && it.correctionId !in dispatchedCorrectionIds
        }
        corrections.forEach { correction ->
            when (val outcome = gateway.dispatch(correction)) {
                CandidateCorrectionDispatchOutcome.DEFERRED -> Unit
                CandidateCorrectionDispatchOutcome.DISPATCHED,
                CandidateCorrectionDispatchOutcome.REJECTED -> {
                    if (outcome == CandidateCorrectionDispatchOutcome.DISPATCHED) {
                        dispositionService?.record(
                            correction.pullRequestId,
                            CANDIDATE_DISPOSITION_BLOCKED,
                            "Design revision ${correction.correctionId} must be explicitly admitted before this candidate can continue.",
                            correction.correctionId,
                        )
                    }
                    return appendDispatch(correction, outcome)
                }
            }
        }
        return null
    }

    private fun appendDispatch(
        correction: CandidatePullRequestCorrection,
        outcome: CandidateCorrectionDispatchOutcome,
    ): CandidatePullRequestCorrectionDispatch = dispatchStore.appendNext { dispatchId ->
        val status = if (outcome == CandidateCorrectionDispatchOutcome.DISPATCHED) {
            CANDIDATE_CORRECTION_DISPATCHED
        } else {
            CANDIDATE_CORRECTION_REJECTED
        }
        val draft = CandidatePullRequestCorrectionDispatch(
            dispatchId = dispatchId,
            correctionId = correction.correctionId,
            correctionHash = correction.hash,
            correctionTarget = correction.correctionTarget,
            runId = correction.runId,
            status = status,
            diagnostic = if (status == CANDIDATE_CORRECTION_DISPATCHED) {
                "Design revision authority was requested and awaits explicit successor admission."
            } else {
                "Design revision authority could not be applied to the pinned workflow design."
            },
            hash = "",
        )
        draft.copy(hash = candidatePullRequestCorrectionDispatchHash(draft))
    }
}

fun newCandidatePullRequestDesignRevisionRequest(
    requestId: Long,
    correction: CandidatePullRequestCorrection,
    design: DesignAuthorityReference,
): CandidatePullRequestDesignRevisionRequest {
    val draft = CandidatePullRequestDesignRevisionRequest(
        requestId = requestId,
        correctionId = correction.correctionId,
        correctionHash = correction.hash,
        pullRequestId = correction.pullRequestId,
        runId = correction.runId,
        design = design,
        findings = correction.findings,
        hash = "",
    )
    return draft.copy(hash = candidatePullRequestDesignRevisionRequestHash(draft))
}

fun candidatePullRequestDesignRevisionRequestHash(request: CandidatePullRequestDesignRevisionRequest): String = stagedPlanHash(
    candidatePullRequestDesignRevisionRequestJson.encodeToString(request.copy(hash = "")),
)

private fun validateCandidatePullRequestDesignRevisionRequest(
    request: CandidatePullRequestDesignRevisionRequest,
    previous: List<CandidatePullRequestDesignRevisionRequest>,
) {
    require(request.requestId == previous.size + 1L && request.correctionId > 0 && request.pullRequestId > 0 && request.runId > 0 &&
        request.correctionHash.matches(SHA256) && request.design.designId > 0 && request.design.hash.matches(SHA256) &&
        request.findings.isNotEmpty() && request.findings.all { it.correctionTarget == REVIEW_CORRECTION_DESIGN_REVISION }
    ) { "Design revision correction request is invalid" }
    require(previous.none { it.correctionId == request.correctionId }) { "Design revision request already exists for correction" }
    require(request.hash == candidatePullRequestDesignRevisionRequestHash(request)) { "Design revision correction request hash is invalid" }
}

@Serializable
private data class CandidatePullRequestDesignRevisionRequestEnvelope(
    val version: Int = STORE_VERSION,
    val value: CandidatePullRequestDesignRevisionRequest,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val candidatePullRequestDesignRevisionRequestJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")