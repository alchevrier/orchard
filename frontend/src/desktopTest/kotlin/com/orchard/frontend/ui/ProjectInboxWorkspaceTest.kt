package com.orchard.frontend.ui

import com.orchard.frontend.network.ProjectGenesisRevisionResponse
import com.orchard.frontend.network.ProjectGenesisViewResponse
import com.orchard.frontend.network.ReportThreadLinkResponse
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