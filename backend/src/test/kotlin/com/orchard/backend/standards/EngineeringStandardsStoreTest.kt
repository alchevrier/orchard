package com.orchard.backend.standards

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineeringStandardsStoreTest {
    @Test
    fun `standards scans and admissions replay as immutable authority`() {
        val directory = createTempDirectory("orchard-engineering-standards-")
        val store = FileEngineeringStandardsStore(directory)
        val first = standard(1)
        val second = standard(2, first.hash)
        store.appendStandard(first)
        store.appendStandard(second)
        val scan = scan(second)
        store.appendScan(scan)
        val admission = newConformanceBacklogAdmission(
            ConformanceBacklogAdmission(1, scan.scanId, scan.hash, 1, scan.repositoryRevision, listOf(2, 3, 4), "HUMAN", "2026-06-21T00:02:00Z", "")
        )
        store.appendAdmission(admission)

        val replayed = FileEngineeringStandardsStore(directory)
        assertEquals(listOf(first, second), replayed.standards())
        assertEquals(listOf(scan), replayed.scans())
        assertEquals(listOf(admission), replayed.admissions())
        assertFailsWith<IllegalArgumentException> { store.appendScan(scan.copy(scanId = 2)) }
        assertFailsWith<IllegalArgumentException> { store.appendAdmission(admission.copy(admissionId = 2)) }
        assertEquals(4, Files.readAllLines(directory.resolve("engineering-standards.jsonl")).size)
    }

    @Test
    fun `standard revisions must extend current project authority`() {
        val store = TransientEngineeringStandardsStore()
        val first = standard(1)
        store.appendStandard(first)

        assertFailsWith<IllegalArgumentException> { store.appendStandard(standard(2)) }
        assertFailsWith<IllegalArgumentException> { store.appendStandard(standard(3, first.hash)) }
    }

    @Test
    fun `backlog parents must precede their children`() {
        val store = TransientEngineeringStandardsStore()
        val standard = standard(1)
        store.appendStandard(standard)
        val invalid = scan(standard).let { candidate ->
            newRepositoryConformanceScan(
                candidate.copy(proposedBacklog = listOf(candidate.proposedBacklog[1], candidate.proposedBacklog[0], candidate.proposedBacklog[2]), hash = "")
            )
        }

        assertFailsWith<IllegalArgumentException> { store.appendScan(invalid) }
    }

    @Test
    fun `scan must judge admitted practices and cover actionable findings`() {
        val store = TransientEngineeringStandardsStore()
        val standard = standard(1)
        store.appendStandard(standard)
        val candidate = scan(standard)
        val unknownPractice = newRepositoryConformanceScan(
            candidate.copy(findings = candidate.findings.map { it.copy(practiceId = "UNKNOWN_PRACTICE") }, hash = "")
        )
        val uncoveredFinding = newRepositoryConformanceScan(candidate.copy(proposedBacklog = emptyList(), hash = ""))

        assertFailsWith<IllegalArgumentException> { store.appendScan(unknownPractice) }
        assertFailsWith<IllegalArgumentException> { store.appendScan(uncoveredFinding) }
    }

    private fun standard(revision: Int, previousHash: String? = null) = newEngineeringStandardRevision(
        standardId = 1,
        projectId = 1,
        revision = revision,
        name = "Project engineering standard",
        practices = listOf(defaultEngineeringPractices().first()),
        actor = "HUMAN",
        createdAt = "2026-06-21T00:0${revision}:00Z",
        previousHash = previousHash,
    )

    private fun scan(standard: EngineeringStandardRevision): RepositoryConformanceScan = newRepositoryConformanceScan(
        RepositoryConformanceScan(
            scanId = 1,
            projectId = 1,
            standardId = standard.standardId,
            standardRevision = standard.revision,
            standardHash = standard.hash,
            repositoryRevision = "a".repeat(40),
            findings = listOf(
                ConformanceFinding(
                    "FINDING_AUTHORITY",
                    standard.practices.single().practiceId,
                    CONFORMANCE_NONCONFORMING,
                    "Recovery behavior is not verified.",
                    listOf(ConformanceCitation("README.md", "b".repeat(64), "The document claims durable authority.")),
                    listOf("README.md"),
                    listOf("Corruption is rejected."),
                    listOf("./gradlew test --no-daemon"),
                    0.9,
                )
            ),
            proposedBacklog = listOf(
                BacklogProposalNode("EPIC", null, BACKLOG_EPIC, "Harden authority", "Make durable authority recoverable.", listOf("FINDING_AUTHORITY")),
                BacklogProposalNode("STORY", "EPIC", BACKLOG_STORY, "Verify recovery", "Prove corruption handling.", listOf("FINDING_AUTHORITY")),
                BacklogProposalNode("TASK", "STORY", BACKLOG_TASK, "Add recovery test", "Exercise corrupt persisted state.", listOf("FINDING_AUTHORITY")),
            ),
            modelBindingFingerprint = "c".repeat(64),
            promptHash = "d".repeat(64),
            contextHash = "e".repeat(64),
            outputHash = "f".repeat(64),
            createdAt = "2026-06-21T00:02:00Z",
            hash = "",
        )
    )
}
