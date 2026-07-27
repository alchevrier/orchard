package com.orchard.backend.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatePullRequestCorrectionDispatchServiceTest {
    @Test
    fun `dispatcher records one terminal dispatch for candidate repair only`() {
        val corrections = TransientCandidatePullRequestCorrectionStore()
        val repair = corrections.appendNext { correctionId -> correction(correctionId, REVIEW_CORRECTION_CANDIDATE_REPAIR) }
        corrections.appendNext { correctionId -> correction(correctionId, REVIEW_CORRECTION_DESIGN_REVISION) }
        val dispatches = TransientCandidatePullRequestCorrectionDispatchStore()
        var gatewayCalls = 0
        val service = CandidatePullRequestCorrectionDispatchService(
            corrections,
            dispatches,
            CandidateCorrectionRepairGateway {
                gatewayCalls += 1
                CandidateCorrectionDispatchOutcome.DISPATCHED
            },
        )

        val dispatch = service.tick()

        assertEquals(repair.correctionId, dispatch?.correctionId)
        assertEquals(CANDIDATE_CORRECTION_DISPATCHED, dispatch?.status)
        assertEquals(1, gatewayCalls)
        assertNull(service.tick())
        assertEquals(listOf(dispatch), service.dispatches())
    }

    @Test
    fun `dispatcher leaves repair authority pending while its workflow is incomplete`() {
        val corrections = TransientCandidatePullRequestCorrectionStore()
        corrections.appendNext { correctionId -> correction(correctionId, REVIEW_CORRECTION_CANDIDATE_REPAIR) }
        val service = CandidatePullRequestCorrectionDispatchService(
            corrections,
            TransientCandidatePullRequestCorrectionDispatchStore(),
            CandidateCorrectionRepairGateway { CandidateCorrectionDispatchOutcome.DEFERRED },
        )

        assertNull(service.tick())
        assertEquals(emptyList(), service.dispatches())
    }

    @Test
    fun `deferred repair does not block a later ready correction`() {
        val corrections = TransientCandidatePullRequestCorrectionStore()
        val deferred = corrections.appendNext { correctionId -> correction(correctionId, REVIEW_CORRECTION_CANDIDATE_REPAIR) }
        val ready = corrections.appendNext { correctionId -> correction(correctionId, REVIEW_CORRECTION_CANDIDATE_REPAIR) }
        val service = CandidatePullRequestCorrectionDispatchService(
            corrections,
            TransientCandidatePullRequestCorrectionDispatchStore(),
            CandidateCorrectionRepairGateway { correction ->
                if (correction.correctionId == deferred.correctionId) CandidateCorrectionDispatchOutcome.DEFERRED
                else CandidateCorrectionDispatchOutcome.DISPATCHED
            },
        )

        val dispatch = service.tick()

        assertEquals(ready.correctionId, dispatch?.correctionId)
        assertEquals(listOf(dispatch), service.dispatches())
    }

    private fun correction(correctionId: Long, correctionTarget: String): CandidatePullRequestCorrection {
        val draft = CandidatePullRequestCorrection(
            correctionId = correctionId,
            reviewId = correctionId,
            reviewHash = "a".repeat(64),
            pullRequestId = 4,
            pullRequestHash = "b".repeat(64),
            runId = 7,
            workPackageId = 3,
            workPackageHash = "c".repeat(64),
            correctionTarget = correctionTarget,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "The Inbox continues independent conversations.",
                    observation = "The observed candidate requires correction.",
                    severity = "BLOCKER",
                    correctionTarget = correctionTarget,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestCorrectionHash(draft))
    }
}