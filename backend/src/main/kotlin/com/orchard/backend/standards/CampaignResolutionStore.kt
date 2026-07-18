package com.orchard.backend.standards

import com.orchard.backend.workspace.loadRecoverableJsonl
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED = "REMEDIATION_EXHAUSTED"
const val RESOLUTION_CAUSE_EVIDENCE_CONFLICT = "EVIDENCE_CONFLICT"
const val RESOLUTION_CAUSE_EVIDENCE_UNKNOWN = "EVIDENCE_UNKNOWN"
const val RESOLUTION_CAUSE_REGRESSION = "REGRESSION"

const val RESOLUTION_ACTION_ADDITIONAL_REMEDIATION = "ADDITIONAL_REMEDIATION"
const val RESOLUTION_ACTION_INVESTIGATION = "INVESTIGATION"
const val RESOLUTION_ACTION_RESCAN = "RESCAN"
const val RESOLUTION_ACTION_EXCEPTION_REQUEST = "EXCEPTION_REQUEST"
const val RESOLUTION_ACTION_STANDARD_CLARIFICATION = "STANDARD_CLARIFICATION"
const val RESOLUTION_ACTION_ABANDON = "ABANDON"

@Serializable
data class CampaignResolutionCase(
    val caseId: Long,
    val campaignId: Long,
    val projectId: Int,
    val evaluationId: Long,
    val evaluationHash: String,
    val repositoryRevision: String,
    val cause: String,
    val practiceIds: List<String>,
    val openedAt: String,
    val hash: String,
)

@Serializable
data class CampaignResolutionProposal(
    val proposalId: Long,
    val caseId: Long,
    val evaluationHash: String,
    val action: String,
    val rationale: String,
    val practiceIds: List<String>,
    val instructions: String,
    val proposedBacklog: List<CampaignResolutionBacklogNode> = emptyList(),
    val actor: String,
    val modelBindingFingerprint: String? = null,
    val promptHash: String? = null,
    val outputHash: String? = null,
    val proposedAt: String,
    val hash: String,
)

@Serializable
data class CampaignResolutionBacklogNode(
    val nodeId: String,
    val parentNodeId: String? = null,
    val type: String,
    val title: String,
    val description: String,
    val practiceIds: List<String>,
    val acceptanceCriteria: List<String> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
)

@Serializable
data class CampaignResolutionAdmission(
    val admissionId: Long,
    val caseId: Long,
    val proposalId: Long,
    val proposalHash: String,
    val actor: String,
    val admittedAt: String,
    val admittedNodes: List<AdmittedBacklogNode> = emptyList(),
    val hash: String,
)

@Serializable
data class CampaignResolutionView(
    val case: CampaignResolutionCase,
    val proposals: List<CampaignResolutionProposal>,
    val admission: CampaignResolutionAdmission? = null,
)

@Serializable
private data class CampaignResolutionEnvelope(
    val version: Int = 1,
    val case: CampaignResolutionCase? = null,
    val proposal: CampaignResolutionProposal? = null,
    val admission: CampaignResolutionAdmission? = null,
    val checksum: String,
)

interface CampaignResolutionStore {
    fun cases(): List<CampaignResolutionCase>
    fun proposals(): List<CampaignResolutionProposal>
    fun admissions(): List<CampaignResolutionAdmission>
    fun appendCase(case: CampaignResolutionCase)
    fun appendProposal(proposal: CampaignResolutionProposal)
    fun appendNextProposal(create: (proposalId: Long) -> CampaignResolutionProposal): CampaignResolutionProposal {
        val proposal = create((proposals().maxOfOrNull { it.proposalId } ?: 0L) + 1L)
        appendProposal(proposal)
        return proposal
    }
    fun appendAdmission(admission: CampaignResolutionAdmission)
}

class TransientCampaignResolutionStore : CampaignResolutionStore {
    private val caseRecords = mutableListOf<CampaignResolutionCase>()
    private val proposalRecords = mutableListOf<CampaignResolutionProposal>()
    private val admissionRecords = mutableListOf<CampaignResolutionAdmission>()

    @Synchronized
    override fun cases(): List<CampaignResolutionCase> = caseRecords.toList()

    @Synchronized
    override fun proposals(): List<CampaignResolutionProposal> = proposalRecords.toList()

    @Synchronized
    override fun admissions(): List<CampaignResolutionAdmission> = admissionRecords.toList()

