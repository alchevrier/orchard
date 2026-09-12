package com.orchard.backend.attention

import kotlinx.serialization.Serializable

const val ATTENTION_PLANNING_SUITE_ID = "attention-planning-v1"
const val ATTENTION_PLANNING_TASK_ID = "candidate-pr-correction-lineage"

@Serializable
data class AttentionPlanningOwner(
    val scopeId: String,
    val scopeKind: AttentionScopeKind,
    val path: String,
    val disposition: String,
    val allowedAction: String,
    val expectedBehavior: String,
    val verificationMethod: String,
)

@Serializable
data class AttentionPlanningTask(
    val suiteId: String,
    val taskId: String,
    val objective: String,
    val invariants: List<String>,
    val owners: List<AttentionPlanningOwner>,
)

@Serializable
data class AttentionPlanningOperation(
    val scopeId: String,
    val scopeKind: AttentionScopeKind,
    val path: String,
    val disposition: String,
    val action: String,
    val expectedBehavior: String,
    val verificationMethod: String,
)

@Serializable
data class AttentionPlanningProposal(
    val summary: String,
    val operations: List<AttentionPlanningOperation>,
    val claims: List<String> = emptyList(),
)

@Serializable
data class AttentionPlanningScore(
    val complete: Boolean,
    val ownerCoverage: Int,
    val ownerTotal: Int,
    val scopeCoverage: Int,
    val scopeTotal: Int,
    val missingOwners: List<String>,
    val missingScopes: List<String>,
    val unexpectedPaths: List<String>,
    val duplicatePaths: List<String>,
    val correlationMismatches: List<String>,
    val actionMismatches: List<String>,
    val missingVerificationMethods: List<String>,
    val verificationMismatches: List<String>,
    val unsupportedLifecycleClaims: List<String>,
)

@Serializable
data class AttentionPlanningNormalization(
    val proposal: AttentionPlanningProposal,
    val corrections: List<AttentionPlanningCorrection>,
)

@Serializable
data class AttentionPlanningCorrection(
    val path: String,
    val fields: List<String>,
)

