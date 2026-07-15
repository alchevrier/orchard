package com.orchard.frontend.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
}