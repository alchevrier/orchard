package com.orchard.backend.standards

import com.orchard.backend.workspace.loadRecoverableJsonl
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PRACTICE_SEVERITY_REQUIRED = "REQUIRED"
const val PRACTICE_SEVERITY_ADVISORY = "ADVISORY"
const val CONFORMANCE_CONFORMING = "CONFORMING"
const val CONFORMANCE_NONCONFORMING = "NONCONFORMING"
const val CONFORMANCE_PARTIAL = "PARTIAL"
const val CONFORMANCE_NOT_APPLICABLE = "NOT_APPLICABLE"
const val CONFORMANCE_UNKNOWN = "UNKNOWN"
const val CONFORMANCE_CONFLICTING = "CONFLICTING"
const val CONFORMANCE_EXCEPTION_ACTIVE = "EXCEPTION_ACTIVE"
const val BACKLOG_EPIC = "EPIC"
const val BACKLOG_STORY = "STORY"
const val BACKLOG_TASK = "TASK"
const val BACKLOG_BUG = "BUG"
const val BACKLOG_INVESTIGATION = "INVESTIGATION"

@Serializable
data class EngineeringPractice(
    val practiceId: String,
    val title: String,
    val category: String,
    val severity: String,
    val applicability: String,
    val requirement: String,
    val requiredEvidence: List<String>,
    val remediation: String,
    val enabled: Boolean = true,
)

@Serializable
data class EngineeringStandardRevision(
    val standardId: Long,
    val projectId: Int,
    val revision: Int,
    val name: String,
    val practices: List<EngineeringPractice>,
    val actor: String,
    val createdAt: String,
    val previousHash: String? = null,
    val hash: String,
)

@Serializable
data class ConformanceCitation(
    val path: String,
    val contentHash: String,
    val observation: String,
)

@Serializable
data class ConformanceFinding(
    val findingId: String,
    val practiceId: String,
    val disposition: String,
    val summary: String,
    val citations: List<ConformanceCitation>,
    val affectedPaths: List<String>,
    val acceptanceCriteria: List<String>,
    val verificationCommands: List<String>,
    val confidence: Double,
)

