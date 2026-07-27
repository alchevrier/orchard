package com.orchard.backend

import com.orchard.backend.agent.CANDIDATE_CORRECTION_DISPATCHED
import com.orchard.backend.agent.CANDIDATE_REVIEW_CODE
import com.orchard.backend.agent.CANDIDATE_REVIEW_INTENT
import com.orchard.backend.agent.CandidateCorrectionDispatchOutcome
import com.orchard.backend.agent.CandidateCorrectionRepairGateway
import com.orchard.backend.agent.CandidatePullRequestAttentionService
import com.orchard.backend.agent.CandidatePullRequestDispositionService
import com.orchard.backend.agent.CandidatePullRequestLearningService
import com.orchard.backend.agent.CandidatePullRequest
import com.orchard.backend.agent.CandidatePullRequestCorrectionDispatchService
import com.orchard.backend.agent.CandidatePullRequestEvidence
import com.orchard.backend.agent.CandidatePullRequestReviewFinding
import com.orchard.backend.agent.CandidatePullRequestReviewService
import com.orchard.backend.agent.CandidatePullRequestReviewSubmission
import com.orchard.backend.agent.FileCandidatePullRequestCorrectionDispatchStore
import com.orchard.backend.agent.FileCandidatePullRequestCorrectionStore
import com.orchard.backend.agent.FileCandidatePullRequestDispositionStore
import com.orchard.backend.agent.FileCandidatePullRequestLearningStore
import com.orchard.backend.agent.FileCandidatePullRequestReviewStore
import com.orchard.backend.agent.FileCandidatePullRequestStore
import com.orchard.backend.agent.REVIEW_CORRECTION_CANDIDATE_REPAIR
import com.orchard.backend.agent.REVIEW_CORRECTION_ESCALATION
import com.orchard.backend.agent.candidatePullRequestHash
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SelfContainedCorrectionIntegrationTest {
    @Test
    fun freshRuntimeUsesPublicApisAndReplaysCorrectionAuthorityAfterRestart() {
        val directory = createTempDirectory("orchard-self-contained-")
        val pullRequests = FileCandidatePullRequestStore(directory)
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }

        testApplication {
            val dispositions = CandidatePullRequestDispositionService(
                pullRequests, FileCandidatePullRequestDispositionStore(directory),
            )
            val reviews = CandidatePullRequestReviewService(
                pullRequests,
                FileCandidatePullRequestReviewStore(directory),
                FileCandidatePullRequestCorrectionStore(directory),
                dispositions,
            )
            val dispatcher = CandidatePullRequestCorrectionDispatchService(
                FileCandidatePullRequestCorrectionStore(directory),
                FileCandidatePullRequestCorrectionDispatchStore(directory),
                CandidateCorrectionRepairGateway { CandidateCorrectionDispatchOutcome.DISPATCHED },
            )
            val escalation = CandidatePullRequestAttentionService(
                REVIEW_CORRECTION_ESCALATION,
                FileCandidatePullRequestCorrectionStore(directory),
                FileCandidatePullRequestCorrectionDispatchStore(directory),
                dispositions,
            )
            val learning = CandidatePullRequestLearningService(
                pullRequests,
                FileCandidatePullRequestReviewStore(directory),
                FileCandidatePullRequestCorrectionStore(directory),
                FileCandidatePullRequestDispositionStore(directory),
                FileCandidatePullRequestLearningStore(directory),
            )
            application {
                workspaceApi(
                    WorkspaceStore(), candidatePullRequestReviews = reviews, candidatePullRequestCorrectionDispatcher = dispatcher,
                    candidatePullRequestEscalation = escalation, candidatePullRequestLearning = learning,
                )
            }

            val review = client.post("/api/coding-worker/pull-request-reviews") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CandidatePullRequestReviewSubmission(
                    pullRequest.pullRequestId,
                    CANDIDATE_REVIEW_CODE,
                    "deterministic-code-reviewer",
                    listOf(CandidatePullRequestReviewFinding(
                        criterion = "The public behavior remains observable.",
                        observation = "The candidate requires a bounded repair.",
                        severity = "BLOCKER",
                        correctionTarget = REVIEW_CORRECTION_CANDIDATE_REPAIR,
                        evidenceHashes = listOf("d".repeat(64)),
                    )),
                )))
            }
            assertEquals(HttpStatusCode.Created, review.status)
            assertEquals(HttpStatusCode.Created, client.post("/api/coding-worker/pull-request-corrections/tick").status)
            val escalationReview = client.post("/api/coding-worker/pull-request-reviews") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(CandidatePullRequestReviewSubmission(
                    pullRequest.pullRequestId,
                    CANDIDATE_REVIEW_INTENT,
                    "deterministic-intent-reviewer",
                    listOf(CandidatePullRequestReviewFinding(
                        criterion = "Governance authority is explicit.",
                        observation = "An explicit escalation response is required.",
                        severity = "BLOCKER",
                        correctionTarget = REVIEW_CORRECTION_ESCALATION,
                        evidenceHashes = listOf("d".repeat(64)),
                    )),
                )))
            }
            assertEquals(HttpStatusCode.Created, escalationReview.status)
            assertEquals(HttpStatusCode.Created, client.post("/api/coding-worker/pull-request-escalations/tick").status)
            assertEquals(HttpStatusCode.Created, client.post("/api/coding-worker/pull-request-learning/tick").status)
        }

        testApplication {
            val dispositions = CandidatePullRequestDispositionService(
                FileCandidatePullRequestStore(directory), FileCandidatePullRequestDispositionStore(directory),
            )
            val reviews = CandidatePullRequestReviewService(
                FileCandidatePullRequestStore(directory),
                FileCandidatePullRequestReviewStore(directory),
                FileCandidatePullRequestCorrectionStore(directory),
            )
            val dispatcher = CandidatePullRequestCorrectionDispatchService(
                FileCandidatePullRequestCorrectionStore(directory),
                FileCandidatePullRequestCorrectionDispatchStore(directory),
                CandidateCorrectionRepairGateway { CandidateCorrectionDispatchOutcome.DISPATCHED },
            )
            val learning = CandidatePullRequestLearningService(
                FileCandidatePullRequestStore(directory),
                FileCandidatePullRequestReviewStore(directory),
                FileCandidatePullRequestCorrectionStore(directory),
                FileCandidatePullRequestDispositionStore(directory),
                FileCandidatePullRequestLearningStore(directory),
            )
            application {
                workspaceApi(
                    WorkspaceStore(), candidatePullRequestReviews = reviews, candidatePullRequestCorrectionDispatcher = dispatcher,
                    candidatePullRequestDispositions = dispositions, candidatePullRequestLearning = learning,
                )
            }

            val corrections = client.get("/api/coding-worker/pull-request-corrections?pullRequestId=${pullRequest.pullRequestId}")
            assertEquals(HttpStatusCode.OK, corrections.status)
            assertEquals(2, Json.decodeFromString<List<com.orchard.backend.agent.CandidatePullRequestCorrection>>(corrections.bodyAsText()).size)
            val dispatches = client.get("/api/coding-worker/pull-request-correction-dispatches")
            assertEquals(HttpStatusCode.OK, dispatches.status)
            assertEquals(
                listOf(CANDIDATE_CORRECTION_DISPATCHED, CANDIDATE_CORRECTION_DISPATCHED),
                Json.decodeFromString<List<com.orchard.backend.agent.CandidatePullRequestCorrectionDispatch>>(dispatches.bodyAsText())
                    .map { it.status },
            )
            val episodes = client.get("/api/coding-worker/pull-request-learning")
            assertEquals(HttpStatusCode.OK, episodes.status)
            assertEquals(1, Json.decodeFromString<List<com.orchard.backend.agent.CandidatePullRequestLearningEpisode>>(episodes.bodyAsText()).size)
        }
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
            implementationClaims = listOf("The public behavior is implemented."),
            checks = listOf("./gradlew test"),
            evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Tests passed.")),
            deviations = emptyList(),
            createdAt = "2026-07-27T00:00:00Z",
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}