package com.orchard.backend.agent

import com.orchard.backend.workspace.DesignAuthorityReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatePullRequestDesignRevisionServiceTest {
    @Test
    fun `design correction dispatch blocks candidate pending successor admission`() {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REPAIR_REQUIRED, "Review requires design correction.")
        val corrections = TransientCandidatePullRequestCorrectionStore()
        val correction = corrections.appendNext { correctionId -> correction(correctionId) }
        val service = CandidatePullRequestDesignRevisionService(
            corrections,
            TransientCandidatePullRequestCorrectionDispatchStore(),
            DesignRevisionCorrectionGateway { CandidateCorrectionDispatchOutcome.DISPATCHED },
            dispositions,
        )

        val dispatch = service.tick()

        assertEquals(CANDIDATE_CORRECTION_DISPATCHED, dispatch?.status)
        assertEquals(REVIEW_CORRECTION_DESIGN_REVISION, dispatch?.correctionTarget)
        assertEquals(
            listOf(CANDIDATE_DISPOSITION_REPAIR_REQUIRED, CANDIDATE_DISPOSITION_BLOCKED),
            dispositions.dispositions(pullRequest.pullRequestId).map { it.status },
        )
    }

    @Test
    fun `design revision request store replays pinned correction and design`() {
        val directory = createTempDirectory("orchard-design-revision-request-")
        val correction = correction(1)
        val design = DesignAuthorityReference(4, 3, 2, "e".repeat(64))
        val store = FileCandidatePullRequestDesignRevisionRequestStore(directory)
        val request = store.appendNext { requestId ->
            newCandidatePullRequestDesignRevisionRequest(requestId, correction, design)
        }

        assertEquals(listOf(request), FileCandidatePullRequestDesignRevisionRequestStore(directory).load())
    }

    private fun correction(correctionId: Long): CandidatePullRequestCorrection {
        val draft = CandidatePullRequestCorrection(
            correctionId = correctionId,
            reviewId = 9,
            reviewHash = "a".repeat(64),
            pullRequestId = 1,
            pullRequestHash = "b".repeat(64),
            runId = 7,
            workPackageId = 3,
            workPackageHash = "c".repeat(64),
            correctionTarget = REVIEW_CORRECTION_DESIGN_REVISION,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "The design remains coherent.",
                    observation = "The public boundary requires an admitted design revision.",
                    severity = "BLOCKER",
                    correctionTarget = REVIEW_CORRECTION_DESIGN_REVISION,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestCorrectionHash(draft))
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