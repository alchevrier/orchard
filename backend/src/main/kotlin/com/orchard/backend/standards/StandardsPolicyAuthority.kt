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

const val STANDARD_SCOPE_ORGANIZATION = "ORGANIZATION"
const val STANDARD_SCOPE_PROJECT = "PROJECT"
const val STANDARD_SCOPE_MODULE = "MODULE"
const val STANDARD_SCOPE_WORK_ITEM = "WORK_ITEM"
const val OVERLAY_ADD = "ADD"
const val OVERLAY_TIGHTEN = "TIGHTEN"
const val OVERLAY_DISABLE = "DISABLE"

@Serializable
data class StandardPolicyScope(
    val type: String,
    val projectId: Int? = null,
    val modulePath: String? = null,
    val workItemId: Int? = null,
)

@Serializable
data class StandardOverlayAdjustment(
    val operation: String,
    val practiceId: String,
    val addedPractice: EngineeringPractice? = null,
    val additionalRequirement: String? = null,
    val additionalEvidence: List<String> = emptyList(),
    val additionalRemediation: String? = null,
    val elevateToRequired: Boolean = false,
    val mandatoryFloor: Boolean = false,
)

@Serializable
data class StandardOverlayRevision(
    val overlayId: Long,
    val revision: Int,
    val scope: StandardPolicyScope,
    val name: String,
    val adjustments: List<StandardOverlayAdjustment>,
    val actor: String,
    val createdAt: String,
    val previousHash: String? = null,
    val hash: String,
)

@Serializable
data class ExceptionControlEvidence(
    val path: String,
    val contentHash: String,
    val description: String,
)

@Serializable
data class StandardsExceptionProposal(
    val proposalId: Long,
    val projectId: Int,
    val scope: StandardPolicyScope,
    val effectiveStandardHash: String,
    val repositoryRevision: String,
    val practiceIds: List<String>,
    val rationale: String,
    val compensatingControls: List<String>,
    val controlEvidence: List<ExceptionControlEvidence>,
    val requestedFrom: String,
    val requestedUntil: String,
    val sourceResolutionCaseId: Long? = null,
    val sourceResolutionAdmissionId: Long? = null,
    val actor: String,
    val proposedAt: String,
    val hash: String,
)

@Serializable
data class StandardsExceptionAdmission(
    val admissionId: Long,
    val proposalId: Long,
    val proposalHash: String,
    val grantor: String,
    val activeFrom: String,
    val expiresAt: String,
    val admittedAt: String,
    val hash: String,
)

@Serializable
data class StandardsExceptionRevocation(
    val revocationId: Long,
    val admissionId: Long,
    val admissionHash: String,
    val actor: String,
    val reason: String,
    val revokedAt: String,
    val hash: String,
)

@Serializable
data class EffectivePractice(
    val practice: EngineeringPractice,
    val mandatoryFloor: Boolean,
    val sourceOverlayIds: List<Long> = emptyList(),
)

@Serializable
data class StandardCompositionConflict(
    val overlayId: Long,
    val practiceId: String,
    val diagnostic: String,
)

@Serializable
data class EffectiveEngineeringStandard(
    val projectId: Int,
    val baseStandardId: Long,
    val baseStandardRevision: Int,
    val baseStandardHash: String,
    val targetScope: StandardPolicyScope,
    val practices: List<EffectivePractice>,
    val overlayHashes: List<String>,
    val conflicts: List<StandardCompositionConflict>,
    val hash: String,
)

@Serializable
data class AppliedStandardsException(
    val admissionId: Long,
    val admissionHash: String,
    val proposalId: Long,
    val proposalHash: String,
    val scope: StandardPolicyScope,
    val practiceIds: List<String>,
    val activeFrom: String,
    val expiresAt: String,
)

