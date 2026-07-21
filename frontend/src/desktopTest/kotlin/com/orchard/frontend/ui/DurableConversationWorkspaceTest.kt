package com.orchard.frontend.ui

import com.orchard.frontend.network.CompanyProjectResponse
import com.orchard.frontend.network.CompanyWorkspaceResponse
import com.orchard.frontend.network.ConversationCommandExecutionResponse
import com.orchard.frontend.network.ConversationCommandAdmissionResponse
import com.orchard.frontend.network.ConversationCommandProposalResponse
import com.orchard.frontend.network.ConversationCommandViewResponse
import com.orchard.frontend.network.ConversationActivityResponse
import com.orchard.frontend.network.EngineeringPracticeResponse
import com.orchard.frontend.network.EngineeringStandardRevisionResponse
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.ProjectGenesisViewResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `authority rail appears only for pending mutation admission`() {
        val readOnly = command(capabilityId = "REQUEST_STATUS", execution = execution("CORRELATED"), mutation = false)
        val completedMutation = command(
            capabilityId = "START_WORKFLOW",
            execution = execution("CORRELATED", "WORKFLOW_RUN", "9"),
            mutation = true,
            admitted = true,
        )
        val pendingMutation = command(capabilityId = "START_WORKFLOW", mutation = true)

        assertFalse(shouldShowAuthorityRail(listOf(readOnly, completedMutation), null))
        assertTrue(shouldShowAuthorityRail(listOf(readOnly, pendingMutation), null))
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
            nextQuestion = "What should people be able to accomplish first?",
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
        assertEquals(
            ConductorSetupStep.ADMISSION,
            conductorSetupStep(withStandards.copy(genesis = genesis.copy(phase = "BLUEPRINT"))),
        )
    }

    @Test
    fun `ready project offers grounded next actions`() {
        val state = ConductorProjectSetupState(
            projectId = 42,
            projectTitle = "Autumn",
            genesis = ProjectGenesisViewResponse(
                projectId = 42,
                phase = "READY",
                progress = 100,
                nextQuestion = "Implementation may proceed.",
                permittedAction = "INSPECT",
            ),
            standards = EngineeringStandardsViewResponse(),
            epics = listOf(7 to "Deliver the first order book outcome"),
        )

        assertEquals(
            listOf("Plan another outcome", "Show status"),
            readyActions(state).map(ReadyAction::label),
        )
        assertTrue(readyActions(state).first().prompt.contains("Autumn"))
    }

    @Test
    fun `company delivery explains that repository analysis needs no user action`() {
        val response = CompanyWorkspaceResponse(
            workspace = com.orchard.frontend.network.WorkspaceSnapshotResponse(
                workflowRuns = listOf(com.orchard.frontend.network.WorkflowRunResponse(
                    runId = 9,
                    state = "CONTEXT_READY",
                    context = com.orchard.frontend.network.ContextManifestResponse(
                        projectId = 42,
                        workItemId = 7,
                        repository = com.orchard.frontend.network.RepositoryHeadResponse("abc"),
                    ),
                    workflow = com.orchard.frontend.network.ResolvedWorkflowResponse(
                        "delivery",
                        1,
                        com.orchard.frontend.network.EvidenceContractResponse("delivery-evidence", 1, emptyList()),
                    ),
                )),
            ),
            companyProjects = listOf(CompanyProjectResponse(
                projectId = 42,
                phase = "IMPLEMENTATION",
                health = "HEALTHY",
            )),
        )

        assertEquals(
            "Orchard is analyzing repository evidence before coding the first outcome. No action is needed. Keep Orchard running; you can leave this screen and return later. This status updates automatically.",
            companyDeliveryGuidance(response, 42)?.message,
        )
        assertFalse(requireNotNull(companyDeliveryGuidance(response, 42)).actionRequired)
    }

    @Test
    fun `generated candidate changes setup copy to explicit review state`() {
        assertEquals(
            "Describe the experience",
            proposalStepHeading(ConductorSetupStep.EXPERIENCE, hasProposal = false),
        )
        assertEquals(
            "Review the experience proposal",
            proposalStepHeading(ConductorSetupStep.EXPERIENCE, hasProposal = true),
        )
        assertEquals(
            "Plan the first working outcome",
            proposalStepHeading(ConductorSetupStep.ARCHITECTURE, hasProposal = false),
        )
        assertEquals(
            "Design how to deliver the first outcome",
            proposalStepHeading(
                ConductorSetupStep.ARCHITECTURE,
                hasProposal = false,
                hasFirstOutcome = true,
            ),
        )
    }

    @Test
    fun `experience proposal prompt recovers from durable product intent`() {
        assertEquals(
            "Find gaps between ADRs and implementation.",
            proposalPromptDefault(
                ConductorSetupStep.EXPERIENCE,
                "Find gaps between ADRs and implementation.",
                "Who is this for?",
            ),
        )
        assertEquals(
            "Who is this for?",
            proposalPromptDefault(ConductorSetupStep.EXPERIENCE, "", "Who is this for?"),
        )
    }

    @Test
    fun `architecture prompt changes from question to recorded outcome`() {
        val question = "What should people be able to accomplish first?"

        assertEquals(
            question,
            proposalPromptDefault(ConductorSetupStep.ARCHITECTURE, "", question),
        )
        assertEquals(
            "Complete the first user journey",
            proposalPromptDefault(
                ConductorSetupStep.ARCHITECTURE,
                "",
                question,
                "Complete the first user journey",
            ),
        )
    }

    @Test
    fun `proposal feedback creates an explicit refinement turn`() {
        assertTrue(proposalNeedsRefinement(listOf("Which transport is authoritative?"), null))
        assertTrue(proposalNeedsRefinement(emptyList(), "The proposal is missing a storage decision."))
        assertFalse(proposalNeedsRefinement(emptyList(), null))
        assertEquals("Refine proposal", proposalActionLabel(hasProposal = true, needsRefinement = true))
        assertEquals("Regenerate proposal", proposalActionLabel(hasProposal = true, needsRefinement = false))
        assertEquals(
            "Complete the architecture proposal",
            proposalStepHeading(
                ConductorSetupStep.ARCHITECTURE,
                hasProposal = true,
                hasFirstOutcome = true,
                needsRefinement = true,
            ),
        )
    }

    @Test
    fun `candidate questions retain a forward action`() {
        assertEquals("Continue with provisional proposal", proposalApplyActionLabel(hasOptionalQuestions = true))
        assertEquals("Apply proposal", proposalApplyActionLabel(hasOptionalQuestions = false))
    }

    @Test
    fun `preliminary assessment gaps do not become blocking candidate questions`() {
        assertEquals(emptyList(), candidateBlockingQuestions(null))
        assertEquals(
            listOf("Which transport should the design make authoritative?"),
            candidateBlockingQuestions(listOf("", "Which transport should the design make authoritative?")),
        )
    }

    @Test
    fun `continuation objective removes obsolete repository search instruction`() {
        assertEquals(
            "Implement the first order book outcome.",
            proposalObjectiveForContinuation(
                "Implement the first order book outcome. Ask explicit questions wherever the implementation cannot be established from repository evidence.",
            ),
        )
    }

    @Test
    fun `proposal refinement preserves brief and correlates each answer to its question`() {
        val first = "Which transport is authoritative?"
        val second = "How is state stored?"

        assertEquals(
            """Deliver the order book.

Answers to the Architect's questions:
Question: Which transport is authoritative?
Answer: The existing UDP gateway.
Question: How is state stored?
Answer: Array-backed off-heap shards.""",
            proposalRefinementPrompt(
                "Deliver the order book.",
                listOf(first, second),
                mapOf(first to "The existing UDP gateway.", second to "Array-backed off-heap shards."),
            ),
        )
    }

    @Test
    fun `refinement presents direct candidate questions`() {
        assertEquals(
            "Which transport is authoritative?",
            refinementQuestionPrompt("Which transport is authoritative?"),
        )
    }

    @Test
    fun `current repository assessment has an explicit proposal action`() {
        assertEquals(
            "Submit answers and refine proposal",
            repositoryAssessmentActionLabel(
                hasProposal = false,
                hasAssessment = true,
                needsRefinement = true,
            ),
        )
        assertEquals(
            "Next: form proposal",
            repositoryAssessmentActionLabel(
                hasProposal = false,
                hasAssessment = true,
                needsRefinement = false,
            ),
        )
        assertEquals(
            "Regenerate proposal",
            repositoryAssessmentActionLabel(
                hasProposal = true,
                hasAssessment = true,
                needsRefinement = false,
            ),
        )
    }

    private fun command(
        capabilityId: String,
        execution: ConversationCommandExecutionResponse? = null,
        mutation: Boolean = true,
        admitted: Boolean = false,
    ) = ConversationCommandViewResponse(
        proposal = ConversationCommandProposalResponse(
            commandId = execution?.commandId ?: 1,
            conversationId = 1,
            sourceMessageId = 1,
            sourceMessageHash = HASH,
            capabilityId = capabilityId,
            payloadJson = "{}",
            mutation = mutation,
            proposedAt = NOW,
            hash = HASH,
        ),
        admission = if (admitted) ConversationCommandAdmissionResponse(
            admissionId = 1,
            commandId = execution?.commandId ?: 1,
            commandHash = HASH,
            actor = "HUMAN",
            admittedAt = NOW,
            hash = HASH,
        ) else null,
        executions = listOfNotNull(execution),
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
