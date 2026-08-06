package com.orchard.backend.agent

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.workspace.WorkspaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class CandidatePullRequestAutomatedReviewServiceTest {
    @Test
    fun `automated review uses the configured bounded audit aperture`() = runBlocking {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        val reviews = CandidatePullRequestReviewService(
            pullRequests, TransientCandidatePullRequestReviewStore(), dispositionService = dispositions,
        )
        val profileSettings = TransientModelProfileSettingsStore().also { settings ->
            settings.save(listOf(ModelProfileOverride("bounded-independent-audit-v1", 20_000, 4_000)))
        }
        var observedContextWindow: Int? = null
        val service = CandidatePullRequestAutomatedReviewService(
            pullRequests,
            reviews,
            listOf(object : ModelProvider {
                override suspend fun triage(prompt: String): String = error("unused")
                override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
                override suspend fun executeCircuitSynthesis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration {
                    observedContextWindow = contextWindowTokens
                    return ModelGeneration("{\"findings\":[]}", 10, 3)
                }
            }),
            MachineResourceController.unrestricted(),
            profileSettings,
        )

        assertEquals(CandidateAutomatedReviewTickStatus.RECORDED, service.tick().status)
        assertEquals(20_512, observedContextWindow)
    }

    @Test
    fun `bounded actors submit all four independent conforming review authorities`() = runBlocking {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        val reviews = CandidatePullRequestReviewService(
            pullRequests, TransientCandidatePullRequestReviewStore(), dispositionService = dispositions,
        )
        val service = CandidatePullRequestAutomatedReviewService(
            pullRequests,
            reviews,
            listOf(object : ModelProvider {
                override suspend fun triage(prompt: String): String = error("unused")
                override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
                override suspend fun executeCircuitSynthesis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int) =
                    ModelGeneration("{\"findings\":[]}", 10, 3)
            }),
            MachineResourceController.unrestricted(),
        )

        repeat(4) { assertEquals(CandidateAutomatedReviewTickStatus.RECORDED, service.tick().status) }

        assertEquals(
            setOf(CANDIDATE_REVIEW_CODE, CANDIDATE_REVIEW_INTENT, CANDIDATE_REVIEW_DESIGN, CANDIDATE_REVIEW_INTEGRATION),
            reviews.reviews(pullRequest.pullRequestId).map { it.kind }.toSet(),
        )
        assertEquals(CANDIDATE_DISPOSITION_ACCEPTED, dispositions.dispositions(pullRequest.pullRequestId).last().status)
        assertEquals(CandidateAutomatedReviewTickStatus.IDLE, service.tick().status)
    }

    @Test
    fun `concurrent ticks do not start overlapping automated reviews`() = runBlocking {
        val pullRequests = TransientCandidatePullRequestStore()
        val pullRequest = pullRequests.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val dispositions = CandidatePullRequestDispositionService(pullRequests)
        dispositions.record(pullRequest.pullRequestId, CANDIDATE_DISPOSITION_REVIEW_REQUIRED, "Candidate awaits review.")
        val reviews = CandidatePullRequestReviewService(
            pullRequests, TransientCandidatePullRequestReviewStore(), dispositionService = dispositions,
        )
        val generationStarted = CompletableDeferred<Unit>()
        val allowGenerationToFinish = CompletableDeferred<Unit>()
        val service = CandidatePullRequestAutomatedReviewService(
            pullRequests,
            reviews,
            listOf(object : ModelProvider {
                override suspend fun triage(prompt: String): String = error("unused")
                override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
                override suspend fun executeCircuitSynthesis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration {
                    generationStarted.complete(Unit)
                    allowGenerationToFinish.await()
                    return ModelGeneration("{\"findings\":[]}", 10, 3)
                }
            }),
            MachineResourceController.unrestricted(),
        )

        val first = async { service.tick() }
        generationStarted.await()
        assertEquals(CandidateAutomatedReviewTickStatus.BUSY, service.tick().status)
        allowGenerationToFinish.complete(Unit)
        assertEquals(CandidateAutomatedReviewTickStatus.RECORDED, first.await().status)
    }

    private fun pullRequest(pullRequestId: Long): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId, runId = 7, workPackageId = 3, workPackageHash = "a".repeat(64), baseRevision = "b".repeat(40),
            candidateRevision = "c".repeat(40), changedPaths = listOf("src/Main.kt"), implementationClaims = listOf("Observable behavior."),
            checks = listOf("./gradlew test"), evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Passed.")),
            deviations = emptyList(), createdAt = "2026-07-27T00:00:00Z", hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}