@Serializable
private data class StandardsPolicyEnvelope(
    val version: Int = 1,
    val overlay: StandardOverlayRevision? = null,
    val proposal: StandardsExceptionProposal? = null,
    val admission: StandardsExceptionAdmission? = null,
    val revocation: StandardsExceptionRevocation? = null,
    val checksum: String,
)

interface StandardsPolicyStore {
    fun overlays(): List<StandardOverlayRevision>
    fun proposals(): List<StandardsExceptionProposal>
    fun admissions(): List<StandardsExceptionAdmission>
    fun revocations(): List<StandardsExceptionRevocation>
    fun appendOverlay(overlay: StandardOverlayRevision)
    fun appendProposal(proposal: StandardsExceptionProposal)
    fun appendAdmission(admission: StandardsExceptionAdmission)
    fun appendRevocation(revocation: StandardsExceptionRevocation)
}

class TransientStandardsPolicyStore : StandardsPolicyStore {
    private val overlayRecords = mutableListOf<StandardOverlayRevision>()
    private val proposalRecords = mutableListOf<StandardsExceptionProposal>()
    private val admissionRecords = mutableListOf<StandardsExceptionAdmission>()
    private val revocationRecords = mutableListOf<StandardsExceptionRevocation>()

    override fun overlays() = overlayRecords.toList()
    override fun proposals() = proposalRecords.toList()
    override fun admissions() = admissionRecords.toList()
    override fun revocations() = revocationRecords.toList()

    override fun appendOverlay(overlay: StandardOverlayRevision) {
        validateOverlayAppend(overlayRecords, overlay)
        overlayRecords += overlay
    }

    override fun appendProposal(proposal: StandardsExceptionProposal) {
        validateProposalAppend(proposalRecords, proposal)
        proposalRecords += proposal
    }

    override fun appendAdmission(admission: StandardsExceptionAdmission) {
        validateExceptionAdmissionAppend(proposalRecords, admissionRecords, admission)
        admissionRecords += admission
    }

    override fun appendRevocation(revocation: StandardsExceptionRevocation) {
        validateRevocationAppend(admissionRecords, revocationRecords, revocation)
        revocationRecords += revocation
    }
}

class FileStandardsPolicyStore(private val directory: Path) : StandardsPolicyStore {
    private val path = directory.resolve("standards-policy.jsonl")
    private val lockPath = directory.resolve("standards-policy.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized override fun overlays() = load().mapNotNull { it.overlay }
    @Synchronized override fun proposals() = load().mapNotNull { it.proposal }
    @Synchronized override fun admissions() = load().mapNotNull { it.admission }
    @Synchronized override fun revocations() = load().mapNotNull { it.revocation }
    @Synchronized override fun appendOverlay(overlay: StandardOverlayRevision) = append(overlay = overlay)
    @Synchronized override fun appendProposal(proposal: StandardsExceptionProposal) = append(proposal = proposal)
    @Synchronized override fun appendAdmission(admission: StandardsExceptionAdmission) = append(admission = admission)
    @Synchronized override fun appendRevocation(revocation: StandardsExceptionRevocation) = append(revocation = revocation)

    private fun append(
        overlay: StandardOverlayRevision? = null,
        proposal: StandardsExceptionProposal? = null,
        admission: StandardsExceptionAdmission? = null,
        revocation: StandardsExceptionRevocation? = null,
    ) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val records = load()
                overlay?.let { validateOverlayAppend(records.mapNotNull { record -> record.overlay }, it) }
                proposal?.let { validateProposalAppend(records.mapNotNull { record -> record.proposal }, it) }
                admission?.let {
                    validateExceptionAdmissionAppend(
                        records.mapNotNull { record -> record.proposal },
                        records.mapNotNull { record -> record.admission },
                        it,
                    )
                }
                revocation?.let {
                    validateRevocationAppend(
                        records.mapNotNull { record -> record.admission },
                        records.mapNotNull { record -> record.revocation },
                        it,
                    )
                }
                val payload = overlay?.let(json::encodeToString)
                    ?: proposal?.let(json::encodeToString)
                    ?: admission?.let(json::encodeToString)
                    ?: json.encodeToString(requireNotNull(revocation))
                val envelope = StandardsPolicyEnvelope(
                    overlay = overlay,
                    proposal = proposal,
                    admission = admission,
                    revocation = revocation,
                    checksum = policySha256(payload),
                )
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
    }

