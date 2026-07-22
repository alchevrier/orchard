package com.orchard.backend

import com.orchard.backend.agent.ArchitectChatRequest
import com.orchard.backend.agent.ArchitectService
import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.agent.ProposalGenerationStatus
import com.orchard.backend.agent.CircuitIntelligenceService
import com.orchard.backend.agent.CircuitGenerationStatus
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.resource.MachineCapacityMonitor
import com.orchard.backend.resource.MachineCapacitySnapshot
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.resource.ModelResourceDemand
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.resource.TransientMachineUsagePolicyStore
import com.orchard.backend.resource.SystemMachineCapacityMonitor
import com.orchard.backend.resource.parseMacAvailableMemoryBytes
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingEvidence
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileUpdateStatus
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.ModelProfileSettingsStore
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
import com.orchard.backend.workspace.ACTION_ACCEPT
import com.orchard.backend.workspace.ACTION_FEEDBACK
import com.orchard.backend.workspace.COLLABORATOR_HUMAN
import com.orchard.backend.workspace.COLLABORATOR_LOCAL_LLM
import com.orchard.backend.workspace.DefinitionExecutionProvenance
import com.orchard.backend.workspace.DefinitionProposalContent
import com.orchard.backend.workspace.DefinitionCollaborationStatus
import com.orchard.backend.workspace.EpisodeQuery
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.EpisodeRecall
import com.orchard.backend.workspace.WorkflowMemoryStore
import com.orchard.backend.workspace.WorkflowRun
import com.orchard.backend.workspace.WorkflowEvent
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkflowMutationStatus
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
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanArtifact
import com.orchard.backend.workspace.StagedPlanArtifactRequirement
import com.orchard.backend.workspace.StagedPlanNodeSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.StagedPlanStatus
import com.orchard.backend.workspace.PLAN_NODE_BLOCKED_DEPENDENCY
import com.orchard.backend.workspace.PLAN_NODE_ELIGIBLE
import com.orchard.backend.workspace.PLAN_NODE_RUNNING
import com.orchard.backend.workspace.PLAN_NODE_DONE
import com.orchard.backend.workspace.RUN_STATE_CANCELLED
import com.orchard.backend.workspace.CIRCUIT_DISPATCH_PENDING
import com.orchard.backend.workspace.CircuitDispatch
import com.orchard.backend.workspace.CircuitDispatchStore
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStoreTest {
    @Test
    fun stagedCircuitDerivesLabelsAndBlocksDownstreamWorkflow() {
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
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Screen", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Define API", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Build screen", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        workspace.submitWorkDefinition(4, readyDefinition())
        workspace.submitWorkDefinition(5, readyDefinition())

        val accepted = workspace.acceptStagedPlan(screenCircuit())
        val view = accepted.snapshot.stagedPlans.single()

        assertEquals(StagedPlanStatus.ACCEPTED, accepted.status)
        assertEquals(listOf("1a", "2a"), view.nodes.map { it.node.label })
        assertEquals(listOf(PLAN_NODE_RUNNING, PLAN_NODE_BLOCKED_DEPENDENCY), view.nodes.map { it.state })
        val run = accepted.snapshot.workflowRuns.single()
        val firstDispatch = accepted.snapshot.circuitDispatches.single()
        assertEquals(firstDispatch.dispatch.dispatchId, run.context.circuitDispatchId)
        assertEquals(PLAN_NODE_RUNNING, firstDispatch.state)
        assertEquals(WorkflowStartStatus.STAGED_PLAN_BLOCKED, workspace.startWorkflow(5).status)
        assertEquals(WorkflowStartStatus.ALREADY_STARTED, workspace.startWorkflow(4).status)
        workspace.beginBatch()
        assertTrue(
            !workspace.applyIntent(intent(ENTITY_TASK, "Late task", projectId = 1, epicId = 2, storyId = 3))
        )
        workspace.rollbackBatch()
        run.workflow.evidenceContract.requirements.forEach { requirement ->
            workspace.submitEvidence(
                run.runId,
                EvidenceSubmission(
                    kind = requirement.kind,
                    revision = "b".repeat(40),
                    command = "verify ${requirement.kind}",
                    exitCode = 0,
                    outputHash = "c".repeat(64),
                    summary = "${requirement.kind} passed",
                    producer = "test",
                ),
            )
        }
        val completedView = workspace.snapshot(MESSAGE_READY).stagedPlans.single()
        assertEquals("API_CONTRACT", completedView.artifacts.single().kind)
        assertEquals(run.runId, completedView.artifacts.single().workflowRunId)
        assertEquals("SOURCE_DIFF", completedView.artifacts.single().evidenceKind)
        assertEquals("c".repeat(64), completedView.artifacts.single().outputHash)
        assertEquals(64, completedView.artifacts.single().evidenceHash.length)
        assertEquals(PLAN_NODE_RUNNING, completedView.nodes.last().state)
        val dispatches = workspace.snapshot(MESSAGE_READY).circuitDispatches
        assertEquals(listOf(PLAN_NODE_DONE, PLAN_NODE_RUNNING), dispatches.map { it.state })
        assertEquals(
            dispatches.last().dispatch.dispatchId,
            workspace.snapshot(MESSAGE_READY).workflowRuns.last().context.circuitDispatchId,
        )
        assertTrue(workspace.snapshot(MESSAGE_READY).resources.getValue("entity-4").action.contains("status=3"))
        assertEquals(WorkflowStartStatus.ALREADY_STARTED, workspace.startWorkflow(5).status)
        assertEquals(StagedPlanStatus.PLAN_LOCKED, workspace.acceptStagedPlan(screenCircuit("Revised circuit")).status)
    }

    @Test
    fun stagedCircuitRejectsUnknownWorkflowAndStaleRevision() {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "First", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Second", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()

        val unknown = screenCircuit().copy(
            stages = screenCircuit().stages.mapIndexed { index, stage ->
                if (index == 0) stage.copy(executionWorkflowId = "unknown-workflow") else stage
            }
        )
        assertEquals(StagedPlanStatus.INVALID_PLAN, workspace.acceptStagedPlan(unknown).status)
        val unboundArtifact = screenCircuit().copy(
            stages = screenCircuit().stages.mapIndexed { index, stage ->
                if (index == 0) stage.copy(
                    nodes = stage.nodes.map { node ->
                        node.copy(
                            produces = node.produces.map { it.copy(evidenceKind = "NOT_A_WORKFLOW_GATE") }
                        )
                    }
                ) else stage
            }
        )
        assertEquals(StagedPlanStatus.INVALID_PLAN, workspace.acceptStagedPlan(unboundArtifact).status)

        val first = workspace.acceptStagedPlan(screenCircuit())
        val active = first.snapshot.stagedPlans.single().plan
        val second = workspace.acceptStagedPlan(
            screenCircuit("Revision 2").copy(baseRevision = active.revision, baseHash = active.hash)
        )
        assertEquals(StagedPlanStatus.ACCEPTED, second.status)
        assertEquals(
            StagedPlanStatus.STALE_PLAN,
            workspace.acceptStagedPlan(
                screenCircuit("Stale edit").copy(baseRevision = active.revision, baseHash = active.hash)
            ).status,
        )
    }

    @Test
    fun stagedCircuitRequiresOneIntegrationOwner() {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "First", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Second", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()

        val plan = StagedDeliveryPlanSubmission(
            3,
            "Invalid integration ownership",
            listOf(
                StagedPlanStageSubmission(
                    "join", "Join", "integration-v1",
                    nodes = listOf(
                        StagedPlanNodeSubmission("first", 4),
                        StagedPlanNodeSubmission("second", 5),
                    ),
                )
            ),
        )

        assertEquals(StagedPlanStatus.INVALID_PLAN, workspace.acceptStagedPlan(plan).status)
    }

    @Test
    fun cancelledCircuitDispatchRequiresExplicitReplacement() {
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
        workspace.submitWorkDefinition(4, readyDefinition())
        val accepted = workspace.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Retry circuit",
                listOf(
                    StagedPlanStageSubmission(
                        "delivery", "Delivery", "sequential-delivery-v1",
                        nodes = listOf(StagedPlanNodeSubmission("task", 4)),
                    )
                ),
            )
        )
        val firstRun = accepted.snapshot.workflowRuns.single()

        val cancelled = workspace.cancelWorkflow(firstRun.runId).snapshot
        val afterTick = workspace.dispatchEligible()

        assertEquals(1, cancelled.workflowRuns.size)
        assertEquals(listOf(RUN_STATE_CANCELLED), cancelled.circuitDispatches.map { it.state })
        assertEquals(1, afterTick.workflowRuns.size)
        assertEquals(listOf(RUN_STATE_CANCELLED), afterTick.circuitDispatches.map { it.state })
        val replacement = workspace.startWorkflow(4)
        assertEquals(WorkflowStartStatus.CREATED, replacement.status)
        assertEquals(2, replacement.snapshot.workflowRuns.size)
        assertEquals(listOf(RUN_STATE_CANCELLED, PLAN_NODE_RUNNING), replacement.snapshot.circuitDispatches.map { it.state })
        assertEquals(listOf(1L, 2L), replacement.snapshot.circuitDispatches.map { it.dispatch.dispatchId })
    }

    @Test
    fun dispatchTickStartsPendingNodeAfterRepositoryBecomesClean() {
        val clean = AtomicBoolean(false)
        val bindings = object : RepositoryBindingStore {
            override fun bind(projectId: Int, requestedPath: String) = Unit
            override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
            override fun resolveHead(projectId: Int) = RepositoryHead(
                projectId, "/repository", "a".repeat(40), "main", "", clean = clean.get(),
            )
        }
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        workspace.submitWorkDefinition(4, readyDefinition())
        val accepted = workspace.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Pending circuit",
                listOf(
                    StagedPlanStageSubmission(
                        "delivery", "Delivery", "sequential-delivery-v1",
                        nodes = listOf(StagedPlanNodeSubmission("task", 4)),
                    )
                ),
            )
        ).snapshot

        assertTrue(accepted.workflowRuns.isEmpty())
        assertEquals(CIRCUIT_DISPATCH_PENDING, accepted.circuitDispatches.single().state)
        clean.set(true)

        val dispatched = workspace.dispatchEligible()

        assertEquals(1, dispatched.workflowRuns.size)
        assertEquals(PLAN_NODE_RUNNING, dispatched.circuitDispatches.single().state)
    }

    @Test
    fun dispatchStorageFailureDoesNotRewriteAcceptedPlanResult() {
        val failingDispatchStore = object : CircuitDispatchStore {
            override fun load(): List<CircuitDispatch> = emptyList()
            override fun append(dispatch: CircuitDispatch) = error("disk full")
        }
        val workspace = WorkspaceStore(circuitDispatchStore = failingDispatchStore)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        workspace.submitWorkDefinition(4, readyDefinition())

        val result = workspace.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Accepted before dispatch",
                listOf(
                    StagedPlanStageSubmission(
                        "delivery", "Delivery", "sequential-delivery-v1",
                        nodes = listOf(StagedPlanNodeSubmission("task", 4)),
                    )
                ),
            )
        )

        assertEquals(StagedPlanStatus.ACCEPTED, result.status)
        assertEquals(1, result.snapshot.stagedPlans.single().plan.revision)
        assertTrue(result.snapshot.circuitDispatches.isEmpty())
    }

    @Test
    fun stagedCircuitRejectsForwardDependency() {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "First", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Second", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        val invalid = screenCircuit().copy(
            stages = screenCircuit().stages.mapIndexed { index, stage ->
                if (index == 0) stage.copy(nodes = stage.nodes.map { it.copy(dependsOn = listOf("screen")) }) else stage
            }
        )

        assertEquals(StagedPlanStatus.INVALID_PLAN, workspace.acceptStagedPlan(invalid).status)
    }

    @Test
    fun epicCircuitRejectsArtifactsUntilStoryAggregationExists() {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        workspace.commitBatch()

        val plan = StagedDeliveryPlanSubmission(
            2,
            "Epic circuit",
            listOf(
                StagedPlanStageSubmission(
                    "story", "Story", "contract-design-v1",
                    nodes = listOf(
                        StagedPlanNodeSubmission(
                            "story", 3,
                            produces = listOf(StagedPlanArtifact("API_CONTRACT", "API")),
                        )
                    ),
                )
            ),
        )

        assertEquals(StagedPlanStatus.INVALID_PLAN, workspace.acceptStagedPlan(plan).status)
    }

    private fun screenCircuit(title: String = "Screen delivery circuit") = StagedDeliveryPlanSubmission(
        scopeId = 3,
        title = title,
        stages = listOf(
            StagedPlanStageSubmission(
                "contract",
                "Accept contract",
                "contract-design-v1",
                nodes = listOf(
                    StagedPlanNodeSubmission(
                        "api",
                        4,
                        produces = listOf(StagedPlanArtifact("API_CONTRACT", "Screen API")),
                    )
                ),
            ),
            StagedPlanStageSubmission(
                "implementation",
                "Parallel implementation",
                "parallel-implementation-v1",
                nodes = listOf(
                    StagedPlanNodeSubmission(
                        "screen",
                        5,
                        dependsOn = listOf("api"),
                        consumes = listOf(StagedPlanArtifactRequirement("api", "API_CONTRACT")),
                    )
                ),
            ),
        ),
    )

    @Test
    fun humanFeedbackAndEditsRemainDistinctFromLlmProposalAuthority() {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        val binding = ModelBindingProfile(
            "ollama:phi3:test",
            "ollama",
            "phi3:mini",
            8_000,
            setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
        val execution = workspace.recordModelExecution(
            com.orchard.backend.workspace.ModelExecutionObservationDraft(
                DefaultModelExecutionProfiles.boundedDefinitionReasoning,
                binding,
                "DEFINE_TASK",
                4,
                "b".repeat(64),
                "a".repeat(64),
                "c".repeat(64),
                100,
                50,
                1,
                true,
            )
        )!!

        val proposed = workspace.recordDefinitionProposal(
            4,
            COLLABORATOR_LOCAL_LLM,
            DefinitionProposalContent(
                definition = readyDefinition(),
                observations = listOf("The capability is absent."),
                assumptions = listOf("The existing component remains the integration boundary."),
            ),
            DefinitionExecutionProvenance(
                executor = "ollama-work-definition",
                model = "phi3:mini",
                executionProfileId = DefaultModelExecutionProfiles.boundedDefinitionReasoning.id,
                bindingFingerprint = modelBindingFingerprint(binding),
                promptVersion = 1,
                promptHash = "a".repeat(64),
                contextHash = "b".repeat(64),
                outputHash = "c".repeat(64),
                executionId = execution.executionId,
            ),
        )
        assertEquals(DefinitionCollaborationStatus.RECORDED, proposed.status)
        assertEquals(
            DefinitionCollaborationStatus.RECORDED,
            workspace.recordDefinitionFeedback(proposed.proposal!!.proposalId, "Keep the public API unchanged.").status,
        )

        val accepted = workspace.acceptDefinitionProposal(
            proposed.proposal.proposalId,
            readyDefinition().copy(constraints = listOf("Keep the public API unchanged")),
        )

        assertEquals(WorkDefinitionStatus.RECORDED, accepted.status)
        val proposals = accepted.snapshot.definitionProposals
        assertEquals(listOf(COLLABORATOR_LOCAL_LLM, COLLABORATOR_HUMAN), proposals.map { it.proposal.actor })
        assertEquals("Keep the public API unchanged.", proposals.first().feedback.single().content)
        assertEquals(proposals.last().proposal.proposalId, accepted.snapshot.workDefinitions.single().sourceProposal?.proposalId)
        assertEquals(DEFINITION_READY, accepted.snapshot.workDefinitions.single().assessment.status)
    }

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
        val readyProposal = ready.snapshot.definitionProposals.last().proposal
        assertEquals(COLLABORATOR_HUMAN, readyProposal.actor)
        assertEquals(readyProposal.proposalId, ready.snapshot.workDefinitions.single().sourceProposal?.proposalId)
        val started = workspace.startWorkflow(4)
        assertEquals(WorkflowStartStatus.CREATED, started.status)
        assertEquals(2L, started.snapshot.workflowRuns.single().workDefinition?.definitionId)
        assertEquals(
            WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED,
            workspace.submitWorkDefinition(4, readyDefinition()).status,
        )
        assertEquals(
            WorkDefinitionStatus.WORKFLOW_ALREADY_STARTED,
            workspace.acceptDefinitionProposal(
                readyProposal.proposalId,
                readyDefinition().copy(constraints = listOf("A post-start edit")),
            ).status,
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
            think = false,
            options = OllamaOptions(temperature = 0, seed = 42),
        )

        val payload = Json.encodeToString(request)

        assertTrue("\"stream\":false" in payload)
        assertTrue("\"format\":\"json\"" in payload)
        assertTrue("\"think\":false" in payload)
    }

    @Test
    fun ollamaOptionsSerializeRequestedContextCapacity() {
        val options = OllamaOptions(
            temperature = 0,
            seed = 42,
            numPredict = 2_000,
            numContext = 131_072,
            numThread = 1,
        )

        val payload = Json.encodeToString(options)

        assertTrue("\"num_predict\":2000" in payload)
        assertTrue("\"num_ctx\":131072" in payload)
        assertTrue("\"num_thread\":1" in payload)
    }

    @Test
    fun routesProvenProfileByReliabilityAndHumanSatisfaction() {
        fun provider(id: String) = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                id,
                "test",
                id,
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
        }
        val lowerSatisfaction = provider("lower-satisfaction")
        val preferred = provider("preferred")

        val selected = ModelProfileResolver.resolve(
            DefaultModelExecutionProfiles.boundedDefinitionReasoning,
            listOf(lowerSatisfaction, preferred),
            listOf(
                ModelBindingEvidence(modelBindingFingerprint(lowerSatisfaction.bindingProfile()), 12_000, 2_000, 10, 1.0, 1, 2, 7, 500),
                ModelBindingEvidence(modelBindingFingerprint(preferred.bindingProfile()), 12_000, 2_000, 10, 1.0, 7, 2, 1, 900),
            ),
        )

        assertEquals("preferred", selected.bindingProfile().bindingId)
    }
}

