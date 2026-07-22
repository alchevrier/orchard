package com.orchard.frontend.ui

import com.orchard.frontend.network.ProjectGenesisRevisionResponse
import com.orchard.frontend.network.ProjectGenesisViewResponse
import com.orchard.frontend.network.ReportThreadLinkResponse
import com.orchard.frontend.network.ProjectReportResponse
import com.orchard.frontend.network.ReportRevisionProjectionResponse
import com.orchard.frontend.network.ReportRevisionResponse
import com.orchard.frontend.network.ReportScopeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectInboxWorkspaceTest {
    @Test
    fun `inbox filters toggle independently for backend intersection filtering`() {
        var filters = emptySet<ProjectInboxFilter>()

        filters = toggleInboxFilter(filters, ProjectInboxFilter.UNREAD)
        filters = toggleInboxFilter(filters, ProjectInboxFilter.BLOCKED)

        assertEquals(setOf(ProjectInboxFilter.UNREAD, ProjectInboxFilter.BLOCKED), filters)
        assertEquals(setOf("unread", "blocked"), filters.mapTo(mutableSetOf(), ProjectInboxFilter::wireValue))
        assertEquals(setOf(ProjectInboxFilter.BLOCKED), toggleInboxFilter(filters, ProjectInboxFilter.UNREAD))
    }

    @Test
    fun `correlated onboarding routes directly to project inbox`() {
        assertEquals(
            AppDestination(AppSurface.INBOX, projectId = 42),
            onboardingProjectDestination(42),
        )
    }

    @Test
    fun `requested canonical conversation wins over fallback selection`() {
        assertEquals(31L, focusedConversationId(31, 9, listOf(7, 9)))
        assertEquals(9L, focusedConversationId(null, 9, listOf(7, 9)))
        assertEquals(9L, focusedConversationId(null, 20, listOf(7, 9)))
    }

    @Test
    fun `first outcome uses direct authority throughout pre-admission phases`() {
        val architecture = genesis("ARCHITECTURE")
        val classification = genesis("CLASSIFICATION")
        val experience = genesis("EXPERIENCE")

        assertEquals(FirstOutcomeActionType.CREATE, firstOutcomeAction(architecture)?.type)
        assertEquals(FirstOutcomeActionType.CREATE, firstOutcomeAction(classification)?.type)
        assertEquals(FirstOutcomeActionType.CREATE, firstOutcomeAction(experience)?.type)
        assertEquals(FirstOutcomeActionType.CREATE, firstOutcomeAction(null)?.type)
        assertEquals(FirstOutcomeActionType.ADMIT, firstOutcomeAction(genesis("ADMISSION", firstEpicId = 7))?.type)
        assertNull(firstOutcomeAction(genesis("READY")))
    }

    @Test
    fun `board and inbox dispatch the exact resolved canonical thread`() {
        val link = ReportThreadLinkResponse(
            threadLinkId = 1,
            projectId = 42,
            targetType = "TICKET",
            targetId = 7,
            conversationId = 31,
        )
        val opened = mutableListOf<Pair<Long, Int>>()

        openResolvedThread(link) { conversationId, projectId -> opened += conversationId to projectId }
        openResolvedThread(link) { conversationId, projectId -> opened += conversationId to projectId }

        assertEquals(listOf(31L to 42, 31L to 42), opened)
    }

    @Test
    fun `project thread selects its canonical Inbox item`() {
        val reports = listOf(7L to 31L, 8L to 44L).map { (reportId, conversationId) ->
            ReportRevisionProjectionResponse(
                report = ProjectReportResponse(reportId, 42, scope = ReportScopeRequest("PROJECT", "42"), title = "Report $reportId"),
                revision = ReportRevisionResponse(reportId, 1),
                thread = ReportThreadLinkResponse(
                    threadLinkId = reportId,
                    projectId = 42,
                    targetType = "REPORT",
                    targetId = reportId,
                    conversationId = conversationId,
                ),
            )
        }

        assertEquals(8L, inboxReportForConversation(reports, 44)?.report?.reportId)
        assertNull(inboxReportForConversation(reports, 99))
    }

    @Test
    fun `delivery timeline combines command outcomes and correlated activity chronologically`() {
        val conversation = com.orchard.frontend.network.ConversationProjectionResponse(
            conversation = com.orchard.frontend.network.ConversationResponse(31, "Delivery", "HUMAN", "2026-07-22T00:00:00Z", "a"),
            commands = listOf(com.orchard.frontend.network.ConversationCommandViewResponse(
                proposal = com.orchard.frontend.network.ConversationCommandProposalResponse(
                    7, 31, 3, 1, "b", "START_WORKFLOW", "{\"workItemId\":4}", true,
                    "2026-07-22T00:00:01Z", "c",
                ),
                executions = listOf(com.orchard.frontend.network.ConversationCommandExecutionResponse(
                    8, 7, "c", "CORRELATED", "WORKFLOW_RUN", "12", "d", "abc123",
                    recordedAt = "2026-07-22T00:00:02Z", hash = "e",
                )),
            )),
            activities = listOf(com.orchard.frontend.network.ConversationActivityResponse(
                9, 31, 3, "ATTENTION", "Verification failed; Orchard will prepare a governed repair.",
                "WORKFLOW_RUN", "12", "f", "2026-07-22T00:00:03Z", "g",
            )),
            lastEventId = 9,
        )

        val timeline = inboxDeliveryTimeline(conversation)

        assertEquals(listOf("command:8", "activity:9"), timeline.map(InboxDeliveryTimelineItem::id))
        assertEquals("Start workflow", timeline.first().title)
        assertEquals("WORKFLOW_RUN", timeline.last().authorityType)
        assertEquals("12", timeline.last().authorityId)
    }

    @Test
    fun `delivery evidence uses latest record per kind`() {
        val run = com.orchard.frontend.network.WorkflowRunResponse(
            runId = 12,
            state = "EVIDENCE_BLOCKED",
            context = com.orchard.frontend.network.ContextManifestResponse(
                projectId = 42,
                workItemId = 4,
                repository = com.orchard.frontend.network.RepositoryHeadResponse("base123"),
                workspaceReservation = com.orchard.frontend.network.DispatchWorkspaceReservationResponse(
                    "ISOLATED", "dispatch-12", "/tmp/dispatch-12", "orchard/dispatch-12", "base123",
                ),
            ),
            workflow = com.orchard.frontend.network.ResolvedWorkflowResponse(
                "delivery",
                1,
                com.orchard.frontend.network.EvidenceContractResponse(
                    "delivery-evidence",
                    1,
                    listOf(
                        com.orchard.frontend.network.EvidenceRequirementResponse("SOURCE_DIFF", "Source changed"),
                        com.orchard.frontend.network.EvidenceRequirementResponse("TEST", "Tests pass"),
                    ),
                ),
            ),
            evidence = listOf(
                com.orchard.frontend.network.EvidenceRecordResponse(1, "TEST", "candidate-1", false),
                com.orchard.frontend.network.EvidenceRecordResponse(2, "SOURCE_DIFF", "candidate-2", true),
                com.orchard.frontend.network.EvidenceRecordResponse(3, "TEST", "candidate-2", true),
                com.orchard.frontend.network.EvidenceRecordResponse(4, "DIAGNOSTIC", "candidate-2", true),
            ),
        )

        val evidence = inboxDeliveryEvidence(run)

        assertEquals("candidate-2", evidence.repositoryRevision)
        assertEquals(2, evidence.passedGateCount)
        assertEquals(2, evidence.requiredGateCount)
        assertEquals(listOf("DIAGNOSTIC" to true, "SOURCE_DIFF" to true, "TEST" to true), evidence.evidence)
        assertEquals("orchard/dispatch-12", evidence.worktree)
    }

    private fun genesis(phase: String, firstEpicId: Int? = null) = ProjectGenesisViewResponse(
        projectId = 42,
        phase = phase,
        revision = ProjectGenesisRevisionResponse(
            genesisId = 1,
            projectId = 42,
            revision = 3,
            phase = phase,
            actor = "HUMAN",
            createdAt = "2026-07-21T00:00:00Z",
            hash = "a".repeat(64),
            firstEpicId = firstEpicId,
        ),
        progress = 40,
        nextQuestion = "What should happen next?",
        permittedAction = "ADVANCE",
    )
}