package com.orchard.backend.standards

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemediationCampaignStoreTest {
    @Test
    fun `campaign and revision evaluation replay as immutable authority`() {
        val directory = createTempDirectory("orchard-remediation-campaign-")
        val store = FileRemediationCampaignStore(directory)
        val campaign = campaign()
        val evaluation = evaluation(campaign)

        store.appendCampaign(campaign)
        store.appendEvaluation(evaluation)

        val replayed = FileRemediationCampaignStore(directory)
        assertEquals(listOf(campaign), replayed.campaigns())
        assertEquals(listOf(evaluation), replayed.evaluations())
        assertEquals(CAMPAIGN_CLOSED, remediationCampaignViews(replayed).single().state)
        val lines = Files.readAllLines(directory.resolve("remediation-campaigns.jsonl"))
        assertEquals(2, lines.size)
        assertEquals(false, lines.first().contains("successorSource"))
    }

    @Test
    fun `campaign admission and repository evaluation are idempotent`() {
        val store = TransientRemediationCampaignStore()
        val campaign = campaign()
        val evaluation = evaluation(campaign)
        store.appendCampaign(campaign)
        store.appendEvaluation(evaluation)

        assertFailsWith<IllegalArgumentException> { store.appendCampaign(campaign.copy(campaignId = 2)) }
        assertFailsWith<IllegalArgumentException> { store.appendEvaluation(evaluation.copy(evaluationId = 2)) }
    }

    @Test
    fun `campaign cannot close with unresolved practices`() {
        val store = TransientRemediationCampaignStore()
        val campaign = campaign()
        store.appendCampaign(campaign)
        val invalid = newRemediationCampaignEvaluation(
            evaluation(campaign).copy(
                practices = listOf(
                    CampaignPracticeEvaluation("AUTHORITY_INTEGRITY", CONFORMANCE_PARTIAL, CONFORMANCE_PARTIAL, false, false)
                ),
                hash = "",
            )
        )

        assertFailsWith<IllegalArgumentException> { store.appendEvaluation(invalid) }
    }

    @Test
    fun `unresolved campaign can block and regression can escalate`() {
        val blockedStore = TransientRemediationCampaignStore()
        val campaign = campaign()
        blockedStore.appendCampaign(campaign)
        val blocked = newRemediationCampaignEvaluation(
            evaluation(campaign).copy(
                practices = listOf(
                    CampaignPracticeEvaluation("AUTHORITY_INTEGRITY", CONFORMANCE_PARTIAL, CONFORMANCE_NONCONFORMING, false, false)
                ),
                state = CAMPAIGN_BLOCKED,
                hash = "",
            )
        )
        blockedStore.appendEvaluation(blocked)
        assertEquals(CAMPAIGN_BLOCKED, remediationCampaignViews(blockedStore).single().state)

        val escalatedStore = TransientRemediationCampaignStore()
        escalatedStore.appendCampaign(campaign)
        val escalated = newRemediationCampaignEvaluation(
            blocked.copy(
                practices = listOf(
                    CampaignPracticeEvaluation("AUTHORITY_INTEGRITY", CONFORMANCE_CONFORMING, CONFORMANCE_CONFLICTING, false, true)
                ),
                state = CAMPAIGN_ESCALATED,
                hash = "",
            )
        )
        escalatedStore.appendEvaluation(escalated)
        assertEquals(CAMPAIGN_ESCALATED, remediationCampaignViews(escalatedStore).single().state)
    }

    private fun campaign() = newRemediationCampaign(
        RemediationCampaign(
            campaignId = 1,
            projectId = 1,
            standardId = 1,
            standardRevision = 1,
            standardHash = "a".repeat(64),
            seedScanId = 1,
            seedScanHash = "b".repeat(64),
            seedAdmissionId = 1,
            seedAdmissionHash = "c".repeat(64),
            seedRepositoryRevision = "d".repeat(40),
            seedPractices = listOf(CampaignSeedPractice("AUTHORITY_INTEGRITY", "FINDING_AUTHORITY", CONFORMANCE_PARTIAL)),
            links = listOf(
                CampaignPracticeLink("AUTHORITY_INTEGRITY", "FINDING_AUTHORITY", listOf("EPIC", "STORY", "TASK"), listOf(2, 3, 4))
            ),
            createdAt = "2026-07-18T00:00:00Z",
            hash = "",
        )
    )

    private fun evaluation(campaign: RemediationCampaign) = newRemediationCampaignEvaluation(
        RemediationCampaignEvaluation(
            evaluationId = 1,
            campaignId = campaign.campaignId,
            scanId = 2,
            scanHash = "e".repeat(64),
            repositoryRevision = "f".repeat(40),
            promotionIds = listOf(1),
            practices = listOf(
                CampaignPracticeEvaluation("AUTHORITY_INTEGRITY", CONFORMANCE_PARTIAL, CONFORMANCE_CONFORMING, true, false)
            ),
            state = CAMPAIGN_CLOSED,
            idempotencyKey = campaignIdempotencyKey(campaign.campaignId, "f".repeat(40)),
            recordedAt = "2026-07-18T00:01:00Z",
            hash = "",
        )
    )
}
