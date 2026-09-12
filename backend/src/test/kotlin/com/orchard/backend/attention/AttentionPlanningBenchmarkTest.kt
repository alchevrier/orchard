package com.orchard.backend.attention

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttentionPlanningBenchmarkTest {
    @Test
    fun `scorer rejects milestone plan that omits correction routes and claims completion`() {
        val task = candidateCorrectionLineagePlanningTask()
        val proposal = AttentionPlanningProposal(
            summary = "Complete candidate correction lineage.",
            operations = task.owners.filter { it.scopeId !in setOf("REQ-DESIGN", "REQ-ATTENTION") }
                .map(::operation),
            claims = listOf("Candidate correction lineage is implemented and verified."),
        )

        val score = scoreAttentionPlanningProposal(task, proposal)

        assertFalse(score.complete)
        assertEquals(7, score.ownerCoverage)
        assertEquals(9, score.ownerTotal)
        assertEquals(listOf("REQ-ATTENTION", "REQ-DESIGN"), score.missingScopes)
        assertEquals(listOf("Candidate correction lineage is implemented and verified."), score.unsupportedLifecycleClaims)
    }

    @Test
    fun `scorer accepts complete traceable plan without implementation or verification claims`() {
        val task = candidateCorrectionLineagePlanningTask()
        val proposal = AttentionPlanningProposal(
            summary = "Plan the remaining correction-lineage proof.",
            operations = task.owners.map(::operation),
        )

        val score = scoreAttentionPlanningProposal(task, proposal)

        assertTrue(score.complete)
        assertEquals(9, score.ownerCoverage)
        assertEquals(9, score.scopeCoverage)
        assertTrue(score.missingOwners.isEmpty())
        assertTrue(score.unsupportedLifecycleClaims.isEmpty())
    }

    @Test
    fun `scorer rejects unrelated paths wrong actions and missing evidence methods`() {
        val task = candidateCorrectionLineagePlanningTask()
        val operations = task.owners.map(::operation).toMutableList()
        operations[0] = operations[0].copy(action = "MODIFY", verificationMethod = "")
        operations += AttentionPlanningOperation(
            "REQ-UNRELATED",
            AttentionScopeKind.IMPLEMENTATION,
            "backend/src/main/kotlin/com/orchard/backend/Unrelated.kt",
            ATTENTION_DISPOSITION_ACTIONABLE,
            "CREATE",
            "Unrelated cleanup.",
            "Run unrelated tests.",
        )

        val score = scoreAttentionPlanningProposal(task, AttentionPlanningProposal("Plan delivery.", operations))

        assertFalse(score.complete)
        assertEquals(
            listOf("backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestStore.kt: expected READ_SOURCE, proposed MODIFY"),
            score.actionMismatches,
        )
        assertEquals(listOf("backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestStore.kt"), score.missingVerificationMethods)
        assertEquals(listOf("backend/src/main/kotlin/com/orchard/backend/Unrelated.kt"), score.unexpectedPaths)
    }

    @Test
    fun `scorer rejects a named check that is not the admitted evidence method`() {
        val task = candidateCorrectionLineagePlanningTask()
        val operations = task.owners.map(::operation).toMutableList()
        operations[0] = operations[0].copy(verificationMethod = "Test that delivery works.")

        val score = scoreAttentionPlanningProposal(task, AttentionPlanningProposal("Plan delivery.", operations))

        assertFalse(score.complete)
        assertEquals(listOf(task.owners.first().path), score.verificationMismatches)
    }

    @Test
    fun `scorer rejects duplicate owners and incorrect path correlations`() {
        val task = candidateCorrectionLineagePlanningTask()
        val operations = task.owners.map(::operation).toMutableList()
        operations[0] = operations[0].copy(
            scopeId = "REQ-SECURITY",
            scopeKind = AttentionScopeKind.SECURITY,
            disposition = ATTENTION_DISPOSITION_ACTIONABLE,
        )
        operations += operations[1]

        val score = scoreAttentionPlanningProposal(task, AttentionPlanningProposal("Plan delivery.", operations))

        assertFalse(score.complete)
        assertEquals(listOf("backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestDispositionStore.kt"), score.duplicatePaths)
        assertEquals(
            listOf("backend/src/main/kotlin/com/orchard/backend/agent/CandidatePullRequestStore.kt: expected REQ-LINEAGE/PERSISTENCE/EVIDENCE_ONLY, proposed REQ-SECURITY/SECURITY/ACTIONABLE"),
            score.correlationMismatches,
        )
    }

    @Test
    fun `scorer rejects reimplementation of compliant correction authorities`() {
        val task = candidateCorrectionLineagePlanningTask()
        val operations = task.owners.map(::operation).toMutableList()
        operations[0] = operations[0].copy(
            disposition = ATTENTION_DISPOSITION_ACTIONABLE,
            action = "MODIFY",
        )

        val score = scoreAttentionPlanningProposal(task, AttentionPlanningProposal("Reimplement lineage.", operations))

        assertFalse(score.complete)
        assertEquals(1, score.correlationMismatches.size)
        assertEquals(1, score.actionMismatches.size)
    }

    @Test
    fun `normalizer repairs authority fields for exact admitted paths`() {
        val task = candidateCorrectionLineagePlanningTask()
        val modelProposal = AttentionPlanningProposal(
            summary = "Plan candidate correction lineage.",
            operations = task.owners.map { owner ->
                operation(owner).copy(
                    scopeId = "${owner.scopeId}/${owner.scopeKind}",
                    expectedBehavior = "Model paraphrase.",
                    verificationMethod = "Test it.",
                )
            },
            claims = task.invariants.take(3),
        )

        val normalized = normalizeAttentionPlanningProposal(task, modelProposal)
        val score = scoreAttentionPlanningProposal(task, normalized.proposal)

        assertEquals(task.owners.map { it.path }.sorted(), normalized.corrections.map { it.path })
        assertTrue(normalized.corrections.all { it.fields == listOf("scopeId", "expectedBehavior", "verificationMethod") })
        assertTrue(score.complete)
    }

    @Test
    fun `normalizer does not invent authority for unknown paths`() {
        val task = candidateCorrectionLineagePlanningTask()
        val unknown = AttentionPlanningOperation(
            "REQ-INVENTED",
            AttentionScopeKind.IMPLEMENTATION,
            "backend/src/main/kotlin/com/orchard/backend/Invented.kt",
            ATTENTION_DISPOSITION_ACTIONABLE,
            "CREATE",
            "Invent another service.",
            "Run invented tests.",
        )
        val normalized = normalizeAttentionPlanningProposal(
            task,
            AttentionPlanningProposal("Plan delivery.", task.owners.map(::operation) + unknown),
        )

        assertTrue(normalized.corrections.isEmpty())
        assertEquals(listOf(unknown.path), scoreAttentionPlanningProposal(task, normalized.proposal).unexpectedPaths)
    }

    private fun operation(owner: AttentionPlanningOwner) = AttentionPlanningOperation(
        scopeId = owner.scopeId,
        scopeKind = owner.scopeKind,
        path = owner.path,
        disposition = owner.disposition,
        action = owner.allowedAction,
        expectedBehavior = owner.expectedBehavior,
        verificationMethod = owner.verificationMethod,
    )
}