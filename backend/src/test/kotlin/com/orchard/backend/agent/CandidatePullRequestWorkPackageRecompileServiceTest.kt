package com.orchard.backend.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatePullRequestWorkPackageRecompileServiceTest {
    @Test
    fun `dispatcher records one work package recompilation`() {
        val corrections = TransientCandidatePullRequestCorrectionStore()
        val correction = corrections.appendNext { correctionId -> correction(correctionId) }
        val dispatches = TransientCandidatePullRequestCorrectionDispatchStore()
        val service = CandidatePullRequestWorkPackageRecompileService(
            corrections,
            dispatches,
            WorkPackageCorrectionRecompileGateway { CandidateCorrectionDispatchOutcome.DISPATCHED },
        )

        val dispatch = service.tick()

        assertEquals(correction.correctionId, dispatch?.correctionId)
        assertEquals(REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE, dispatch?.correctionTarget)
        assertEquals(CANDIDATE_CORRECTION_DISPATCHED, dispatch?.status)
        assertNull(service.tick())
    }

    private fun correction(correctionId: Long): CandidatePullRequestCorrection {
        val draft = CandidatePullRequestCorrection(
            correctionId = correctionId,
            reviewId = correctionId,
            reviewHash = "a".repeat(64),
            pullRequestId = 4,
            pullRequestHash = "b".repeat(64),
            runId = 7,
            workPackageId = 3,
            workPackageHash = "c".repeat(64),
            correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "The required behavior remains correct.",
                    observation = "The package requires a focused correction.",
                    severity = "BLOCKER",
                    correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestCorrectionHash(draft))
    }
}