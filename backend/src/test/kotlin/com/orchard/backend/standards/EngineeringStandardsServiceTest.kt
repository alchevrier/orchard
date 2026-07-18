package com.orchard.backend.standards

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EngineeringStandardsServiceTest {
    @Test
    fun `scan is evidence bound and admission creates hierarchy exactly once`() = runTest {
        val state = createTempDirectory("orchard-conformance-state-")
        val repository = initializedRepository()
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val bindings = FileRepositoryBindingStore(state)
        bindings.bind(projectId, repository.toString())
        val readmeHash = sha256(Files.readString(repository.resolve("README.md")))
        val store = TransientEngineeringStandardsStore()
        val service = EngineeringStandardsService(workspace, bindings, listOf(FixedConformanceModel(output(readmeHash))), store)
        assertEquals(
            StandardUpdateStatus.UPDATED,
            service.updateStandard(
                projectId,
                EngineeringStandardSubmission("Orchard standard", listOf(defaultEngineeringPractices().first())),
            ).status,
        )
        val countBeforeScan = workspace.entityCount

        val scanResult = service.scan(projectId)

        assertEquals(ConformanceScanStatus.CREATED, scanResult.status)
        val scan = assertNotNull(scanResult.scan)
        assertEquals(countBeforeScan, workspace.entityCount)
        assertEquals(readmeHash, scan.findings.single().citations.single().contentHash)
        assertEquals(BacklogAdmissionStatus.ADMITTED, service.admitBacklog(scan.scanId).status)
        assertEquals(countBeforeScan + 3, workspace.entityCount)
        val epic = workspace.entityAt(countBeforeScan)
        val story = workspace.entityAt(countBeforeScan + 1)
        val task = workspace.entityAt(countBeforeScan + 2)
        assertEquals(ENTITY_EPIC, epic.type)
        assertEquals(projectId, epic.parentId)
        assertEquals(ENTITY_STORY, story.type)
        assertEquals(epic.id, story.parentId)
        assertEquals(ENTITY_TASK, task.type)
        assertEquals(story.id, task.parentId)
        assertEquals(BacklogAdmissionStatus.ALREADY_ADMITTED, service.admitBacklog(scan.scanId).status)
        assertEquals(countBeforeScan + 3, workspace.entityCount)
    }

    @Test
    fun `scan rejects invented repository citation`() = runTest {
        val state = createTempDirectory("orchard-conformance-invalid-state-")
        val repository = initializedRepository()
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val bindings = FileRepositoryBindingStore(state)
        bindings.bind(projectId, repository.toString())
        val service = EngineeringStandardsService(
            workspace,
            bindings,
            listOf(FixedConformanceModel(output("f".repeat(64)))),
        )
        service.updateStandard(projectId, EngineeringStandardSubmission("Orchard standard", listOf(defaultEngineeringPractices().first())))

        val result = service.scan(projectId)

        assertEquals(ConformanceScanStatus.INVALID_OUTPUT, result.status)
        assertEquals(1, workspace.entityCount)
    }

    @Test
    fun `campaign reconciliation compiles admitted remediation exactly once`() = runTest {
        val state = createTempDirectory("orchard-remediation-state-")
        val repository = initializedRepository()
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val bindings = FileRepositoryBindingStore(state)
        bindings.bind(projectId, repository.toString())
        val standardsStore = TransientEngineeringStandardsStore()
        val readmeHash = sha256(Files.readString(repository.resolve("README.md")))
        val standards = EngineeringStandardsService(
            workspace,
            bindings,
            listOf(FixedConformanceModel(output(readmeHash))),
            standardsStore,
        )
        standards.updateStandard(
            projectId,
            EngineeringStandardSubmission("Orchard standard", listOf(defaultEngineeringPractices().first())),
        )
        val scan = assertNotNull(standards.scan(projectId).scan)
        assertEquals(BacklogAdmissionStatus.ADMITTED, standards.admitBacklog(scan.scanId).status)
        val campaignStore = TransientRemediationCampaignStore()
        val campaigns = RemediationCampaignService(
            workspace,
            bindings,
            standardsStore,
            standards,
            CompanyControlService(workspace, emptyList(), repositories = bindings),
            campaignStore,
        )

        assertEquals(CAMPAIGN_TICK_WAITING_FOR_PROMOTION, campaigns.tick().status)
        assertEquals(CAMPAIGN_TICK_WAITING_FOR_PROMOTION, campaigns.tick().status)

        assertEquals(1, campaignStore.campaigns().size)
        assertEquals(0, campaignStore.evaluations().size)
        val campaign = campaignStore.campaigns().single()
        assertEquals(listOf("AUTHORITY_INTEGRITY"), campaign.links.map { it.practiceId })
        assertEquals(listOf("EPIC", "STORY", "TASK"), campaign.links.single().backlogNodeIds)
        val snapshot = workspace.snapshot(com.orchard.backend.workspace.MESSAGE_READY)
        assertEquals(1, snapshot.workDefinitions.size)
        assertEquals(3, snapshot.designRevisions.count { it.status == com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED })
        assertEquals(2, snapshot.stagedPlans.size)
        assertEquals(0, snapshot.workflowRuns.size)
        assertEquals(1, snapshot.circuitDispatches.size)
    }

    private fun createProject(workspace: WorkspaceStore): Int {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Orchard")))
        val projectId = workspace.lastCreatedId
        workspace.commitBatch()
        return projectId
    }

    private fun initializedRepository(): Path {
        val directory = createTempDirectory("orchard-conformance-repository-")
        git(directory, "init")
        git(directory, "config", "user.name", "Orchard Test")
        git(directory, "config", "user.email", "orchard-test@localhost")
        Files.writeString(directory.resolve("README.md"), "# Orchard\n\nDurable authority is append-only.\n")
        Files.writeString(directory.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") version \"2.1.21\" }\n")
        git(directory, "add", ".")
        git(directory, "commit", "-m", "Initial")
        return directory
    }

    private fun output(contentHash: String): String = """
        {
          "findings": [{
            "findingId": "FINDING_AUTHORITY",
            "practiceId": "AUTHORITY_INTEGRITY",
            "disposition": "PARTIAL",
            "summary": "The authority claim lacks recovery verification.",
            "citations": [{"path":"README.md","contentHash":"$contentHash","observation":"README declares append-only authority."}],
            "affectedPaths": ["README.md"],
            "acceptanceCriteria": ["Corrupt authority records are rejected."],
            "verificationCommands": ["./gradlew test --no-daemon"],
            "confidence": 0.9
          }],
          "proposedBacklog": [
            {"nodeId":"EPIC","parentNodeId":null,"type":"EPIC","title":"Harden authority","description":"Make authority recovery verifiable.","findingIds":["FINDING_AUTHORITY"],"acceptanceCriteria":[],"verificationCommands":[]},
            {"nodeId":"STORY","parentNodeId":"EPIC","type":"STORY","title":"Verify recovery","description":"Prove corruption handling.","findingIds":["FINDING_AUTHORITY"],"acceptanceCriteria":[],"verificationCommands":[]},
            {"nodeId":"TASK","parentNodeId":"STORY","type":"TASK","title":"Add recovery test","description":"Exercise corrupt persisted state.","findingIds":["FINDING_AUTHORITY"],"acceptanceCriteria":["Corrupt authority records are rejected."],"verificationCommands":["./gradlew test --no-daemon"]}
          ]
        }
    """.trimIndent()

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class FixedConformanceModel(private val output: String) : ModelProvider {
        override suspend fun triage(prompt: String): String = error("Unsupported")
        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("Unsupported")
        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:conformance",
            provider = "test",
            model = "fixed-conformance-model",
            contextWindowTokens = 96_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
        override suspend fun executeRepositoryAnalysis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int) =
            ModelGeneration(output, 100, 100)
    }
}