    private fun load(): List<StandardsPolicyEnvelope> = loadRecoverableJsonl(path, "standards policy") { line, _ ->
        val envelope = json.decodeFromString<StandardsPolicyEnvelope>(line)
        require(envelope.version == 1 && listOfNotNull(
            envelope.overlay, envelope.proposal, envelope.admission, envelope.revocation,
        ).size == 1) { "Standards policy record shape is invalid" }
        val payload = envelope.overlay?.let(json::encodeToString)
            ?: envelope.proposal?.let(json::encodeToString)
            ?: envelope.admission?.let(json::encodeToString)
            ?: json.encodeToString(requireNotNull(envelope.revocation))
        require(envelope.checksum == policySha256(payload)) { "Standards policy checksum mismatch" }
        envelope
    }.also { records ->
        val overlays = mutableListOf<StandardOverlayRevision>()
        val proposals = mutableListOf<StandardsExceptionProposal>()
        val admissions = mutableListOf<StandardsExceptionAdmission>()
        val revocations = mutableListOf<StandardsExceptionRevocation>()
        records.forEach { record ->
            record.overlay?.let { validateOverlayAppend(overlays, it); overlays += it }
            record.proposal?.let { validateProposalAppend(proposals, it); proposals += it }
            record.admission?.let { validateExceptionAdmissionAppend(proposals, admissions, it); admissions += it }
            record.revocation?.let { validateRevocationAppend(admissions, revocations, it); revocations += it }
        }
    }
}

fun newStandardOverlayRevision(overlay: StandardOverlayRevision): StandardOverlayRevision =
    overlay.copy(hash = policySha256(Json.encodeToString(overlay.copy(hash = ""))))

fun newStandardsExceptionProposal(proposal: StandardsExceptionProposal): StandardsExceptionProposal =
    proposal.copy(hash = policySha256(Json.encodeToString(proposal.copy(hash = ""))))

fun newStandardsExceptionAdmission(admission: StandardsExceptionAdmission): StandardsExceptionAdmission =
    admission.copy(hash = policySha256(Json.encodeToString(admission.copy(hash = ""))))

fun newStandardsExceptionRevocation(revocation: StandardsExceptionRevocation): StandardsExceptionRevocation =
    revocation.copy(hash = policySha256(Json.encodeToString(revocation.copy(hash = ""))))

