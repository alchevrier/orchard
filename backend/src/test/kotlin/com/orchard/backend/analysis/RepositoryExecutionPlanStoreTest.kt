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
        assert(prompt.contains("Include exactly the disposition, summary, evidence, reuse, preservedInvariants, nonGoals, operations, verificationCommands, and unresolvedQuestions top-level keys."))
        assert(prompt.contains("Copy acceptance-criterion descriptions and verification strings exactly; do not paraphrase them."))
    }

    @Test
    fun `execution plans recover immutable successor revisions`() {
        val directory = createTempDirectory("orchard-analysis-plans-")
        val store = FileRepositoryExecutionPlanStore(directory)
        val first = plan(1, 1, "a".repeat(40))
        val successor = plan(2, 2, "b".repeat(40))

        store.append(first)
        store.append(successor)

        assertEquals(listOf(first, successor), FileRepositoryExecutionPlanStore(directory).load())
        assertFailsWith<IllegalArgumentException> {
            store.append(plan(3, 3, "b".repeat(40)))
        }
        assertEquals(2, FileRepositoryExecutionPlanStore(directory).load().size)
        assertEquals(2, Files.readAllLines(directory.resolve("repository-analysis-plans.jsonl")).size)
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
            "Execution operation 1 cannot CREATE observed path src/Main.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = valid.operations.map { it.copy(action = PLAN_OPERATION_CREATE) }),
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
