package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.OllamaGenerateRequest
import com.orchard.backend.vector.OllamaOptions
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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

        assertFalse(workspace.applyIntent(intent(ENTITY_STORY, "Orphan story")))
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Delivery", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Ship it", projectId = 1, epicId = 2)))
        assertFalse(workspace.applyIntent(intent(ENTITY_TASK, "Wrong project", projectId = 99, epicId = 2, storyId = 3)))
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
}