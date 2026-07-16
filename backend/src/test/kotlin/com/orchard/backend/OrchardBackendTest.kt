package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.OllamaGenerateRequest
import com.orchard.backend.vector.OllamaOptions
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceRepository
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RepositoryView
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.RevisionValidation
import com.orchard.backend.workspace.DefaultSystemWorkflow
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFINITION_NEEDS_CLARIFICATION
import com.orchard.backend.workspace.DEFINITION_NEEDS_INVESTIGATION
import com.orchard.backend.workspace.DEFINITION_NEEDS_SPLIT
import com.orchard.backend.workspace.DEFINITION_READY
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkflowStepEngine
import com.orchard.backend.workspace.FACT_WORK_ITEM_EXISTS
import com.orchard.backend.workspace.EpisodeQuery
import com.orchard.backend.workspace.EpisodeRecall
import com.orchard.backend.workspace.WorkflowMemoryStore
import com.orchard.backend.workspace.WorkflowRun
import com.orchard.backend.workspace.WorkflowEvent
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkEpisode
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.ENTITY_BUG
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStoreTest {
    @Test
    fun latestDefinitionRevisionControlsDeliveryAdmission() {
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "a".repeat(40), "main", "", clean = true,
            )
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()

        val ambiguous = workspace.submitWorkDefinition(
            4,
            readyDefinition().copy(unresolvedQuestions = listOf("Should archived records be included?")),
        )
        assertEquals(DEFINITION_NEEDS_CLARIFICATION, ambiguous.snapshot.workDefinitions.single().assessment.status)
        assertEquals(WorkflowStartStatus.WORK_DEFINITION_NOT_READY, workspace.startWorkflow(4).status)

        val ready = workspace.submitWorkDefinition(4, readyDefinition())
        assertEquals(2, ready.snapshot.workDefinitions.single().revision)
        val started = workspace.startWorkflow(4)
        assertEquals(WorkflowStartStatus.CREATED, started.status)
        assertEquals(2L, started.snapshot.workflowRuns.single().workDefinition?.definitionId)
        assertEquals(
            WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED,
            workspace.submitWorkDefinition(4, readyDefinition()).status,
        )
    }

    @Test
    fun emptySnapshotUsesStableResourceOrder() {
        val resources = WorkspaceStore().snapshot(MESSAGE_READY).resources

        assertEquals(listOf("focus", "message"), resources.keys.toList())
        assertEquals("0", resources.getValue("focus").path)
    }

    @Test
    fun workflowRequiresTheCorrectParentHierarchy() {
        val workspace = WorkspaceStore()

        workspace.beginBatch()
        assertFalse(workspace.applyIntent(intent(ENTITY_STORY, "Orphan story")))
        workspace.rollbackBatch()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Delivery", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Ship it", projectId = 1, epicId = 2)))
        workspace.commitBatch()
        workspace.beginBatch()
        assertFalse(workspace.applyIntent(intent(ENTITY_TASK, "Wrong project", projectId = 99, epicId = 2, storyId = 3)))
        workspace.rollbackBatch()
        assertEquals(3, workspace.entityCount)
    }

    @Test
    fun workflowAdmissionRequiresTaskCleanHeadAndSingleRun() {
        val cleanHead = RepositoryHead(1, "/repository", "a".repeat(40), "main", "", clean = true)
        var head = cleanHead
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int): RepositoryHead = head
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()

        assertEquals(WorkflowStartStatus.UNSUPPORTED_ENTITY, workspace.startWorkflow(3).status)
        assertEquals(WorkflowStartStatus.WORK_DEFINITION_NOT_READY, workspace.startWorkflow(4).status)
        assertEquals(
            DEFINITION_READY,
            workspace.submitWorkDefinition(4, readyDefinition()).snapshot.workDefinitions.single().assessment.status,
        )
        head = cleanHead.copy(clean = false)
        assertEquals(WorkflowStartStatus.REPOSITORY_DIRTY, workspace.startWorkflow(4).status)
        head = cleanHead
        assertEquals(WorkflowStartStatus.CREATED, workspace.startWorkflow(4).status)
        assertEquals(WorkflowStartStatus.ALREADY_STARTED, workspace.startWorkflow(4).status)
    }

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
        requestedOutcome = "Complete the bounded task",
        currentBehavior = "The requested capability is absent",
        requiredBehavior = "The requested capability is available",
        scope = listOf("Bounded component"),
        nonGoals = listOf("Unrelated components"),
        constraints = emptyList(),
        acceptanceCriteria = listOf(AcceptanceCriterion("The capability works", "Run its focused test")),
    )
}

