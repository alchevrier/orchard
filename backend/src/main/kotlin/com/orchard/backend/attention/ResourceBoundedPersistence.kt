package com.orchard.backend.attention

import com.orchard.backend.workspace.stagedPlanHash
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