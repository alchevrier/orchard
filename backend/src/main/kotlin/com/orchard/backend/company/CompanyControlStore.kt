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

const val ROLE_IMPLEMENTER = "IMPLEMENTER"
const val ROLE_ANALYST_DESIGNER = "ANALYST_DESIGNER"
const val ROLE_ARCHITECTURE_AUDITOR = "ARCHITECTURE_AUDITOR"
const val ROLE_QUALITY_AUDITOR = "QUALITY_AUDITOR"

const val RISK_LOW = "LOW"
const val RISK_MEDIUM = "MEDIUM"
const val RISK_HIGH = "HIGH"
const val RISK_CRITICAL = "CRITICAL"

const val AUDIT_CONFORMING = "CONFORMING"
const val AUDIT_VIOLATION = "VIOLATION"
const val AUDIT_EVIDENCE_STALE = "EVIDENCE_STALE"
const val AUDIT_NOT_ASSESSED = "NOT_ASSESSED"

@Serializable
data class StaffAssignment(
    val assignmentId: Long,
    val projectId: Int,
    val runId: Long,
    val role: String,
    val risk: String,
    val bindingFingerprint: String,
    val model: String,
    val rationale: String,
    val evidenceSampleCount: Int,
    val confidence: Double,
    val independentFromAssignmentId: Long? = null,
    val assignedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class ArchitectureRule(
    val ruleId: String,
    val projectId: Int,
    val genesisRevision: Int,
    val genesisHash: String,
    val sourceDecisionId: String? = null,
    val componentIds: List<String> = emptyList(),
    val kind: String,
    val statement: String,
    val repositoryPaths: List<String> = emptyList(),
    val severity: String,
    val requiresIndependentAudit: Boolean,
    val hash: String,
)

@Serializable
data class ArchitectureRuleSet(
    val ruleSetId: Long,
    val projectId: Int,
    val revision: Int,
    val genesisRevision: Int,
    val genesisHash: String,
    val rules: List<ArchitectureRule>,
    val compiledAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class AuditFinding(
    val ruleId: String,
    val status: String,
    val summary: String,
    val evidenceIds: List<Long> = emptyList(),
)

@Serializable
data class AuditJudgment(
    val auditId: Long,
    val projectId: Int,
    val runId: Long,
    val assignmentId: Long,
    val role: String,
    val candidateRevision: String,
    val candidateDiffHash: String,
    val genesisHash: String,
    val ruleSetHash: String,
    val findings: List<AuditFinding>,
    val status: String,
    val rationale: String,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class StaffEscalation(
    val escalationId: Long,
    val projectId: Int,
    val runId: Long,
    val fromAssignmentId: Long,
    val requiredRole: String,
    val reason: String,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CompanyAcceptance(
    val acceptanceId: Long,
    val projectId: Int,
    val runId: Long,
    val candidateRevision: String,
    val candidateDiffHash: String,
    val genesisHash: String,
    val auditIds: List<Long>,
    val acceptedBy: String,
    val acceptedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class LocalPromotion(
    val promotionId: Long,
    val projectId: Int,
    val runId: Long,
    val acceptanceId: Long,
    val baseRevision: String,
    val candidateRevision: String,
    val destinationRevision: String,
    val promotedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CompanyControlEvent(
    val eventId: Long,
    val ruleSet: ArchitectureRuleSet? = null,
    val assignment: StaffAssignment? = null,
    val audit: AuditJudgment? = null,
    val escalation: StaffEscalation? = null,
    val acceptance: CompanyAcceptance? = null,
    val promotion: LocalPromotion? = null,
)

interface CompanyControlStore {
    fun loadEvents(): List<CompanyControlEvent>
    fun append(event: CompanyControlEvent)
}

@Serializable
data class CompanyControlView(
    val ruleSets: List<ArchitectureRuleSet>,
    val assignments: List<StaffAssignment>,
    val audits: List<AuditJudgment>,
    val escalations: List<StaffEscalation>,
    val acceptances: List<CompanyAcceptance>,
    val promotions: List<LocalPromotion>,
)

fun companyControlView(events: List<CompanyControlEvent>): CompanyControlView = CompanyControlView(
    ruleSets = events.mapNotNull { it.ruleSet },
    assignments = events.mapNotNull { it.assignment },
    audits = events.mapNotNull { it.audit },
    escalations = events.mapNotNull { it.escalation },
    acceptances = events.mapNotNull { it.acceptance },
    promotions = events.mapNotNull { it.promotion },
)

class TransientCompanyControlStore : CompanyControlStore {
    private val events = mutableListOf<CompanyControlEvent>()

    @Synchronized
    override fun loadEvents(): List<CompanyControlEvent> = events.toList()

    @Synchronized
    override fun append(event: CompanyControlEvent) {
        validateCompanyControlEvent(event, events.size + 1L, events)
        events += event
    }
}

class FileCompanyControlStore(private val directory: Path) : CompanyControlStore {
    private val path = directory.resolve("company-control.jsonl")
    private val lockPath = directory.resolve("company-control.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<CompanyControlEvent> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<CompanyControlEvent> {
        val events = mutableListOf<CompanyControlEvent>()
        return loadRecoverableJsonl(path, "company-control") { line, recordNumber ->
            val envelope = json.decodeFromString<CompanyControlEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported company control format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in company control event $recordNumber"
            }
            validateCompanyControlEvent(envelope.value, recordNumber.toLong(), events)
            events += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun append(event: CompanyControlEvent) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val existing = loadUnlocked()
                validateCompanyControlEvent(event, existing.size + 1L, existing)
                val payload = json.encodeToString(event)
                val line = json.encodeToString(
                    CompanyControlEnvelope(value = event, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun companyRecordHash(value: String): String = stagedPlanHash(value)

private fun validateCompanyControlEvent(
    event: CompanyControlEvent,
    expectedEventId: Long,
    preceding: List<CompanyControlEvent>,
) {
    require(event.eventId == expectedEventId) { "Expected company control event ID $expectedEventId" }
    require(listOfNotNull(event.ruleSet, event.assignment, event.audit, event.escalation, event.acceptance, event.promotion).size == 1) {
        "Company control event must contain exactly one payload"
    }
    event.ruleSet?.let { ruleSet ->
        require(ruleSet.ruleSetId == event.eventId && ruleSet.projectId > 0 && ruleSet.revision > 0)
        require(ruleSet.genesisRevision > 0 && ruleSet.genesisHash.matches(SHA256) && ruleSet.rules.isNotEmpty())
        val previous = preceding.mapNotNull { it.ruleSet }.filter { it.projectId == ruleSet.projectId }
        require(ruleSet.revision == previous.size + 1)
        require(ruleSet.rules.map { it.ruleId }.distinct().size == ruleSet.rules.size)
        require(ruleSet.rules.all { rule ->
            rule.projectId == ruleSet.projectId && rule.genesisRevision == ruleSet.genesisRevision &&
                rule.genesisHash == ruleSet.genesisHash && rule.ruleId.isNotBlank() && rule.kind.isNotBlank() &&
                rule.statement.isNotBlank() && rule.severity in RISK_CLASSES &&
                rule.hash == companyRecordHash(rule.copy(hash = "").toString())
        })
        require(ruleSet.hash == companyRecordHash(ruleSet.copy(hash = "").toString()))
    }
    event.assignment?.let { assignment ->
        require(assignment.assignmentId == event.eventId && assignment.projectId > 0 && assignment.runId > 0)
        require(assignment.role in STAFF_ROLES && assignment.risk in RISK_CLASSES)
        require(assignment.bindingFingerprint.matches(SHA256) && assignment.model.isNotBlank())
        require(assignment.evidenceSampleCount >= 0 && assignment.confidence in 0.0..1.0 && assignment.rationale.isNotBlank())
        assignment.independentFromAssignmentId?.let { priorId ->
            require(preceding.mapNotNull { it.assignment }.any { it.assignmentId == priorId && it.runId == assignment.runId })
        }
        require(assignment.hash == companyRecordHash(assignment.copy(hash = "").toString()))
    }
    event.audit?.let { audit ->
        require(audit.auditId == event.eventId && audit.projectId > 0 && audit.runId > 0)
        val assignment = preceding.mapNotNull { it.assignment }.singleOrNull { it.assignmentId == audit.assignmentId }
            ?: throw IllegalArgumentException("Audit references an unknown assignment")
        require(assignment.runId == audit.runId && assignment.role == audit.role)
        require(
            audit.candidateRevision.matches(GIT_HASH) && audit.candidateDiffHash.matches(SHA256) &&
                audit.genesisHash.matches(SHA256) && audit.ruleSetHash.matches(SHA256)
        )
        require(preceding.mapNotNull { it.ruleSet }.any {
            it.projectId == audit.projectId && it.genesisHash == audit.genesisHash && it.hash == audit.ruleSetHash
        })
        require(audit.status in AUDIT_STATES && audit.findings.isNotEmpty() && audit.rationale.isNotBlank())
        require(audit.findings.all { it.status in AUDIT_STATES && it.ruleId.isNotBlank() && it.summary.isNotBlank() })
        require(audit.hash == companyRecordHash(audit.copy(hash = "").toString()))
    }
    event.escalation?.let { escalation ->
        require(escalation.escalationId == event.eventId && escalation.reason.isNotBlank())
        require(escalation.requiredRole in STAFF_ROLES)
        require(preceding.mapNotNull { it.assignment }.any {
            it.assignmentId == escalation.fromAssignmentId && it.runId == escalation.runId
        })
        require(escalation.hash == companyRecordHash(escalation.copy(hash = "").toString()))
    }
    event.acceptance?.let { acceptance ->
        require(acceptance.acceptanceId == event.eventId && acceptance.acceptedBy.isNotBlank())
        require(acceptance.candidateRevision.matches(GIT_HASH) && acceptance.candidateDiffHash.matches(SHA256))
        require(acceptance.genesisHash.matches(SHA256) && acceptance.auditIds.isNotEmpty())
        val audits = preceding.mapNotNull { it.audit }.filter { it.auditId in acceptance.auditIds }
        require(audits.size == acceptance.auditIds.size && audits.all {
            it.runId == acceptance.runId && it.candidateRevision == acceptance.candidateRevision && it.status == AUDIT_CONFORMING
        })
        require(acceptance.hash == companyRecordHash(acceptance.copy(hash = "").toString()))
    }
    event.promotion?.let { promotion ->
        require(promotion.promotionId == event.eventId)
        require(listOf(promotion.baseRevision, promotion.candidateRevision, promotion.destinationRevision).all { it.matches(GIT_HASH) })
        require(preceding.mapNotNull { it.acceptance }.any {
            it.acceptanceId == promotion.acceptanceId && it.runId == promotion.runId &&
                it.candidateRevision == promotion.candidateRevision
        })
        require(promotion.hash == companyRecordHash(promotion.copy(hash = "").toString()))
    }
}

@Serializable
private data class CompanyControlEnvelope(
    val version: Int = 1,
    val value: CompanyControlEvent,
    val checksum: String,
)

private val STAFF_ROLES = setOf(ROLE_ANALYST_DESIGNER, ROLE_IMPLEMENTER, ROLE_ARCHITECTURE_AUDITOR, ROLE_QUALITY_AUDITOR)
private val RISK_CLASSES = setOf(RISK_LOW, RISK_MEDIUM, RISK_HIGH, RISK_CRITICAL)
private val AUDIT_STATES = setOf(AUDIT_CONFORMING, AUDIT_VIOLATION, AUDIT_EVIDENCE_STALE, AUDIT_NOT_ASSESSED)
private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_HASH = Regex("[0-9a-fA-F]{40}")