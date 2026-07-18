package com.orchard.backend.standards

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.RepositoryHead
import com.orchard.backend.workspace.WorkspaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CampaignResolutionServiceTest {
    @Test
    fun `terminal campaign requires explicit admission before successor authority exists`() = runTest {
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val revision = "a".repeat(40)
        val standards = TransientEngineeringStandardsStore()
        val standard = standard(projectId)
        val scan = scan(projectId, standard, revision)
        standards.appendStandard(standard)
        standards.appendScan(scan)
        val campaigns = TransientRemediationCampaignStore()
        val campaign = campaign(projectId, standard, scan)
        campaigns.appendCampaign(campaign)
        campaigns.appendEvaluation(evaluation(campaign, scan, revision))
        val resolutions = FailOnceAdmissionStore()
        val bindings = FixedRepositoryBindings(projectId, revision)
        val service = CampaignResolutionService(
            workspace,
            bindings,
            standards,
            campaigns,
            listOf(FixedResolutionModel(resolutionOutput())),
            resolutions,
        )

        assertEquals(1, service.reconcileCases())
        assertEquals(0, service.reconcileCases())
        val case = resolutions.cases().single()
        assertEquals(RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED, case.cause)
        assertEquals(listOf("AUTHORITY_INTEGRITY"), case.practiceIds)
        assertEquals(1, campaigns.campaigns().size)

        val proposed = service.propose(case.caseId)
        assertEquals(CampaignResolutionProposalStatus.CREATED, proposed.status)
        val proposal = assertNotNull(proposed.proposal)
        assertEquals(3, proposal.proposedBacklog.size)
        assertEquals(1, campaigns.campaigns().size)
        assertEquals(1, workspace.entityCount)

        bindings.revision = "9".repeat(40)
        val drifted = service.admit(proposal.proposalId)
        assertEquals(CampaignResolutionAdmissionStatus.REPOSITORY_DRIFTED, drifted.status)
        assertEquals(1, workspace.entityCount)
        assertEquals(1, campaigns.campaigns().size)

        bindings.revision = revision
        val interrupted = service.admit(proposal.proposalId)
        assertEquals(CampaignResolutionAdmissionStatus.STORAGE_UNAVAILABLE, interrupted.status)
        assertEquals(4, workspace.entityCount)
        assertEquals(1, campaigns.campaigns().size)

        val admitted = service.admit(proposal.proposalId)
        assertEquals(CampaignResolutionAdmissionStatus.ADMITTED, admitted.status)
        val admission = assertNotNull(admitted.admission)
        val successor = assertNotNull(admitted.successorCampaign)
        assertEquals(3, admission.admittedNodes.size)
        assertEquals(campaign.campaignId, successor.successorSource?.predecessorCampaignId)
        assertEquals(proposal.hash, successor.successorSource?.resolutionProposalHash)
        assertEquals(scan.scanId, successor.seedScanId)
        assertEquals(revision, successor.seedRepositoryRevision)
        assertEquals(2, campaigns.campaigns().size)
        assertEquals(4, workspace.entityCount)
        assertEquals(listOf(ENTITY_EPIC, ENTITY_STORY, ENTITY_TASK), (1 until workspace.entityCount).map { workspace.entityAt(it).type })

        val repeated = service.admit(proposal.proposalId)
        assertEquals(CampaignResolutionAdmissionStatus.CASE_RESOLVED, repeated.status)
        assertEquals(2, campaigns.campaigns().size)
        assertEquals(4, workspace.entityCount)
    }

    @Test
    fun `admitted non delivery decision creates no successor authority`() = runTest {
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val revision = "a".repeat(40)
        val standards = TransientEngineeringStandardsStore()
        val standard = standard(projectId)
        val scan = scan(projectId, standard, revision)
        standards.appendStandard(standard)
        standards.appendScan(scan)
        val campaigns = TransientRemediationCampaignStore()
        val campaign = campaign(projectId, standard, scan)
        campaigns.appendCampaign(campaign)
        campaigns.appendEvaluation(evaluation(campaign, scan, revision))
        val resolutions = TransientCampaignResolutionStore()
        val service = CampaignResolutionService(
            workspace,
            FixedRepositoryBindings(projectId, revision),
            standards,
            campaigns,
            listOf(FixedResolutionModel(abandonOutput())),
            resolutions,
        )
        service.reconcileCases()

        val proposal = assertNotNull(service.propose(resolutions.cases().single().caseId).proposal)
        val result = service.admit(proposal.proposalId)

        assertEquals(CampaignResolutionAdmissionStatus.ADMITTED, result.status)
        assertEquals(emptyList(), assertNotNull(result.admission).admittedNodes)
        assertEquals(null, result.successorCampaign)
        assertEquals(1, workspace.entityCount)
        assertEquals(1, campaigns.campaigns().size)
    }

    @Test
    fun `escalated evidence opens specific resolution causes`() {
        assertEquals(
            RESOLUTION_CAUSE_EVIDENCE_CONFLICT,
            resolutionCause(
                CampaignPracticeEvaluation(
                    "AUTHORITY_INTEGRITY",
                    CONFORMANCE_PARTIAL,
                    CONFORMANCE_CONFLICTING,
                    false,
                    false,
                )
            ),
        )
        assertEquals(
            RESOLUTION_CAUSE_REGRESSION,
            resolutionCause(
                CampaignPracticeEvaluation(
                    "AUTHORITY_INTEGRITY",
                    CONFORMANCE_CONFORMING,
                    CONFORMANCE_NONCONFORMING,
                    false,
                    true,
                )
            ),
        )
    }

    private fun resolutionCause(practice: CampaignPracticeEvaluation): String {
        val workspace = WorkspaceStore()
        val projectId = createProject(workspace)
        val revision = "a".repeat(40)
        val standards = TransientEngineeringStandardsStore()
        val standard = standard(projectId)
        val scan = scan(projectId, standard, revision)
        standards.appendStandard(standard)
        standards.appendScan(scan)
        val campaigns = TransientRemediationCampaignStore()
        val campaign = campaign(projectId, standard, scan)
        campaigns.appendCampaign(campaign)
        campaigns.appendEvaluation(
            newRemediationCampaignEvaluation(
                evaluation(campaign, scan, revision).copy(
                    practices = listOf(practice),
                    state = CAMPAIGN_ESCALATED,
                    hash = "",
                )
            )
        )
        val resolutions = TransientCampaignResolutionStore()
        CampaignResolutionService(
            workspace,
            FixedRepositoryBindings(projectId, revision),
            standards,
            campaigns,
            emptyList(),
            resolutions,
        ).reconcileCases()
        return resolutions.cases().single().cause
    }

    private fun createProject(workspace: WorkspaceStore): Int {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Orchard")))
        val projectId = workspace.lastCreatedId
        workspace.commitBatch()
        return projectId
    }

    private fun standard(projectId: Int) = newEngineeringStandardRevision(
        standardId = 1,
        projectId = projectId,
        revision = 1,
        name = "Project standard",
        practices = listOf(defaultEngineeringPractices().first()),
        actor = "HUMAN",
        createdAt = "2026-07-18T00:00:00Z",
    )

    private fun scan(projectId: Int, standard: EngineeringStandardRevision, revision: String) = newRepositoryConformanceScan(
        RepositoryConformanceScan(
            scanId = 2,
            projectId = projectId,
            standardId = standard.standardId,
            standardRevision = standard.revision,
            standardHash = standard.hash,
            repositoryRevision = revision,
            findings = listOf(
                ConformanceFinding(
                    findingId = "FOLLOWUP_AUTHORITY",
                    practiceId = "AUTHORITY_INTEGRITY",
                    disposition = CONFORMANCE_NONCONFORMING,
                    summary = "The promoted revision still lacks recovery evidence.",
                    citations = listOf(ConformanceCitation("README.md", "b".repeat(64), "Recovery remains unverified.")),
                    affectedPaths = listOf("README.md"),
                    acceptanceCriteria = listOf("Corrupt records are rejected."),
                    verificationCommands = listOf("./gradlew test --no-daemon"),
                    confidence = 0.9,
                )
            ),
            proposedBacklog = listOf(
                BacklogProposalNode("OLD_EPIC", null, BACKLOG_EPIC, "Prior remediation", "Prior admitted work.", listOf("FOLLOWUP_AUTHORITY")),
                BacklogProposalNode("OLD_STORY", "OLD_EPIC", BACKLOG_STORY, "Prior story", "Prior admitted behavior.", listOf("FOLLOWUP_AUTHORITY")),
                BacklogProposalNode("OLD_TASK", "OLD_STORY", BACKLOG_TASK, "Prior task", "Prior admitted implementation.", listOf("FOLLOWUP_AUTHORITY")),
            ),
            modelBindingFingerprint = "c".repeat(64),
            promptHash = "d".repeat(64),
            contextHash = "e".repeat(64),
            outputHash = "f".repeat(64),
            createdAt = "2026-07-18T00:01:00Z",
            hash = "",
        )
    )

    private fun campaign(
        projectId: Int,
        standard: EngineeringStandardRevision,
        scan: RepositoryConformanceScan,
    ) = newRemediationCampaign(
        RemediationCampaign(
            campaignId = 1,
            projectId = projectId,
            standardId = standard.standardId,
            standardRevision = standard.revision,
            standardHash = standard.hash,
            seedScanId = scan.scanId,
            seedScanHash = scan.hash,
            seedAdmissionId = 1,
            seedAdmissionHash = "1".repeat(64),
            seedRepositoryRevision = scan.repositoryRevision,
            seedPractices = listOf(CampaignSeedPractice("AUTHORITY_INTEGRITY", "FOLLOWUP_AUTHORITY", CONFORMANCE_PARTIAL)),
            links = listOf(CampaignPracticeLink("AUTHORITY_INTEGRITY", "FOLLOWUP_AUTHORITY", listOf("OLD_EPIC", "OLD_STORY", "OLD_TASK"), listOf(2, 3, 4))),
            createdAt = "2026-07-18T00:02:00Z",
            hash = "",
        )
    )

    private fun evaluation(
        campaign: RemediationCampaign,
        scan: RepositoryConformanceScan,
        revision: String,
    ) = newRemediationCampaignEvaluation(
        RemediationCampaignEvaluation(
            evaluationId = 1,
            campaignId = campaign.campaignId,
            scanId = scan.scanId,
            scanHash = scan.hash,
            repositoryRevision = revision,
            promotionIds = listOf(1),
            practices = listOf(
                CampaignPracticeEvaluation("AUTHORITY_INTEGRITY", CONFORMANCE_PARTIAL, CONFORMANCE_NONCONFORMING, false, false)
            ),
            state = CAMPAIGN_BLOCKED,
            idempotencyKey = campaignIdempotencyKey(campaign.campaignId, revision),
            recordedAt = "2026-07-18T00:03:00Z",
            hash = "",
        )
    )

    private fun resolutionOutput(): String = """
        {
          "action": "ADDITIONAL_REMEDIATION",
          "rationale": "The first slice did not establish the required recovery evidence.",
          "practiceIds": ["AUTHORITY_INTEGRITY"],
          "instructions": "Add one bounded recovery test and preserve the pinned standard.",
          "proposedBacklog": [
            {"nodeId":"RECOVERY_EPIC","parentNodeId":null,"type":"EPIC","title":"Recover authority","description":"Resolve the terminal campaign.","practiceIds":["AUTHORITY_INTEGRITY"],"acceptanceCriteria":[],"verificationCommands":[]},
            {"nodeId":"RECOVERY_STORY","parentNodeId":"RECOVERY_EPIC","type":"STORY","title":"Prove recovery","description":"Establish accepted recovery evidence.","practiceIds":["AUTHORITY_INTEGRITY"],"acceptanceCriteria":[],"verificationCommands":[]},
            {"nodeId":"RECOVERY_TASK","parentNodeId":"RECOVERY_STORY","type":"TASK","title":"Add recovery evidence","description":"Implement the bounded recovery test.","practiceIds":["AUTHORITY_INTEGRITY"],"acceptanceCriteria":["Corrupt records are rejected."],"verificationCommands":["./gradlew test --no-daemon"]}
          ]
        }
    """.trimIndent()

        private fun abandonOutput(): String = """
                {
                    "action": "ABANDON",
                    "rationale": "The accepted authority owner has chosen not to continue this remediation lineage.",
                    "practiceIds": ["AUTHORITY_INTEGRITY"],
                    "instructions": "Retain the terminal campaign and admitted abandonment as historical authority.",
                    "proposedBacklog": []
                }
        """.trimIndent()

    private class FixedRepositoryBindings(
        private val projectId: Int,
        var revision: String,
    ) : RepositoryBindingStore {
        override fun bind(projectId: Int, requestedPath: String) = Unit
        override fun views(projectIds: Set<Int>) = emptyMap<Int, com.orchard.backend.workspace.RepositoryView>()
        override fun resolveHead(projectId: Int): RepositoryHead? = this.projectId.takeIf { it == projectId }?.let {
            RepositoryHead(projectId, "/tmp/orchard", revision, "main", "", true)
        }
    }

    private class FailOnceAdmissionStore : CampaignResolutionStore {
        private val delegate = TransientCampaignResolutionStore()
        private var failAdmission = true

        override fun cases() = delegate.cases()
        override fun proposals() = delegate.proposals()
        override fun admissions() = delegate.admissions()
        override fun appendCase(case: CampaignResolutionCase) = delegate.appendCase(case)
        override fun appendProposal(proposal: CampaignResolutionProposal) = delegate.appendProposal(proposal)
        override fun appendAdmission(admission: CampaignResolutionAdmission) {
            if (failAdmission) {
                failAdmission = false
                error("Injected admission failure")
            }
            delegate.appendAdmission(admission)
        }
    }

    private class FixedResolutionModel(private val output: String) : ModelProvider {
        override suspend fun triage(prompt: String): String = error("Unsupported")
        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("Unsupported")
        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:resolution",
            provider = "test",
            model = "fixed-resolution-model",
            contextWindowTokens = 96_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
        override suspend fun executeRepositoryAnalysis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int) =
            ModelGeneration(output, 100, 100)
    }
}
