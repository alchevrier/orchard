package com.orchard.backend.pilot

import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_RUNNING
import com.orchard.backend.analysis.RepositoryAnalysisAttempt
import com.orchard.backend.analysis.RepositoryAnalysisTickStatus
import com.orchard.backend.conversation.ConversationObjectiveRevision
import com.orchard.backend.conversation.OBJECTIVE_ACTIVE
import com.orchard.backend.config.OrchardOperationMode
import com.orchard.backend.resource.MachineCapacitySnapshot
import com.orchard.backend.resource.MachineResourceConfiguration
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.vector.ModelProviderAuditEvent
import com.orchard.backend.workspace.ContextManifest
import com.orchard.backend.workspace.DispatchWorkspaceReservation
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.EvidenceContract
import com.orchard.backend.workspace.EvidenceRequirement
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.ResolvedWorkflow
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspaceApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class PilotServiceTest {
    @Test
    fun `pilot status explains running analysis from deterministic projections`() {
        val revision = "a".repeat(40)
        val run = WorkflowRunView(
            runId = 7,
            createdAt = "2026-09-16T00:00:00Z",
            state = RUN_STATE_CONTEXT_READY,
            context = ContextManifest(
                projectId = 2,
                epicId = 3,
                storyId = 4,
                workItemId = 5,
                workItemType = ENTITY_TASK,
                title = "Implement pilot status",
                content = "Expose compact operator state.",
                workflowId = "default-delivery-task",
                workflowVersion = 4,
                repository = RepositoryHead(2, "/repo", revision, "main", "", clean = true),
                recalledEpisodes = emptyList(),
                hash = "b".repeat(64),
            ),
            workflow = ResolvedWorkflow(
                id = "default-delivery-task",
                version = 4,
                workItemType = ENTITY_TASK,
                steps = listOf("DELIVER_CHANGE"),
                evidenceContract = EvidenceContract(
                    "task-completion",
                    4,
                    listOf(
                        EvidenceRequirement("SOURCE_DIFF", "Source changed."),
                        EvidenceRequirement("TEST", "Tests passed."),
                    ),
                ),
            ),
            evidence = emptyList(),
            attempts = emptyList(),
            decisions = emptyList(),
        )
        val analysisAttempt = RepositoryAnalysisAttempt(
            attemptId = 9,
            runId = 7,
            baseRevision = revision,
            state = ANALYSIS_ATTEMPT_RUNNING,
            resultStatus = RepositoryAnalysisTickStatus.BUSY.name,
            diagnostic = "Repository analysis model execution is running.",
            promptHash = "c".repeat(64),
            recordedAt = "2026-09-16T00:00:01Z",
            executionProfileId = "broad-repository-analysis-v1",
            providerFingerprint = "d".repeat(64),
            inputTokens = 1200,
        )
        val providerEvents = listOf(
            ModelProviderAuditEvent(
                eventId = 1,
                endpointId = "local-ollama",
                bindingId = "ollama:test",
                model = "test-model",
                phase = "REQUEST_STARTED",
                elapsedMillis = 0,
                promptHash = "c".repeat(64),
                promptTokens = 1200,
                contextWindowTokens = 4096,
                maxOutputTokens = 512,
                structured = true,
                recordedAt = "2026-09-16T00:00:02Z",
            ),
            ModelProviderAuditEvent(
                eventId = 2,
                endpointId = "local-ollama",
                bindingId = "ollama:test",
                model = "test-model",
                phase = "RESPONSE_HEADERS_RECEIVED",
                elapsedMillis = 300,
                promptHash = "c".repeat(64),
                promptTokens = 1200,
                contextWindowTokens = 4096,
                maxOutputTokens = 512,
                structured = true,
                diagnostic = "HTTP 200",
                recordedAt = "2026-09-16T00:00:02.300Z",
            ),
        )
        val objective = ConversationObjectiveRevision(
            objectiveId = 3,
            revision = 2,
            conversationId = 1,
            projectId = 2,
            title = "Pilot Orchard",
            outcome = "Operate Orchard without reading implementation code.",
            priority = 90,
            state = OBJECTIVE_ACTIVE,
            sourceMessageId = 1,
            sourceMessageHash = "e".repeat(64),
            actor = "HUMAN",
            createdAt = "2026-09-16T00:00:00Z",
            hash = "f".repeat(64),
        )
        val resources = MachineResourceConfiguration(
            policy = MachineUsagePolicy(),
            capacity = MachineCapacitySnapshot(128, 64, 8, 0.1, "2026-09-16T00:00:03Z"),
            reservedMemoryBytes = 32,
            reservedCpuUnits = 4,
            activeLeases = 1,
        )

        val status = compilePilotStatus(
            WorkspaceSnapshot(emptyMap(), workflowRuns = listOf(run)),
            listOf(analysisAttempt),
            emptyList(),
            providerEvents,
            resources,
            listOf(objective),
            now = Instant.parse("2026-09-16T00:00:11Z"),
        )

        assertEquals("RUNNING", status.state)
        assertEquals(OrchardOperationMode.AUTONOMOUS, status.operationMode)
        assertEquals(3, status.activeObjective?.objectiveId)
        assertEquals(7, status.currentRun?.runId)
        assertEquals(9, status.currentOperation?.attemptId)
        assertEquals(10_000, status.currentOperation?.elapsedMillis)
        assertEquals("RESPONSE_HEADERS_RECEIVED", status.model?.latestPhase)
        assertEquals(listOf("REQUEST_STARTED", "RESPONSE_HEADERS_RECEIVED"), status.model?.providerPhases)
        assertEquals(listOf("SOURCE_DIFF", "TEST"), status.evidence.missing)
        assertEquals("inspect-provider-progress", status.authorizedActions.single().id)
        assertEquals("repeat-repository-analysis", status.disallowedActions.single().id)
        assertEquals(1, status.resources?.activeLeases)
    }

    @Test
    fun `pilot status does not attribute unrelated provider failure to analysis attempt`() {
        val status = compilePilotStatus(
            snapshot = WorkspaceSnapshot(emptyMap(), workflowRuns = listOf(run())),
            analysisAttempts = listOf(runningAttempt()),
            analysisPlans = emptyList(),
            providerEvents = listOf(
                ModelProviderAuditEvent(
                    eventId = 1,
                    endpointId = "local-ollama",
                    bindingId = "ollama:other",
                    model = "other-model",
                    phase = "REQUEST_FAILED",
                    elapsedMillis = 37,
                    promptHash = "f".repeat(64),
                    promptTokens = 500,
                    contextWindowTokens = 4096,
                    diagnostic = "Connection refused",
                ),
            ),
            resources = null,
            objectives = emptyList(),
            now = Instant.parse("2026-09-16T00:00:11Z"),
        )

        assertEquals("broad-repository-analysis-v1", status.model?.profileId)
        assertEquals("d".repeat(64), status.model?.providerFingerprint)
        assertEquals(1200, status.model?.promptTokens)
        assertEquals(null, status.model?.latestPhase)
        assertEquals(null, status.model?.model)
        assertTrue(status.model?.providerPhases.orEmpty().isEmpty())
    }

    @Test
    fun `pilot requires repository intelligence before it authorizes analysis`() {
        val status = compilePilotStatus(
            snapshot = WorkspaceSnapshot(emptyMap(), workflowRuns = listOf(run())),
            analysisAttempts = emptyList(),
            analysisPlans = emptyList(),
            providerEvents = emptyList(),
            resources = null,
            objectives = emptyList(),
            intelligenceReady = { false },
            now = Instant.parse("2026-09-16T00:00:11Z"),
        )

        assertEquals("ensure-repository-intelligence", status.authorizedActions.single().id)
        assertEquals("/api/repository-intelligence/runs/7/ensure", status.authorizedActions.single().path)
    }

    @Test
    fun `pilot reports pinned reservation revision when present`() {
        val reservationRevision = "b".repeat(40)
        val reservedRun = run().copy(
            context = run().context.copy(
                workspaceReservation = DispatchWorkspaceReservation(
                    mode = "RESERVED",
                    owner = "pilot",
                    path = "/repo-reservation",
                    branch = "pilot/work",
                    baseRevision = reservationRevision,
                )
            )
        )

        val status = compilePilotStatus(
            snapshot = WorkspaceSnapshot(emptyMap(), workflowRuns = listOf(reservedRun)),
            analysisAttempts = emptyList(),
            analysisPlans = emptyList(),
            providerEvents = emptyList(),
            resources = null,
            objectives = emptyList(),
            now = Instant.parse("2026-09-16T00:00:11Z"),
        )

        assertEquals(reservationRevision, status.currentRun?.repositoryRevision)
    }

    @Test
    fun `state atlas declares every transition endpoint`() {
        val atlas = PilotStateAtlas.domains

        assertTrue(atlas.size >= 13)
        assertTrue(atlas.any { it.id == "ORCHARD_OPERATION_MODE" })
        assertEquals(atlas.size, atlas.map { it.id }.distinct().size)
        atlas.forEach { domain ->
            assertTrue(domain.states.isNotEmpty(), domain.id)
            assertTrue(domain.transitions.isNotEmpty(), domain.id)
            domain.transitions.forEach { transition ->
                assertTrue(transition.to in domain.states, "${domain.id}: ${transition.to}")
                assertTrue(transition.from == null || transition.from in domain.states, "${domain.id}: ${transition.from}")
                assertTrue(transition.trigger.isNotBlank())
                assertTrue(transition.authority.isNotBlank())
                assertTrue(transition.operatorMeaning.isNotBlank())
            }
        }
    }

    @Test
    fun `pilot routes expose idle status and complete atlas without a model`() = testApplication {
        val service = PilotService(
            WorkspaceStore(),
            operationMode = OrchardOperationMode.PILOTED,
            now = { Instant.parse("2026-09-16T00:00:00Z") },
        )
        application { workspaceApi(WorkspaceStore(), pilotService = service) }

        val statusResponse = client.get("/api/pilot/status")
        val atlasResponse = client.get("/api/pilot/state-atlas")

        assertEquals(HttpStatusCode.OK, statusResponse.status)
        val status = Json.decodeFromString<PilotStatus>(statusResponse.bodyAsText())
        assertEquals("IDLE", status.state)
        assertEquals(OrchardOperationMode.PILOTED, status.operationMode)
        assertNotNull(status.atlasDomains.singleOrNull { it == "WORKFLOW_RUN" })
        assertTrue(status.authorizedActions.isEmpty())
        assertFalse(statusResponse.bodyAsText().contains("prompt content"))

        assertEquals(HttpStatusCode.OK, atlasResponse.status)
        val atlas = Json.decodeFromString<List<PilotStateDomain>>(atlasResponse.bodyAsText())
        assertTrue(atlas.any { it.id == "REPOSITORY_ANALYSIS_ATTEMPT" })
        assertTrue(atlas.any { it.id == "EXTERNAL_OPERATOR_ACTION" })
    }

    private fun run(): WorkflowRunView {
        val revision = "a".repeat(40)
        return WorkflowRunView(
            runId = 7,
            createdAt = "2026-09-16T00:00:00Z",
            state = RUN_STATE_CONTEXT_READY,
            context = ContextManifest(
                projectId = 2,
                epicId = 3,
                storyId = 4,
                workItemId = 5,
                workItemType = ENTITY_TASK,
                title = "Implement pilot status",
                content = "Expose compact operator state.",
                workflowId = "default-delivery-task",
                workflowVersion = 4,
                repository = RepositoryHead(2, "/repo", revision, "main", "", clean = true),
                recalledEpisodes = emptyList(),
                hash = "b".repeat(64),
            ),
            workflow = ResolvedWorkflow(
                id = "default-delivery-task",
                version = 4,
                workItemType = ENTITY_TASK,
                steps = listOf("DELIVER_CHANGE"),
                evidenceContract = EvidenceContract("task-completion", 4, emptyList()),
            ),
            evidence = emptyList(),
            attempts = emptyList(),
            decisions = emptyList(),
        )
    }

    private fun runningAttempt() = RepositoryAnalysisAttempt(
        attemptId = 9,
        runId = 7,
        baseRevision = "a".repeat(40),
        state = ANALYSIS_ATTEMPT_RUNNING,
        resultStatus = RepositoryAnalysisTickStatus.BUSY.name,
        diagnostic = "Repository analysis model execution is running.",
        promptHash = "c".repeat(64),
        recordedAt = "2026-09-16T00:00:01Z",
        executionProfileId = "broad-repository-analysis-v1",
        providerFingerprint = "d".repeat(64),
        inputTokens = 1200,
    )
}
