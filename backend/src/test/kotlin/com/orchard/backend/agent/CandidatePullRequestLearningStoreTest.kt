package com.orchard.backend.agent

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidatePullRequestLearningStoreTest {
    @Test
    fun `accepted candidate outcome becomes one replayable retrieval-only learning episode`() {
        val directory = createTempDirectory("orchard-candidate-learning-")
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val reviews = TransientCandidatePullRequestReviewStore()
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        val reviewService = CandidatePullRequestReviewService(pullRequests, reviews, dispositionService = dispositions)
        listOf(CANDIDATE_REVIEW_CODE, CANDIDATE_REVIEW_INTENT, CANDIDATE_REVIEW_DESIGN, CANDIDATE_REVIEW_INTEGRATION).forEach { kind ->
            reviewService.submit(CandidatePullRequestReviewSubmission(pullRequest.pullRequestId, kind, "$kind-reviewer", emptyList()))
        }
        val learning = CandidatePullRequestLearningService(
            pullRequests,
            reviews,
            TransientCandidatePullRequestCorrectionStore(),
            object : CandidatePullRequestDispositionStore {
                override fun load() = dispositions.dispositions()
                override fun appendNext(create: (Long) -> CandidatePullRequestDisposition) = error("read-only test adapter")
            },
            FileCandidatePullRequestLearningStore(directory),
        )

        val recorded = learning.reconcile().single()

        assertEquals(CANDIDATE_DISPOSITION_ACCEPTED, recorded.outcome)
        assertEquals(pullRequest.hash, recorded.pullRequestHash)
        assertEquals(4, recorded.reviewHashes.size)
        assertEquals(0, learning.reconcile().size)
        val recovered = CandidatePullRequestLearningService(
            pullRequests, reviews, TransientCandidatePullRequestCorrectionStore(),
            object : CandidatePullRequestDispositionStore {
                override fun load() = dispositions.dispositions()
                override fun appendNext(create: (Long) -> CandidatePullRequestDisposition) = error("read-only test adapter")
            },
            FileCandidatePullRequestLearningStore(directory),
        )
        assertEquals(listOf(recorded), recovered.episodes())
        assertTrue(recovered.recall("answer behavior").contains(recorded))
    }

    private fun pullRequest(pullRequestId: Long): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId, runId = 7, workPackageId = 3, workPackageHash = "a".repeat(64), baseRevision = "b".repeat(40),
            candidateRevision = "c".repeat(40), changedPaths = listOf("src/Main.kt"), implementationClaims = listOf("The answer behavior is observable."),
            checks = listOf("./gradlew test"), evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Passed.")),
            deviations = emptyList(), createdAt = "2026-07-27T00:00:00Z", hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}