fun composeEffectiveStandard(
    base: EngineeringStandardRevision,
    targetScope: StandardPolicyScope,
    overlays: List<StandardOverlayRevision>,
): EffectiveEngineeringStandard {
    require(targetScope.projectId == base.projectId && validPolicyScope(targetScope)) { "Effective standard target scope is invalid" }
    val latestByScope = overlays.groupBy { scopeKey(it.scope) }.mapNotNull { (_, revisions) -> revisions.maxByOrNull { it.revision } }
    val applicable = latestByScope.filter { policyScopeAppliesTo(it.scope, targetScope) }
        .sortedWith(compareBy<StandardOverlayRevision> { scopeRank(it.scope) }.thenBy { it.scope.modulePath?.count { char -> char == '/' } ?: 0 }.thenBy { it.overlayId })
    val practices = base.practices.associateBy { it.practiceId }.mapValues { EffectivePractice(it.value, false) }.toMutableMap()
    val conflicts = mutableListOf<StandardCompositionConflict>()
    applicable.forEach { overlay ->
        overlay.adjustments.forEach { adjustment ->
            val current = practices[adjustment.practiceId]
            when (adjustment.operation) {
                OVERLAY_ADD -> if (current != null) {
                    conflicts += StandardCompositionConflict(overlay.overlayId, adjustment.practiceId, "ADD cannot replace an inherited practice.")
                } else {
                    practices[adjustment.practiceId] = EffectivePractice(
                        requireNotNull(adjustment.addedPractice), adjustment.mandatoryFloor, listOf(overlay.overlayId),
                    )
                }
                OVERLAY_TIGHTEN -> if (current == null) {
                    conflicts += StandardCompositionConflict(overlay.overlayId, adjustment.practiceId, "TIGHTEN requires an inherited practice.")
                } else {
                    val practice = current.practice.copy(
                        severity = if (adjustment.elevateToRequired) PRACTICE_SEVERITY_REQUIRED else current.practice.severity,
                        requirement = listOfNotNull(current.practice.requirement, adjustment.additionalRequirement?.trim()).joinToString("\n"),
                        requiredEvidence = (current.practice.requiredEvidence + adjustment.additionalEvidence).distinct(),
                        remediation = listOfNotNull(current.practice.remediation, adjustment.additionalRemediation?.trim()).joinToString("\n"),
                    )
                    practices[adjustment.practiceId] = EffectivePractice(
                        practice, current.mandatoryFloor || adjustment.mandatoryFloor,
                        current.sourceOverlayIds + overlay.overlayId,
                    )
                }
                OVERLAY_DISABLE -> if (current == null) {
                    conflicts += StandardCompositionConflict(overlay.overlayId, adjustment.practiceId, "DISABLE requires an inherited practice.")
                } else if (current.mandatoryFloor) {
                    conflicts += StandardCompositionConflict(overlay.overlayId, adjustment.practiceId, "A mandatory floor cannot be disabled.")
                } else {
                    practices[adjustment.practiceId] = current.copy(
                        practice = current.practice.copy(enabled = false),
                        sourceOverlayIds = current.sourceOverlayIds + overlay.overlayId,
                    )
                }
            }
        }
    }
    val draft = EffectiveEngineeringStandard(
        projectId = base.projectId,
        baseStandardId = base.standardId,
        baseStandardRevision = base.revision,
        baseStandardHash = base.hash,
        targetScope = targetScope,
        practices = practices.values.sortedBy { it.practice.practiceId },
        overlayHashes = applicable.map { it.hash },
        conflicts = conflicts,
        hash = "",
    )
    return draft.copy(hash = policySha256(Json.encodeToString(draft)))
}

fun validEffectiveStandard(standard: EffectiveEngineeringStandard): Boolean =
    standard.hash == policySha256(Json.encodeToString(standard.copy(hash = ""))) &&
        standard.conflicts.isEmpty() && validPolicyScope(standard.targetScope) &&
        standard.practices.map { it.practice.practiceId }.distinct().size == standard.practices.size &&
        standard.practices.all { validEngineeringPractice(it.practice) && it.sourceOverlayIds.distinct().size == it.sourceOverlayIds.size } &&
        standard.overlayHashes.all(POLICY_SHA256::matches)

private fun validateOverlayAppend(existing: List<StandardOverlayRevision>, overlay: StandardOverlayRevision) {
    require(overlay.overlayId > 0 && overlay.revision > 0 && validPolicyScope(overlay.scope) && overlay.name.isNotBlank() &&
        overlay.actor.isNotBlank() && overlay.createdAt.isNotBlank() && overlay.adjustments.isNotEmpty()) {
        "Standards overlay identity is invalid"
    }
    require(overlay.adjustments.map { it.practiceId }.distinct().size == overlay.adjustments.size &&
        overlay.adjustments.all(::validAdjustment)) { "Standards overlay adjustments are invalid" }
    val prior = existing.filter { scopeKey(it.scope) == scopeKey(overlay.scope) }.maxByOrNull { it.revision }
    require(overlay.revision == (prior?.revision ?: 0) + 1 && overlay.previousHash == prior?.hash &&
        (prior == null || overlay.overlayId == prior.overlayId)) { "Standards overlay does not extend current scope authority" }
    require(overlay.hash == newStandardOverlayRevision(overlay.copy(hash = "")).hash) { "Standards overlay hash is invalid" }
}

