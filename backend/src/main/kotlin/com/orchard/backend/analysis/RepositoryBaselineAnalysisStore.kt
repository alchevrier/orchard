package com.orchard.backend.analysis

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val BASELINE_STAGE_STRUCTURE = "STRUCTURE"
const val BASELINE_STAGE_DECISIONS = "DECISIONS"
const val BASELINE_STAGE_VERIFICATION = "VERIFICATION"
const val BASELINE_STAGE_DELIVERY = "DELIVERY"

val REPOSITORY_BASELINE_STAGES = listOf(
    BASELINE_STAGE_STRUCTURE,
    BASELINE_STAGE_DECISIONS,
    BASELINE_STAGE_VERIFICATION,
    BASELINE_STAGE_DELIVERY,
)

@Serializable
data class RepositoryBaselineSection(
    val stage: String,
    val summary: String,
    val findings: List<RepositoryCapabilityClaim>,
    val unresolvedQuestions: List<String> = emptyList(),
    val omittedRepositoryFileCount: Int = 0,
    val model: String,
    val promptHash: String,
    val outputHash: String,
)

@Serializable
data class RepositoryBaselineAnalysis(
    val analysisId: Long,
    val projectId: Int,
    val genesisRevision: Int,
    val repositoryRevision: String,
    val graphHash: String,
    val graphCoverage: RepositoryIntelligenceCoverage,
    val sections: List<RepositoryBaselineSection>,
    val complete: Boolean,
    val analyzedAt: String = Instant.now().toString(),
    val hash: String,
)

interface RepositoryBaselineAnalysisStore {
    fun load(): List<RepositoryBaselineAnalysis>
    fun appendNext(create: (analysisId: Long) -> RepositoryBaselineAnalysis): RepositoryBaselineAnalysis
}

class TransientRepositoryBaselineAnalysisStore : RepositoryBaselineAnalysisStore {
    private val analyses = mutableListOf<RepositoryBaselineAnalysis>()

    @Synchronized
    override fun load(): List<RepositoryBaselineAnalysis> = analyses.toList()

    @Synchronized
    override fun appendNext(create: (analysisId: Long) -> RepositoryBaselineAnalysis): RepositoryBaselineAnalysis {
        val analysis = create(analyses.size + 1L)
        validateRepositoryBaselineAnalysis(analysis, analyses)
        analyses += analysis
        return analysis
    }
}

class FileRepositoryBaselineAnalysisStore(private val directory: Path) : RepositoryBaselineAnalysisStore {
    private val path = directory.resolve("repository-baseline-analyses.jsonl")
    private val lockPath = directory.resolve("repository-baseline-analyses.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<RepositoryBaselineAnalysis> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<RepositoryBaselineAnalysis> = mutableListOf<RepositoryBaselineAnalysis>().also { analyses ->
        loadRecoverableJsonl(path, "repository-baseline-analyses") { line, recordNumber ->
            val version = json.parseToJsonElement(line).jsonObject["version"]?.jsonPrimitive?.content?.toIntOrNull()
            val analysis = when (version) {
                1 -> {
                    val envelope = json.decodeFromString<LegacyRepositoryBaselineAnalysisEnvelope>(line)
                    require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                        "Checksum mismatch in repository baseline analysis $recordNumber"
                    }
                    require(envelope.value.hash == legacyRepositoryBaselineAnalysisHash(envelope.value)) {
                        "Repository baseline analysis hash is invalid"
                    }
                    envelope.value.asUnpinnedHistory()
                }
                FORMAT_VERSION -> {
                    val envelope = json.decodeFromString<RepositoryBaselineAnalysisEnvelope>(line)
                    require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                        "Checksum mismatch in repository baseline analysis $recordNumber"
                    }
                    validateRepositoryBaselineAnalysis(envelope.value, analyses)
                    envelope.value
                }
                else -> error("Unsupported repository baseline analysis format $version")
            }
            require(analysis.analysisId == analyses.size + 1L) { "Repository baseline analysis ID is not monotonic" }
            analyses += analysis
            analysis
        }
    }

    @Synchronized
    override fun appendNext(create: (analysisId: Long) -> RepositoryBaselineAnalysis): RepositoryBaselineAnalysis {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val analyses = loadUnlocked()
                val analysis = create(analyses.size + 1L)
                validateRepositoryBaselineAnalysis(analysis, analyses)
                val payload = json.encodeToString(analysis)
                val line = json.encodeToString(
                    RepositoryBaselineAnalysisEnvelope(
                        value = analysis,
                        checksum = stagedPlanHash(payload),
                    )
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                analysis
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 2
    }
}

fun newRepositoryBaselineAnalysis(
    analysisId: Long,
    projectId: Int,
    genesisRevision: Int,
    repositoryRevision: String,
    graphHash: String,
    graphCoverage: RepositoryIntelligenceCoverage,
    sections: List<RepositoryBaselineSection>,
): RepositoryBaselineAnalysis {
    val unsigned = RepositoryBaselineAnalysis(
        analysisId = analysisId,
        projectId = projectId,
        genesisRevision = genesisRevision,
        repositoryRevision = repositoryRevision,
        graphHash = graphHash,
        graphCoverage = graphCoverage,
        sections = sections,
        complete = sections.map { it.stage } == REPOSITORY_BASELINE_STAGES,
        hash = "",
    )
    return unsigned.copy(hash = repositoryBaselineAnalysisHash(unsigned))
}