class ArchitectServiceTest {
    @Test
    fun resourcePolicyRejectsArchitectBeforeTriage() = runTest {
        var triageCalls = 0
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = "{}".also { triageCalls++ }
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun architectResourceDemand() = ModelResourceDemand(250, 1)
        }
        val controller = MachineResourceController(
            TransientMachineUsagePolicyStore(MachineUsagePolicy(20, 0, 2)),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(1_000, 900, 8, 0.0)
            },
        )

        val result = ArchitectService(WorkspaceStore(), provider, controller).submit(
            ArchitectChatRequest("Create a project")
        )

        assertEquals(429, result.statusCode)
        assertEquals(0, triageCalls)
    }

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

class MachineResourceControllerTest {
    @Test
    fun macCapacityUsesReclaimableVmPagesInsteadOfJvmFreePages() {
        val directory = createTempDirectory("orchard-mac-capacity-test")
        try {
            val snapshot = SystemMachineCapacityMonitor(
                directory.resolve("missing-meminfo"),
                directory.resolve("missing-cgroup"),
                directory.resolve("missing-cgroup-root"),
                directory.resolve("missing-v1-root"),
                osName = "Mac OS X",
                macAvailableMemoryProbe = { 24_000_000_000 },
            ).snapshot()

            assertEquals(24_000_000_000, snapshot.availableMemoryBytes)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesMacFreeInactiveAndSpeculativePagesAsAvailable() {
        val vmStat = """
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                               100.
            Pages active:                             900.
            Pages inactive:                           200.
            Pages speculative:                         50.
            Pages wired down:                         300.
        """.trimIndent()

        assertEquals(350L * 16_384, parseMacAvailableMemoryBytes(vmStat))
    }

    @Test
    fun observesMostRestrictiveProcessCgroupV2Ancestor() {
        val directory = createTempDirectory("orchard-cgroup-test")
        try {
            val root = directory.resolve("cgroup")
            val parent = root.resolve("user.slice")
            val process = parent.resolve("orchard.service")
            Files.createDirectories(process)
            Files.writeString(directory.resolve("meminfo"), "MemTotal: 1000 kB\nMemAvailable: 900 kB\n")
            Files.writeString(directory.resolve("self-cgroup"), "0::/user.slice/orchard.service\n")
            Files.writeString(root.resolve("cgroup.controllers"), "memory cpu\n")
            Files.writeString(root.resolve("memory.max"), "max\n")
            Files.writeString(parent.resolve("memory.max"), "600000\n")
            Files.writeString(parent.resolve("memory.current"), "200000\n")
            Files.writeString(process.resolve("memory.max"), "800000\n")
            Files.writeString(process.resolve("memory.current"), "300000\n")

            val snapshot = SystemMachineCapacityMonitor(
                directory.resolve("meminfo"),
                directory.resolve("self-cgroup"),
                root,
                directory.resolve("v1-memory"),
            ).snapshot()

            assertEquals(600_000, snapshot.totalMemoryBytes)
            assertEquals(400_000, snapshot.availableMemoryBytes)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun enforcesUserShareAndReleasesReservations() {
        val controller = controller(MachineUsagePolicy(20, 0, 4), total = 1_000, available = 900)

        val first = controller.tryAcquire(ModelResourceDemand(150, 1))
        val overShare = controller.tryAcquire(ModelResourceDemand(60, 1))
        first.lease?.close()
        val afterRelease = controller.tryAcquire(ModelResourceDemand(60, 1))

        assertEquals(ResourceAdmissionDecision.ADMITTED, first.evidence.decision)
        assertEquals(ResourceAdmissionDecision.POLICY_CAPACITY_EXCEEDED, overShare.evidence.decision)
        assertEquals(ResourceAdmissionDecision.ADMITTED, afterRelease.evidence.decision)
        afterRelease.lease?.close()
    }

    @Test
    fun liveAvailableCapacityOverridesTheoreticalUserShare() {
        val controller = controller(MachineUsagePolicy(100, 100, 4), total = 1_000, available = 250)

        val result = controller.tryAcquire(ModelResourceDemand(151, 1))

        assertEquals(ResourceAdmissionDecision.LIVE_CAPACITY_EXCEEDED, result.evidence.decision)
        assertEquals(250, result.evidence.capacity.availableMemoryBytes)
    }

    @Test
    fun cumulativeReservationsCannotExceedLiveAvailableMemory() {
        val controller = controller(MachineUsagePolicy(100, 0, 4), total = 2_000, available = 1_000)

        val first = controller.tryAcquire(ModelResourceDemand(600, 1))
        val second = controller.tryAcquire(ModelResourceDemand(600, 1))

        assertEquals(ResourceAdmissionDecision.ADMITTED, first.evidence.decision)
        assertEquals(ResourceAdmissionDecision.LIVE_CAPACITY_EXCEEDED, second.evidence.decision)
        first.lease?.close()
    }

    @Test
    fun unavailableCpuTelemetryFailsClosed() {
        val controller = MachineResourceController(
            TransientMachineUsagePolicyStore(MachineUsagePolicy(100, 0, 4)),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(2_000, 2_000, 8, null)
            },
        )

        val result = controller.tryAcquire(ModelResourceDemand(100, 1))

        assertEquals(ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE, result.evidence.decision)
    }

    private fun controller(
        policy: MachineUsagePolicy,
        total: Long,
        available: Long,
    ) = MachineResourceController(
        TransientMachineUsagePolicyStore(policy),
        object : MachineCapacityMonitor {
            override fun snapshot() = MachineCapacitySnapshot(total, available, 8, 0.0)
        },
    )
}

class DefinitionIntelligenceServiceTest {
    @Test
    fun defaultPromptIncludesConcreteSchemaValidBugExample() {
        val prompt = requireNotNull(
            DefinitionIntelligenceService::class.java.getResourceAsStream(
                "/default-system-prompts/work_definition_agent.md"
            )
        ).bufferedReader().use { it.readText() }

        assertTrue(prompt.contains("For a Bug, a valid complete response looks like this:"))
        assertTrue(prompt.contains("\"requestedOutcome\": \"Interactive work receives a bounded scheduling opportunity\""))
        assertTrue(prompt.contains("Include exactly the definition, observations, and assumptions top-level keys."))
    }

    @Test
    fun userOverrideSelectsPreferredBindingAndAppliesOutputReserve() = runTest {
        val workspace = definitionWorkspace()
        var smallCalls = 0
        var preferredOutputBudget = 0
        var preferredContextWindow = 0
        fun provider(id: String, onExecute: (Int) -> Unit) = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                id,
                "test",
                id,
                20_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): com.orchard.backend.vector.ModelGeneration {
                onExecute(maxOutputTokens)
                return com.orchard.backend.vector.ModelGeneration(proposalJson(), 1_000, 200)
            }
            override suspend fun executeWorkDefinition(
                prompt: String,
                maxOutputTokens: Int,
                contextWindowTokens: Int,
            ): com.orchard.backend.vector.ModelGeneration {
                if (id == "preferred") preferredContextWindow = contextWindowTokens
                return executeWorkDefinition(prompt, maxOutputTokens)
            }
        }
        val settings = TransientModelProfileSettingsStore()
        val service = DefinitionIntelligenceService(
            workspace,
            listOf(
                provider("small") { smallCalls++ },
                provider("preferred") { preferredOutputBudget = it },
            ),
            settings,
            systemPrompt = "policy",
        )

        val update = service.updateProfile(
            ModelProfileOverride("bounded-definition-reasoning-v1", 8_000, 1_500, "preferred")
        )
        val proposal = service.propose(4)

        assertEquals(ModelProfileUpdateStatus.UPDATED, update.status)
        assertEquals(
            8_000,
            service.profileConfigurations().single {
                it.effectiveProfile.id == "bounded-definition-reasoning-v1"
            }.effectiveProfile.inputBudgetTokens,
        )
        assertEquals(0, smallCalls)
        assertEquals(1_500, preferredOutputBudget)
        assertEquals(9_500, preferredContextWindow)
        assertEquals(ProposalGenerationStatus.CREATED, proposal.status)
        assertEquals("preferred", proposal.snapshot.modelProfiles.single().binding.bindingId)
    }

    @Test
    fun refusesInferenceWhenConfiguredMachineShareCannotFitDemand() = runTest {
        val workspace = definitionWorkspace()
        var providerCalls = 0
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "resource-bound",
                "test",
                "model",
                20_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override fun resourceDemand(profile: com.orchard.backend.vector.ModelExecutionProfile) =
                ModelResourceDemand(250, 1)
            override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int) =
                com.orchard.backend.vector.ModelGeneration(proposalJson(), 1_000, 200).also { providerCalls++ }
        }
        val resources = MachineResourceController(
            TransientMachineUsagePolicyStore(MachineUsagePolicy(20, 0, 4)),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(1_000, 900, 8, 0.0)
            },
        )
        val service = DefinitionIntelligenceService(
            workspace,
            listOf(provider),
            TransientModelProfileSettingsStore(),
            resources,
            systemPrompt = "policy",
        )

        val result = service.propose(4)

        assertEquals(ProposalGenerationStatus.RESOURCE_CAPACITY_UNAVAILABLE, result.status)
        assertEquals(0, providerCalls)
        assertEquals(ResourceAdmissionDecision.POLICY_CAPACITY_EXCEEDED, resources.configuration().lastAdmission?.decision)
    }

    @Test
    fun distinctWorkItemsCanReachInferenceConcurrently() = runTest {
        val workspace = definitionWorkspace()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(definitionIntent(ENTITY_TASK, "Second task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "parallel",
                "test",
                "model",
                20_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): com.orchard.backend.vector.ModelGeneration {
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                bothStarted.await()
                return com.orchard.backend.vector.ModelGeneration(proposalJson(), 1_000, 200)
            }
        }
        val service = DefinitionIntelligenceService(workspace, provider, systemPrompt = "policy")

        val first = async { service.propose(4) }
        val second = async { service.propose(5) }

        assertEquals(ProposalGenerationStatus.CREATED, first.await().status)
        assertEquals(ProposalGenerationStatus.CREATED, second.await().status)
        assertEquals(2, started.get())
    }

    @Test
    fun rejectsProfileOverrideThatNoInstalledBindingCanRun() {
        val service = DefinitionIntelligenceService(
            definitionWorkspace(),
            proposalProvider("bounded", 14_000, mutableListOf()),
            systemPrompt = "policy",
        )

        val result = service.updateProfile(
            ModelProfileOverride("bounded-definition-reasoning-v1", 13_000, 2_000)
        )

        assertEquals(ModelProfileUpdateStatus.NO_COMPATIBLE_BINDING, result.status)
        assertEquals(
            12_000,
            result.configurations.single {
                it.effectiveProfile.id == "bounded-definition-reasoning-v1"
            }.effectiveProfile.inputBudgetTokens,
        )
    }

    @Test
    fun localModelRevisesProposalFromHumanFeedbackWithoutCreatingAuthority() = runTest {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(definitionIntent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(definitionIntent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(definitionIntent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(definitionIntent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        val prompts = mutableListOf<String>()
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
            override suspend fun proposeWorkDefinition(prompt: String): String {
                prompts += prompt
                return proposalJson()
            }
            override fun modelIdentity(): String = "phi3:test"
            override fun bindingProfile() = ModelBindingProfile(
                "phi3:test",
                "test",
                "phi3:test",
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
        }
        val service = DefinitionIntelligenceService(workspace, provider, systemPrompt = "proposal policy v1")

        val first = service.propose(4)
        val firstProposal = first.snapshot.definitionProposals.single().proposal
        workspace.recordDefinitionFeedback(firstProposal.proposalId, "Preserve the public API exactly.")
        val latest = service.propose(4)

        assertEquals(ProposalGenerationStatus.CREATED, first.status)
        assertEquals(ProposalGenerationStatus.CREATED, latest.status)
        assertEquals(2, latest.snapshot.definitionProposals.size)
        assertTrue("Preserve the public API exactly." in prompts.last())
        assertTrue(latest.snapshot.definitionProposals.all { it.proposal.actor == COLLABORATOR_LOCAL_LLM })
        assertEquals("phi3:test", latest.snapshot.definitionProposals.last().proposal.provenance?.model)
        assertTrue(latest.snapshot.definitionProposals.all { it.proposal.provenance?.executionId != null })
        assertEquals(2, latest.snapshot.modelProfiles.single().sampleCount)
        assertEquals(1, latest.snapshot.modelProfiles.single().revisionRequestedCount)
        assertTrue(latest.snapshot.workDefinitions.isEmpty())
    }

    @Test
    fun selectsCompatibleBindingForWorkflowProfile() = runTest {
        val workspace = definitionWorkspace()
        val incompatiblePrompts = mutableListOf<String>()
        val compatiblePrompts = mutableListOf<String>()
        val service = DefinitionIntelligenceService(
            workspace,
            listOf(
                proposalProvider("too-small", 13_999, incompatiblePrompts),
                proposalProvider("compatible", 14_000, compatiblePrompts),
            ),
            systemPrompt = "proposal policy v1",
        )

        val result = service.propose(4)

        assertEquals(ProposalGenerationStatus.CREATED, result.status)
        assertTrue(incompatiblePrompts.isEmpty())
        assertEquals(1, compatiblePrompts.size)
        assertEquals("compatible", result.snapshot.modelProfiles.single().binding.bindingId)
    }

    @Test
    fun refusesInferenceWhenMandatoryEnvelopeExceedsProfileBudget() = runTest {
        val workspace = definitionWorkspace()
        val prompts = mutableListOf<String>()
        val service = DefinitionIntelligenceService(
            workspace,
            proposalProvider("bounded", 14_000, prompts),
            systemPrompt = "proposal policy v1",
        )
        val first = service.propose(4)
        val proposalId = first.snapshot.definitionProposals.single().proposal.proposalId
        repeat(16) { index ->
            workspace.recordDefinitionFeedback(proposalId, "$index:${"x".repeat(4_090)}")
        }

        val overflow = service.propose(4)

        assertEquals(ProposalGenerationStatus.CONTEXT_BUDGET_EXCEEDED, overflow.status)
        assertEquals(1, prompts.size)
        assertEquals(1, overflow.snapshot.modelProfiles.single().sampleCount)
    }

    @Test
    fun projectsFeedbackAndAcceptanceAsDistinctSatisfactionSignals() = runTest {
        val workspace = definitionWorkspace()
        val service = DefinitionIntelligenceService(
            workspace,
            proposalProvider("satisfaction", 14_000, mutableListOf()),
            systemPrompt = "proposal policy v1",
        )
        val first = service.propose(4)
        workspace.recordDefinitionFeedback(
            first.snapshot.definitionProposals.single().proposal.proposalId,
            "Preserve compatibility.",
        )
        val second = service.propose(4)
        val secondProposal = second.snapshot.definitionProposals.last().proposal
        workspace.acceptDefinitionProposal(
            secondProposal.proposalId,
            secondProposal.content.definition.copy(constraints = listOf("Preserve compatibility")),
        )
        val third = service.propose(4)
        val accepted = workspace.acceptDefinitionProposal(third.snapshot.definitionProposals.last().proposal.proposalId)

        val profile = accepted.snapshot.modelProfiles.single()
        assertEquals(3, profile.sampleCount)
        assertEquals(1, profile.revisionRequestedCount)
        assertEquals(1, profile.acceptedAfterEditCount)
        assertEquals(1.0, profile.averageHumanRevisionFields)
        assertEquals(1, profile.acceptedUnchangedCount)
    }

    @Test
    fun rejectsOutputWhenProviderMeasuresInputBeyondProfileBudget() = runTest {
        val workspace = definitionWorkspace()
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "measured-overflow",
                "test",
                "measured-overflow",
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int) =
                com.orchard.backend.vector.ModelGeneration(proposalJson(), 12_001, 200)
        }

        val result = DefinitionIntelligenceService(workspace, provider, systemPrompt = "policy").propose(4)

        assertEquals(ProposalGenerationStatus.INVALID_OUTPUT, result.status)
        assertEquals(1, result.snapshot.modelProfiles.single().sampleCount)
        assertTrue(result.snapshot.definitionProposals.isEmpty())
    }

    @Test
    fun recordsCancelledModelInvocationBeforePropagatingCancellation() = runTest {
        val workspace = definitionWorkspace()
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "cancelled",
                "test",
                "cancelled",
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override suspend fun executeWorkDefinition(prompt: String, maxOutputTokens: Int): com.orchard.backend.vector.ModelGeneration {
                throw CancellationException("cancelled by test")
            }
        }
        val service = DefinitionIntelligenceService(workspace, provider, systemPrompt = "policy")

        try {
            service.propose(4)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, workspace.snapshot(0).modelProfiles.single().sampleCount)
            assertEquals(0.0, workspace.snapshot(0).modelProfiles.single().schemaValidityRate)
        }
    }

    private fun definitionWorkspace(): WorkspaceStore = WorkspaceStore().also { workspace ->
        workspace.beginBatch()
        check(workspace.applyIntent(definitionIntent(ENTITY_PROJECT, "Project")))
        check(workspace.applyIntent(definitionIntent(ENTITY_EPIC, "Epic", projectId = 1)))
        check(workspace.applyIntent(definitionIntent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        check(workspace.applyIntent(definitionIntent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
    }

    private fun proposalProvider(
        bindingId: String,
        contextWindowTokens: Int,
        prompts: MutableList<String>,
    ): ModelProvider = object : ModelProvider {
        override suspend fun triage(prompt: String): String = error("unused")
        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override suspend fun proposeWorkDefinition(prompt: String): String {
            prompts += prompt
            return proposalJson()
        }

        override fun modelIdentity(): String = bindingId

        override fun bindingProfile(): ModelBindingProfile = ModelBindingProfile(
            bindingId = bindingId,
            provider = "test",
            model = bindingId,
            contextWindowTokens = contextWindowTokens,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
    }

    private fun definitionIntent(
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

    private fun proposalJson(): String = """
        {
          "definition": {
            "requestedOutcome": "Complete the bounded task",
            "currentBehavior": "The capability is absent",
            "requiredBehavior": "The capability is available",
            "scope": ["Bounded component"],
            "nonGoals": ["Unrelated components"],
            "constraints": [],
            "acceptanceCriteria": [
              {"description": "The capability works", "verification": "Run its focused test"}
            ],
            "unresolvedQuestions": [],
            "proposedSplitTitles": [],
            "reproduction": "",
            "regressionCriterion": ""
          },
          "observations": ["The task requests a missing capability."],
          "assumptions": ["The bounded component is the intended integration point."]
        }
    """.trimIndent()
}

class CircuitIntelligenceServiceTest {
        @Test
        fun generationIsProposalOnlyAndHumanAcceptancePinsItsProvenance() = runTest {
                val workspace = circuitWorkspace()
                val prompts = mutableListOf<String>()
                val service = CircuitIntelligenceService(workspace, circuitProvider(prompts), systemPrompt = "circuit policy")

                val generated = service.propose(3)

                assertEquals(CircuitGenerationStatus.CREATED, generated.status)
                assertTrue(generated.snapshot.stagedPlans.isEmpty())
                val proposal = generated.snapshot.circuitProposals.single().proposal
                assertEquals(3, proposal.scopeId)
                assertEquals(listOf(4, 5), proposal.content.plan.stages.flatMap { it.nodes }.map { it.workItemId })
                assertTrue(prompts.single().contains("forbiddenActions"))
                assertTrue(prompts.single().contains("artifactEvidenceKinds"))

                val accepted = workspace.acceptCircuitProposal(proposal.proposalId)
                val plan = accepted.snapshot.stagedPlans.single().plan

                assertEquals(StagedPlanStatus.ACCEPTED, accepted.status)
                assertEquals(proposal.proposalId, plan.sourceProposal?.proposalId)
                assertEquals(proposal.hash, plan.sourceProposal?.proposalHash)
                assertTrue(plan.acceptedProposalUnchanged)
                assertEquals(plan.planId, accepted.snapshot.circuitProposals.single().acceptedPlanId)
                assertTrue(accepted.snapshot.circuitProposals.single().acceptedUnchanged)
                val profile = accepted.snapshot.modelProfiles.single {
                    it.executionProfileId == "bounded-circuit-synthesis-v1"
                }
                assertEquals(1, profile.acceptedUnchangedCount)
                assertEquals(0, profile.acceptedAfterEditCount)
        }

        @Test
        fun staleProposalCannotReplaceAPlanAcceptedAfterGeneration() = runTest {
                val workspace = circuitWorkspace()
                val service = CircuitIntelligenceService(workspace, circuitProvider(mutableListOf()), systemPrompt = "policy")
                val proposal = service.propose(3).snapshot.circuitProposals.single().proposal
                val competing = proposal.content.plan.copy(title = "Human circuit")

                assertEquals(StagedPlanStatus.ACCEPTED, workspace.acceptStagedPlan(competing).status)
                assertEquals(StagedPlanStatus.STALE_PLAN, workspace.acceptCircuitProposal(proposal.proposalId).status)
                assertEquals("Human circuit", workspace.snapshot(MESSAGE_READY).stagedPlans.single().plan.title)
        }

            @Test
            fun editedAcceptanceRecordsStructuralCircuitRevisionSize() = runTest {
                val workspace = circuitWorkspace()
                val proposal = CircuitIntelligenceService(
                    workspace,
                    circuitProvider(mutableListOf()),
                    systemPrompt = "policy",
                ).propose(3).snapshot.circuitProposals.single().proposal
                val edited = proposal.content.plan.copy(
                    title = "Human-edited circuit",
                    stages = proposal.content.plan.stages.mapIndexed { index, stage ->
                        if (index == 1) stage.copy(title = "Human implementation", executionWorkflowId = "sequential-delivery-v1")
                        else stage
                    },
                    sourceProposal = com.orchard.backend.workspace.CircuitProposalReference(proposal.proposalId, proposal.hash),
                )

                val accepted = workspace.acceptStagedPlan(edited)
                val profile = accepted.snapshot.modelProfiles.single {
                    it.executionProfileId == "bounded-circuit-synthesis-v1"
                }

                assertEquals(StagedPlanStatus.ACCEPTED, accepted.status)
                assertTrue(!accepted.snapshot.stagedPlans.single().plan.acceptedProposalUnchanged)
                assertEquals(1, profile.acceptedAfterEditCount)
                assertEquals(3.0, profile.averageHumanRevisionFields)
            }

        private fun circuitWorkspace(): WorkspaceStore = WorkspaceStore().also { workspace ->
                workspace.beginBatch()
                check(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Project")))
                check(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Epic")))
                check(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_STORY, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Story")))
                check(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Contract")))
                check(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Implementation")))
                workspace.commitBatch()
        }

        private fun circuitProvider(prompts: MutableList<String>) = object : ModelProvider {
                override suspend fun triage(prompt: String): String = error("unused")
                override suspend fun plan(
                        prompt: String,
                        actionType: Int,
                        entityType: Int,
                        workspace: WorkspaceStore,
                ): String = error("unused")

                override suspend fun executeCircuitSynthesis(
                        prompt: String,
                        maxOutputTokens: Int,
                        contextWindowTokens: Int,
                ): com.orchard.backend.vector.ModelGeneration {
                        prompts += prompt
                        return com.orchard.backend.vector.ModelGeneration(circuitJson(), 1_000, 300)
                }

                override fun bindingProfile() = ModelBindingProfile(
                        "circuit-test",
                        "test",
                        "circuit-model",
                        20_000,
                        setOf(MODEL_CAPABILITY_STRICT_JSON),
                )
        }

        private fun circuitJson() = """
                {
                    "title": "Generated delivery circuit",
                    "stages": [
                        {
                            "stageId": "contract",
                            "title": "Establish boundary",
                            "executionWorkflowId": "contract-design-v1",
                            "executionWorkflowVersion": 1,
                            "nodes": [
                                {"nodeId": "contract", "workItemId": 4, "dependsOn": [], "consumes": [], "produces": []}
                            ]
                        },
                        {
                            "stageId": "implementation",
                            "title": "Implement",
                            "executionWorkflowId": "parallel-implementation-v1",
                            "executionWorkflowVersion": 1,
                            "nodes": [
                                {"nodeId": "implementation", "workItemId": 5, "dependsOn": ["contract"], "consumes": [], "produces": []}
                            ]
                        }
                    ],
                    "observations": ["Implementation follows the contract task."],
                    "assumptions": ["The task titles imply the intended order."]
                }
        """.trimIndent()
}

class WorkspaceApiTest {
    @Test
    fun designGovernanceRoutesActivateRecordAndAdmitAuthority() = testApplication {
        val workspace = WorkspaceStore()
        createTaskHierarchy(workspace)
        application { workspaceApi(workspace) }

        val activation = client.post("/api/projects/1/design-governance")
        val candidate = client.post("/api/designs") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"workItemId":2,"title":"System design","problem":"Govern exact execution authority.","scope":["Epic boundary"],"assumptions":["Hierarchy is authoritative"],"constraints":["Fail closed"],"alternatives":["Ungoverned execution"],"architecture":["Compile immutable contracts"],"failureModes":["Missing authority blocks execution"],"qualityAttributes":["Auditable"],"securityImpact":"No new privilege.","complianceImpact":"No new obligation.","requirements":[{"requirementId":"SYS-API","statement":"The system shall govern execution.","criteria":[{"description":"Execution authority is inspectable.","verification":"Inspect the admitted contract.","gate":"AUTOMATED"}]}]}"""
            )
        }
        val admission = client.post("/api/designs/1/admission")

        assertEquals(HttpStatusCode.Created, activation.status)
        assertEquals(HttpStatusCode.Created, candidate.status)
        assertEquals(HttpStatusCode.OK, admission.status)
        assertTrue(admission.bodyAsText().contains("\"status\":\"ADMITTED\""))
        assertTrue(admission.bodyAsText().contains("\"contractId\":1"))
    }

    @Test
    fun circuitProposalRoutesSeparateGenerationFromHumanAcceptance() = testApplication {
        val workspace = WorkspaceStore()
        createTaskHierarchy(workspace)
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")

            override suspend fun executeCircuitSynthesis(
                prompt: String,
                maxOutputTokens: Int,
                contextWindowTokens: Int,
            ) = com.orchard.backend.vector.ModelGeneration(
                """{
                    "title":"Generated circuit",
                    "stages":[{
                        "stageId":"delivery",
                        "title":"Delivery",
                        "executionWorkflowId":"sequential-delivery-v1",
                        "executionWorkflowVersion":1,
                        "nodes":[{"nodeId":"task","workItemId":4,"dependsOn":[],"consumes":[],"produces":[]}]
                    }],
                    "observations":[],
                    "assumptions":[]
                }""".trimIndent(),
                1_000,
                200,
            )

            override fun bindingProfile() = ModelBindingProfile(
                "api-circuit", "test", "circuit-model", 20_000, setOf(MODEL_CAPABILITY_STRICT_JSON)
            )
        }
        val service = CircuitIntelligenceService(workspace, provider, systemPrompt = "policy")
        application { workspaceApi(workspace, circuitIntelligence = service) }

        val generated = client.post("/api/staged-plan-proposals/3/generate")
        assertEquals(HttpStatusCode.Created, generated.status)
        val generatedBody = generated.bodyAsText()
        assertTrue(generatedBody.contains("\"circuitProposals\":[{"))
        assertTrue(!generatedBody.contains("\"stagedPlans\":[{"))

        val accepted = client.post("/api/staged-plan-proposals/1/accept")
        assertEquals(HttpStatusCode.Created, accepted.status)
        assertTrue(accepted.bodyAsText().contains("\"acceptedProposalUnchanged\":true"))
        assertTrue(accepted.bodyAsText().contains("\"proposalId\":1"))
    }

    @Test
    fun stagedPlanRouteRejectsOversizedBody() = testApplication {
        application { workspaceApi(WorkspaceStore()) }

        val response = client.post("/api/staged-plans") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat(64 * 1024 + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun stagedPlanRouteAcceptsAndProjectsCircuit() = testApplication {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Project")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Epic")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_STORY, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Story")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Task")))
        workspace.commitBatch()
        application { workspaceApi(workspace) }

        val response = client.post("/api/staged-plans") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    StagedDeliveryPlanSubmission(
                        3,
                        "API circuit",
                        listOf(
                            StagedPlanStageSubmission(
                                "delivery", "Delivery", "sequential-delivery-v1",
                                nodes = listOf(StagedPlanNodeSubmission("task", 4)),
                            )
                        ),
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.bodyAsText().contains("\"label\":\"1a\""))
        assertTrue(response.bodyAsText().contains("\"executionWorkflowId\":\"sequential-delivery-v1\""))
    }

    @Test
    fun machineResourcePolicyRouteUsesLiveCapacityAndPersistsUserShare() = testApplication {
        val resources = MachineResourceController(
            TransientMachineUsagePolicyStore(),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(10_000, 8_000, 8, 0.25)
            },
        )
        val provider = proposalProviderForApi()
        val service = DefinitionIntelligenceService(
            WorkspaceStore(),
            listOf(provider),
            TransientModelProfileSettingsStore(),
            resources,
            systemPrompt = "policy",
        )
        application { workspaceApi(WorkspaceStore(), service) }

        val updated = client.put("/api/machine-resources/policy") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(MachineUsagePolicy(20, 1_000, 2)))
        }
        val read = client.get("/api/machine-resources")

        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.bodyAsText().contains("\"capacityPercent\":20"))
        assertTrue(read.bodyAsText().contains("\"availableMemoryBytes\":8000"))
    }

    @Test
    fun modelProfileRouteReturnsServiceUnavailableForCorruptSettingsAuthority() = testApplication {
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "binding",
                "test",
                "model",
                20_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
        }
        val failingStore = object : ModelProfileSettingsStore {
            override fun load(): List<ModelProfileOverride> = error("checksum mismatch")
            override fun save(overrides: List<ModelProfileOverride>) = Unit
        }
        val service = DefinitionIntelligenceService(
            WorkspaceStore(),
            listOf(provider),
            failingStore,
            systemPrompt = "policy",
        )
        application { workspaceApi(WorkspaceStore(), service) }

        val response = client.get("/api/model-profiles")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    private fun proposalProviderForApi() = object : ModelProvider {
        override suspend fun triage(prompt: String): String = error("unused")
        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")
        override fun bindingProfile() = ModelBindingProfile(
            "binding",
            "test",
            "model",
            20_000,
            setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
    }

    @Test
    fun modelProfileRoutePersistsEffectiveUserAperture() = testApplication {
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(
                prompt: String,
                actionType: Int,
                entityType: Int,
                workspace: WorkspaceStore,
            ): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "local-binding",
                "test",
                "local-model",
                20_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
        }
        val service = DefinitionIntelligenceService(WorkspaceStore(), provider, systemPrompt = "policy")
        application { workspaceApi(WorkspaceStore(), service) }

        val response = client.put("/api/model-profiles/bounded-definition-reasoning-v1") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    ModelProfileOverride("bounded-definition-reasoning-v1", 7_000, 1_500, "local-binding")
                )
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"inputBudgetTokens\":7000"))
        assertTrue(body.contains("\"outputBudgetTokens\":1500"))
        assertTrue(body.contains("\"preferredBindingId\":\"local-binding\""))
    }

    @Test
    fun definitionProposalRoutesSupportHumanLlmIterationAndAcceptance() = testApplication {
        val workspace = WorkspaceStore()
        createTaskHierarchy(workspace)
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
            override suspend fun proposeWorkDefinition(prompt: String): String =
                """{"definition":${Json.encodeToString(definition())},"observations":["Existing behavior is documented."],"assumptions":["The loader remains the boundary."]}"""
            override fun modelIdentity(): String = "phi3:test"
            override fun bindingProfile() = ModelBindingProfile(
                "phi3:test",
                "test",
                "phi3:test",
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
        }
        val intelligence = DefinitionIntelligenceService(workspace, provider, systemPrompt = "proposal policy v1")
        application { workspaceApi(workspace, intelligence) }

        val first = client.post("/api/work-items/4/definition-proposals")
        val feedback = client.post("/api/definition-proposals/1/feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"content":"Do not change the storage format."}""")
        }
        val revised = client.post("/api/work-items/4/definition-proposals")
        val invalidAcceptance = client.post("/api/definition-proposals/3/accept") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    AcceptDefinitionProposalRequest(definition().copy(requestedOutcome = "x".repeat(4097)))
                )
            )
        }
        val acceptance = client.post("/api/definition-proposals/3/accept") {
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    AcceptDefinitionProposalRequest(
                        definition().copy(constraints = listOf("Existing saved orders remain compatible", "Keep public APIs stable"))
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, feedback.status)
        assertEquals(HttpStatusCode.Created, revised.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidAcceptance.status)
        assertEquals(HttpStatusCode.Created, acceptance.status)
        val acceptedBody = acceptance.bodyAsText()
        assertTrue(acceptedBody.contains("\"actor\":\"HUMAN\""))
        assertTrue(acceptedBody.contains("\"sourceProposal\":{\"proposalId\":4"))
        assertTrue(acceptedBody.contains("\"status\":\"READY\""))
    }

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
        assertTrue(body.contains("\"actor\":\"HUMAN\""))
        assertTrue(body.contains("\"sourceProposal\":{\"proposalId\":1"))
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
        assertFalse(WorkflowStepEngine.canPerform(definitionStep, COLLABORATOR_LOCAL_LLM, ACTION_FEEDBACK))
        assertFalse(WorkflowStepEngine.canPerform(definitionStep, COLLABORATOR_LOCAL_LLM, ACTION_ACCEPT))
        assertTrue(WorkflowStepEngine.canPerform(definitionStep, COLLABORATOR_HUMAN, ACTION_ACCEPT))
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