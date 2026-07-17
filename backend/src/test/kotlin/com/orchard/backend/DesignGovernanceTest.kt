package com.orchard.backend

import com.orchard.backend.workspace.CriterionJudgmentSubmission
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.CRITERION_AUTOMATED
import com.orchard.backend.workspace.CRITERION_HUMAN
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.DESIGN_STATUS_REJECTED
import com.orchard.backend.workspace.DesignCriterionSubmission
import com.orchard.backend.workspace.DesignGovernanceStatus
import com.orchard.backend.workspace.DesignGovernanceStore
import com.orchard.backend.workspace.DesignGovernanceEvent
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.FileDesignGovernanceStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileDefinitionCollaborationStore
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
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.WorkflowMemoryStore
import com.orchard.backend.workspace.WorkflowEvent
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        val started = workspace.startWorkflow(4)
        assertEquals(WorkflowStartStatus.CREATED, started.status)
        val criterionGate = started.snapshot.workflowRuns.single().workflow.evidenceContract.requirements.last()
        assertEquals("CRITERION:IMP-ORDER:C1", criterionGate.kind)
        assertEquals("IMP-ORDER:C1", criterionGate.criterionId)
        assertEquals("IMP-ORDER", criterionGate.requirementId)
        assertEquals(CRITERION_AUTOMATED, criterionGate.gate)
        assertEquals("Run the requirement-specific verification.", criterionGate.verification)
    }

    @Test
    fun automatedCriterionBlocksCompletionAndRequiresAdmittedVerification() {
        val workspace = governedWorkspace()
        admitHierarchy(workspace, listOf(4))
        val runId = workspace.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "b".repeat(40)

        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { submission ->
            assertEquals(WorkflowMutationStatus.RECORDED, workspace.submitEvidence(runId, submission).status)
        }
        assertTrue(workspace.snapshot(0).workflowRuns.single().state != RUN_STATE_DONE)

        val wrong = workspace.submitEvidence(
            runId,
            evidence("CRITERION:IMP-ORDER-4:C1", revision, "./gradlew approximateCheck"),
        )
        assertEquals(WorkflowMutationStatus.INVALID_RECORD, wrong.status)
        val accepted = workspace.submitEvidence(
            runId,
            evidence(
                "CRITERION:IMP-ORDER-4:C1",
                revision,
                "Run the requirement-specific verification.",
            ),
        )

        assertEquals(WorkflowMutationStatus.RECORDED, accepted.status)
        assertEquals(RUN_STATE_DONE, accepted.snapshot.workflowRuns.single().state)
    }

    @Test
    fun humanCriterionRejectsEvidenceAndRequiresExplicitRevisionJudgment() {
        val workspace = governedWorkspace()
        val epic = workspace.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        workspace.admitDesign(requireNotNull(epic.design).designId)
        val story = workspace.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        workspace.admitDesign(requireNotNull(story.design).designId)
        val task = workspace.recordDesignCandidate(
            design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN)
        )
        assertEquals(DesignGovernanceStatus.ADMITTED, workspace.admitDesign(requireNotNull(task.design).designId).status)
        val runId = workspace.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "d".repeat(40)
        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { workspace.submitEvidence(runId, it) }

        assertEquals(
            WorkflowMutationStatus.INVALID_RECORD,
            workspace.submitEvidence(
                runId,
                evidence("CRITERION:IMP-HUMAN:C1", revision, "human said yes"),
            ).status,
        )
        val rejected = workspace.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1",
                revision,
                "release-owner@example.test",
                "The observable behavior does not yet satisfy the requirement.",
                approved = false,
            ),
        )
        assertEquals(RUN_STATE_EVIDENCE_BLOCKED, rejected.snapshot.workflowRuns.single().state)
        assertEquals("REJECTED", rejected.snapshot.workflowRuns.single().criterionGates.single().status)

        val approved = workspace.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1",
                revision,
                "release-owner@example.test",
                "The corrected behavior now satisfies the admitted requirement.",
                approved = true,
            ),
        )

        val run = approved.snapshot.workflowRuns.single()
        assertEquals(RUN_STATE_DONE, run.state)
        assertEquals(2, run.judgments.size)
        assertEquals("PASSED", run.criterionGates.single().status)
        assertEquals(listOf(run.judgments.last().judgmentId), run.decisions.last().judgmentIds)
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
    fun humanCriterionJudgmentsAndCompletionRecoverExactly() = withTempDirectory { directory ->
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
        val epic = first.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        first.admitDesign(requireNotNull(epic.design).designId)
        val story = first.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        first.admitDesign(requireNotNull(story.design).designId)
        val task = first.recordDesignCandidate(design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN))
        first.admitDesign(requireNotNull(task.design).designId)
        val runId = first.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "e".repeat(40)
        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { first.submitEvidence(runId, it) }
        first.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1", revision, "owner@example.test", "Initial inspection failed.", false,
            ),
        )
        first.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1", revision, "owner@example.test", "Corrected behavior passed inspection.", true,
            ),
        )

        val restored = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            workflowMemory = workflowMemory,
            definitionStore = definitions,
            collaborationStore = collaboration,
            designGovernanceStore = governance,
        ).snapshot(0).workflowRuns.single()

        assertEquals(RUN_STATE_DONE, restored.state)
        assertEquals(listOf(false, true), restored.judgments.map { it.approved })
        assertEquals("PASSED", restored.criterionGates.single().status)
        assertEquals(restored.judgments.last().judgmentId, restored.criterionGates.single().authorityEventId)
    }

    @Test
    fun humanCriterionJudgmentRoutePublishesAuthorityProjection() = testApplication {
        val workspace = governedWorkspace()
        val epic = workspace.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        workspace.admitDesign(requireNotNull(epic.design).designId)
        val story = workspace.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        workspace.admitDesign(requireNotNull(story.design).designId)
        val task = workspace.recordDesignCandidate(design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN))
        workspace.admitDesign(requireNotNull(task.design).designId)
        val runId = workspace.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "f".repeat(40)
        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { workspace.submitEvidence(runId, it) }
        application { workspaceApi(workspace) }

        val response = client.post("/api/workflow-runs/$runId/criterion-judgments") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    CriterionJudgmentSubmission(
                        "IMP-HUMAN:C1",
                        revision,
                        "release-owner@example.test",
                        "The admitted human criterion passed inspection.",
                        true,
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"criterionId\":\"IMP-HUMAN:C1\""))
        assertTrue(body.contains("\"status\":\"PASSED\""))
        assertTrue(body.contains("\"state\":\"DONE\""))
    }

    @Test
    fun replayRejectsForgedOutcomesTransitionsAndJudgmentIdentity() = withTempDirectory { directory ->
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
        val epic = first.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        first.admitDesign(requireNotNull(epic.design).designId)
        val story = first.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        first.admitDesign(requireNotNull(story.design).designId)
        val task = first.recordDesignCandidate(design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN))
        first.admitDesign(requireNotNull(task.design).designId)
        val runId = first.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "9".repeat(40)
        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { first.submitEvidence(runId, it) }
        first.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1", revision, "owner@example.test", "Inspection passed.", true,
            ),
        )
        val events = workflowMemory.loadEvents()
        val invalidRevision = "8".repeat(40)
        val tamperedEventSets = listOf(
            events.map { event ->
                if (event.evidence?.kind == "BUILD") {
                    event.copy(evidence = event.evidence.copy(exitCode = 1))
                } else event
            },
            events.mapIndexed { index, event ->
                if (index == 0) {
                    event.copy(decision = requireNotNull(event.decision).copy(signal = "", toState = "IMPOSSIBLE"))
                } else event
            },
            events.map { event ->
                event.judgment?.let { judgment ->
                    event.copy(judgment = judgment.copy(judgmentId = judgment.judgmentId + 100))
                } ?: event
            },
            events.map { event ->
                if (event.evidence?.kind == "BUILD") {
                    event.copy(evidence = event.evidence.copy(revision = invalidRevision))
                } else event
            },
        )

        tamperedEventSets.forEachIndexed { index, tamperedEvents ->
            val tamperedMemory = object : WorkflowMemoryStore by workflowMemory {
                override fun loadEvents(): List<WorkflowEvent> = tamperedEvents
            }
            assertFailsWith<IllegalArgumentException> {
                WorkspaceStore(
                    repository = FileWorkspaceRepository(directory),
                    repositoryBindings = repositoryBindings(
                        rejectedRevision = invalidRevision.takeIf { index == tamperedEventSets.lastIndex }
                    ),
                    workflowMemory = tamperedMemory,
                    definitionStore = definitions,
                    collaborationStore = collaboration,
                    designGovernanceStore = governance,
                )
            }
        }
    }

    @Test
    fun failedJudgmentAppendPublishesNoApprovalAuthority() {
        val persistedMemory = TransientWorkflowMemoryStore()
        var rejectJudgment = false
        val workflowMemory = object : WorkflowMemoryStore by persistedMemory {
            override fun appendEvent(event: WorkflowEvent) {
                if (rejectJudgment && event.judgment != null) error("storage unavailable")
                persistedMemory.appendEvent(event)
            }
        }
        val workspace = WorkspaceStore(
            repositoryBindings = repositoryBindings(),
            workflowMemory = workflowMemory,
        )
        populateGovernedWorkspace(workspace)
        val epic = workspace.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        workspace.admitDesign(requireNotNull(epic.design).designId)
        val story = workspace.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        workspace.admitDesign(requireNotNull(story.design).designId)
        val task = workspace.recordDesignCandidate(design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN))
        workspace.admitDesign(requireNotNull(task.design).designId)
        val runId = workspace.startWorkflow(4).snapshot.workflowRuns.single().runId
        rejectJudgment = true

        val failed = workspace.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1",
                "7".repeat(40),
                "owner@example.test",
                "This approval must not be published.",
                true,
            ),
        )

        assertEquals(WorkflowMutationStatus.STORAGE_UNAVAILABLE, failed.status)
        assertTrue(failed.snapshot.workflowRuns.single().judgments.isEmpty())
        assertEquals("PENDING", failed.snapshot.workflowRuns.single().criterionGates.single().status)
        assertTrue(persistedMemory.loadEvents().none { it.judgment != null })
    }

    @Test
    fun workflowEventJournalQuarantinesTornTailAndRecoversAcceptedJudgment() = withTempDirectory { directory ->
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            workflowMemory = FileWorkflowMemoryStore(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            designGovernanceStore = FileDesignGovernanceStore(directory),
        )
        populateGovernedWorkspace(first)
        val epic = first.recordDesignCandidate(design(2, "SYS-ORDER", emptyList()))
        first.admitDesign(requireNotNull(epic.design).designId)
        val story = first.recordDesignCandidate(design(3, "SUB-ORDER", listOf("SYS-ORDER")))
        first.admitDesign(requireNotNull(story.design).designId)
        val task = first.recordDesignCandidate(design(4, "IMP-HUMAN", listOf("SUB-ORDER"), CRITERION_HUMAN))
        first.admitDesign(requireNotNull(task.design).designId)
        val runId = first.startWorkflow(4).snapshot.workflowRuns.single().runId
        val revision = "6".repeat(40)
        listOf(
            evidence("SOURCE_DIFF", revision, ""),
            evidence("BUILD", revision, "./gradlew build"),
            evidence("TEST", revision, "./gradlew test"),
            evidence("ACCEPTANCE", revision, "./gradlew acceptance"),
        ).forEach { first.submitEvidence(runId, it) }
        first.recordCriterionJudgment(
            runId,
            CriterionJudgmentSubmission(
                "IMP-HUMAN:C1", revision, "owner@example.test", "Inspection passed.", true,
            ),
        )
        Files.writeString(
            directory.resolve("workflow-events.jsonl"),
            "{\"truncated\"",
            StandardOpenOption.APPEND,
        )

        val restored = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = repositoryBindings(),
            workflowMemory = FileWorkflowMemoryStore(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            designGovernanceStore = FileDesignGovernanceStore(directory),
        ).snapshot(0).workflowRuns.single()

        assertEquals(RUN_STATE_DONE, restored.state)
        assertEquals("PASSED", restored.criterionGates.single().status)
        assertTrue(
            Files.list(directory).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith("workflow-events.corrupt-") }
            }
        )
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

    private fun repositoryBindings(rejectedRevision: String? = null) = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "a".repeat(40), "main", "", clean = true,
            )
            override fun validateRevision(projectId: Int, baseRevision: String, targetRevision: String) =
                targetRevision.takeUnless { it == rejectedRevision }
                    ?.let { RevisionValidation(it, changedFromBase = true) }
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

    private fun design(
        workItemId: Int,
        requirementId: String,
        parents: List<String>,
        gate: String = CRITERION_AUTOMATED,
    ) = DesignSubmission(
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
                        if (gate == CRITERION_AUTOMATED) "Run the requirement-specific verification." else "",
                        gate,
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

    private fun evidence(kind: String, revision: String, command: String) = EvidenceSubmission(
        kind = kind,
        revision = revision,
        command = command,
        exitCode = 0,
        outputHash = "c".repeat(64),
        summary = "$kind passed",
        producer = "test-runner",
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
