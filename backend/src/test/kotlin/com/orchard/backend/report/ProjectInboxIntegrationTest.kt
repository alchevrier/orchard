package com.orchard.backend.report

import com.orchard.backend.analysis.CLAIM_SUPPORTED
import com.orchard.backend.analysis.RepositoryCapabilityClaim
import com.orchard.backend.analysis.RepositoryClaimEvidence
import com.orchard.backend.analysis.newRepositoryObjectiveAssessment
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.conversation.ConversationCapabilityRegistry
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.ConversationInterpretation
import com.orchard.backend.conversation.ConversationInterpreter
import com.orchard.backend.conversation.FileConversationStore
import com.orchard.backend.conversation.InterpretedConversationTurn
import com.orchard.backend.conversation.SPEECH_DISCUSS
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileDefinitionCollaborationStore
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisFirstOutcomeStatus
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectInboxIntegrationTest {
    @Test
    fun `project inbox authority and canonical threads survive restart`() {
        val root = Files.createTempDirectory("orchard-project-inbox-")
        try {
            val repository = createRepository(root.resolve("repository"))
            val state = root.resolve("state")
            val firstBindings = FileRepositoryBindingStore(state)
            val firstWorkspace = workspace(state, firstBindings)
            firstWorkspace.beginBatch()
            assertTrue(firstWorkspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Orchard",
            )))
            firstWorkspace.commitBatch()
            assertEquals(RepositoryBindStatus.BOUND, firstWorkspace.bindRepository(1, repository.toString()).status)
            val revision = requireNotNull(firstBindings.resolveHead(1)).commitHash
            var assessment = null as com.orchard.backend.analysis.RepositoryObjectiveAssessment?
            val firstConductor = conductor(state)
            val firstReports = ProjectReportService(
                firstWorkspace,
                firstBindings,
                FileProjectReportStore(state),
                latestAssessment = { assessment },
                conversationConductor = firstConductor,
            )

            assertEquals("PENDING", firstReports.inbox(1).reports.single().revision.state)
            assessment = newRepositoryObjectiveAssessment(
                assessmentId = 1,
                projectId = 1,
                genesisRevision = 0,
                phase = "CLASSIFICATION",
                objective = "Understand the bound repository",
                repositoryRevision = revision,
                claims = listOf(RepositoryCapabilityClaim(
                    claimId = "repository-readme",
                    statement = "The repository has an established project description.",
                    status = CLAIM_SUPPORTED,
                    support = listOf(RepositoryClaimEvidence(
                        "README.md", "a".repeat(64), "The bound revision contains a project description.",
                    )),
                )),
                unresolvedQuestions = emptyList(),
                omittedRepositoryFileCount = 0,
                model = "integration-model",
                promptHash = "b".repeat(64),
                outputHash = "c".repeat(64),
            )
            assertTrue(firstReports.inbox(1).reports.any { it.revision.sourceType == "REPOSITORY_OBJECTIVE_ASSESSMENT" })

            val outcome = firstWorkspace.createProjectGenesisFirstOutcome(
                projectId = 1,
                title = "Operate the project from a correlated Inbox",
                baseRevision = 0,
                baseHash = null,
                confirmedProductIntent = "Correlate intended, implemented, tested, and observed project reality.",
            )
            assertEquals(ProjectGenesisFirstOutcomeStatus.CREATED, outcome.status)
            assertEquals(ProjectGenesisStatus.ADMITTED, firstWorkspace.admitProjectGenesis(1).status)
            val epicId = requireNotNull(outcome.outcomeId)
            firstWorkspace.beginBatch()
            assertTrue(firstWorkspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_STORY, DEFAULT_DELIVERY_WORKFLOW_ID,
                projectId = 1, epicId = epicId, title = "Project report operation",
            )))
            assertTrue(firstWorkspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_TASK, DEFAULT_DELIVERY_WORKFLOW_ID,
                projectId = 1, epicId = epicId, storyId = 3, title = "Publish workflow evidence",
            )))
            firstWorkspace.commitBatch()
            assertEquals(WorkDefinitionStatus.RECORDED, firstWorkspace.submitWorkDefinition(
                4,
                WorkDefinitionSubmission(
                    requestedOutcome = "Ticket state appears as an immutable report revision.",
                    currentBehavior = "The ticket has no workflow report.",
                    requiredBehavior = "The Inbox projects its revision-pinned workflow state.",
                    scope = listOf("Project Inbox"),
                    nonGoals = listOf("Streaming transport"),
                    constraints = listOf("Preserve workspace ticket authority"),
                    acceptanceCriteria = listOf(AcceptanceCriterion("Ticket report exists", "Run report integration test")),
                ),
            ).status)
            assertEquals(WorkflowStartStatus.CREATED, firstWorkspace.startWorkflow(4).status)
            firstReports.synchronizeTicketReports(1)
            val ticketReport = firstReports.inbox(1).reports.single { it.report.scope == ReportScope(REPORT_SCOPE_TICKET, "4") }
            firstReports.subscribe(1, ticketReport.report.reportId, ReportSubscriptionRequest(REPORT_MODE_ALL))
            val reportThread = firstReports.resolveThread(
                1, ReportThreadRequest(REPORT_TARGET_REPORT, ticketReport.report.reportId),
            )
            val ticketThread = firstReports.resolveThread(1, ReportThreadRequest(REPORT_TARGET_TICKET, 4))

            val recoveredBindings = FileRepositoryBindingStore(state)
            val recoveredWorkspace = workspace(state, recoveredBindings)
            val recoveredConductor = conductor(state)
            val recoveredReports = ProjectReportService(
                recoveredWorkspace,
                recoveredBindings,
                FileProjectReportStore(state),
                conversationConductor = recoveredConductor,
            )
            val recoveredInbox = recoveredReports.inbox(1)
            val recoveredTicket = recoveredInbox.reports.single {
                it.report.reportId == ticketReport.report.reportId && it.revision.sourceType == "WORKFLOW_RUN"
            }

            assertEquals(GENESIS_READY, recoveredWorkspace.snapshot(0).projectGenesis.single().phase)
            assertEquals("Operate the project from a correlated Inbox", recoveredWorkspace.entities().single { it.id == epicId }.title)
            assertEquals(1, recoveredWorkspace.snapshot(0).workflowRuns.size)
            assertTrue(recoveredTicket.subscribed)
            assertTrue(recoveredInbox.reports.any { it.revision.sourceType == "REPOSITORY_OBJECTIVE_ASSESSMENT" })
            assertEquals(reportThread, recoveredReports.resolveThread(
                1, ReportThreadRequest(REPORT_TARGET_REPORT, ticketReport.report.reportId),
            ))
            assertEquals(ticketThread, recoveredReports.resolveThread(1, ReportThreadRequest(REPORT_TARGET_TICKET, 4)))
            assertEquals(reportThread, ticketThread)
            assertEquals(1, recoveredConductor.list().size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun workspace(state: Path, bindings: FileRepositoryBindingStore) = WorkspaceStore(
        repository = FileWorkspaceRepository(state),
        repositoryBindings = bindings,
        workflowMemory = FileWorkflowMemoryStore(state),
        definitionStore = FileWorkDefinitionStore(state),
        collaborationStore = FileDefinitionCollaborationStore(state),
        projectGenesisStore = FileProjectGenesisStore(state),
        enforceProjectGenesis = true,
    )

    private fun conductor(state: Path) = ConversationConductorService(
        FileConversationStore(state),
        ConversationInterpreter { _, _ ->
            InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Scoped reply."))
        },
        ConversationCapabilityRegistry(emptyList()),
    )

    private fun createRepository(path: Path): Path {
        Files.createDirectories(path)
        Files.writeString(path.resolve("README.md"), "# Correlated project\n")
        git(path, "init", "--initial-branch=main")
        git(path, "config", "user.email", "orchard@example.test")
        git(path, "config", "user.name", "Orchard Test")
        git(path, "add", "README.md")
        git(path, "commit", "-m", "initial")
        return path
    }

    private fun git(path: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(path.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}