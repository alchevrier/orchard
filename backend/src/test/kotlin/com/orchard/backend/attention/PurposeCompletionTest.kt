package com.orchard.backend.attention

import com.orchard.backend.workspace.EvidenceRecord
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisRevision
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PurposeCompletionTest {
    @Test
    fun `project is done only when purpose claims have revision bound evidence and acceptance`() {
        val contract = contract()
        val revision = "a".repeat(40)
        val evidence = listOf(evidence(1, "JOURNEY", "./gradlew journeyTest", revision, passed = true))
        val links = listOf(PurposeEvidenceLink("CLAIM-JOURNEY", contract.hash, 1, "public interface after restart"))

        val unaccepted = assessProjectCompletion(contract, revision, evidence, links, emptyList(), setOf("CLAIM-JOURNEY"))
        val accepted = assessProjectCompletion(
            contract,
            revision,
            evidence,
            links,
            listOf(PurposeClaimAcceptance("CLAIM-JOURNEY", contract.hash, revision, true, "product-owner")),
            setOf("CLAIM-JOURNEY"),
        )

        assertFalse(unaccepted.done)
        assertEquals(PURPOSE_CLAIM_EVIDENCE_PASSED, unaccepted.claimStates.single().state)
        assertTrue(accepted.done)
        assertEquals(PURPOSE_CLAIM_ACCEPTED, accepted.claimStates.single().state)
    }

    @Test
    fun `implementation and wrong revision evidence do not imply project completion`() {
        val contract = contract()
        val target = "a".repeat(40)
        val assessment = assessProjectCompletion(
            contract,
            target,
            listOf(evidence(1, "JOURNEY", "./gradlew journeyTest", "b".repeat(40), passed = true)),
            listOf(PurposeEvidenceLink("CLAIM-JOURNEY", contract.hash, 1, "public interface after restart")),
            listOf(PurposeClaimAcceptance("CLAIM-JOURNEY", contract.hash, target, true, "product-owner")),
            setOf("CLAIM-JOURNEY"),
        )

        assertFalse(assessment.done)
        assertEquals(PURPOSE_CLAIM_IMPLEMENTED_UNVERIFIED, assessment.claimStates.single().state)
    }

    @Test
    fun `evidence at the wrong observation boundary cannot satisfy the purpose claim`() {
        val contract = contract()
        val revision = "a".repeat(40)
        val assessment = assessProjectCompletion(
            contract,
            revision,
            listOf(evidence(1, "JOURNEY", "./gradlew journeyTest", revision, passed = true)),
            listOf(PurposeEvidenceLink("CLAIM-JOURNEY", contract.hash, 1, "serializer unit test")),
            listOf(PurposeClaimAcceptance("CLAIM-JOURNEY", contract.hash, revision, true, "product-owner")),
        )

        assertFalse(assessment.done)
        assertEquals(PURPOSE_CLAIM_DEFINED, assessment.claimStates.single().state)
    }

    @Test
    fun `required skill cannot be skipped before purpose claim acceptance`() {
        val base = contract()
        val contract = base.copy(
            claims = base.claims.map { it.copy(requiredSkillIds = listOf("journey-observation-v1")) },
            hash = "",
        ).let { it.copy(hash = projectCompletionContractHash(it)) }
        val revision = "a".repeat(40)
        val evidence = listOf(evidence(1, "JOURNEY", "./gradlew journeyTest", revision, passed = true))
        val links = listOf(PurposeEvidenceLink("CLAIM-JOURNEY", contract.hash, 1, "public interface after restart"))
        val acceptances = listOf(PurposeClaimAcceptance("CLAIM-JOURNEY", contract.hash, revision, true, "product-owner"))

        val skipped = assessProjectCompletion(contract, revision, evidence, links, acceptances)
        val executed = assessProjectCompletion(
            contract,
            revision,
            evidence,
            links,
            acceptances,
            skillExecutions = listOf(PurposeSkillExecution(
                "journey-observation-v1",
                1,
                contract.hash,
                "CLAIM-JOURNEY",
                "e".repeat(64),
                "f".repeat(64),
                true,
            )),
        )

        assertFalse(skipped.done)
        assertEquals(listOf("journey-observation-v1"), skipped.claimStates.single().missingSkillIds)
        assertTrue(executed.done)
    }

    @Test
    fun `completion compiler rejects claims outside admitted purpose outcomes`() {
        val error = runCatching {
            compileProjectCompletionContract(
                genesis(),
                listOf(PurposeClaimDefinition("CLAIM-X", "Invented outcome", "Something works.", "TEST", "./gradlew test", "public API")),
            )
        }.exceptionOrNull()

        assertEquals("Project completion claim does not trace to an admitted outcome.", error?.message)
    }

    @Test
    fun `completion authority survives restart and reconstructs accepted purpose`() {
        val directory = createTempDirectory("orchard-purpose-completion-")
        val service = ProjectCompletionAuthorityService(FileProjectCompletionAuthorityStore(directory))
        val contract = service.recordContract(genesis(), contract().claims)
        val revision = "a".repeat(40)
        service.linkEvidence(contract.hash, "CLAIM-JOURNEY", 1, "public interface after restart")
        service.recordAcceptance(contract.hash, "CLAIM-JOURNEY", revision, true, "product-owner")

        val recovered = ProjectCompletionAuthorityService(FileProjectCompletionAuthorityStore(directory))
        val assessment = recovered.assess(
            contract.hash,
            revision,
            listOf(evidence(1, "JOURNEY", "./gradlew journeyTest", revision, passed = true)),
        )

        assertTrue(assessment.done)
        assertEquals(3, recovered.events().size)
    }

    private fun contract() = compileProjectCompletionContract(
        genesis(),
        listOf(PurposeClaimDefinition(
            "CLAIM-JOURNEY",
            "A user completes the governed journey.",
            "The governed journey completes through the public interface.",
            "JOURNEY",
            "./gradlew journeyTest",
            "public interface after restart",
        )),
    )

    private fun genesis() = ProjectGenesisRevision(
        genesisId = 4,
        projectId = 1,
        revision = 5,
        phase = GENESIS_READY,
        productIntent = "Let teams deliver governed software with justified completion.",
        experience = ExperienceContract(
            audience = "Software teams",
            productPromise = "A user completes the governed journey.",
            primaryJourney = listOf("A user completes the governed journey."),
            interactionPrinciples = listOf("Authority remains visible."),
            emotionalQualities = listOf("Trustworthy"),
            mustNotFeelLike = listOf("An opaque coding loop"),
            accessibility = listOf("Keyboard operation remains complete."),
        ),
        admitted = true,
        actor = "owner",
        hash = "c".repeat(64),
    )

    private fun evidence(
        id: Long,
        kind: String,
        command: String,
        revision: String,
        passed: Boolean,
    ) = EvidenceRecord(id, kind, revision, command, if (passed) 0 else 1, "d".repeat(64), "Observed result.", "test", passed, "2026-09-12T00:00:00Z")
}