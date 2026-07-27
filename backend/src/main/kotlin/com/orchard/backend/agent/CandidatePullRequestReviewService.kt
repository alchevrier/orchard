package com.orchard.backend.agent

import kotlinx.serialization.Serializable

enum class CandidatePullRequestReviewMutationStatus {
    RECORDED,
    PULL_REQUEST_NOT_FOUND,
    REVIEW_ALREADY_RECORDED,
    INVALID_REVIEW,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CandidatePullRequestReviewSubmission(
    val pullRequestId: Long,
    val kind: String,
    val reviewer: String,
    val findings: List<CandidatePullRequestReviewFinding>,
)

@Serializable
data class CandidatePullRequestReviewMutationResult(
    val status: CandidatePullRequestReviewMutationStatus,
    val review: CandidatePullRequestReview? = null,
    val corrections: List<CandidatePullRequestCorrection> = emptyList(),
    val diagnostic: String = "",
)

class CandidatePullRequestReviewService(
    private val pullRequestStore: CandidatePullRequestStore,
    private val reviewStore: CandidatePullRequestReviewStore,
    private val correctionStore: CandidatePullRequestCorrectionStore = TransientCandidatePullRequestCorrectionStore(),
    private val dispositionService: CandidatePullRequestDispositionService? = null,
) {
    fun reviews(pullRequestId: Long? = null): List<CandidatePullRequestReview> = reviewStore.load()
        .asSequence()
        .filter { pullRequestId == null || it.pullRequestId == pullRequestId }
        .toList()

    fun corrections(pullRequestId: Long? = null): List<CandidatePullRequestCorrection> = correctionStore.load()
        .asSequence()
        .filter { pullRequestId == null || it.pullRequestId == pullRequestId }
        .toList()

    @Synchronized
    fun submit(submission: CandidatePullRequestReviewSubmission): CandidatePullRequestReviewMutationResult {
        val pullRequest = pullRequestStore.load().singleOrNull { it.pullRequestId == submission.pullRequestId }
            ?: return CandidatePullRequestReviewMutationResult(
                CandidatePullRequestReviewMutationStatus.PULL_REQUEST_NOT_FOUND,
                diagnostic = "Candidate PR ${submission.pullRequestId} does not exist.",
            )
        val existingReview = reviewStore.load().singleOrNull {
            it.pullRequestId == pullRequest.pullRequestId && it.kind == submission.kind
        }
        if (existingReview != null) {
            return CandidatePullRequestReviewMutationResult(
                CandidatePullRequestReviewMutationStatus.REVIEW_ALREADY_RECORDED,
                existingReview,
                reconcileCorrections(pullRequest, existingReview),
                diagnostic = "Candidate PR ${pullRequest.pullRequestId} already has a ${submission.kind} review.",
            )
        }
        return runCatching {
            reviewStore.appendNext { reviewId ->
                newCandidatePullRequestReview(
                    reviewId,
                    pullRequest,
                    submission.kind,
                    submission.reviewer,
                    submission.findings,
                )
            }
        }.fold(
            onSuccess = { review ->
                val corrections = reconcileCorrections(pullRequest, review)
                if (review.findings.isNotEmpty()) {
                    dispositionService?.record(
                        pullRequest.pullRequestId,
                        CANDIDATE_DISPOSITION_REPAIR_REQUIRED,
                        "Candidate review ${review.reviewId} requires correction.",
                        corrections.firstOrNull()?.correctionId,
                    )
                } else if (reviews(pullRequest.pullRequestId).let { recorded ->
                        recorded.map { it.kind }.toSet() == REQUIRED_REVIEW_KINDS &&
                            recorded.all { it.status == CANDIDATE_REVIEW_CONFORMING }
                    }
                ) {
                    dispositionService?.record(
                        pullRequest.pullRequestId,
                        CANDIDATE_DISPOSITION_ACCEPTED,
                        "All independent candidate review authorities recorded conforming judgments.",
                    )
                }
                CandidatePullRequestReviewMutationResult(
                    CandidatePullRequestReviewMutationStatus.RECORDED,
                    review,
                    corrections,
                )
            },
            onFailure = { error ->
                CandidatePullRequestReviewMutationResult(
                    if (error is IllegalArgumentException) {
                        CandidatePullRequestReviewMutationStatus.INVALID_REVIEW
                    } else {
                        CandidatePullRequestReviewMutationStatus.STORAGE_UNAVAILABLE
                    },
                    diagnostic = error.message.orEmpty(),
                )
            },
        )
    }

    @Synchronized
    fun reconcileCorrections(): List<CandidatePullRequestCorrection> {
        val pullRequests = pullRequestStore.load().associateBy { it.pullRequestId }
        return reviewStore.load().flatMap { review ->
            val pullRequest = pullRequests[review.pullRequestId]
                ?.takeIf { it.hash == review.pullRequestHash }
                ?: return@flatMap emptyList()
            reconcileCorrections(pullRequest, review)
        }
    }

    private fun reconcileCorrections(
        pullRequest: CandidatePullRequest,
        review: CandidatePullRequestReview,
    ): List<CandidatePullRequestCorrection> {
        val existingTargets = correctionStore.load()
            .asSequence()
            .filter { it.reviewId == review.reviewId && it.reviewHash == review.hash }
            .mapTo(hashSetOf()) { it.correctionTarget }
        return review.findings.groupBy { it.correctionTarget }
            .toSortedMap()
            .mapNotNull { (correctionTarget, findings) ->
                if (correctionTarget in existingTargets) return@mapNotNull null
                correctionStore.appendNext { correctionId ->
                    newCandidatePullRequestCorrection(correctionId, review, pullRequest, correctionTarget, findings)
                }
            }
    }
}

private val REQUIRED_REVIEW_KINDS = setOf(
    CANDIDATE_REVIEW_CODE,
    CANDIDATE_REVIEW_INTENT,
    CANDIDATE_REVIEW_DESIGN,
    CANDIDATE_REVIEW_INTEGRATION,
)