@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.attention

import com.orchard.backend.workspace.EvidenceRecord
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisRevision
import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PROJECT_COMPLETION_CONTRACT_VERSION = 1
const val PURPOSE_CLAIM_DEFINED = "DEFINED"
const val PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED = "IMPLEMENTED_UNVERIFIED"
const val PURPOSE_CLAIM_EVIDENCE_FAILED = "EVIDENCE_FAILED"
const val PURPOSE_CLAIM_EVIDENCE_PASSED = "EVIDENCE_PASSED"
const val PURPOSE_CLAIM_ACCEPTED = "ACCEPTED"

@Serializable
data class PurposeClaimDefinition(
    val claimId: String,
    val outcome: String,
    val statement: String,
    val verificationKind: String,
    val verificationMethod: String,
    val observationBoundary: String,
    val requiredSkillIds: List<String> = emptyList(),
)

@Serializable
data class ProjectCompletionContract(
    val formatVersion: Int = PROJECT_COMPLETION_CONTRACT_VERSION,
    val projectId: Int,
    val genesisId: Long,
    val genesisRevision: Int,
    val genesisHash: String,
    val purpose: String,
    val intendedOutcomes: List<String>,
    val claims: List<PurposeClaimDefinition>,
    val nonGoals: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hash: String = "",
)

@Serializable
data class PurposeClaimAcceptance(
    val claimId: String,
    val contractHash: String,
    val targetRevision: String,
    val accepted: Boolean,
    val authority: String,
)

@Serializable
data class PurposeEvidenceLink(
    val claimId: String,
    val contractHash: String,
    val evidenceId: Long,
    val observationBoundary: String,
)

@Serializable
data class PurposeClaimState(
    val claimId: String,
    val state: String,
    val evidenceIds: List<Long> = emptyList(),
    val missingSkillIds: List<String> = emptyList(),
)

@Serializable
data class PurposeSkillExecution(
    val skillId: String,
    val skillVersion: Int,
    val contractHash: String,
    val claimId: String,
    val inputHash: String,
    val outputHash: String,
    val schemaValid: Boolean,
)

@Serializable
data class ProjectCompletionAssessment(
    val done: Boolean,
    val targetRevision: String,
    val claimStates: List<PurposeClaimState>,
    val diagnostics: List<String>,
)

@Serializable
data class ProjectCompletionAuthorityEvent(
    val eventId: Long,
    val contract: ProjectCompletionContract? = null,
    val evidenceLink: PurposeEvidenceLink? = null,
    val acceptance: PurposeClaimAcceptance? = null,
    val recordedAt: String = Instant.now().toString(),
)

interface ProjectCompletionAuthorityStore {
    fun load(): List<ProjectCompletionAuthorityEvent>
    fun appendNext(create: (eventId: Long, previous: List<ProjectCompletionAuthorityEvent>) -> ProjectCompletionAuthorityEvent): ProjectCompletionAuthorityEvent
}

class TransientProjectCompletionAuthorityStore : ProjectCompletionAuthorityStore {
    private val events = mutableListOf<ProjectCompletionAuthorityEvent>()

    @Synchronized
    override fun load(): List<ProjectCompletionAuthorityEvent> = events.toList()

    @Synchronized
    override fun appendNext(
        create: (eventId: Long, previous: List<ProjectCompletionAuthorityEvent>) -> ProjectCompletionAuthorityEvent,
    ): ProjectCompletionAuthorityEvent {
        val event = create(events.size + 1L, events.toList())
        validateProjectCompletionEvent(event, events)
        events += event
        return event
    }
}

class FileProjectCompletionAuthorityStore(private val directory: Path) : ProjectCompletionAuthorityStore {
    private val path = directory.resolve("project-completion-authority.jsonl")
    private val lockPath = directory.resolve("project-completion-authority.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<ProjectCompletionAuthorityEvent> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(
        create: (eventId: Long, previous: List<ProjectCompletionAuthorityEvent>) -> ProjectCompletionAuthorityEvent,
    ): ProjectCompletionAuthorityEvent {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val events = loadUnlocked()
                val event = create(events.size + 1L, events)
                validateProjectCompletionEvent(event, events)
                val payload = json.encodeToString(event)
                val line = json.encodeToString(ProjectCompletionEnvelope(value = event, checksum = stagedPlanHash(payload))) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                event
            }
        }
    }

    private fun loadUnlocked(): List<ProjectCompletionAuthorityEvent> = mutableListOf<ProjectCompletionAuthorityEvent>().also { events ->
        loadRecoverableJsonl(path, "project-completion-authority") { line, recordNumber ->
            val envelope = json.decodeFromString<ProjectCompletionEnvelope>(line)
            require(envelope.version == PROJECT_COMPLETION_STORE_VERSION) { "Unsupported project completion authority format ${envelope.version}." }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in project completion authority event $recordNumber."
            }
            validateProjectCompletionEvent(envelope.value, events)
            events += envelope.value
            envelope.value
        }
    }
}

