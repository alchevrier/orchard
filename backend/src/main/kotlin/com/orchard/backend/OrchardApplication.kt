package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.agent.ProposalGenerationStatus
import com.orchard.backend.agent.CircuitIntelligenceService
import com.orchard.backend.agent.CircuitGenerationStatus
import com.orchard.backend.config.OrchardPaths
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
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.AttemptSubmission
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
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.remaining

fun main() {
    OrchardPaths.initialize()
    val workspace = WorkspaceStore(
        FileWorkspaceRepository(OrchardPaths.WORKSPACE_DIR),
        FileRepositoryBindingStore(OrchardPaths.WORKSPACE_DIR),
        FileWorkflowMemoryStore(OrchardPaths.WORKSPACE_DIR),
        FileWorkDefinitionStore(OrchardPaths.WORKSPACE_DIR),
        FileDefinitionCollaborationStore(OrchardPaths.WORKSPACE_DIR),
        FileModelExperienceStore(OrchardPaths.WORKSPACE_DIR),
        FileStagedDeliveryPlanStore(OrchardPaths.WORKSPACE_DIR),
        FileCircuitProposalStore(OrchardPaths.WORKSPACE_DIR),
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
    val workspaceServer = embeddedServer(Netty, host = "127.0.0.1", port = 8085) {
        workspaceApi(workspace, definitionIntelligence, circuitIntelligence)
    }
    val architectServer = embeddedServer(Netty, host = "127.0.0.1", port = 8086) {
        architectApi(architect)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
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
) {
    configureJson()
    routing {
        get("/api/workspace") {
            call.respond(workspace.snapshot(MESSAGE_READY))
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
                WorkflowStartStatus.STAGED_PLAN_BLOCKED -> HttpStatusCode.Conflict
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
        post("/api/workflow-runs/{runId}/cancel") {
            val runId = call.parameters["runId"]?.toLongOrNull()
            if (runId == null || runId <= 0) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val result = workspace.cancelWorkflow(runId)
            call.respond(workflowMutationStatus(result.status, HttpStatusCode.OK), result.snapshot)
        }
    }
}

private const val MAX_STAGED_PLAN_REQUEST_BYTES = 64 * 1024

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