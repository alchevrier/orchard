package com.orchard.backend.conversation

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.conversationRoutes(conductor: ConversationConductorService) {
    post("/api/conversations") {
        val request = runCatching { call.receive<CreateConversationRequest>() }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val result = conductor.create(request)
        call.respond(conversationStatus(result.status), result.toApiResponse())
    }
    get("/api/conversations") {
        call.respond(conductor.list())
    }
    get("/api/conversations/{conversationId}") {
        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
        val afterEventId = call.request.queryParameters["afterEventId"]?.toLongOrNull() ?: 0
        if (conversationId == null || conversationId <= 0 || afterEventId < 0) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val projection = conductor.projection(conversationId, afterEventId)
        if (projection == null) call.respond(HttpStatusCode.NotFound) else call.respond(projection)
    }
    post("/api/conversations/{conversationId}/messages") {
        val conversationId = call.parameters["conversationId"]?.toLongOrNull()
        val request = runCatching { call.receive<SubmitConversationMessageRequest>() }.getOrNull()
        if (conversationId == null || conversationId <= 0 || request == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val result = conductor.submitMessage(conversationId, request)
        call.respond(conversationStatus(result.status), result.toApiResponse())
    }
    post("/api/conversation-commands/{commandId}/admission") {
        val commandId = call.parameters["commandId"]?.toLongOrNull()
        val request = runCatching { call.receive<AdmitConversationCommandRequest>() }.getOrNull()
        if (commandId == null || commandId <= 0 || request == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val result = conductor.admitCommand(commandId, request)
        call.respond(conversationStatus(result.status), result.toApiResponse())
    }
    post("/api/conversation-objectives/{objectiveId}/control") {
        val objectiveId = call.parameters["objectiveId"]?.toLongOrNull()
        val request = runCatching { call.receive<ControlConversationObjectiveRequest>() }.getOrNull()
        if (objectiveId == null || objectiveId <= 0 || request == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val result = conductor.controlObjective(objectiveId, request)
        call.respond(conversationStatus(result.status), result.toApiResponse())
    }
}

private fun conversationStatus(status: ConversationOperationStatus): HttpStatusCode = when (status) {
    ConversationOperationStatus.CREATED -> HttpStatusCode.Created
    ConversationOperationStatus.RECORDED,
    ConversationOperationStatus.ALREADY_RECORDED -> HttpStatusCode.OK
    ConversationOperationStatus.NOT_FOUND -> HttpStatusCode.NotFound
    ConversationOperationStatus.STALE_SEQUENCE,
    ConversationOperationStatus.AMBIGUOUS_OBJECTIVE,
    ConversationOperationStatus.ADMISSION_REQUIRED -> HttpStatusCode.Conflict
    ConversationOperationStatus.INVALID_REQUEST -> HttpStatusCode.BadRequest
    ConversationOperationStatus.REJECTED -> HttpStatusCode.UnprocessableEntity
    ConversationOperationStatus.RESOURCE_UNAVAILABLE -> HttpStatusCode.TooManyRequests
    ConversationOperationStatus.MODEL_UNAVAILABLE,
    ConversationOperationStatus.STORAGE_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
}