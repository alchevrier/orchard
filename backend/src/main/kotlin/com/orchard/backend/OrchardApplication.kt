package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.agent.ProposalGenerationStatus
import com.orchard.backend.agent.CircuitIntelligenceService
import com.orchard.backend.agent.CircuitGenerationStatus
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.agent.GenesisProposalStatus
import com.orchard.backend.agent.CodingWorkerTickStatus
import com.orchard.backend.agent.FileCodingWorkerStore
import com.orchard.backend.agent.FileToolchainPolicyCatalog
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.analysis.FileRepositoryExecutionPlanStore
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryAnalysisTickStatus
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.config.OrchardPaths
import com.orchard.backend.company.CompanyAuditService
import com.orchard.backend.company.CompanyCircuitService
import com.orchard.backend.company.CompanyCircuitStatus
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.company.CompanyProjectView
import com.orchard.backend.company.FileCompanyControlStore
import com.orchard.backend.vector.OllamaClient
import com.orchard.backend.vector.FileModelProfileSettingsStore
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileUpdateStatus
import com.orchard.backend.resource.FileMachineUsagePolicyStore
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.resource.MachineUsagePolicyUpdateStatus
import com.orchard.backend.resource.SystemMachineCapacityMonitor
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileDefinitionCollaborationStore
import com.orchard.backend.workspace.FileModelExperienceStore
import com.orchard.backend.workspace.FileStagedDeliveryPlanStore
import com.orchard.backend.workspace.FileCircuitProposalStore
import com.orchard.backend.workspace.FileCircuitDispatchStore
import com.orchard.backend.workspace.FileDesignGovernanceStore
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.DesignGovernanceStatus
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.AttemptSubmission
import com.orchard.backend.workspace.CriterionJudgmentSubmission
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.DefinitionCollaborationStatus
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanStatus
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.remaining

