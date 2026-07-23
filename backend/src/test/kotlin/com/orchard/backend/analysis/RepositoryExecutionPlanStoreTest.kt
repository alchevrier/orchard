package com.orchard.backend.analysis

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RepositoryExecutionPlanStoreTest {
    @Test
    fun `repository analyst prompt includes a complete strict json example`() {
        val prompt = requireNotNull(
            RepositoryAnalysisService::class.java.classLoader.getResourceAsStream(
                "default-system-prompts/repository_analysis_agent.md"
            )
        ).bufferedReader().use { it.readText() }

        assert(prompt.contains("A valid response has exactly this shape:"))
        assert(prompt.contains("\"disposition\":\"PARTIALLY_IMPLEMENTED\""))
        assert(prompt.contains("Include exactly the disposition, summary, evidence, reuse, preservedInvariants, nonGoals, scopeCoverage, operations, verificationCommands, and unresolvedQuestions top-level keys."))
        assert(prompt.contains("copying scope exactly without paraphrasing, omission, or invention"))
        assert(prompt.contains("or from a path introduced by a CREATE operation"))
        assert(prompt.contains("Every evidencePath in scopeCoverage must also be targeted by a CREATE, MODIFY, or DELETE operation"))
        assert(prompt.contains("every referenced order must exist"))
        assert(prompt.contains("Treat universal scope words such as all, every, and across as exhaustive"))
        assert(prompt.contains("every supplied source file containing an explicit FontFamily declaration"))
        assert(prompt.contains("return those gaps in unresolvedQuestions instead of claiming complete scope coverage"))
        assert(prompt.contains("copy path and contentHash together as one unchanged pair from requiredEvidence"))
        assert(prompt.contains("Copy values from requiredAcceptanceCriteria and requiredVerificationCommands exactly; do not paraphrase them."))
        assert(prompt.contains("Copy the complete requiredAcceptanceCriteria list into the final VERIFY operation"))
    }

    @Test
    fun `execution plans recover immutable successors including revised plans on one base`() {
        val directory = createTempDirectory("orchard-analysis-plans-")
        val store = FileRepositoryExecutionPlanStore(directory)
        val first = plan(1, 1, "a".repeat(40))
        val successor = plan(2, 2, "b".repeat(40))
        val revisedSuccessor = plan(3, 3, "b".repeat(40))

        store.append(first)
        store.append(successor)
        store.append(revisedSuccessor)

        assertEquals(listOf(first, successor, revisedSuccessor), FileRepositoryExecutionPlanStore(directory).load())
        assertFailsWith<IllegalArgumentException> {
            store.append(plan(4, 3, "c".repeat(40)))
        }
        assertEquals(3, FileRepositoryExecutionPlanStore(directory).load().size)
        assertEquals(3, Files.readAllLines(directory.resolve("repository-analysis-plans.jsonl")).size)
    }

    @Test
    fun `repository operation diagnostics identify invalid path shape`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(
            files = listOf(com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun main() = Unit")),
            omittedFileCount = 0,
        )
        val valid = plan(1, 1, "a".repeat(40)).content

        assertNull(repositoryOperationShapeDiagnostic(context, valid))
        assertEquals(
            "Execution operation 1 cannot CREATE existing path src/Main.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = valid.operations.map { it.copy(action = PLAN_OPERATION_CREATE) }),
            ),
        )
        assertNull(
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_CREATE, "src/New.kt", "new", "Create it.", listOf("Behavior works.")),
                    ExecutionPlanOperation(2, PLAN_OPERATION_VERIFY, "src/New.kt", "new", "Verify it.", listOf("Behavior works.")),
                )),
            )
        )
        assertNull(
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "main", "Change it.", listOf("Behavior works.")),
                    ExecutionPlanOperation(2, PLAN_OPERATION_VERIFY, ".", null, "Verify the repository.", listOf("Behavior works.")),
                )),
            )
        )
        assertEquals(
            "Execution operation 1 cannot VERIFY unavailable path src/New.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_VERIFY, "src/New.kt", "new", "Verify it.", listOf("Behavior works.")),
                )),
            ),
        )
        assertEquals(
            "Execution operation 1 cannot MODIFY unobserved path src/Missing.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = valid.operations.map { it.copy(path = "src/Missing.kt") }),
            ),
        )
    }

    @Test
    fun `repository evidence diagnostics identify unavailable paths and changed hashes`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(
            files = listOf(com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun main() = Unit", "c".repeat(64))),
            omittedFileCount = 0,
        )
        val valid = plan(1, 1, "a".repeat(40)).content

        assertNull(repositoryEvidenceDiagnostic(context, valid))
        assertEquals(
            "Repository evidence citation 1 uses unavailable path src/Missing.kt.",
            repositoryEvidenceDiagnostic(
                context,
                valid.copy(evidence = valid.evidence.map { it.copy(path = "src/Missing.kt") }),
            ),
        )
        assertEquals(
            "Repository evidence citation 1 has the wrong content hash for src/Main.kt.",
            repositoryEvidenceDiagnostic(
                context,
                valid.copy(evidence = valid.evidence.map { it.copy(contentHash = "d".repeat(64)) }),
            ),
        )
    }

    @Test
    fun `repository scope coverage requires exact clauses pinned evidence and source operations`() {
        val scope = listOf("Inspect the owner.", "Add regression coverage.")
        val content = plan(1, 1, "a".repeat(40)).content.copy(
            coveredScope = scope,
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/Main.kt"), listOf(1)) },
        )

        assertNull(repositoryScopeCoverageDiagnostic(scope, content))
        assertEquals(
            "Execution plan scope coverage does not map every accepted scope clause exactly once.",
            repositoryScopeCoverageDiagnostic(scope, content.copy(scopeCoverage = content.scopeCoverage.take(1))),
        )
        assertEquals(
            "Scope coverage 1 does not cite pinned evidence or a planned creation.",
            repositoryScopeCoverageDiagnostic(
                scope,
                content.copy(scopeCoverage = content.scopeCoverage.map { it.copy(evidencePaths = listOf("src/Missing.kt")) }),
            ),
        )
        val createContent = content.copy(
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/NewTest.kt"), listOf(1)) },
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_CREATE, "src/NewTest.kt", null, "Create it.", listOf("Behavior works."))),
        )
        assertNull(repositoryScopeCoverageDiagnostic(scope, createContent))
        val verifyOnly = content.copy(
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_VERIFY, ".", null, "Verify it.", listOf("Behavior works."))),
        )
        assertEquals(
            "Scope coverage 1 cites a path without a corresponding source operation.",
            repositoryScopeCoverageDiagnostic(scope, verifyOnly),
        )
        val unmatchedEvidence = content.copy(
            evidence = content.evidence + RepositoryEvidenceCitation("src/Other.kt", null, "Another owner.", "d".repeat(64)),
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/Other.kt"), listOf(1)) },
        )
        assertEquals(
            "Scope coverage 1 cites a path without a corresponding source operation.",
            repositoryScopeCoverageDiagnostic(scope, unmatchedEvidence),
        )
    }

    private fun plan(planId: Long, revision: Int, baseRevision: String): RepositoryExecutionPlan =
        newRepositoryExecutionPlan(
            planId = planId,
            runId = 7,
            revision = revision,
            projectId = 1,
            baseRevision = baseRevision,
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Reuse the existing implementation surface.",
                evidence = listOf(
                    RepositoryEvidenceCitation("src/Main.kt", "main", "The owning implementation exists.", "c".repeat(64))
                ),
                reuse = listOf("main"),
                preservedInvariants = listOf("Preserve the public entrypoint."),
                nonGoals = listOf("Do not create a parallel implementation."),
                operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "main", "Extend the existing behavior.", listOf("Behavior works."))
                ),
                verificationCommands = listOf("./gradlew test"),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "broad-repository-analysis-v1",
                bindingFingerprint = "d".repeat(64),
                promptHash = "e".repeat(64),
                contextHash = "f".repeat(64),
                outputHash = "1".repeat(64),
                modelExecutionId = planId,
            ),
        )
}
