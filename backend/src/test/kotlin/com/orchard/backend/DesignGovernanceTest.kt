package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.CRITERION_AUTOMATED
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.DESIGN_STATUS_REJECTED
import com.orchard.backend.workspace.DesignCriterionSubmission
import com.orchard.backend.workspace.DesignGovernanceStatus
import com.orchard.backend.workspace.DesignGovernanceStore
import com.orchard.backend.workspace.DesignGovernanceEvent
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.FileDesignGovernanceStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.RepositoryView
import com.orchard.backend.workspace.RequirementSubmission
import com.orchard.backend.workspace.RevisionValidation
import com.orchard.backend.workspace.TransientDefinitionCollaborationStore
import com.orchard.backend.workspace.TransientWorkflowMemoryStore
import com.orchard.backend.workspace.TransientWorkDefinitionStore
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesignGovernanceTest {
    @Test
    fun admittedHierarchyCompilesTraceableContractAndGatesExecution() {
        val workspace = governedWorkspace()
        val rejected = workspace.recordDesignCandidate(
            design(2, "SYS-ORDER", emptyList()).copy(assumptions = emptyList())
        )
        val rejectedDecision = workspace.admitDesign(requireNotNull(rejected.design).designId)

        assertEquals(DesignGovernanceStatus.REJECTED, rejectedDecision.status)
        assertEquals(DESIGN_STATUS_REJECTED, rejectedDecision.decision?.status)
        assertTrue(rejectedDecision.decision?.findings.orEmpty().any { it.code == "ASSUMPTIONS_REQUIRED" })

        val rejectedDesign = requireNotNull(rejected.design)
        val epic = workspace.recordDesignCandidate(
            design(2, "SYS-ORDER", emptyList()).copy(
                baseRevision = rejectedDesign.revision,
                baseHash = rejectedDesign.hash,
            )
        )
        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(epic.design).designId).status)
        val story = workspace.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(story.design).designId).status)
        val task = workspace.recordDesignCandidate(design(4, "IMP-ORDER", listOf("SUB-ORDER")))
        val admittedTask = workspace.admitDesign(requireNotNull(task.design).designId)

        assertEquals(DesignGovernanceStatus.ADMITTED, admittedTask.status)
        assertEquals(DESIGN_STATUS_ADMITTED, admittedTask.decision?.status)
        val contract = requireNotNull(admittedTask.decision?.contract)
        assertEquals(listOf("IMP-ORDER:C1"), contract.criteria.map { it.criterionId })
        assertEquals(listOf("SUB-ORDER", "SYS-ORDER"), contract.inheritedRequirementIds)
        assertEquals(listOf(3, 2), contract.parentDesigns.map { it.workItemId })
        assertEquals(WorkflowStartStatus.CREATED, workspace.startWorkflow(4).status)
    }

    @Test
    fun parentAdmissionMakesDescendantContractsStaleWithoutChangingStartedRun() {
        val workspace = governedWorkspace(includeSiblingTask = true)
        admitHierarchy(workspace, taskIds = listOf(4, 5))
        val started = workspace.startWorkflow(4)
        val pinnedContract = requireNotNull(started.snapshot.workflowRuns.single().context.acceptanceContract)
        val story = workspace.snapshot(0).designRevisions.last { it.design.workItemId == 3 }.design
        val revisedStory = workspace.recordDesignCandidate(
            design(3, "SUB-ORDER", listOf("SYS-ORDER")).copy(
                baseRevision = story.revision,
                baseHash = story.hash,
                architecture = listOf("Compile revised criteria into immutable acceptance authority"),
            )
        )

        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(revisedStory.design).designId).status)
        val blocked = workspace.startWorkflow(5)

        assertEquals(WorkflowStartStatus.DESIGN_NOT_ADMITTED, blocked.status)
        assertEquals(pinnedContract, blocked.snapshot.workflowRuns.single().context.acceptanceContract)
        assertTrue(
            blocked.snapshot.projectGovernance.single().blockingFindings.any {
                it.code == "DESIGN_AUTHORITY_STALE" && it.message.contains("TASK 5")
            }
        )
    }

    @Test
    fun admittedDesignRegistryRecoversExactlyAfterRestart() = withTempDirectory { directory ->
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            designGovernanceStore = FileDesignGovernanceStore(directory),
        )
        populateGovernedWorkspace(first)
        admitHierarchy(first, listOf(4))
        val expected = first.snapshot(0)

        val restored = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            designGovernanceStore = FileDesignGovernanceStore(directory),
        ).snapshot(0)

        assertEquals(expected.designRevisions, restored.designRevisions)
        assertEquals(expected.projectGovernance, restored.projectGovernance)
        assertEquals(
            expected.designRevisions.last().decision?.contract,
            restored.designRevisions.last().decision?.contract,
        )
    }

    @Test
    fun restartRejectsRunWhoseAdmissionAuthorityIsMissing() = withTempDirectory { directory ->
        val workflowMemory = TransientWorkflowMemoryStore()
        val definitions = TransientWorkDefinitionStore()
        val collaboration = TransientDefinitionCollaborationStore()
        val governance = com.orchard.backend.workspace.TransientDesignGovernanceStore()
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            workflowMemory = workflowMemory,
            definitionStore = definitions,
            collaborationStore = collaboration,
            designGovernanceStore = governance,
        )
        populateGovernedWorkspace(first)
        admitHierarchy(first, listOf(4))
        assertEquals(WorkflowStartStatus.CREATED, first.startWorkflow(4).status)
        val withoutTaskAdmission = object : DesignGovernanceStore {
            override fun loadEvents(): List<DesignGovernanceEvent> = governance.loadEvents().dropLast(1)
            override fun append(event: DesignGovernanceEvent) = error("read only")
        }

        assertFailsWith<IllegalArgumentException> {
            WorkspaceStore(
                repository = FileWorkspaceRepository(directory),
                repositoryBindings = repositoryBindings(),
                workflowMemory = workflowMemory,
                definitionStore = definitions,
                collaborationStore = collaboration,
                designGovernanceStore = withoutTaskAdmission,
            )
        }
    }

    @Test
    fun failedAdmissionAppendPublishesNeitherDecisionNorContract() {
        val events = mutableListOf<DesignGovernanceEvent>()
        var rejectDecision = false
        val governance = object : DesignGovernanceStore {
            override fun loadEvents(): List<DesignGovernanceEvent> = events.toList()
            override fun append(event: DesignGovernanceEvent) {
                if (rejectDecision && event.decision != null) error("storage unavailable")
                events += event
            }
        }
        val workspace = WorkspaceStore(
            repositoryBindings = repositoryBindings(),
            designGovernanceStore = governance,
        )
        populateGovernedWorkspace(workspace)
        val candidate = workspace.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        rejectDecision = true

        val failed = workspace.admitDesign(requireNotNull(candidate.design).designId)

        assertEquals(DesignGovernanceStatus.STORAGE_UNAVAILABLE, failed.status)
        val projected = failed.snapshot.designRevisions.single()
        assertEquals("CANDIDATE", projected.status)
        assertEquals(null, projected.decision)
        assertTrue(events.none { it.decision != null })
    }

    private fun admitHierarchy(workspace: WorkspaceStore, taskIds: List<Int>) {
        val epic = workspace.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(epic.design).designId).status)
        val story = workspace.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(story.design).designId).status)
        taskIds.forEach { taskId ->
            val task = workspace.recordDesignCandidate(design(taskId, "IMP-ORDER-$taskId", listOf("SUB-ORDER")))
            assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(task.design).designId).status)
        }
    }

    private fun governedWorkspace(includeSiblingTask: Boolean = false): WorkspaceStore {
        val workspace = WorkspaceStore(repositoryBindings = repositoryBindings())
        populateGovernedWorkspace(workspace, includeSiblingTask)
        return workspace
    }

    private fun repositoryBindings() = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "a".repeat(40), "main", "", clean = true,
            )
            override fun validateRevision(projectId: Int, baseRevision: String, targetRevision: String) =
                RevisionValidation(targetRevision, changedFromBase = true)
        }

    private fun populateGovernedWorkspace(workspace: WorkspaceStore, includeSiblingTask: Boolean = false) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        if (includeSiblingTask) {
            assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Sibling task", projectId = 1, epicId = 2, storyId = 3)))
        }
        workspace.commitBatch()
        assertEquals(DesignGovernanceStatus.RECORDED, workspace.activateDesignGovernance(1).status)
        workspace.submitWorkDefinition(4, readyDefinition())
        if (includeSiblingTask) workspace.submitWorkDefinition(5, readyDefinition())
    }

    private fun design(workItemId: Int, requirementId: String, parents: List<String>) = DesignSubmission(
        workItemId = workItemId,
        title = "Design for $requirementId",
        problem = "The governed behavior must be exact and inspectable.",
        scope = listOf("The selected work-item boundary"),
        assumptions = listOf("The admitted parent requirements remain authoritative"),
        constraints = listOf("Child requirements cannot weaken parent requirements"),
        alternatives = listOf("Retain manual, ungoverned execution"),
        architecture = listOf("Compile requirement criteria into immutable acceptance authority"),
        failureModes = listOf("Missing traceability blocks admission"),
        qualityAttributes = listOf("Deterministic", "Auditable"),
        securityImpact = "No new privilege boundary.",
        complianceImpact = "No jurisdiction-specific claim.",
        requirements = listOf(
            RequirementSubmission(
                requirementId,
                "The system shall satisfy $requirementId.",
                parents,
                listOf(
                    DesignCriterionSubmission(
                        "The exact requirement is implemented.",
                        "Run the requirement-specific verification.",
                        CRITERION_AUTOMATED,
                    )
                ),
            )
        ),
    )

    private fun readyDefinition() = WorkDefinitionSubmission(
        requestedOutcome = "Implement the admitted requirement",
        currentBehavior = "The requirement is not implemented",
        requiredBehavior = "The admitted behavior is observable",
        scope = listOf("Implementation boundary"),
        nonGoals = listOf("Changing parent authority"),
        constraints = listOf("Use the admitted contract"),
        acceptanceCriteria = listOf(AcceptanceCriterion("The behavior passes", "Run verification")),
    )

    private fun intent(
        type: Int,
        title: String,
        projectId: Int = 0,
        epicId: Int = 0,
        storyId: Int = 0,
    ) = DocumentIntent(
        ACTION_CREATE,
        type,
        DEFAULT_DELIVERY_WORKFLOW_ID,
        projectId,
        epicId,
        storyId,
        title,
    )

    private fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("orchard-design-governance-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
