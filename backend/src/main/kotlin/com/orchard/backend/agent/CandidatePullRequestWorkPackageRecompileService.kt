package com.orchard.backend.agent

import com.orchard.backend.analysis.ExecutableWorkPackageStore
import com.orchard.backend.analysis.recompileExecutableWorkPackage

fun interface WorkPackageCorrectionRecompileGateway {
    fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome
}

class PackageStoreWorkPackageCorrectionRecompileGateway(
    private val workPackageStore: ExecutableWorkPackageStore,
) : WorkPackageCorrectionRecompileGateway {
    override fun dispatch(correction: CandidatePullRequestCorrection): CandidateCorrectionDispatchOutcome {
        val packages = workPackageStore.load()
        val prior = packages.singleOrNull {
            it.packageId == correction.workPackageId && it.hash == correction.workPackageHash && it.runId == correction.runId
        } ?: return CandidateCorrectionDispatchOutcome.REJECTED
        if (packages.filter { it.runId == correction.runId }.maxByOrNull { it.revision }?.packageId != prior.packageId) {
            return CandidateCorrectionDispatchOutcome.REJECTED
        }
        return runCatching {
            workPackageStore.appendNext(correction.runId) { packageId, revision ->
                recompileExecutableWorkPackage(packageId, revision, prior, correction)
            }
        }.fold(
            onSuccess = { CandidateCorrectionDispatchOutcome.DISPATCHED },
            onFailure = { CandidateCorrectionDispatchOutcome.DEFERRED },
        )
    }
}

class CandidatePullRequestWorkPackageRecompileService(
    private val correctionStore: CandidatePullRequestCorrectionStore,
    private val dispatchStore: CandidatePullRequestCorrectionDispatchStore,
    private val recompileGateway: WorkPackageCorrectionRecompileGateway,
) {
    @Synchronized
    fun tick(): CandidatePullRequestCorrectionDispatch? {
        val dispatchedCorrectionIds = dispatchStore.load().mapTo(hashSetOf()) { it.correctionId }
        val corrections = correctionStore.load().filter {
            it.correctionTarget == REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE && it.correctionId !in dispatchedCorrectionIds
        }
        corrections.forEach { correction ->
            when (val outcome = recompileGateway.dispatch(correction)) {
                CandidateCorrectionDispatchOutcome.DEFERRED -> Unit
                CandidateCorrectionDispatchOutcome.DISPATCHED,
                CandidateCorrectionDispatchOutcome.REJECTED -> return appendDispatch(correction, outcome)
            }
        }
        return null
    }

    private fun appendDispatch(
        correction: CandidatePullRequestCorrection,
        outcome: CandidateCorrectionDispatchOutcome,
    ): CandidatePullRequestCorrectionDispatch = dispatchStore.appendNext { dispatchId ->
        val status = if (outcome == CandidateCorrectionDispatchOutcome.DISPATCHED) {
            CANDIDATE_CORRECTION_DISPATCHED
        } else {
            CANDIDATE_CORRECTION_REJECTED
        }
        val draft = CandidatePullRequestCorrectionDispatch(
            dispatchId = dispatchId,
            correctionId = correction.correctionId,
            correctionHash = correction.hash,
            correctionTarget = correction.correctionTarget,
            runId = correction.runId,
            status = status,
            diagnostic = if (status == CANDIDATE_CORRECTION_DISPATCHED) {
                "Work-package recompilation authority was dispatched."
            } else {
                "Work-package recompilation authority could not be applied to its pinned package."
            },
            hash = "",
        )
        draft.copy(hash = candidatePullRequestCorrectionDispatchHash(draft))
    }
}