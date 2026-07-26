package com.orchard.backend.vector

import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val HARDWARE_PROFILE_16_GIB = "16_GIB"
const val HARDWARE_PROFILE_32_GIB = "32_GIB"
const val HARDWARE_PROFILE_64_GIB = "64_GIB"
const val HARDWARE_PROFILE_128_GIB = "128_GIB"

const val BENCHMARK_MODEL_SMALL = "SMALL"
const val BENCHMARK_MODEL_MEDIUM = "MEDIUM"
const val BENCHMARK_MODEL_LARGE = "LARGE"
const val BENCHMARK_TOOLING_MINIMAL = "MINIMAL"
const val BENCHMARK_TOOLING_FULL_ORCHARD = "FULL_ORCHARD"

@Serializable
data class CodingBenchmarkResult(
    val resultId: Long,
    val suiteId: String,
    val taskId: String,
    val hardwareProfile: String,
    val modelTier: String,
    val bindingFingerprint: String,
    val toolingMode: String,
    val completed: Boolean,
    val attemptsToCompile: Int,
    val repairCount: Int,
    val fabricatedReferenceCount: Int,
    val scopeViolationCount: Int,
    val changedLineCount: Int,
    val elapsedMillis: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val peakMemoryBytes: Long,
    val escalationCount: Int,
    val pullRequestClaimAccuracy: Double,
    val intentAlignment: Double,
    val recordedAt: String = Instant.now().toString(),
    val hash: String,
)

interface CodingBenchmarkStore {
    fun load(): List<CodingBenchmarkResult>
    fun appendNext(create: (resultId: Long) -> CodingBenchmarkResult): CodingBenchmarkResult
}

class TransientCodingBenchmarkStore : CodingBenchmarkStore {
    private val results = mutableListOf<CodingBenchmarkResult>()
    override fun load(): List<CodingBenchmarkResult> = results.toList()
    override fun appendNext(create: (resultId: Long) -> CodingBenchmarkResult): CodingBenchmarkResult {
        val result = create(results.size + 1L)
        validateCodingBenchmarkResult(result, results)
        results += result
        return result
    }
}

class FileCodingBenchmarkStore(private val directory: Path) : CodingBenchmarkStore {
    private val path = directory.resolve("coding-benchmarks.jsonl")
    private val lockPath = directory.resolve("coding-benchmarks.lock")
    private val json = Json { encodeDefaults = true }

    override fun load(): List<CodingBenchmarkResult> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    override fun appendNext(create: (resultId: Long) -> CodingBenchmarkResult): CodingBenchmarkResult {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val results = loadUnlocked()
                val result = create(results.size + 1L)
                validateCodingBenchmarkResult(result, results)
                val payload = json.encodeToString(result)
                val line = json.encodeToString(CodingBenchmarkEnvelope(value = result, checksum = stagedPlanHash(payload))) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                result
            }
        }
    }

    private fun loadUnlocked(): List<CodingBenchmarkResult> = mutableListOf<CodingBenchmarkResult>().also { results ->
        loadRecoverableJsonl(path, "coding-benchmarks") { line, recordNumber ->
            val envelope = json.decodeFromString<CodingBenchmarkEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported coding benchmark format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in coding benchmark $recordNumber"
            }
            validateCodingBenchmarkResult(envelope.value, results)
            results += envelope.value
            envelope.value
        }
    }
}

fun newCodingBenchmarkResult(
    resultId: Long,
    suiteId: String,
    taskId: String,
    hardwareProfile: String,
    modelTier: String,
    bindingFingerprint: String,
    toolingMode: String,
    completed: Boolean,
    attemptsToCompile: Int,
    repairCount: Int,
    fabricatedReferenceCount: Int,
    scopeViolationCount: Int,
    changedLineCount: Int,
    elapsedMillis: Long,
    inputTokens: Int,
    outputTokens: Int,
    peakMemoryBytes: Long,
    escalationCount: Int,
    pullRequestClaimAccuracy: Double,
    intentAlignment: Double,
): CodingBenchmarkResult {
    val draft = CodingBenchmarkResult(
        resultId, suiteId, taskId, hardwareProfile, modelTier, bindingFingerprint, toolingMode, completed,
        attemptsToCompile, repairCount, fabricatedReferenceCount, scopeViolationCount, changedLineCount,
        elapsedMillis, inputTokens, outputTokens, peakMemoryBytes, escalationCount, pullRequestClaimAccuracy,
        intentAlignment, hash = "",
    )
    return draft.copy(hash = codingBenchmarkHash(draft))
}

