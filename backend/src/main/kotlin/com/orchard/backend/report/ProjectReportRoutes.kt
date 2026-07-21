package com.orchard.backend.report

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class ProjectReportApiError(val diagnostic: String)

fun Route.projectReportRoutes(service: ProjectReportService) {
    get("/api/projects/{projectId}/reports") {
        call.reportResult {
            val projectId = call.projectId()
            val actor = call.request.queryParameters["actor"] ?: "HUMAN"
            val filters = call.request.queryParameters.getAll("filter").orEmpty()
                .flatMap { it.split(',') }.filter { it.isNotBlank() }.toSet()
            service.inbox(projectId, actor, filters)
        }
    }
    post("/api/projects/{projectId}/reports") {
        call.reportResult(HttpStatusCode.Created) {
            service.create(call.projectId(), call.receive<CreateProjectReportRequest>())
        }
    }
    post("/api/projects/{projectId}/reports/{reportId}/subscriptions") {
        call.reportResult {
            service.subscribe(call.projectId(), call.reportId(), call.receive<ReportSubscriptionRequest>())
        }
    }
    post("/api/projects/{projectId}/reports/{reportId}/subscriptions/pause") {
        call.reportResult {
            service.pause(call.projectId(), call.reportId(), call.receive<ReportActorRequest>())
        }
    }
    post("/api/projects/{projectId}/reports/{reportId}/subscriptions/resume") {
        call.reportResult {
            service.resume(call.projectId(), call.reportId(), call.receive<ReportActorRequest>())
        }
    }
    put("/api/projects/{projectId}/reports/{reportId}/revisions/{revision}/read") {
        call.reportResult {
            val revision = call.parameters["revision"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("Report revision is invalid")
            service.markRead(call.projectId(), call.reportId(), revision, call.receive<ReportActorRequest>())
        }
    }
    post("/api/projects/{projectId}/reports/threads") {
        call.reportResult {
            service.resolveThread(call.projectId(), call.receive<ReportThreadRequest>())
        }
    }
}

private fun ApplicationCall.projectId(): Int = parameters["projectId"]?.toIntOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("Project ID is invalid")

private fun ApplicationCall.reportId(): Long = parameters["reportId"]?.toLongOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("Report ID is invalid")

private suspend fun ApplicationCall.reportResult(
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    block: suspend () -> Any,
) {
    try {
        respond(successStatus, block())
    } catch (exception: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ProjectReportApiError(exception.message.orEmpty()))
    } catch (exception: IllegalStateException) {
        respond(HttpStatusCode.ServiceUnavailable, ProjectReportApiError(exception.message.orEmpty()))
    } catch (exception: Exception) {
        respond(HttpStatusCode.ServiceUnavailable, ProjectReportApiError(exception.message.orEmpty()))
    }
}