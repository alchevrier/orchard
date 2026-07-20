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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopNetworkClientTest {
    @Test
    fun requestsWorkItemPolicyThroughModuleAncestry() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("backend/standards", request.url.parameters["modulePath"])
            assertEquals("42", request.url.parameters["workItemId"])
            respond(
                content = """{"overlays":[],"exceptions":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.getStandardsPolicy(1, "backend/standards", 42)

        assertTrue(result.overlays.isEmpty())
        client.close()
    }

    @Test
    fun admitsExactStandardsExceptionProposal() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/standards-exception-proposals/7/admission", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"grantor\":\"TECH_LEAD\""))
            assertTrue(body.contains("\"expiresAt\":\"2026-08-01T00:00:00Z\""))
            respond(
                content = """{
                    "status":"RECORDED",
                    "admission":{
                        "admissionId":3,
                        "proposalId":7,
                        "proposalHash":"${"a".repeat(64)}",
                        "grantor":"TECH_LEAD",
                        "activeFrom":"2026-07-20T00:00:00Z",
                        "expiresAt":"2026-08-01T00:00:00Z",
                        "admittedAt":"2026-07-20T00:00:00Z",
                        "hash":"${"b".repeat(64)}"
                    }
                }""".trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.admitStandardsException(
            7,
            StandardsExceptionAdmissionSubmissionRequest(
                "TECH_LEAD", "2026-07-20T00:00:00Z", "2026-08-01T00:00:00Z",
            ),
        )

        assertEquals("RECORDED", result.status)
        assertEquals(3L, result.admission?.admissionId)
        client.close()
    }

    @Test
    fun requestsNonAuthoritativeGenesisProposal() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/projects/4/genesis/proposal", request.url.toString())
            assertTrue(request.body.toByteArray().decodeToString().contains("Make it feel calm and continuous"))
            respond(
                content = """{
                    "projectId":4,
                    "phase":"EXPERIENCE",
                    "baseRevision":2,
                    "baseHash":"${"a".repeat(64)}",
                    "submission":{
                        "experience":{
                            "audience":"Local developers",
                            "productPromise":"Intent becomes visible state.",
                            "primaryJourney":["Describe","Resolve","Admit"],
                            "interactionPrinciples":["Show the next valid decision"],
                            "emotionalQualities":["Calm"],
                            "mustNotFeelLike":["A dashboard"]
                        },
                        "baseRevision":2,
                        "baseHash":"${"a".repeat(64)}"
                    },
                    "observations":["Continuity is a product constraint."],
                    "unresolvedQuestions":[],
                    "repositoryRevision":"${"c".repeat(40)}",
                    "repositoryEvidence":[{
                        "path":"frontend/src/desktopMain/kotlin/App.kt",
                        "contentHash":"${"d".repeat(64)}"
                    }],
                    "omittedRepositoryFileCount":7,
                    "model":"phi3:mini"
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val proposal = client.proposeProjectGenesis(4, "Make it feel calm and continuous")

        assertEquals("EXPERIENCE", proposal.phase)
        assertEquals("Calm", proposal.submission.experience?.emotionalQualities?.single())
        assertEquals("c".repeat(40), proposal.repositoryRevision)
        assertEquals("frontend/src/desktopMain/kotlin/App.kt", proposal.repositoryEvidence.single().path)
        assertEquals(7, proposal.omittedRepositoryFileCount)
        client.close()
    }

    @Test
    fun surfacesActionableGenesisProposalFailure() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{
                    "status":"INVALID_OUTPUT",
                    "diagnostic":"The Architect did not return valid structured JSON for experience. Revise the description and retry.",
                    "retryable":true
                }""".trimIndent(),
                status = HttpStatusCode.UnprocessableEntity,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val error = assertFailsWith<GenesisProposalFailureException> {
            client.proposeProjectGenesis(4, "Describe the experience")
        }

        assertTrue(error.message.orEmpty().contains("Revise the description and retry"))
        assertTrue(error.canRefinePrompt)
        assertEquals("INVALID_OUTPUT", error.failure.status)
        client.close()
    }

    @Test
    fun createsRevisionPinnedFirstWorkingOutcomeDirectly() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/projects/4/genesis/first-outcome", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"title\":\"Complete the first user journey\""))
            assertTrue(body.contains("\"baseRevision\":2"))
            assertTrue(body.contains("\"baseHash\":\"${"a".repeat(64)}\""))
            respond(
                content = """{"status":"CREATED","outcomeId":9,"diagnostic":"First working outcome created."}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.createProjectGenesisFirstOutcome(
            4,
            "Complete the first user journey",
            2,
            "a".repeat(64),
        )

        assertEquals("CREATED", result.status)
        assertEquals(9, result.outcomeId)
        client.close()
    }

    @Test
    fun advancesGenesisWithPinnedRevisionAndDecodesProjection() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/projects/4/genesis", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"classification\":\"GREENFIELD_LOCAL\""))
            assertTrue(body.contains("\"baseRevision\":2"))
            assertTrue(body.contains("\"baseHash\":\"${"a".repeat(64)}\""))
            respond(
                content = """{
                    "resources":{},
                    "projectGenesis":[{
                        "projectId":4,
                        "phase":"EXPERIENCE",
                        "revision":{
                            "genesisId":3,
                            "projectId":4,
                            "revision":3,
                            "phase":"EXPERIENCE",
                            "classification":"GREENFIELD_LOCAL",
                            "productIntent":"A guided local product.",
                            "actor":"HUMAN",
                            "createdAt":"2026-07-18T00:00:00Z",
                            "hash":"${"b".repeat(64)}"
                        },
                        "progress":20,
                        "nextQuestion":"Who is this for?",
                        "permittedAction":"ADVANCE"
                    }]
                }""".trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.advanceProjectGenesis(
            4,
            ProjectGenesisSubmissionRequest(
                classification = "GREENFIELD_LOCAL",
                productIntent = "A guided local product.",
                baseRevision = 2,
                baseHash = "a".repeat(64),
            ),
        )

        assertEquals("EXPERIENCE", result.projectGenesis.single().phase)
        assertEquals(3, result.projectGenesis.single().revision?.revision)
        client.close()
    }

    @Test
    fun acceptsTypedStagedDeliveryCircuit() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/staged-plans", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"executionWorkflowId\":\"contract-v1\""))
            assertTrue(body.contains("\"dependsOn\":[\"api\"]"))
            assertTrue(body.contains("\"sourceProposal\":{\"proposalId\":7"))
            respond(
                content = """{
                    "resources":{},
                    "stagedPlans":[],
                    "circuitDispatches":[{
                        "dispatch":{
                            "dispatchId":1,
                            "planId":3,
                            "planRevision":1,
                            "planHash":"${"a".repeat(64)}",
                            "scopeId":3,
                            "stageId":"build",
                            "nodeId":"screen",
                            "workItemId":5,
                            "priority":2001,
                            "integrationOwner":false,
                            "state":"PENDING",
                            "createdAt":"2026-07-17T00:00:00Z",
                            "hash":"${"b".repeat(64)}"
                        },
                        "state":"RUNNING",
                        "workflowRunId":9
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
        val request = StagedDeliveryPlanSubmissionRequest(
            3,
            "Screen circuit",
            listOf(
                StagedPlanStageSubmissionRequest(
                    "contract", "Contract", "contract-v1",
                    nodes = listOf(StagedPlanNodeSubmissionRequest("api", 4)),
                ),
                StagedPlanStageSubmissionRequest(
                    "build", "Build", "build-v1",
                    nodes = listOf(StagedPlanNodeSubmissionRequest("screen", 5, dependsOn = listOf("api"))),
                ),
            ),
            sourceProposal = CircuitProposalReferenceRequest(7, "a".repeat(64)),
        )

        val response = client.acceptStagedPlan(request)

        assertTrue(response.stagedPlans.isEmpty())
        assertEquals(2001, response.circuitDispatches.single().dispatch.priority)
        assertEquals("RUNNING", response.circuitDispatches.single().state)
        assertEquals(9L, response.circuitDispatches.single().workflowRunId)
        client.close()
    }

    @Test
    fun generatesAndDecodesCircuitProposal() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/staged-plan-proposals/3/generate", request.url.toString())
            respond(
                content = """{
                    "resources":{},
                    "circuitProposals":[{
                        "proposal":{
                            "proposalId":1,
                            "scopeId":3,
                            "revision":1,
                            "actor":"LOCAL_LLM",
                            "content":{
                                "plan":{
                                    "scopeId":3,
                                    "title":"Generated circuit",
                                    "stages":[{
                                        "stageId":"delivery",
                                        "title":"Delivery",
                                        "executionWorkflowId":"sequential-delivery-v1",
                                        "executionWorkflowVersion":1,
                                        "nodes":[{"nodeId":"task","workItemId":4}]
                                    }]
                                },
                                "observations":["One task is in scope."],
                                "assumptions":[]
                            },
                            "provenance":{
                                "executor":"profile:bounded-circuit-synthesis-v1",
                                "model":"phi3:mini",
                                "executionProfileId":"bounded-circuit-synthesis-v1",
                                "bindingFingerprint":"${"a".repeat(64)}",
                                "promptVersion":1,
                                "promptHash":"${"b".repeat(64)}",
                                "contextHash":"${"c".repeat(64)}",
                                "outputHash":"${"d".repeat(64)}",
                                "executionId":1
                            },
                            "createdAt":"2026-07-17T00:00:00Z",
                            "hash":"${"e".repeat(64)}"
                        }
                    }]
                }""".trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val proposal = client.generateCircuitProposal(3).circuitProposals.single().proposal

        assertEquals("Generated circuit", proposal.content.plan.title)
        assertEquals("bounded-circuit-synthesis-v1", proposal.provenance.executionProfileId)
        client.close()
    }

    @Test
    fun updatesMachineUsagePolicyAndDecodesLiveCapacity() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("http://127.0.0.1:8085/api/machine-resources/policy", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"capacityPercent\":20"))
            assertTrue(body.contains("\"minimumFreeMemoryBytes\":2147483648"))
            respond(
                content = """{
                    "policy":{"capacityPercent":20,"minimumFreeMemoryBytes":2147483648,"maxConcurrentModelExecutions":2},
                    "capacity":{"totalMemoryBytes":34359738368,"availableMemoryBytes":17179869184,"logicalProcessors":8,"systemCpuLoad":0.25,"observedAt":"2026-07-16T00:00:00Z"},
                    "reservedMemoryBytes":0,"reservedCpuUnits":0,"activeLeases":0
                }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.updateMachineUsagePolicy(MachineUsagePolicyRequest(20, 2_147_483_648, 2))

        assertEquals(20, result.policy.capacityPercent)
        assertEquals(17_179_869_184, result.capacity.availableMemoryBytes)
        client.close()
    }

    @Test
    fun updatesModelProfileApertureAndPreferredBinding() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                "http://127.0.0.1:8085/api/model-profiles/bounded-definition-reasoning-v1",
                request.url.toString(),
            )
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"inputBudgetTokens\":7000"))
            assertTrue(body.contains("\"outputBudgetTokens\":1500"))
            assertTrue(body.contains("\"preferredBindingId\":\"local-binding\""))
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val result = client.updateModelProfile(
            ModelProfileOverrideRequest("bounded-definition-reasoning-v1", 7_000, 1_500, "local-binding")
        )

        assertTrue(result.isEmpty())
        client.close()
    }

    @Test
    fun decodesDerivedModelCapabilityProfile() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{
                    "resources":{},
                    "modelProfiles":[{
                        "executionProfileId":"bounded-definition-reasoning-v1",
                        "executionProfileVersion":1,
                        "inputBudgetTokens":12000,
                        "outputBudgetTokens":2000,
                        "binding":{"bindingId":"ollama:phi3:mini","provider":"ollama","model":"phi3:mini","contextWindowTokens":131072,"capabilities":["STRICT_JSON"]},
                        "bindingFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "sampleCount":4,
                        "schemaValidityRate":0.75,
                        "acceptedUnchangedCount":1,
                        "acceptedAfterEditCount":1,
                        "revisionRequestedCount":2,
                        "averageHumanRevisionFields":1.0,
                        "medianLatencyMillis":850,
                        "confidence":0.2857142857
                    }]
                }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val profile = client.getWorkspace().modelProfiles.single()

        assertEquals("phi3:mini", profile.binding.model)
        assertEquals(4, profile.sampleCount)
        assertEquals(2, profile.revisionRequestedCount)
        client.close()
    }

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

    @Test
    fun submitsStructuredWorkDefinition() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/work-items/4/definitions", request.url.toString())
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("\"requestedOutcome\":\"Open saved orders\""))
            assertTrue(body.contains("\"verification\":\"Run the regression test\""))
            respond(
                content = """{"resources":{},"workDefinitions":[]}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        client.submitWorkDefinition(
            4,
            WorkDefinitionSubmissionRequest(
                requestedOutcome = "Open saved orders",
                currentBehavior = "Opening fails",
                requiredBehavior = "Opening restores all fields",
                scope = listOf("Order loader"),
                nonGoals = listOf("Storage migration"),
                constraints = emptyList(),
                acceptanceCriteria = listOf(
                    AcceptanceCriterionRequest("Saved orders open", "Run the regression test")
                ),
            ),
        )

        client.close()
    }

    @Test
    fun sendsHumanFeedbackToDefinitionProposal() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/definition-proposals/7/feedback", request.url.toString())
            assertTrue(request.body.toByteArray().decodeToString().contains("Preserve the interaction model"))
            respond(
                content = """{"resources":{},"definitionProposals":[]}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        client.submitDefinitionFeedback(7, "Preserve the interaction model")

        client.close()
    }

    @Test
    fun restoresConversationAfterEventCursor() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("7", request.url.parameters["afterEventId"])
            respond(
                content = """{
                    "conversation":{"conversationId":3,"title":"Orchard work","actor":"HUMAN","createdAt":"2026-06-21T00:00:00Z","hash":"${"a".repeat(64)}"},
                    "messages":[],"objectives":[],"commands":[],"activities":[],"events":[],"lastEventId":9
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val projection = client.getConversation(3, 7)

        assertEquals(9, projection.lastEventId)
        assertEquals("Orchard work", projection.conversation.title)
        client.close()
    }

    @Test
    fun admitsExactConversationCommandHash() = runBlocking {
        val commandHash = "b".repeat(64)
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:8085/api/conversation-commands/11/admission", request.url.toString())
            assertTrue(request.body.toByteArray().decodeToString().contains("\"commandHash\":\"$commandHash\""))
            respond(
                content = """{"status":"RECORDED","diagnostic":""}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = DesktopNetworkClient(httpClient)

        val response = client.admitConversationCommand(11, commandHash)

        assertEquals("RECORDED", response.status)
        client.close()
    }
}