@Serializable
data class BacklogProposalNode(
    val nodeId: String,
    val parentNodeId: String? = null,
    val type: String,
    val title: String,
    val description: String,
    val findingIds: List<String>,
    val acceptanceCriteria: List<String> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RepositoryConformanceScan(
    val scanId: Long,
    val projectId: Int,
    val standardId: Long,
    val standardRevision: Int,
    val standardHash: String,
    val repositoryRevision: String,
    val findings: List<ConformanceFinding>,
    val proposedBacklog: List<BacklogProposalNode>,
    val modelBindingFingerprint: String,
    val promptHash: String,
    val contextHash: String,
    val outputHash: String,
    val createdAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effectiveStandard: EffectiveEngineeringStandard? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val appliedExceptions: List<AppliedStandardsException> = emptyList(),
    val hash: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ConformanceBacklogAdmission(
    val admissionId: Long,
    val scanId: Long,
    val scanHash: String,
    val projectId: Int,
    val repositoryRevision: String,
    val admittedEntityIds: List<Int>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val admittedNodes: List<AdmittedBacklogNode> = emptyList(),
    val actor: String,
    val admittedAt: String,
    val hash: String,
)

@Serializable
data class AdmittedBacklogNode(
    val nodeId: String,
    val entityId: Int,
)

@Serializable
private data class EngineeringStandardsEnvelope(
    val version: Int = 1,
    val standard: EngineeringStandardRevision? = null,
    val scan: RepositoryConformanceScan? = null,
    val admission: ConformanceBacklogAdmission? = null,
    val checksum: String,
)

interface EngineeringStandardsStore {
    fun standards(): List<EngineeringStandardRevision>
    fun scans(): List<RepositoryConformanceScan>
    fun admissions(): List<ConformanceBacklogAdmission>
    fun appendStandard(standard: EngineeringStandardRevision)
    fun appendScan(scan: RepositoryConformanceScan)
    fun appendAdmission(admission: ConformanceBacklogAdmission)
}

class TransientEngineeringStandardsStore : EngineeringStandardsStore {
    private val standardRecords = mutableListOf<EngineeringStandardRevision>()
    private val scanRecords = mutableListOf<RepositoryConformanceScan>()
    private val admissionRecords = mutableListOf<ConformanceBacklogAdmission>()

    @Synchronized
    override fun standards(): List<EngineeringStandardRevision> = standardRecords.toList()

    @Synchronized
    override fun scans(): List<RepositoryConformanceScan> = scanRecords.toList()

    @Synchronized
    override fun admissions(): List<ConformanceBacklogAdmission> = admissionRecords.toList()

    @Synchronized
    override fun appendStandard(standard: EngineeringStandardRevision) {
        validateStandardAppend(standardRecords, standard)
        standardRecords += standard
    }

    @Synchronized
    override fun appendScan(scan: RepositoryConformanceScan) {
        validateScanAppend(standardRecords, scanRecords, scan)
        scanRecords += scan
    }

    @Synchronized
    override fun appendAdmission(admission: ConformanceBacklogAdmission) {
        validateAdmissionAppend(scanRecords, admissionRecords, admission)
        admissionRecords += admission
    }
}

class FileEngineeringStandardsStore(private val directory: Path) : EngineeringStandardsStore {
    private val path = directory.resolve("engineering-standards.jsonl")
    private val lockPath = directory.resolve("engineering-standards.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun standards(): List<EngineeringStandardRevision> = load().mapNotNull { it.standard }

    @Synchronized
    override fun scans(): List<RepositoryConformanceScan> = load().mapNotNull { it.scan }

    @Synchronized
    override fun admissions(): List<ConformanceBacklogAdmission> = load().mapNotNull { it.admission }

    @Synchronized
    override fun appendStandard(standard: EngineeringStandardRevision) {
        append(standard = standard)
    }

    @Synchronized
    override fun appendScan(scan: RepositoryConformanceScan) {
        append(scan = scan)
    }

    @Synchronized
    override fun appendAdmission(admission: ConformanceBacklogAdmission) {
        append(admission = admission)
    }

    private fun append(
        standard: EngineeringStandardRevision? = null,
        scan: RepositoryConformanceScan? = null,
        admission: ConformanceBacklogAdmission? = null,
    ) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val records = load()
                val standards = records.mapNotNull { it.standard }
                val scans = records.mapNotNull { it.scan }
                val admissions = records.mapNotNull { it.admission }
                standard?.let { validateStandardAppend(standards, it) }
                scan?.let { validateScanAppend(standards, scans, it) }
                admission?.let { validateAdmissionAppend(scans, admissions, it) }
                val payload = standard?.let(json::encodeToString)
                    ?: scan?.let(json::encodeToString)
                    ?: json.encodeToString(requireNotNull(admission))
                val envelope = EngineeringStandardsEnvelope(
                    standard = standard,
                    scan = scan,
                    admission = admission,
                    checksum = sha256(payload),
                )
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = java.nio.ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
    }

    private fun load(): List<EngineeringStandardsEnvelope> = loadRecoverableJsonl(path, "engineering standards") { line, _ ->
        val envelope = json.decodeFromString<EngineeringStandardsEnvelope>(line)
        require(envelope.version == 1 && listOfNotNull(envelope.standard, envelope.scan, envelope.admission).size == 1) {
            "Engineering standards record shape is invalid"
        }
        val payload = envelope.standard?.let(json::encodeToString)
            ?: envelope.scan?.let(json::encodeToString)
            ?: json.encodeToString(requireNotNull(envelope.admission))
        require(envelope.checksum == sha256(payload)) { "Engineering standards checksum mismatch" }
        envelope
    }.also { records ->
        val standards = mutableListOf<EngineeringStandardRevision>()
        val scans = mutableListOf<RepositoryConformanceScan>()
        val admissions = mutableListOf<ConformanceBacklogAdmission>()
        records.forEach { record ->
            record.standard?.let { validateStandardAppend(standards, it); standards += it }
            record.scan?.let { validateScanAppend(standards, scans, it); scans += it }
            record.admission?.let { validateAdmissionAppend(scans, admissions, it); admissions += it }
        }
    }
}

fun newEngineeringStandardRevision(
    standardId: Long,
    projectId: Int,
    revision: Int,
    name: String,
    practices: List<EngineeringPractice>,
    actor: String,
    createdAt: String,
    previousHash: String? = null,
): EngineeringStandardRevision {
    val draft = EngineeringStandardRevision(standardId, projectId, revision, name, practices, actor, createdAt, previousHash, "")
    return draft.copy(hash = engineeringStandardHash(draft))
}

fun newRepositoryConformanceScan(scan: RepositoryConformanceScan): RepositoryConformanceScan =
    scan.copy(hash = repositoryConformanceScanHash(scan.copy(hash = "")))

fun conformanceAuthorityHash(
    standardHash: String,
    effectiveStandard: EffectiveEngineeringStandard?,
    appliedExceptions: List<AppliedStandardsException>,
): String = sha256(
    listOf(effectiveStandard?.hash ?: standardHash, appliedExceptions.map { it.admissionHash }.sorted().joinToString(",")).joinToString(":"),
)

fun newConformanceBacklogAdmission(admission: ConformanceBacklogAdmission): ConformanceBacklogAdmission =
    admission.copy(hash = conformanceBacklogAdmissionHash(admission.copy(hash = "")))

private fun validateStandardAppend(existing: List<EngineeringStandardRevision>, standard: EngineeringStandardRevision) {
    require(standard.projectId > 0 && standard.standardId > 0 && standard.name.isNotBlank() && standard.actor.isNotBlank()) {
        "Engineering standard identity is invalid"
    }
    require(standard.practices.isNotEmpty() && standard.practices.map { it.practiceId }.distinct().size == standard.practices.size) {
        "Engineering standard practices are empty or duplicated"
    }
    require(standard.practices.all(::validEngineeringPractice)) { "Engineering standard contains an invalid practice" }
    val previous = existing.filter { it.projectId == standard.projectId }.maxByOrNull { it.revision }
    require(standard.revision == (previous?.revision ?: 0) + 1 && standard.previousHash == previous?.hash) {
        "Engineering standard revision does not extend current project authority"
    }
    require(standard.hash == engineeringStandardHash(standard.copy(hash = ""))) { "Engineering standard hash is invalid" }
}

private fun validateScanAppend(
    standards: List<EngineeringStandardRevision>,
    existing: List<RepositoryConformanceScan>,
    scan: RepositoryConformanceScan,
) {
    val standard = standards.singleOrNull { it.standardId == scan.standardId && it.revision == scan.standardRevision }
    require(standard != null && standard.projectId == scan.projectId && standard.hash == scan.standardHash) {
        "Conformance scan does not reference admitted standard authority"
    }
    require(scan.scanId > 0 && scan.repositoryRevision.matches(Regex("[0-9a-f]{40,64}"))) { "Conformance scan identity is invalid" }
    val authorityHash = conformanceAuthorityHash(scan.standardHash, scan.effectiveStandard, scan.appliedExceptions)
    require(existing.none { it.projectId == scan.projectId &&
        conformanceAuthorityHash(it.standardHash, it.effectiveStandard, it.appliedExceptions) == authorityHash &&
        it.repositoryRevision == scan.repositoryRevision }) {
        "Conformance scan already exists for this standard and repository revision"
    }
    val effective = scan.effectiveStandard
    require(effective == null || effective.projectId == scan.projectId && effective.baseStandardId == standard.standardId &&
        effective.baseStandardRevision == standard.revision && effective.baseStandardHash == standard.hash && validEffectiveStandard(effective)) {
        "Conformance scan effective standard is invalid"
    }
    val enabledPracticeIds = (effective?.practices?.map { it.practice } ?: standard.practices)
        .filter { it.enabled }.map { it.practiceId }.toSet()
    require(scan.findings.size == enabledPracticeIds.size && scan.findings.map { it.practiceId }.toSet() == enabledPracticeIds) {
        "Conformance scan must judge every enabled practice exactly once"
    }
    require(scan.findings.map { it.findingId }.distinct().size == scan.findings.size && scan.findings.all(::validFinding)) {
        "Conformance scan findings are invalid"
    }
    require(scan.appliedExceptions.map { it.admissionId }.distinct().size == scan.appliedExceptions.size &&
        scan.appliedExceptions.all { exception ->
            exception.admissionId > 0 && exception.proposalId > 0 && exception.admissionHash.matches(Regex("[0-9a-f]{64}")) &&
                exception.proposalHash.matches(Regex("[0-9a-f]{64}")) && validPolicyScope(exception.scope) &&
                exception.practiceIds.isNotEmpty() && exception.practiceIds.all(enabledPracticeIds::contains) && exception.activeFrom < exception.expiresAt
        }) { "Conformance scan applied exception authority is invalid" }
    val exceptedPracticeIds = scan.appliedExceptions.flatMap { it.practiceIds }.toSet()
    require(scan.findings.filter { it.disposition == CONFORMANCE_EXCEPTION_ACTIVE }.all { it.practiceId in exceptedPracticeIds }) {
        "Conformance scan exception dispositions are not backed by applied authority"
    }
    require(validBacklog(scan.proposedBacklog, scan.findings.map { it.findingId }.toSet())) { "Conformance backlog proposal is invalid" }
    val actionableFindingIds = scan.findings.filter {
        it.disposition in setOf(CONFORMANCE_NONCONFORMING, CONFORMANCE_PARTIAL, CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING)
    }.map { it.findingId }.toSet()
    require(scan.proposedBacklog.flatMap { it.findingIds }.toSet() == actionableFindingIds) {
        "Conformance backlog must cover exactly the actionable findings"
    }
    require(scan.hash == repositoryConformanceScanHash(scan.copy(hash = ""))) { "Conformance scan hash is invalid" }
}

private fun validateAdmissionAppend(
    scans: List<RepositoryConformanceScan>,
    existing: List<ConformanceBacklogAdmission>,
    admission: ConformanceBacklogAdmission,
) {
    val scan = scans.singleOrNull { it.scanId == admission.scanId }
    require(scan != null && scan.hash == admission.scanHash && scan.projectId == admission.projectId &&
        scan.repositoryRevision == admission.repositoryRevision) { "Backlog admission does not reference conformance scan authority" }
    require(admission.admissionId > 0 && admission.actor.isNotBlank() && admission.admittedAt.isNotBlank() &&
        admission.admittedEntityIds.isNotEmpty() && admission.admittedEntityIds.distinct().size == admission.admittedEntityIds.size) {
        "Backlog admission identity is invalid"
    }
    require(admission.admittedNodes.isEmpty() ||
        (admission.admittedNodes.map { it.nodeId } == scan.proposedBacklog.map { it.nodeId } &&
            admission.admittedNodes.map { it.entityId } == admission.admittedEntityIds)) {
        "Backlog admission node mapping is invalid"
    }
    require(existing.none { it.scanId == admission.scanId }) { "Conformance backlog is already admitted" }
    require(admission.hash == conformanceBacklogAdmissionHash(admission.copy(hash = ""))) { "Backlog admission hash is invalid" }
}

fun validEngineeringPractice(practice: EngineeringPractice): Boolean =
    practice.practiceId.matches(Regex("[A-Z][A-Z0-9_-]{2,63}")) && practice.title.isNotBlank() && practice.category.isNotBlank() &&
        practice.severity in setOf(PRACTICE_SEVERITY_REQUIRED, PRACTICE_SEVERITY_ADVISORY) && practice.applicability.isNotBlank() &&
        practice.requirement.isNotBlank() && practice.requiredEvidence.isNotEmpty() && practice.remediation.isNotBlank()

private fun validFinding(finding: ConformanceFinding): Boolean =
    finding.findingId.matches(Regex("[A-Z0-9_-]{3,96}")) && finding.practiceId.isNotBlank() &&
        finding.disposition in setOf(
            CONFORMANCE_CONFORMING, CONFORMANCE_NONCONFORMING, CONFORMANCE_PARTIAL, CONFORMANCE_NOT_APPLICABLE,
            CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING, CONFORMANCE_EXCEPTION_ACTIVE,
        ) && finding.summary.isNotBlank() && finding.confidence in 0.0..1.0 &&
        (finding.disposition in setOf(CONFORMANCE_NOT_APPLICABLE, CONFORMANCE_UNKNOWN) || finding.citations.isNotEmpty()) &&
        finding.citations.all { it.path.isNotBlank() && it.contentHash.matches(Regex("[0-9a-f]{64}")) && it.observation.isNotBlank() }

private fun validBacklog(nodes: List<BacklogProposalNode>, findingIds: Set<String>): Boolean {
    if (nodes.isEmpty()) return true
    if (nodes.map { it.nodeId }.distinct().size != nodes.size) return false
    val byId = nodes.associateBy { it.nodeId }
    val positions = nodes.mapIndexed { index, node -> node.nodeId to index }.toMap()
    if (nodes.count { it.type == BACKLOG_EPIC && it.parentNodeId == null } != 1) return false
    return nodes.all { node ->
        node.nodeId.matches(Regex("[A-Z0-9_-]{2,96}")) && node.title.isNotBlank() && node.description.isNotBlank() &&
            node.type in setOf(BACKLOG_EPIC, BACKLOG_STORY, BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) &&
            node.findingIds.isNotEmpty() && node.findingIds.all(findingIds::contains) &&
            ((node.type == BACKLOG_EPIC && node.parentNodeId == null) || byId[node.parentNodeId]?.let { parent ->
                positions.getValue(parent.nodeId) < positions.getValue(node.nodeId) &&
                    ((node.type == BACKLOG_STORY && parent.type == BACKLOG_EPIC) ||
                        (node.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) && parent.type == BACKLOG_STORY))
            } == true)
    }
}

private fun engineeringStandardHash(standard: EngineeringStandardRevision): String = sha256(Json.encodeToString(standard))
private fun repositoryConformanceScanHash(scan: RepositoryConformanceScan): String = sha256(Json.encodeToString(scan))
private fun conformanceBacklogAdmissionHash(admission: ConformanceBacklogAdmission): String = sha256(Json.encodeToString(admission))
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
