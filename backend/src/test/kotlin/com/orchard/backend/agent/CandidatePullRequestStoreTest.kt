package com.orchard.backend.agent

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CandidatePullRequestStoreTest {
    @Test
    fun `candidate PR store replays immutable review artifacts`() {
        val directory = createTempDirectory("orchard-candidate-pr-")
        val store = FileCandidatePullRequestStore(directory)
        val pullRequest = store.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        val successor = store.appendNext { pullRequestId ->
            pullRequest(pullRequestId, candidateRevision = "e".repeat(40), parentPullRequestId = pullRequest.pullRequestId)
        }

        assertEquals(listOf(pullRequest, successor), FileCandidatePullRequestStore(directory).load())
        assertEquals(pullRequest.pullRequestId, successor.parentPullRequestId)
        assertFailsWith<IllegalArgumentException> {
            store.appendNext { pullRequestId -> pullRequest(pullRequestId) }
        }
    }

    private fun pullRequest(
        pullRequestId: Long,
        candidateRevision: String = "c".repeat(40),
        parentPullRequestId: Long? = null,
    ): CandidatePullRequest {
        val draft = CandidatePullRequest(
            pullRequestId = pullRequestId,
            parentPullRequestId = parentPullRequestId,
            runId = 7,
            workPackageId = 3,
            workPackageHash = "a".repeat(64),
            baseRevision = "b".repeat(40),
            candidateRevision = candidateRevision,
            changedPaths = listOf("src/Main.kt"),
            implementationClaims = listOf("The answer is forty two."),
            checks = listOf("./gradlew test"),
            evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, "d".repeat(64), "Tests passed.")),
            deviations = emptyList(),
            createdAt = "2026-07-26T00:00:00Z",
            hash = "",
        )
        return draft.copy(hash = candidatePullRequestHash(draft))
    }
}