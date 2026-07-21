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

const val CLAIM_SUPPORTED = "SUPPORTED"
const val CLAIM_PARTIALLY_SUPPORTED = "PARTIALLY_SUPPORTED"
const val CLAIM_CONTRADICTED = "CONTRADICTED"
const val CLAIM_UNESTABLISHED = "UNESTABLISHED"

@Serializable
data class RepositoryClaimEvidence(
    val path: String,
    val contentHash: String,
    val observation: String,
)

@Serializable
data class RepositoryCapabilityClaim(
    val claimId: String,
    val statement: String,
    val status: String,
    val support: List<RepositoryClaimEvidence> = emptyList(),
    val defeaters: List<RepositoryClaimEvidence> = emptyList(),
)

@Serializable
data class RepositoryObjectiveAssessment(
    val assessmentId: Long,
    val projectId: Int,
    val genesisRevision: Int,
    val phase: String,
    val objective: String,
    val repositoryRevision: String,
    val claims: List<RepositoryCapabilityClaim>,
    val unresolvedQuestions: List<String> = emptyList(),
    val omittedRepositoryFileCount: Int = 0,
    val model: String,
    val promptHash: String,
    val outputHash: String,
    val assessedAt: String = Instant.now().toString(),
    val hash: String,
)

interface RepositoryObjectiveAssessmentStore {
    fun load(): List<RepositoryObjectiveAssessment>
    fun appendNext(create: (assessmentId: Long) -> RepositoryObjectiveAssessment): RepositoryObjectiveAssessment
}

class TransientRepositoryObjectiveAssessmentStore : RepositoryObjectiveAssessmentStore {
    private val assessments = mutableListOf<RepositoryObjectiveAssessment>()

    @Synchronized
    override fun load(): List<RepositoryObjectiveAssessment> = assessments.toList()

    @Synchronized
    override fun appendNext(create: (assessmentId: Long) -> RepositoryObjectiveAssessment): RepositoryObjectiveAssessment {
        val assessment = create(assessments.size + 1L)
        validateRepositoryObjectiveAssessment(assessment, assessments)
        assessments += assessment
        return assessment
    }
}

