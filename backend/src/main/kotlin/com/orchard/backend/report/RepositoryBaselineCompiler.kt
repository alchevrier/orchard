package com.orchard.backend.report

import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.agent.GenesisProposalStatus

data class RepositoryBaselineCompilation(
    val projectId: Int,
    val status: GenesisProposalStatus,
    val diagnostic: String = "",
)

class RepositoryBaselineCompiler(
    private val reports: ProjectReportService,
    private val assess: suspend (Int, GenesisProposalRequest) -> com.orchard.backend.agent.GenesisProposalResult,
) {
    constructor(reports: ProjectReportService, genesis: GenesisIntelligenceService) : this(
        reports,
        genesis::assessRepository,
    )

    suspend fun tick(): List<RepositoryBaselineCompilation> {
        val results = reports.synchronizeRepositoryBaselines().filter(reports::repositoryBaselineAttemptDue).map { projectId ->
            val result = assess(projectId, GenesisProposalRequest(BASELINE_OBJECTIVE))
            if (result.status != GenesisProposalStatus.CREATED) {
                reports.recordRepositoryBaselineDiagnostic(
                    projectId = projectId,
                    status = result.status.name,
                    diagnostic = result.diagnostic,
                    actionRequired = result.status in ACTION_REQUIRED_STATUSES,
                )
            }
            RepositoryBaselineCompilation(projectId, result.status, result.diagnostic)
        }
        reports.synchronizeRepositoryBaselines()
        return results
    }

    private companion object {
        val ACTION_REQUIRED_STATUSES = setOf(
            GenesisProposalStatus.MODEL_UNAVAILABLE,
            GenesisProposalStatus.REPOSITORY_CONTEXT_UNAVAILABLE,
        )
        const val BASELINE_OBJECTIVE =
            "Derive a provisional, revision-pinned baseline of the repository's apparent current intent, design, implementation, techniques, tests, reports, and evidence. Do not propose future product intent or mutate Genesis authority."
    }
}