package com.orchard.backend.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatePullRequestAttentionServiceTest {
    @Test
    fun `clarification and escalation each block their candidate through distinct durable dispatches`() {
        listOf(REVIEW_CORRECTION_CLARIFICATION, REVIEW_CORRECTION_ESCALATION).forEach { target ->
            val pullRequests = TransientCandidatePullRequestStore()
            val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
            val corrections = TransientCandidatePullRequestCorrectionStore()
            val review = newCandidatePullRequestReview(
                1, pullRequest, CANDIDATE_REVIEW_INTENT, "intent-reviewer",
                listOf(CandidatePullRequestReviewFinding("Need", "An explicit response is required.", "BLOCKER", target, listOf("d".repeat(64)))),
            )
            corrections.appendNext { correctionId -> newCandidatePullRequestCorrection(correctionId, review, pullRequest, target, review.findings) }
            val dispositions = CandidatePullRequestDispositionService(pullRequests)
            dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REPAIR_REQUIRED, "Review requires attention.")
            val service = CandidatePullRequestAttentionService(
                target, corrections, TransientCandidatePullRequestCorrectionDispatchStore(), dispositions,
            )

            val dispatch = service.tick()

            assertEquals(target, dispatch?.correctionTarget)
            assertEquals(CANDIDATE_CORRECTION_DISPATCHED, dispatch?.status)
            assertEquals(CANDIDATE_DISPOSITION_BLOCKED, dispositions.dispositions(pullRequest.pullRequestId).last().status)
            assertEquals(null, service.tick())
        }
    }

    private fun pullRequest(pullRequestId: Long): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId, runId = 7, workPackageId = 3, workPackageHash = "a".repeat(64), baseRevision = "b".repeat(40),
            candidateRevision = "c".repeat(40), changedPaths = listOf("src/Main.kt"), implementationClaims = listOf("Observed behavior."),
            checks = listOf("./gradlew test"), evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Passed.")),
            deviations = emptyList(), createdAt = "2026-07-27T00:00:00Z", hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}