fun repositoryBaselineAnalysisHash(analysis: RepositoryBaselineAnalysis): String = stagedPlanHash(
    baselineAnalysisJson.encodeToString(analysis.copy(hash = ""))
)

private fun validateRepositoryBaselineAnalysis(
    analysis: RepositoryBaselineAnalysis,
    previous: List<RepositoryBaselineAnalysis>,
) {
    require(analysis.analysisId == previous.size + 1L) { "Repository baseline analysis ID is not monotonic" }
    require(analysis.projectId > 0 && analysis.genesisRevision >= 0) { "Repository baseline analysis identity is invalid" }
    require(analysis.repositoryRevision.matches(GIT_REVISION)) { "Repository baseline revision is invalid" }
    require(analysis.graphHash.matches(SHA256)) { "Repository baseline graph hash is invalid" }
    require(
        analysis.graphCoverage.trackedFileCount >= 0 &&
            analysis.graphCoverage.contentAddressedFileCount == analysis.graphCoverage.trackedFileCount
    ) { "Repository baseline graph coverage is incomplete" }
    val stages = analysis.sections.map { it.stage }
    require(stages.isNotEmpty() && stages == REPOSITORY_BASELINE_STAGES.take(stages.size)) {
        "Repository baseline stages are not a valid ordered prefix"
    }
    require(analysis.complete == (stages == REPOSITORY_BASELINE_STAGES)) {
        "Repository baseline completion does not match its stages"
    }
    analysis.sections.forEach { section ->
        require(section.summary.isNotBlank() && section.summary.length <= MAX_BASELINE_TEXT) {
            "Repository baseline section summary is invalid"
        }
        require(section.findings.size in MIN_STAGE_FINDINGS..MAX_STAGE_FINDINGS) {
            "Repository baseline section must contain $MIN_STAGE_FINDINGS to $MAX_STAGE_FINDINGS findings"
        }
        require(repositoryCapabilityClaimsError(section.findings) == null) {
            repositoryCapabilityClaimsError(section.findings).orEmpty()
        }
        require(section.unresolvedQuestions.size <= MAX_STAGE_QUESTIONS && section.unresolvedQuestions.all {
            it.isNotBlank() && it.length <= MAX_BASELINE_TEXT
        }) { "Repository baseline section questions are invalid" }
        require(section.omittedRepositoryFileCount >= 0 && section.model.isNotBlank()) {
            "Repository baseline section provenance is incomplete"
        }
        require(section.promptHash.matches(SHA256) && section.outputHash.matches(SHA256)) {
            "Repository baseline section hashes are invalid"
        }
    }
    val predecessor = previous.lastOrNull {
        it.projectId == analysis.projectId &&
            it.repositoryRevision == analysis.repositoryRevision &&
            it.genesisRevision == analysis.genesisRevision &&
            it.graphHash == analysis.graphHash
    }
    require(predecessor == null || (
        analysis.sections.size == predecessor.sections.size + 1 &&
            analysis.sections.dropLast(1) == predecessor.sections
    )) { "Repository baseline analysis does not extend its predecessor" }
    require(analysis.hash == repositoryBaselineAnalysisHash(analysis)) { "Repository baseline analysis hash is invalid" }
}

@Serializable
private data class RepositoryBaselineAnalysisEnvelope(
    val version: Int = 2,
    val value: RepositoryBaselineAnalysis,
    val checksum: String,
)

@Serializable
private data class LegacyRepositoryBaselineAnalysis(
    val analysisId: Long,
    val projectId: Int,
    val genesisRevision: Int,
    val repositoryRevision: String,
    val sections: List<RepositoryBaselineSection>,
    val complete: Boolean,
    val analyzedAt: String,
    val hash: String,
)

@Serializable
private data class LegacyRepositoryBaselineAnalysisEnvelope(
    val version: Int = 1,
    val value: LegacyRepositoryBaselineAnalysis,
    val checksum: String,
)

private fun legacyRepositoryBaselineAnalysisHash(analysis: LegacyRepositoryBaselineAnalysis): String = stagedPlanHash(
    baselineAnalysisJson.encodeToString(analysis.copy(hash = ""))
)

private fun LegacyRepositoryBaselineAnalysis.asUnpinnedHistory() = RepositoryBaselineAnalysis(
    analysisId,
    projectId,
    genesisRevision,
    repositoryRevision,
    graphHash = "",
    graphCoverage = RepositoryIntelligenceCoverage(0, 0, 0, 0, 0, 0),
    sections,
    complete,
    analyzedAt,
    hash,
)

private val baselineAnalysisJson = Json { encodeDefaults = true }
private val GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val SHA256 = Regex("[0-9a-f]{64}")
private const val MIN_STAGE_FINDINGS = 2
private const val MAX_STAGE_FINDINGS = 8
private const val MAX_STAGE_QUESTIONS = 8
private const val MAX_BASELINE_TEXT = 2_000