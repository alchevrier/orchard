package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.ArchitectureComponent
import com.orchard.backend.workspace.ArchitectureDecision
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ConversationCommandReference
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_ARCHITECTURE
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_EXPERIENCE
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.PROJECT_EXISTING_LOCAL
import com.orchard.backend.workspace.PROJECT_ORGANIZATION_GOVERNED
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisFirstOutcomeStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.RepositoryView
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.agent.GenesisProposalStatus
import com.orchard.backend.agent.GenesisProposalOutput
import com.orchard.backend.agent.GenesisRepositoryContextProvider
import com.orchard.backend.agent.GenesisRepositoryObservation
import com.orchard.backend.analysis.FileRepositoryObjectiveAssessmentStore
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.GenesisOutputMode
import com.orchard.backend.vector.GenesisProposalOutputDomain
import com.orchard.backend.vector.CatalogModelProvider
import com.orchard.backend.vector.ModelBoundaryTraceEvent
import com.orchard.backend.vector.ModelBoundaryTraceStore
import com.orchard.backend.vector.defaultLocalModelProviderCatalog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ProjectGenesisTest {
    @Test
    fun outputRecoveryChangesDecoderOnceWithoutAdmittingAuthority() = kotlinx.coroutines.test.runTest {
        val valid = Json.encodeToString(GenesisProposalOutput(ProjectGenesisSubmission(
            classification = PROJECT_GREENFIELD_LOCAL, productIntent = "A local service.", baseRevision = 0,
        )))
        val corrupt = valid.replace("A local service.", "Bad \u200B\u200B\u200B\uF0B7")
        for (recovers in listOf(true, false)) {
            val workspace = WorkspaceStore()
            createHierarchy(workspace)
            val outputs = ArrayDeque(listOf(corrupt, if (recovers) valid else corrupt))
            val modes = mutableListOf<GenesisOutputMode>()
            val model = object : ModelProvider by FakeGenesisModel("{}") {
                override fun bindingProfile() = FakeGenesisModel("{}").bindingProfile().let {
                    it.copy(configuration = it.configuration + ("protocol" to "OLLAMA_NATIVE"))
                }
                override suspend fun executeGenesisProposal(
                    prompt: String, maxOutputTokens: Int, contextWindowTokens: Int, outputDomain: GenesisProposalOutputDomain,
                ): ModelGeneration {
                    modes += outputDomain.outputMode
                    return ModelGeneration(outputs.removeFirst(), 64, 64)
                }
            }
            val result = GenesisIntelligenceService(workspace, model).propose(1,
                GenesisProposalRequest("Create a local service.", recoverInvalidOutput = true))
            assertEquals(listOf(GenesisOutputMode.NATIVE_SCHEMA_FINAL, GenesisOutputMode.JSON_OBJECT_FINAL), modes)
            assertEquals(if (recovers) GenesisProposalStatus.CREATED else GenesisProposalStatus.INVALID_OUTPUT, result.status)
            assertEquals(!recovers, result.recoveryExhausted)
            assertEquals(GENESIS_CLASSIFICATION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
        }
    }

    @Test
    fun unsupportedDecoderRecoveryDoesNotRepeatAnIdenticalProviderRequest() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        var calls = 0
        val model = object : ModelProvider by FakeGenesisModel("{}") {
            override fun bindingProfile() = FakeGenesisModel("{}").bindingProfile().let {
                it.copy(configuration = it.configuration + ("protocol" to "OPENAI_COMPATIBLE"))
            }
            override suspend fun executeGenesisProposal(
                prompt: String, maxOutputTokens: Int, contextWindowTokens: Int, outputDomain: GenesisProposalOutputDomain,
            ): ModelGeneration {
                calls++
                return ModelGeneration("{}", 64, 1)
            }
        }
        val result = GenesisIntelligenceService(workspace, model).propose(1,
            GenesisProposalRequest("Create a local service.", recoverInvalidOutput = true))
        assertEquals(1, calls)
        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, result.status)
        assertTrue(result.recoveryExhausted)
    }

    @Test
    fun outputQualityGateRejectsGeneratedArtifactsWithoutRejectingOrdinaryUnicode() = kotlinx.coroutines.test.runTest {
        for ((text, expected) in listOf(
            "Use H2 \u200B\u200B\u200B\uF0B7" to GenesisProposalStatus.INVALID_OUTPUT,
            "<|channel|>analysis<|message|>private scratch text" to GenesisProposalStatus.INVALID_OUTPUT,
            "Use H2 \uF0B7" to GenesisProposalStatus.INVALID_OUTPUT,
            "Use H2 \u0000" to GenesisProposalStatus.INVALID_OUTPUT,
            "Caf\u00e9 \u4f60\u597d \u0645\u06cc\u200c\u0631\u0648\u062f" to GenesisProposalStatus.CREATED,
        )) {
            val workspace = WorkspaceStore()
            createHierarchy(workspace)
            val output = Json.encodeToString(GenesisProposalOutput(ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL, productIntent = text, baseRevision = 0,
            )))
            val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output))
                .propose(1, GenesisProposalRequest("Create a local service."))
            assertEquals(expected, result.status)
            assertTrue(!result.diagnostic.contains("private scratch text"))
        }
    }

    @Test
    fun exhaustedOutputRecoveryIsNotAdvertisedAsRetryable() = testApplication {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val base = FakeGenesisModel("{}")
        val model = object : ModelProvider by base {
            override fun bindingProfile() = base.bindingProfile().let {
                it.copy(configuration = it.configuration + ("protocol" to "OLLAMA_NATIVE"))
            }
        }
        application { workspaceApi(workspace, genesisIntelligence = GenesisIntelligenceService(workspace, model)) }
        val response = client.post("/api/projects/1/genesis/proposal") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"prompt":"Create a local service.","recoverInvalidOutput":true}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("false", body.getValue("retryable").jsonPrimitive.content)
        assertTrue(body.getValue("diagnostic").jsonPrimitive.content.contains("Bounded output recovery exhausted"))
    }

    @Test
    fun unsupportedBlueprintToolchainsAreRejectedOnlyForGreenfieldProjects() {
        for (classification in listOf(PROJECT_GREENFIELD_LOCAL, PROJECT_EXISTING_LOCAL)) {
            val workspace = WorkspaceStore()
            createHierarchy(workspace)
            assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, ProjectGenesisSubmission(
                classification = classification, productIntent = "Deliver a local service.", baseRevision = 0,
            )).status)
            fun current() = workspace.snapshot(MESSAGE_READY).projectGenesis.single()
            assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, experienceSubmission(current())).status)
            assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, architectureSubmission(current())).status)
            val basis = current()
            val submission = blueprintSubmission(basis)
            val result = workspace.advanceProjectGenesis(1,
                submission.copy(blueprint = requireNotNull(submission.blueprint).copy(toolchain = "maven")))
            assertEquals(
                if (classification == PROJECT_GREENFIELD_LOCAL) ProjectGenesisStatus.INVALID_TRANSITION else ProjectGenesisStatus.RECORDED,
                result.status,
            )
            if (classification == PROJECT_GREENFIELD_LOCAL) assertEquals(basis, current())
        }
    }

    @Test
    fun onboardingWireContractAndTracePreserveIncompleteEvidenceUntilRejection() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val path = contextProvider.observation.context.files.single().path
        val valid = """{
            "submission":{"classification":"EXISTING_LOCAL","productIntent":"Maintain the existing product.","baseRevision":0},
            "repositoryClaims":[{
                "claimId":"genesis-service","statement":"The repository defines a Genesis service.","status":"SUPPORTED",
                "support":[{"path":"$path","contentHash":"${contextProvider.contentHash}","observation":"The supplied file defines GenesisIntelligenceService."}]
            }]
        }""".trimIndent()
        val incomplete = valid.replace(
            ""","contentHash":"${contextProvider.contentHash}","observation":"The supplied file defines GenesisIntelligenceService."""",
            "",
        )
        val outputs = ArrayDeque(listOf(incomplete, valid))
        val requests = mutableListOf<JsonObject>()
        val responses = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val body = Json.encodeToString(buildJsonObject {
                put("response", outputs.removeFirst())
                put("done", true)
                put("prompt_eval_count", 100)
                put("eval_count", 100)
            })
            responses += body
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val catalog = defaultLocalModelProviderCatalog()
        val provider = CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single(), engine = engine)
        val directory = createTempDirectory("orchard-genesis-boundaries-")
        val quotaDirectory = createTempDirectory("orchard-genesis-trace-quota-")
        val store = ModelBoundaryTraceStore(directory)
        val service = GenesisIntelligenceService(
            workspace, provider, repositoryContextProvider = contextProvider, modelBoundaryTraces = store,
        )
        try {
            val rejected = service.propose(1, GenesisProposalRequest("Classify the existing repository."))
            assertEquals(GenesisProposalStatus.INVALID_OUTPUT, rejected.status)
            assertTrue(rejected.diagnostic.contains("claimSupport[0]:{path:string}"))
            assertEquals(null, service.latestRepositoryAssessment(1))

            val schema = requests.single().getValue("format").jsonObject
            val support = schema.getValue("properties").jsonObject.getValue("repositoryClaims").jsonObject
                .getValue("items").jsonObject.getValue("properties").jsonObject.getValue("support").jsonObject.getValue("items").jsonObject
                .getValue("oneOf").jsonArray.single().jsonObject
            assertEquals(setOf("path", "contentHash", "observation"),
                support.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(path, support.getValue("properties").jsonObject.getValue("path").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
            assertEquals(contextProvider.contentHash, support.getValue("properties").jsonObject.getValue("contentHash").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
            val submissionSchema = schema.getValue("properties").jsonObject.getValue("submission").jsonObject
            assertEquals(setOf("baseRevision", "classification", "productIntent"),
                submissionSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals("null", submissionSchema.getValue("properties").jsonObject.getValue("components").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(requests.single().getValue("prompt").jsonPrimitive.content.contains("genesis-proposal-v2"))

            val created = service.propose(1, GenesisProposalRequest("Classify the existing repository."))
            assertEquals(GenesisProposalStatus.CREATED, created.status)
            assertEquals(contextProvider.contentHash, created.proposal?.repositoryAssessment?.claims?.single()?.support?.single()?.contentHash)
            assertEquals(GENESIS_CLASSIFICATION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)

            val traces = Files.list(directory).use { it.toList() }.map { file ->
                Files.readAllLines(file).map { Json.decodeFromString<ModelBoundaryTraceEvent>(it) }
            }
            assertTrue(traces.flatten().all { it.projectId == 1 && it.runId == null })
            val failedTrace = traces.single { events -> events.any { it.stage == "MODEL_GENERATION" && it.payload == incomplete } }
            assertEquals(incomplete, failedTrace.single { it.stage == "GENESIS_NORMALIZED_CANDIDATE" }.payload)
            assertEquals("FAIL", failedTrace.single { it.stage == "GENESIS_DECODE" }.details["status"])
            assertEquals(responses.first(), failedTrace.single { it.stage == "PROVIDER_RESPONSE" }.payload)
            assertEquals("GENESIS_RESULT", failedTrace.last().stage)
            val passedTrace = traces.single { events -> events.any { it.stage == "GENESIS_VALIDATION" } }
            assertEquals(listOf("GENESIS_REQUEST", "PROVIDER_REQUEST", "PROVIDER_RESPONSE", "MODEL_GENERATION",
                "GENESIS_NORMALIZED_CANDIDATE", "GENESIS_CANONICAL_CANDIDATE", "GENESIS_DECODE",
                "GENESIS_VALIDATION", "GENESIS_RESULT"), passedTrace.map { it.stage })

            val quotaStore = ModelBoundaryTraceStore(quotaDirectory, maxTraces = 1)
            quotaStore.newTrace(34).record("OCCUPIED", "existing trace")
            val unavailable = GenesisIntelligenceService(
                workspace, provider, repositoryContextProvider = contextProvider, modelBoundaryTraces = quotaStore,
            ).propose(1, GenesisProposalRequest("Classify the existing repository."))
            assertEquals(GenesisProposalStatus.STORAGE_UNAVAILABLE, unavailable.status)
            assertEquals(2, requests.size)
        } finally {
            provider.close()
            for (path in listOf(directory, quotaDirectory)) {
                Files.list(path).use { entries -> entries.forEach(Files::deleteIfExists) }
                Files.delete(path)
            }
        }
    }

    @Test
    fun modelProposalIsPhaseBoundPinnedAndNonAuthoritative() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val validOutput = """{
            "submission":{
                "classification":"GREENFIELD_LOCAL",
                "productIntent":"A guided local product.",
                "baseRevision":99,
                "baseHash":"forged"
            },
            "observations":["The user asked for local authority."],
            "unresolvedQuestions":[]
        }""".trimIndent()
        val service = GenesisIntelligenceService(workspace, FakeGenesisModel(validOutput))

        val result = service.propose(1, GenesisProposalRequest("I am making a private local product."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(0, result.proposal?.baseRevision)
        assertEquals(null, result.proposal?.baseHash)
        assertEquals(0, workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision?.revision ?: 0)

        val invalidOutput = """{
            "submission":{
                "blueprint":{"rootName":"wrong","toolchain":"gradle","modules":["app"],"verificationCommands":["test"]},
                "baseRevision":0
            }
        }""".trimIndent()
        val invalid = GenesisIntelligenceService(workspace, FakeGenesisModel(invalidOutput))
            .propose(1, GenesisProposalRequest("Skip directly to repository setup."))
        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, invalid.status)
        assertEquals(GENESIS_CLASSIFICATION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    @Test
    fun experienceProposalReceivesExactStructuredOutputExample() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        val output = """{
            "submission":{
                "experience":{
                    "audience":"Local developers",
                    "productPromise":"Decisions become visible authority.",
                    "primaryJourney":["Describe","Review","Admit"],
                    "interactionPrinciples":["Show one next decision"],
                    "emotionalQualities":["Calm"],
                    "mustNotFeelLike":["A dashboard"],
                    "accessibility":["Keyboard accessible"]
                },
                "baseRevision":1
            }
        }""".trimIndent()
        val model = FakeGenesisModel(output)

        val result = GenesisIntelligenceService(workspace, model)
            .propose(1, GenesisProposalRequest("Make the experience calm and guided."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertTrue(model.lastPrompt.contains("\"productPromise\""))
        assertTrue(model.lastPrompt.contains("\"mustNotFeelLike\""))
        assertTrue(model.lastPrompt.contains("\"requiredOutputExample\":{\"submission\""))
    }

    @Test
    fun plainProviderJsonFenceIsDecodedBeforeStrictValidation() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val output = """```json
            {
              "submission": {
                "classification": "GREENFIELD_LOCAL",
                "productIntent": "A guided local product.",
                "baseRevision": 0
              }
            }
            ```""".trimIndent()

        val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output))
            .propose(1, GenesisProposalRequest("Create a local product."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals("A guided local product.", result.proposal?.submission?.productIntent)
    }

    @Test
    fun knownSubmissionMetadataMisplacedAtRootIsCanonicalized() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        val output = """{
            "submission": {
                "classification": null,
                "productIntent": null,
                "experience": {
                    "audience": "Local developers",
                    "productPromise": "Decisions become visible authority.",
                    "primaryJourney": ["Describe", "Review", "Admit"],
                    "interactionPrinciples": ["Show one next decision"],
                    "emotionalQualities": ["Calm"],
                    "mustNotFeelLike": ["A dashboard"],
                    "accessibility": ["Keyboard accessible"]
                }
            },
            "baseRevision": 1,
            "baseHash": "misplaced",
            "components": null,
            "decisions": null,
            "firstEpicId": null,
            "blueprint": null,
            "observations": [],
            "unresolvedQuestions": []
        }""".trimIndent()

        val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output))
            .propose(1, GenesisProposalRequest("Make the experience calm and guided."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertNotNull(result.proposal?.submission?.experience)
        assertEquals(1, result.proposal?.baseRevision)
    }

    @Test
    fun unknownRootFieldStillFailsStrictDecoding() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val output = """{
            "submission": {
                "classification": "GREENFIELD_LOCAL",
                "productIntent": "A guided local product."
            },
            "unexpectedAuthority": true
        }""".trimIndent()

        val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output))
            .propose(1, GenesisProposalRequest("Create a local product."))

        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, result.status)
        assertTrue(result.diagnostic.contains("unexpectedAuthority:boolean"))
    }

    @Test
    fun invalidArchitectOutputReturnsActionableFailureBody() = testApplication {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val service = GenesisIntelligenceService(workspace, FakeGenesisModel("not-json"))
        application { workspaceApi(workspace, genesisIntelligence = service) }

        val response = client.post("/api/projects/1/genesis/proposal") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"prompt":"Describe the experience"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"INVALID_OUTPUT\""))
        assertTrue(body.contains("required schema"))
        assertTrue(body.contains("\"retryable\":true"))
    }

    @Test
    fun schemaFailureReportsOnlyFieldNamesAndJsonTypes() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val output = """{
            "submission": {
                "classification": ["wrong-type"],
                "productIntent": "private product details",
                "baseRevision": 0
            }
        }""".trimIndent()

        val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output))
            .propose(1, GenesisProposalRequest("Create a local product."))

        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, result.status)
        assertTrue(result.diagnostic.contains("classification:array"))
        assertTrue(result.diagnostic.contains("productIntent:string"))
        assertTrue(!result.diagnostic.contains("private product details"))
    }

    @Test
    fun validArchitectureUsesProviderTokenCountInsteadOfJsonByteLength() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val longResponsibility = "Deliver the admitted working outcome. ".repeat(80)
        val output = """{
            "submission": {
                "components": [{
                    "componentId": "core",
                    "name": "Core component",
                    "responsibility": "$longResponsibility",
                    "repositoryPaths": ["src"]
                }],
                "decisions": [{
                    "decisionId": "ADR-GENESIS-1",
                    "title": "Founding decision",
                    "context": "The first outcome needs one bounded implementation path.",
                    "decision": "Use the core component.",
                    "consequences": ["The first delivery remains focused."],
                    "componentIds": ["core"]
                }],
                "firstEpicId": 2,
                "baseRevision": 2
            }
        }""".trimIndent()
        assertTrue(output.encodeToByteArray().size > 2_000)

        val result = GenesisIntelligenceService(workspace, FakeGenesisModel(output, completionTokens = 1_800))
            .propose(1, GenesisProposalRequest("Deliver the first working outcome."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(2, result.proposal?.submission?.firstEpicId)
    }

    @Test
    fun architectureProposalIsGroundedInRevisionPinnedRepositoryEvidence() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val model = FakeGenesisModel(validArchitectureOutput(contextProvider.contentHash))

        val result = GenesisIntelligenceService(
            workspace,
            model,
            repositoryContextProvider = contextProvider,
        ).propose(
            1,
            GenesisProposalRequest(
                "Use the existing Architect service. Ask explicit questions wherever the implementation cannot be established from repository evidence.",
            ),
        )

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertTrue(model.lastPrompt.contains("\"repositoryContext\""))
        assertTrue(model.lastPrompt.contains("GenesisIntelligenceService"))
        assertTrue(model.lastPrompt.contains(contextProvider.observation.revision))
        assertTrue(model.lastPrompt.contains("Do not ask the user to locate code"))
        assertTrue(model.lastPrompt.contains("Do not use unresolvedQuestions for capacity, sizing, tuning"))
        assertTrue(!model.lastPrompt.contains("Ask explicit questions wherever the implementation cannot be established"))
        assertEquals(contextProvider.observation.revision, result.proposal?.repositoryRevision)
        assertEquals(
            contextProvider.observation.context.files.single().contentHash,
            result.proposal?.repositoryEvidence?.single()?.contentHash,
        )
        assertEquals("SUPPORTED", result.proposal?.repositoryAssessment?.claims?.single()?.status)
        assertEquals("Use the existing Architect service.", result.proposal?.repositoryAssessment?.objective)
    }

    @Test
    fun oversizedRepositoryContextIsCompactedToArchitectBudget() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val files = (1..4).map { index ->
            CodingContextFile(
                "backend/src/main/kotlin/com/orchard/backend/agent/GenesisContext$index.kt",
                "class GenesisContext$index\n" + "implementation detail $index ".repeat(120),
            )
        }
        val observation = GenesisRepositoryObservation(
            revision = "c".repeat(40),
            context = CodingRepositoryContext(files, omittedFileCount = 7),
        )
        val contextProvider = object : GenesisRepositoryContextProvider {
            override fun observe(repositoryPath: String, query: String): GenesisRepositoryObservation = observation
            override fun currentRevision(repositoryPath: String): String = observation.revision
            override fun isCurrent(
                repositoryPath: String,
                query: String,
                observation: GenesisRepositoryObservation,
            ): Boolean = true
        }
        val model = FakeGenesisModel(validArchitectureOutput(files.first().contentHash).replace(
            "backend/src/main/kotlin/com/orchard/backend/agent/GenesisIntelligenceService.kt",
            files.first().path,
        ))

        val result = GenesisIntelligenceService(
            workspace,
            model,
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Use the existing Architect implementation."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        val suppliedEvidence = requireNotNull(result.proposal).repositoryEvidence
        assertTrue(suppliedEvidence.isNotEmpty())
        assertTrue(suppliedEvidence.size < files.size)
        assertEquals(files.first().path, suppliedEvidence.first().path)
        assertTrue(!model.lastPrompt.contains(files.last().path))
        assertTrue(model.lastPrompt.encodeToByteArray().size <= 12_000)
        assertEquals(7 + files.size - suppliedEvidence.size, result.proposal?.omittedRepositoryFileCount)
        assertEquals(result.proposal?.omittedRepositoryFileCount, result.proposal?.repositoryAssessment?.omittedRepositoryFileCount)
    }

    @Test
    fun unestablishedComponentNullRepositoryPathsAreCanonicalizedToEmpty() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val output = validArchitectureOutput(contextProvider.contentHash).replace(
            "\"repositoryPaths\": [\"backend/src/main/kotlin/com/orchard/backend/agent\"]",
            "\"repositoryPaths\": null",
        )

        val result = GenesisIntelligenceService(
            workspace,
            FakeGenesisModel(output),
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Add a component that is not established yet."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(emptyList(), result.proposal?.submission?.components?.single()?.repositoryPaths)
    }

    @Test
    fun explicitNullClaimCollectionsAreCanonicalizedToEmpty() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val output = validArchitectureOutput(contextProvider.contentHash)
            .replace("\"defeaters\": []", "\"defeaters\": null")
            .replace("\"repositoryClaims\": [{", "\"observations\": null, \"unresolvedQuestions\": null, \"repositoryClaims\": [{")

        val result = GenesisIntelligenceService(
            workspace,
            FakeGenesisModel(output),
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Use the existing Architect service."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(emptyList(), result.proposal?.observations)
        assertEquals(emptyList(), result.proposal?.unresolvedQuestions)
        assertEquals(emptyList(), result.proposal?.repositoryAssessment?.claims?.single()?.defeaters)
    }

    @Test
    fun repositoryEvidenceRejectsInventedProtocolTextButAllowsCitingActualProtocolSource() = kotlinx.coroutines.test.runTest {
        val protocol = "<|start|>assistant<|channel|>analysis<|message|>"
        for (containsProtocol in listOf(false, true)) {
            val workspace = WorkspaceStore(repositoryBindings = boundRepository())
            createHierarchy(workspace)
            val classified = advance(workspace, ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL, productIntent = "A guided local product.", baseRevision = 0,
            ), GENESIS_EXPERIENCE)
            advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
            val contextProvider = FakeGenesisRepositoryContextProvider(source = if (containsProtocol) {
                """class GenesisIntelligenceService { val header = "$protocol" }"""
            } else "class GenesisIntelligenceService")
            val output = validArchitectureOutput(contextProvider.contentHash)
                .replace("The supplied file defines GenesisIntelligenceService.", "The source contains $protocol")
            val service = GenesisIntelligenceService(workspace, FakeGenesisModel(output), repositoryContextProvider = contextProvider)

            val result = service.propose(1, GenesisProposalRequest("Use the existing Architect implementation."))

            assertEquals(if (containsProtocol) GenesisProposalStatus.CREATED else GenesisProposalStatus.INVALID_OUTPUT, result.status)
            if (!containsProtocol) {
                assertTrue(result.diagnostic.contains("model-protocol text"))
                assertEquals(null, service.latestRepositoryAssessment(1))
            }
        }
    }

    @Test
    fun repositoryClaimWithForgedEvidenceIsRejected() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()

        val result = GenesisIntelligenceService(
            workspace,
            FakeGenesisModel(validArchitectureOutput("f".repeat(64))),
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Use the existing Architect service."))

        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, result.status)
        assertTrue(result.diagnostic.contains("claim evidence"))
    }

    @Test
    fun malformedGeneratedClaimIsNotAttributedToUserAnswers() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val output = validArchitectureOutput(contextProvider.contentHash).replace(
            "\"claimId\": \"genesis-proposals\"",
            "\"claimId\": \"Genesis Proposals\"",
        )

        val result = GenesisIntelligenceService(
            workspace,
            FakeGenesisModel(output),
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Use my answers to refine the existing proposal."))

        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, result.status)
        assertTrue(result.diagnostic.contains("Architect-generated claim 1"))
        assertTrue(result.diagnostic.contains("not lowercase hyphenated text"))
        assertTrue(result.diagnostic.contains("not a judgment about your answers"))
    }

    @Test
    fun durableRepositoryAssessmentIsReusedByNextRefinement() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val directory = createTempDirectory("repository-assessments")
        try {
            val firstStore = FileRepositoryObjectiveAssessmentStore(directory)
            val first = GenesisIntelligenceService(
                workspace,
                FakeGenesisModel(validArchitectureOutput(contextProvider.contentHash)),
                repositoryContextProvider = contextProvider,
                assessmentStore = firstStore,
            ).propose(1, GenesisProposalRequest("Use the existing Architect service."))
            assertEquals(GenesisProposalStatus.CREATED, first.status)

            val secondModel = FakeGenesisModel(validArchitectureOutput(contextProvider.contentHash))
            val second = GenesisIntelligenceService(
                workspace,
                secondModel,
                repositoryContextProvider = contextProvider,
                assessmentStore = FileRepositoryObjectiveAssessmentStore(directory),
            ).propose(1, GenesisProposalRequest("Refine the existing Architect correlation."))

            assertEquals(GenesisProposalStatus.CREATED, second.status)
            assertTrue(secondModel.lastPrompt.contains("priorRepositoryAssessment"))
            assertTrue(secondModel.lastPrompt.contains("Use the existing Architect service."))
            assertEquals(2, FileRepositoryObjectiveAssessmentStore(directory).load().size)
            assertEquals(2, requireNotNull(
                GenesisIntelligenceService(
                    workspace,
                    secondModel,
                    repositoryContextProvider = contextProvider,
                    assessmentStore = FileRepositoryObjectiveAssessmentStore(directory),
                ).latestRepositoryAssessment(1)
            ).assessmentId)
            assertEquals(
                null,
                GenesisIntelligenceService(
                    workspace,
                    secondModel,
                    repositoryContextProvider = FakeGenesisRepositoryContextProvider(
                        reportedRevision = "d".repeat(40),
                    ),
                    assessmentStore = FileRepositoryObjectiveAssessmentStore(directory),
                ).latestRepositoryAssessment(1),
            )
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun repositoryChangeInvalidatesGeneratedProposal() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        createHierarchy(workspace)
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A guided local product.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val contextProvider = FakeGenesisRepositoryContextProvider(current = false)

        val result = GenesisIntelligenceService(
            workspace,
            FakeGenesisModel(validArchitectureOutput(contextProvider.contentHash)),
            repositoryContextProvider = contextProvider,
        ).propose(1, GenesisProposalRequest("Use the existing Architect service."))

        assertEquals(GenesisProposalStatus.REPOSITORY_CHANGED, result.status)
        assertEquals(null, result.proposal)
    }

    @Test
    fun firstWorkingOutcomeIsRevisionPinnedAndCreatedWithoutConversationObjective() = testApplication {
        val workspace = WorkspaceStore()
        val productIntent = "A guided local product with explicit human approval and verifiable delivery evidence. ".repeat(5).trim()
        assertTrue(productIntent.length > 256)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        workspace.commitBatch()
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = productIntent,
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        application { workspaceApi(workspace) }

        val response = client.post("/api/projects/1/genesis/first-outcome") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"title":"Complete the first user journey","baseRevision":2,"baseHash":"${workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision?.hash}"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"CREATED\""))
        assertEquals("Complete the first user journey", workspace.entities().single { it.type == ENTITY_EPIC }.title)
        val review = workspace.snapshot(MESSAGE_READY).projectGenesis.single()
        assertEquals(GENESIS_ADMISSION, review.phase)
        assertEquals(productIntent, review.revision?.productIntent)
        assertEquals(emptyList(), review.revision?.components)
        assertEquals(null, review.revision?.blueprint)
        assertEquals(ProjectGenesisStatus.ADMITTED, workspace.admitProjectGenesis(1).status)
        assertEquals(GENESIS_READY, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    @Test
    fun repositoryFirstIntentAndOutcomeCreateOneAdmissionStateAcrossRestart() = withTempDirectory { directory ->
        val productIntent = "Make repository reality understandable and operable. ".repeat(8).trim()
        assertTrue(productIntent.length > 256)
        val bindings = boundRepository()
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = bindings,
            projectGenesisStore = FileProjectGenesisStore(directory),
            enforceProjectGenesis = true,
        )
        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        first.commitBatch()

        val created = first.createProjectGenesisFirstOutcome(
            projectId = 1,
            title = "Deliver a repository-first project inbox",
            baseRevision = 0,
            baseHash = null,
            confirmedProductIntent = productIntent,
        )
        val duplicate = first.createProjectGenesisFirstOutcome(
            projectId = 1,
            title = "Ignored duplicate title",
            baseRevision = 1,
            baseHash = first.snapshot(MESSAGE_READY).projectGenesis.single().revision?.hash,
            confirmedProductIntent = productIntent,
        )

        assertEquals(ProjectGenesisFirstOutcomeStatus.CREATED, created.status)
        assertEquals(ProjectGenesisFirstOutcomeStatus.WRONG_PHASE, duplicate.status)
        assertEquals(1, first.entities().count { it.type == ENTITY_EPIC })
        assertEquals(GENESIS_ADMISSION, first.snapshot(MESSAGE_READY).projectGenesis.single().phase)

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = bindings,
            projectGenesisStore = FileProjectGenesisStore(directory),
            enforceProjectGenesis = true,
        )
        val genesis = recovered.snapshot(MESSAGE_READY).projectGenesis.single()
        assertEquals(GENESIS_ADMISSION, genesis.phase)
        assertEquals(productIntent, genesis.revision?.productIntent)
        assertEquals(created.outcomeId, genesis.revision?.firstEpicId)
        assertEquals("Deliver a repository-first project inbox", recovered.entities().single { it.type == ENTITY_EPIC }.title)
    }

    @Test
    fun repositoryBaselineAssessmentContinuesAfterOutcomeReachesAdmission() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore(repositoryBindings = boundRepository())
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        workspace.commitBatch()
        assertEquals(
            ProjectGenesisFirstOutcomeStatus.CREATED,
            workspace.createProjectGenesisFirstOutcome(
                projectId = 1,
                title = "Deliver a repository-first project inbox",
                baseRevision = 0,
                baseHash = null,
                confirmedProductIntent = "Make repository reality understandable and operable.",
            ).status,
        )
        val contextProvider = FakeGenesisRepositoryContextProvider()
        val output = """{
            "submission":{"baseRevision":1},
            "repositoryClaims":[{
                "claimId":"genesis-proposals",
                "statement":"The repository defines a Genesis proposal service.",
                "status":"SUPPORTED",
                "support":[{
                    "path":"backend/src/main/kotlin/com/orchard/backend/agent/GenesisIntelligenceService.kt",
                    "contentHash":"${contextProvider.contentHash}",
                    "observation":"The supplied file defines GenesisIntelligenceService."
                }],
                "defeaters":[]
            }]
        }""".trimIndent()
        val model = FakeGenesisModel(output)
        val service = GenesisIntelligenceService(
            workspace,
            model,
            repositoryContextProvider = contextProvider,
        )

        val result = service.assessRepository(1, GenesisProposalRequest("Compile the current repository baseline."))
        val assessment = service.latestRepositoryAssessment(1)

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(GENESIS_ADMISSION, assessment?.phase)
        assertEquals(1, assessment?.genesisRevision)
        assertEquals("c".repeat(40), assessment?.repositoryRevision)
        assertEquals("genesis-proposals", assessment?.claims?.single()?.claimId)
        assertTrue(model.lastPrompt.contains("When allowedAction is ASSESS_REPOSITORY_BASELINE"))
        assertTrue(model.lastPrompt.contains("Do not advance Genesis or propose product authority"))
    }

    @Test
    fun guidedGenesisRecoversAtExactAdmittedState() = withTempDirectory { directory ->
        val first = newWorkspace(directory)
        createHierarchy(first)

        val classified = advance(
            first,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A calm local environment where product intent becomes governed software.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        assertEquals(
            ProjectGenesisStatus.STALE_REVISION,
            first.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    classification = PROJECT_GREENFIELD_LOCAL,
                    productIntent = "A stale competing direction.",
                    baseRevision = 0,
                ),
            ).status,
        )
        val experienced = advance(first, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val architected = advance(first, architectureSubmission(experienced), GENESIS_BLUEPRINT)
        val advanceReference = ConversationCommandReference(41, "a".repeat(64), "ADVANCE_PROJECT_GENESIS")
        val blueprint = advance(first, blueprintSubmission(architected), GENESIS_ADMISSION, advanceReference)
        assertEquals(advanceReference, blueprint.revision?.conversationCommand)

        val admissionReference = ConversationCommandReference(42, "b".repeat(64), "ADMIT_PROJECT_GENESIS")
        val admitted = first.admitProjectGenesis(1, admissionReference)
        assertEquals(ProjectGenesisStatus.ADMITTED, admitted.status)
        val ready = admitted.snapshot.projectGenesis.single()
        assertEquals(GENESIS_READY, ready.phase)
        assertEquals(100, ready.progress)
        assertTrue(ready.revision?.admitted == true)
        assertEquals(admissionReference, ready.revision?.conversationCommand)
        assertTrue(ready.revision?.decisions.orEmpty().all { it.status == DESIGN_STATUS_ADMITTED })

        val recovered = newWorkspace(directory).snapshot(MESSAGE_READY).projectGenesis.single()
        assertEquals(ready, recovered)
        assertEquals(5, FileProjectGenesisStore(directory).loadEvents().size)
        assertTrue(Files.readString(directory.resolve("project-genesis.jsonl")).contains("\"checksum\""))
        assertEquals(blueprint.revision?.hash, recovered.revision?.let { _ -> blueprint.revision?.hash })
    }

    @Test
    fun invalidOrderAndOrganizationalAdmissionFailClosed() {
        val workspace = WorkspaceStore(
            projectGenesisStore = com.orchard.backend.workspace.TransientProjectGenesisStore(),
            enforceProjectGenesis = true,
        )
        createHierarchy(workspace)

        assertEquals(
            ProjectGenesisStatus.INVALID_TRANSITION,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    experience = experience(),
                    baseRevision = 0,
                ),
            ).status,
        )
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_ORGANIZATION_GOVERNED,
                productIntent = "Organization-governed delivery.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        val experienced = advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val architected = advance(workspace, architectureSubmission(experienced), GENESIS_BLUEPRINT)
        val blueprint = advance(workspace, blueprintSubmission(architected), GENESIS_ADMISSION)

        assertNotNull(blueprint.revision)
        assertEquals(
            ProjectGenesisStatus.ORGANIZATION_POLICY_REQUIRED,
            workspace.admitProjectGenesis(1).status,
        )
        assertEquals(GENESIS_ADMISSION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    @Test
    fun directWorkflowApiCannotBypassGenesisAdmission() = testApplication {
        val workspace = WorkspaceStore(enforceProjectGenesis = true)
        createHierarchy(workspace)
        workspace.submitWorkDefinition(4, readyDefinition())
        application { workspaceApi(workspace) }

        val response = client.post("/api/work-items/4/runs")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(WorkflowStartStatus.PROJECT_GENESIS_NOT_ADMITTED, workspace.startWorkflow(4).status)
        assertTrue(workspace.snapshot(MESSAGE_READY).workflowRuns.isEmpty())
    }

    private fun newWorkspace(directory: Path) = WorkspaceStore(
        repository = FileWorkspaceRepository(directory),
        projectGenesisStore = FileProjectGenesisStore(directory),
        enforceProjectGenesis = true,
    )

    private fun createHierarchy(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Guided product genesis", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Experience formation", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Build genesis circuit", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
    }

    private fun boundRepository() = object : RepositoryBindingStore {
        override fun bind(projectId: Int, requestedPath: String) = Unit
        override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = projectIds.associateWith {
            RepositoryView(it, "/workspace/project", available = true)
        }
        override fun resolveHead(projectId: Int): RepositoryHead? = null
    }

    private fun validArchitectureOutput(contentHash: String) = """{
        "submission": {
            "components": [{
                "componentId": "architect",
                "name": "Architect",
                "responsibility": "Form revision-correlated proposals.",
                "repositoryPaths": ["backend/src/main/kotlin/com/orchard/backend/agent"]
            }],
            "decisions": [{
                "decisionId": "ADR-GENESIS-1",
                "title": "Ground proposals in code",
                "context": "The repository already contains an Architect service.",
                "decision": "Use bounded Git-tracked context.",
                "consequences": ["Each proposal carries evidence provenance."],
                "componentIds": ["architect"]
            }],
            "firstEpicId": 2,
            "baseRevision": 2
        },
        "repositoryClaims": [{
            "claimId": "genesis-proposals",
            "statement": "The repository defines a Genesis proposal service.",
            "status": "SUPPORTED",
            "support": [{
                "path": "backend/src/main/kotlin/com/orchard/backend/agent/GenesisIntelligenceService.kt",
                "contentHash": "$contentHash",
                "observation": "The supplied file defines GenesisIntelligenceService."
            }],
            "defeaters": []
        }]
    }""".trimIndent()

    private class FakeGenesisRepositoryContextProvider(
        private val current: Boolean = true,
        private val reportedRevision: String = "c".repeat(40),
        source: String = "class GenesisIntelligenceService",
    ) : GenesisRepositoryContextProvider {
        val contentHash = CodingContextFile(
            "backend/src/main/kotlin/com/orchard/backend/agent/GenesisIntelligenceService.kt",
            source,
        ).contentHash
        val observation = GenesisRepositoryObservation(
            revision = "c".repeat(40),
            context = CodingRepositoryContext(
                files = listOf(CodingContextFile(
                    "backend/src/main/kotlin/com/orchard/backend/agent/GenesisIntelligenceService.kt",
                    source,
                    contentHash,
                )),
                omittedFileCount = 12,
            ),
        )

        override fun observe(repositoryPath: String, query: String): GenesisRepositoryObservation = observation

        override fun currentRevision(repositoryPath: String): String? = reportedRevision

        override fun isCurrent(
            repositoryPath: String,
            query: String,
            observation: GenesisRepositoryObservation,
        ): Boolean = current
    }

    private fun advance(
        workspace: WorkspaceStore,
        submission: ProjectGenesisSubmission,
        expectedPhase: String,
        conversationCommand: ConversationCommandReference? = null,
    ): com.orchard.backend.workspace.ProjectGenesisView {
        assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, submission, conversationCommand).status)
        return workspace.snapshot(MESSAGE_READY).projectGenesis.single().also {
            assertEquals(expectedPhase, it.phase)
        }
    }

    private fun experienceSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            experience = experience(),
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

    private fun experience() = ExperienceContract(
        audience = "Developers shaping local products",
        productPromise = "Conversation continuously resolves into visible, governed project state.",
        primaryJourney = listOf("Describe intent", "Resolve design", "Admit authority", "Observe delivery"),
        interactionPrinciples = listOf("Expose only the next valid decision", "Keep prior authority inspectable"),
        emotionalQualities = listOf("Calm", "Sophisticated", "Continuous"),
        mustNotFeelLike = listOf("A project-management dashboard", "An unrestricted coding chat"),
        accessibility = listOf("Honor reduced motion", "Maintain keyboard access"),
    )

    private fun architectureSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            components = listOf(
                ArchitectureComponent(
                    "architect",
                    "Architect",
                    "Turns conversation into candidate governed transitions.",
                    requirementIds = listOf("GEN-1"),
                    repositoryPaths = listOf("backend/src/main/kotlin"),
                ),
                ArchitectureComponent(
                    "projection",
                    "State projection",
                    "Shows the current authority without becoming an alternate mutation path.",
                    dependsOn = listOf("architect"),
                    requirementIds = listOf("GEN-1"),
                    repositoryPaths = listOf("frontend/src/desktopMain/kotlin"),
                ),
            ),
            decisions = listOf(
                ArchitectureDecision(
                    "ADR-GEN-1",
                    "Use a guided state-driven circuit",
                    context = "Free navigation permits implementation before product design is authoritative.",
                    decision = "The backend determines the next legal genesis phase and the UI projects it.",
                    consequences = listOf("Users may inspect prior state but cannot jump forward."),
                    componentIds = listOf("architect", "projection"),
                    requirementIds = listOf("GEN-1"),
                )
            ),
            firstEpicId = 2,
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

    private fun blueprintSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            blueprint = RepositoryBlueprint(
                rootName = "orchard",
                toolchain = "gradle-kotlin",
                modules = listOf("backend", "frontend"),
                verificationCommands = listOf("./gradlew build --no-daemon"),
                policyPackIds = listOf("builtin-toolchains-v1"),
            ),
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

    private fun intent(
        type: Int,
        title: String,
        projectId: Int = 0,
        epicId: Int = 0,
        storyId: Int = 0,
    ) = DocumentIntent(
        actionTypeId = ACTION_CREATE,
        entityTypeId = type,
        boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
        projectId = projectId,
        epicId = epicId,
        storyId = storyId,
        title = title,
    )

    private fun readyDefinition() = WorkDefinitionSubmission(
        requestedOutcome = "The guided genesis circuit is enforced.",
        currentBehavior = "Implementation can start without genesis authority.",
        requiredBehavior = "Implementation starts only after genesis admission.",
        scope = listOf("Workflow admission"),
        nonGoals = listOf("Remote policy sources"),
        constraints = listOf("Preserve existing design governance"),
        acceptanceCriteria = listOf(AcceptanceCriterion("Bypass is rejected", "Run the genesis tests")),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("orchard-genesis-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeGenesisModel(
        private val output: String,
        private val completionTokens: Int = output.length,
    ) : ModelProvider {
        var lastPrompt: String = ""
            private set

        override suspend fun triage(prompt: String): String = error("unused")
        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override suspend fun executeWorkDefinition(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            lastPrompt = prompt
            return ModelGeneration(output, prompt.length, completionTokens)
        }
    }
}