package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.config.OrchardPaths
import com.orchard.backend.vector.OllamaClient
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.AttemptSubmission
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    OrchardPaths.initialize()
    val workspace = WorkspaceStore(
        FileWorkspaceRepository(OrchardPaths.WORKSPACE_DIR),
        FileRepositoryBindingStore(OrchardPaths.WORKSPACE_DIR),
        FileWorkflowMemoryStore(OrchardPaths.WORKSPACE_DIR),
        FileWorkDefinitionStore(OrchardPaths.WORKSPACE_DIR),
    )
    val modelProvider = OllamaClient()
    val architect = ArchitectService(workspace, modelProvider)
    val workspaceServer = embeddedServer(Netty, host = "127.0.0.1", port = 8085) {
        workspaceApi(workspace)
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

fun Application.workspaceApi(workspace: WorkspaceStore) {
    configureJson()
    routing {
        get("/api/workspace") {
            call.respond(workspace.snapshot(MESSAGE_READY))
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

private fun workflowMutationStatus(status: WorkflowMutationStatus, success: HttpStatusCode): HttpStatusCode = when (status) {
    WorkflowMutationStatus.RECORDED -> success
    WorkflowMutationStatus.RUN_NOT_FOUND -> HttpStatusCode.NotFound
    WorkflowMutationStatus.RUN_CLOSED -> HttpStatusCode.Conflict
    WorkflowMutationStatus.INVALID_RECORD,
    WorkflowMutationStatus.REVISION_INVALID -> HttpStatusCode.UnprocessableEntity
    WorkflowMutationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}

@Serializable
data class BindRepositoryRequest(val path: String)

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