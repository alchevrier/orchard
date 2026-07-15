package com.orchard.frontend.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopNetworkClientTest {
    @Test
    fun decodesWorkspaceSnapshotFromConflictResponse() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"resources":{"message":{"type":"MESSAGE","path":"Busy","action":"none"}}}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val snapshot = client.submitArchitectPrompt("Create a project")

        assertEquals("Busy", snapshot.resources.getValue("message").path)
        client.close()
    }

    @Test
    fun bindsRepositoryToSelectedProject() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("http://127.0.0.1:8085/api/projects/7/repository", request.url.toString())
            assertTrue(request.body.toByteArray().decodeToString().contains("/work/orchard"))
            respond(
                content = """{"resources":{},"repositories":{"7":{"projectId":7,"path":"/work/orchard","available":true,"branch":"main","buildSystem":"Gradle"}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val snapshot = client.bindRepository(7, "/work/orchard")

        assertEquals("main", snapshot.repositories.getValue(7).branch)
        client.close()
    }

    @Test
    fun startsWorkflowAndDecodesRecalledEpisode() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/work-items/4/runs", request.url.toString())
            respond(
                content = """{
                    "resources":{},
                    "workflowRuns":[{
                        "runId":1,
                        "state":"CONTEXT_READY",
                        "context":{
                            "workItemId":4,
                            "repository":{"commitHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                            "recalledEpisodes":[{
                                "episodeId":3,
                                "score":75,
                                "problem":"Gradle target failed",
                                "failedApproaches":["Changing Java alone"],
                                "resolution":"Align Kotlin and Java targets",
                                "evidenceSummary":"Build passed",
                                "sourceRevision":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                            }]
                        },
                        "workflow":{
                            "id":"default-delivery-task",
                            "version":1,
                            "evidenceContract":{
                                "id":"task-completion",
                                "version":1,
                                "requirements":[{"kind":"BUILD","description":"Build passes"}]
                            }
                        }
                    }]
                }""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val snapshot = client.startWorkflow(4)

        assertEquals("Align Kotlin and Java targets", snapshot.workflowRuns.single().context.recalledEpisodes.single().resolution)
        client.close()
    }

    @Test
    fun submitsEvidenceToWorkflowRun() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/workflow-runs/9/evidence", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"kind\":\"BUILD\""))
            assertTrue(body.contains("\"producer\":\"quality-center\""))
            respond(
                content = """{"resources":{},"workflowRuns":[]}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        client.submitEvidence(
            9,
            EvidenceSubmissionRequest(
                kind = "BUILD",
                revision = "a".repeat(40),
                command = "./gradlew build",
                exitCode = 0,
                outputHash = "b".repeat(64),
                summary = "Build passed",
                producer = "quality-center",
            ),
        )

        client.close()
    }
}