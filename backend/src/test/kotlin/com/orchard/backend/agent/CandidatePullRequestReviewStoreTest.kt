package com.orchard.backend.agent

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CandidatePullRequestReviewStoreTest {
    @Test
    fun `review store replays distinct review authorities for one candidate`() {
        val directory = createTempDirectory("orchard-candidate-pr-reviews-")
        val store = FileCandidatePullRequestReviewStore(directory)
        val pullRequest = pullRequest()
        val codeReview = store.appendNext { reviewId ->
            newCandidatePullRequestReview(reviewId, pullRequest, CANDIDATE_REVIEW_CODE, "code-reviewer", emptyList())
        }
        val intentReview = store.appendNext { reviewId ->
            newCandidatePullRequestReview(
                reviewId,
                pullRequest,
                CANDIDATE_REVIEW_INTENT,
                "intent-reviewer",
                listOf(
                    CandidatePullRequestReviewFinding(
                        criterion = "The Inbox opens an independent conversation.",
                        observation = "The candidate lists a conversation but cannot continue it.",
                        severity = "BLOCKER",
                        correctionTarget = REVIEW_CORRECTION_CANDIDATE_REPAIR,
                        evidenceHashes = listOf("d".repeat(64)),
                    ),
                ),
            )
        }

        assertEquals(listOf(codeReview, intentReview), FileCandidatePullRequestReviewStore(directory).load())
        assertEquals(CANDIDATE_REVIEW_CONFORMING, codeReview.status)
        assertEquals(CANDIDATE_REVIEW_REPAIR_REQUIRED, intentReview.status)
        assertFailsWith<IllegalArgumentException> {
            store.appendNext { reviewId ->
                newCandidatePullRequestReview(reviewId, pullRequest, CANDIDATE_REVIEW_CODE, "second-code-reviewer", emptyList())
            }
        }
    }

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