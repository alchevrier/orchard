package com.orchard.backend.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatePullRequestCorrectionStoreTest {
    @Test
    fun `compiler separates one review into correction targets`() {
        val pullRequest = pullRequest()
        val review = review(pullRequest)
        val corrections = compileCandidatePullRequestCorrections(1, review, pullRequest)

        assertEquals(
            listOf(REVIEW_CORRECTION_CANDIDATE_REPAIR, REVIEW_CORRECTION_DESIGN_REVISION),
            corrections.map { it.correctionTarget },
        )
        assertEquals(listOf("Code behavior is incomplete."), corrections.first().findings.map { it.observation })
        assertEquals(listOf("The design lacks an API boundary."), corrections.last().findings.map { it.observation })
        assertEquals(corrections.map { it.hash }, corrections.map(::candidatePullRequestCorrectionHash))
    }

    private fun review(pullRequest: CandidatePullRequest): CandidatePullRequestReview {
        val draft = CandidatePullRequestReview(
            reviewId = 9,
            pullRequestId = pullRequest.pullRequestId,
            pullRequestHash = pullRequest.hash,
            kind = CANDIDATE_REVIEW_INTENT,
            reviewer = "intent-reviewer",
            findings = listOf(
                finding(REVIEW_CORRECTION_CANDIDATE_REPAIR, "Code behavior is incomplete."),
                finding(REVIEW_CORRECTION_DESIGN_REVISION, "The design lacks an API boundary."),
            ),
            status = CANDIDATE_REVIEW_REPAIR_REQUIRED,
            recordedAt = "2026-07-27T00:00:00Z",
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestReviewHash(draft))
    }

    private fun finding(correctionTarget: String, observation: String) = CandidatePullRequestReviewFinding(
        criterion = "The Inbox continues independent conversations.",
        observation = observation,
        severity = "BLOCKER",
        correctionTarget = correctionTarget,
        evidenceHashes = listOf("d".repeat(64)),
    )

    private fun pullRequest(): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId = 4,
            runId = 7,
            workPackageId = 3,
            workPackageHash = "a".repeat(64),
            baseRevision = "b".repeat(40),
            candidateRevision = "c".repeat(40),
            changedPaths = listOf("src/Main.kt"),
            implementationClaims = listOf("The answer is forty two."),
            checks = listOf("./gradlew test"),
            evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Tests passed.")),
            deviations = emptyList(),
            createdAt = "2026-07-27T00:00:00Z",
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}