class ProjectCompletionAuthorityService(
    private val store: ProjectCompletionAuthorityStore = TransientProjectCompletionAuthorityStore(),
) {
    fun events(): List<ProjectCompletionAuthorityEvent> = store.load()

    fun recordContract(genesis: ProjectGenesisRevision, claims: List<PurposeClaimDefinition>): ProjectCompletionContract =
        requireNotNull(store.appendNext { eventId, previous ->
            val contract = compileProjectCompletionContract(genesis, claims)
            require(previous.mapNotNull { it.contract }.none {
                it.projectId == contract.projectId && it.genesisHash == contract.genesisHash
            }) { "Project completion contract already exists for this purpose revision." }
            ProjectCompletionAuthorityEvent(eventId, contract = contract)
        }.contract)

    fun linkEvidence(contractHash: String, claimId: String, evidenceId: Long, observationBoundary: String): PurposeEvidenceLink =
        requireNotNull(store.appendNext { eventId, _ ->
            ProjectCompletionAuthorityEvent(
                eventId,
                evidenceLink = PurposeEvidenceLink(claimId, contractHash, evidenceId, observationBoundary),
            )
        }.evidenceLink)

    fun recordAcceptance(
        contractHash: String,
        claimId: String,
        targetRevision: String,
        accepted: Boolean,
        authority: String,
    ): PurposeClaimAcceptance = requireNotNull(store.appendNext { eventId, _ ->
        ProjectCompletionAuthorityEvent(
            eventId,
            acceptance = PurposeClaimAcceptance(claimId, contractHash, targetRevision, accepted, authority),
        )
    }.acceptance)

    fun assess(
        contractHash: String,
        targetRevision: String,
        evidence: List<EvidenceRecord>,
        implementedClaimIds: Set<String> = emptySet(),
        skillExecutions: List<PurposeSkillExecution> = emptyList(),
    ): ProjectCompletionAssessment {
        val events = store.load()
        val contract = requireNotNull(events.mapNotNull { it.contract }.singleOrNull { it.hash == contractHash }) {
            "Project completion contract does not exist."
        }
        return assessProjectCompletion(
            contract,
            targetRevision,
            evidence,
            events.mapNotNull { it.evidenceLink }.filter { it.contractHash == contractHash },
            events.mapNotNull { it.acceptance }.filter { it.contractHash == contractHash },
            implementedClaimIds,
            skillExecutions,
        )
    }
}

fun compileProjectCompletionContract(
    genesis: ProjectGenesisRevision,
    claims: List<PurposeClaimDefinition>,
): ProjectCompletionContract {
    require(genesis.phase == GENESIS_READY && genesis.admitted) { "Project purpose must be admitted before completion compilation." }
    require(genesis.productIntent.isNotBlank() && genesis.experience.productPromise.isNotBlank()) {
        "Admitted project purpose is incomplete."
    }
    require(claims.isNotEmpty() && claims.all { claim ->
        claim.claimId.isNotBlank() && claim.outcome.isNotBlank() && claim.statement.isNotBlank() &&
            claim.verificationKind.isNotBlank() && claim.verificationMethod.isNotBlank() && claim.observationBoundary.isNotBlank()
            && claim.requiredSkillIds.all(String::isNotBlank) && claim.requiredSkillIds.distinct().size == claim.requiredSkillIds.size
    }) { "Project completion claims are incomplete." }
    require(claims.map { it.claimId }.distinct().size == claims.size) { "Project completion claim IDs must be unique." }
    val admittedOutcomes = (listOf(genesis.experience.productPromise) + genesis.experience.primaryJourney +
        genesis.experience.accessibility).map(String::trim).filter(String::isNotEmpty).distinct()
    require(claims.all { it.outcome in admittedOutcomes }) { "Project completion claim does not trace to an admitted outcome." }
    val draft = ProjectCompletionContract(
        projectId = genesis.projectId,
        genesisId = genesis.genesisId,
        genesisRevision = genesis.revision,
        genesisHash = genesis.hash,
        purpose = genesis.productIntent,
        intendedOutcomes = admittedOutcomes,
        claims = claims.sortedBy { it.claimId },
        nonGoals = genesis.experience.mustNotFeelLike.distinct(),
    )
    return draft.copy(hash = projectCompletionContractHash(draft))
}

