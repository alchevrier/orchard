package com.orchard.backend.agent

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatePullRequestDispositionStoreTest {
    @Test
    fun `disposition service replays immutable candidate lifecycle`() {
        val directory = createTempDirectory("orchard-candidate-pr-dispositions-")
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val service = CandidatePullRequestDispositionService(
            pullRequests,
            FileCandidatePullRequestDispositionStore(directory),
        )

        val reviewRequired = requireNotNull(
            service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review."),
        )
        val repairRequired = requireNotNull(
            service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REPAIR_REQUIRED, "Code review requires repair.", 7),
        )
        val superseded = requireNotNull(
            service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_SUPERSEDED, "A corrective successor was created."),
        )

        assertEquals(
            listOf(reviewRequired, repairRequired, superseded),
            CandidatePullRequestDispositionService(pullRequests, FileCandidatePullRequestDispositionStore(directory)).dispositions(),
        )
        assertNull(service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_ACCEPTED, "Too late to accept."))
    }

    @Test
    fun `independent audit evidence can block a previously accepted candidate`() {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val service = CandidatePullRequestDispositionService(pullRequests)
        service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_ACCEPTED, "Independent reviews conform.")

        val blocked = service.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_BLOCKED, "Independent audit found a violation.")

        assertEquals(CANDIDATE_DISPOSITION_BLOCKED, blocked?.status)
    }

    private fun pullRequest(pullRequestId: Long): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId = pullRequestId,
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