fun main() {
    OrchardPaths.initialize()
    val repositoryBindings = FileRepositoryBindingStore(OrchardPaths.WORKSPACE_DIR)
    val workspace = WorkspaceStore(
        FileWorkspaceRepository(OrchardPaths.WORKSPACE_DIR),
        repositoryBindings,
        FileWorkflowMemoryStore(OrchardPaths.WORKSPACE_DIR),
        FileWorkDefinitionStore(OrchardPaths.WORKSPACE_DIR),
        FileDefinitionCollaborationStore(OrchardPaths.WORKSPACE_DIR),
        FileModelExperienceStore(OrchardPaths.WORKSPACE_DIR),
        FileStagedDeliveryPlanStore(OrchardPaths.WORKSPACE_DIR),
        FileCircuitProposalStore(OrchardPaths.WORKSPACE_DIR),
        FileCircuitDispatchStore(OrchardPaths.WORKSPACE_DIR),
        FileDesignGovernanceStore(OrchardPaths.WORKSPACE_DIR),
        FileProjectGenesisStore(OrchardPaths.WORKSPACE_DIR),
        enforceProjectGenesis = true,
    )
    val modelProvider = OllamaClient()
    val resourceController = MachineResourceController(
        FileMachineUsagePolicyStore(OrchardPaths.WORKSPACE_DIR),
        SystemMachineCapacityMonitor(),
    )
    val architect = ArchitectService(workspace, modelProvider, resourceController)
    val definitionIntelligence = DefinitionIntelligenceService(
        workspace,
        listOf(modelProvider),
        FileModelProfileSettingsStore(OrchardPaths.WORKSPACE_DIR),
        resourceController,
    )
    val circuitIntelligence = CircuitIntelligenceService(
        workspace,
        listOf(modelProvider),
        FileModelProfileSettingsStore(OrchardPaths.WORKSPACE_DIR),
        resourceController,
    )
    val genesisIntelligence = GenesisIntelligenceService(workspace, modelProvider, resourceController)
    val companyControl = CompanyControlService(
        workspace,
        listOf(modelProvider),
        FileCompanyControlStore(OrchardPaths.WORKSPACE_DIR),
        repositoryBindings,
    )
    val companyCircuit = CompanyCircuitService(workspace, companyControl, OrchardPaths.LOCAL_REPOSITORIES_DIR)
    val codingWorkspaceGateway = LocalCodingWorkspaceGateway(FileToolchainPolicyCatalog(OrchardPaths.TOOLCHAIN_POLICY_PACKS_DIR))
    val repositoryAnalysis = RepositoryAnalysisService(
        workspace,
        listOf(modelProvider),
        FileRepositoryExecutionPlanStore(OrchardPaths.WORKSPACE_DIR),
        codingWorkspaceGateway,
        resourceController,
        companyControl = companyControl,
    )
    val codingWorker = CodingWorkerService(
        workspace,
        listOf(modelProvider),
        FileCodingWorkerStore(OrchardPaths.WORKSPACE_DIR),
        codingWorkspaceGateway,
        resourceController,
        companyControl = companyControl,
        repositoryAnalysis = repositoryAnalysis,
    )
    val companyAudit = CompanyAuditService(
        workspace,
        codingWorker,
        companyControl,
        LocalCodingWorkspaceGateway(FileToolchainPolicyCatalog(OrchardPaths.TOOLCHAIN_POLICY_PACKS_DIR)),
        resourceController,
    )
    val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dispatchScope.launch {
        while (isActive) {
            delay(DISPATCH_INTERVAL_MILLIS)
            runCatching { workspace.dispatchEligible() }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable circuit dispatch tick failed", error)
            }
        }
    }
    val codingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    analysisScope.launch {
        while (isActive) {
            delay(ANALYSIS_INTERVAL_MILLIS)
            runCatching { repositoryAnalysis.tick() }.onFailure { error ->
                ANALYSIS_LOGGER.log(Level.WARNING, "Repository analysis tick failed", error)
            }
        }
    }
    codingScope.launch {
        while (isActive) {
            delay(CODING_INTERVAL_MILLIS)
            runCatching { codingWorker.tick() }.onFailure { error ->
                CODING_LOGGER.log(Level.WARNING, "Governed coding worker tick failed", error)
            }
        }
    }
    val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    auditScope.launch {
        while (isActive) {
            delay(AUDIT_INTERVAL_MILLIS)
            runCatching { companyAudit.tick() }.onFailure { error ->
                AUDIT_LOGGER.log(Level.WARNING, "Independent company audit tick failed", error)
            }
        }
    }
    val workspaceServer = embeddedServer(Netty, host = "127.0.0.1", port = 8085) {
        workspaceApi(
            workspace,
            definitionIntelligence,
            circuitIntelligence,
            codingWorker,
            genesisIntelligence,
            companyControl,
            companyCircuit,
            repositoryAnalysis,
        )
    }
    val architectServer = embeddedServer(Netty, host = "127.0.0.1", port = 8086) {
        architectApi(architect)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        dispatchScope.cancel()
        analysisScope.cancel()
        codingScope.cancel()
        auditScope.cancel()
        workspaceServer.stop()
        architectServer.stop()
        modelProvider.close()
    })
    workspaceServer.start(wait = false)
    architectServer.start(wait = true)
}

