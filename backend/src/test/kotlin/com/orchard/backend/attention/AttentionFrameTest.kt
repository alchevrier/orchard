package com.orchard.backend.attention

import com.orchard.backend.analysis.AnalysisExecutionProvenance
import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.ExecutionPlanOperation
import com.orchard.backend.analysis.ExecutionPlanScopeCoverage
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.WorkPackageCheck
import com.orchard.backend.analysis.WorkPackageDesignAuthority
import com.orchard.backend.analysis.WorkPackageEvidenceAuthority
import com.orchard.backend.analysis.WorkPackageEvidenceCitation
import com.orchard.backend.analysis.WorkPackageIntentAuthority
import com.orchard.backend.analysis.WorkPackageOperation
import com.orchard.backend.analysis.WorkPackageOperationAuthority
import com.orchard.backend.analysis.WorkPackageOwnershipBoundary
import com.orchard.backend.analysis.WorkPackageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttentionFrameTest {
    @Test
    fun `compiler preserves bidirectional task code correlation and explicit deferred work`() {
        val plan = plan()
        val workPackage = workPackage()

        val frame = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())

        assertEquals(listOf(1), frame.slice.activeOperationOrders)
        assertEquals(listOf(2), frame.slice.deferredOperationOrders)
        assertFalse(frame.slice.completesObjective)
        assertEquals(listOf(1), frame.correlations.map { it.operationOrder }.distinct())
        assertEquals(listOf("src/Main.kt"), frame.correlations.flatMap { it.ownerPaths }.distinct())
        assertEquals(listOf("Return forty two."), frame.correlations.map { it.requirement.text })
        assertEquals(frame.hash, compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds()).hash)
        assertTrue(verifyCodingAttentionFrame(frame, plan, workPackage).adequate)
    }

    @Test
    fun `verifier rejects orphan operations and omitted invariants`() {
        val plan = plan()
        val workPackage = workPackage()
        val valid = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())
        val invalid = valid.copy(correlations = emptyList(), invariants = emptyList(), hash = "")
            .let { it.copy(hash = attentionFrameHash(it)) }

        val report = verifyCodingAttentionFrame(invalid, plan, workPackage)

        assertFalse(report.adequate)
        assertTrue(report.diagnostics.contains("Active operations lack task correlation: 1."))
        assertTrue(report.diagnostics.contains("Attention frame changes mandatory governing authority."))
    }

    @Test
    fun `verifier rejects rehashed authority widening`() {
        val plan = plan()
        val workPackage = workPackage()
        val valid = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())
        val widened = valid.copy(
            ownershipPaths = valid.ownershipPaths + "src/Unrelated.kt",
            allowedActions = valid.allowedActions + "DELETE_FILE",
            hash = "",
        ).let { it.copy(hash = attentionFrameHash(it)) }

        assertEquals(
            listOf("Attention frame changes executable ownership or action authority."),
            verifyCodingAttentionFrame(widened, plan, workPackage).diagnostics,
        )
    }

    @Test
    fun `compiler represents transversal operational and security owners without losing reverse traceability`() {
        val plan = plan().let { original ->
            original.copy(content = original.content.copy(
                scopeCoverage = listOf(
                    ExecutionPlanScopeCoverage("Migrate persisted records and deployment recovery.", listOf("src/Main.kt", "src/Api.kt"), listOf(1, 2)),
                    ExecutionPlanScopeCoverage("Preserve ACL enforcement and denied-access auditing.", listOf("src/Main.kt", "src/Api.kt"), listOf(1, 2)),
                ),
            ))
        }
        val packageAuthority = workPackage().let { original ->
            original.copy(
                ownership = original.ownership.copy(
                    paths = listOf("src/Main.kt", "src/Api.kt"),
                    likelyImplementationPaths = listOf("src/Main.kt", "src/Api.kt"),
                    requiredImplementationPaths = listOf("src/Main.kt", "src/Api.kt"),
                ),
                operations = WorkPackageOperationAuthority(listOf(
                    original.operations.operations.single(),
                    WorkPackageOperation(2, PLAN_OPERATION_MODIFY, "src/Api.kt", "answerApi", "Expose the migrated answer securely.", listOf("Authorized access returns 42 and denied access is audited.")),
                )),
            )
        }

        val frame = compileCodingAttentionFrame(
            "DELIVER_CHANGE:CODING_PATCH",
            9,
            plan,
            packageAuthority,
            mapOf(0 to AttentionScopeKind.OPERATIONAL, 1 to AttentionScopeKind.SECURITY),
        )

        assertTrue(frame.slice.completesObjective)
        assertEquals(setOf(AttentionScopeKind.OPERATIONAL, AttentionScopeKind.SECURITY), frame.correlations.map { it.scopeKind }.toSet())
        assertEquals(setOf(1, 2), frame.correlations.mapNotNull { it.operationOrder }.toSet())
        assertEquals(setOf("src/Main.kt", "src/Api.kt"), frame.correlations.flatMap { it.ownerPaths }.toSet())
        assertTrue(verifyCodingAttentionFrame(frame, plan, packageAuthority).adequate)
    }

    @Test
    fun `compiler keeps compliant evidence visible without granting an operation`() {
        val plan = plan().let { original ->
            original.copy(content = original.content.copy(scopeCoverage = original.content.scopeCoverage +
                ExecutionPlanScopeCoverage("Document the unchanged compatibility contract.", listOf("docs/Contract.md"), compliantEvidencePaths = listOf("docs/Contract.md"))))
        }
        val packageAuthority = workPackage().let { original ->
            original.copy(evidence = WorkPackageEvidenceAuthority(original.evidence.citations +
                WorkPackageEvidenceCitation("docs/Contract.md", null, "The contract already documents compatibility.", "5".repeat(64), true)))
        }

        val frame = compileCodingAttentionFrame(
            "DELIVER_CHANGE:CODING_PATCH",
            9,
            plan,
            packageAuthority,
            scopeKinds() + (2 to AttentionScopeKind.DOCUMENTATION),
        )

        val evidenceOnly = frame.correlations.single { it.disposition == ATTENTION_DISPOSITION_EVIDENCE_ONLY }
        assertEquals(null, evidenceOnly.operationOrder)
        assertEquals(listOf("docs/Contract.md"), evidenceOnly.ownerPaths)
        assertTrue(verifyCodingAttentionFrame(frame, plan, packageAuthority).adequate)
        assertEquals(
            "Proposed paths do not reverse-trace to the admitted objective: docs/Contract.md.",
            attentionOperationDiagnostic(frame, listOf(
                AttentionProposedOperation("REPLACE_LITERAL", "src/Main.kt"),
                AttentionProposedOperation("REWRITE_FILE", "docs/Contract.md"),
            )),
        )
    }

    @Test
    fun `actionable path guard rejects drift and incomplete forward coverage`() {
        val plan = plan()
        val workPackage = workPackage()
        val frame = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())

        assertEquals(null, attentionOperationDiagnostic(frame, listOf(AttentionProposedOperation("REPLACE_LITERAL", "src/Main.kt"))))
        assertEquals(
            "Proposed paths do not reverse-trace to the admitted objective: src/Unrelated.kt.",
            attentionOperationDiagnostic(frame, listOf(
                AttentionProposedOperation("REWRITE_FILE", "src/Main.kt"),
                AttentionProposedOperation("REWRITE_FILE", "src/Unrelated.kt"),
            )),
        )
        assertEquals(
            "Proposed operations omit actionable task-code correlations: src/Main.kt.",
            attentionOperationDiagnostic(frame, emptyList()),
        )
        assertEquals(
            "Proposed action DELETE_FILE on src/Main.kt does not reverse-trace to the admitted operation.",
            attentionOperationDiagnostic(frame, listOf(AttentionProposedOperation("DELETE_FILE", "src/Main.kt"))),
        )
    }

    @Test
    fun `scope classifier preserves security and operational impact`() {
        assertEquals(AttentionScopeKind.SECURITY, attentionScopeKind("Preserve ACL enforcement and authorization denial."))
        assertEquals(AttentionScopeKind.OPERATIONAL, attentionScopeKind("Provide deployment rollback and recovery metrics."))
        assertEquals(AttentionScopeKind.MIGRATION, attentionScopeKind("Maintain backward compatibility during migration."))
    }

    private fun scopeKinds() = mapOf(
        0 to AttentionScopeKind.IMPLEMENTATION,
        1 to AttentionScopeKind.API,
    )

    private fun plan(): RepositoryExecutionPlan = RepositoryExecutionPlan(
        planId = 4,
        runId = 2,
        revision = 1,
        projectId = 1,
        baseRevision = "a".repeat(40),
        content = RepositoryAnalysisPlanContent(
            disposition = "PARTIALLY_IMPLEMENTED",
            summary = "Return forty two and expose it through the API.",
            evidence = emptyList(),
            reuse = emptyList(),
            preservedInvariants = listOf("Keep the public function name."),
            nonGoals = listOf("Do not redesign the module."),
            scopeCoverage = listOf(
                ExecutionPlanScopeCoverage("Return forty two.", listOf("src/Main.kt"), listOf(1)),
                ExecutionPlanScopeCoverage("Expose the answer through the API.", listOf("src/Api.kt"), listOf(2)),
            ),
            operations = listOf(
                ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", instruction = "Return 42.", acceptanceCriteria = listOf("The function returns 42.")),
                ExecutionPlanOperation(2, PLAN_OPERATION_MODIFY, "src/Api.kt", instruction = "Expose the answer.", acceptanceCriteria = listOf("The API returns 42."), dependsOnOrders = listOf(1)),
            ),
            verificationCommands = listOf("./gradlew test"),
        ),
        provenance = AnalysisExecutionProvenance("analysis", "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64), 3),
        hash = "f".repeat(64),
    )

    private fun workPackage(): ExecutableWorkPackage = ExecutableWorkPackage(
        packageId = 7,
        revision = 1,
        projectId = 1,
        runId = 2,
        repositoryRevision = "a".repeat(40),
        intent = WorkPackageIntentAuthority(8, 1, "1".repeat(64), "Return the answer.", "Returns one.", "Returns forty two.", listOf("Keep the API stable."), listOf("The answer is 42.")),
        design = WorkPackageDesignAuthority(4, 1, "f".repeat(64), "Change the owner.", listOf("Keep the public function name."), listOf("Do not redesign the module.")),
        ownership = WorkPackageOwnershipBoundary(listOf("src/Main.kt"), listOf("src/Main.kt"), emptyList(), listOf("READ_SOURCE", "REWRITE_FILE", "RUN_CHECK"), listOf("src/Main.kt")),
        evidence = WorkPackageEvidenceAuthority(listOf(WorkPackageEvidenceCitation("src/Main.kt", "answer", "The owner returns one.", "2".repeat(64), false))),
        operations = WorkPackageOperationAuthority(listOf(WorkPackageOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "answer", "Return 42.", listOf("The function returns 42.")))),
        expectedBehavior = listOf("Returns forty two."),
        unresolvedAuthorityQuestions = emptyList(),
        sources = listOf(WorkPackageSource("src/Main.kt", "fun answer() = 1\n", "3".repeat(64))),
        checks = listOf(WorkPackageCheck("check-1", "./gradlew test")),
        escalationConditions = listOf("STALE_REVISION", "SCOPE_REQUIRED", "MISSING_AUTHORITY", "DESIGN_CONTRADICTION"),
        hash = "4".repeat(64),
    )
}