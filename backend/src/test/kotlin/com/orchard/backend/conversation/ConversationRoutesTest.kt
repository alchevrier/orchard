package com.orchard.backend.conversation

import com.orchard.backend.workspaceApi
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRoutesTest {
    @Test
    fun `conversation routes restore chronology and preserve message idempotency`() = testApplication {
        val conductor = ConversationConductorService(
            TransientConversationStore(),
            ConversationInterpreter { _, _ ->
                InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Durable reply."))
            },
            ConversationCapabilityRegistry(emptyList()),
            now = { "2026-06-21T00:00:00Z" },
        )
        application { workspaceApi(WorkspaceStore(), conversationConductor = conductor) }

        val created = client.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateConversationRequest("Long-running work")))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val conversationId = Json.decodeFromString<ConversationApiResponse>(created.bodyAsText())
            .projection!!.conversation.conversationId

        val request = SubmitConversationMessageRequest("client-message-0001", 1, "Discuss the plan")
        val recorded = client.post("/api/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
        assertEquals(HttpStatusCode.OK, recorded.status)
        val retried = client.post("/api/conversations/$conversationId/messages") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
        assertEquals(HttpStatusCode.OK, retried.status)
        assertEquals("ALREADY_RECORDED", Json.decodeFromString<ConversationApiResponse>(retried.bodyAsText()).status)

        val listed = client.get("/api/conversations")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(2, Json.decodeFromString<List<ConversationListItem>>(listed.bodyAsText()).single().messageCount)
        val restored = client.get("/api/conversations/$conversationId?afterEventId=1")
        assertEquals(HttpStatusCode.OK, restored.status)
        val projection = Json.decodeFromString<ConversationProjection>(restored.bodyAsText())
        assertEquals(listOf(MESSAGE_ROLE_USER, MESSAGE_ROLE_ASSISTANT), projection.messages.map { it.role })
        assertTrue(projection.events.all { it.eventId > 1 })
    }
}