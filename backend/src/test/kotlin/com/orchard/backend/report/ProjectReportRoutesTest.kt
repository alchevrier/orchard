package com.orchard.backend.report

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.conversation.ConversationCapabilityRegistry
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.ConversationInterpretation
import com.orchard.backend.conversation.ConversationInterpreter
import com.orchard.backend.conversation.InterpretedConversationTurn
import com.orchard.backend.conversation.SPEECH_DISCUSS
import com.orchard.backend.conversation.TransientConversationStore
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.TransientRepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspaceApi
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectReportRoutesTest {
    @Test
    fun `project report routes expose subscription read and thread projections`() = testApplication {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(
            ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Project",
        )))
        workspace.commitBatch()
        val conductor = ConversationConductorService(
            TransientConversationStore(),
            ConversationInterpreter { _, _ -> InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Ready.")) },
            ConversationCapabilityRegistry(emptyList()),
        )
        val service = ProjectReportService(
            workspace,
            TransientRepositoryBindingStore,
            TransientProjectReportStore(),
            conversationConductor = conductor,
        )
        application { workspaceApi(workspace, conversationConductor = conductor, projectReports = service) }

        val created = client.post("/api/projects/1/reports") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreateProjectReportRequest(
                clientRequestId = "route-request",
                scope = ReportScope(REPORT_SCOPE_PROJECT, "1"),
                title = "Route report",
                items = listOf(ReportItemInput("finding", "FINDING", "OPEN", "Finding", "Route-backed report.")),
            )))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val publication = Json.decodeFromString<ReportPublication>(created.bodyAsText())

        assertEquals(HttpStatusCode.OK, client.post("/api/projects/1/reports/${publication.report.reportId}/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ReportSubscriptionRequest(REPORT_MODE_ACTION_REQUIRED)))
        }.status)
        assertEquals(HttpStatusCode.OK, client.put(
            "/api/projects/1/reports/${publication.report.reportId}/revisions/${publication.revision.revision}/read"
        ) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ReportActorRequest()))
        }.status)
        val linked = client.post("/api/projects/1/reports/threads") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ReportThreadRequest(REPORT_TARGET_REPORT, publication.report.reportId)))
        }
        assertEquals(HttpStatusCode.OK, linked.status)
        assertNotNull(Json.decodeFromString<ReportThreadLink>(linked.bodyAsText()).conversationId)

        val inbox = Json.decodeFromString<ProjectReportInbox>(client.get("/api/projects/1/reports").bodyAsText())
        assertEquals(1, inbox.reports.size)
        assertFalse(inbox.reports.single().unread)
        assertTrue(inbox.reports.single().subscribed)
    }
}