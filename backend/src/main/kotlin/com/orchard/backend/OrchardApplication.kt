package com.orchard.backend

import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.agent.ProposalGenerationStatus
import com.orchard.backend.agent.CircuitIntelligenceService
import com.orchard.backend.agent.CircuitGenerationStatus
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.CodingGenesisRepositoryContextProvider
import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.agent.GenesisProposalStatus
import com.orchard.backend.agent.CodingWorkerTickStatus
import com.orchard.backend.agent.FileCodingWorkerStore
import com.orchard.backend.agent.FileToolchainPolicyCatalog
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.analysis.FileRepositoryExecutionPlanStore
import com.orchard.backend.analysis.FileRepositoryBaselineAnalysisStore
import com.orchard.backend.analysis.FileRepositoryIntelligenceGraphStore
import com.orchard.backend.analysis.FileRepositoryObjectiveAssessmentStore
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryAnalysisTickStatus
import com.orchard.backend.analysis.RepositoryBaselineAnalysisService
import com.orchard.backend.analysis.RepositoryIntelligenceImporter
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.config.OrchardPaths
import com.orchard.backend.company.CompanyAuditService
import com.orchard.backend.company.CompanyCircuitService
import com.orchard.backend.company.CompanyCircuitStatus
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.company.CompanyProjectView
import com.orchard.backend.company.FileCompanyControlStore
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.FileConversationStore
import com.orchard.backend.conversation.ModelConversationInterpreter
import com.orchard.backend.conversation.conversationRoutes
import com.orchard.backend.conversation.defaultConversationCapabilities
import com.orchard.backend.report.FileProjectReportStore
import com.orchard.backend.report.ProjectReportService
import com.orchard.backend.report.RepositoryBaselineCompiler
import com.orchard.backend.report.projectReportRoutes
import com.orchard.backend.vector.FileModelProviderCatalogStore
import com.orchard.backend.vector.FileModelProfileSettingsStore
import com.orchard.backend.vector.ModelProviderCatalog
import com.orchard.backend.vector.ModelProviderRegistry
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileUpdateStatus
import com.orchard.backend.vector.LocalModelSetupRecommendations
import com.orchard.backend.vector.ModelSetupApplication
import com.orchard.backend.vector.bootstrapDetectedLocalModelSetup
import com.orchard.backend.resource.FileMachineUsagePolicyStore
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.resource.MachineUsagePolicyUpdateStatus
import com.orchard.backend.resource.SystemMachineCapacityMonitor
import com.orchard.backend.standards.BacklogAdmissionStatus
import com.orchard.backend.standards.CampaignResolutionAdmissionStatus
import com.orchard.backend.standards.CampaignResolutionProposalStatus
import com.orchard.backend.standards.CampaignResolutionService
import com.orchard.backend.standards.ConformanceScanStatus
import com.orchard.backend.standards.ConformanceScanSubmission
import com.orchard.backend.standards.EngineeringStandardSubmission
import com.orchard.backend.standards.EngineeringStandardsService
import com.orchard.backend.standards.FileEngineeringStandardsStore
import com.orchard.backend.standards.FileStandardsPolicyStore
import com.orchard.backend.standards.FileCampaignResolutionStore
import com.orchard.backend.standards.FileRemediationCampaignStore
import com.orchard.backend.standards.RemediationCampaignService
import com.orchard.backend.standards.StandardOverlaySubmission
import com.orchard.backend.standards.StandardPolicyScope
import com.orchard.backend.standards.StandardUpdateStatus
import com.orchard.backend.standards.StandardsExceptionAdmissionSubmission
import com.orchard.backend.standards.StandardsExceptionProposalSubmission
import com.orchard.backend.standards.StandardsExceptionRevocationSubmission
import com.orchard.backend.standards.StandardsPolicyMutationStatus
import com.orchard.backend.standards.StandardsPolicyService
import com.orchard.backend.standards.STANDARD_SCOPE_MODULE
import com.orchard.backend.standards.STANDARD_SCOPE_PROJECT
import com.orchard.backend.standards.STANDARD_SCOPE_WORK_ITEM
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
import com.orchard.backend.workspace.ProjectGenesisFirstOutcomeStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.RepositoryOnboardingService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
    val machineCapacityMonitor = SystemMachineCapacityMonitor()
    val modelProviderCatalogStore = FileModelProviderCatalogStore(OrchardPaths.WORKSPACE_DIR)
    val modelProfileSettingsStore = FileModelProfileSettingsStore(OrchardPaths.WORKSPACE_DIR)
    val machineCapacity = machineCapacityMonitor.snapshot()
    bootstrapDetectedLocalModelSetup(
        modelProviderCatalogStore,
        modelProfileSettingsStore,
        LocalModelSetupRecommendations.detectPlatform(),
        machineCapacity.totalMemoryBytes,
    )
    val modelProviderRegistry = ModelProviderRegistry(modelProviderCatalogStore)
    val modelProviders = modelProviderRegistry.providers()
    val resourceController = MachineResourceController(
        FileMachineUsagePolicyStore(OrchardPaths.WORKSPACE_DIR),
        machineCapacityMonitor,
    )
    val definitionIntelligence = DefinitionIntelligenceService(
        workspace,
        modelProviders,
        modelProfileSettingsStore,
        resourceController,
    )
    val circuitIntelligence = CircuitIntelligenceService(
        workspace,
        modelProviders,
        modelProfileSettingsStore,
        resourceController,
    )
    val codingWorkspaceGateway = LocalCodingWorkspaceGateway(FileToolchainPolicyCatalog(OrchardPaths.TOOLCHAIN_POLICY_PACKS_DIR))
    val repositoryObjectiveAssessmentStore = FileRepositoryObjectiveAssessmentStore(OrchardPaths.WORKSPACE_DIR)
    val repositoryIntelligenceImporter = RepositoryIntelligenceImporter(
        workspace,
        FileRepositoryIntelligenceGraphStore(OrchardPaths.WORKSPACE_DIR),
    )
    val genesisIntelligence = GenesisIntelligenceService(
        workspace,
        modelProviderRegistry,
        resourceController,
        CodingGenesisRepositoryContextProvider(codingWorkspaceGateway),
        repositoryObjectiveAssessmentStore,
    )
    val repositoryBaselineAnalysis = RepositoryBaselineAnalysisService(
        workspace,
        modelProviders,
        FileRepositoryBaselineAnalysisStore(OrchardPaths.WORKSPACE_DIR),
        codingWorkspaceGateway,
        resourceController,
        repositoryIntelligenceImporter,
    )
    val companyControl = CompanyControlService(
        workspace,
        modelProviders,
        FileCompanyControlStore(OrchardPaths.WORKSPACE_DIR),
        repositoryBindings,
    )
    val companyCircuit = CompanyCircuitService(workspace, companyControl, OrchardPaths.LOCAL_REPOSITORIES_DIR)
    val repositoryAnalysis = RepositoryAnalysisService(
        workspace,
        modelProviders,
        FileRepositoryExecutionPlanStore(OrchardPaths.WORKSPACE_DIR),
        codingWorkspaceGateway,
        resourceController,
        companyControl = companyControl,
    )
    val engineeringStandardsStore = FileEngineeringStandardsStore(OrchardPaths.WORKSPACE_DIR)
    val standardsPolicy = StandardsPolicyService(
        engineeringStandardsStore,
        repositoryBindings,
        FileStandardsPolicyStore(OrchardPaths.WORKSPACE_DIR),
    )
    val engineeringStandards = EngineeringStandardsService(
        workspace,
        repositoryBindings,
        modelProviders,
        engineeringStandardsStore,
        codingWorkspaceGateway,
        resourceController,
        standardsPolicy,
    )
    val remediationCampaignStore = FileRemediationCampaignStore(OrchardPaths.WORKSPACE_DIR)
    val remediationCampaigns = RemediationCampaignService(
        workspace,
        repositoryBindings,
        engineeringStandardsStore,
        engineeringStandards,
        companyControl,
        remediationCampaignStore,
    )
    val campaignResolutionStore = FileCampaignResolutionStore(OrchardPaths.WORKSPACE_DIR)
    val campaignResolutions = CampaignResolutionService(
        workspace,
        repositoryBindings,
        engineeringStandardsStore,
        remediationCampaignStore,
        modelProviders,
        campaignResolutionStore,
        resourceController,
        standardsPolicy,
    )
    val codingWorker = CodingWorkerService(
        workspace,
        modelProviders,
        FileCodingWorkerStore(OrchardPaths.WORKSPACE_DIR),
        codingWorkspaceGateway,
        resourceController,
        companyControl = companyControl,
        repositoryAnalysis = repositoryAnalysis,
        profileSettingsStore = modelProfileSettingsStore,
    )
    val companyAudit = CompanyAuditService(
        workspace,
        codingWorker,
        companyControl,
        LocalCodingWorkspaceGateway(FileToolchainPolicyCatalog(OrchardPaths.TOOLCHAIN_POLICY_PACKS_DIR)),
        resourceController,
    )
    val repositoryOnboarding = RepositoryOnboardingService(workspace, OrchardPaths.LOCAL_REPOSITORIES_DIR)
    val conversationConductor = ConversationConductorService(
        FileConversationStore(OrchardPaths.WORKSPACE_DIR),
        ModelConversationInterpreter(modelProviders, resourceController),
        defaultConversationCapabilities(
            workspace,
            definitionIntelligence,
            circuitIntelligence,
            companyControl,
            companyCircuit,
            repositoryOnboarding,
            modelProviderRegistry,
        ),
    )
    val projectReports = ProjectReportService(
        workspace,
        repositoryBindings,
        FileProjectReportStore(OrchardPaths.WORKSPACE_DIR),
        genesisIntelligence::latestRepositoryAssessment,
        conversationConductor,
        latestBaselineAnalysis = repositoryBaselineAnalysis::latest,
    )
    val baselineCompiler = RepositoryBaselineCompiler(projectReports, repositoryBaselineAnalysis)
    val baselineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    baselineScope.launch {
        while (isActive) {
            delay(BASELINE_INTERVAL_MILLIS)
            runCatching { baselineCompiler.tick() }.onFailure { error ->
                BASELINE_LOGGER.log(Level.WARNING, "Repository baseline compilation failed", error)
            }
        }
    }
    val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    dispatchScope.launch {
        while (isActive) {
            delay(DISPATCH_INTERVAL_MILLIS)
            runCatching { workspace.dispatchEligible() }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable circuit dispatch tick failed", error)
            }
            runCatching { conversationConductor.reconcilePending() }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable conversation command reconciliation failed", error)
            }
            runCatching { conversationConductor.projectWorkspaceActivity(workspace) }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable conversation activity projection failed", error)
            }
            runCatching { conversationConductor.projectCompanyActivity(companyControl.projectViews()) }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable company activity projection failed", error)
            }
            runCatching { projectReports.synchronizeTicketReports() }.onFailure { error ->
                DISPATCH_LOGGER.log(Level.WARNING, "Durable ticket report projection failed", error)
            }
        }
    }
    val codingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    analysisScope.launch {
        while (isActive) {
            delay(ANALYSIS_INTERVAL_MILLIS)
            runCatching {
                coroutineScope {
                    conversationConductor.dispatchableRunIds(repositoryAnalysis.eligibleRunIds())
                        .map { runId -> async { repositoryAnalysis.tick(runId) } }.awaitAll()
                }
            }.onFailure { error ->
                ANALYSIS_LOGGER.log(Level.WARNING, "Repository analysis tick failed", error)
            }
        }
    }
    codingScope.launch {
        while (isActive) {
            delay(CODING_INTERVAL_MILLIS)
            runCatching {
                coroutineScope {
                    conversationConductor.dispatchableRunIds(codingWorker.eligibleRunIds())
                        .map { runId -> async { codingWorker.tick(runId) } }.awaitAll()
                }
            }.onFailure { error ->
                CODING_LOGGER.log(Level.WARNING, "Governed coding worker tick failed", error)
            }
        }
    }
    val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    auditScope.launch {
        while (isActive) {
            delay(AUDIT_INTERVAL_MILLIS)
            runCatching {
                coroutineScope {
                    conversationConductor.dispatchableRunIds(companyAudit.eligibleRunIds())
                        .map { runId -> async { companyAudit.tick(runId) } }.awaitAll()
                }
            }.onFailure { error ->
                AUDIT_LOGGER.log(Level.WARNING, "Independent company audit tick failed", error)
            }
        }
    }
    val campaignScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    campaignScope.launch {
        while (isActive) {
            delay(CAMPAIGN_INTERVAL_MILLIS)
            runCatching { remediationCampaigns.tick() }.onFailure { error ->
                CAMPAIGN_LOGGER.log(Level.WARNING, "Remediation campaign tick failed", error)
            }
            runCatching {
                campaignResolutions.reconcileCases()
                campaignResolutions.reconcileSuccessors()
                campaignResolutions.reconcileExceptionRequests()
            }.onFailure { error ->
                CAMPAIGN_LOGGER.log(Level.WARNING, "Campaign resolution reconciliation failed", error)
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
            modelProviderRegistry,
            engineeringStandards,
            remediationCampaigns,
            campaignResolutions,
            standardsPolicy,
            conversationConductor,
            projectReports,
            repositoryIntelligenceImporter,
        )
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        baselineScope.cancel()
        dispatchScope.cancel()
        analysisScope.cancel()
        codingScope.cancel()
        auditScope.cancel()
        campaignScope.cancel()
        workspaceServer.stop()
        modelProviderRegistry.close()
    })
    workspaceServer.start(wait = true)
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
    modelProviderRegistry: ModelProviderRegistry? = null,
    engineeringStandards: EngineeringStandardsService? = null,
    remediationCampaigns: RemediationCampaignService? = null,
    campaignResolutions: CampaignResolutionService? = null,
    standardsPolicy: StandardsPolicyService? = null,
    conversationConductor: ConversationConductorService? = null,
    projectReports: ProjectReportService? = null,
    repositoryIntelligenceImporter: RepositoryIntelligenceImporter? = null,
) {
    configureJson()
    routing {
        if (conversationConductor != null) conversationRoutes(conversationConductor)
        if (projectReports != null) projectReportRoutes(projectReports)
        get("/api/workspace") {
            call.respond(workspace.snapshot(MESSAGE_READY))
        }
        get("/api/projects/{projectId}/genesis/repository-assessment") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (genesisIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val assessment = genesisIntelligence.latestRepositoryAssessment(projectId)
            if (assessment == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(assessment)
        }
        get("/api/projects/{projectId}/repository-intelligence") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            if (repositoryIntelligenceImporter == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val graph = runCatching { repositoryIntelligenceImporter.current(projectId) }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            if (graph == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(graph)
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
        get("/api/projects/{projectId}/engineering-standards") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || engineeringStandards == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(engineeringStandards.view(projectId))
        }
        get("/api/projects/{projectId}/standards-policy") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || standardsPolicy == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val modulePath = call.request.queryParameters["modulePath"]
            val workItemId = call.request.queryParameters["workItemId"]?.toIntOrNull()
            val scope = when {
                workItemId != null -> StandardPolicyScope(STANDARD_SCOPE_WORK_ITEM, projectId, modulePath, workItemId)
                modulePath != null -> StandardPolicyScope(STANDARD_SCOPE_MODULE, projectId, modulePath)
                else -> StandardPolicyScope(STANDARD_SCOPE_PROJECT, projectId)
            }
            call.respond(standardsPolicy.view(projectId, scope))
        }
        put("/api/projects/{projectId}/engineering-standards") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val submission = runCatching { call.receive<EngineeringStandardSubmission>() }.getOrNull()
            if (projectId == null || projectId <= 0 || submission == null || engineeringStandards == null) {
                val invalid = projectId == null || projectId <= 0 || submission == null
                call.respond(if (invalid) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val result = engineeringStandards.updateStandard(projectId, submission)
            val status = when (result.status) {
                StandardUpdateStatus.UPDATED -> HttpStatusCode.Created
                StandardUpdateStatus.PROJECT_NOT_FOUND -> HttpStatusCode.NotFound
                StandardUpdateStatus.INVALID_STANDARD -> HttpStatusCode.UnprocessableEntity
                StandardUpdateStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
        post("/api/projects/{projectId}/standards-overlays") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val submission = runCatching { call.receive<StandardOverlaySubmission>() }.getOrNull()
            if (projectId == null || projectId <= 0 || submission == null || standardsPolicy == null) {
                call.respond(if (projectId == null || projectId <= 0 || submission == null) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = standardsPolicy.appendOverlay(projectId, submission)
            call.respond(standardsPolicyStatus(result.status), result)
        }
        post("/api/projects/{projectId}/standards-exception-proposals") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val submission = runCatching { call.receive<StandardsExceptionProposalSubmission>() }.getOrNull()
            if (projectId == null || projectId <= 0 || submission == null || standardsPolicy == null) {
                call.respond(if (projectId == null || projectId <= 0 || submission == null) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = standardsPolicy.proposeException(projectId, submission)
            call.respond(standardsPolicyStatus(result.status), result)
        }
        post("/api/standards-exception-proposals/{proposalId}/admission") {
            val proposalId = call.parameters["proposalId"]?.toLongOrNull()
            val submission = runCatching { call.receive<StandardsExceptionAdmissionSubmission>() }.getOrNull()
            if (proposalId == null || proposalId <= 0 || submission == null || standardsPolicy == null) {
                call.respond(if (proposalId == null || proposalId <= 0 || submission == null) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = standardsPolicy.admitException(proposalId, submission)
            call.respond(standardsPolicyStatus(result.status), result)
        }
        post("/api/standards-exception-admissions/{admissionId}/revocation") {
            val admissionId = call.parameters["admissionId"]?.toLongOrNull()
            val submission = runCatching { call.receive<StandardsExceptionRevocationSubmission>() }.getOrNull()
            if (admissionId == null || admissionId <= 0 || submission == null || standardsPolicy == null) {
                call.respond(if (admissionId == null || admissionId <= 0 || submission == null) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = standardsPolicy.revokeException(admissionId, submission)
            call.respond(standardsPolicyStatus(result.status), result)
        }
        post("/api/projects/{projectId}/conformance-scans") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || engineeringStandards == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = engineeringStandards.scan(
                projectId,
                ConformanceScanSubmission(
                    modulePath = call.request.queryParameters["modulePath"],
                    workItemId = call.request.queryParameters["workItemId"]?.toIntOrNull(),
                ),
            )
            val status = when (result.status) {
                ConformanceScanStatus.CREATED -> HttpStatusCode.Created
                ConformanceScanStatus.PROJECT_NOT_FOUND,
                ConformanceScanStatus.STANDARD_NOT_FOUND,
                ConformanceScanStatus.REPOSITORY_UNAVAILABLE -> HttpStatusCode.NotFound
                ConformanceScanStatus.BUSY,
                ConformanceScanStatus.REPOSITORY_DIRTY,
                ConformanceScanStatus.ALREADY_SCANNED,
                ConformanceScanStatus.REPOSITORY_DRIFTED -> HttpStatusCode.Conflict
                ConformanceScanStatus.CONTEXT_BUDGET_EXCEEDED,
                ConformanceScanStatus.INVALID_OUTPUT,
                ConformanceScanStatus.POLICY_CONFLICT -> HttpStatusCode.UnprocessableEntity
                ConformanceScanStatus.RESOURCE_BLOCKED -> HttpStatusCode.TooManyRequests
                ConformanceScanStatus.CONTEXT_UNAVAILABLE,
                ConformanceScanStatus.NO_COMPATIBLE_MODEL,
                ConformanceScanStatus.MODEL_FAILED,
                ConformanceScanStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
        post("/api/conformance-scans/{scanId}/admission") {
            val scanId = call.parameters["scanId"]?.toLongOrNull()
            if (scanId == null || scanId <= 0 || engineeringStandards == null) {
                call.respond(if (scanId == null || scanId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = runCatching { engineeringStandards.admitBacklog(scanId) }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val status = when (result.status) {
                BacklogAdmissionStatus.ADMITTED -> HttpStatusCode.Created
                BacklogAdmissionStatus.SCAN_NOT_FOUND -> HttpStatusCode.NotFound
                BacklogAdmissionStatus.ALREADY_ADMITTED,
                BacklogAdmissionStatus.REPOSITORY_DRIFTED -> HttpStatusCode.Conflict
                BacklogAdmissionStatus.EMPTY_BACKLOG,
                BacklogAdmissionStatus.CAPACITY_EXCEEDED,
                BacklogAdmissionStatus.INVALID_BACKLOG -> HttpStatusCode.UnprocessableEntity
                BacklogAdmissionStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            if (result.status == BacklogAdmissionStatus.ADMITTED) {
                runCatching { remediationCampaigns?.reconcileAdmissions() }
            }
            call.respond(status, result)
        }
        get("/api/projects/{projectId}/remediation-campaigns") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || remediationCampaigns == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(remediationCampaigns.views(projectId))
        }
        get("/api/projects/{projectId}/campaign-resolutions") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            if (projectId == null || projectId <= 0 || campaignResolutions == null) {
                call.respond(if (projectId == null || projectId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@get
            }
            campaignResolutions.reconcileCases()
            call.respond(campaignResolutions.views(projectId))
        }
        post("/api/campaign-resolution-cases/{caseId}/proposals") {
            val caseId = call.parameters["caseId"]?.toLongOrNull()
            if (caseId == null || caseId <= 0 || campaignResolutions == null) {
                call.respond(if (caseId == null || caseId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = campaignResolutions.propose(caseId)
            val status = when (result.status) {
                CampaignResolutionProposalStatus.CREATED -> HttpStatusCode.Created
                CampaignResolutionProposalStatus.CASE_NOT_FOUND -> HttpStatusCode.NotFound
                CampaignResolutionProposalStatus.CASE_RESOLVED,
                CampaignResolutionProposalStatus.STALE_EVALUATION -> HttpStatusCode.Conflict
                CampaignResolutionProposalStatus.CONTEXT_BUDGET_EXCEEDED,
                CampaignResolutionProposalStatus.INVALID_OUTPUT -> HttpStatusCode.UnprocessableEntity
                CampaignResolutionProposalStatus.RESOURCE_BLOCKED -> HttpStatusCode.TooManyRequests
                CampaignResolutionProposalStatus.NO_COMPATIBLE_MODEL,
                CampaignResolutionProposalStatus.MODEL_FAILED,
                CampaignResolutionProposalStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
        post("/api/campaign-resolution-proposals/{proposalId}/admission") {
            val proposalId = call.parameters["proposalId"]?.toLongOrNull()
            if (proposalId == null || proposalId <= 0 || campaignResolutions == null) {
                call.respond(if (proposalId == null || proposalId <= 0) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = campaignResolutions.admit(proposalId)
            val status = when (result.status) {
                CampaignResolutionAdmissionStatus.ADMITTED -> HttpStatusCode.Created
                CampaignResolutionAdmissionStatus.PROPOSAL_NOT_FOUND -> HttpStatusCode.NotFound
                CampaignResolutionAdmissionStatus.CASE_RESOLVED,
                CampaignResolutionAdmissionStatus.STALE_EVALUATION,
                CampaignResolutionAdmissionStatus.REPOSITORY_DRIFTED -> HttpStatusCode.Conflict
                CampaignResolutionAdmissionStatus.CAPACITY_EXCEEDED,
                CampaignResolutionAdmissionStatus.INVALID_BACKLOG -> HttpStatusCode.UnprocessableEntity
                CampaignResolutionAdmissionStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
            }
            call.respond(status, result)
        }
        post("/api/remediation-campaigns/tick") {
            if (remediationCampaigns == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val result = runCatching { remediationCampaigns.tick() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            call.respond(HttpStatusCode.OK, result)
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
            if (proposal == null) call.respond(
                genesisProposalStatus(result.status),
                GenesisProposalFailureResponse(
                    status = result.status.name,
                    diagnostic = result.diagnostic,
                    retryable = result.status !in setOf(
                        GenesisProposalStatus.PROJECT_NOT_FOUND,
                        GenesisProposalStatus.PHASE_NOT_PROPOSABLE,
                    ),
                ),
            )
            else call.respond(genesisProposalStatus(result.status), proposal)
        }
        post("/api/projects/{projectId}/genesis/first-outcome") {
            val projectId = call.parameters["projectId"]?.toIntOrNull()
            val request = runCatching { call.receive<ProjectGenesisFirstOutcomeRequest>() }.getOrNull()
            if (projectId == null || projectId <= 0 || request == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.createProjectGenesisFirstOutcome(
                projectId,
                request.title,
                request.baseRevision,
                request.baseHash,
                request.confirmedProductIntent,
            )
            val status = when (result.status) {
                ProjectGenesisFirstOutcomeStatus.CREATED -> HttpStatusCode.Created
                ProjectGenesisFirstOutcomeStatus.ALREADY_EXISTS -> HttpStatusCode.OK
                ProjectGenesisFirstOutcomeStatus.PROJECT_NOT_FOUND -> HttpStatusCode.NotFound
                ProjectGenesisFirstOutcomeStatus.INVALID_INTENT,
                ProjectGenesisFirstOutcomeStatus.INVALID_TITLE -> HttpStatusCode.UnprocessableEntity
                ProjectGenesisFirstOutcomeStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                else -> HttpStatusCode.Conflict
            }
            call.respond(
                status,
                ProjectGenesisFirstOutcomeResponse(
                    result.status.name,
                    result.outcomeId,
                    projectGenesisFirstOutcomeDiagnostic(result.status),
                ),
            )
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
        get("/api/model-providers") {
            if (modelProviderRegistry == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val catalog = runCatching { modelProviderRegistry.catalog() }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(catalog)
        }
        put("/api/model-providers") {
            if (modelProviderRegistry == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@put
            }
            val catalog = runCatching { call.receive<ModelProviderCatalog>() }.getOrNull()
            if (catalog == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            val update = runCatching { modelProviderRegistry.update(catalog) }
            if (update.isFailure) {
                val status = if (update.exceptionOrNull() is IllegalArgumentException) {
                    HttpStatusCode.UnprocessableEntity
                } else {
                    HttpStatusCode.ServiceUnavailable
                }
                call.respond(status)
                return@put
            }
            call.respond(modelProviderRegistry.catalog())
        }
        get("/api/model-providers/inspection") {
            if (modelProviderRegistry == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(modelProviderRegistry.inspect())
        }
        get("/api/model-setup/recommendations") {
            if (definitionIntelligence == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val capacity = runCatching { definitionIntelligence.machineResourceConfiguration().capacity }.getOrElse {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }
            call.respond(LocalModelSetupRecommendations.forMachine(
                LocalModelSetupRecommendations.detectPlatform(),
                capacity.totalMemoryBytes,
            ))
        }
        post("/api/model-setup/presets/{presetId}/apply") {
            if (definitionIntelligence == null || modelProviderRegistry == null) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val preset = runCatching {
                LocalModelSetupRecommendations.resolve(call.parameters["presetId"].orEmpty())
            }.getOrElse {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val previousCatalog = modelProviderRegistry.catalog()
            val catalogUpdated = runCatching { modelProviderRegistry.update(preset.catalog) }
            if (catalogUpdated.isFailure) {
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@post
            }
            val profiles = definitionIntelligence.replaceProfiles(preset.profileOverrides)
            if (profiles.status != ModelProfileUpdateStatus.UPDATED) {
                runCatching { modelProviderRegistry.update(previousCatalog) }
                val status = if (profiles.status == ModelProfileUpdateStatus.STORAGE_UNAVAILABLE) {
                    HttpStatusCode.ServiceUnavailable
                } else {
                    HttpStatusCode.UnprocessableEntity
                }
                call.respond(status)
                return@post
            }
            call.respond(ModelSetupApplication(preset, profiles.configurations))
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
private const val BASELINE_INTERVAL_MILLIS = 5_000L
private const val ANALYSIS_INTERVAL_MILLIS = 1_000L
private const val CODING_INTERVAL_MILLIS = 1_000L
private const val AUDIT_INTERVAL_MILLIS = 1_000L
private const val CAMPAIGN_INTERVAL_MILLIS = 1_000L
private val DISPATCH_LOGGER: Logger = Logger.getLogger("com.orchard.backend.dispatch")
private val BASELINE_LOGGER: Logger = Logger.getLogger("com.orchard.backend.baseline")
private val ANALYSIS_LOGGER: Logger = Logger.getLogger("com.orchard.backend.analysis")
private val CODING_LOGGER: Logger = Logger.getLogger("com.orchard.backend.coding")
private val AUDIT_LOGGER: Logger = Logger.getLogger("com.orchard.backend.audit")
private val CAMPAIGN_LOGGER: Logger = Logger.getLogger("com.orchard.backend.remediation")

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

private fun standardsPolicyStatus(status: StandardsPolicyMutationStatus): HttpStatusCode = when (status) {
    StandardsPolicyMutationStatus.RECORDED -> HttpStatusCode.Created
    StandardsPolicyMutationStatus.PROJECT_NOT_FOUND,
    StandardsPolicyMutationStatus.STANDARD_NOT_FOUND,
    StandardsPolicyMutationStatus.PROPOSAL_NOT_FOUND,
    StandardsPolicyMutationStatus.ADMISSION_NOT_FOUND,
    StandardsPolicyMutationStatus.REPOSITORY_UNAVAILABLE -> HttpStatusCode.NotFound
    StandardsPolicyMutationStatus.REPOSITORY_DIRTY,
    StandardsPolicyMutationStatus.REPOSITORY_DRIFTED,
    StandardsPolicyMutationStatus.STALE_POLICY,
    StandardsPolicyMutationStatus.ALREADY_DECIDED -> HttpStatusCode.Conflict
    StandardsPolicyMutationStatus.INVALID_REQUEST,
    StandardsPolicyMutationStatus.POLICY_CONFLICT -> HttpStatusCode.UnprocessableEntity
    StandardsPolicyMutationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
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
    GenesisProposalStatus.RESOURCE_TELEMETRY_UNAVAILABLE,
    GenesisProposalStatus.REPOSITORY_CONTEXT_UNAVAILABLE,
    GenesisProposalStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    GenesisProposalStatus.REPOSITORY_CHANGED -> HttpStatusCode.Conflict
}

@Serializable
data class BindRepositoryRequest(val path: String)

@Serializable
data class GenesisProposalFailureResponse(
    val status: String,
    val diagnostic: String,
    val retryable: Boolean,
)

@Serializable
data class ProjectGenesisFirstOutcomeRequest(
    val title: String,
    val baseRevision: Int,
    val baseHash: String? = null,
    val confirmedProductIntent: String? = null,
)

@Serializable
data class ProjectGenesisFirstOutcomeResponse(
    val status: String,
    val outcomeId: Int? = null,
    val diagnostic: String,
)

private fun projectGenesisFirstOutcomeDiagnostic(status: ProjectGenesisFirstOutcomeStatus): String = when (status) {
    ProjectGenesisFirstOutcomeStatus.CREATED -> "First working outcome created."
    ProjectGenesisFirstOutcomeStatus.PROJECT_NOT_FOUND -> "The selected project no longer exists. Refresh and retry."
    ProjectGenesisFirstOutcomeStatus.WRONG_PHASE -> "The project is no longer planning its first working outcome. Refresh to load the current step."
    ProjectGenesisFirstOutcomeStatus.STALE_REVISION -> "Project setup changed before this outcome was created. Refresh and retry."
    ProjectGenesisFirstOutcomeStatus.ALREADY_EXISTS -> "A first working outcome already exists. Refresh to continue."
    ProjectGenesisFirstOutcomeStatus.INVALID_INTENT -> "Confirm the repository's current product intent before recording the first outcome."
    ProjectGenesisFirstOutcomeStatus.INVALID_TITLE -> "Name the first working outcome in 256 characters or fewer."
    ProjectGenesisFirstOutcomeStatus.WORKSPACE_REJECTED -> "The project hierarchy rejected this first working outcome."
    ProjectGenesisFirstOutcomeStatus.STORAGE_UNAVAILABLE -> "The first working outcome could not be stored. Check Orchard storage and retry."
}

@Serializable
data class DefinitionFeedbackRequest(val content: String)

@Serializable
data class AcceptDefinitionProposalRequest(val definition: WorkDefinitionSubmission? = null)

private fun Application.configureJson() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}