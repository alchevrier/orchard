package com.orchard.frontend.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DesktopNetworkClient(private val client: HttpClient = createHttpClient()) : AutoCloseable {

    suspend fun getWorkspace(): WorkspaceSnapshotResponse =
        client.get("http://127.0.0.1:8085/api/workspace").body()

    suspend fun submitArchitectPrompt(prompt: String): WorkspaceSnapshotResponse =
        client.post("http://127.0.0.1:8086/api/architect/chat") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(ArchitectChatRequest(prompt))
        }.body()

    override fun close() {
        client.close()
    }

    private companion object {
        fun createHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

@Serializable
private data class ArchitectChatRequest(val prompt: String)

@Serializable
data class WorkspaceSnapshotResponse(val resources: Map<String, WorkspaceResourceResponse> = emptyMap())

@Serializable
data class WorkspaceResourceResponse(
    val type: String,
    val path: String,
    val action: String,
)