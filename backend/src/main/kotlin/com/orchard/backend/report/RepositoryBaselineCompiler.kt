package com.orchard.backend.report

import com.orchard.backend.analysis.RepositoryBaselineAnalysisService
import com.orchard.backend.analysis.RepositoryBaselineTickResult
import com.orchard.backend.analysis.RepositoryBaselineTickStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class RepositoryBaselineCompilation(
    val projectId: Int,
    val status: RepositoryBaselineTickStatus,
    val stage: String? = null,
    val diagnostic: String = "",
)

class RepositoryBaselineCompiler(
    private val reports: ProjectReportService,
    private val analyze: suspend (Int) -> RepositoryBaselineTickResult,
) {
    constructor(reports: ProjectReportService, analysis: RepositoryBaselineAnalysisService) : this(
        reports,
        analysis::tick,
    )

    suspend fun tick(): List<RepositoryBaselineCompilation> = coroutineScope {
        val results = reports.synchronizeRepositoryBaselines()
            .filter(reports::repositoryBaselineAttemptDue)
            .map { projectId ->
                async {
                    val result = analyze(projectId)
                    val diagnosticStatus = diagnosticStatus(result.status)
                    if (diagnosticStatus != null) {
                        reports.recordRepositoryBaselineDiagnostic(
                            projectId = projectId,
                            status = diagnosticStatus,
                            diagnostic = result.diagnostic,
                            actionRequired = result.status in ACTION_REQUIRED_STATUSES,
                        )
                    }
                    RepositoryBaselineCompilation(projectId, result.status, result.stage, result.diagnostic)
                }
            }
            .awaitAll()
        reports.synchronizeRepositoryBaselines()
        results
    }

    private companion object {
        val ACTION_REQUIRED_STATUSES = setOf(
            RepositoryBaselineTickStatus.NO_COMPATIBLE_MODEL,
            RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
        )

        fun diagnosticStatus(status: RepositoryBaselineTickStatus): String? = when (status) {
            RepositoryBaselineTickStatus.IDLE,
            RepositoryBaselineTickStatus.STAGE_COMPLETED,
            RepositoryBaselineTickStatus.COMPLETE,
            RepositoryBaselineTickStatus.REPOSITORY_CHANGED -> null
            RepositoryBaselineTickStatus.BUSY -> "BUSY"
            RepositoryBaselineTickStatus.RESOURCE_BLOCKED -> "RESOURCE_CAPACITY_UNAVAILABLE"
            RepositoryBaselineTickStatus.NO_COMPATIBLE_MODEL,
            RepositoryBaselineTickStatus.MODEL_FAILED -> "MODEL_UNAVAILABLE"
            RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE -> "REPOSITORY_CONTEXT_UNAVAILABLE"
            RepositoryBaselineTickStatus.INVALID_ANALYSIS -> "INVALID_OUTPUT"
            else -> status.name
        }
    }
}