package com.orchard.backend.standards

import com.orchard.backend.workspace.RepositoryBindingStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable

const val EXCEPTION_PENDING = "PENDING"
const val EXCEPTION_ACTIVE = "ACTIVE"
const val EXCEPTION_EXPIRED = "EXPIRED"
const val EXCEPTION_REVOKED = "REVOKED"
const val EXCEPTION_SUPERSEDED = "SUPERSEDED"
const val EXCEPTION_INVALIDATED = "INVALIDATED"

@Serializable
data class StandardOverlaySubmission(
    val scope: StandardPolicyScope,
    val name: String,
    val adjustments: List<StandardOverlayAdjustment>,
    val actor: String = "HUMAN",
)

@Serializable
data class StandardsExceptionProposalSubmission(
    val scope: StandardPolicyScope,
    val practiceIds: List<String>,
    val rationale: String,
    val compensatingControls: List<String>,
    val controlEvidence: List<ExceptionControlEvidence>,
    val requestedFrom: String,
    val requestedUntil: String,
    val actor: String = "HUMAN",
)

@Serializable
data class StandardsExceptionAdmissionSubmission(
    val grantor: String,
    val activeFrom: String,
    val expiresAt: String,
)

@Serializable
data class StandardsExceptionRevocationSubmission(
    val actor: String,
    val reason: String,
)

@Serializable
data class StandardsExceptionView(
    val proposal: StandardsExceptionProposal,
    val admission: StandardsExceptionAdmission? = null,
    val revocation: StandardsExceptionRevocation? = null,
    val state: String,
)

@Serializable
data class StandardsPolicyView(
    val effectiveStandard: EffectiveEngineeringStandard? = null,
    val overlays: List<StandardOverlayRevision> = emptyList(),
    val exceptions: List<StandardsExceptionView> = emptyList(),
)

enum class StandardsPolicyMutationStatus {
    RECORDED,
    PROJECT_NOT_FOUND,
    STANDARD_NOT_FOUND,
    PROPOSAL_NOT_FOUND,
    ADMISSION_NOT_FOUND,
    INVALID_REQUEST,
    POLICY_CONFLICT,
    REPOSITORY_UNAVAILABLE,
    REPOSITORY_DIRTY,
    REPOSITORY_DRIFTED,
    STALE_POLICY,
    ALREADY_DECIDED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class StandardsPolicyMutationResult(
    val status: StandardsPolicyMutationStatus,
    val overlay: StandardOverlayRevision? = null,
    val proposal: StandardsExceptionProposal? = null,
    val admission: StandardsExceptionAdmission? = null,
    val revocation: StandardsExceptionRevocation? = null,
    val diagnostic: String = "",
)

class StandardsPolicyService(
    private val standardsStore: EngineeringStandardsStore,
    private val repositoryBindings: RepositoryBindingStore,
    private val store: StandardsPolicyStore = TransientStandardsPolicyStore(),
    private val clock: () -> Instant = Instant::now,
) {
    fun view(projectId: Int, scope: StandardPolicyScope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, projectId)): StandardsPolicyView {
        val effective = effectiveStandard(projectId, scope)
        val proposals = store.proposals().filter { it.projectId == projectId }
        val admissions = store.admissions().associateBy { it.proposalId }
        val revocations = store.revocations().associateBy { it.admissionId }
        val now = clock()
        val head = repositoryBindings.resolveHead(projectId)
        return StandardsPolicyView(
            effectiveStandard = effective,
            overlays = store.overlays().filter { it.scope.projectId == null || it.scope.projectId == projectId },
            exceptions = proposals.map { proposal ->
                val admission = admissions[proposal.proposalId]
                val revocation = admission?.let { revocations[it.admissionId] }
                val proposalEffective = effectiveStandard(projectId, proposal.scope)
                val authorityActive = admission != null && proposalEffective != null && head != null &&
                    activeExceptions(proposalEffective, head.path, head.commitHash, now).any { it.admissionId == admission.admissionId }
                StandardsExceptionView(
                    proposal, admission, revocation,
                    exceptionState(proposal, admission, revocation, proposalEffective, authorityActive, now),
                )
            },
        )
    }

    fun effectiveStandard(projectId: Int, scope: StandardPolicyScope): EffectiveEngineeringStandard? {
        val base = standardsStore.standards().filter { it.projectId == projectId }.maxByOrNull { it.revision } ?: return null
        return effectiveStandard(base, scope)
    }

