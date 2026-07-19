package com.orchard.frontend.ui

import com.orchard.frontend.network.ConversationCommandExecutionResponse
import com.orchard.frontend.network.ConversationCommandProposalResponse
import com.orchard.frontend.network.ConversationCommandViewResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurableConversationWorkspaceTest {
    @Test
    fun `next actions require correlated repository onboarding authority`() {
        val dispatched = command(
            capabilityId = "ONBOARD_REPOSITORY",
            execution = execution("DISPATCHED"),
        )
        val unrelated = command(
            capabilityId = "START_WORKFLOW",
            execution = execution("CORRELATED", "WORKFLOW_RUN", "9"),
        )

        assertNull(latestOnboardedProjectId(listOf(dispatched, unrelated)))

        val onboarded = command(
            capabilityId = "ONBOARD_REPOSITORY",
            execution = execution("CORRELATED", "REPOSITORY_ONBOARDING", "42"),
        )
        assertEquals(42, latestOnboardedProjectId(listOf(dispatched, unrelated, onboarded)))
    }

    private fun command(
        capabilityId: String,
        execution: ConversationCommandExecutionResponse,
    ) = ConversationCommandViewResponse(
        proposal = ConversationCommandProposalResponse(
            commandId = execution.commandId,
            conversationId = 1,
            sourceMessageId = 1,
            sourceMessageHash = HASH,
            capabilityId = capabilityId,
            payloadJson = "{}",
            mutation = true,
            proposedAt = NOW,
            hash = HASH,
        ),
        executions = listOf(execution),
    )

    private fun execution(
        state: String,
        downstreamType: String? = null,
        downstreamId: String? = null,
    ) = ConversationCommandExecutionResponse(
        executionId = 1,
        commandId = 1,
        commandHash = HASH,
        state = state,
        downstreamType = downstreamType,
        downstreamId = downstreamId,
        recordedAt = NOW,
        hash = HASH,
    )

    private companion object {
        const val NOW = "2026-07-19T00:00:00Z"
        val HASH = "a".repeat(64)
    }
}
