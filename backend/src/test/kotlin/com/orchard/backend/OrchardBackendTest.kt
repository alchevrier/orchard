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
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
}