class OllamaRequestTest {
    @Test
    fun nonStreamingJsonSettingsAreAlwaysSerialized() {
        val request = OllamaGenerateRequest(
            model = "phi3:mini",
            prompt = "test",
            stream = false,
            format = "json",
            options = OllamaOptions(temperature = 0, seed = 42),
        )

        val payload = Json.encodeToString(request)

        assertTrue("\"stream\":false" in payload)
        assertTrue("\"format\":\"json\"" in payload)
    }
}

class ArchitectServiceTest {
    @Test
    fun preservesExplicitTitleAndContent() = runTest {
        val workspace = WorkspaceStore()
        val provider = StubModelProvider(
            triage = """{"actionTypeId":1,"entityTypeId":1,"intentCount":1,"isBatch":0}""",
            plan = """{"operations":[{"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Model title","content":"Model content"}]}""",
        )

        val result = ArchitectService(workspace, provider).submit(
            ArchitectChatRequest("Create a project\nName: Exact title\nDescription: Exact content")
        )

        assertEquals(200, result.statusCode)
        assertEquals("Exact title", workspace.entityAt(0).title)
        assertEquals("Exact content", workspace.entityAt(0).content)
    }

    @Test
    fun singleIntentIgnoresExtraModelOperations() = runTest {
        val workspace = WorkspaceStore()
        val provider = StubModelProvider(
            triage = """{"actionTypeId":1,"entityTypeId":1,"intentCount":1,"isBatch":0}""",
            plan = """{"operations":[
                {"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Model project","content":""},
                {"action":"CREATE","entity":"EPIC","parentOperationIndex":0,"title":"Invented epic","content":""},
                {"action":"CREATE","entity":"STORY","parentOperationIndex":1,"title":"Invented story","content":""}
            ]}""",
        )

        val result = ArchitectService(workspace, provider).submit(
            ArchitectChatRequest("Create a project named Planner check")
        )

        assertEquals(200, result.statusCode)
        assertEquals(1, workspace.entityCount)
        assertEquals("Planner check", workspace.entityAt(0).title)
    }

    @Test
    fun synthesizesGeneralEpicAndCommitsDependentBatch() = runTest {
        val workspace = WorkspaceStore()
        val provider = StubModelProvider(
            triage = """{"actionTypeId":1,"entityTypeId":1,"intentCount":3,"isBatch":1}""",
            plan = """{"operations":[
                {"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Atlas","content":""},
                {"action":"CREATE","entity":"STORY","parentOperationIndex":-1,"title":"Import data","content":""},
                {"action":"CREATE","entity":"TASK","parentOperationIndex":1,"title":"Parse feed","content":""}
            ]}""",
        )

        val result = ArchitectService(workspace, provider).submit(
            ArchitectChatRequest("Create a project named Atlas, a story named Import data, and a task named Parse feed")
        )

        assertEquals(200, result.statusCode)
        assertEquals(listOf(ENTITY_PROJECT, ENTITY_EPIC, ENTITY_STORY, ENTITY_TASK), (0 until 4).map { workspace.entityAt(it).type })
        assertEquals("General", workspace.entityAt(1).title)
        assertEquals(3, workspace.entityAt(3).parentId)
    }

