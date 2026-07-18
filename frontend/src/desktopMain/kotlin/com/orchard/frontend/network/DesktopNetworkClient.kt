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

    suspend fun getCompanyState(): CompanyWorkspaceResponse =
        client.get("http://127.0.0.1:8085/api/company/state").body()

    suspend fun startCompany(projectId: Int): CompanyWorkspaceResponse =
        client.post("http://127.0.0.1:8085/api/projects/$projectId/company/start").successBody()

    suspend fun promoteCompanyRun(runId: Long): CompanyWorkspaceResponse =
        client.post("http://127.0.0.1:8085/api/company/runs/$runId/promotion").successBody()

    suspend fun getEngineeringStandards(projectId: Int): EngineeringStandardsViewResponse =
        client.get("http://127.0.0.1:8085/api/projects/$projectId/engineering-standards").successBody()

    suspend fun updateEngineeringStandard(
        projectId: Int,
        submission: EngineeringStandardSubmissionRequest,
    ): StandardUpdateResultResponse = client.put("http://127.0.0.1:8085/api/projects/$projectId/engineering-standards") {
        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(submission)
    }.successBody()

    suspend fun runConformanceScan(projectId: Int): ConformanceScanResultResponse =
        client.post("http://127.0.0.1:8085/api/projects/$projectId/conformance-scans").successBody()

    suspend fun admitConformanceBacklog(scanId: Long): BacklogAdmissionResultResponse =
        client.post("http://127.0.0.1:8085/api/conformance-scans/$scanId/admission").successBody()

    suspend fun getRemediationCampaigns(projectId: Int): List<RemediationCampaignViewResponse> =
        client.get("http://127.0.0.1:8085/api/projects/$projectId/remediation-campaigns").successBody()

    suspend fun advanceProjectGenesis(
        projectId: Int,
        submission: ProjectGenesisSubmissionRequest,
    ): WorkspaceSnapshotResponse = client.post("http://127.0.0.1:8085/api/projects/$projectId/genesis") {
        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(submission)
    }.successBody()

    suspend fun admitProjectGenesis(projectId: Int): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/projects/$projectId/genesis/admission").successBody()

    suspend fun proposeProjectGenesis(projectId: Int, prompt: String): GenesisProposalResponse =
        client.post("http://127.0.0.1:8085/api/projects/$projectId/genesis/proposal") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(GenesisProposalRequest(prompt))
        }.successBody()

    suspend fun getModelProfileConfigurations(): List<ModelProfileConfigurationResponse> =
        client.get("http://127.0.0.1:8085/api/model-profiles").successBody()

    suspend fun updateModelProfile(override: ModelProfileOverrideRequest): List<ModelProfileConfigurationResponse> =
        client.put("http://127.0.0.1:8085/api/model-profiles/${override.profileId}") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(override)
        }.successBody()

    suspend fun getModelProviderCatalog(): ModelProviderCatalogResponse =
        client.get("http://127.0.0.1:8085/api/model-providers").successBody()

    suspend fun updateModelProviderCatalog(catalog: ModelProviderCatalogResponse): ModelProviderCatalogResponse =
        client.put("http://127.0.0.1:8085/api/model-providers") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(catalog)
        }.successBody()

    suspend fun inspectModelProviders(): List<ModelEndpointInspectionResponse> =
        client.get("http://127.0.0.1:8085/api/model-providers/inspection").successBody()

    suspend fun getMachineResourceConfiguration(): MachineResourceConfigurationResponse =
        client.get("http://127.0.0.1:8085/api/machine-resources").successBody()

    suspend fun updateMachineUsagePolicy(policy: MachineUsagePolicyRequest): MachineResourceConfigurationResponse =
        client.put("http://127.0.0.1:8085/api/machine-resources/policy") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(policy)
        }.successBody()

    suspend fun acceptStagedPlan(plan: StagedDeliveryPlanSubmissionRequest): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/staged-plans") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(plan)
        }.successBody()

    suspend fun generateCircuitProposal(scopeId: Int): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/staged-plan-proposals/$scopeId/generate").successBody()

    suspend fun acceptCircuitProposal(proposalId: Long): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8085/api/staged-plan-proposals/$proposalId/accept").successBody()

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

    suspend fun recordCriterionJudgment(
        runId: Long,
        judgment: CriterionJudgmentSubmissionRequest,
    ): WorkspaceSnapshotResponse = client.post("http://127.0.0.1:8085/api/workflow-runs/$runId/criterion-judgments") {
        headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(judgment)
    }.successBody()

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
    val stagedPlans: List<StagedDeliveryPlanViewResponse> = emptyList(),
    val circuitProposals: List<CircuitProposalViewResponse> = emptyList(),
    val stageWorkflows: List<StageExecutionWorkflowDefinitionResponse> = emptyList(),
    val circuitDispatches: List<CircuitDispatchViewResponse> = emptyList(),
    val projectGenesis: List<ProjectGenesisViewResponse> = emptyList(),
)

