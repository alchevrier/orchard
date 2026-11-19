package com.orchard.backend.conversation

import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalOutput
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.company.CompanyCircuitService
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.config.OrchardOperationMode
import com.orchard.backend.pilot.PilotService
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.ArchitectureComponent
import com.orchard.backend.workspace.ArchitectureDecision
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspaceApi
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProjectSetupWorkflowTest {
    @Test
    fun `persisted setup advances through exact admissions to company delivery without model routing`() = runTest {
        Fixture().use { fixture ->
            val expected = listOf(
                ProjectSetupStep.CLASSIFY, ProjectSetupStep.DEFINE_EXPERIENCE,
                ProjectSetupStep.CREATE_FIRST_OUTCOME, ProjectSetupStep.DEFINE_ARCHITECTURE,
                ProjectSetupStep.DEFINE_BLUEPRINT, ProjectSetupStep.ADMIT_GENESIS, ProjectSetupStep.START_DELIVERY,
            )
            for (step in expected) {
                assertEquals(step, fixture.state().step)
                val before = fixture.workspace.snapshot(MESSAGE_READY)
                val priorCalls = fixture.model.phases.size
                val proposed = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
                assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status, proposed.diagnostic)
                val command = requireNotNull(proposed.projection).commands.last().proposal
                assertEquals(step.capabilityId, command.capabilityId)
                assertEquals(before.projectGenesis, fixture.workspace.snapshot(MESSAGE_READY).projectGenesis)
                assertEquals(before.resources, fixture.workspace.snapshot(MESSAGE_READY).resources)
                assertEquals(priorCalls + if (step.modelBacked) 1 else 0, fixture.model.phases.size)

                if (step == ProjectSetupStep.DEFINE_EXPERIENCE) {
                    fixture.reload()
                    assertEquals(ProjectSetupStatus.AWAITING_ADMISSION, fixture.state().status)
                    val repeated = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
                    assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, repeated.status)
                    assertEquals(command, requireNotNull(repeated.projection).commands.last().proposal)
                    assertEquals(priorCalls + 1, fixture.model.phases.size)
                }
                val admitted = fixture.conductor.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
                assertEquals(ConversationOperationStatus.RECORDED, admitted.status, admitted.diagnostic)
            }
            assertEquals(ProjectSetupStatus.HANDED_OFF, fixture.state().status)
            assertEquals(listOf("EXPERIENCE", "ARCHITECTURE", "BLUEPRINT"), fixture.model.phases)
            assertEquals(0, fixture.interpretations)
            val snapshot = fixture.workspace.snapshot(MESSAGE_READY)
            assertTrue(snapshot.repositories.getValue(1).available)
            assertEquals(1, snapshot.workflowRuns.size)
            assertEquals(1, fixture.workspace.entities().count { it.type == ENTITY_EPIC })
            assertEquals(ConversationOperationStatus.ALREADY_RECORDED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertEquals(1, fixture.workspace.snapshot(MESSAGE_READY).workflowRuns.size)
        }
    }

    @Test
    fun `stale first outcome cannot create a duplicate or block a changed setup basis`() = runTest {
        Fixture().use { fixture ->
            repeat(2) { fixture.advance() }
            val proposed = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
            val command = requireNotNull(proposed.projection).commands.last().proposal
            fixture.workspace.beginBatch()
            assertTrue(fixture.workspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Concurrent outcome",
            )))
            fixture.workspace.commitBatch()

            val admitted = fixture.conductor.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
            assertEquals(ConversationOperationStatus.REJECTED, admitted.status)
            assertEquals(1, fixture.workspace.entities().count { it.type == ENTITY_EPIC })
            assertEquals(ProjectSetupStatus.READY, fixture.state().status)
            assertEquals(ProjectSetupStep.DEFINE_ARCHITECTURE, fixture.state().step)
            val modelCalls = fixture.model.phases.size
            assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertEquals(modelCalls + 1, fixture.model.phases.size)
        }
    }

    @Test
    fun `failed startup blocks equivalent continuation instead of creating retry authority`() = runTest {
        Fixture().use { fixture ->
            repeat(6) { fixture.advance() }
            Files.writeString(fixture.directory.resolve("projects"), "A file blocks the repository directory.")
            val proposed = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
            val command = requireNotNull(proposed.projection).commands.last().proposal
            val failed = fixture.conductor.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
            assertEquals(ConversationOperationStatus.REJECTED, failed.status)
            assertEquals(ProjectSetupStatus.BLOCKED, fixture.state().status)
            assertEquals(command.commandId, fixture.state().commandId)
            assertEquals(ConversationOperationStatus.REJECTED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertTrue(fixture.workspace.snapshot(MESSAGE_READY).workflowRuns.isEmpty())
            assertEquals(3, fixture.model.phases.size)
        }
    }

    @Test
    fun `unsupported greenfield toolchain never becomes an admissible blueprint`() = runTest {
        Fixture().use { fixture ->
            fixture.model.toolchain = "maven"
            repeat(4) { fixture.advance() }
            val before = fixture.workspace.snapshot(MESSAGE_READY).projectGenesis
            val result = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
            assertEquals(ConversationOperationStatus.REJECTED, result.status)
            assertTrue(result.diagnostic.contains("Unsupported greenfield"), result.diagnostic)
            assertEquals(before, fixture.workspace.snapshot(MESSAGE_READY).projectGenesis)
            assertEquals(4, requireNotNull(result.projection).commands.size)
            assertEquals(3, fixture.model.phases.size)
        }
    }

    @Test
    fun `invalid Architect output leaves authority unchanged and paused setup never invokes a model`() = runTest {
        Fixture().use { fixture ->
            fixture.advance()
            val before = fixture.workspace.snapshot(MESSAGE_READY).projectGenesis
            fixture.model.invalid = true
            val rejected = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
            assertEquals(ConversationOperationStatus.REJECTED, rejected.status)
            assertTrue(rejected.diagnostic.contains("INVALID_OUTPUT"), rejected.diagnostic)
            assertEquals(before, fixture.workspace.snapshot(MESSAGE_READY).projectGenesis)
            assertEquals(2, fixture.model.phases.size)
            val projection = requireNotNull(fixture.conductor.projection(fixture.conversationId))
            assertEquals(1, projection.commands.size)
            val source = projection.messages.first()
            fixture.conductor.controlObjective(fixture.objectiveId,
                ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_PAUSE, source.messageId, source.hash))
            val paused = fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN")
            assertEquals(ConversationOperationStatus.REJECTED, paused.status)
            assertTrue(paused.diagnostic.contains("PAUSED"), paused.diagnostic)
            assertEquals(2, fixture.model.phases.size)
        }
    }

    @Test
    fun `exhausted output recovery survives restart and requires a changed basis`() = runTest {
        Fixture().use { fixture ->
            fixture.advance()
            fixture.model.invalid = true
            assertEquals(ConversationOperationStatus.REJECTED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertEquals(2, fixture.model.phases.size)
            assertEquals(ProjectSetupStatus.BLOCKED, fixture.state().status)
            fixture.reload()
            assertEquals(ProjectSetupStatus.BLOCKED, fixture.state().status)
            assertEquals(ConversationOperationStatus.REJECTED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertEquals(2, fixture.model.phases.size)
            fixture.model.invalid = false
            fixture.model.bindingRevision++
            assertEquals(ProjectSetupStatus.READY, fixture.state().status)
            assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED,
                fixture.conductor.continueProjectSetup(fixture.objectiveId, "HUMAN").status)
            assertEquals(3, fixture.model.phases.size)
        }
    }

    @Test
    fun `public continuation and pilot expose the exact deterministic transition and admission`() = testApplication {
        Fixture().use { fixture ->
            val pilot = PilotService(fixture.workspace, conversationConductor = fixture.conductor,
                operationMode = OrchardOperationMode.PILOTED, providerEvents = { emptyList() })
            application { workspaceApi(fixture.workspace, conversationConductor = fixture.conductor) }
            val action = pilot.status().authorizedActions.single()
            assertEquals("/api/conversation-objectives/${fixture.objectiveId}/continue", action.path)
            assertEquals("DETERMINISTIC", action.costClass)
            val response = client.post(action.path) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(action.requestBody))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            val stateResponse = client.get("/api/conversation-objectives/${fixture.objectiveId}/setup")
            val state = Json.decodeFromString<ProjectSetupState>(stateResponse.bodyAsText())
            assertEquals(ProjectSetupStatus.AWAITING_ADMISSION, state.status)
            val admission = pilot.status().authorizedActions.single { it.id == "admit-project-setup" }
            assertEquals("/api/conversation-commands/${state.commandId}/admission", admission.path)
            assertEquals(state.commandHash, admission.requestBody["commandHash"])
            assertEquals("HUMAN_AUTHORITY", admission.costClass)
            val rejection = pilot.status().authorizedActions.single { it.id == "reject-project-setup" }
            assertEquals("/api/conversation-commands/${state.commandId}/rejection", rejection.path)
            assertEquals(admission.requestBody, rejection.requestBody)
            assertEquals(0, fixture.model.phases.size)
        }
    }

    private class Fixture : AutoCloseable {
        val directory = createTempDirectory("orchard-setup-fsm-")
        val model = Architect()
        var interpretations = 0
        lateinit var workspace: WorkspaceStore
        lateinit var conductor: ConversationConductorService
        lateinit var store: FileConversationStore
        val conversationId: Long
        val objectiveId: Long

        init {
            reload()
            workspace.beginBatch()
            assertTrue(workspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Local content service", content = REQUIREMENT,
            )))
            workspace.commitBatch()
            conversationId = requireNotNull(conductor.create(CreateConversationRequest("FSM")).projection).conversation.conversationId
            val source = newConversationMessage(ConversationMessage(
                messageId = 1, conversationId = conversationId, sequence = 1, clientMessageId = "original-goal",
                role = MESSAGE_ROLE_USER, content = REQUIREMENT, actor = "HUMAN",
                createdAt = "2026-06-21T00:00:00Z", hash = "",
            ))
            store.appendMessage(source)
            val objective = newConversationObjective(ConversationObjectiveRevision(
                objectiveId = 1, revision = 1, conversationId = conversationId, projectId = 1,
                title = "Deliver local content", outcome = REQUIREMENT, constraints = emptyList(),
                priority = 100, dependencyObjectiveIds = emptyList(), state = OBJECTIVE_AWAITING_ADMISSION,
                sourceMessageId = source.messageId, sourceMessageHash = source.hash, actor = "MODEL_PROPOSAL",
                createdAt = "2026-06-21T00:00:00Z", hash = "",
            ))
            store.appendObjective(objective)
            objectiveId = objective.objectiveId
            assertEquals(ConversationOperationStatus.RECORDED, conductor.controlObjective(objectiveId,
                ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash)).status)
        }

        fun reload() {
            val bindings = FileRepositoryBindingStore(directory)
            workspace = WorkspaceStore(
                repository = FileWorkspaceRepository(directory), repositoryBindings = bindings,
                projectGenesisStore = FileProjectGenesisStore(directory), enforceProjectGenesis = true,
            )
            val company = CompanyControlService(workspace, listOf(model), repositories = bindings)
            val circuit = CompanyCircuitService(workspace, company, directory.resolve("projects"))
            store = FileConversationStore(directory)
            conductor = ConversationConductorService(
                store,
                ConversationInterpreter { _, _ ->
                    interpretations++
                    error("The FSM must not ask a model to select a workflow action.")
                },
                defaultConversationCapabilities(workspace, companyControl = company, companyCircuit = circuit),
                projectSetup = ProjectSetupWorkflow(workspace, GenesisIntelligenceService(workspace, model)),
            )
        }

        fun state() = requireNotNull(conductor.projectSetupState(objectiveId))

        suspend fun advance() {
            val proposed = conductor.continueProjectSetup(objectiveId, "HUMAN")
            assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status, proposed.diagnostic)
            val command = requireNotNull(proposed.projection).commands.last().proposal
            assertEquals(ConversationOperationStatus.RECORDED,
                conductor.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash)).status)
        }

        override fun close() { directory.toFile().deleteRecursively() }
    }

    private class Architect : ModelProvider {
        val phases = mutableListOf<String>()
        var invalid = false
        var bindingRevision = 0
        var toolchain = "gradle"
        override fun bindingProfile() = ModelBindingProfile(
            "test:setup", "test-local", "setup-architect", 131_072, setOf(MODEL_CAPABILITY_STRICT_JSON),
            configuration = mapOf("revision" to bindingRevision.toString(), "protocol" to "OLLAMA_NATIVE"),
        )
        override suspend fun triage(prompt: String): String = error("unused")
        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
        override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration {
            val envelope = Json.parseToJsonElement(prompt.substringAfter("Authoritative genesis envelope:\n")).jsonObject
            val phase = envelope.getValue("current").jsonObject.getValue("phase").jsonPrimitive.content
            assertEquals(REQUIREMENT, envelope.getValue("userMessage").jsonPrimitive.content)
            assertTrue(envelope.getValue("greenfieldToolchainConstraint").jsonPrimitive.content.contains("Maven"))
            assertTrue(envelope.getValue("supportedGreenfieldToolchains").jsonArray.any { it.jsonPrimitive.content == "gradle" })
            phases += phase
            if (invalid) return ModelGeneration("{}", 64, 1)
            val submission = when (phase) {
                "EXPERIENCE" -> ProjectGenesisSubmission(baseRevision = 0, experience = ExperienceContract(
                    audience = "Developers", productPromise = "Look up persisted content.",
                    primaryJourney = listOf("Start", "Fetch content"), interactionPrinciples = listOf("Predictable"),
                    emotionalQualities = listOf("Confident"), mustNotFeelLike = listOf("An untested stub"),
                ))
                "ARCHITECTURE" -> ProjectGenesisSubmission(
                    baseRevision = 0,
                    components = listOf(ArchitectureComponent("app", "Application", "Deliver the content service.",
                        repositoryPaths = listOf("build.gradle.kts"))),
                    decisions = listOf(ArchitectureDecision("local", "Local delivery", context = "Local project.",
                        decision = "Remain local.", componentIds = listOf("app"))),
                    firstEpicId = envelope.getValue("availableFirstEpics").jsonArray.single().jsonObject
                        .getValue("epicId").jsonPrimitive.content.toInt(),
                )
                "BLUEPRINT" -> ProjectGenesisSubmission(baseRevision = 0, blueprint = RepositoryBlueprint(
                    rootName = "local-content", toolchain = toolchain, modules = listOf("src"),
                    verificationCommands = listOf("./gradlew test --no-daemon"), policyPackIds = listOf("orchard.default-toolchains"),
                ))
                else -> error("Unexpected model-backed phase $phase")
            }
            return ModelGeneration(Json.encodeToString(GenesisProposalOutput(submission)), 64, 64)
        }
    }

    private companion object {
        const val REQUIREMENT = "Deliver a local persisted content lookup service with unit and integration tests."
    }
}
