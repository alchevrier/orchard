package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceRepository
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceRepositoryTest {
    @Test
    fun recoversHierarchyAndContinuesMonotonicIdsAfterRestart() = withTempDirectory { directory ->
        val firstStore = WorkspaceStore(FileWorkspaceRepository(directory))
        firstStore.beginBatch()
        assertTrue(firstStore.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(firstStore.applyIntent(intent(ENTITY_EPIC, "Persistence", projectId = 1)))
        assertTrue(firstStore.applyIntent(intent(ENTITY_STORY, "Recover state", projectId = 1, epicId = 2)))
        assertTrue(firstStore.applyIntent(intent(ENTITY_TASK, "Replay journal", projectId = 1, epicId = 2, storyId = 3)))
        firstStore.commitBatch()

        val recoveredStore = WorkspaceStore(FileWorkspaceRepository(directory))
        assertEquals(4, recoveredStore.entityCount)
        assertEquals(listOf(1, 2, 3, 4), (0 until 4).map { recoveredStore.entityAt(it).id })
        assertEquals(listOf(0, 1, 2, 3), (0 until 4).map { recoveredStore.entityAt(it).parentId })

        recoveredStore.beginBatch()
        assertTrue(recoveredStore.applyIntent(intent(ENTITY_TASK, "Verify IDs", projectId = 1, epicId = 2, storyId = 3)))
        recoveredStore.commitBatch()

        val restartedAgain = WorkspaceStore(FileWorkspaceRepository(directory))
        assertEquals(5, restartedAgain.entityCount)
        assertEquals(5, restartedAgain.entityAt(4).id)
    }

    @Test
    fun writesMultiEntityBatchAsOneJournalTransaction() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Atlas")))
        assertTrue(store.applyIntent(intent(ENTITY_EPIC, "General", projectId = 1)))
        store.commitBatch()

        val lines = Files.readAllLines(directory.resolve("workspace.journal.jsonl"))
        assertEquals(1, lines.size)
        assertTrue("\"sequence\":1" in lines.single())
        assertTrue("\"Atlas\"" in lines.single())
        assertTrue("\"General\"" in lines.single())
    }

    @Test
    fun compactsToAtomicSnapshotAndRecoversWithoutJournal() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory, compactAfterTransactions = 1))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Compacted")))
        store.commitBatch()

        assertTrue(Files.exists(directory.resolve("workspace.snapshot.json")))
        assertEquals(0L, Files.size(directory.resolve("workspace.journal.jsonl")))

        val recovered = WorkspaceStore(FileWorkspaceRepository(directory, compactAfterTransactions = 1))
        assertEquals(1, recovered.entityCount)
        assertEquals("Compacted", recovered.entityAt(0).title)
    }

    @Test
    fun quarantinesTruncatedJournalTailAndKeepsValidPrefix() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Durable")))
        store.commitBatch()
        Files.writeString(
            directory.resolve("workspace.journal.jsonl"),
            "{\"truncated\"",
            StandardOpenOption.APPEND,
        )

        val recovered = WorkspaceStore(FileWorkspaceRepository(directory))

        assertEquals(1, recovered.entityCount)
        assertEquals("Durable", recovered.entityAt(0).title)
        assertEquals(1, Files.readAllLines(directory.resolve("workspace.journal.jsonl")).size)
        Files.list(directory).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("workspace.journal.corrupt-") })
        }
    }

    @Test
    fun failedDurableCommitCanBeRolledBackInMemory() {
        val repository = object : WorkspaceRepository {
            override fun load(): List<WorkspaceEntity> = emptyList()
            override fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>) {
                error("disk full")
            }
        }
        val store = WorkspaceStore(repository)
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Not durable")))

        assertFailsWith<IllegalStateException> { store.commitBatch() }
        store.rollbackBatch()

        assertEquals(0, store.entityCount)
    }

    @Test
    fun repositoryBindingRecoversCanonicalRootAndLiveMetadata() = withTempDirectory { directory ->
        val workspaceDirectory = directory.resolve("workspace")
        val repositoryDirectory = directory.resolve("bound-repository")
        val nestedDirectory = repositoryDirectory.resolve("src/main")
        Files.createDirectories(nestedDirectory)
        Files.writeString(repositoryDirectory.resolve("settings.gradle.kts"), "rootProject.name = \"bound\"\n")
        runCommand(repositoryDirectory, "git", "init", "--initial-branch=main")

        val firstStore = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
        )
        firstStore.beginBatch()
        assertTrue(firstStore.applyIntent(intent(ENTITY_PROJECT, "Bound")))
        firstStore.commitBatch()

        val result = firstStore.bindRepository(1, nestedDirectory.toString())

        assertEquals(RepositoryBindStatus.BOUND, result.status)
        assertEquals(repositoryDirectory.toRealPath().toString(), result.snapshot.repositories.getValue(1).path)

        val recoveredStore = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
        )
        val repository = recoveredStore.snapshot(0).repositories.getValue(1)
        assertEquals(repositoryDirectory.toRealPath().toString(), repository.path)
        assertEquals("main", repository.branch)
        assertEquals("Gradle", repository.buildSystem)
        assertTrue(repository.available)
        assertTrue(repository.dirty)

        Files.walk(repositoryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        val unavailable = recoveredStore.snapshot(0).repositories.getValue(1)
        assertEquals(repository.path, unavailable.path)
        assertTrue(!unavailable.available)
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

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("orchard-workspace-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun runCommand(directory: Path, vararg command: String) {
        val process = ProcessBuilder(command.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Command ${command.joinToString(" ")} failed: $output" }
    }
}