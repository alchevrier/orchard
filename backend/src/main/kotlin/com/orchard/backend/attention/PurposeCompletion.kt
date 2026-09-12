@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.attention

import com.orchard.backend.workspace.EvidenceRecord
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisRevision
import com.orchard.backend.workspace.stagedPlanHash
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
)

@Serializable
data class ProjectCompletionAssessment(
    val done: Boolean,
    val targetRevision: String,
    val claimStates: List<PurposeClaimState>,
    val diagnostics: List<String>,
)

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
        val accepted = acceptances.any {
            it.claimId == claim.claimId && it.contractHash == contract.hash &&
                it.targetRevision == targetRevision && it.accepted && it.authority.isNotBlank()
        }
        val state = when {
            passingEvidence.isNotEmpty() && accepted -> PURPOSE_CLAIM_ACCEPTED
            passingEvidence.isNotEmpty() -> PURPOSE_CLAIM_EVIDENCE_PASSED
            matchingEvidence.isNotEmpty() -> PURPOSE_CLAIM_EVIDENCE_FAILED
            claim.claimId in implementedClaimIds -> PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED
            else -> PURPOSE_CLAIM_DEFINED
        }
        if (state != PURPOSE_CLAIM_ACCEPTED) diagnostics += "Purpose-linked claim ${claim.claimId} is $state."
        PurposeClaimState(claim.claimId, state, matchingEvidence.map { it.evidenceId }.sorted())
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