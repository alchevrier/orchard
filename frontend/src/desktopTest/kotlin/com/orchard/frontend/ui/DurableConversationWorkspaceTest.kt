package com.orchard.frontend.ui

import com.orchard.frontend.network.ConversationCommandExecutionResponse
import com.orchard.frontend.network.ConversationCommandProposalResponse
import com.orchard.frontend.network.ConversationCommandViewResponse
import com.orchard.frontend.network.ConversationActivityResponse
import com.orchard.frontend.network.EngineeringPracticeResponse
import com.orchard.frontend.network.EngineeringStandardRevisionResponse
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.ProjectGenesisViewResponse
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
        assertEquals(42, onboardedProjectId(onboarded))
        assertNull(onboardedProjectId(unrelated))
    }

    @Test
    fun `duplicate onboarding activity recovers project setup`() {
        val activity = ConversationActivityResponse(
            activityId = 1,
            conversationId = 1,
            kind = "INFO",
            summary = "Repository is already onboarded.",
            authorityType = "REPOSITORY_ONBOARDING",
            authorityId = "42",
            recordedAt = NOW,
            hash = HASH,
        )

        assertEquals(42, latestOnboardedProjectId(emptyList(), listOf(activity)))
    }

    @Test
    fun `project setup establishes standards before current genesis phase`() {
        val genesis = ProjectGenesisViewResponse(
            projectId = 42,
            phase = "ARCHITECTURE",
            progress = 40,
            nextQuestion = "What is the first vertical slice?",
            permittedAction = "ADVANCE",
        )
        val withoutStandards = ConductorProjectSetupState(
            projectId = 42,
            projectTitle = "Autumn",
            genesis = genesis,
            standards = EngineeringStandardsViewResponse(),
            epics = emptyList(),
        )
        assertEquals(ConductorSetupStep.STANDARDS, conductorSetupStep(withoutStandards))

        val practice = EngineeringPracticeResponse(
            practiceId = "TESTS",
            title = "Tests",
            category = "QUALITY",
            severity = "REQUIRED",
            applicability = "All changes",
            requirement = "Changes require tests.",
            requiredEvidence = listOf("test output"),
            remediation = "Add tests.",
        )
        val withStandards = withoutStandards.copy(
            standards = EngineeringStandardsViewResponse(
                standards = listOf(EngineeringStandardRevisionResponse(
                    standardId = 1,
                    projectId = 42,
                    revision = 1,
                    name = "Project standard",
                    practices = listOf(practice),
                    actor = "HUMAN",
                    createdAt = NOW,
                    hash = HASH,
                ))
            )
        )
        assertEquals(ConductorSetupStep.ARCHITECTURE, conductorSetupStep(withStandards))
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