    @Test
    fun rejectsForwardParentAndRollsBackWholeBatch() = runTest {
        val workspace = WorkspaceStore()
        val provider = StubModelProvider(
            triage = """{"actionTypeId":1,"entityTypeId":1,"intentCount":2,"isBatch":1}""",
            plan = """{"operations":[
                {"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Atlas","content":""},
                {"action":"CREATE","entity":"STORY","parentOperationIndex":2,"title":"Invalid","content":""}
            ]}""",
        )

        val result = ArchitectService(workspace, provider).submit(ArchitectChatRequest("Create a project and story"))

        assertEquals(422, result.statusCode)
        assertEquals(0, workspace.entityCount)
    }

    @Test
    fun enforcesUtf8PromptLimitWithoutCallingModel() = runTest {
        val provider = StubModelProvider("{}", "{}")

        val result = ArchitectService(WorkspaceStore(), provider).submit(ArchitectChatRequest("é".repeat(2047) + "a"))

        assertEquals(400, result.statusCode)
        assertEquals(0, provider.triageCalls)
    }

    @Test
    fun mapsModelFailureToServiceUnavailable() = runTest {
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("offline")
            override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
        }

        val result = ArchitectService(WorkspaceStore(), provider).submit(ArchitectChatRequest("Create a project"))

        assertEquals(503, result.statusCode)
    }

    @Test
    fun mapsMalformedModelJsonToUnprocessableRequest() = runTest {
        val provider = StubModelProvider("not json", "{}")

        val result = ArchitectService(WorkspaceStore(), provider).submit(ArchitectChatRequest("Create a project"))

        assertEquals(422, result.statusCode)
    }

    @Test
    fun rollsBackWhenWorkspaceCannotBePersisted() = runTest {
        val repository = object : WorkspaceRepository {
            override fun load(): List<WorkspaceEntity> = emptyList()
            override fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>) {
                error("disk full")
            }
        }
        val workspace = WorkspaceStore(repository)
        val provider = StubModelProvider(
            triage = """{"actionTypeId":1,"entityTypeId":1,"intentCount":1,"isBatch":0}""",
            plan = """{"operations":[{"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Lost","content":""}]}""",
        )

        val result = ArchitectService(workspace, provider).submit(ArchitectChatRequest("Create a project named Durable"))

        assertEquals(503, result.statusCode)
        assertEquals(0, workspace.entityCount)
        assertEquals("0", result.snapshot.resources.getValue("focus").path)
    }

    @Test
    fun rejectsConcurrentChatWithConflict() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String {
                entered.complete(Unit)
                release.await()
                return """{"actionTypeId":1,"entityTypeId":1,"intentCount":1,"isBatch":0}"""
            }

            override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String =
                """{"operations":[{"action":"CREATE","entity":"PROJECT","parentOperationIndex":-1,"title":"Atlas","content":""}]}"""
        }
        val service = ArchitectService(WorkspaceStore(), provider)
        val first = async { service.submit(ArchitectChatRequest("Create a project")) }
        entered.await()

        val second = service.submit(ArchitectChatRequest("Create another project"))
        release.complete(Unit)

        assertEquals(409, second.statusCode)
        assertEquals(200, first.await().statusCode)
    }

    private class StubModelProvider(
        private val triage: String,
        private val plan: String,
    ) : ModelProvider {
        var triageCalls = 0
            private set

        override suspend fun triage(prompt: String): String {
            triageCalls++
            return triage
        }

        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = plan
    }
}

