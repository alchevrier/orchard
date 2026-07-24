package com.orchard.backend.agent

import com.orchard.backend.analysis.AnalysisExecutionProvenance
import com.orchard.backend.analysis.DISPOSITION_PARTIALLY_IMPLEMENTED
import com.orchard.backend.analysis.ExecutionPlanOperation
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.workspace.WorkspaceStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodingWorkerAttemptStoreTest {
    @Test
    fun `coding retry authorization is durable and consumed by exactly one successor`() {
        val directory = createTempDirectory("orchard-coding-attempts-")
        val store = FileCodingWorkerAttemptStore(directory)
        store.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_BLOCKED, "Rejected scope.") }
        store.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_RETRY_AUTHORIZED, "Human authorized one successor.") }

        val restored = FileCodingWorkerAttemptStore(directory)
        assertTrue(requireNotNull(restored.retryDiagnostic(RUN_ID, PLAN_ID, PLAN_HASH)).contains("Rejected scope."))
        assertFailsWith<IllegalArgumentException> {
            restored.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_RETRY_AUTHORIZED, "Duplicate authorization.") }
        }
        restored.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_RETRY_CONSUMED, "Authorized successor consumed.") }

        assertEquals(CODING_ATTEMPT_RETRY_CONSUMED, restored.latestAttempt(RUN_ID, PLAN_ID, PLAN_HASH)?.state)
        assertEquals(null, restored.retryDiagnostic(RUN_ID, PLAN_ID, PLAN_HASH))
        assertFailsWith<IllegalArgumentException> {
            restored.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_RETRY_CONSUMED, "Second successor consumed.") }
        }
    }

    @Test
    fun `proposal diagnostic reports exact unauthorized and allowed action paths`() {
        val diagnostic = codingProposalAuthorizationDiagnostic(
            CodingPatchProposal(
                summary = "Change two files.",
                operations = listOf(
                    CodingFileOperation(CODING_FILE_REPLACE, "src/Allowed.kt", replacements = emptyList()),
                    CodingFileOperation(CODING_FILE_DELETE, "src/Unexpected.kt"),
                ),
            ),
            executionPlan(),
        )

        assertNotNull(diagnostic)
        assertTrue(diagnostic.contains("DELETE src/Unexpected.kt"))
        assertTrue(diagnostic.contains("MODIFY src/Allowed.kt permits REPLACE or WRITE"))
    }

    @Test
    fun `legacy identical scope failures bootstrap one durable block without changing worker events`() {
        val directory = createTempDirectory("orchard-legacy-coding-attempts-")
        val workerStore = TransientCodingWorkerStore()
        repeat(3) { index -> appendLegacyFailure(workerStore, index) }
        val originalEvents = workerStore.loadEvents()

        CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = workerStore,
            attemptStore = FileCodingWorkerAttemptStore(directory),
        )

        assertEquals(originalEvents, workerStore.loadEvents())
        assertEquals(1, FileCodingWorkerAttemptStore(directory).load().size)
        assertEquals(CODING_ATTEMPT_BLOCKED, FileCodingWorkerAttemptStore(directory).load().single().state)

        CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = workerStore,
            attemptStore = FileCodingWorkerAttemptStore(directory),
        )

        assertEquals(1, FileCodingWorkerAttemptStore(directory).load().size)
    }

    @Test
    fun `scope accepted application failure bootstraps one corrective successor`() {
        val directory = createTempDirectory("orchard-application-failure-attempts-")
        val attemptStore = FileCodingWorkerAttemptStore(directory)
        attemptStore.appendNext { attemptId ->
            attempt(attemptId, CODING_ATTEMPT_BLOCKED, "Rejected scope.").copy(proposalHash = HISTORICAL_PROPOSAL_HASH)
        }
        attemptStore.appendNext { attemptId ->
            attempt(attemptId, CODING_ATTEMPT_RETRY_AUTHORIZED, "Successor authorized.").copy(proposalHash = HISTORICAL_PROPOSAL_HASH)
        }
        attemptStore.appendNext { attemptId ->
            attempt(attemptId, CODING_ATTEMPT_RETRY_CONSUMED, "Successor consumed.").copy(proposalHash = null)
        }
        attemptStore.appendNext { attemptId -> attempt(attemptId, CODING_ATTEMPT_SCOPE_ACCEPTED, "Scope accepted.") }
        val workerStore = TransientCodingWorkerStore()
        appendApplicationFailure(workerStore)

        CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = workerStore,
            attemptStore = attemptStore,
        )

        val recovered = attemptStore.load()
        assertEquals(listOf(CODING_ATTEMPT_BLOCKED, CODING_ATTEMPT_RETRY_AUTHORIZED), recovered.takeLast(2).map { it.state })
        assertTrue(requireNotNull(attemptStore.retryDiagnostic(RUN_ID, PLAN_ID, PLAN_HASH)).contains("REPLACE old text must occur exactly once"))

        CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = workerStore,
            attemptStore = FileCodingWorkerAttemptStore(directory),
        )

        assertEquals(recovered, FileCodingWorkerAttemptStore(directory).load())
    }

    private fun attempt(attemptId: Long, state: String, diagnostic: String) = CodingWorkerAttempt(
        attemptId = attemptId,
        runId = RUN_ID,
        executionPlanId = PLAN_ID,
        executionPlanHash = PLAN_HASH,
        state = state,
        resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
        diagnostic = diagnostic,
        proposalHash = PROPOSAL_HASH,
        recordedAt = "2026-06-22T00:00:0${attemptId}Z",
    )

    private fun appendLegacyFailure(store: CodingWorkerStore, index: Int) {
        val eventId = index * 2L + 1L
        val claimDraft = CodingWorkerClaim(
            executionId = eventId,
            runId = RUN_ID,
            attempt = index + 1,
            contextHash = "a".repeat(64),
            workspacePath = "/tmp/orchard-legacy-worktree",
            bindingFingerprint = "b".repeat(64),
            executionPlanId = PLAN_ID,
            executionPlanHash = PLAN_HASH,
            claimedAt = "2026-06-22T00:00:0${eventId}Z",
            hash = "",
        )
        val claim = claimDraft.copy(hash = codingWorkerClaimHash(claimDraft))
        store.append(CodingWorkerEvent(eventId = eventId, claim = claim))
        val resultDraft = CodingWorkerResult(
            executionId = eventId,
            status = CODING_EXECUTION_FAILED,
            proposalHash = PROPOSAL_HASH,
            diagnostic = "The coding proposal exceeds the accepted execution-plan path or action scope.",
            completedAt = "2026-06-22T00:00:0${eventId + 1}Z",
            hash = "",
        )
        store.append(
            CodingWorkerEvent(
                eventId = eventId + 1,
                result = resultDraft.copy(hash = codingWorkerResultHash(resultDraft)),
            )
        )
    }

    private fun appendApplicationFailure(store: CodingWorkerStore) {
        val claimDraft = CodingWorkerClaim(
            executionId = 1,
            runId = RUN_ID,
            attempt = 1,
            contextHash = "a".repeat(64),
            workspacePath = "/tmp/orchard-application-failure-worktree",
            bindingFingerprint = "b".repeat(64),
            executionPlanId = PLAN_ID,
            executionPlanHash = PLAN_HASH,
            claimedAt = "2026-06-22T00:00:01Z",
            hash = "",
        )
        store.append(CodingWorkerEvent(eventId = 1, claim = claimDraft.copy(hash = codingWorkerClaimHash(claimDraft))))
        val resultDraft = CodingWorkerResult(
            executionId = 1,
            status = CODING_EXECUTION_FAILED,
            proposalHash = PROPOSAL_HASH,
            diagnostic = "REPLACE old text must occur exactly once",
            completedAt = "2026-06-22T00:00:02Z",
            hash = "",
        )
        store.append(CodingWorkerEvent(eventId = 2, result = resultDraft.copy(hash = codingWorkerResultHash(resultDraft))))
    }

    private fun executionPlan() = RepositoryExecutionPlan(
        planId = PLAN_ID,
        runId = RUN_ID,
        revision = 1,
        projectId = 1,
        baseRevision = "c".repeat(40),
        content = RepositoryAnalysisPlanContent(
            disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
            summary = "Modify the admitted file.",
            evidence = emptyList(),
            reuse = emptyList(),
            preservedInvariants = emptyList(),
            nonGoals = emptyList(),
            operations = listOf(
                ExecutionPlanOperation(
                    order = 1,
                    action = PLAN_OPERATION_MODIFY,
                    path = "src/Allowed.kt",
                    instruction = "Make the admitted change.",
                    acceptanceCriteria = listOf("The admitted change is present."),
                )
            ),
            verificationCommands = emptyList(),
        ),
        provenance = AnalysisExecutionProvenance(
            executionProfileId = "test-analysis",
            bindingFingerprint = "d".repeat(64),
            promptHash = "e".repeat(64),
            contextHash = "f".repeat(64),
            outputHash = "1".repeat(64),
            modelExecutionId = 1,
        ),
        hash = PLAN_HASH,
    )

    private companion object {
        const val RUN_ID = 11L
        const val PLAN_ID = 11L
        val PLAN_HASH = "2".repeat(64)
        val PROPOSAL_HASH = "3".repeat(64)
        val HISTORICAL_PROPOSAL_HASH = "4".repeat(64)
    }
}