package com.orchard.backend.agent

class CandidatePullRequestAttentionService(
    private val correctionTarget: String,
    private val correctionStore: CandidatePullRequestCorrectionStore,
    private val dispatchStore: CandidatePullRequestCorrectionDispatchStore,
    private val dispositionService: CandidatePullRequestDispositionService,
) {
    init {
        require(correctionTarget in setOf(REVIEW_CORRECTION_CLARIFICATION, REVIEW_CORRECTION_ESCALATION)) {
            "Attention dispatch requires clarification or escalation authority."
        }
    }

    @Synchronized
    fun tick(): CandidatePullRequestCorrectionDispatch? {
        val dispatched = dispatchStore.load().mapTo(hashSetOf()) { it.correctionId }
        val correction = correctionStore.load().firstOrNull {
            it.correctionTarget == correctionTarget && it.correctionId !in dispatched
        } ?: return null
        dispositionService.record(
            correction.pullRequestId,
            CANDIDATE_DISPOSITION_BLOCKED,
            if (correctionTarget == REVIEW_CORRECTION_CLARIFICATION) {
                "Candidate review ${correction.reviewId} requires explicit clarification before work can continue."
            } else {
                "Candidate review ${correction.reviewId} requires explicit governance escalation before work can continue."
            },
            correction.correctionId,
        )
        return dispatchStore.appendNext { dispatchId ->
            val draft = CandidatePullRequestCorrectionDispatch(
                dispatchId = dispatchId,
                correctionId = correction.correctionId,
                correctionHash = correction.hash,
                correctionTarget = correction.correctionTarget,
                runId = correction.runId,
                status = CANDIDATE_CORRECTION_DISPATCHED,
                diagnostic = if (correctionTarget == REVIEW_CORRECTION_CLARIFICATION) {
                    "Clarification authority is pending an explicit response."
                } else {
                    "Escalation authority is pending an explicit governance response."
                },
                hash = "",
            )
            draft.copy(hash = candidatePullRequestCorrectionDispatchHash(draft))
        }
    }
}