    @Synchronized
    override fun appendCase(case: CampaignResolutionCase) {
        validateCaseAppend(caseRecords, case)
        caseRecords += case
    }

    @Synchronized
    override fun appendProposal(proposal: CampaignResolutionProposal) {
        validateProposalAppend(caseRecords, proposalRecords, admissionRecords, proposal)
        proposalRecords += proposal
    }

    @Synchronized
    override fun appendNextProposal(create: (proposalId: Long) -> CampaignResolutionProposal): CampaignResolutionProposal {
        val proposal = create((proposalRecords.maxOfOrNull { it.proposalId } ?: 0L) + 1L)
        appendProposal(proposal)
        return proposal
    }

    @Synchronized
    override fun appendAdmission(admission: CampaignResolutionAdmission) {
        validateAdmissionAppend(caseRecords, proposalRecords, admissionRecords, admission)
        admissionRecords += admission
    }
}

class FileCampaignResolutionStore(private val directory: Path) : CampaignResolutionStore {
    private val path = directory.resolve("campaign-resolutions.jsonl")
    private val lockPath = directory.resolve("campaign-resolutions.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun cases(): List<CampaignResolutionCase> = load().mapNotNull { it.case }

    @Synchronized
    override fun proposals(): List<CampaignResolutionProposal> = load().mapNotNull { it.proposal }

    @Synchronized
    override fun admissions(): List<CampaignResolutionAdmission> = load().mapNotNull { it.admission }

    @Synchronized
    override fun appendCase(case: CampaignResolutionCase) = append(case = case)

    @Synchronized
    override fun appendProposal(proposal: CampaignResolutionProposal) = append(proposal = proposal)

    @Synchronized
    override fun appendNextProposal(create: (proposalId: Long) -> CampaignResolutionProposal): CampaignResolutionProposal {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val records = load()
                val cases = records.mapNotNull { it.case }
                val proposals = records.mapNotNull { it.proposal }
                val admissions = records.mapNotNull { it.admission }
                val proposal = create((proposals.maxOfOrNull { it.proposalId } ?: 0L) + 1L)
                validateProposalAppend(cases, proposals, admissions, proposal)
                appendEnvelope(proposal = proposal)
                proposal
            }
        }
    }

    @Synchronized
    override fun appendAdmission(admission: CampaignResolutionAdmission) = append(admission = admission)

    private fun append(
        case: CampaignResolutionCase? = null,
        proposal: CampaignResolutionProposal? = null,
        admission: CampaignResolutionAdmission? = null,
    ) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val records = load()
                val cases = records.mapNotNull { it.case }
                val proposals = records.mapNotNull { it.proposal }
                val admissions = records.mapNotNull { it.admission }
                case?.let { validateCaseAppend(cases, it) }
                proposal?.let { validateProposalAppend(cases, proposals, admissions, it) }
                admission?.let { validateAdmissionAppend(cases, proposals, admissions, it) }
                appendEnvelope(case, proposal, admission)
            }
        }
    }

    private fun appendEnvelope(
        case: CampaignResolutionCase? = null,
        proposal: CampaignResolutionProposal? = null,
        admission: CampaignResolutionAdmission? = null,
    ) {
        val payload = case?.let(json::encodeToString)
            ?: proposal?.let(json::encodeToString)
            ?: json.encodeToString(requireNotNull(admission))
        val envelope = CampaignResolutionEnvelope(case = case, proposal = proposal, admission = admission, checksum = sha256(payload))
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun load(): List<CampaignResolutionEnvelope> = loadRecoverableJsonl(path, "campaign resolutions") { line, _ ->
        val envelope = json.decodeFromString<CampaignResolutionEnvelope>(line)
        require(envelope.version == 1 && listOfNotNull(envelope.case, envelope.proposal, envelope.admission).size == 1) {
            "Campaign resolution record shape is invalid"
        }
        val payload = envelope.case?.let(json::encodeToString)
            ?: envelope.proposal?.let(json::encodeToString)
            ?: json.encodeToString(requireNotNull(envelope.admission))
        require(envelope.checksum == sha256(payload)) { "Campaign resolution checksum mismatch" }
        envelope
    }.also { records ->
        val cases = mutableListOf<CampaignResolutionCase>()
        val proposals = mutableListOf<CampaignResolutionProposal>()
        val admissions = mutableListOf<CampaignResolutionAdmission>()
        records.forEach { record ->
            record.case?.let { validateCaseAppend(cases, it); cases += it }
            record.proposal?.let { validateProposalAppend(cases, proposals, admissions, it); proposals += it }
            record.admission?.let { validateAdmissionAppend(cases, proposals, admissions, it); admissions += it }
        }
    }
}