private fun validateProposalAppend(existing: List<StandardsExceptionProposal>, proposal: StandardsExceptionProposal) {
    require(proposal.proposalId > 0 && proposal.projectId > 0 && proposal.scope.projectId == proposal.projectId &&
        proposal.scope.type != STANDARD_SCOPE_ORGANIZATION && validPolicyScope(proposal.scope) &&
        proposal.effectiveStandardHash.matches(POLICY_SHA256) && proposal.repositoryRevision.matches(POLICY_GIT_REVISION)) {
        "Standards exception proposal authority is invalid"
    }
    require(proposal.practiceIds.isNotEmpty() && proposal.practiceIds.distinct().size == proposal.practiceIds.size &&
        proposal.practiceIds.all(POLICY_PRACTICE_ID::matches) && proposal.rationale.isNotBlank() &&
        proposal.compensatingControls.isNotEmpty() && proposal.compensatingControls.all(String::isNotBlank) &&
        proposal.controlEvidence.isNotEmpty() && proposal.controlEvidence.all(::validEvidence) &&
        proposal.requestedFrom < proposal.requestedUntil && proposal.actor.isNotBlank() && proposal.proposedAt.isNotBlank()) {
        "Standards exception proposal content is invalid"
    }
    require((proposal.sourceResolutionCaseId == null) == (proposal.sourceResolutionAdmissionId == null)) {
        "Standards exception resolution source is incomplete"
    }
    require(existing.none { it.proposalId == proposal.proposalId || it.hash == proposal.hash }) { "Standards exception proposal already exists" }
    require(proposal.hash == newStandardsExceptionProposal(proposal.copy(hash = "")).hash) { "Standards exception proposal hash is invalid" }
}

private fun validateExceptionAdmissionAppend(
    proposals: List<StandardsExceptionProposal>,
    existing: List<StandardsExceptionAdmission>,
    admission: StandardsExceptionAdmission,
) {
    val proposal = proposals.singleOrNull { it.proposalId == admission.proposalId }
    require(proposal != null && proposal.hash == admission.proposalHash) { "Standards exception admission proposal is invalid" }
    require(admission.admissionId > 0 && admission.grantor.isNotBlank() && admission.admittedAt.isNotBlank() &&
        admission.activeFrom >= proposal.requestedFrom && admission.expiresAt <= proposal.requestedUntil &&
        admission.activeFrom < admission.expiresAt) { "Standards exception admission bounds are invalid" }
    require(existing.none { it.admissionId == admission.admissionId || it.proposalId == admission.proposalId }) {
        "Standards exception proposal is already admitted"
    }
    require(admission.hash == newStandardsExceptionAdmission(admission.copy(hash = "")).hash) { "Standards exception admission hash is invalid" }
}

private fun validateRevocationAppend(
    admissions: List<StandardsExceptionAdmission>,
    existing: List<StandardsExceptionRevocation>,
    revocation: StandardsExceptionRevocation,
) {
    val admission = admissions.singleOrNull { it.admissionId == revocation.admissionId }
    require(admission != null && admission.hash == revocation.admissionHash && revocation.revocationId > 0 &&
        revocation.actor.isNotBlank() && revocation.reason.isNotBlank() && revocation.revokedAt.isNotBlank()) {
        "Standards exception revocation is invalid"
    }
    require(existing.none { it.revocationId == revocation.revocationId || it.admissionId == revocation.admissionId }) {
        "Standards exception admission is already revoked"
    }
    require(revocation.hash == newStandardsExceptionRevocation(revocation.copy(hash = "")).hash) {
        "Standards exception revocation hash is invalid"
    }
}