class FileRepositoryObjectiveAssessmentStore(private val directory: Path) : RepositoryObjectiveAssessmentStore {
    private val path = directory.resolve("repository-objective-assessments.jsonl")
    private val lockPath = directory.resolve("repository-objective-assessments.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<RepositoryObjectiveAssessment> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<RepositoryObjectiveAssessment> = mutableListOf<RepositoryObjectiveAssessment>().also { assessments ->
        loadRecoverableJsonl(path, "repository-objective-assessments") { line, recordNumber ->
            val envelope = json.decodeFromString<RepositoryObjectiveAssessmentEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) {
                "Unsupported repository objective assessment format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in repository objective assessment $recordNumber"
            }
            validateRepositoryObjectiveAssessment(envelope.value, assessments)
            assessments += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (assessmentId: Long) -> RepositoryObjectiveAssessment): RepositoryObjectiveAssessment {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val assessments = loadUnlocked()
                val assessment = create(assessments.size + 1L)
                validateRepositoryObjectiveAssessment(assessment, assessments)
                val payload = json.encodeToString(assessment)
                val line = json.encodeToString(
                    RepositoryObjectiveAssessmentEnvelope(
                        value = assessment,
                        checksum = stagedPlanHash(payload),
                    )
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                assessment
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun newRepositoryObjectiveAssessment(
    assessmentId: Long,
    projectId: Int,
    genesisRevision: Int,
    phase: String,
    objective: String,
    repositoryRevision: String,
    claims: List<RepositoryCapabilityClaim>,
    unresolvedQuestions: List<String>,
    omittedRepositoryFileCount: Int,
    model: String,
    promptHash: String,
    outputHash: String,
): RepositoryObjectiveAssessment {
    val unsigned = RepositoryObjectiveAssessment(
        assessmentId = assessmentId,
        projectId = projectId,
        genesisRevision = genesisRevision,
        phase = phase,
        objective = objective,
        repositoryRevision = repositoryRevision,
        claims = claims,
        unresolvedQuestions = unresolvedQuestions,
        omittedRepositoryFileCount = omittedRepositoryFileCount,
        model = model,
        promptHash = promptHash,
        outputHash = outputHash,
        hash = "",
    )
    return unsigned.copy(hash = repositoryObjectiveAssessmentHash(unsigned))
}

fun repositoryObjectiveAssessmentHash(assessment: RepositoryObjectiveAssessment): String = stagedPlanHash(
    assessmentJson.encodeToString(assessment.copy(hash = ""))
)

private fun validateRepositoryObjectiveAssessment(
    assessment: RepositoryObjectiveAssessment,
    previous: List<RepositoryObjectiveAssessment>,
) {
    require(assessment.assessmentId == previous.size + 1L) { "Repository assessment ID is not monotonic" }
    require(assessment.projectId > 0 && assessment.genesisRevision >= 0 && assessment.phase.isNotBlank()) {
        "Repository assessment identity is invalid"
    }
    require(assessment.objective.isNotBlank() && assessment.objective.length <= MAX_OBJECTIVE_LENGTH) {
        "Repository assessment objective is invalid"
    }
    require(assessment.repositoryRevision.matches(GIT_HASH)) { "Repository assessment revision is invalid" }
    require(repositoryCapabilityClaimsError(assessment.claims) == null) {
        repositoryCapabilityClaimsError(assessment.claims).orEmpty()
    }
    require(assessment.unresolvedQuestions.size <= MAX_QUESTIONS && assessment.unresolvedQuestions.all {
        it.isNotBlank() && it.length <= MAX_TEXT_LENGTH
    }) { "Repository assessment questions are invalid" }
    require(assessment.omittedRepositoryFileCount >= 0 && assessment.model.isNotBlank()) {
        "Repository assessment provenance is incomplete"
    }
    require(assessment.promptHash.matches(SHA256) && assessment.outputHash.matches(SHA256)) {
        "Repository assessment provenance hashes are invalid"
    }
    require(assessment.hash == repositoryObjectiveAssessmentHash(assessment)) {
        "Repository assessment authority hash is invalid"
    }
}

internal fun repositoryCapabilityClaimsError(claims: List<RepositoryCapabilityClaim>): String? {
    if (claims.isEmpty()) return "the Architect generated no repository claims"
    if (claims.size > MAX_CLAIMS) return "the Architect generated more than $MAX_CLAIMS repository claims"
    if (claims.map { it.claimId }.distinct().size != claims.size) return "the Architect generated duplicate claim IDs"
    claims.forEachIndexed { index, claim ->
        claimError(claim)?.let { return "Architect-generated claim ${index + 1} $it" }
    }
    return null
}

private fun claimError(claim: RepositoryCapabilityClaim): String? {
    if (!claim.claimId.matches(CLAIM_ID)) return "has an ID that is not lowercase hyphenated text"
    if (claim.statement.isBlank()) return "has an empty capability statement"
    if (claim.statement.length > MAX_TEXT_LENGTH) return "has a capability statement longer than $MAX_TEXT_LENGTH characters"
    if (claim.status !in CLAIM_STATUSES) return "uses unsupported status '${claim.status.take(40)}'"
    if (claim.support.size > MAX_CITATIONS) return "has more than $MAX_CITATIONS supporting citations"
    if (claim.defeaters.size > MAX_CITATIONS) return "has more than $MAX_CITATIONS defeating citations"
    (claim.support + claim.defeaters).forEachIndexed { index, citation ->
        if (!validPath(citation.path)) return "has an invalid path in citation ${index + 1}"
        if (!citation.contentHash.matches(SHA256)) return "has an invalid content hash in citation ${index + 1}"
        if (citation.observation.isBlank()) return "has an empty observation in citation ${index + 1}"
        if (citation.observation.length > MAX_TEXT_LENGTH) {
            return "has a citation observation longer than $MAX_TEXT_LENGTH characters"
        }
    }
    return when (claim.status) {
        CLAIM_SUPPORTED, CLAIM_PARTIALLY_SUPPORTED -> if (claim.support.isEmpty()) {
            "uses status ${claim.status} without supporting evidence"
        } else null
        CLAIM_CONTRADICTED -> if (claim.defeaters.isEmpty()) {
            "uses status $CLAIM_CONTRADICTED without defeating evidence"
        } else null
        CLAIM_UNESTABLISHED -> null
        else -> null
    }
}

private fun validPath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." }

@Serializable
private data class RepositoryObjectiveAssessmentEnvelope(
    val version: Int = 1,
    val value: RepositoryObjectiveAssessment,
    val checksum: String,
)

private val assessmentJson = Json { encodeDefaults = true }
private val GIT_HASH = Regex("[0-9a-f]{40,64}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val CLAIM_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val CLAIM_STATUSES = setOf(CLAIM_SUPPORTED, CLAIM_PARTIALLY_SUPPORTED, CLAIM_CONTRADICTED, CLAIM_UNESTABLISHED)
private const val MAX_CLAIMS = 16
private const val MAX_CITATIONS = 16
private const val MAX_QUESTIONS = 16
private const val MAX_OBJECTIVE_LENGTH = 16_384
private const val MAX_TEXT_LENGTH = 2_000