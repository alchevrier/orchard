package com.orchard.backend.company

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompanyControlStoreTest {
    @Test
    fun `company ledger restores linked authority and rejects stale acceptance`() {
        val directory = createTempDirectory("orchard-company-control-")
        val store = FileCompanyControlStore(directory)
        val genesisHash = "a".repeat(64)
        val ruleDraft = ArchitectureRule(
            ruleId = "RULE_ONE",
            projectId = 1,
            genesisRevision = 5,
            genesisHash = genesisHash,
            kind = "ARCHITECTURE_DECISION",
            statement = "Keep the boundary explicit.",
            severity = RISK_HIGH,
            requiresIndependentAudit = true,
            hash = "",
        )
        val rule = ruleDraft.copy(hash = companyRecordHash(ruleDraft.toString()))
        val ruleSetDraft = ArchitectureRuleSet(
            ruleSetId = 1,
            projectId = 1,
            revision = 1,
            genesisRevision = 5,
            genesisHash = genesisHash,
            rules = listOf(rule),
            compiledAt = "2026-06-22T00:00:00Z",
            hash = "",
        )
        val ruleSet = ruleSetDraft.copy(hash = companyRecordHash(ruleSetDraft.toString()))
        store.append(CompanyControlEvent(1, ruleSet = ruleSet))
        val implementer = assignment(2, ROLE_IMPLEMENTER)
        store.append(CompanyControlEvent(2, assignment = implementer))
        val architecture = assignment(3, ROLE_ARCHITECTURE_AUDITOR, independentFrom = 2)
        store.append(CompanyControlEvent(3, assignment = architecture))
        val quality = assignment(4, ROLE_QUALITY_AUDITOR, independentFrom = 2)
        store.append(CompanyControlEvent(4, assignment = quality))
        val revision = "b".repeat(40)
        val diffHash = "c".repeat(64)
        val architectureAudit = audit(5, architecture, revision, diffHash, genesisHash, ruleSet.hash)
        store.append(CompanyControlEvent(5, audit = architectureAudit))
        val qualityAudit = audit(6, quality, revision, diffHash, genesisHash, ruleSet.hash)
        store.append(CompanyControlEvent(6, audit = qualityAudit))
        val acceptanceDraft = CompanyAcceptance(
            acceptanceId = 7,
            projectId = 1,
            runId = 11,
            candidateRevision = revision,
            candidateDiffHash = diffHash,
            genesisHash = genesisHash,
            auditIds = listOf(5, 6),
            acceptedBy = "architect",
            acceptedAt = "2026-06-22T00:05:00Z",
            hash = "",
        )
        val acceptance = acceptanceDraft.copy(hash = companyRecordHash(acceptanceDraft.toString()))
        store.append(CompanyControlEvent(7, acceptance = acceptance))

        val restored = companyControlView(FileCompanyControlStore(directory).loadEvents())
        assertEquals(1, restored.ruleSets.size)
        assertEquals(3, restored.assignments.size)
        assertEquals(2, restored.audits.size)
        assertEquals(acceptance, restored.acceptances.single())

        val staleDraft = acceptanceDraft.copy(acceptanceId = 8, candidateRevision = "d".repeat(40), hash = "")
        val stale = staleDraft.copy(hash = companyRecordHash(staleDraft.toString()))
        assertFailsWith<IllegalArgumentException> {
            store.append(CompanyControlEvent(8, acceptance = stale))
        }
    }

    @Test
    fun `company ledger quarantines corrupt tail and preserves valid authority`() {
        val directory = createTempDirectory("orchard-company-tail-")
        val store = FileCompanyControlStore(directory)
        val assignment = assignment(1, ROLE_IMPLEMENTER)
        store.append(CompanyControlEvent(1, assignment = assignment))
        Files.writeString(directory.resolve("company-control.jsonl"), "{broken", java.nio.file.StandardOpenOption.APPEND)

        assertEquals(listOf(CompanyControlEvent(1, assignment = assignment)), store.loadEvents())
        assertEquals(1, Files.list(directory).use { paths -> paths.filter { it.fileName.toString().contains("corrupt") }.count() })
    }

    private fun assignment(id: Long, role: String, independentFrom: Long? = null): StaffAssignment {
        val draft = StaffAssignment(
            assignmentId = id,
            projectId = 1,
            runId = 11,
            role = role,
            risk = RISK_HIGH,
            bindingFingerprint = "f".repeat(64),
            model = "local-model",
            rationale = "Selected from local evidence.",
            evidenceSampleCount = 3,
            confidence = 0.25,
            independentFromAssignmentId = independentFrom,
            assignedAt = "2026-06-22T00:0${id}:00Z",
            hash = "",
        )
        return draft.copy(hash = companyRecordHash(draft.toString()))
    }

    private fun audit(
        id: Long,
        assignment: StaffAssignment,
        revision: String,
        diffHash: String,
        genesisHash: String,
        ruleSetHash: String,
    ): AuditJudgment {
        val draft = AuditJudgment(
            auditId = id,
            projectId = 1,
            runId = 11,
            assignmentId = assignment.assignmentId,
            role = assignment.role,
            candidateRevision = revision,
            candidateDiffHash = diffHash,
            genesisHash = genesisHash,
            ruleSetHash = ruleSetHash,
            findings = listOf(AuditFinding("RULE_ONE", AUDIT_CONFORMING, "Conforms.", listOf(21))),
            status = AUDIT_CONFORMING,
            rationale = "Objective evidence supports conformance.",
            recordedAt = "2026-06-22T00:0${id}:00Z",
            hash = "",
        )
        return draft.copy(hash = companyRecordHash(draft.toString()))
    }
}