private fun validAdjustment(adjustment: StandardOverlayAdjustment): Boolean {
    if (!adjustment.practiceId.matches(POLICY_PRACTICE_ID)) return false
    return when (adjustment.operation) {
        OVERLAY_ADD -> adjustment.addedPractice?.practiceId == adjustment.practiceId && validEngineeringPractice(adjustment.addedPractice) &&
            adjustment.additionalRequirement == null && adjustment.additionalEvidence.isEmpty() && adjustment.additionalRemediation == null
        OVERLAY_TIGHTEN -> adjustment.addedPractice == null &&
            (!adjustment.additionalRequirement.isNullOrBlank() || adjustment.additionalEvidence.any(String::isNotBlank) ||
                !adjustment.additionalRemediation.isNullOrBlank() || adjustment.elevateToRequired || adjustment.mandatoryFloor)
        OVERLAY_DISABLE -> adjustment.addedPractice == null && adjustment.additionalRequirement == null &&
            adjustment.additionalEvidence.isEmpty() && adjustment.additionalRemediation == null && !adjustment.elevateToRequired && !adjustment.mandatoryFloor
        else -> false
    }
}

fun validPolicyScope(scope: StandardPolicyScope): Boolean = when (scope.type) {
    STANDARD_SCOPE_ORGANIZATION -> scope.projectId == null && scope.modulePath == null && scope.workItemId == null
    STANDARD_SCOPE_PROJECT -> scope.projectId?.let { it > 0 } == true && scope.modulePath == null && scope.workItemId == null
    STANDARD_SCOPE_MODULE -> scope.projectId?.let { it > 0 } == true && validModulePath(scope.modulePath) && scope.workItemId == null
    STANDARD_SCOPE_WORK_ITEM -> scope.projectId?.let { it > 0 } == true &&
        (scope.modulePath == null || validModulePath(scope.modulePath)) && scope.workItemId?.let { it > 0 } == true
    else -> false
}

private fun validModulePath(path: String?): Boolean = path != null && path.isNotBlank() && !path.startsWith('/') &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." }

private fun validEvidence(evidence: ExceptionControlEvidence): Boolean = validModulePath(evidence.path) &&
    evidence.contentHash.matches(POLICY_SHA256) && evidence.description.isNotBlank()

private fun scopeKey(scope: StandardPolicyScope): String = listOf(
    scope.type, scope.projectId.orEmpty(), scope.modulePath.orEmpty(), scope.workItemId.orEmpty(),
).joinToString(":")

fun policyScopeAppliesTo(overlay: StandardPolicyScope, target: StandardPolicyScope): Boolean = when (overlay.type) {
    STANDARD_SCOPE_ORGANIZATION -> true
    STANDARD_SCOPE_PROJECT -> overlay.projectId == target.projectId
    STANDARD_SCOPE_MODULE -> overlay.projectId == target.projectId && target.modulePath != null &&
        (target.modulePath == overlay.modulePath || target.modulePath.startsWith("${overlay.modulePath}/"))
    STANDARD_SCOPE_WORK_ITEM -> overlay.projectId == target.projectId && overlay.workItemId == target.workItemId &&
        (overlay.modulePath == null || overlay.modulePath == target.modulePath)
    else -> false
}

private fun scopeRank(scope: StandardPolicyScope): Int = when (scope.type) {
    STANDARD_SCOPE_ORGANIZATION -> 0
    STANDARD_SCOPE_PROJECT -> 1
    STANDARD_SCOPE_MODULE -> 2
    STANDARD_SCOPE_WORK_ITEM -> 3
    else -> Int.MAX_VALUE
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
private fun policySha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val POLICY_SHA256 = Regex("[0-9a-f]{64}")
private val POLICY_GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val POLICY_PRACTICE_ID = Regex("[A-Z][A-Z0-9_-]{2,63}")