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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DesktopNetworkClient(private val client: HttpClient = createHttpClient()) : AutoCloseable {

    suspend fun getWorkspace(): WorkspaceSnapshotResponse =
        client.get("http://127.0.0.1:8085/api/workspace").body()

    suspend fun getModelProfileConfigurations(): List<ModelProfileConfigurationResponse> =
        client.get("http://127.0.0.1:8085/api/model-profiles").successBody()

    suspend fun updateModelProfile(override: ModelProfileOverrideRequest): List<ModelProfileConfigurationResponse> =
        client.put("http://127.0.0.1:8085/api/model-profiles/${override.profileId}") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(override)
        }.successBody()

    suspend fun getMachineResourceConfiguration(): MachineResourceConfigurationResponse =
        client.get("http://127.0.0.1:8085/api/machine-resources").successBody()

    suspend fun updateMachineUsagePolicy(policy: MachineUsagePolicyRequest): MachineResourceConfigurationResponse =
        client.put("http://127.0.0.1:8085/api/machine-resources/policy") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(policy)
        }.successBody()

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

    suspend fun generateDefinitionProposal(workItemId: Int): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/work-items/$workItemId/definition-proposals").successBody()

    suspend fun submitDefinitionFeedback(proposalId: Long, content: String): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/definition-proposals/$proposalId/feedback") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(DefinitionFeedbackRequest(content))
        }.successBody()

    suspend fun acceptDefinitionProposal(
        proposalId: Long,
        definition: WorkDefinitionSubmissionRequest,
    ): WorkspaceSnapshotResponse = client.post("http://127.0.0.1:8085/api/definition-proposals/$proposalId/accept") {
        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(AcceptDefinitionProposalRequest(definition))
    }.successBody()

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
    val definitionProposals: List<DefinitionProposalViewResponse> = emptyList(),
    val modelProfiles: List<ModelCapabilityProfileResponse> = emptyList(),
)

@Serializable
data class ModelProfileOverrideRequest(
    val profileId: String,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val preferredBindingId: String? = null,
)

@Serializable
data class ModelProfileConfigurationResponse(
    val defaultProfile: ModelExecutionProfileResponse,
    val effectiveProfile: ModelExecutionProfileResponse,
    val override: ModelProfileOverrideRequest? = null,
    val installedBindings: List<ModelBindingProfileResponse>,
    val compatibleBindingIds: List<String>,
)

@Serializable
data class ModelExecutionProfileResponse(
    val id: String,
    val version: Int,
    val reasoningClass: String,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val requiredCapabilities: Set<String>,
)

@Serializable
data class ModelCapabilityProfileResponse(
    val executionProfileId: String,
    val executionProfileVersion: Int,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val binding: ModelBindingProfileResponse,
    val bindingFingerprint: String,
    val sampleCount: Int,
    val schemaValidityRate: Double,
    val acceptedUnchangedCount: Int,
    val acceptedAfterEditCount: Int,
    val revisionRequestedCount: Int,
    val averageHumanRevisionFields: Double,
    val medianLatencyMillis: Long,
    val confidence: Double,
)

@Serializable
data class ModelBindingProfileResponse(
    val bindingId: String,
    val provider: String,
    val model: String,
    val contextWindowTokens: Int,
    val capabilities: Set<String>,
    val configuration: Map<String, String> = emptyMap(),
    val modelDigest: String? = null,
)

@Serializable
data class MachineUsagePolicyRequest(
    val capacityPercent: Int,
    val minimumFreeMemoryBytes: Long,
    val maxConcurrentModelExecutions: Int,
)

@Serializable
data class MachineResourceConfigurationResponse(
    val policy: MachineUsagePolicyRequest,
    val capacity: MachineCapacitySnapshotResponse,
    val reservedMemoryBytes: Long,
    val reservedCpuUnits: Int,
    val activeLeases: Int,
    val lastAdmission: ResourceAdmissionEvidenceResponse? = null,
)

@Serializable
data class MachineCapacitySnapshotResponse(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val logicalProcessors: Int,
    val systemCpuLoad: Double?,
    val observedAt: String,
)

@Serializable
data class ResourceAdmissionEvidenceResponse(
    val decision: String,
    val reason: String,
)

@Serializable
private data class DefinitionFeedbackRequest(val content: String)

@Serializable
private data class AcceptDefinitionProposalRequest(val definition: WorkDefinitionSubmissionRequest)

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
data class DefinitionProposalViewResponse(
    val proposal: DefinitionProposalResponse,
    val feedback: List<DefinitionFeedbackResponse> = emptyList(),
    val acceptedDefinitionId: Long? = null,
)

@Serializable
data class DefinitionProposalResponse(
    val proposalId: Long,
    val workItemId: Int,
    val revision: Int,
    val parentProposalId: Long? = null,
    val actor: String,
    val content: DefinitionProposalContentResponse,
    val provenance: DefinitionExecutionProvenanceResponse? = null,
    val hash: String,
)

@Serializable
data class DefinitionProposalContentResponse(
    val definition: WorkDefinitionSubmissionRequest,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

@Serializable
data class DefinitionExecutionProvenanceResponse(
    val executor: String,
    val model: String,
    val executionProfileId: String,
    val bindingFingerprint: String,
    val promptVersion: Int,
    val promptHash: String,
    val contextHash: String,
    val outputHash: String,
    val executionId: Long,
)

@Serializable
data class DefinitionFeedbackResponse(
    val feedbackId: Long,
    val proposalId: Long,
    val actor: String,
    val content: String,
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

private suspend inline fun <reified T> HttpResponse.successBody(): T {
    if (!status.isSuccess()) error("Orchard returned HTTP ${status.value}: ${bodyAsText().take(512)}")
    return body()
}