fun newCampaignResolutionCase(case: CampaignResolutionCase): CampaignResolutionCase =
    case.copy(hash = campaignResolutionCaseHash(case.copy(hash = "")))

fun newCampaignResolutionProposal(proposal: CampaignResolutionProposal): CampaignResolutionProposal =
    proposal.copy(hash = campaignResolutionProposalHash(proposal.copy(hash = "")))

fun newCampaignResolutionAdmission(admission: CampaignResolutionAdmission): CampaignResolutionAdmission =
    admission.copy(hash = campaignResolutionAdmissionHash(admission.copy(hash = "")))

fun campaignResolutionViews(store: CampaignResolutionStore, projectId: Int? = null): List<CampaignResolutionView> {
    val proposals = store.proposals().groupBy { it.caseId }
    val admissions = store.admissions().associateBy { it.caseId }
    return store.cases().filter { projectId == null || it.projectId == projectId }.map { case ->
        CampaignResolutionView(case, proposals[case.caseId].orEmpty().sortedBy { it.proposalId }, admissions[case.caseId])
    }
}

private fun validateCaseAppend(existing: List<CampaignResolutionCase>, case: CampaignResolutionCase) {
    require(case.caseId > 0 && case.campaignId > 0 && case.projectId > 0 && case.evaluationId > 0) {
        "Campaign resolution case identity is invalid"
    }
    require(case.evaluationHash.matches(SHA256) && case.repositoryRevision.matches(GIT_REVISION) && case.openedAt.isNotBlank()) {
        "Campaign resolution case authority is invalid"
    }
    require(case.cause in RESOLUTION_CAUSES && case.practiceIds.isNotEmpty() &&
        case.practiceIds.distinct().size == case.practiceIds.size && case.practiceIds.all { it.matches(PRACTICE_ID) }) {
        "Campaign resolution case cause or practices are invalid"
    }
    require(existing.none { it.caseId == case.caseId || it.evaluationHash == case.evaluationHash }) {
        "Campaign resolution case already exists"
    }
    require(case.hash == campaignResolutionCaseHash(case.copy(hash = ""))) { "Campaign resolution case hash is invalid" }
}

private fun validateProposalAppend(
    cases: List<CampaignResolutionCase>,
    existing: List<CampaignResolutionProposal>,
    admissions: List<CampaignResolutionAdmission>,
    proposal: CampaignResolutionProposal,
) {
    val case = cases.singleOrNull { it.caseId == proposal.caseId }
    require(case != null && proposal.evaluationHash == case.evaluationHash) { "Campaign resolution proposal is stale" }
    require(proposal.proposalId > 0 && proposal.action in RESOLUTION_ACTIONS && proposal.rationale.isNotBlank() &&
        proposal.instructions.isNotBlank() && proposal.actor.isNotBlank() && proposal.proposedAt.isNotBlank()) {
        "Campaign resolution proposal content is invalid"
    }
    require(proposal.practiceIds.isNotEmpty() && proposal.practiceIds.distinct().size == proposal.practiceIds.size &&
        proposal.practiceIds.all(case.practiceIds::contains)) { "Campaign resolution proposal practices are invalid" }
    val requiresBacklog = proposal.action in setOf(RESOLUTION_ACTION_ADDITIONAL_REMEDIATION, RESOLUTION_ACTION_INVESTIGATION)
    require(requiresBacklog == proposal.proposedBacklog.isNotEmpty() &&
        (!requiresBacklog || validBacklog(proposal.proposedBacklog, proposal.practiceIds.toSet()) &&
            proposal.proposedBacklog.flatMap { it.practiceIds }.toSet() == proposal.practiceIds.toSet())) {
        "Campaign resolution proposal backlog is invalid"
    }
    require(listOfNotNull(proposal.modelBindingFingerprint, proposal.promptHash, proposal.outputHash).let { it.isEmpty() || it.size == 3 && it.all(SHA256::matches) }) {
        "Campaign resolution proposal provenance is invalid"
    }
    require(existing.none { it.proposalId == proposal.proposalId }) { "Campaign resolution proposal already exists" }
    require(admissions.none { it.caseId == proposal.caseId }) { "Campaign resolution case is already admitted" }
    require(proposal.hash == campaignResolutionProposalHash(proposal.copy(hash = ""))) { "Campaign resolution proposal hash is invalid" }
}