fun candidateCorrectionLineagePlanningTask(): AttentionPlanningTask = AttentionPlanningTask(
    suiteId = ATTENTION_PLANNING_SUITE_ID,
    taskId = ATTENTION_PLANNING_TASK_ID,
    objective = "Complete Milestone 10.2.2 candidate PR correction-lineage proof without reimplementing existing authorities.",
    invariants = listOf(
        "Rejected candidates remain immutable and inspectable.",
        "Ordinary compile, test, verification, and review defects produce bounded successors without broad repository reanalysis.",
        "Stale authority, missing scope, design contradiction, clarification, and escalation route to their owning authority.",
        "Independent review cannot approve its own correction or promotion.",
        "A plan cannot claim implementation or verification evidence.",
    ),
    owners = listOf(
        AttentionPlanningOwner("REQ-LINEAGE", AttentionScopeKind.PERSISTENCE, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestStore.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Candidate PRs already pin immutable parent lineage and repository revisions.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestStoreTest --no-daemon"),
        AttentionPlanningOwner("REQ-LIFECYCLE", AttentionScopeKind.PERSISTENCE, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestDispositionStore.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Append-only dispositions already preserve review, repair, acceptance, blocking, and supersession transitions.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestDispositionStoreTest --no-daemon"),
        AttentionPlanningOwner("REQ-REVIEWS", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestReviewService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Typed reviews already compile findings into target-specific correction authority.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestReviewServiceTest --no-daemon"),
        AttentionPlanningOwner("REQ-REPAIR", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestCorrectionDispatchService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Candidate-repair corrections already dispatch independently and replay durably.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestCorrectionDispatchServiceTest --no-daemon"),
        AttentionPlanningOwner("REQ-PACKAGE", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestWorkPackageRecompileService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Work-package findings already compile bounded successor package authority.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestWorkPackageRecompileServiceTest --no-daemon"),
        AttentionPlanningOwner("REQ-DESIGN", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestDesignRevisionService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Design contradictions already create revision requests that await explicit successor admission.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestDesignRevisionServiceTest --no-daemon"),
        AttentionPlanningOwner("REQ-ATTENTION", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestAttentionService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "Clarification and escalation already block candidates without becoming coding retries.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CandidatePullRequestAttentionServiceTest --no-daemon"),
        AttentionPlanningOwner("REQ-CODING", AttentionScopeKind.IMPLEMENTATION, "backend/src/main/kotlin/com/orchard/backend/agent/CodingWorkerService.kt", ATTENTION_DISPOSITION_EVIDENCE_ONLY, "READ_SOURCE", "The coding worker already links corrective child candidates and preserves failed candidate lineage.", "./gradlew :backend:jvmTest --tests com.orchard.backend.agent.CodingWorkerTest --no-daemon"),
        AttentionPlanningOwner("REQ-PROOF", AttentionScopeKind.VERIFICATION, "backend/src/test/kotlin/com/orchard/backend/SelfContainedCorrectionIntegrationTest.kt", ATTENTION_DISPOSITION_ACTIONABLE, "MODIFY", "Extend the self-contained public-API restart scenario to inject compile, test, code-review, and intent-review defects and prove bounded successors remain one lineage.", "./gradlew :backend:jvmTest --tests com.orchard.backend.SelfContainedCorrectionIntegrationTest --no-daemon"),
    ),
)

fun scoreAttentionPlanningProposal(
    task: AttentionPlanningTask,
    proposal: AttentionPlanningProposal,
): AttentionPlanningScore {
    val expectedByPath = task.owners.associateBy { it.path }
    val proposedByPath = proposal.operations.groupBy { it.path }
    val missingOwners = task.owners.map { it.path }.filter { it !in proposedByPath }.sorted()
    val unexpectedPaths = proposal.operations.map { it.path }.filter { it !in expectedByPath }.distinct().sorted()
    val duplicatePaths = proposedByPath.filterValues { it.size != 1 }.keys.sorted()
    val correlationMismatches = proposal.operations.mapNotNull { operation ->
        expectedByPath[operation.path]?.takeIf {
            it.scopeId != operation.scopeId || it.scopeKind != operation.scopeKind || it.disposition != operation.disposition
        }?.let {
            "${operation.path}: expected ${it.scopeId}/${it.scopeKind}/${it.disposition}, proposed ${operation.scopeId}/${operation.scopeKind}/${operation.disposition}"
        }
    }.sorted()
    val actionMismatches = proposal.operations.mapNotNull { operation ->
        expectedByPath[operation.path]?.takeIf { it.allowedAction != operation.action }
            ?.let { "${operation.path}: expected ${it.allowedAction}, proposed ${operation.action}" }
    }.sorted()
    val missingVerification = task.owners.mapNotNull { owner ->
        proposedByPath[owner.path].orEmpty().singleOrNull()
            ?.takeIf { it.verificationMethod.isBlank() }
            ?.let { owner.path }
    }.sorted()
    val verificationMismatches = task.owners.mapNotNull { owner ->
        proposedByPath[owner.path].orEmpty().singleOrNull()
            ?.takeIf { it.verificationMethod.isNotBlank() && it.verificationMethod != owner.verificationMethod }
            ?.let { owner.path }
    }.sorted()
    val expectedScopeIds = task.owners.mapTo(linkedSetOf()) { it.scopeId }
    val coveredScopeIds = proposal.operations.mapTo(linkedSetOf()) { it.scopeId }
    val missingScopes = (expectedScopeIds - coveredScopeIds).sorted()
    val unsupportedClaims = proposal.claims.filter(::unsupportedPlanningClaim)
    val complete = listOf(
        missingOwners,
        missingScopes,
        unexpectedPaths,
        duplicatePaths,
        correlationMismatches,
        actionMismatches,
        missingVerification,
        verificationMismatches,
        unsupportedClaims,
    ).all { it.isEmpty() } && proposal.operations.size == task.owners.size
    return AttentionPlanningScore(
        complete = complete,
        ownerCoverage = task.owners.size - missingOwners.size,
        ownerTotal = task.owners.size,
        scopeCoverage = expectedScopeIds.size - missingScopes.size,
        scopeTotal = expectedScopeIds.size,
        missingOwners = missingOwners,
        missingScopes = missingScopes,
        unexpectedPaths = unexpectedPaths,
        duplicatePaths = duplicatePaths,
        correlationMismatches = correlationMismatches,
        actionMismatches = actionMismatches,
        missingVerificationMethods = missingVerification,
        verificationMismatches = verificationMismatches,
        unsupportedLifecycleClaims = unsupportedClaims,
    )
}

fun normalizeAttentionPlanningProposal(
    task: AttentionPlanningTask,
    proposal: AttentionPlanningProposal,
): AttentionPlanningNormalization {
    val expectedByPath = task.owners.associateBy { it.path }
    val corrections = mutableListOf<AttentionPlanningCorrection>()
    val operations = proposal.operations.map { operation ->
        val owner = expectedByPath[operation.path] ?: return@map operation
        val fields = buildList {
            if (operation.scopeId != owner.scopeId) add("scopeId")
            if (operation.scopeKind != owner.scopeKind) add("scopeKind")
            if (operation.disposition != owner.disposition) add("disposition")
            if (operation.action != owner.allowedAction) add("action")
            if (operation.expectedBehavior != owner.expectedBehavior) add("expectedBehavior")
            if (operation.verificationMethod != owner.verificationMethod) add("verificationMethod")
        }
        val normalized = operation.copy(
            scopeId = owner.scopeId,
            scopeKind = owner.scopeKind,
            disposition = owner.disposition,
            action = owner.allowedAction,
            expectedBehavior = owner.expectedBehavior,
            verificationMethod = owner.verificationMethod,
        )
        if (fields.isNotEmpty()) corrections += AttentionPlanningCorrection(operation.path, fields)
        normalized
    }
    return AttentionPlanningNormalization(
        proposal.copy(operations = operations),
        corrections.sortedBy { it.path },
    )
}

private fun unsupportedPlanningClaim(claim: String): Boolean = LIFECYCLE_CLAIM.containsMatchIn(claim)

private val LIFECYCLE_CLAIM = Regex(
    "\\b(?:(?:is|are|was|were|has been|have been)\\s+(?:implemented|completed|verified|deployed|migrated|accepted)|" +
        "(?:tests?|verification)\\s+(?:has |have )?passed)\\b",
    RegexOption.IGNORE_CASE,
)