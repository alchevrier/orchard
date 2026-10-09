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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ModelProviderCatalogTest {
    @Test
    fun `default GPT OSS catalog formats identical raw wire input across structured fallback`() = runTest {
        val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val engine = MockEngine { request ->
            requests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(if (requests.size == 1) """{"response":"{","done":false}""" else """{"response":"{}","done":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = defaultLocalModelProviderCatalog()
        val saved = catalog.bindings.single().copy(model = "gpt-oss:120b")
        assertFalse("input.format" in saved.configuration)
        val provider = CatalogModelProvider(catalog.endpoints.single(), saved, engine = engine)
        try {
            val prompt = "\u4f60\u597d \uD83D\uDE80 Return one compact JSON object."
            val profile = provider.bindingProfile()
            assertEquals(MODEL_INPUT_FORMAT_GPT_OSS_HARMONY, profile.configuration["input.format"])
            val expected = requireNotNull(formatModelInput(prompt, profile))
            val accounting = accountModelInput(prompt, profile)
            val result = provider.executeRepositoryAnalysis(prompt, 128, 4_096)
            assertEquals(2, requests.size)
            requests.forEach {
                assertEquals(expected.prompt, it.getValue("prompt").jsonPrimitive.content)
                assertEquals("true", it.getValue("raw").jsonPrimitive.content)
            }
            assertTrue("format" in requests.first())
            assertFalse("format" in requests.last())
            assertEquals(accounting.totalTokens, result.promptTokens)
            assertEquals(expected.prompt.encodeToByteArray().size, accounting.serializedInputBytes)
            assertEquals(com.orchard.backend.workspace.stagedPlanHash(expected.prompt), accounting.serializedInputHash)
            assertEquals(profile, provider.bindingProfile())
            assertFalse("input.format" in saved.configuration)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `GPT OSS Harmony formatting accounts complete payload and fails closed for unsupported bindings`() {
        val binding = ModelBindingProfile("test", "local", "gpt-oss:120b", 131_072, emptySet(), mapOf(
            "protocol" to PROVIDER_PROTOCOL_OLLAMA_NATIVE, "input.format" to MODEL_INPUT_FORMAT_GPT_OSS_HARMONY,
            "input.format.date" to "2026-10-09",
        ))
        val prompt = "\u4f60\u597d \uD83D\uDE80 Return one JSON object."
        val formatted = requireNotNull(formatModelInput(prompt, binding))
        assertTrue(formatted.prompt.contains("Current date: 2026-10-09"))
        assertTrue(formatted.prompt.contains("<|start|>user<|message|>$prompt<|end|>"))
        assertTrue(formatted.prompt.endsWith("<|start|>assistant"))
        val input = accountModelInput(prompt, binding)
        val rendered = accountModelOutput(formatted.prompt, binding)
        assertTrue(input.capacityEstablished)
        assertEquals(MODEL_TOKEN_COUNT_FORMATTED_UPPER_BOUND, input.method)
        assertEquals(rendered.totalTokens + 2, input.totalTokens)
        assertEquals(input.totalTokens - input.contentTokens, input.providerOverheadTokens)
        assertTrue(input.providerOverheadTokens > 2)
        assertEquals(prompt.encodeToByteArray().size, input.contentBytes)
        assertEquals(formatted.prompt.encodeToByteArray().size, input.serializedInputBytes)
        assertEquals(com.orchard.backend.workspace.stagedPlanHash(formatted.prompt), input.serializedInputHash)
        for (unsupported in listOf(
            binding.copy(model = "unknown-model"),
            binding.copy(configuration = binding.configuration - "input.format.date"),
            binding.copy(configuration = binding.configuration + ("input.format.date" to "invalid")),
            binding.copy(configuration = binding.configuration + ("input.format" to "automatic")),
        )) {
            assertEquals(null, formatModelInput(prompt, unsupported))
            assertFalse(accountModelInput(prompt, unsupported).capacityEstablished)
        }
        val mismatch = binding.copy(configuration = binding.configuration + ("tokenizer.model" to "different-model"))
        val fallback = accountModelInput(prompt, mismatch)
        assertEquals(MODEL_TOKEN_COUNT_BYTE_FALLBACK, fallback.method)
        assertEquals(requireNotNull(formatModelInput(prompt, mismatch)).prompt.encodeToByteArray().size + 2, fallback.totalTokens)
    }

    @Test
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun `typed stage instructions wire schemas and validators share array boundaries`() = runTest {
        val contracts = listOf(ModelOutputContract.REPOSITORY_ANALYSIS_CANDIDATE, ModelOutputContract.BOUNDED_CODING_TOOL_BATCH, ModelOutputContract.BOUNDED_LITERAL_REPLACEMENTS)
        for (contract in contracts) {
            var body = ""
            val engine = MockEngine { request ->
                body = (request.body as TextContent).text
                respond("""{"response":"{}","done":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val catalog = defaultLocalModelProviderCatalog()
            val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)
            try {
                val prompt = "${contract.instruction}\nConflicting source text: RepositoryAnalysisPlanContent( REQUIRE_LITERAL_REPLACEMENTS bounded-coding-tool-batch-v1"
                if (contract == ModelOutputContract.REPOSITORY_ANALYSIS_CANDIDATE) provider.executeRepositoryAnalysis(prompt, 128, 4_096)
                else provider.executeCodingPatch(prompt, 128, 4_096, contract)
                val request = Json.parseToJsonElement(body).jsonObject
                assertEquals(prompt, request.getValue("prompt").jsonPrimitive.content)
                val properties = request.getValue("format").jsonObject.getValue("properties").jsonObject
                if (contract == ModelOutputContract.REPOSITORY_ANALYSIS_CANDIDATE) {
                    val descriptor = com.orchard.backend.analysis.RepositoryAnalysisCandidate.serializer().descriptor
                    val required = request.getValue("format").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                    assertEquals((0 until descriptor.elementsCount).filterNot { descriptor.isElementOptional(it) }.map { descriptor.getElementName(it) }.toSet(), required)
                    for (field in contract.arrayLimits.keys + "unresolvedQuestions") {
                        val items = properties.getValue(field).jsonObject.getValue("items").jsonObject
                        assertEquals(if (field == "evidence") "object" else "string", items.getValue("type").jsonPrimitive.content)
                    }
                    val citation = properties.getValue("evidence").jsonObject.getValue("items").jsonObject
                    val citationDescriptor = com.orchard.backend.analysis.RepositoryEvidenceCitation.serializer().descriptor
                    val citationProperties = citation.getValue("properties").jsonObject
                    assertEquals((0 until citationDescriptor.elementsCount).map { citationDescriptor.getElementName(it) }.toSet(), citationProperties.keys)
                    assertEquals((0 until citationDescriptor.elementsCount).filterNot { citationDescriptor.isElementOptional(it) }.map { citationDescriptor.getElementName(it) }.toSet(), citation.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    assertEquals("false", citation.getValue("additionalProperties").jsonPrimitive.content)
                    assertEquals("string", citationProperties.getValue("contentHash").jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals(setOf("string", "null"), citationProperties.getValue("symbol").jsonObject.getValue("type").jsonArray.map { it.jsonPrimitive.content }.toSet())
                }
                for ((field, limit) in contract.arrayLimits) {
                    assertTrue(prompt.contains("$field=$limit"))
                    assertEquals(limit.toString(), properties.getValue(field).jsonObject.getValue("maxItems").jsonPrimitive.content)
                    val item = kotlinx.serialization.json.buildJsonObject { put("action", kotlinx.serialization.json.JsonPrimitive("REPLACE_LITERAL")) }
                    val atLimit = kotlinx.serialization.json.JsonObject(mapOf(field to kotlinx.serialization.json.JsonArray(List(limit) { item })))
                    val overLimit = kotlinx.serialization.json.JsonObject(mapOf(field to kotlinx.serialization.json.JsonArray(List(limit + 1) { item })))
                    assertEquals(null, contract.diagnostic(atLimit))
                    assertTrue(requireNotNull(contract.diagnostic(overLimit)).contains("at most $limit"))
                }
                if (contract == ModelOutputContract.BOUNDED_LITERAL_REPLACEMENTS) {
                    val forbidden = Json.parseToJsonElement("""{"operations":[{"action":"REWRITE_FILE"}]}""").jsonObject
                    assertTrue(requireNotNull(contract.diagnostic(forbidden)).contains("REPLACE_LITERAL"))
                    assertEquals("REPLACE_LITERAL", properties.getValue("operations").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
                }
            } finally {
                provider.close()
            }
        }
    }

    @Test
    fun `catalog allows exact tokenizer metadata but rejects credential configuration`() {
        val catalog = defaultLocalModelProviderCatalog()
        val binding = catalog.bindings.single().copy(configuration = mapOf(
            "tokenizer.model" to "gpt-oss:120b", "tokenizer.encoding" to "o200k_base", "input.format" to "raw",
        ))
        validateModelProviderCatalog(catalog.copy(bindings = listOf(binding)))
        val provider = CatalogModelProvider(catalog.endpoints.single(), binding)
        try {
            binding.configuration.forEach { (key, value) ->
                assertEquals(value, provider.bindingProfile().configuration[key], key)
            }
        } finally {
            provider.close()
        }
        for (key in listOf("api.key", "SECRET", "accessToken", "tokenizer.token", "tokenizer.model.secret", "tokenizer.encoding.key")) {
            assertFailsWith<IllegalArgumentException>(key) {
                validateModelProviderCatalog(catalog.copy(bindings = listOf(binding.copy(configuration = binding.configuration + (key to "forbidden")))))
            }
        }
    }

    @Test
    fun `raw provider request counts actual Unicode payload and rejects unknown formatting`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond("""{"response":"{}","done":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = defaultLocalModelProviderCatalog()
        val binding = catalog.bindings.single().copy(model = "gpt-oss:120b", configuration = catalog.bindings.single().configuration + ("input.format" to "raw"))
        val provider = CatalogModelProvider(catalog.endpoints.single(), binding, engine = engine)
        val prompt = "\u4f60\u597d \uD83D\uDE80 hello world"
        val accounting = accountModelInput(prompt, provider.bindingProfile())
        val generation = provider.executeRepositoryAnalysis(prompt, 128, 4096)
        val request = Json.parseToJsonElement(body).jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), request["raw"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(prompt), request["prompt"])
        assertTrue(accounting.capacityEstablished)
        assertEquals("raw-input-v1", accounting.providerFormat)
        assertEquals(0, accounting.providerOverheadTokens)
        assertTrue(accounting.contentBytes > accounting.contentTokens)
        assertEquals(accounting.totalTokens, generation.promptTokens)
        val automatic = accountModelInput(prompt, provider.bindingProfile().copy(configuration = provider.bindingProfile().configuration + ("input.format" to "automatic")))
        assertFalse(automatic.capacityEstablished)
        assertEquals(MODEL_TOKEN_COUNT_UNVERIFIED_FORMAT, automatic.method)
        val mismatch = accountModelInput(prompt, provider.bindingProfile().copy(configuration = provider.bindingProfile().configuration + ("tokenizer.model" to "different-model")))
        assertEquals(MODEL_TOKEN_COUNT_BYTE_FALLBACK, mismatch.method)
        assertEquals(mismatch.contentBytes, mismatch.totalTokens)
        provider.close()
    }

    @Test
    fun `GPT OSS input accounting distinguishes content bytes tokens and provider reserve`() {
        val binding = ModelBindingProfile("test", "ollama", "gpt-oss:120b", 131_072, setOf(MODEL_CAPABILITY_STRICT_JSON))
        val input = accountModelInput("hello world", binding)

        assertEquals(11, input.contentBytes)
        assertEquals(2, input.contentTokens)
        assertEquals(MODEL_PROVIDER_OVERHEAD_RESERVE_TOKENS, input.providerOverheadTokens)
        assertEquals(2 + MODEL_PROVIDER_OVERHEAD_RESERVE_TOKENS, input.totalTokens)
        assertEquals(MODEL_TOKEN_COUNT_TOKENIZER, input.method)
        assertEquals("jtokkit:1.1.0:o200k_base", input.tokenizerId)
        assertEquals(2, accountModelOutput("hello world", binding).totalTokens)
    }

    @Test
    fun `unknown tokenizer is an explicit conservative byte fallback`() {
        val binding = ModelBindingProfile("test", "local", "unknown-model", 8_192, emptySet())
        val input = accountModelInput("\u4f60\u597d \uD83D\uDE80", binding)

        assertEquals(input.contentBytes, input.contentTokens)
        assertEquals(input.contentBytes + MODEL_PROVIDER_OVERHEAD_RESERVE_TOKENS, input.totalTokens)
        assertEquals(MODEL_TOKEN_COUNT_BYTE_FALLBACK, input.method)
        assertEquals(null, input.tokenizerId)
    }

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
    fun `recent Ollama generation does not charge resident model twice`() = runTest {
        var now = 1L
        val engine = MockEngine {
            respond(
                """{"response":"{\"ok\":true}","done":true,"prompt_eval_count":7,"eval_count":3}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = defaultLocalModelProviderCatalog()
        val binding = catalog.bindings.single()
        val provider = CatalogModelProvider(
            catalog.endpoints.single(),
            binding,
            engine = engine,
            nanoTime = { now },
            ollamaResidentProbe = { _, _ -> null },
        )
        val profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning
        val coldDemand = provider.resourceDemand(profile, 100)

        provider.executeWorkDefinition("prompt", profile.outputBudgetTokens, 4_096)
        val residentDemand = provider.resourceDemand(profile, 100)
        now += java.time.Duration.ofMinutes(5).toNanos()
        val expiredDemand = provider.resourceDemand(profile, 100)
        provider.close()

        assertEquals(0, residentDemand.memoryBytes)
        assertEquals(coldDemand, expiredDemand)
    }

    @Test
    fun `resident Ollama model is not charged again after provider restart`() {
        val catalog = defaultLocalModelProviderCatalog()
        val binding = catalog.bindings.single()
        var probes = 0
        val provider = CatalogModelProvider(
            catalog.endpoints.single(),
            binding,
            ollamaResidentProbe = { _, model -> probes++; binding.contextWindowTokens.takeIf { model == binding.model } },
        )
        val profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning

        val first = provider.resourceDemand(profile, 100)
        val cached = provider.resourceDemand(profile, 100)
        provider.close()

        assertEquals(0, first.memoryBytes)
        assertEquals(first, cached)
        assertEquals(1, probes)
    }

    @Test
    fun `resident Ollama model charges only incremental context memory`() {
        val catalog = defaultLocalModelProviderCatalog()
        val binding = catalog.bindings.single()
        val provider = CatalogModelProvider(
            catalog.endpoints.single(),
            binding,
            ollamaResidentProbe = { _, _ -> 1_000 },
        )
        val profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning

        val demand = provider.resourceDemand(profile, 2_000)
        provider.close()

        assertEquals((2_000 + profile.outputBudgetTokens - 1_000).toLong() * 393_216L, demand.memoryBytes)
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
                    """{"response":"{\"ok\":true}","done":true,"prompt_eval_count":7,"eval_count":3}""",
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
    fun `Ollama adapter constrains bounded coding batches to operation objects`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += (request.body as? TextContent)?.text.orEmpty()
            respond(
                """{"response":"{\"summary\":\"change\",\"expectedRevision\":\"abc\",\"operations\":[{\"action\":\"REPLACE_LITERAL\",\"path\":\"src/Main.kt\"}]}","done":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)

        provider.executeCodingPatch("RepositoryAnalysisCandidate(ignored source text)", 128, 4_096, ModelOutputContract.BOUNDED_CODING_TOOL_BATCH)
        provider.close()

        assertTrue(requests.single().contains("\"format\":{\"type\":\"object\""))
        assertTrue(requests.single().contains("\"operations\""))
        assertTrue(requests.single().contains("\"items\":{\"type\":\"object\""))
    }

    @Test
    fun `Ollama adapter constrains a truncated-write correction to literal replacements`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += (request.body as? TextContent)?.text.orEmpty()
            respond(
                """{"response":"{\"summary\":\"repair\",\"expectedRevision\":\"abc\",\"operations\":[{\"action\":\"REPLACE_LITERAL\",\"path\":\"src/Main.kt\"}]}","done":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)

        provider.executeCodingPatch(
            "Return bounded-coding-tool-batch-v1 only. REQUIRE_LITERAL_REPLACEMENTS",
            128,
            4_096,
            ModelOutputContract.BOUNDED_LITERAL_REPLACEMENTS,
        )
        provider.close()

        assertTrue(requests.single().contains("\"enum\":[\"REPLACE_LITERAL\"]"))
        assertTrue(
            requests.single().contains(
                "\"required\":[\"action\",\"path\",\"expectedLiteral\",\"replacement\",\"expectedCount\"]",
            ),
        )
    }

    @Test
    fun `Ollama adapter retries empty JSON mode without weakening strict decoding`() = runTest {
        val requests = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        ModelProviderAuditLog.clear()
        val engine = MockEngine { request ->
            requests += (request.body as? TextContent)?.text.orEmpty()
            if (requests.size == 1) {
                respond(
                    """{"response":"","thinking":"private reasoning","done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":0}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"response":"{\"speechAct\":\"PROPOSE_DOMAIN_ACTION\",\"response\":\"Ready\"}","thinking":"more private reasoning","done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":12}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(
            catalog.endpoints.single(),
            catalog.bindings.single(),
            engine = engine,
            providerDiagnosticsEnabled = true,
            diagnosticSink = diagnostics::add,
        )

        val privatePrompt = "onboard https://example.com/private-repository.git"
        val generation = provider.executeConversation(privatePrompt, 128, 4_096)
        provider.close()

        assertEquals(2, requests.size)
        assertTrue(requests.first().contains("\"format\":\"json\""))
        assertFalse(requests.last().contains("\"format\""))
        assertTrue(requests.all { it.contains("\"think\":false") })
        assertTrue(generation.text.contains("PROPOSE_DOMAIN_ACTION"))
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.first().contains("\"responseLength\":0"))
        assertTrue(diagnostics.first().contains("\"thinkingLength\":17"))
        assertTrue(diagnostics.first().contains("\"done\":true"))
        assertTrue(diagnostics.first().contains("\"formatPresent\":true"))
        assertTrue(diagnostics.last().contains("\"formatPresent\":false"))
        assertTrue(diagnostics.all { it.contains("\"httpStatus\":200") && it.contains("\"think\":false") })
        assertTrue(diagnostics.none { it.contains(privatePrompt) || it.contains("private reasoning") || it.contains("PROPOSE_DOMAIN_ACTION") })
        assertTrue(ModelProviderAuditLog.recent().any { it.phase == "REQUEST_STARTED" && it.model == catalog.bindings.single().model })
        assertTrue(ModelProviderAuditLog.recent().any { it.phase == "STREAM_TERMINAL_FRAME" && it.done == true })
        assertTrue(ModelProviderAuditLog.recent().none { it.diagnostic.contains(privatePrompt) })
        ModelProviderAuditLog.clear()
    }

    @Test
    fun `model provider audit route exposes recent phases without prompt content`() = testApplication {
        ModelProviderAuditLog.clear()
        ModelProviderAuditLog.record(
            ModelProviderAuditEvent(
                eventId = 0,
                endpointId = "local-ollama",
                bindingId = "ollama:gpt-oss-120b:json:t0:s42",
                model = "gpt-oss:120b",
                phase = "REQUEST_STARTED",
                elapsedMillis = 0,
                promptTokens = 60102,
                contextWindowTokens = 96000,
                maxOutputTokens = 8192,
                structured = true,
            )
        )
        application { workspaceApi(WorkspaceStore()) }

        val response = client.get("/api/model-providers/audit-events?limit=1")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("REQUEST_STARTED"))
        assertTrue(body.contains("60102"))
        assertFalse(body.contains("prompt content"))
        ModelProviderAuditLog.clear()
    }

    @Test
    fun `Ollama analysis contract ignores conflicting prompt markers`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as TextContent).text
            respond(
                """{"response":"{\"sourcePaths\":[]}","done":true,"prompt_eval_count":7,"eval_count":3}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)

        provider.executeRepositoryAnalysis("bounded-coding-tool-batch-v1 REQUIRE_LITERAL_REPLACEMENTS", 128, 4_096)
        provider.close()

        val properties = Json.parseToJsonElement(body).jsonObject.getValue("format").jsonObject.getValue("properties").jsonObject
        assertTrue("sourcePaths" in properties)
        assertFalse("operations" in properties)
        assertFalse("scopeCoverage" in properties)
    }

    @Test
    fun `Ollama adapter retries incomplete structured response`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += (request.body as? TextContent)?.text.orEmpty()
            if (requests.size == 1) {
                respond(
                    """{"response":"{\"partial\":", "done":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"response":"{\"ok\":true}","done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":3}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(
            catalog.endpoints.single(),
            catalog.bindings.single().copy(model = "gpt-oss:120b"),
            engine = engine,
        )

        val generation = provider.executeWorkDefinition("prompt", 128, 4_096)
        provider.close()

        assertEquals("{\"ok\":true}", generation.text)
        assertEquals(2, requests.size)
        assertTrue(requests.first().contains("\"format\":"))
        assertFalse(requests.last().contains("\"format\""))
        assertTrue(requests.last().contains("\"think\":\"low\""))
    }

    @Test
    fun `Ollama adapter retries completed malformed structured response`() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += (request.body as? TextContent)?.text.orEmpty()
            if (requests.size == 1) {
                respond(
                    """{"response":"{not-json}","done":true,"done_reason":"stop","eval_count":8}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    """{"response":"{\"ok\":true}","done":true,"done_reason":"stop","prompt_eval_count":7,"eval_count":3}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)

        val generation = provider.executeWorkDefinition("prompt", 128, 4_096)
        provider.close()

        assertEquals("{\"ok\":true}", generation.text)
        assertEquals(2, requests.size)
        assertTrue(requests.first().contains("\"format\":\"json\""))
        assertFalse(requests.last().contains("\"format\""))
    }

    @Test
    fun `OpenAI compatible adapter resolves bearer at request time and uses JSON mode`() = runTest {
        var authorization: String? = null
        var body = ""
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            body = (request.body as? TextContent)?.text.orEmpty()
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"plan\":true}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":5}}""",
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
    fun `OpenAI compatible adapter rejects nonterminal partial completion`() = runTest {
        val engine = MockEngine {
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"expectedRevision\":\"0000"},"finish_reason":null}],"usage":{"prompt_tokens":0,"completion_tokens":0}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val endpoint = ModelEndpointDefinition(
            "local-compatible",
            "Local compatible",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "http://127.0.0.1:11434",
            PROVIDER_LOCALITY_LOCAL,
        )
        val provider = CatalogModelProvider(endpoint, binding("local-compatible"), engine = engine)

        val failure = assertFailsWith<IllegalStateException> {
            provider.executeCodingPatch("code", 128, 4_096)
        }
        provider.close()

        assertTrue(failure.message.orEmpty().contains("nonterminal completion"))
    }

    @Test
    fun `remote OpenAI compatible adapter resolves environment reference without persisting the secret`() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}""",
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
