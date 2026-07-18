package com.orchard.backend.standards

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandardsPolicyAuthorityTest {
    @Test
    fun `scoped overlays compose by precedence and mandatory floors cannot be disabled`() {
        val base = baseStandard()
        val organization = overlay(
            id = 1,
            scope = StandardPolicyScope(STANDARD_SCOPE_ORGANIZATION),
            adjustment = StandardOverlayAdjustment(
                operation = OVERLAY_TIGHTEN,
                practiceId = "AUTHORITY_INTEGRITY",
                additionalRequirement = "Authority must survive process interruption.",
                additionalEvidence = listOf("interruption recovery test"),
                mandatoryFloor = true,
            ),
        )
        val project = overlay(
            id = 2,
            scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, projectId = 1),
            adjustment = StandardOverlayAdjustment(OVERLAY_DISABLE, "AUTHORITY_INTEGRITY"),
        )
        val module = overlay(
            id = 3,
            scope = StandardPolicyScope(STANDARD_SCOPE_MODULE, projectId = 1, modulePath = "backend"),
            adjustment = StandardOverlayAdjustment(
                operation = OVERLAY_ADD,
                practiceId = "MODULE_TESTS",
                addedPractice = EngineeringPractice(
                    "MODULE_TESTS", "Module tests", "TESTING", PRACTICE_SEVERITY_REQUIRED,
                    "backend", "Backend changes require focused tests.", listOf("focused test"), "Add a focused test.",
                ),
            ),
        )

        val effective = composeEffectiveStandard(
            base,
            StandardPolicyScope(STANDARD_SCOPE_MODULE, projectId = 1, modulePath = "backend/standards"),
            listOf(project, module, organization),
        )

        val authority = effective.practices.single { it.practice.practiceId == "AUTHORITY_INTEGRITY" }
        assertTrue(authority.practice.enabled)
        assertTrue(authority.mandatoryFloor)
        assertTrue("interruption recovery test" in authority.practice.requiredEvidence)
        assertEquals(listOf(1L), authority.sourceOverlayIds)
        assertEquals(1, effective.conflicts.size)
        assertEquals("MODULE_TESTS", effective.practices.last().practice.practiceId)
    }

    @Test
    fun `policy ledger replays overlays exception admission and revocation`() {
        val directory = createTempDirectory("orchard-standards-policy-")
        val store = FileStandardsPolicyStore(directory)
        val overlay = overlay(
            id = 1,
            scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, projectId = 1),
            adjustment = StandardOverlayAdjustment(
                OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("restart test"),
            ),
        )
        val proposal = newStandardsExceptionProposal(
            StandardsExceptionProposal(
                1, 1, StandardPolicyScope(STANDARD_SCOPE_MODULE, 1, "backend"), "a".repeat(64), "b".repeat(40),
                listOf("AUTHORITY_INTEGRITY"), "Migration requires a bounded deviation.", listOf("Run recovery suite."),
                listOf(ExceptionControlEvidence("backend/build.gradle.kts", "c".repeat(64), "Build verification authority.")),
                "2026-07-18T00:00:00Z", "2026-08-18T00:00:00Z", actor = "HUMAN",
                proposedAt = "2026-07-18T00:00:00Z", hash = "",
            )
        )
        val admission = newStandardsExceptionAdmission(
            StandardsExceptionAdmission(
                1, proposal.proposalId, proposal.hash, "TECH_LEAD", proposal.requestedFrom, proposal.requestedUntil,
                "2026-07-18T00:01:00Z", "",
            )
        )
        val revocation = newStandardsExceptionRevocation(
            StandardsExceptionRevocation(1, admission.admissionId, admission.hash, "TECH_LEAD", "Migration ended.", "2026-07-20T00:00:00Z", "")
        )

        store.appendOverlay(overlay)
        store.appendProposal(proposal)
        store.appendAdmission(admission)
        store.appendRevocation(revocation)

        val replayed = FileStandardsPolicyStore(directory)
        assertEquals(listOf(overlay), replayed.overlays())
        assertEquals(listOf(proposal), replayed.proposals())
        assertEquals(listOf(admission), replayed.admissions())
        assertEquals(listOf(revocation), replayed.revocations())
        assertFailsWith<IllegalArgumentException> { store.appendAdmission(admission.copy(admissionId = 2)) }
        assertFailsWith<IllegalArgumentException> { store.appendRevocation(revocation.copy(revocationId = 2)) }
    }

    @Test
    fun `same scope overlay revisions retain prior adjustments`() {
        val fixture = TransientStandardsPolicyStore()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, projectId = 1)
        val first = overlay(
            id = 1,
            scope = scope,
            adjustment = StandardOverlayAdjustment(
                OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("restart test"),
            ),
        )
        fixture.appendOverlay(first)
        val second = newStandardOverlayRevision(
            first.copy(
                revision = 2,
                adjustments = first.adjustments + StandardOverlayAdjustment(
                    OVERLAY_ADD,
                    "MODULE_TESTS",
                    addedPractice = EngineeringPractice(
                        "MODULE_TESTS", "Module tests", "TESTING", PRACTICE_SEVERITY_REQUIRED,
                        "all modules", "Modules require tests.", listOf("focused test"), "Add tests.",
                    ),
                ),
                previousHash = first.hash,
                hash = "",
            )
        )
        fixture.appendOverlay(second)

        val effective = composeEffectiveStandard(baseStandard(), scope, fixture.overlays())

        assertTrue("restart test" in effective.practices.single { it.practice.practiceId == "AUTHORITY_INTEGRITY" }.practice.requiredEvidence)
        assertTrue(effective.practices.any { it.practice.practiceId == "MODULE_TESTS" })
    }

    @Test
    fun `work item policy composes nested module ancestry without sibling leakage`() {
        val parentModule = overlay(
            id = 1,
            scope = StandardPolicyScope(STANDARD_SCOPE_MODULE, 1, "backend"),
            adjustment = StandardOverlayAdjustment(
                OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("backend recovery test"),
            ),
        )
        val nestedModule = overlay(
            id = 2,
            scope = StandardPolicyScope(STANDARD_SCOPE_MODULE, 1, "backend/standards"),
            adjustment = StandardOverlayAdjustment(
                OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("standards replay test"),
            ),
        )
        val workItem = overlay(
            id = 3,
            scope = StandardPolicyScope(STANDARD_SCOPE_WORK_ITEM, 1, "backend/standards", 42),
            adjustment = StandardOverlayAdjustment(
                OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("work-item acceptance"),
            ),
        )

        val effective = composeEffectiveStandard(
            baseStandard(),
            StandardPolicyScope(STANDARD_SCOPE_WORK_ITEM, 1, "backend/standards", 42),
            listOf(workItem, nestedModule, parentModule),
        )
        val sibling = composeEffectiveStandard(
            baseStandard(),
            StandardPolicyScope(STANDARD_SCOPE_WORK_ITEM, 1, "frontend", 43),
            listOf(workItem, nestedModule, parentModule),
        )

        val authority = effective.practices.single { it.practice.practiceId == "AUTHORITY_INTEGRITY" }
        assertEquals(listOf(1L, 2L, 3L), authority.sourceOverlayIds)
        assertTrue("backend recovery test" in authority.practice.requiredEvidence)
        assertTrue("standards replay test" in authority.practice.requiredEvidence)
        assertTrue("work-item acceptance" in authority.practice.requiredEvidence)
        assertTrue(sibling.practices.single().sourceOverlayIds.isEmpty())
    }

    private fun baseStandard() = newEngineeringStandardRevision(
        1, 1, 1, "Base", listOf(defaultEngineeringPractices().first()), "HUMAN", "2026-07-18T00:00:00Z",
    )

    private fun overlay(
        id: Long,
        scope: StandardPolicyScope,
        adjustment: StandardOverlayAdjustment,
    ) = newStandardOverlayRevision(
        StandardOverlayRevision(id, 1, scope, "Overlay $id", listOf(adjustment), "HUMAN", "2026-07-18T00:00:00Z", hash = "")
    )
}