package com.orchard.backend.standards

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CampaignResolutionStoreTest {
    @Test
    fun `case proposal and admission replay as immutable authority`() {
        val directory = createTempDirectory("orchard-campaign-resolution-")
        val store = FileCampaignResolutionStore(directory)
        val case = resolutionCase()
        val proposal = proposal(case)
        val admission = admission(case, proposal)

        store.appendCase(case)
        store.appendProposal(proposal)
        store.appendAdmission(admission)

        val replayed = FileCampaignResolutionStore(directory)
        assertEquals(listOf(case), replayed.cases())
        assertEquals(listOf(proposal), replayed.proposals())
        assertEquals(listOf(admission), replayed.admissions())
        assertEquals(admission, campaignResolutionViews(replayed).single().admission)
        assertEquals(3, Files.readAllLines(directory.resolve("campaign-resolutions.jsonl")).size)
    }

    @Test
    fun `proposal must pin exact terminal evaluation`() {
        val store = TransientCampaignResolutionStore()
        val case = resolutionCase()
        store.appendCase(case)

        assertFailsWith<IllegalArgumentException> {
            store.appendProposal(proposal(case).copy(evaluationHash = "f".repeat(64)))
        }
    }

    @Test
    fun `one admitted decision resolves a case`() {
        val store = TransientCampaignResolutionStore()
        val case = resolutionCase()
        val first = proposal(case)
        store.appendCase(case)
        store.appendProposal(first)
        store.appendAdmission(admission(case, first))

        assertFailsWith<IllegalArgumentException> {
            store.appendProposal(newCampaignResolutionProposal(first.copy(proposalId = 2, hash = "")))
        }
        assertFailsWith<IllegalArgumentException> {
            store.appendAdmission(newCampaignResolutionAdmission(admission(case, first).copy(admissionId = 2, hash = "")))
        }
    }

    @Test
    fun `non delivery decision cannot invent successor work`() {
        val store = TransientCampaignResolutionStore()
        val case = resolutionCase()
        val abandon = newCampaignResolutionProposal(
            proposal(case).copy(action = RESOLUTION_ACTION_ABANDON, proposedBacklog = emptyList(), hash = "")
        )
        store.appendCase(case)
        store.appendProposal(abandon)

        store.appendAdmission(admission(case, abandon).copy(admittedNodes = emptyList()).let(::newCampaignResolutionAdmission))
        assertEquals(emptyList(), store.admissions().single().admittedNodes)
    }

    private fun resolutionCase() = newCampaignResolutionCase(
        CampaignResolutionCase(
            caseId = 1,
            campaignId = 7,
            projectId = 1,
            evaluationId = 9,
            evaluationHash = "a".repeat(64),
            repositoryRevision = "b".repeat(40),
            cause = RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED,
            practiceIds = listOf("AUTHORITY_INTEGRITY"),
            openedAt = "2026-07-18T00:00:00Z",
            hash = "",
        )
    )

    private fun proposal(case: CampaignResolutionCase) = newCampaignResolutionProposal(
        CampaignResolutionProposal(
            proposalId = 1,
            caseId = case.caseId,
            evaluationHash = case.evaluationHash,
            action = RESOLUTION_ACTION_ADDITIONAL_REMEDIATION,
            rationale = "The admitted remediation was insufficient.",
            practiceIds = case.practiceIds,
            instructions = "Add a bounded recovery test for the unresolved authority path.",
            proposedBacklog = listOf(
                CampaignResolutionBacklogNode("RECOVERY_EPIC", null, BACKLOG_EPIC, "Recover authority", "Resolve the terminal campaign.", case.practiceIds),
                CampaignResolutionBacklogNode("RECOVERY_STORY", "RECOVERY_EPIC", BACKLOG_STORY, "Prove recovery", "Establish accepted evidence.", case.practiceIds),
                CampaignResolutionBacklogNode(
                    "RECOVERY_TASK",
                    "RECOVERY_STORY",
                    BACKLOG_TASK,
                    "Add recovery evidence",
                    "Implement the bounded recovery.",
                    case.practiceIds,
                    listOf("The authority path is verified."),
                    listOf("./gradlew test --no-daemon"),
                ),
            ),
            actor = "ARCHITECT",
            proposedAt = "2026-07-18T00:01:00Z",
            hash = "",
        )
    )

    private fun admission(case: CampaignResolutionCase, proposal: CampaignResolutionProposal) = newCampaignResolutionAdmission(
        CampaignResolutionAdmission(
            admissionId = 1,
            caseId = case.caseId,
            proposalId = proposal.proposalId,
            proposalHash = proposal.hash,
            actor = "HUMAN",
            admittedAt = "2026-07-18T00:02:00Z",
            admittedNodes = proposal.proposedBacklog.mapIndexed { index, node -> AdmittedBacklogNode(node.nodeId, index + 11) },
            hash = "",
        )
    )
}
