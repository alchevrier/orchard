package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkEpisode
import com.orchard.backend.workspace.AttemptSubmission
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceRepository
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFINITION_READY
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

    @Test
    fun workflowRunRecoversPinnedContextContractAndPastFixes() = withTempDirectory { directory ->
        val workspaceDirectory = directory.resolve("workspace")
        val repositoryDirectory = directory.resolve("bound-repository")
        Files.createDirectories(repositoryDirectory)
        Files.writeString(repositoryDirectory.resolve("settings.gradle.kts"), "rootProject.name = \"bound\"\n")
        runCommand(repositoryDirectory, "git", "init", "--initial-branch=main")
        runCommand(repositoryDirectory, "git", "config", "user.email", "orchard@example.test")
        runCommand(repositoryDirectory, "git", "config", "user.name", "Orchard Test")
        runCommand(repositoryDirectory, "git", "add", "settings.gradle.kts")
        runCommand(repositoryDirectory, "git", "commit", "-m", "initial")
        val pinnedRevision = commandOutput(repositoryDirectory, "git", "rev-parse", "HEAD")

        val workflowMemory = FileWorkflowMemoryStore(workspaceDirectory)
        val definitionStore = FileWorkDefinitionStore(workspaceDirectory)
        workflowMemory.appendEpisode(
            WorkEpisode(
                episodeId = 1,
                projectId = 1,
                workItemType = ENTITY_TASK,
                workflowId = "default-delivery-task",
                title = "Fix Gradle JDK target failure",
                problem = "Gradle compilation failed because the JDK target was unsupported.",
                failedApproaches = listOf("Changing source compatibility alone did not change the Kotlin target."),
                resolution = "Configured the Kotlin JVM target consistently with the supported JDK.",
                evidenceSummary = "The complete Gradle build passed.",
                sourceRevision = pinnedRevision,
            )
        )
        workflowMemory.appendEpisode(
            WorkEpisode(
                episodeId = 2,
                projectId = 99,
                workItemType = ENTITY_TASK,
                workflowId = "default-delivery-task",
                title = "Fix Gradle JDK target failure",
                problem = "A different project had the same words.",
                failedApproaches = emptyList(),
                resolution = "Out of scope.",
                evidenceSummary = "None.",
                sourceRevision = pinnedRevision,
            )
        )
        val store = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
            workflowMemory,
            definitionStore,
        )
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(store.applyIntent(intent(ENTITY_EPIC, "Runtime", projectId = 1)))
        assertTrue(store.applyIntent(intent(ENTITY_STORY, "Build compatibility", projectId = 1, epicId = 2)))
        assertTrue(store.applyIntent(intent(ENTITY_TASK, "Fix Gradle JDK target", projectId = 1, epicId = 2, storyId = 3)))
        store.commitBatch()
        assertEquals(RepositoryBindStatus.BOUND, store.bindRepository(1, repositoryDirectory.toString()).status)
        assertEquals(DEFINITION_READY, store.submitWorkDefinition(4, readyDefinition()).snapshot.workDefinitions.single().assessment.status)

        val started = store.startWorkflow(4)

        assertEquals(WorkflowStartStatus.CREATED, started.status)
        val run = started.snapshot.workflowRuns.single()
        assertEquals(pinnedRevision, run.context.repository.commitHash)
        assertEquals(listOf(1L), run.context.recalledEpisodes.map { it.episodeId })
        assertEquals(4, run.workflow.evidenceContract.requirements.size)
        assertTrue(run.workflow.evidenceContract.requirements.any {
            it.kind == "ACCEPTANCE" && "./gradlew build" in it.description
        })
        assertEquals(started.snapshot.workDefinitions.single().hash, run.workDefinition?.hash)
        assertTrue("status=1" in started.snapshot.resources.getValue("entity-4").action)

        Files.writeString(repositoryDirectory.resolve("README.md"), "later revision\n")
        runCommand(repositoryDirectory, "git", "add", "README.md")
        runCommand(repositoryDirectory, "git", "commit", "-m", "later")
        val completedRevision = commandOutput(repositoryDirectory, "git", "rev-parse", "HEAD")

        assertEquals(
            com.orchard.backend.workspace.WorkflowMutationStatus.RECORDED,
            store.recordAttempt(
                1,
                AttemptSubmission(
                    description = "Change Java source compatibility only",
                    outcome = "Kotlin still targeted the unsupported bytecode level.",
                    diagnosticHash = "d".repeat(64),
                    successful = false,
                ),
            ).status,
        )
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "SOURCE_DIFF",
                revision = completedRevision,
                command = "",
                exitCode = 0,
                outputHash = "a".repeat(64),
                summary = "Aligned Kotlin and Java target configuration.",
                producer = "test-fixture",
            ),
        )
        val blocked = store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "BUILD",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 1,
                outputHash = "b".repeat(64),
                summary = "Build still failed before the corrected retry.",
                producer = "test-fixture",
            ),
        )
        assertEquals(RUN_STATE_EVIDENCE_BLOCKED, blocked.snapshot.workflowRuns.single().state)
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "BUILD",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 0,
                outputHash = "c".repeat(64),
                summary = "Complete build passed.",
                producer = "test-fixture",
            ),
        )
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "TEST",
                revision = completedRevision,
                command = "./gradlew test",
                exitCode = 0,
                outputHash = "e".repeat(64),
                summary = "Relevant tests passed.",
                producer = "test-fixture",
            ),
        )
        val completed = store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "ACCEPTANCE",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 0,
                outputHash = "f".repeat(64),
                summary = "The work-definition acceptance criterion passed.",
                producer = "test-fixture",
            ),
        )
        assertEquals(RUN_STATE_DONE, completed.snapshot.workflowRuns.single().state)
        assertTrue("status=3" in completed.snapshot.resources.getValue("entity-4").action)

        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_TASK, "Fix Gradle JDK target again", projectId = 1, epicId = 2, storyId = 3)))
        store.commitBatch()
        store.submitWorkDefinition(5, readyDefinition())
        val recalled = store.startWorkflow(5).snapshot.workflowRuns.first { it.context.workItemId == 5 }
        assertTrue(recalled.context.recalledEpisodes.any { recall ->
            recall.failedApproaches.any { "Kotlin still targeted" in it }
        })

        val recovered = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
            FileWorkflowMemoryStore(workspaceDirectory),
            FileWorkDefinitionStore(workspaceDirectory),
        ).snapshot(0)
        assertEquals(2, recovered.workflowRuns.size)
        assertEquals(2, recovered.workDefinitions.size)
        assertEquals(RUN_STATE_DONE, recovered.workflowRuns.first { it.runId == 1L }.state)
        assertEquals(pinnedRevision, recovered.workflowRuns.first { it.runId == 1L }.context.repository.commitHash)
        assertTrue("status=3" in recovered.resources.getValue("entity-4").action)
        assertTrue(recovered.workflowRuns.first { it.runId == 2L }.context.recalledEpisodes.any { recall ->
            recall.failedApproaches.any { "Kotlin still targeted" in it }
        })
        assertEquals(
            recovered.workDefinitions.first { it.workItemId == 5 }.hash,
            recovered.workflowRuns.first { it.runId == 2L }.workDefinition?.hash,
        )
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
        requestedOutcome = "Build succeeds on the supported JDK",
        currentBehavior = "Gradle targets an unsupported bytecode version",
        requiredBehavior = "Kotlin and Java compile to the supported target",
        scope = listOf("Gradle JVM target configuration"),
        nonGoals = listOf("Changing application behavior"),
        constraints = listOf("Keep the current JDK toolchain"),
        acceptanceCriteria = listOf(
            AcceptanceCriterion("The complete build succeeds", "Run ./gradlew build")
        ),
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

    private fun commandOutput(directory: Path, vararg command: String): String {
        val process = ProcessBuilder(command.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "Command ${command.joinToString(" ")} failed: $output" }
        return output
    }
}