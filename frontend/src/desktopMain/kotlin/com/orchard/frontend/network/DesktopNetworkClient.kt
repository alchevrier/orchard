package com.orchard.frontend.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DesktopNetworkClient(private val client: HttpClient = createHttpClient()) : AutoCloseable {

    suspend fun getWorkspace(): WorkspaceSnapshotResponse =
        client.get("http://127.0.0.1:8085/api/workspace").body()

    suspend fun submitArchitectPrompt(prompt: String): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8086/api/architect/chat") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ArchitectChatRequest(prompt))
        }.body()

    suspend fun bindRepository(projectId: Int, path: String): WorkspaceSnapshotResponse =
        client.put("http://127.0.0.1:8085/api/projects/$projectId/repository") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(BindRepositoryRequest(path))
        }.body()

    suspend fun startWorkflow(workItemId: Int): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/work-items/$workItemId/runs").body()

    suspend fun submitWorkDefinition(
        workItemId: Int,
        definition: WorkDefinitionSubmissionRequest,
    ): WorkspaceSnapshotResponse = client.post("http://127.0.0.1:8085/api/work-items/$workItemId/definitions") {
        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(definition)
    }.body()

    suspend fun submitEvidence(runId: Long, evidence: EvidenceSubmissionRequest): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/workflow-runs/$runId/evidence") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(evidence)
        }.body()

    suspend fun recordAttempt(runId: Long, attempt: AttemptSubmissionRequest): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/workflow-runs/$runId/attempts") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(attempt)
        }.body()

    suspend fun cancelWorkflow(runId: Long): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/workflow-runs/$runId/cancel").body()

    override fun close() {
        client.close()
    }

    private companion object {
        fun createHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

@Serializable
private data class ArchitectChatRequest(val prompt: String)

@Serializable
private data class BindRepositoryRequest(val path: String)

@Serializable
data class WorkspaceSnapshotResponse(
    val resources: Map<String, WorkspaceResourceResponse> = emptyMap(),
    val repositories: Map<Int, RepositoryResponse> = emptyMap(),
    val workflowRuns: List<WorkflowRunResponse> = emptyList(),
    val workDefinitions: List<WorkDefinitionResponse> = emptyList(),
)

@Serializable
data class WorkspaceResourceResponse(
    val type: String,
    val path: String,
    val action: String,
)

@Serializable
data class RepositoryResponse(
    val projectId: Int,
    val path: String,
    val available: Boolean,
    val branch: String = "",
    val remote: String = "",
    val dirty: Boolean = false,
    val buildSystem: String = "Unknown",
)

@Serializable
data class WorkflowRunResponse(
    val runId: Long,
    val state: String,
    val context: ContextManifestResponse,
    val workflow: ResolvedWorkflowResponse,
    val evidence: List<EvidenceRecordResponse> = emptyList(),
    val workDefinition: WorkDefinitionResponse? = null,
)

@Serializable
data class WorkDefinitionSubmissionRequest(
    val requestedOutcome: String,
    val currentBehavior: String,
    val requiredBehavior: String,
    val scope: List<String>,
    val nonGoals: List<String>,
    val constraints: List<String>,
    val acceptanceCriteria: List<AcceptanceCriterionRequest>,
    val unresolvedQuestions: List<String> = emptyList(),
    val proposedSplitTitles: List<String> = emptyList(),
    val reproduction: String = "",
    val regressionCriterion: String = "",
)

@Serializable
data class AcceptanceCriterionRequest(
    val description: String,
    val verification: String,
)

@Serializable
data class WorkDefinitionResponse(
    val definitionId: Long,
    val revision: Int,
    val workItemId: Int,
    val systemWorkflow: SystemWorkflowResponse,
    val definition: WorkDefinitionSubmissionRequest,
    val assessment: DefinitionAssessmentResponse,
    val hash: String,
)

@Serializable
data class SystemWorkflowResponse(
    val id: String,
    val version: Int,
    val phases: List<String>,
)

@Serializable
data class DefinitionAssessmentResponse(
    val status: String,
    val missingFields: List<String> = emptyList(),
    val ambiguities: List<String> = emptyList(),
)

@Serializable
data class EvidenceSubmissionRequest(
    val kind: String,
    val revision: String,
    val command: String,
    val exitCode: Int,
    val outputHash: String,
    val summary: String,
    val producer: String,
)

@Serializable
data class AttemptSubmissionRequest(
    val description: String,
    val outcome: String,
    val diagnosticHash: String,
    val successful: Boolean,
)

@Serializable
data class EvidenceRecordResponse(
    val evidenceId: Long,
    val kind: String,
    val revision: String,
    val passed: Boolean,
)

@Serializable
data class ContextManifestResponse(
    val workItemId: Int,
    val repository: RepositoryHeadResponse,
    val recalledEpisodes: List<EpisodeRecallResponse> = emptyList(),
)

@Serializable
data class RepositoryHeadResponse(
    val commitHash: String,
)

@Serializable
data class EpisodeRecallResponse(
    val episodeId: Long,
    val score: Int,
    val problem: String,
    val failedApproaches: List<String> = emptyList(),
    val resolution: String,
    val evidenceSummary: String,
    val sourceRevision: String,
)

@Serializable
data class ResolvedWorkflowResponse(
    val id: String,
    val version: Int,
    val evidenceContract: EvidenceContractResponse,
)

@Serializable
data class EvidenceContractResponse(
    val id: String,
    val version: Int,
    val requirements: List<EvidenceRequirementResponse>,
)

@Serializable
data class EvidenceRequirementResponse(
    val kind: String,
    val description: String,
)