@Serializable
data class CompanyWorkspaceResponse(
    val workspace: WorkspaceSnapshotResponse = WorkspaceSnapshotResponse(),
    val companyProjects: List<CompanyProjectResponse> = emptyList(),
    val executionPlans: List<RepositoryExecutionPlanResponse> = emptyList(),
    val companyDiagnostic: String = "",
)

@Serializable
data class EngineeringPracticeResponse(
    val practiceId: String,
    val title: String,
    val category: String,
    val severity: String,
    val applicability: String,
    val requirement: String,
    val requiredEvidence: List<String>,
    val remediation: String,
    val enabled: Boolean = true,
)

@Serializable
data class EngineeringStandardSubmissionRequest(
    val name: String,
    val practices: List<EngineeringPracticeResponse>,
    val actor: String = "HUMAN",
)

@Serializable
data class EngineeringStandardRevisionResponse(
    val standardId: Long,
    val projectId: Int,
    val revision: Int,
    val name: String,
    val practices: List<EngineeringPracticeResponse>,
    val actor: String,
    val createdAt: String,
    val previousHash: String? = null,
    val hash: String,
)

@Serializable
data class ConformanceCitationResponse(val path: String, val contentHash: String, val observation: String)

@Serializable
data class ConformanceFindingResponse(
    val findingId: String,
    val practiceId: String,
    val disposition: String,
    val summary: String,
    val citations: List<ConformanceCitationResponse>,
    val affectedPaths: List<String>,
    val acceptanceCriteria: List<String>,
    val verificationCommands: List<String>,
    val confidence: Double,
)

@Serializable
data class BacklogProposalNodeResponse(
    val nodeId: String,
    val parentNodeId: String? = null,
    val type: String,
    val title: String,
    val description: String,
    val findingIds: List<String>,
    val acceptanceCriteria: List<String> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
)