    fun effectiveStandard(base: EngineeringStandardRevision, scope: StandardPolicyScope): EffectiveEngineeringStandard =
        composeEffectiveStandard(base, scope, store.overlays())

    @Synchronized
    fun appendOverlay(projectId: Int, submission: StandardOverlaySubmission): StandardsPolicyMutationResult {
        if (standardsStore.standards().none { it.projectId == projectId }) return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STANDARD_NOT_FOUND)
        if (submission.scope.projectId != null && submission.scope.projectId != projectId) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = "Overlay scope belongs to another project.")
        }
        val prior = store.overlays().filter { sameScope(it.scope, submission.scope) }.maxByOrNull { it.revision }
        val overlay = runCatching {
            newStandardOverlayRevision(
                StandardOverlayRevision(
                    overlayId = prior?.overlayId ?: (store.overlays().maxOfOrNull { it.overlayId } ?: 0L) + 1L,
                    revision = (prior?.revision ?: 0) + 1,
                    scope = submission.scope,
                    name = submission.name.trim(),
                    adjustments = prior?.adjustments.orEmpty().filter { priorAdjustment ->
                        submission.adjustments.none { it.practiceId == priorAdjustment.practiceId }
                    } + submission.adjustments,
                    actor = submission.actor.trim(),
                    createdAt = clock().toString(),
                    previousHash = prior?.hash,
                    hash = "",
                )
            )
        }.getOrElse { return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = it.message.orEmpty()) }
        return runCatching { store.appendOverlay(overlay) }.fold(
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.RECORDED, overlay = overlay) },
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    @Synchronized
    fun proposeException(projectId: Int, submission: StandardsExceptionProposalSubmission): StandardsPolicyMutationResult {
        val effective = effectiveStandard(projectId, submission.scope)
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STANDARD_NOT_FOUND)
        if (effective.conflicts.isNotEmpty()) return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.POLICY_CONFLICT)
        val enabled = effective.practices.filter { it.practice.enabled }.map { it.practice.practiceId }.toSet()
        if (submission.scope.projectId != projectId || submission.practiceIds.any { it !in enabled }) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = "Exception scope or practices are invalid.")
        }
        val head = repositoryBindings.resolveHead(projectId)
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.REPOSITORY_UNAVAILABLE)
        if (!head.clean) return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.REPOSITORY_DIRTY)
        if (!evidenceMatches(head.path, submission.controlEvidence)) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = "Compensating-control evidence is not revision-bound.")
        }
        val proposal = runCatching {
            val requestedFrom = Instant.parse(submission.requestedFrom)
            val requestedUntil = Instant.parse(submission.requestedUntil)
            require(requestedFrom.isBefore(requestedUntil)) { "Exception request bounds are invalid." }
            require(requestedUntil.isAfter(clock())) { "Exception request expiry must be in the future." }
            newStandardsExceptionProposal(
                StandardsExceptionProposal(
                    proposalId = (store.proposals().maxOfOrNull { it.proposalId } ?: 0L) + 1L,
                    projectId = projectId,
                    scope = submission.scope,
                    effectiveStandardHash = effective.hash,
                    repositoryRevision = head.commitHash,
                    practiceIds = submission.practiceIds,
                    rationale = submission.rationale.trim(),
                    compensatingControls = submission.compensatingControls.map(String::trim),
                    controlEvidence = submission.controlEvidence,
                    requestedFrom = requestedFrom.toString(),
                    requestedUntil = requestedUntil.toString(),
                    actor = submission.actor.trim(),
                    proposedAt = clock().toString(),
                    hash = "",
                )
            )
        }.getOrElse { return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = it.message.orEmpty()) }
        return appendProposal(proposal)
    }

    @Synchronized
    fun admitException(proposalId: Long, submission: StandardsExceptionAdmissionSubmission): StandardsPolicyMutationResult {
        val proposal = store.proposals().singleOrNull { it.proposalId == proposalId }
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.PROPOSAL_NOT_FOUND)
        store.admissions().singleOrNull { it.proposalId == proposalId }?.let {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.ALREADY_DECIDED, admission = it)
        }
        val effective = effectiveStandard(proposal.projectId, proposal.scope)
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STANDARD_NOT_FOUND)
        if (effective.hash != proposal.effectiveStandardHash) return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STALE_POLICY)
        val head = repositoryBindings.resolveHead(proposal.projectId)
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.REPOSITORY_UNAVAILABLE)
        if (!head.clean || !isAncestor(head.path, proposal.repositoryRevision, head.commitHash)) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.REPOSITORY_DRIFTED)
        }
        if (!evidenceMatches(head.path, proposal.controlEvidence)) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.REPOSITORY_DRIFTED, diagnostic = "Compensating-control evidence changed.")
        }
        val admission = runCatching {
            val activeFrom = Instant.parse(submission.activeFrom)
            val expiresAt = Instant.parse(submission.expiresAt)
            require(!activeFrom.isBefore(Instant.parse(proposal.requestedFrom)) &&
                !expiresAt.isAfter(Instant.parse(proposal.requestedUntil)) && activeFrom.isBefore(expiresAt)) {
                "Exception admission must remain within the requested bounds."
            }
            require(expiresAt.isAfter(clock())) { "Exception admission expiry must be in the future." }
            newStandardsExceptionAdmission(
                StandardsExceptionAdmission(
                    admissionId = (store.admissions().maxOfOrNull { it.admissionId } ?: 0L) + 1L,
                    proposalId = proposal.proposalId,
                    proposalHash = proposal.hash,
                    grantor = submission.grantor.trim(),
                    activeFrom = activeFrom.toString(),
                    expiresAt = expiresAt.toString(),
                    admittedAt = clock().toString(),
                    hash = "",
                )
            )
        }.getOrElse { return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = it.message.orEmpty()) }
        return runCatching { store.appendAdmission(admission) }.fold(
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.RECORDED, admission = admission) },
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    @Synchronized
    fun revokeException(admissionId: Long, submission: StandardsExceptionRevocationSubmission): StandardsPolicyMutationResult {
        val admission = store.admissions().singleOrNull { it.admissionId == admissionId }
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.ADMISSION_NOT_FOUND)
        store.revocations().singleOrNull { it.admissionId == admissionId }?.let {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.ALREADY_DECIDED, revocation = it)
        }
        val revocation = runCatching {
            newStandardsExceptionRevocation(
                StandardsExceptionRevocation(
                    (store.revocations().maxOfOrNull { it.revocationId } ?: 0L) + 1L,
                    admission.admissionId,
                    admission.hash,
                    submission.actor.trim(),
                    submission.reason.trim(),
                    clock().toString(),
                    "",
                )
            )
        }.getOrElse { return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST, diagnostic = it.message.orEmpty()) }
        return runCatching { store.appendRevocation(revocation) }.fold(
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.RECORDED, revocation = revocation) },
            { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    fun activeExceptions(
        effective: EffectiveEngineeringStandard,
        repositoryPath: String,
        repositoryRevision: String,
        at: Instant = clock(),
    ): List<AppliedStandardsException> {
        if (effective.conflicts.isNotEmpty()) return emptyList()
        val revocations = store.revocations().associateBy { it.admissionId }
        val proposals = store.proposals().associateBy { it.proposalId }
        return store.admissions().mapNotNull { admission ->
            val proposal = proposals[admission.proposalId] ?: return@mapNotNull null
            val revocation = revocations[admission.admissionId]
            if (proposal.projectId != effective.projectId || proposal.effectiveStandardHash != effective.hash ||
                !policyScopeAppliesTo(proposal.scope, effective.targetScope) || at < Instant.parse(admission.activeFrom) ||
                at >= Instant.parse(admission.expiresAt) || revocation?.let { !Instant.parse(it.revokedAt).isAfter(at) } == true ||
                !isAncestor(repositoryPath, proposal.repositoryRevision, repositoryRevision) ||
                !evidenceMatches(repositoryPath, proposal.controlEvidence)) return@mapNotNull null
            AppliedStandardsException(
                admission.admissionId, admission.hash, proposal.proposalId, proposal.hash, proposal.scope,
                proposal.practiceIds, admission.activeFrom, admission.expiresAt,
            )
        }
    }

    internal fun appendResolutionProposal(proposal: StandardsExceptionProposal): StandardsPolicyMutationResult = appendProposal(proposal)

    @Synchronized
    fun seedExceptionRequest(
        case: CampaignResolutionCase,
        resolutionProposal: CampaignResolutionProposal,
        resolutionAdmission: CampaignResolutionAdmission,
        scan: RepositoryConformanceScan,
    ): StandardsPolicyMutationResult {
        store.proposals().singleOrNull {
            it.sourceResolutionCaseId == case.caseId && it.sourceResolutionAdmissionId == resolutionAdmission.admissionId
        }?.let { return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.ALREADY_DECIDED, proposal = it) }
        if (resolutionProposal.action != RESOLUTION_ACTION_EXCEPTION_REQUEST || resolutionProposal.practiceIds.any { it !in case.practiceIds }) {
            return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.INVALID_REQUEST)
        }
        val scope = scan.effectiveStandard?.targetScope ?: StandardPolicyScope(STANDARD_SCOPE_PROJECT, case.projectId)
        val effective = effectiveStandard(case.projectId, scope)
            ?: return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STANDARD_NOT_FOUND)
        if (effective.hash != (scan.effectiveStandard?.hash ?: run {
                val base = standardsStore.standards().single { it.standardId == scan.standardId && it.revision == scan.standardRevision }
                composeEffectiveStandard(base, scope, emptyList()).hash
            })) return StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STALE_POLICY)
        val evidence = scan.findings.filter { it.practiceId in resolutionProposal.practiceIds }
            .flatMap { it.citations }
            .distinctBy { it.path to it.contentHash }
            .map { ExceptionControlEvidence(it.path, it.contentHash, it.observation) }
        if (evidence.isEmpty()) {
            return StandardsPolicyMutationResult(
                StandardsPolicyMutationStatus.INVALID_REQUEST,
                diagnostic = "An exception request requires repository-bound compensating-control evidence.",
            )
        }
        val now = clock()
        val proposal = newStandardsExceptionProposal(
            StandardsExceptionProposal(
                proposalId = (store.proposals().maxOfOrNull { it.proposalId } ?: 0L) + 1L,
                projectId = case.projectId,
                scope = scope,
                effectiveStandardHash = effective.hash,
                repositoryRevision = scan.repositoryRevision,
                practiceIds = resolutionProposal.practiceIds,
                rationale = resolutionProposal.rationale,
                compensatingControls = listOf(resolutionProposal.instructions),
                controlEvidence = evidence,
                requestedFrom = now.toString(),
                requestedUntil = now.plusSeconds(DEFAULT_EXCEPTION_REQUEST_SECONDS).toString(),
                sourceResolutionCaseId = case.caseId,
                sourceResolutionAdmissionId = resolutionAdmission.admissionId,
                actor = resolutionAdmission.actor,
                proposedAt = now.toString(),
                hash = "",
            )
        )
        return appendProposal(proposal)
    }

    private fun appendProposal(proposal: StandardsExceptionProposal): StandardsPolicyMutationResult = runCatching {
        store.appendProposal(proposal)
    }.fold(
        { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.RECORDED, proposal = proposal) },
        { StandardsPolicyMutationResult(StandardsPolicyMutationStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
    )

    private fun exceptionState(
        proposal: StandardsExceptionProposal,
        admission: StandardsExceptionAdmission?,
        revocation: StandardsExceptionRevocation?,
        effective: EffectiveEngineeringStandard?,
        authorityActive: Boolean,
        now: Instant,
    ): String = when {
        admission == null -> EXCEPTION_PENDING
        revocation != null && !Instant.parse(revocation.revokedAt).isAfter(now) -> EXCEPTION_REVOKED
        effective?.hash != proposal.effectiveStandardHash -> EXCEPTION_SUPERSEDED
        now < Instant.parse(admission.activeFrom) -> EXCEPTION_PENDING
        now >= Instant.parse(admission.expiresAt) -> EXCEPTION_EXPIRED
        !authorityActive -> EXCEPTION_INVALIDATED
        else -> EXCEPTION_ACTIVE
    }

    private fun evidenceMatches(repositoryPath: String, evidence: List<ExceptionControlEvidence>): Boolean {
        val root = runCatching { Path.of(repositoryPath).toRealPath() }.getOrNull() ?: return false
        return evidence.isNotEmpty() && evidence.all { item ->
            val target = root.resolve(item.path).normalize()
            target.startsWith(root) && target != root && Files.isRegularFile(target) && !Files.isSymbolicLink(target) &&
                fileSha256(target) == item.contentHash
        }
    }

    private fun isAncestor(repositoryPath: String, ancestor: String, descendant: String): Boolean = runCatching {
        val process = ProcessBuilder("git", "-C", repositoryPath, "merge-base", "--is-ancestor", ancestor, descendant)
            .redirectErrorStream(true).start()
        process.inputStream.use { it.readBytes() }
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun sameScope(left: StandardPolicyScope, right: StandardPolicyScope): Boolean =
        left.type == right.type && left.projectId == right.projectId && left.modulePath == right.modulePath && left.workItemId == right.workItemId

    private fun fileSha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val DEFAULT_EXCEPTION_REQUEST_SECONDS = 30L * 24L * 60L * 60L
    }
}