fun assessProjectCompletion(
    contract: ProjectCompletionContract,
    targetRevision: String,
    evidence: List<EvidenceRecord>,
    evidenceLinks: List<PurposeEvidenceLink>,
    acceptances: List<PurposeClaimAcceptance>,
    implementedClaimIds: Set<String> = emptySet(),
    skillExecutions: List<PurposeSkillExecution> = emptyList(),
): ProjectCompletionAssessment {
    val diagnostics = mutableListOf<String>()
    if (contract.hash != projectCompletionContractHash(contract)) diagnostics += "Project completion contract hash is invalid."
    val evidenceById = evidence.associateBy { it.evidenceId }
    val claimStates = contract.claims.map { claim ->
        val matchingEvidence = evidenceLinks.filter { link ->
            link.claimId == claim.claimId && link.contractHash == contract.hash &&
                link.observationBoundary == claim.observationBoundary
        }.mapNotNull { evidenceById[it.evidenceId] }.filter {
            it.kind == claim.verificationKind && it.command == claim.verificationMethod && it.revision == targetRevision
        }
        val passingEvidence = matchingEvidence.filter { it.passed }
        val satisfiedSkillIds = skillExecutions.filter {
            it.contractHash == contract.hash && it.claimId == claim.claimId && it.skillVersion > 0 &&
                it.inputHash.matches(SHA256) && it.outputHash.matches(SHA256) && it.schemaValid
        }.mapTo(hashSetOf()) { it.skillId }
        val missingSkillIds = claim.requiredSkillIds.filter { it !in satisfiedSkillIds }.sorted()
        val accepted = acceptances.any {
            it.claimId == claim.claimId && it.contractHash == contract.hash &&
                it.targetRevision == targetRevision && it.accepted && it.authority.isNotBlank()
        }
        val state = when {
            passingEvidence.isNotEmpty() && accepted && missingSkillIds.isEmpty() -> PURPOSE_CLAIM_ACCEPTED
            passingEvidence.isNotEmpty() -> PURPOSE_CLAIM_EVIDENCE_PASSED
            matchingEvidence.isNotEmpty() -> PURPOSE_CLAIM_EVIDENCE_FAILED
            claim.claimId in implementedClaimIds -> PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED
            else -> PURPOSE_CLAIM_DEFINED
        }
        if (missingSkillIds.isNotEmpty()) diagnostics += "Purpose-linked claim ${claim.claimId} lacks required skills: ${missingSkillIds.joinToString()}."
        if (state != PURPOSE_CLAIM_ACCEPTED) diagnostics += "Purpose-linked claim ${claim.claimId} is $state."
        PurposeClaimState(claim.claimId, state, matchingEvidence.map { it.evidenceId }.sorted(), missingSkillIds)
    }
    return ProjectCompletionAssessment(
        done = diagnostics.isEmpty() && claimStates.isNotEmpty(),
        targetRevision = targetRevision,
        claimStates = claimStates,
        diagnostics = diagnostics,
    )
}

fun projectCompletionContractHash(contract: ProjectCompletionContract): String = stagedPlanHash(
    purposeJson.encodeToString(contract.copy(hash = "")),
)

private val purposeJson = Json { encodeDefaults = true }
private const val PROJECT_COMPLETION_STORE_VERSION = 1
private val SHA256 = Regex("[0-9a-f]{64}")

@Serializable
private data class ProjectCompletionEnvelope(
    val version: Int = PROJECT_COMPLETION_STORE_VERSION,
    val value: ProjectCompletionAuthorityEvent,
    val checksum: String,
)

private fun validateProjectCompletionEvent(
    event: ProjectCompletionAuthorityEvent,
    previous: List<ProjectCompletionAuthorityEvent>,
) {
    require(event.eventId == previous.size + 1L && event.recordedAt.isNotBlank()) {
        "Project completion authority event identity is invalid."
    }
    require(listOfNotNull(event.contract, event.evidenceLink, event.acceptance).size == 1) {
        "Project completion authority event must contain exactly one record."
    }
    event.contract?.let { contract ->
        require(contract.hash == projectCompletionContractHash(contract)) { "Project completion contract hash is invalid." }
    }
    event.evidenceLink?.let { link ->
        val contract = previous.mapNotNull { it.contract }.singleOrNull { it.hash == link.contractHash }
        require(contract != null && contract.claims.any { it.claimId == link.claimId } &&
            link.evidenceId > 0 && link.observationBoundary.isNotBlank()
        ) { "Purpose evidence link authority is invalid." }
    }
    event.acceptance?.let { acceptance ->
        val contract = previous.mapNotNull { it.contract }.singleOrNull { it.hash == acceptance.contractHash }
        require(contract != null && contract.claims.any { it.claimId == acceptance.claimId } &&
            acceptance.targetRevision.matches(Regex("[0-9a-f]{40}")) && acceptance.authority.isNotBlank()
        ) { "Purpose claim acceptance authority is invalid." }
    }
}