fun Application.workspaceApi(
    workspace: WorkspaceStore,
    definitionIntelligence: DefinitionIntelligenceService? = null,
    circuitIntelligence: CircuitIntelligenceService? = null,
    codingWorker: CodingWorkerService? = null,
    genesisIntelligence: GenesisIntelligenceService? = null,
    companyControl: CompanyControlService? = null,
    companyCircuit: CompanyCircuitService? = null,
    repositoryAnalysis: RepositoryAnalysisService? = null,
) {
    configureJson()
    routing {
        get("/api/workspace") {
            call.respond(workspace.snapshot(MESSAGE_READY))
        }
        get("/api/company/state") {
            if (companyControl == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(workspaceResponse(workspace, companyControl, repositoryAnalysis))
        }
        get("/api/company") {
            if (companyControl == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(companyControl.projectViews())
        }
        get("/api/repository-analysis/plans") {
            if (repositoryAnalysis == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(repositoryAnalysis.plans())
        }
        post("/api/repository-analysis/tick") {
            if (repositoryAnalysis == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = runCatching { repositoryAnalysis.tick() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val status = when (result.status) {
                RepositoryAnalysisTickStatus.PLAN_CREATED -> HttpStatusCode.Created
                RepositoryAnalysisTickStatus.IDLE -> HttpStatusCode.OK
                RepositoryAnalysisTickStatus.BUSY,
                RepositoryAnalysisTickStatus.PLAN_STALE,
                RepositoryAnalysisTickStatus.ARCHITECT_DECISION_REQUIRED -> HttpStatusCode.Conflict
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS,
                RepositoryAnalysisTickStatus.CONTEXT_BUDGET_EXCEEDED -> HttpStatusCode.UnprocessableEntity
                RepositoryAnalysisTickStatus.RESOURCE_BLOCKED -> HttpStatusCode.TooManyRequests
                RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE,
                RepositoryAnalysisTickStatus.NO_COMPATIBLE_MODEL,
                RepositoryAnalysisTickStatus.MODEL_FAILED,
                RepositoryAnalysisTickStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
        post("/api/projects/{projectId}/company/start") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || companyCircuit == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = runCatching { companyCircuit.start(projectId) }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val status = when (result.status) {
                CompanyCircuitStatus.STARTED -> HttpStatusCode.Created
                CompanyCircuitStatus.ALREADY_STARTED -> HttpStatusCode.OK
                CompanyCircuitStatus.PROJECT_NOT_READY,
                CompanyCircuitStatus.REPOSITORY_REQUIRED -> HttpStatusCode.Conflict
                CompanyCircuitStatus.BLUEPRINT_UNSUPPORTED,
                CompanyCircuitStatus.AUTHORITY_REJECTED -> HttpStatusCode.UnprocessableEntity
                CompanyCircuitStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(
                status,
                CompanyWorkspaceResponse(
                    workspace = workspace.snapshot(MESSAGE_READY),
                    companyProjects = requireNotNull(companyControl).projectViews(),
                    executionPlans = repositoryAnalysis?.plans().orEmpty(),
                    companyDiagnostic = result.diagnostic,
                ),
            )
        }
        post("/api/company/runs/{runId}/promotion") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            if (runId == null || runId <= 0 || companyControl == null) {
                call.respond(if (runId == null || runId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = companyControl.promote(runId)
            val status = when (result.status) {
                CompanyMutationStatus.RECORDED -> HttpStatusCode.OK
                CompanyMutationStatus.RUN_NOT_FOUND -> HttpStatusCode.NotFound
                CompanyMutationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                else -> HttpStatusCode.Conflict
            }
            call.respond(status, workspaceResponse(workspace, companyControl))
        }
        post("/api/projects/{projectId}/genesis") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val request = runCatching { call.receive<ProjectGenesisSubmission>() }.getOrNull()
            if (projectId == null || projectId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.advanceProjectGenesis(projectId, request)
            call.respond(projectGenesisStatus(result.status), result.snapshot)
        }
        post("/api/projects/{projectId}/genesis/admission") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.admitProjectGenesis(projectId)
            call.respond(projectGenesisStatus(result.status), result.snapshot)
        }
        post("/api/projects/{projectId}/genesis/proposal") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val request = runCatching { call.receive<GenesisProposalRequest>() }.getOrNull()
            if (projectId == null || projectId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (genesisIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = genesisIntelligence.propose(projectId, request)
            val proposal = result.proposal
            if (proposal == null) call.respond(genesisProposalStatus(result.status))
            else call.respond(genesisProposalStatus(result.status), proposal)
        }
        post("/api/projects/{projectId}/design-governance") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.activateDesignGovernance(projectId)
            call.respond(designGovernanceStatus(result.status), result.snapshot)
        }
        post("/api/designs") {
            val request = runCatching { call.receive<DesignSubmission>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.recordDesignCandidate(request)
            call.respond(designGovernanceStatus(result.status), result.snapshot)
        }
        post("/api/designs/{designId}/admission") {
            val designId = call.parameters["designId"]?.toLongOrNull()
            if (designId == null || designId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.admitDesign(designId)
            call.respond(designGovernanceStatus(result.status), result.snapshot)
        }
        get("/api/model-profiles") {
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val configurations = runCatching { definitionIntelligence.profileConfigurations() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(configurations)
        }
        get("/api/machine-resources") {
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val configuration = runCatching { definitionIntelligence.machineResourceConfiguration() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(configuration)
        }
        put("/api/machine-resources/policy") {
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val request = runCatching { call.receive<MachineUsagePolicy>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            val result = definitionIntelligence.updateMachineUsagePolicy(request)
            val status = when (result.status) {
                MachineUsagePolicyUpdateStatus.UPDATED -> HttpStatusCode.OK
                MachineUsagePolicyUpdateStatus.INVALID_POLICY -> HttpStatusCode.UnprocessableEntity
                MachineUsagePolicyUpdateStatus.STORAGE_UNAVAILABLE,
                MachineUsagePolicyUpdateStatus.TELEMETRY_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            result.configuration?.let { call.respond(status, it) } ?: call.respond(status)
        }
        post("/api/staged-plans") {
            val body = call.receiveChannel().readRemaining(MAX_STAGED_PLAN_REQUEST_BYTES + 1L)
            if (body.remaining > MAX_STAGED_PLAN_REQUEST_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            val request = runCatching {
                Json.decodeFromString<StagedDeliveryPlanSubmission>(body.readText())
            }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.acceptStagedPlan(request)
            val status = when (result.status) {
                StagedPlanStatus.ACCEPTED -> HttpStatusCode.Created
                StagedPlanStatus.SCOPE_NOT_FOUND -> HttpStatusCode.NotFound
                StagedPlanStatus.PROPOSAL_NOT_FOUND -> HttpStatusCode.NotFound
                StagedPlanStatus.PLAN_LOCKED -> HttpStatusCode.Conflict
                StagedPlanStatus.STALE_PLAN -> HttpStatusCode.Conflict
                StagedPlanStatus.INVALID_SCOPE,
                StagedPlanStatus.INVALID_PLAN -> HttpStatusCode.UnprocessableEntity
                StagedPlanStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/staged-plan-proposals/{scopeId}/generate") {
            val scopeId = call.parameters["scopeId"]?.toIntOrNull()
            if (scopeId == null || scopeId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (circuitIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, workspace.snapshot(MESSAGE_READY))
                return@post
            }
            val result = circuitIntelligence.propose(scopeId)
            val status = when (result.status) {
                CircuitGenerationStatus.CREATED -> HttpStatusCode.Created
                CircuitGenerationStatus.SCOPE_NOT_FOUND -> HttpStatusCode.NotFound
                CircuitGenerationStatus.PLAN_LOCKED,
                CircuitGenerationStatus.BUSY,
                CircuitGenerationStatus.STALE_CONTEXT -> HttpStatusCode.Conflict
                CircuitGenerationStatus.INVALID_SCOPE,
                CircuitGenerationStatus.CONTEXT_BUDGET_EXCEEDED,
                CircuitGenerationStatus.INVALID_OUTPUT -> HttpStatusCode.UnprocessableEntity
                CircuitGenerationStatus.RESOURCE_CAPACITY_UNAVAILABLE -> HttpStatusCode.TooManyRequests
                CircuitGenerationStatus.NO_COMPATIBLE_MODEL,
                CircuitGenerationStatus.MODEL_UNAVAILABLE,
                CircuitGenerationStatus.STORAGE_UNAVAILABLE,
                CircuitGenerationStatus.RESOURCE_TELEMETRY_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/staged-plan-proposals/{proposalId}/accept") {
            val proposalId = call.parameters["proposalId"]?.toLongOrNull()
            if (proposalId == null || proposalId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.acceptCircuitProposal(proposalId)
            val status = when (result.status) {
                StagedPlanStatus.ACCEPTED -> HttpStatusCode.Created
                StagedPlanStatus.SCOPE_NOT_FOUND -> HttpStatusCode.NotFound
                StagedPlanStatus.PROPOSAL_NOT_FOUND -> HttpStatusCode.NotFound
                StagedPlanStatus.PLAN_LOCKED,
                StagedPlanStatus.STALE_PLAN -> HttpStatusCode.Conflict
                StagedPlanStatus.INVALID_SCOPE,
                StagedPlanStatus.INVALID_PLAN -> HttpStatusCode.UnprocessableEntity
                StagedPlanStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        put("/api/model-profiles/{profileId}") {
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val profileId = call.parameters["profileId"]
            val request = runCatching { call.receive<ModelProfileOverride>() }.getOrNull()
            if (profileId.isNullOrBlank() || request == null || request.profileId != profileId) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            val result = definitionIntelligence.updateProfile(request)
            val status = when (result.status) {
                ModelProfileUpdateStatus.UPDATED -> HttpStatusCode.OK
                ModelProfileUpdateStatus.PROFILE_NOT_FOUND -> HttpStatusCode.NotFound
                ModelProfileUpdateStatus.INVALID_BUDGET,
                ModelProfileUpdateStatus.NO_COMPATIBLE_BINDING -> HttpStatusCode.UnprocessableEntity
                ModelProfileUpdateStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.configurations)
        }
        put("/api/projects/{projectId}/repository") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val request = runCatching { call.receive<BindRepositoryRequest>() }.getOrNull()
            if (projectId == null || projectId <= 0 || request == null || request.path.isBlank()) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            val result = workspace.bindRepository(projectId, request.path)
            val status = when (result.status) {
                RepositoryBindStatus.BOUND -> HttpStatusCode.OK
                RepositoryBindStatus.PROJECT_NOT_FOUND -> HttpStatusCode.NotFound
                RepositoryBindStatus.INVALID_REPOSITORY -> HttpStatusCode.UnprocessableEntity
                RepositoryBindStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/work-items/{workItemId}/runs") {
            val workItemId = call.parameters["workItemId"]?.toIntOrNull()
            if (workItemId == null || workItemId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.startWorkflow(workItemId)
            val status = when (result.status) {
                WorkflowStartStatus.CREATED -> HttpStatusCode.Created
                WorkflowStartStatus.WORK_ITEM_NOT_FOUND -> HttpStatusCode.NotFound
                WorkflowStartStatus.UNSUPPORTED_ENTITY,
                WorkflowStartStatus.REPOSITORY_UNAVAILABLE,
                WorkflowStartStatus.REPOSITORY_DIRTY,
                WorkflowStartStatus.WORK_DEFINITION_NOT_READY -> HttpStatusCode.UnprocessableEntity
                WorkflowStartStatus.STAGED_PLAN_BLOCKED,
                WorkflowStartStatus.DESIGN_NOT_ADMITTED,
                WorkflowStartStatus.PROJECT_GENESIS_NOT_ADMITTED -> HttpStatusCode.Conflict
                WorkflowStartStatus.ALREADY_STARTED -> HttpStatusCode.Conflict
                WorkflowStartStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/work-items/{workItemId}/definitions") {
            val workItemId = call.parameters["workItemId"]?.toIntOrNull()
            val request = runCatching { call.receive<WorkDefinitionSubmission>() }.getOrNull()
            if (workItemId == null || workItemId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.submitWorkDefinition(workItemId, request)
            val status = when (result.status) {
                WorkDefinitionStatus.RECORDED -> HttpStatusCode.Created
                WorkDefinitionStatus.WORK_ITEM_NOT_FOUND -> HttpStatusCode.NotFound
                WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED -> HttpStatusCode.Conflict
                WorkDefinitionStatus.UNSUPPORTED_ENTITY,
                WorkDefinitionStatus.INVALID_DEFINITION -> HttpStatusCode.UnprocessableEntity
                WorkDefinitionStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/work-items/{workItemId}/definition-proposals") {
            val workItemId = call.parameters["workItemId"]?.toIntOrNull()
            if (workItemId == null || workItemId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, workspace.snapshot(MESSAGE_READY))
                return@post
            }
            val result = definitionIntelligence.propose(workItemId)
            val status = when (result.status) {
                ProposalGenerationStatus.CREATED -> HttpStatusCode.Created
                ProposalGenerationStatus.WORK_ITEM_NOT_FOUND -> HttpStatusCode.NotFound
                ProposalGenerationStatus.WORKFLOW_ALREADY_STARTED,
                ProposalGenerationStatus.BUSY -> HttpStatusCode.Conflict
                ProposalGenerationStatus.RESOURCE_CAPACITY_UNAVAILABLE -> HttpStatusCode.TooManyRequests
                ProposalGenerationStatus.RESOURCE_TELEMETRY_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                ProposalGenerationStatus.INVALID_OUTPUT -> HttpStatusCode.UnprocessableEntity
                ProposalGenerationStatus.CONTEXT_BUDGET_EXCEEDED -> HttpStatusCode.UnprocessableEntity
                ProposalGenerationStatus.NO_COMPATIBLE_MODEL -> HttpStatusCode.ServiceUnavailable
                ProposalGenerationStatus.MODEL_UNAVAILABLE,
                ProposalGenerationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/definition-proposals/{proposalId}/feedback") {
            val proposalId = call.parameters["proposalId"]?.toLongOrNull()
            val request = runCatching { call.receive<DefinitionFeedbackRequest>() }.getOrNull()
            if (proposalId == null || proposalId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.recordDefinitionFeedback(proposalId, request.content)
            call.respond(collaborationStatus(result.status), result.snapshot)
        }
        post("/api/definition-proposals/{proposalId}/accept") {
            val proposalId = call.parameters["proposalId"]?.toLongOrNull()
            val request = runCatching { call.receive<AcceptDefinitionProposalRequest>() }.getOrNull()
            if (proposalId == null || proposalId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.acceptDefinitionProposal(proposalId, request.definition)
            val status = when (result.status) {
                WorkDefinitionStatus.RECORDED -> HttpStatusCode.Created
                WorkDefinitionStatus.WORK_ITEM_NOT_FOUND -> HttpStatusCode.NotFound
                WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED -> HttpStatusCode.Conflict
                WorkDefinitionStatus.UNSUPPORTED_ENTITY,
                WorkDefinitionStatus.INVALID_DEFINITION -> HttpStatusCode.UnprocessableEntity
                WorkDefinitionStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result.snapshot)
        }
        post("/api/workflow-runs/{runId}/evidence") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            val request = runCatching { call.receive<EvidenceSubmission>() }.getOrNull()
            if (runId == null || runId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.submitEvidence(runId, request)
            call.respond(workflowMutationStatus(result.status, HttpStatusCode.Created), result.snapshot)
        }
        post("/api/workflow-runs/{runId}/attempts") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            val request = runCatching { call.receive<AttemptSubmission>() }.getOrNull()
            if (runId == null || runId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.recordAttempt(runId, request)
            call.respond(workflowMutationStatus(result.status, HttpStatusCode.Created), result.snapshot)
        }
        post("/api/workflow-runs/{runId}/criterion-judgments") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            val request = runCatching { call.receive<CriterionJudgmentSubmission>() }.getOrNull()
            if (runId == null || runId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.recordCriterionJudgment(runId, request)
            call.respond(workflowMutationStatus(result.status, HttpStatusCode.Created), result.snapshot)
        }
        post("/api/workflow-runs/{runId}/cancel") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            if (runId == null || runId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.cancelWorkflow(runId)
            call.respond(workflowMutationStatus(result.status, HttpStatusCode.OK), result.snapshot)
        }
        get("/api/coding-worker/executions") {
            if (codingWorker == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val executions = runCatching { codingWorker.executions() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(executions)
        }
        post("/api/coding-worker/tick") {
            if (codingWorker == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = runCatching { codingWorker.tick() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val status = when (result.status) {
                CodingWorkerTickStatus.CANDIDATE_COMPLETED -> HttpStatusCode.Created
                CodingWorkerTickStatus.IDLE,
                CodingWorkerTickStatus.INTERRUPTED_RECOVERED -> HttpStatusCode.OK
                CodingWorkerTickStatus.BUSY -> HttpStatusCode.Conflict
                CodingWorkerTickStatus.RESOURCE_BLOCKED -> HttpStatusCode.TooManyRequests
                CodingWorkerTickStatus.INVALID_PROPOSAL,
                CodingWorkerTickStatus.PLAN_BLOCKED -> HttpStatusCode.UnprocessableEntity
                CodingWorkerTickStatus.ANALYSIS_REQUIRED,
                CodingWorkerTickStatus.PLAN_STALE -> HttpStatusCode.Conflict
                CodingWorkerTickStatus.MODEL_FAILED,
                CodingWorkerTickStatus.APPLICATION_FAILED,
                CodingWorkerTickStatus.VERIFICATION_FAILED,
                CodingWorkerTickStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
    }
}

private const val MAX_STAGED_PLAN_REQUEST_BYTES = 64 * 1024
private const val DISPATCH_INTERVAL_MILLIS = 1_000L
private const val ANALYSIS_INTERVAL_MILLIS = 1_000L
private const val CODING_INTERVAL_MILLIS = 1_000L
private const val AUDIT_INTERVAL_MILLIS = 1_000L
private val DISPATCH_LOGGER: Logger = Logger.getLogger("com.orchard.backend.dispatch")
private val ANALYSIS_LOGGER: Logger = Logger.getLogger("com.orchard.backend.analysis")
private val CODING_LOGGER: Logger = Logger.getLogger("com.orchard.backend.coding")
private val AUDIT_LOGGER: Logger = Logger.getLogger("com.orchard.backend.audit")

@Serializable
data class CompanyWorkspaceResponse(
    val workspace: com.orchard.backend.workspace.WorkspaceSnapshot,
    val companyProjects: List<CompanyProjectView> = emptyList(),
    val executionPlans: List<RepositoryExecutionPlan> = emptyList(),
    val companyDiagnostic: String = "",
)

private fun workspaceResponse(
    workspace: WorkspaceStore,
    companyControl: CompanyControlService?,
    repositoryAnalysis: RepositoryAnalysisService? = null,
): CompanyWorkspaceResponse = CompanyWorkspaceResponse(
    workspace = workspace.snapshot(MESSAGE_READY),
    companyProjects = companyControl?.projectViews().orEmpty(),
    executionPlans = repositoryAnalysis?.plans().orEmpty(),
)

private fun workflowMutationStatus(status: WorkflowMutationStatus, success: HttpStatusCode): HttpStatusCode = when (status) {
    WorkflowMutationStatus.RECORDED -> success
    WorkflowMutationStatus.RUN_NOT_FOUND -> HttpStatusCode.NotFound
    WorkflowMutationStatus.RUN_CLOSED -> HttpStatusCode.Conflict
    WorkflowMutationStatus.INVALID_RECORD,
    WorkflowMutationStatus.REVISION_INVALID -> HttpStatusCode.UnprocessableEntity
    WorkflowMutationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

private fun collaborationStatus(status: DefinitionCollaborationStatus): HttpStatusCode = when (status) {
    DefinitionCollaborationStatus.RECORDED -> HttpStatusCode.Created
    DefinitionCollaborationStatus.WORK_ITEM_NOT_FOUND,
    DefinitionCollaborationStatus.PROPOSAL_NOT_FOUND -> HttpStatusCode.NotFound
    DefinitionCollaborationStatus.WORKFLOW_ALREADY_STARTED -> HttpStatusCode.Conflict
    DefinitionCollaborationStatus.INVALID_RECORD -> HttpStatusCode.UnprocessableEntity
    DefinitionCollaborationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

private fun designGovernanceStatus(status: DesignGovernanceStatus): HttpStatusCode = when (status) {
    DesignGovernanceStatus.RECORDED -> HttpStatusCode.Created
    DesignGovernanceStatus.ADMITTED -> HttpStatusCode.OK
    DesignGovernanceStatus.REJECTED,
    DesignGovernanceStatus.INVALID_SCOPE,
    DesignGovernanceStatus.INVALID_DESIGN -> HttpStatusCode.UnprocessableEntity
    DesignGovernanceStatus.WORK_ITEM_NOT_FOUND,
    DesignGovernanceStatus.DESIGN_NOT_FOUND -> HttpStatusCode.NotFound
    DesignGovernanceStatus.STALE_DESIGN,
    DesignGovernanceStatus.ALREADY_DECIDED,
    DesignGovernanceStatus.GOVERNANCE_ALREADY_ACTIVE,
    DesignGovernanceStatus.WORKFLOW_ALREADY_STARTED -> HttpStatusCode.Conflict
    DesignGovernanceStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

private fun projectGenesisStatus(status: ProjectGenesisStatus): HttpStatusCode = when (status) {
    ProjectGenesisStatus.RECORDED -> HttpStatusCode.Created
    ProjectGenesisStatus.ADMITTED -> HttpStatusCode.OK
    ProjectGenesisStatus.PROJECT_NOT_FOUND -> HttpStatusCode.NotFound
    ProjectGenesisStatus.STALE_REVISION,
    ProjectGenesisStatus.ORGANIZATION_POLICY_REQUIRED -> HttpStatusCode.Conflict
    ProjectGenesisStatus.INVALID_TRANSITION -> HttpStatusCode.UnprocessableEntity
    ProjectGenesisStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

private fun genesisProposalStatus(status: GenesisProposalStatus): HttpStatusCode = when (status) {
    GenesisProposalStatus.CREATED -> HttpStatusCode.OK
    GenesisProposalStatus.PROJECT_NOT_FOUND -> HttpStatusCode.NotFound
    GenesisProposalStatus.PHASE_NOT_PROPOSABLE,
    GenesisProposalStatus.INVALID_OUTPUT,
    GenesisProposalStatus.CONTEXT_BUDGET_EXCEEDED -> HttpStatusCode.UnprocessableEntity
    GenesisProposalStatus.BUSY -> HttpStatusCode.Conflict
    GenesisProposalStatus.MODEL_UNAVAILABLE,
    GenesisProposalStatus.RESOURCE_CAPACITY_UNAVAILABLE,
    GenesisProposalStatus.RESOURCE_TELEMETRY_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

@Serializable
data class BindRepositoryRequest(val path: String)

@Serializable
data class DefinitionFeedbackRequest(val content: String)

@Serializable
data class AcceptDefinitionProposalRequest(val definition: WorkDefinitionSubmission? = null)

fun Application.architectApi(architect: ArchitectService) {
    configureJson()
    routing {
        post("/api/architect/chat") {
            val request = runCatching { call.receive<ArchitectChatRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = architect.submit(request)
            call.respond(HttpStatusCode.fromValue(result.statusCode), result.snapshot)
        }
    }
}

private fun Application.configureJson() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}