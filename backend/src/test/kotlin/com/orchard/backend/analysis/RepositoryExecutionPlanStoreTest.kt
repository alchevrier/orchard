package com.orchard.backend.analysis

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryExecutionPlanStoreTest {
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
