package com.orchard.backend.report

import com.orchard.backend.analysis.CLAIM_SUPPORTED
import com.orchard.backend.analysis.RepositoryCapabilityClaim
import com.orchard.backend.analysis.RepositoryClaimEvidence
import com.orchard.backend.analysis.newRepositoryObjectiveAssessment
import com.orchard.backend.agent.GenesisProposalResult
import com.orchard.backend.agent.GenesisProposalStatus
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.conversation.ConversationCapabilityRegistry
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.ConversationInterpretation
import com.orchard.backend.conversation.ConversationInterpreter
import com.orchard.backend.conversation.InterpretedConversationTurn
import com.orchard.backend.conversation.SPEECH_DISCUSS
import com.orchard.backend.conversation.TransientConversationStore
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.RepositoryView
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkflowStartStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectReportServiceTest {
    @Test
    fun `baseline compiler enriches once and becomes idle for the assessed revision`() = kotlinx.coroutines.test.runTest {
        val fixture = fixture()
        var assessmentCalls = 0
        val compiler = RepositoryBaselineCompiler(fixture.service) { projectId, _ ->
            assertEquals(1, projectId)
            assessmentCalls++
            fixture.assessment = assessment()
            GenesisProposalResult(GenesisProposalStatus.CREATED)
        }

        val first = compiler.tick()
        val second = compiler.tick()

        assertEquals(listOf(GenesisProposalStatus.CREATED), first.map { it.status })
        assertTrue(second.isEmpty())
        assertEquals(1, assessmentCalls)
        assertEquals(2, fixture.service.inbox(1).reports.size)
    }

    @Test
    fun `baseline compiler publishes a durable actionable model diagnostic`() = kotlinx.coroutines.test.runTest {
        val fixture = fixture()
        var assessmentCalls = 0
        val compiler = RepositoryBaselineCompiler(fixture.service) { _, _ ->
            assessmentCalls++
            GenesisProposalResult(GenesisProposalStatus.MODEL_UNAVAILABLE, diagnostic = "No compatible baseline model is available.")
        }

        compiler.tick()
        compiler.tick()

        val diagnostic = fixture.service.inbox(1).reports.single { it.revision.sourceType == "REPOSITORY_BASELINE_DIAGNOSTIC" }
        assertEquals(1, assessmentCalls)
        assertTrue(diagnostic.actionRequired)
        assertEquals("No compatible baseline model is available.", diagnostic.items.single().summary)
    }

    @Test
    fun `capacity delay remains pending and retries once per capacity window`() = kotlinx.coroutines.test.runTest {
        var currentTime = Instant.parse("2026-07-21T00:00:00Z")
        val fixture = fixture { currentTime.toString() }
        var assessmentCalls = 0
        val compiler = RepositoryBaselineCompiler(fixture.service) { _, _ ->
            assessmentCalls++
            GenesisProposalResult(GenesisProposalStatus.RESOURCE_CAPACITY_UNAVAILABLE)
        }

        compiler.tick()
        compiler.tick()

        val first = fixture.service.inbox(1).reports.single {
            it.revision.sourceType == "REPOSITORY_BASELINE_DIAGNOSTIC"
        }
        assertEquals(1, assessmentCalls)
        assertEquals("PENDING", first.revision.state)
        assertEquals("Retry when capacity is available", first.items.single().title)
        assertEquals(
            "Orchard will retry the repository baseline automatically when Architect capacity is available. No action is needed.",
            first.items.single().summary,
        )
        assertFalse(first.actionRequired)
        assertFalse(first.blocked)

        currentTime = currentTime.plusSeconds(31)
        compiler.tick()
        compiler.tick()

        assertEquals(2, assessmentCalls)
        assertEquals(1, fixture.service.inbox(1).reports.count {
            it.revision.sourceType == "REPOSITORY_BASELINE_DIAGNOSTIC"
        })
    }

    @Test
    fun `pending baseline enriches idempotently with exact assessment provenance`() {
        val fixture = fixture()

        val pending = fixture.service.inbox(1)
        assertEquals(1, pending.reports.size)
        assertEquals(REVISION, pending.reports.single().revision.repositoryRevision)
        fixture.assessment = assessment()

        val enriched = fixture.service.inbox(1)
        val assessed = enriched.reports.single { it.revision.genesisRevision != null }
        assertEquals(2, enriched.reports.size)
        assertEquals(REVISION, assessed.revision.repositoryRevision)
        assertEquals(3, assessed.revision.genesisRevision)
        assertEquals(fixture.assessment!!.hash, assessed.revision.sourceHash)
        assertEquals("repository-shape", assessed.items.single().itemKey)
        assertEquals(2, fixture.service.inbox(1).reports.size)
    }

    @Test
    fun `subscription successors and reads drive inbox filters`() {
        val fixture = fixture()
        val report = fixture.service.create(1, userReport(ReportScope(REPORT_SCOPE_PROJECT, "1"))).report

        fixture.service.subscribe(1, report.reportId, ReportSubscriptionRequest(REPORT_MODE_ALL))
        fixture.service.pause(1, report.reportId, ReportActorRequest())
        val resumed = fixture.service.resume(1, report.reportId, ReportActorRequest())
        assertEquals(3, resumed.revision)
        val beforeRead = fixture.service.inbox(1, requestedFilters = setOf("subscribed"))
        val userRevision = beforeRead.reports.single { it.report.reportId == report.reportId }
        assertTrue(userRevision.unread)
        fixture.service.markRead(1, report.reportId, userRevision.revision.revision, ReportActorRequest())
        assertFalse(fixture.service.inbox(1).reports.single { it.report.reportId == report.reportId }.unread)
    }

    @Test
    fun `subscription projection is isolated by actor`() {
        val fixture = fixture()
        val report = fixture.service.create(1, userReport(ReportScope(REPORT_SCOPE_PROJECT, "1"))).report

        fixture.service.subscribe(1, report.reportId, ReportSubscriptionRequest(REPORT_MODE_ALL, actor = "ALICE"))
        fixture.service.subscribe(1, report.reportId, ReportSubscriptionRequest(REPORT_MODE_ACTION_REQUIRED, actor = "BOB"))
        fixture.service.pause(1, report.reportId, ReportActorRequest(actor = "ALICE"))

        val alice = fixture.service.inbox(1, actor = "ALICE").reports.single { it.report.reportId == report.reportId }
        val bob = fixture.service.inbox(1, actor = "BOB").reports.single { it.report.reportId == report.reportId }

        assertFalse(alice.subscribed)
        assertEquals(REPORT_MODE_ALL, alice.subscription?.mode)
        assertTrue(bob.subscribed)
        assertEquals(REPORT_MODE_ACTION_REQUIRED, bob.subscription?.mode)
    }

    @Test
    fun `workflow state publishes one idempotent ticket report revision`() {
        val bindings = FixedRepositoryBindings()
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Project")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, title = "Outcome")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_STORY, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Story")))
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Build inbox")))
        workspace.commitBatch()
        assertEquals(WorkDefinitionStatus.RECORDED, workspace.submitWorkDefinition(4, WorkDefinitionSubmission(
            requestedOutcome = "The project has an inbox.",
            currentBehavior = "Ticket updates require manual correlation.",
            requiredBehavior = "Ticket updates appear as report revisions.",
            scope = listOf("Project reports"),
            nonGoals = listOf("Streaming transport"),
            constraints = listOf("Preserve ticket authority"),
            acceptanceCriteria = listOf(AcceptanceCriterion("Report is projected", "Run report tests")),
        )).status)
        assertEquals(WorkflowStartStatus.CREATED, workspace.startWorkflow(4).status)
        val service = ProjectReportService(workspace, bindings, TransientProjectReportStore())

        val first = service.inbox(1)
        val second = service.inbox(1)
        val ticketReports = second.reports.filter { it.report.scope == ReportScope(REPORT_SCOPE_TICKET, "4") }

        assertEquals(1, first.reports.count { it.report.scope.type == REPORT_SCOPE_TICKET })
        assertEquals(1, ticketReports.size)
        assertEquals("CONTEXT_READY", workspace.snapshot(0).workflowRuns.single().state)
        assertEquals(REVISION, ticketReports.single().revision.repositoryRevision)
        assertEquals("Repository context is ready", ticketReports.single().items.single().title)
    }

    @Test
    fun `ticket scope rejects another project and canonical thread is unique`() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.service.create(1, userReport(ReportScope(REPORT_SCOPE_TICKET, "4")))
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.service.resolveThread(1, ReportThreadRequest(REPORT_TARGET_TICKET, 4))
        }

        val first = fixture.service.resolveThread(1, ReportThreadRequest(REPORT_TARGET_TICKET, 2))
        val second = fixture.service.resolveThread(1, ReportThreadRequest(REPORT_TARGET_TICKET, 2))
        assertEquals(first, second)
        assertEquals(1, fixture.conductor.list().size)
    }

    private fun fixture(now: () -> String = { "2026-07-21T00:00:00Z" }): Fixture {
        val workspace = WorkspaceStore()
        createProjectAndEpic(workspace, "First")
        createProjectAndEpic(workspace, "Second")
        val store = TransientProjectReportStore()
        val conductor = ConversationConductorService(
            TransientConversationStore(),
            ConversationInterpreter { _, _ -> InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Ready.")) },
            ConversationCapabilityRegistry(emptyList()),
        )
        val fixture = Fixture(workspace, store, conductor)
        fixture.service = ProjectReportService(
            workspace,
            FixedRepositoryBindings(),
            store,
            latestAssessment = { fixture.assessment },
            conversationConductor = conductor,
            now = now,
        )
        return fixture
    }

    private fun createProjectAndEpic(workspace: WorkspaceStore, title: String) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = title)))
        val projectId = workspace.lastCreatedId
        assertTrue(workspace.applyIntent(DocumentIntent(
            ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = projectId, title = "$title outcome",
        )))
        workspace.commitBatch()
    }

    private fun userReport(scope: ReportScope) = CreateProjectReportRequest(
        clientRequestId = "request-1",
        scope = scope,
        title = "User report",
        items = listOf(ReportItemInput("item", "FINDING", "OPEN", "Finding", "A useful finding.")),
    )

    private fun assessment() = newRepositoryObjectiveAssessment(
        assessmentId = 7,
        projectId = 1,
        genesisRevision = 3,
        phase = "CLASSIFICATION",
        objective = "Understand the repository",
        repositoryRevision = REVISION,
        claims = listOf(RepositoryCapabilityClaim(
            "repository-shape",
            "The repository has an established backend.",
            CLAIM_SUPPORTED,
            support = listOf(RepositoryClaimEvidence("backend/build.gradle.kts", "b".repeat(64), "Kotlin backend module.")),
        )),
        unresolvedQuestions = emptyList(),
        omittedRepositoryFileCount = 0,
        model = "test-model",
        promptHash = "c".repeat(64),
        outputHash = "d".repeat(64),
    )

    private class Fixture(
        val workspace: WorkspaceStore,
        val store: ProjectReportStore,
        val conductor: ConversationConductorService,
    ) {
        var assessment: com.orchard.backend.analysis.RepositoryObjectiveAssessment? = null
        lateinit var service: ProjectReportService
    }

    private class FixedRepositoryBindings : RepositoryBindingStore {
        override fun bind(projectId: Int, requestedPath: String) = Unit
        override fun views(projectIds: Set<Int>) = projectIds.filter { it == 1 }.associateWith {
            RepositoryView(it, "/repository", available = true)
        }
        override fun resolveHead(projectId: Int) = if (projectId == 1) {
            RepositoryHead(1, "/repository", REVISION, "main", "", clean = true)
        } else null
    }

    private companion object {
        const val REVISION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}