class WorkspaceApiTest {
    @Test
    fun definitionRouteRecordsAReadySystemWorkflowRevision() = testApplication {
        val workspace = WorkspaceStore()
        createTaskHierarchy(workspace)
        application { workspaceApi(workspace) }

        val response = client.post("/api/work-items/4/definitions") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(definition()))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"id\":\"default-definition-task\""))
        assertTrue(body.contains("\"status\":\"READY\""))
        assertTrue(body.contains(Regex("\\\"hash\\\":\\\"[0-9a-f]{64}\\\"")))
    }

    @Test
    fun systemWorkflowRequiresAnUnambiguousTestableDefinition() {
        val incompleteBug = DefaultSystemWorkflow.assess(
            ENTITY_BUG,
            definition(reproduction = "", regressionCriterion = ""),
        )
        val ambiguousTask = DefaultSystemWorkflow.assess(
            ENTITY_TASK,
            definition(unresolvedQuestions = listOf("Should this include archived projects?")),
        )
        val oversizedTask = DefaultSystemWorkflow.assess(
            ENTITY_TASK,
            definition(proposedSplitTitles = listOf("Add import", "Add export")),
        )
        val readyTask = DefaultSystemWorkflow.assess(ENTITY_TASK, definition())
        val definitionStep = DefaultSystemWorkflow.resolve(ENTITY_TASK).stepDefinitions.single()

        assertEquals(DEFINITION_NEEDS_INVESTIGATION, incompleteBug.status)
        assertEquals(listOf("reproduction", "regressionCriterion"), incompleteBug.missingFields)
        assertEquals(DEFINITION_NEEDS_CLARIFICATION, ambiguousTask.status)
        assertEquals(DEFINITION_NEEDS_SPLIT, oversizedTask.status)
        assertEquals(DEFINITION_READY, readyTask.status)
        assertFalse(WorkflowStepEngine.canStart(definitionStep, emptySet()))
        assertTrue(WorkflowStepEngine.canStart(definitionStep, setOf(FACT_WORK_ITEM_EXISTS)))
    }

    @Test
    fun workspaceGetReturnsJsonEnvelope() = testApplication {
        application { workspaceApi(WorkspaceStore()) }

        val response = client.get("/api/workspace")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun repositoryBindingRouteDistinguishesProjectAndRepositoryFailures() = testApplication {
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) {
                require(requestedPath == "/valid/repository")
            }

            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int): RepositoryHead? = null
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "API")))
        workspace.commitBatch()
        application { workspaceApi(workspace) }

        val bound = client.put("/api/projects/1/repository") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/valid/repository"}""")
        }
        val missing = client.put("/api/projects/99/repository") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/valid/repository"}""")
        }
        val invalid = client.put("/api/projects/1/repository") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/invalid"}""")
        }

        assertEquals(HttpStatusCode.OK, bound.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
    }

    @Test
    fun repositoryBindingRouteReportsStorageFailure() = testApplication {
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = error("disk full")
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int): RepositoryHead? = null
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "API")))
        workspace.commitBatch()
        application { workspaceApi(workspace) }

        val response = client.put("/api/projects/1/repository") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/valid/repository"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun workflowRunRouteCreatesPinnedContext() = testApplication {
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId,
                "/repository",
                "b".repeat(40),
                "main",
                "https://example.test/repository.git",
                clean = true,
            )
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        createTaskHierarchy(workspace)
        workspace.submitWorkDefinition(4, definition())
        application { workspaceApi(workspace) }

        val response = client.post("/api/work-items/4/runs")

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("CONTEXT_READY"))
        assertTrue(response.bodyAsText().contains("task-completion"))
    }

    @Test
    fun workflowRunRouteDoesNotPublishFailedAppend() = testApplication {
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "c".repeat(40), "main", "", clean = true,
            )
        }
        val memory = object : WorkflowMemoryStore {
            override fun loadRuns(): List<WorkflowRun> = emptyList()
            override fun appendRun(run: WorkflowRun) = error("disk full")
            override fun loadEvents(): List<WorkflowEvent> = emptyList()
            override fun appendEvent(event: WorkflowEvent) = error("disk full")
            override fun recallEpisodes(query: EpisodeQuery): List<EpisodeRecall> = emptyList()
            override fun appendEpisode(episode: WorkEpisode) = Unit
            override fun nextEpisodeId(): Long = 1
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings, workflowMemory = memory)
        createTaskHierarchy(workspace)
        workspace.submitWorkDefinition(4, definition())
        application { workspaceApi(workspace) }

        val response = client.post("/api/work-items/4/runs")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(body.contains("CONTEXT_READY"))
        assertTrue(body.contains("status=0"))
    }

    @Test
    fun workflowEventRoutesCompleteAndCancelRuns() = testApplication {
        val targetRevision = "f".repeat(40)
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "a".repeat(40), "main", "", clean = true,
            )
            override fun validateRevision(projectId: Int, baseRevision: String, targetRevision: String) =
                RevisionValidation(targetRevision, changedFromBase = true)
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        createTaskHierarchy(workspace)
        workspace.submitWorkDefinition(4, definition())
        assertEquals(WorkflowStartStatus.CREATED, workspace.startWorkflow(4).status)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Cancelled")))
        workspace.commitBatch()
        workspace.submitWorkDefinition(5, definition())
        assertEquals(WorkflowStartStatus.CREATED, workspace.startWorkflow(5).status)
        application { workspaceApi(workspace) }

        val attempt = client.post("/api/workflow-runs/1/attempts") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"First approach","outcome":"Compiler still failed","diagnosticHash":"${"1".repeat(64)}","successful":false}""")
        }
        val source = client.post("/api/workflow-runs/1/evidence") {
            contentType(ContentType.Application.Json)
            setBody(evidenceJson("SOURCE_DIFF", targetRevision, "", 0, "Source changed"))
        }
        val build = client.post("/api/workflow-runs/1/evidence") {
            contentType(ContentType.Application.Json)
            setBody(evidenceJson("BUILD", targetRevision, "./gradlew build", 0, "Build passed"))
        }
        val test = client.post("/api/workflow-runs/1/evidence") {
            contentType(ContentType.Application.Json)
            setBody(evidenceJson("TEST", targetRevision, "./gradlew test", 0, "Tests passed"))
        }
        val acceptance = client.post("/api/workflow-runs/1/evidence") {
            contentType(ContentType.Application.Json)
            setBody(evidenceJson("ACCEPTANCE", targetRevision, "./gradlew focusedTest", 0, "Acceptance criterion passed"))
        }
        val cancelled = client.post("/api/workflow-runs/2/cancel")
        val completedBody = acceptance.bodyAsText()

        assertEquals(HttpStatusCode.Created, attempt.status)
        assertEquals(HttpStatusCode.Created, source.status)
        assertEquals(HttpStatusCode.Created, build.status)
        assertEquals(HttpStatusCode.Created, test.status)
        assertEquals(HttpStatusCode.Created, acceptance.status)
        assertTrue(completedBody.contains("\"state\":\"DONE\""))
        assertTrue(completedBody.contains("\"signal\":\"COMPLETED\""))
        assertTrue(completedBody.contains("status=3"))
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("CANCELLED"))
        assertTrue(cancelled.bodyAsText().contains("\"signal\":\"CANCELLED\""))
    }

    private fun createTaskHierarchy(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Project")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Epic")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_STORY, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Story")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Task")))
        workspace.commitBatch()
    }

    private fun evidenceJson(kind: String, revision: String, command: String, exitCode: Int, summary: String): String =
        """{"kind":"$kind","revision":"$revision","command":"$command","exitCode":$exitCode,"outputHash":"${"2".repeat(64)}","summary":"$summary","producer":"api-test"}"""

    private fun definition(
        unresolvedQuestions: List<String> = emptyList(),
        proposedSplitTitles: List<String> = emptyList(),
        reproduction: String = "Open the saved order",
        regressionCriterion: String = "The saved order opens without an exception",
    ) = WorkDefinitionSubmission(
        requestedOutcome = "Open saved orders reliably",
        currentBehavior = "Opening a saved order throws an exception",
        requiredBehavior = "Opening a saved order restores its persisted fields",
        scope = listOf("Order loader"),
        nonGoals = listOf("Changing the storage format"),
        constraints = listOf("Existing saved orders remain compatible"),
        acceptanceCriteria = listOf(
            AcceptanceCriterion(
                description = "A saved order opens with all fields restored",
                verification = "Run the saved-order regression test",
            )
        ),
        unresolvedQuestions = unresolvedQuestions,
        proposedSplitTitles = proposedSplitTitles,
        reproduction = reproduction,
        regressionCriterion = regressionCriterion,
    )
}