@Serializable
data class RepositoryConformanceScanResponse(
    val scanId: Long,
    val projectId: Int,
    val standardId: Long,
    val standardRevision: Int,
    val standardHash: String,
    val repositoryRevision: String,
    val findings: List<ConformanceFindingResponse>,
    val proposedBacklog: List<BacklogProposalNodeResponse>,
    val modelBindingFingerprint: String,
    val promptHash: String,
    val contextHash: String,
    val outputHash: String,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class ConformanceBacklogAdmissionResponse(
    val admissionId: Long,
    val scanId: Long,
    val scanHash: String,
    val projectId: Int,
    val repositoryRevision: String,
    val admittedEntityIds: List<Int>,
    val actor: String,
    val admittedAt: String,
    val hash: String,
)

@Serializable
data class EngineeringStandardsViewResponse(
    val baseline: List<EngineeringPracticeResponse> = emptyList(),
    val standards: List<EngineeringStandardRevisionResponse> = emptyList(),
    val scans: List<RepositoryConformanceScanResponse> = emptyList(),
    val admissions: List<ConformanceBacklogAdmissionResponse> = emptyList(),
)

@Serializable
data class StandardUpdateResultResponse(
    val status: String,
    val standard: EngineeringStandardRevisionResponse? = null,
    val diagnostic: String = "",
)

@Serializable
data class ConformanceScanResultResponse(
    val status: String,
    val scan: RepositoryConformanceScanResponse? = null,
    val diagnostic: String = "",
)

@Serializable
data class BacklogAdmissionResultResponse(
    val status: String,
    val admission: ConformanceBacklogAdmissionResponse? = null,
    val diagnostic: String = "",
)

@Serializable
data class CampaignPracticeLinkResponse(
    val practiceId: String,
    val seedFindingId: String,
    val backlogNodeIds: List<String>,
    val admittedEntityIds: List<Int>,
)

@Serializable
data class CampaignSeedPracticeResponse(
    val practiceId: String,
    val findingId: String,
    val disposition: String,
)

@Serializable
data class RemediationCampaignResponse(
    val campaignId: Long,
    val projectId: Int,
    val standardId: Long,
    val standardRevision: Int,
    val standardHash: String,
    val seedScanId: Long,
    val seedScanHash: String,
    val seedAdmissionId: Long,
    val seedAdmissionHash: String,
    val seedRepositoryRevision: String,
    val seedPractices: List<CampaignSeedPracticeResponse>,
    val links: List<CampaignPracticeLinkResponse>,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class CampaignPracticeEvaluationResponse(
    val practiceId: String,
    val priorDisposition: String,
    val currentDisposition: String,
    val resolved: Boolean,
    val regressed: Boolean,
)

@Serializable
data class RemediationCampaignEvaluationResponse(
    val evaluationId: Long,
    val campaignId: Long,
    val scanId: Long,
    val scanHash: String,
    val repositoryRevision: String,
    val promotionIds: List<Long>,
    val practices: List<CampaignPracticeEvaluationResponse>,
    val state: String,
    val idempotencyKey: String,
    val recordedAt: String,
    val hash: String,
)

@Serializable
data class RemediationCampaignViewResponse(
    val campaign: RemediationCampaignResponse,
    val evaluations: List<RemediationCampaignEvaluationResponse>,
    val state: String,
)

@Serializable
data class RepositoryExecutionPlanResponse(
    val planId: Long,
    val runId: Long,
    val revision: Int,
    val projectId: Int,
    val baseRevision: String,
    val content: RepositoryAnalysisPlanContentResponse,
    val hash: String,
)

@Serializable
data class RepositoryAnalysisPlanContentResponse(
    val disposition: String,
    val summary: String,
    val reuse: List<String> = emptyList(),
    val preservedInvariants: List<String> = emptyList(),
    val nonGoals: List<String> = emptyList(),
    val operations: List<ExecutionPlanOperationResponse> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
)

@Serializable
data class ExecutionPlanOperationResponse(
    val order: Int,
    val action: String,
    val path: String,
    val symbol: String? = null,
    val instruction: String,
    val acceptanceCriteria: List<String> = emptyList(),
)

@Serializable
data class CompanyProjectResponse(
    val projectId: Int,
    val phase: String,
    val health: String,
    val ruleSet: ArchitectureRuleSetResponse? = null,
    val staff: List<InstalledStaffResponse> = emptyList(),
    val assignments: List<StaffAssignmentResponse> = emptyList(),
    val audits: List<AuditJudgmentResponse> = emptyList(),
    val escalations: List<StaffEscalationResponse> = emptyList(),
    val acceptances: List<CompanyAcceptanceResponse> = emptyList(),
    val promotions: List<LocalPromotionResponse> = emptyList(),
    val accountability: List<AccountabilityLinkResponse> = emptyList(),
    val requiredDecision: String? = null,
)

@Serializable
data class ArchitectureRuleSetResponse(
    val ruleSetId: Long,
    val projectId: Int,
    val revision: Int,
    val genesisRevision: Int,
    val genesisHash: String,
    val rules: List<ArchitectureRuleResponse>,
    val compiledAt: String,
    val hash: String,
)

@Serializable
data class ArchitectureRuleResponse(
    val ruleId: String,
    val kind: String,
    val statement: String,
    val repositoryPaths: List<String> = emptyList(),
    val severity: String,
)

@Serializable
data class InstalledStaffResponse(
    val bindingFingerprint: String,
    val bindingId: String,
    val provider: String,
    val model: String,
    val roles: List<String>,
    val sampleCount: Int,
    val schemaValidityRate: Double,
    val confidence: Double,
)

@Serializable
data class StaffAssignmentResponse(
    val assignmentId: Long,
    val runId: Long,
    val role: String,
    val risk: String,
    val model: String,
    val rationale: String,
    val evidenceSampleCount: Int,
    val confidence: Double,
)

@Serializable
data class AuditFindingResponse(
    val ruleId: String,
    val status: String,
    val summary: String,
)

@Serializable
data class AuditJudgmentResponse(
    val auditId: Long,
    val runId: Long,
    val role: String,
    val candidateRevision: String,
    val findings: List<AuditFindingResponse>,
    val status: String,
    val rationale: String,
)

@Serializable
data class StaffEscalationResponse(
    val escalationId: Long,
    val runId: Long,
    val requiredRole: String,
    val reason: String,
)

@Serializable
data class CompanyAcceptanceResponse(
    val acceptanceId: Long,
    val runId: Long,
    val candidateRevision: String,
    val auditIds: List<Long>,
    val acceptedBy: String,
)

@Serializable
data class LocalPromotionResponse(
    val promotionId: Long,
    val runId: Long,
    val candidateRevision: String,
    val destinationRevision: String,
)

@Serializable
data class AccountabilityLinkResponse(
    val from: String,
    val relation: String,
    val to: String,
)

@Serializable
data class ExperienceContractRequest(
    val audience: String = "",
    val productPromise: String = "",
    val primaryJourney: List<String> = emptyList(),
    val interactionPrinciples: List<String> = emptyList(),
    val emotionalQualities: List<String> = emptyList(),
    val mustNotFeelLike: List<String> = emptyList(),
    val accessibility: List<String> = emptyList(),
)

@Serializable
data class ArchitectureComponentRequest(
    val componentId: String,
    val name: String,
    val responsibility: String,
    val dependsOn: List<String> = emptyList(),
    val requirementIds: List<String> = emptyList(),
    val repositoryPaths: List<String> = emptyList(),
)

@Serializable
data class ArchitectureDecisionRequest(
    val decisionId: String,
    val title: String,
    val status: String = "CANDIDATE",
    val context: String,
    val decision: String,
    val consequences: List<String> = emptyList(),
    val componentIds: List<String> = emptyList(),
    val requirementIds: List<String> = emptyList(),
)

@Serializable
data class RepositoryBlueprintRequest(
    val rootName: String = "",
    val toolchain: String = "",
    val modules: List<String> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
    val policyPackIds: List<String> = emptyList(),
)

@Serializable
data class ProjectGenesisSubmissionRequest(
    val classification: String? = null,
    val productIntent: String? = null,
    val experience: ExperienceContractRequest? = null,
    val components: List<ArchitectureComponentRequest>? = null,
    val decisions: List<ArchitectureDecisionRequest>? = null,
    val firstEpicId: Int? = null,
    val blueprint: RepositoryBlueprintRequest? = null,
    val baseRevision: Int,
    val baseHash: String? = null,
)

@Serializable
data class ProjectGenesisRevisionResponse(
    val genesisId: Long,
    val projectId: Int,
    val revision: Int,
    val phase: String,
    val classification: String? = null,
    val productIntent: String = "",
    val experience: ExperienceContractRequest = ExperienceContractRequest(),
    val components: List<ArchitectureComponentRequest> = emptyList(),
    val decisions: List<ArchitectureDecisionRequest> = emptyList(),
    val firstEpicId: Int? = null,
    val blueprint: RepositoryBlueprintRequest? = null,
    val admitted: Boolean = false,
    val actor: String,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class ProjectGenesisViewResponse(
    val projectId: Int,
    val phase: String,
    val revision: ProjectGenesisRevisionResponse? = null,
    val progress: Int,
    val nextQuestion: String,
    val permittedAction: String,
    val blockingReason: String? = null,
)

@Serializable
private data class GenesisProposalRequest(val prompt: String)

@Serializable
data class GenesisProposalResponse(
    val projectId: Int,
    val phase: String,
    val baseRevision: Int,
    val baseHash: String? = null,
    val submission: ProjectGenesisSubmissionRequest,
    val observations: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val model: String,
)

@Serializable
data class CircuitDispatchResponse(
    val dispatchId: Long,
    val planId: Long,
    val planRevision: Int,
    val planHash: String,
    val scopeId: Int,
    val stageId: String,
    val nodeId: String,
    val workItemId: Int,
    val priority: Int,
    val integrationOwner: Boolean,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class CircuitDispatchViewResponse(
    val dispatch: CircuitDispatchResponse,
    val state: String,
    val workflowRunId: Long? = null,
)

@Serializable
data class StageExecutionWorkflowDefinitionResponse(
    val id: String,
    val version: Int,
    val entryPolicy: String,
    val exitPolicy: String,
    val description: String,
)

@Serializable
data class StagedPlanArtifactRequest(
    val kind: String,
    val name: String,
    val evidenceKind: String = "SOURCE_DIFF",
)

@Serializable
data class StagedPlanArtifactRequirementRequest(val producerNodeId: String, val kind: String)

@Serializable
data class StagedPlanNodeSubmissionRequest(
    val nodeId: String,
    val workItemId: Int,
    val dependsOn: List<String> = emptyList(),
    val consumes: List<StagedPlanArtifactRequirementRequest> = emptyList(),
    val produces: List<StagedPlanArtifactRequest> = emptyList(),
)

@Serializable
data class StagedPlanStageSubmissionRequest(
    val stageId: String,
    val title: String,
    val executionWorkflowId: String,
    val executionWorkflowVersion: Int = 1,
    val nodes: List<StagedPlanNodeSubmissionRequest>,
)

@Serializable
data class StagedDeliveryPlanSubmissionRequest(
    val scopeId: Int,
    val title: String,
    val stages: List<StagedPlanStageSubmissionRequest>,
    val baseRevision: Int = 0,
    val baseHash: String? = null,
    val sourceProposal: CircuitProposalReferenceRequest? = null,
)

@Serializable
data class CircuitProposalReferenceRequest(
    val proposalId: Long,
    val proposalHash: String,
)

@Serializable
data class CircuitExecutionProvenanceResponse(
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
data class CircuitProposalContentResponse(
    val plan: StagedDeliveryPlanSubmissionRequest,
    val observations: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
)

@Serializable
data class CircuitProposalResponse(
    val proposalId: Long,
    val scopeId: Int,
    val revision: Int,
    val actor: String,
    val content: CircuitProposalContentResponse,
    val provenance: CircuitExecutionProvenanceResponse,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class CircuitProposalViewResponse(
    val proposal: CircuitProposalResponse,
    val acceptedPlanId: Long? = null,
    val acceptedUnchanged: Boolean = false,
)

@Serializable
data class StagedPlanArtifactInstanceResponse(
    val producerNodeId: String,
    val workItemId: Int,
    val kind: String,
    val name: String,
    val workflowRunId: Long,
    val evidenceId: Long,
    val evidenceKind: String,
    val revision: String,
    val outputHash: String,
    val evidenceHash: String,
)

@Serializable
data class StagedPlanNodeResponse(
    val nodeId: String,
    val label: String,
    val workItemId: Int,
    val dependsOn: List<String>,
    val consumes: List<StagedPlanArtifactRequirementRequest>,
    val produces: List<StagedPlanArtifactRequest>,
)

@Serializable
data class StagedPlanStageResponse(
    val stageId: String,
    val ordinal: Int,
    val title: String,
    val executionWorkflowId: String,
    val executionWorkflowVersion: Int,
    val nodes: List<StagedPlanNodeResponse>,
)

@Serializable
data class StagedDeliveryPlanResponse(
    val planId: Long,
    val revision: Int,
    val scopeId: Int,
    val scopeType: Int,
    val title: String,
    val stages: List<StagedPlanStageResponse>,
    val acceptedBy: String,
    val acceptedAt: String,
    val hash: String,
    val sourceProposal: CircuitProposalReferenceRequest? = null,
    val acceptedProposalUnchanged: Boolean = false,
)

@Serializable
data class StagedPlanNodeViewResponse(
    val node: StagedPlanNodeResponse,
    val state: String,
    val blockedReason: String = "",
)

@Serializable
data class StagedDeliveryPlanViewResponse(
    val plan: StagedDeliveryPlanResponse,
    val nodes: List<StagedPlanNodeViewResponse>,
    val artifacts: List<StagedPlanArtifactInstanceResponse> = emptyList(),
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
data class ModelProviderCatalogResponse(
    val policy: String,
    val endpoints: List<ModelEndpointDefinitionResponse>,
    val bindings: List<CatalogModelBindingResponse>,
)

@Serializable
data class ModelEndpointDefinitionResponse(
    val endpointId: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val locality: String,
    val credentialReference: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class CatalogModelBindingResponse(
    val bindingId: String,
    val endpointId: String,
    val model: String,
    val contextWindowTokens: Int,
    val capabilities: Set<String> = setOf("STRICT_JSON"),
    val modelDigest: String? = null,
    val residentMemoryBytes: Long = 0,
    val cpuUnits: Int = 1,
    val configuration: Map<String, String> = emptyMap(),
)

@Serializable
data class ModelEndpointInspectionResponse(
    val endpointId: String,
    val reachable: Boolean,
    val discoveredModels: List<String> = emptyList(),
    val diagnostic: String = "",
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
    val judgments: List<CriterionJudgmentResponse> = emptyList(),
    val criterionGates: List<CriterionGateViewResponse> = emptyList(),
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
data class CriterionJudgmentSubmissionRequest(
    val criterionId: String,
    val revision: String,
    val approver: String,
    val rationale: String,
    val approved: Boolean,
)

@Serializable
data class CriterionJudgmentResponse(
    val judgmentId: Long,
    val criterionId: String,
    val requirementId: String,
    val revision: String,
    val contractHash: String,
    val approver: String,
    val rationale: String,
    val approved: Boolean,
    val recordedAt: String,
)

@Serializable
data class CriterionGateViewResponse(
    val criterionId: String,
    val requirementId: String,
    val kind: String,
    val gate: String,
    val description: String,
    val verification: String,
    val status: String,
    val revision: String? = null,
    val authorityEventId: Long? = null,
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
    val circuitDispatchId: Long? = null,
    val workspaceReservation: DispatchWorkspaceReservationResponse? = null,
)

@Serializable
data class DispatchWorkspaceReservationResponse(
    val mode: String,
    val owner: String,
    val path: String,
    val branch: String,
    val baseRevision: String,
)

@Serializable
data class RepositoryHeadResponse(
    val commitHash: String,
    val path: String = "",
    val branch: String = "",
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
    val criterionId: String? = null,
    val requirementId: String? = null,
    val gate: String? = null,
    val verification: String? = null,
)

private suspend inline fun <reified T> HttpResponse.successBody(): T {
    if (!status.isSuccess()) error("Orchard returned HTTP ${status.value}: ${bodyAsText().take(512)}")
    return body()
}