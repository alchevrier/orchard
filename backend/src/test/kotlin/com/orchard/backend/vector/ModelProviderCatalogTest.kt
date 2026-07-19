package com.orchard.backend.vector

import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspaceApi
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ModelProviderCatalogTest {
    @Test
    fun `local provider sizes KV demand to actual input tokens`() {
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single())
        val profile = DefaultModelExecutionProfiles.boundedConversationConductor

        val fullAperture = provider.resourceDemand(profile)
        val shortTurn = provider.resourceDemand(profile, 100)
        provider.close()

        assertTrue(shortTurn.memoryBytes < fullAperture.memoryBytes)
        assertEquals(fullAperture.cpuUnits, shortTurn.cpuUnits)
    }

    @Test
    fun `catalog bootstraps local Ollama and recovers without secrets`() {
        val directory = createTempDirectory("orchard-provider-catalog-")
        val store = FileModelProviderCatalogStore(directory)

        val catalog = store.load()
        val recovered = FileModelProviderCatalogStore(directory).load()

        assertEquals(catalog, recovered)
        assertEquals(PROVIDER_POLICY_LOCAL_ONLY, catalog.policy)
        assertEquals(PROVIDER_PROTOCOL_OLLAMA_NATIVE, catalog.endpoints.single().protocol)
        assertFalse(Files.readString(directory.resolve("model-provider-catalog.json")).contains("apiKey", ignoreCase = true))
    }

    @Test
    fun `catalog recovers legacy checksum before command provenance fields`() {
        val directory = createTempDirectory("orchard-legacy-provider-catalog-")
        val compactCatalog = """{"policy":"LOCAL_ONLY","endpoints":[{"endpointId":"local-ollama","displayName":"Local Ollama","protocol":"OLLAMA_NATIVE","baseUrl":"http://127.0.0.1:11434","locality":"LOCAL","credentialReference":null,"enabled":true}],"bindings":[{"bindingId":"ollama:phi3-mini:json:t0:s42","endpointId":"local-ollama","model":"phi3:mini","contextWindowTokens":131072,"capabilities":["STRICT_JSON"],"modelDigest":null,"residentMemoryBytes":2400000000,"cpuUnits":1,"configuration":{"temperature":"0","seed":"42"}}]}"""
        val checksum = MessageDigest.getInstance("SHA-256").digest(compactCatalog.toByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        Files.writeString(
            directory.resolve("model-provider-catalog.json"),
            """{"version":1,"catalog":$compactCatalog,"checksum":"$checksum"}""",
        )

        val recovered = FileModelProviderCatalogStore(directory).load()

        assertEquals("phi3:mini", recovered.bindings.single().model)
        assertEquals(null, recovered.bindings.single().conversationCommand)
    }

    @Test
    fun `catalog rejects secret fields and remote endpoints under local only policy`() {
        val local = defaultLocalModelProviderCatalog()
        assertFailsWith<IllegalArgumentException> {
            validateModelProviderCatalog(
                local.copy(bindings = local.bindings.map { it.copy(configuration = mapOf("apiKey" to "forbidden")) })
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateModelProviderCatalog(
                ModelProviderCatalog(
                    policy = PROVIDER_POLICY_LOCAL_ONLY,
                    endpoints = listOf(
                        ModelEndpointDefinition(
                            "cloud-openai",
                            "Cloud",
                            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
                            "https://api.example.test",
                            PROVIDER_LOCALITY_REMOTE,
                            "env:ORCHARD_TEST_KEY",
                        )
                    ),
                    bindings = listOf(binding("cloud-openai")),
                )
            )
        }
    }

    @Test
    fun `Ollama adapter generates strict JSON and discovers models`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath} ${(request.body as? TextContent)?.text.orEmpty()}"
            when (request.url.encodedPath) {
                "/api/generate" -> respond(
                    """{"response":"{\"ok\":true}","prompt_eval_count":7,"eval_count":3}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/tags" -> respond(
                    """{"models":[{"name":"coder:14b"},{"name":"analyst:70b"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val endpoint = defaultLocalModelProviderCatalog().endpoints.single()
        val provider = CatalogModelProvider(endpoint, defaultLocalModelProviderCatalog().bindings.single(), engine = engine)

        val generation = provider.executeCodingPatch("implement", 128, 4_096)
        val inspection = provider.inspect()
        provider.close()

        assertEquals("{\"ok\":true}", generation.text)
        assertEquals(7, generation.promptTokens)
        assertEquals(listOf("analyst:70b", "coder:14b"), inspection.discoveredModels)
        assertTrue(requests.first().contains("\"format\":\"json\""))
        assertTrue(requests.first().contains("\"think\":false"))
        assertTrue(requests.first().contains("\"num_ctx\":4096"))
    }

    @Test
    fun `Ollama adapter reports thinking-only generation instead of JSON EOF`() = runTest {
        val engine = MockEngine {
            respond(
                """{"response":"","thinking":"I should formulate JSON","prompt_eval_count":7,"eval_count":128}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)

        val error = assertFailsWith<IllegalStateException> {
            provider.executeConversation("onboard https://example.com/repository.git", 128, 4_096)
        }
        provider.close()

        assertTrue(error.message.orEmpty().contains("exhausted the generation in thinking"))
    }

    @Test
    fun `OpenAI compatible adapter resolves bearer at request time and uses JSON mode`() = runTest {
        var authorization: String? = null
        var body = ""
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            body = (request.body as? TextContent)?.text.orEmpty()
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"plan\":true}"}}],"usage":{"prompt_tokens":11,"completion_tokens":5}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val endpoint = ModelEndpointDefinition(
            "lm-studio",
            "LM Studio",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "http://127.0.0.1:1234",
            PROVIDER_LOCALITY_LOCAL,
        )
        val provider = CatalogModelProvider(endpoint, binding("lm-studio"), credentialResolver = CredentialResolver { "runtime-secret" }, engine = engine)

        val generation = provider.executeRepositoryAnalysis("analyze", 256, 8_192)
        provider.close()

        assertEquals("{\"plan\":true}", generation.text)
        assertEquals(null, authorization)
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertFalse(body.contains("runtime-secret"))
    }

    @Test
    fun `remote OpenAI compatible adapter resolves environment reference without persisting the secret`() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{}"}}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val endpoint = ModelEndpointDefinition(
            "cloud-api",
            "Cloud API",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "https://api.example.test",
            PROVIDER_LOCALITY_REMOTE,
            "env:ORCHARD_TEST_KEY",
        )
        val provider = CatalogModelProvider(endpoint, binding("cloud-api"), credentialResolver = CredentialResolver { reference ->
            assertEquals("env:ORCHARD_TEST_KEY", reference)
            "runtime-secret"
        }, engine = engine)

        provider.executeCodingPatch("code", 128, 4_096)
        provider.close()

        assertEquals("Bearer runtime-secret", authorization)
        assertFalse(endpoint.toString().contains("runtime-secret"))
    }

    @Test
    fun `provider catalog routes retrieve replace and reject secret-bearing configuration`() = testApplication {
        val store = TransientModelProviderCatalogStore()
        val registry = ModelProviderRegistry(store)
        application { workspaceApi(WorkspaceStore(), modelProviderRegistry = registry) }

        val initial = client.get("/api/model-providers")
        assertEquals(HttpStatusCode.OK, initial.status)
        assertTrue(initial.bodyAsText().contains("local-ollama"))

        val localLmStudio = ModelProviderCatalog(
            endpoints = listOf(
                ModelEndpointDefinition(
                    "local-lm-studio",
                    "Local LM Studio",
                    PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
                    "http://127.0.0.1:1234",
                    PROVIDER_LOCALITY_LOCAL,
                )
            ),
            bindings = listOf(binding("local-lm-studio")),
        )
        val updated = client.put("/api/model-providers") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(Json.encodeToString(localLmStudio))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("local-lm-studio", registry.catalog().endpoints.single().endpointId)

        val secretBearing = localLmStudio.copy(
            bindings = localLmStudio.bindings.map { it.copy(configuration = mapOf("apiKey" to "forbidden")) }
        )
        val rejected = client.put("/api/model-providers") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(Json.encodeToString(secretBearing))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
        assertEquals("local-lm-studio", registry.catalog().endpoints.single().endpointId)
        registry.close()
    }

    @Test
    fun `local preferred policy excludes a smaller compatible remote binding`() {
        val localEndpoint = ModelEndpointDefinition(
            "local-models",
            "Local models",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "http://127.0.0.1:1234",
            PROVIDER_LOCALITY_LOCAL,
        )
        val remoteEndpoint = ModelEndpointDefinition(
            "remote-models",
            "Remote models",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "https://api.example.test",
            PROVIDER_LOCALITY_REMOTE,
            "env:ORCHARD_TEST_KEY",
        )
        val local = CatalogModelProvider(
            localEndpoint,
            binding("local-models").copy(bindingId = "local:large", contextWindowTokens = 131_072),
            PROVIDER_POLICY_LOCAL_PREFERRED,
            engine = MockEngine { respondError(HttpStatusCode.NotFound) },
        )
        val remote = CatalogModelProvider(
            remoteEndpoint,
            binding("remote-models").copy(bindingId = "remote:small", contextWindowTokens = 32_768),
            PROVIDER_POLICY_LOCAL_PREFERRED,
            engine = MockEngine { respondError(HttpStatusCode.NotFound) },
        )

        val selected = ModelProfileResolver.resolve(
            ModelExecutionProfile("test", 1, "TEST", 8_192, 1_024, setOf(MODEL_CAPABILITY_STRICT_JSON)),
            listOf(remote, local),
        )

        assertEquals("local:large", selected.bindingProfile().bindingId)
        local.close()
        remote.close()
    }

    private fun binding(endpointId: String) = CatalogModelBinding(
        bindingId = "$endpointId:test-model",
        endpointId = endpointId,
        model = "test-model",
        contextWindowTokens = 131_072,
    )
}