private fun validateAdmissionAppend(
    cases: List<CampaignResolutionCase>,
    proposals: List<CampaignResolutionProposal>,
    existing: List<CampaignResolutionAdmission>,
    admission: CampaignResolutionAdmission,
) {
    val case = cases.singleOrNull { it.caseId == admission.caseId }
    val proposal = proposals.singleOrNull { it.proposalId == admission.proposalId }
    require(case != null && proposal != null && proposal.caseId == case.caseId && proposal.hash == admission.proposalHash) {
        "Campaign resolution admission does not reference current proposal authority"
    }
    require(admission.admissionId > 0 && admission.actor.isNotBlank() && admission.admittedAt.isNotBlank()) {
        "Campaign resolution admission identity is invalid"
    }
    require(admission.admittedNodes.map { it.nodeId } == proposal.proposedBacklog.map { it.nodeId } &&
        admission.admittedNodes.map { it.entityId }.distinct().size == admission.admittedNodes.size &&
        admission.admittedNodes.all { it.entityId > 0 }) {
        "Campaign resolution admitted node mapping is invalid"
    }
    require(existing.none { it.admissionId == admission.admissionId || it.caseId == admission.caseId }) {
        "Campaign resolution case is already admitted"
    }
    require(admission.hash == campaignResolutionAdmissionHash(admission.copy(hash = ""))) {
        "Campaign resolution admission hash is invalid"
    }
}

private fun validBacklog(nodes: List<CampaignResolutionBacklogNode>, practiceIds: Set<String>): Boolean {
    if (nodes.map { it.nodeId }.distinct().size != nodes.size) return false
    val byId = nodes.associateBy { it.nodeId }
    val positions = nodes.mapIndexed { index, node -> node.nodeId to index }.toMap()
    if (nodes.count { it.type == BACKLOG_EPIC && it.parentNodeId == null } != 1) return false
    return nodes.all { node ->
        node.nodeId.matches(Regex("[A-Z0-9_-]{2,96}")) && node.title.isNotBlank() && node.description.isNotBlank() &&
            node.type in setOf(BACKLOG_EPIC, BACKLOG_STORY, BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) &&
            node.practiceIds.isNotEmpty() && node.practiceIds.distinct().size == node.practiceIds.size &&
            node.practiceIds.all(practiceIds::contains) &&
            ((node.type == BACKLOG_EPIC && node.parentNodeId == null) || byId[node.parentNodeId]?.let { parent ->
                positions.getValue(parent.nodeId) < positions.getValue(node.nodeId) &&
                    ((node.type == BACKLOG_STORY && parent.type == BACKLOG_EPIC) ||
                        (node.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) && parent.type == BACKLOG_STORY))
            } == true)
    }
}

private fun campaignResolutionCaseHash(case: CampaignResolutionCase): String = sha256(Json.encodeToString(case))
private fun campaignResolutionProposalHash(proposal: CampaignResolutionProposal): String = sha256(Json.encodeToString(proposal))
private fun campaignResolutionAdmissionHash(admission: CampaignResolutionAdmission): String = sha256(Json.encodeToString(admission))
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val PRACTICE_ID = Regex("[A-Z][A-Z0-9_-]{2,63}")
private val RESOLUTION_CAUSES = setOf(
    RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED,
    RESOLUTION_CAUSE_EVIDENCE_CONFLICT,
    RESOLUTION_CAUSE_EVIDENCE_UNKNOWN,
    RESOLUTION_CAUSE_REGRESSION,
)
private val RESOLUTION_ACTIONS = setOf(
    RESOLUTION_ACTION_ADDITIONAL_REMEDIATION,
    RESOLUTION_ACTION_INVESTIGATION,
    RESOLUTION_ACTION_RESCAN,
    RESOLUTION_ACTION_EXCEPTION_REQUEST,
    RESOLUTION_ACTION_STANDARD_CLARIFICATION,
    RESOLUTION_ACTION_ABANDON,
)
