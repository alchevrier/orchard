package com.orchard.backend.agent

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ModelWorkPriority
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.ModelProfileSettingsStore
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.effectiveModelExecutionProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CandidateAutomatedReviewTickStatus {
    IDLE,
    RECORDED,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_OUTPUT,
}

@Serializable
data class CandidateAutomatedReviewTickResult(
    val status: CandidateAutomatedReviewTickStatus,
    val pullRequestId: Long? = null,
    val kind: String? = null,
    val diagnostic: String = "",
)

@Serializable
private data class CandidateAutomatedReviewEnvelope(
    val reviewerKind: String,
    val reviewFocus: String,
    val candidate: CandidatePullRequest,
)

@Serializable
private data class CandidateAutomatedReviewOutput(
    val findings: List<CandidatePullRequestReviewFinding>,
)

class CandidatePullRequestAutomatedReviewService(
    private val pullRequestStore: CandidatePullRequestStore,
    private val reviewService: CandidatePullRequestReviewService,
    private val modelProviders: List<ModelProvider>,
    private val resourceController: MachineResourceController,
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    suspend fun tick(): CandidateAutomatedReviewTickResult {
        val pending = pullRequestStore.load().asSequence().flatMap { pullRequest ->
            REVIEW_FOCUS.keys.asSequence()
                .filter { kind -> reviewService.reviews(pullRequest.pullRequestId).none { it.kind == kind } }
                .map { kind -> pullRequest to kind }
        }.firstOrNull() ?: return CandidateAutomatedReviewTickResult(CandidateAutomatedReviewTickStatus.IDLE)
        val (pullRequest, kind) = pending
        val provider = modelProviders.firstOrNull() ?: return CandidateAutomatedReviewTickResult(
            CandidateAutomatedReviewTickStatus.MODEL_FAILED, pullRequest.pullRequestId, kind, "No reviewer model is configured.",
        )
        val defaultProfile = DefaultModelExecutionProfiles.boundedIndependentAudit
        val profileOverride = runCatching { profileSettingsStore.load() }.getOrElse {
            return CandidateAutomatedReviewTickResult(
                CandidateAutomatedReviewTickStatus.MODEL_FAILED,
                pullRequest.pullRequestId,
                kind,
                "Cannot load model profile settings.",
            )
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, profileOverride)
        val envelope = CandidateAutomatedReviewEnvelope(kind, requireNotNull(REVIEW_FOCUS[kind]), pullRequest)
        val prompt = "$SYSTEM_PROMPT\n\nAuthoritative candidate review envelope:\n${json.encodeToString(envelope)}"
        val promptTokens = estimateModelTokens(prompt)
        if (promptTokens > profile.inputBudgetTokens) return CandidateAutomatedReviewTickResult(
            CandidateAutomatedReviewTickStatus.INVALID_OUTPUT, pullRequest.pullRequestId, kind, "Reviewer envelope exceeds its bounded input budget.",
        )
        val admission = resourceController.acquire(provider.resourceDemand(profile, promptTokens), ModelWorkPriority.DELIVERY)
        val lease = admission.lease ?: return CandidateAutomatedReviewTickResult(
            CandidateAutomatedReviewTickStatus.RESOURCE_BLOCKED, pullRequest.pullRequestId, kind, admission.evidence.reason,
        )
        val output = runCatching {
            lease.use {
                provider.executeCircuitSynthesis(prompt, profile.outputBudgetTokens, profile.inputBudgetTokens + profile.outputBudgetTokens)
            }
        }.getOrElse { error ->
            return CandidateAutomatedReviewTickResult(CandidateAutomatedReviewTickStatus.MODEL_FAILED, pullRequest.pullRequestId, kind, error.message.orEmpty())
        }
        val proposal = runCatching { json.decodeFromString<CandidateAutomatedReviewOutput>(output.text) }.getOrNull()
            ?: return CandidateAutomatedReviewTickResult(CandidateAutomatedReviewTickStatus.INVALID_OUTPUT, pullRequest.pullRequestId, kind, "Reviewer output is not strict JSON.")
        val result = reviewService.submit(CandidatePullRequestReviewSubmission(
            pullRequest.pullRequestId, kind, "AUTOMATED_$kind", proposal.findings,
        ))
        return when (result.status) {
            CandidatePullRequestReviewMutationStatus.RECORDED -> CandidateAutomatedReviewTickResult(
                CandidateAutomatedReviewTickStatus.RECORDED, pullRequest.pullRequestId, kind,
            )
            CandidatePullRequestReviewMutationStatus.INVALID_REVIEW -> CandidateAutomatedReviewTickResult(
                CandidateAutomatedReviewTickStatus.INVALID_OUTPUT, pullRequest.pullRequestId, kind, result.diagnostic,
            )
            else -> CandidateAutomatedReviewTickResult(
                CandidateAutomatedReviewTickStatus.MODEL_FAILED, pullRequest.pullRequestId, kind, result.diagnostic,
            )
        }
    }

    private companion object {
        const val SYSTEM_PROMPT = """
You are an independent candidate reviewer. Return strict JSON only: {\"findings\":[...]}. 
Each finding must contain criterion, observation, severity (INFO|WARNING|BLOCKER), correctionTarget, and evidenceHashes.
Do not admit designs, accept candidates, promote code, or request actions outside the declared correction targets.
"""
        val REVIEW_FOCUS = linkedMapOf(
            CANDIDATE_REVIEW_CODE to "Assess correctness, safety, maintainability, compatibility, and test adequacy from the candidate evidence.",
            CANDIDATE_REVIEW_INTENT to "Assess whether observable behavior satisfies the admitted outcome and constraints represented by the candidate claims.",
            CANDIDATE_REVIEW_DESIGN to "Assess whether the candidate remains coherent with its pinned work-package boundary and declared invariants.",
            CANDIDATE_REVIEW_INTEGRATION to "Assess public-interface and durable-behavior integration evidence supplied by the candidate.",
        )
    }
}