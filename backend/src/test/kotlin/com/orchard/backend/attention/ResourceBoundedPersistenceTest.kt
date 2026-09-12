package com.orchard.backend.attention

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceBoundedPersistenceTest {
    @Test
    fun `repeated equivalent failure stops without another inference`() {
        val basis = basis("Same source-backed repair.", "COMPILE_FAILURE")
        val fingerprint = attemptBasisFingerprint(basis)
        val decision = evaluatePersistence(
            budget(),
            "2026-09-12T00:00:00Z",
            basis,
            listOf(
                PersistenceAttemptObservation(fingerprint, "COMPILE_FAILURE", 2_000, 500, 20_000, false),
                PersistenceAttemptObservation(fingerprint, "COMPILE_FAILURE", 2_000, 500, 20_000, false),
            ),
            Instant.parse("2026-09-12T00:05:00Z"),
        )

        assertEquals(PERSISTENCE_RECURRENT_FAILURE, decision.outcome)
    }

    @Test
    fun `new evidence or hypothesis preserves bounded continuation`() {
        val prior = basis("Repair the decoder.", "REPLAY_FAILURE")
        val next = basis("Use the new restart observation to repair migration ordering.", "RESTART_FAILURE")
        val decision = evaluatePersistence(
            budget(),
            "2026-09-12T00:00:00Z",
            next,
            listOf(PersistenceAttemptObservation(attemptBasisFingerprint(prior), "REPLAY_FAILURE", 2_000, 500, 20_000, true)),
            Instant.parse("2026-09-12T00:05:00Z"),
        )

        assertEquals(PERSISTENCE_CONTINUE, decision.outcome)
    }

    @Test
    fun `wall clock and cumulative inference budgets stop independently`() {
        val basis = basis("Bounded repair.", "TEST_FAILURE")
        assertEquals(
            PERSISTENCE_DEADLINE_BLOCKED,
            evaluatePersistence(budget(maxWallClockMillis = 60_000), "2026-09-12T00:00:00Z", basis, emptyList(), Instant.parse("2026-09-12T00:02:00Z")).outcome,
        )
        assertEquals(
            PERSISTENCE_BUDGET_EXHAUSTED,
            evaluatePersistence(
                budget(maxInferenceMillis = 10_000),
                "2026-09-12T00:00:00Z",
                basis,
                listOf(PersistenceAttemptObservation("e".repeat(64), "OTHER", 100, 100, 10_000, false)),
                Instant.parse("2026-09-12T00:00:30Z"),
            ).outcome,
        )
    }

    private fun budget(
        maxInferenceMillis: Long = 120_000,
        maxWallClockMillis: Long = 600_000,
    ) = PersistenceBudget(4, 20_000, 5_000, maxInferenceMillis, maxWallClockMillis, 2)

    private fun basis(hypothesis: String, failureClass: String) = AttemptBasis(
        claimId = "CLAIM-1",
        authorityHash = "a".repeat(64),
        repositoryRevision = "b".repeat(40),
        attentionFrameHash = "c".repeat(64),
        methodId = "coding-worker",
        failureClass = failureClass,
        hypothesis = hypothesis,
    )
}