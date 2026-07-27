package com.orchard.backend.agent

import com.orchard.backend.workspaceApi
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CandidatePullRequestReviewServiceTest {
    @Test
    fun `service pins review to an existing candidate and rejects duplicate authority`() {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val service = CandidatePullRequestReviewService(pullRequests, TransientCandidatePullRequestReviewStore())
        val submission = CandidatePullRequestReviewSubmission(
            pullRequestId = pullRequest.pullRequestId,
            kind = CANDIDATE_REVIEW_CODE,
            reviewer = "code-reviewer",
            findings = emptyList(),
        )

        assertEquals(CandidatePullRequestReviewMutationStatus.RECORDED, service.submit(submission).status)
        assertEquals(CandidatePullRequestReviewMutationStatus.REVIEW_ALREADY_RECORDED, service.submit(submission).status)
        assertEquals(
            CandidatePullRequestReviewMutationStatus.PULL_REQUEST_NOT_FOUND,
            service.submit(submission.copy(pullRequestId = 99)).status,
        )
    }

    @Test
    fun `service compiles correction authority for each review target`() {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        val service = CandidatePullRequestReviewService(
            pullRequests,
            TransientCandidatePullRequestReviewStore(),
            TransientCandidatePullRequestCorrectionStore(),
            dispositions,
        )

        val result = service.submit(
            CandidatePullRequestReviewSubmission(
                pullRequest.pullRequestId,
                CANDIDATE_REVIEW_INTENT,
                "intent-reviewer",
                listOf(
                    finding(REVIEW_CORRECTION_CANDIDATE_REPAIR),
                    finding(REVIEW_CORRECTION_DESIGN_REVISION),
                ),
            ),
        )

        assertEquals(CandidatePullRequestReviewMutationStatus.RECORDED, result.status)
        assertEquals(
            listOf(REVIEW_CORRECTION_CANDIDATE_REPAIR, REVIEW_CORRECTION_DESIGN_REVISION),
            result.corrections.map { it.correctionTarget },
        )
        assertEquals(result.corrections, service.corrections(pullRequest.pullRequestId))
        assertEquals(
            CANDIDATE_DISPOSITION_REPAIR_REQUIRED,
            dispositions.dispositions(pullRequest.pullRequestId).single().status,
        )
    }

    @Test
    fun `all independent conforming reviews accept the candidate lifecycle`() {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        val service = CandidatePullRequestReviewService(
            pullRequests,
            TransientCandidatePullRequestReviewStore(),
            dispositionService = dispositions,
        )

        listOf(CANDIDATE_REVIEW_CODE, CANDIDATE_REVIEW_INTENT, CANDIDATE_REVIEW_DESIGN, CANDIDATE_REVIEW_INTEGRATION)
            .forEach { kind ->
                assertEquals(
                    CandidatePullRequestReviewMutationStatus.RECORDED,
                    service.submit(CandidatePullRequestReviewSubmission(pullRequest.pullRequestId, kind, "$kind-reviewer", emptyList())).status,
                )
            }

        assertEquals(
            listOf(CANDIDATE_DISPOSITION_REVIEW_REQUIRED, CANDIDATE_DISPOSITION_ACCEPTED),
            dispositions.dispositions(pullRequest.pullRequestId).map { it.status },
        )
    }

    @Test
    fun `review routes submit and filter durable candidate review authority`() = testApplication {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val service = CandidatePullRequestReviewService(pullRequests, TransientCandidatePullRequestReviewStore())
        application { workspaceApi(WorkspaceStore(), candidatePullRequestReviews = service) }

        val submitted = client.post("/api/coding-worker/pull-request-reviews") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    CandidatePullRequestReviewSubmission(
                        pullRequest.pullRequestId,
                        CANDIDATE_REVIEW_CODE,
                        "code-reviewer",
                        emptyList(),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Created, submitted.status)
        val listed = client.get("/api/coding-worker/pull-request-reviews?pullRequestId=${pullRequest.pullRequestId}")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(
            listOf(CANDIDATE_REVIEW_CODE),
            Json.decodeFromString<List<CandidatePullRequestReview>>(listed.bodyAsText()).map { it.kind },
        )
    }

    @Test
    fun `correction routes dispatch and list candidate repair authority`() = testApplication {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val corrections = TransientCandidatePullRequestCorrectionStore()
        val reviews = CandidatePullRequestReviewService(
            pullRequests,
            TransientCandidatePullRequestReviewStore(),
            corrections,
        )
        reviews.submit(
            CandidatePullRequestReviewSubmission(
                pullRequest.pullRequestId,
                CANDIDATE_REVIEW_CODE,
                "code-reviewer",
                listOf(finding(REVIEW_CORRECTION_CANDIDATE_REPAIR)),
            ),
        )
        val dispatcher = CandidatePullRequestCorrectionDispatchService(
            corrections,
            TransientCandidatePullRequestCorrectionDispatchStore(),
            CandidateCorrectionRepairGateway { CandidateCorrectionDispatchOutcome.DISPATCHED },
        )
        application {
            workspaceApi(
                WorkspaceStore(),
                candidatePullRequestReviews = reviews,
                candidatePullRequestCorrectionDispatcher = dispatcher,
            )
        }

        val ticked = client.post("/api/coding-worker/pull-request-corrections/tick")

        assertEquals(HttpStatusCode.Created, ticked.status)
        val listed = client.get("/api/coding-worker/pull-request-correction-dispatches")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(
            listOf(CANDIDATE_CORRECTION_DISPATCHED),
            Json.decodeFromString<List<CandidatePullRequestCorrectionDispatch>>(listed.bodyAsText()).map { it.status },
        )
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

    private fun finding(correctionTarget: String) = CandidatePullRequestReviewFinding(
        criterion = "The Inbox continues independent conversations.",
        observation = "The observed candidate requires correction.",
        severity = "BLOCKER",
        correctionTarget = correctionTarget,
        evidenceHashes = listOf("d".repeat(64)),
    )
}