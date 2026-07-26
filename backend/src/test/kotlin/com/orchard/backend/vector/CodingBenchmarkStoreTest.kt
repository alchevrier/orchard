package com.orchard.backend.vector

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodingBenchmarkStoreTest {
    @Test
    fun `benchmark store replays the required small large and tooling matrix`() {
        val directory = createTempDirectory("orchard-coding-benchmarks-")
        val store = FileCodingBenchmarkStore(directory)
        listOf(
            BENCHMARK_MODEL_SMALL to BENCHMARK_TOOLING_MINIMAL,
            BENCHMARK_MODEL_SMALL to BENCHMARK_TOOLING_FULL_ORCHARD,
            BENCHMARK_MODEL_LARGE to BENCHMARK_TOOLING_MINIMAL,
            BENCHMARK_MODEL_LARGE to BENCHMARK_TOOLING_FULL_ORCHARD,
        ).forEach { (modelTier, toolingMode) ->
            store.appendNext { resultId -> result(resultId, modelTier, toolingMode) }
        }

        val restored = FileCodingBenchmarkStore(directory).load()

        assertEquals(4, restored.size)
        assertNull(codingBenchmarkMatrixDiagnostic(restored, "typography-v1", "remove-serif"))
        assertTrue(restored.all { it.hash == codingBenchmarkHash(it) })
    }

    @Test
    fun `benchmark matrix identifies missing architecture comparisons`() {
        val result = result(1, BENCHMARK_MODEL_SMALL, BENCHMARK_TOOLING_FULL_ORCHARD)

        assertEquals(
            "Benchmark matrix is incomplete: LARGE/FULL_ORCHARD, LARGE/MINIMAL, SMALL/MINIMAL.",
            codingBenchmarkMatrixDiagnostic(listOf(result), "typography-v1", "remove-serif"),
        )
    }

    private fun result(resultId: Long, modelTier: String, toolingMode: String) = newCodingBenchmarkResult(
        resultId = resultId,
        suiteId = "typography-v1",
        taskId = "remove-serif",
        hardwareProfile = HARDWARE_PROFILE_16_GIB,
        modelTier = modelTier,
        bindingFingerprint = "a".repeat(64),
        toolingMode = toolingMode,
        completed = true,
        attemptsToCompile = 2,
        repairCount = 1,
        fabricatedReferenceCount = 0,
        scopeViolationCount = 0,
        changedLineCount = 8,
        elapsedMillis = 1_000,
        inputTokens = 2_000,
        outputTokens = 500,
        peakMemoryBytes = 8L * 1_073_741_824L,
        escalationCount = 0,
        pullRequestClaimAccuracy = 1.0,
        intentAlignment = 1.0,
    )
}