fun codingBenchmarkMatrixDiagnostic(results: List<CodingBenchmarkResult>, suiteId: String, taskId: String): String? {
    val cells = results.filter { it.suiteId == suiteId && it.taskId == taskId }
        .map { it.modelTier to it.toolingMode }.toSet()
    val required = setOf(
        BENCHMARK_MODEL_SMALL to BENCHMARK_TOOLING_MINIMAL,
        BENCHMARK_MODEL_SMALL to BENCHMARK_TOOLING_FULL_ORCHARD,
        BENCHMARK_MODEL_LARGE to BENCHMARK_TOOLING_MINIMAL,
        BENCHMARK_MODEL_LARGE to BENCHMARK_TOOLING_FULL_ORCHARD,
    )
    val missing = required - cells
    return missing.takeIf { it.isNotEmpty() }?.let {
        "Benchmark matrix is incomplete: ${it.sortedWith(compareBy<Pair<String, String>> { cell -> cell.first }.thenBy { cell -> cell.second }).joinToString { cell -> "${cell.first}/${cell.second}" }}."
    }
}

fun codingBenchmarkHash(result: CodingBenchmarkResult): String = stagedPlanHash(
    benchmarkJson.encodeToString(result.copy(hash = ""))
)

private fun validateCodingBenchmarkResult(result: CodingBenchmarkResult, previous: List<CodingBenchmarkResult>) {
    require(result.resultId == previous.size + 1L && result.suiteId.isNotBlank() && result.taskId.isNotBlank()) {
        "Coding benchmark identity is invalid"
    }
    require(result.hardwareProfile in HARDWARE_PROFILES && result.modelTier in MODEL_TIERS && result.toolingMode in TOOLING_MODES) {
        "Coding benchmark classification is invalid"
    }
    require(result.bindingFingerprint.matches(SHA256) && listOf(
        result.attemptsToCompile, result.repairCount, result.fabricatedReferenceCount, result.scopeViolationCount,
        result.changedLineCount, result.inputTokens, result.outputTokens, result.escalationCount,
    ).all { it >= 0 } && result.elapsedMillis >= 0 && result.peakMemoryBytes > 0) { "Coding benchmark metrics are invalid" }
    require(result.pullRequestClaimAccuracy in 0.0..1.0 && result.intentAlignment in 0.0..1.0) {
        "Coding benchmark review scores are invalid"
    }
    require(previous.none {
        it.suiteId == result.suiteId && it.taskId == result.taskId && it.hardwareProfile == result.hardwareProfile &&
            it.modelTier == result.modelTier && it.bindingFingerprint == result.bindingFingerprint && it.toolingMode == result.toolingMode
    }) { "Coding benchmark cell already exists" }
    require(result.hash == codingBenchmarkHash(result)) { "Coding benchmark hash is invalid" }
}

@Serializable
private data class CodingBenchmarkEnvelope(
    val version: Int = STORE_VERSION,
    val value: CodingBenchmarkResult,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val HARDWARE_PROFILES = setOf(HARDWARE_PROFILE_16_GIB, HARDWARE_PROFILE_32_GIB, HARDWARE_PROFILE_64_GIB, HARDWARE_PROFILE_128_GIB)
private val MODEL_TIERS = setOf(BENCHMARK_MODEL_SMALL, BENCHMARK_MODEL_MEDIUM, BENCHMARK_MODEL_LARGE)
private val TOOLING_MODES = setOf(BENCHMARK_TOOLING_MINIMAL, BENCHMARK_TOOLING_FULL_ORCHARD)
private val SHA256 = Regex("[0-9a-f]{64}")
private val benchmarkJson = Json { encodeDefaults = true }