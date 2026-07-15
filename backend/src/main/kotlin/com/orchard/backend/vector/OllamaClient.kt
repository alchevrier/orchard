package com.orchard.backend.vector

import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ModelProvider : AutoCloseable {
    suspend fun triage(prompt: String): String
    suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String
    override fun close() = Unit
}

class OllamaClient(
    private val endpoint: String = "http://127.0.0.1:11434/api/generate",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ModelProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }
    private val triagePrompt = loadArchitectPrompt("architect_phase0_triage.md")
    private val planningPrompt = loadArchitectPrompt("architect_phase2_planning.md")

    override suspend fun triage(prompt: String): String = generate("$triagePrompt\nUser request:\n$prompt")

    override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String {
        val workspaceText = buildString {
            repeat(workspace.entityCount) { index ->
                val entity = workspace.entityAt(index)
                append("[${entity.type} id=${entity.id} parent=${entity.parentId} title=${entity.title}] ")
            }
        }
        return generate(
            "$planningPrompt\nFirst-pass actionTypeId=$actionType, entityTypeId=$entityType.\n" +
                "Workspace: $workspaceText\nUser request:\n$prompt"
        )
    }

    private suspend fun generate(prompt: String): String {
        val response = client.post(endpoint) {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                OllamaGenerateRequest(
                    model = "phi3:mini",
                    prompt = prompt,
                    stream = false,
                    format = "json",
                    options = OllamaOptions(temperature = 0, seed = 42),
                )
            )
        }
        val body = response.bodyAsText()
        check(response.status.isSuccess()) { "Ollama returned HTTP ${response.status.value}: ${body.take(512)}" }
        check(body.encodeToByteArray().size <= MAX_RESPONSE_BYTES) { "Ollama response exceeded $MAX_RESPONSE_BYTES bytes" }
        return json.decodeFromString<OllamaGenerateResponse>(body).response
    }

    override fun close() {
        client.close()
    }

    private fun loadArchitectPrompt(name: String): String {
        val stream = requireNotNull(OllamaClient::class.java.getResourceAsStream("/default-system-prompts/$name")) {
            "Missing Architect prompt resource: $name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}

@Serializable
internal data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean,
    val format: String,
    val options: OllamaOptions,
)

@Serializable
internal data class OllamaOptions(val temperature: Int, val seed: Int)

@Serializable
private data class OllamaGenerateResponse(val response: String)