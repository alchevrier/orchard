package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.FileProjectArchiveStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ProjectArchive
import com.orchard.backend.workspace.ProjectArchiveStatus
import com.orchard.backend.workspace.ProjectArchiveStore
import com.orchard.backend.workspace.ProjectGenesisFirstOutcomeStatus
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProjectArchiveTest {
    @Test
    fun `archive hides a hierarchy without deleting it and survives restart`() {
        val directory = createTempDirectory("orchard-project-archive-")
        val store = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            projectArchiveStore = FileProjectArchiveStore(directory),
        )
        store.beginBatch()
        assertTrue(store.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Old")))
        assertTrue(store.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "History")))
        assertTrue(store.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Current")))
        store.commitBatch()

        assertEquals(ProjectArchiveStatus.ARCHIVED, store.archiveProject(1).status)
        assertEquals(ProjectArchiveStatus.ALREADY_ARCHIVED, store.archiveProject(1).status)
        val restored = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            projectArchiveStore = FileProjectArchiveStore(directory),
        )
        assertTrue(restored.isProjectArchived(1))
        assertEquals(3, restored.entityCount)
        val active = restored.snapshot(MESSAGE_READY)
        assertFalse("entity-1" in active.resources)
        assertFalse("entity-2" in active.resources)
        assertTrue("entity-3" in active.resources)
        assertEquals(listOf(3), active.projectGenesis.map { it.projectId })
        assertEquals(listOf(1), active.archivedProjects.map { it.projectId })
        assertTrue("entity-2" in restored.snapshot(MESSAGE_READY, includeArchived = true).resources)
        restored.beginBatch()
        assertFalse(restored.applyIntent(DocumentIntent(
            ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Disallowed",
        )))
        restored.rollbackBatch()
        assertEquals(RepositoryBindStatus.PROJECT_ARCHIVED, restored.bindRepository(1, directory.toString()).status)
        assertEquals(ProjectGenesisStatus.PROJECT_NOT_FOUND, restored.advanceProjectGenesis(1, ProjectGenesisSubmission(baseRevision = 0)).status)
        assertEquals(ProjectGenesisStatus.PROJECT_NOT_FOUND, restored.admitProjectGenesis(1).status)
        assertEquals(ProjectGenesisFirstOutcomeStatus.PROJECT_NOT_FOUND,
            restored.createProjectGenesisFirstOutcome(1, "Disallowed", 0, null).status)
        assertTrue(Files.exists(directory.resolve("workspace.journal.jsonl")))
    }

    @Test
    fun `failed archive persistence leaves the active project unchanged`() {
        val store = WorkspaceStore(projectArchiveStore = object : ProjectArchiveStore {
            override fun load(): List<ProjectArchive> = emptyList()
            override fun append(archive: ProjectArchive): Unit = throw IOException("archive disk unavailable")
        })
        store.beginBatch()
        assertTrue(store.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Keep")))
        store.commitBatch()

        val result = store.archiveProject(1)

        assertEquals(ProjectArchiveStatus.STORAGE_UNAVAILABLE, result.status)
        assertEquals("archive disk unavailable", result.diagnostic)
        assertFalse(store.isProjectArchived(1))
        assertTrue("entity-1" in store.snapshot(MESSAGE_READY).resources)
    }

    @Test
    fun `public archive route preserves explicit historical inspection`() = testApplication {
        val store = WorkspaceStore()
        store.beginBatch()
        assertTrue(store.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Old")))
        store.commitBatch()
        application { workspaceApi(store) }

        val archived = client.post("/api/projects/1/archive") {
            contentType(ContentType.Application.Json)
            setBody("""{"actor":"HUMAN"}""")
        }
        assertEquals(HttpStatusCode.Created, archived.status)
        val active = Json.decodeFromString<WorkspaceSnapshot>(client.get("/api/workspace").bodyAsText())
        assertFalse("entity-1" in active.resources)
        assertEquals("0", active.resources.getValue("focus").path)
        val historical = Json.decodeFromString<WorkspaceSnapshot>(client.get("/api/workspace?includeArchived=true").bodyAsText())
        assertTrue("entity-1" in historical.resources)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/workspace?includeArchived=invalid").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/projects/999/archive") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status)
    }
}
