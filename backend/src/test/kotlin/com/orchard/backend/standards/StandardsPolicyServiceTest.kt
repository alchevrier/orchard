package com.orchard.backend.standards

import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StandardsPolicyServiceTest {
    @Test
    fun `exception admission is evidence bound scope isolated expiring and revocable`() {
        val fixture = fixture()
        val moduleScope = StandardPolicyScope(STANDARD_SCOPE_MODULE, fixture.projectId, "backend")
        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId,
            exceptionSubmission(moduleScope, fixture.readmeHash),
        ).proposal)
        val admission = assertNotNull(fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-08-01T00:00:00Z"),
        ).admission)
        val effective = assertNotNull(fixture.policy.effectiveStandard(fixture.projectId, moduleScope))

        assertEquals(1, fixture.policy.activeExceptions(effective, fixture.repository.toString(), git(fixture.repository, "rev-parse", "HEAD")).size)
        assertTrue(fixture.policy.activeExceptions(
            effective,
            fixture.repository.toString(),
            git(fixture.repository, "rev-parse", "HEAD"),
            Instant.parse("2026-08-02T00:00:00Z"),
        ).isEmpty())
        val projectEffective = assertNotNull(fixture.policy.effectiveStandard(
            fixture.projectId, StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId),
        ))
        assertTrue(fixture.policy.activeExceptions(
            projectEffective, fixture.repository.toString(), git(fixture.repository, "rev-parse", "HEAD"),
        ).isEmpty())

        assertEquals(StandardsPolicyMutationStatus.RECORDED, fixture.policy.revokeException(
            admission.admissionId, StandardsExceptionRevocationSubmission("TECH_LEAD", "The migration completed."),
        ).status)
        assertTrue(fixture.policy.activeExceptions(effective, fixture.repository.toString(), git(fixture.repository, "rev-parse", "HEAD")).isEmpty())
    }

    @Test
    fun `policy drift rejects stale exception admission`() {
        val fixture = fixture()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)
        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId, exceptionSubmission(scope, fixture.readmeHash),
        ).proposal)
        assertEquals(StandardsPolicyMutationStatus.RECORDED, fixture.policy.appendOverlay(
            fixture.projectId,
            StandardOverlaySubmission(
                scope,
                "Require restart evidence",
                listOf(StandardOverlayAdjustment(
                    OVERLAY_TIGHTEN, "AUTHORITY_INTEGRITY", additionalEvidence = listOf("restart test"),
                )),
            ),
        ).status)

        val result = fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-08-01T00:00:00Z"),
        )

        assertEquals(StandardsPolicyMutationStatus.STALE_POLICY, result.status)
    }

    @Test
    fun `control evidence drift invalidates projected exception authority`() {
        val fixture = fixture()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)
        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId, exceptionSubmission(scope, fixture.readmeHash),
        ).proposal)
        fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-08-01T00:00:00Z"),
        )

        Files.writeString(fixture.repository.resolve("README.md"), "# Authority\n\nThe evidence changed.\n")

        assertEquals(EXCEPTION_INVALIDATED, fixture.policy.view(fixture.projectId).exceptions.single().state)
    }

    @Test
    fun `expired proposal and admission bounds fail as invalid requests`() {
        val fixture = fixture()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)

        val expiredProposal = fixture.policy.proposeException(
            fixture.projectId,
            exceptionSubmission(scope, fixture.readmeHash).copy(requestedUntil = "2026-07-19T12:00:00Z"),
        )
        assertEquals(StandardsPolicyMutationStatus.INVALID_REQUEST, expiredProposal.status)

        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId, exceptionSubmission(scope, fixture.readmeHash),
        ).proposal)
        val expiredAdmission = fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-07-20T00:00:00Z"),
        )
        assertEquals(StandardsPolicyMutationStatus.INVALID_REQUEST, expiredAdmission.status)
    }

    @Test
    fun `active exception permits independently conforming disposition`() = runTest {
        val fixture = fixture()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)
        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId, exceptionSubmission(scope, fixture.readmeHash),
        ).proposal)
        fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-08-01T00:00:00Z"),
        )
        val output = exceptionOutput(fixture.readmeHash)
            .replace("EXCEPTION_ACTIVE", "CONFORMING")
            .replace("An admitted scoped exception covers the observed deviation.", "The repository independently satisfies the practice.")
        val standards = EngineeringStandardsService(
            fixture.workspace,
            fixture.bindings,
            listOf(FixedExceptionModel(output)),
            fixture.standardsStore,
            LocalCodingWorkspaceGateway(),
            MachineResourceController.unrestricted(),
            fixture.policy,
        )

        val result = standards.scan(fixture.projectId)

        assertEquals(ConformanceScanStatus.CREATED, result.status)
        assertEquals(CONFORMANCE_CONFORMING, result.scan?.findings?.single()?.disposition)
        assertEquals(1, result.scan?.appliedExceptions?.size)
    }

    @Test
    fun `conformance accepts exception disposition only while deterministic authority is active`() = runTest {
        val fixture = fixture()
        val scope = StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)
        val proposal = assertNotNull(fixture.policy.proposeException(
            fixture.projectId, exceptionSubmission(scope, fixture.readmeHash),
        ).proposal)
        val admission = assertNotNull(fixture.policy.admitException(
            proposal.proposalId,
            StandardsExceptionAdmissionSubmission("TECH_LEAD", "2026-07-19T00:00:00Z", "2026-08-01T00:00:00Z"),
        ).admission)
        val standards = EngineeringStandardsService(
            fixture.workspace,
            fixture.bindings,
            listOf(FixedExceptionModel(exceptionOutput(fixture.readmeHash))),
            fixture.standardsStore,
            LocalCodingWorkspaceGateway(),
            MachineResourceController.unrestricted(),
            fixture.policy,
        )

        val activeScan = standards.scan(fixture.projectId)

        assertEquals(ConformanceScanStatus.CREATED, activeScan.status)
        assertEquals(CONFORMANCE_EXCEPTION_ACTIVE, activeScan.scan?.findings?.single()?.disposition)
        assertEquals(admission.admissionId, activeScan.scan?.appliedExceptions?.single()?.admissionId)

        assertEquals(StandardsPolicyMutationStatus.RECORDED, fixture.policy.revokeException(
            admission.admissionId, StandardsExceptionRevocationSubmission("TECH_LEAD", "Control no longer applies."),
        ).status)

        val rejectedScan = standards.scan(fixture.projectId)

        assertEquals(ConformanceScanStatus.INVALID_OUTPUT, rejectedScan.status)
        assertTrue(rejectedScan.diagnostic.contains("EXCEPTION_ACTIVE"))
    }

    @Test
    fun `admitted resolution seeds one candidate exception without granting it`() {
        val fixture = fixture()
        val standard = fixture.standardsStore.standards().single()
        val effective = assertNotNull(fixture.policy.effectiveStandard(fixture.projectId, StandardPolicyScope(STANDARD_SCOPE_PROJECT, fixture.projectId)))
        val revision = git(fixture.repository, "rev-parse", "HEAD")
        val scan = newRepositoryConformanceScan(
            RepositoryConformanceScan(
                1, fixture.projectId, standard.standardId, standard.revision, standard.hash, revision,
                listOf(ConformanceFinding(
                    "FINDING_AUTHORITY", "AUTHORITY_INTEGRITY", CONFORMANCE_PARTIAL, "A bounded deviation remains.",
                    listOf(ConformanceCitation("README.md", fixture.readmeHash, "Current authority evidence.")),
                    listOf("README.md"), listOf("Restore the full control."), listOf("./gradlew test --no-daemon"), 0.9,
                )),
                emptyList(), "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64),
                "2026-07-20T00:00:00Z", effective, emptyList(), "",
            )
        )
        val case = newCampaignResolutionCase(CampaignResolutionCase(
            1, 1, fixture.projectId, 1, "e".repeat(64), revision, RESOLUTION_CAUSE_REMEDIATION_EXHAUSTED,
            listOf("AUTHORITY_INTEGRITY"), "2026-07-20T00:00:00Z", "",
        ))
        val resolutionProposal = newCampaignResolutionProposal(CampaignResolutionProposal(
            1, case.caseId, case.evaluationHash, RESOLUTION_ACTION_EXCEPTION_REQUEST,
            "Delivery cannot safely remove the deviation yet.", listOf("AUTHORITY_INTEGRITY"),
            "Require manual review of every authority append.", actor = "ARCHITECT",
            proposedAt = "2026-07-20T00:00:00Z", hash = "",
        ))
        val resolutionAdmission = newCampaignResolutionAdmission(CampaignResolutionAdmission(
            1, case.caseId, resolutionProposal.proposalId, resolutionProposal.hash, "HUMAN", "2026-07-20T00:01:00Z", hash = "",
        ))

        val first = fixture.policy.seedExceptionRequest(case, resolutionProposal, resolutionAdmission, scan)
        val second = fixture.policy.seedExceptionRequest(case, resolutionProposal, resolutionAdmission, scan)

        assertEquals(StandardsPolicyMutationStatus.RECORDED, first.status)
        assertEquals(StandardsPolicyMutationStatus.ALREADY_DECIDED, second.status)
        val view = fixture.policy.view(fixture.projectId).exceptions.single()
        assertEquals(case.caseId, view.proposal.sourceResolutionCaseId)
        assertEquals(resolutionAdmission.admissionId, view.proposal.sourceResolutionAdmissionId)
        assertEquals(EXCEPTION_PENDING, view.state)
        assertEquals(null, view.admission)
    }

    private fun fixture(): Fixture {
        val state = createTempDirectory("orchard-policy-state-")
        val repository = createTempDirectory("orchard-policy-repository-")
        git(repository, "init")
        git(repository, "config", "user.name", "Orchard Test")
        git(repository, "config", "user.email", "orchard-test@localhost")
        Files.writeString(repository.resolve("README.md"), "# Authority\n\nAppend-only and checksummed.\n")
        Files.writeString(repository.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") version \"2.1.21\" }\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "Initial")
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = "Policy Test")))
        val projectId = workspace.lastCreatedId
        workspace.commitBatch()
        val bindings = FileRepositoryBindingStore(state)
        bindings.bind(projectId, repository.toString())
        val standardsStore = TransientEngineeringStandardsStore()
        standardsStore.appendStandard(newEngineeringStandardRevision(
            1, projectId, 1, "Base", listOf(defaultEngineeringPractices().first()), "HUMAN", "2026-07-18T00:00:00Z",
        ))
        val policyStore = TransientStandardsPolicyStore()
        val policy = StandardsPolicyService(
            standardsStore, bindings, policyStore,
            clock = { Instant.parse("2026-07-20T00:00:00Z") },
        )
        return Fixture(workspace, projectId, repository, bindings, standardsStore, policy, sha256(Files.readAllBytes(repository.resolve("README.md"))))
    }

    private fun exceptionSubmission(scope: StandardPolicyScope, readmeHash: String) = StandardsExceptionProposalSubmission(
        scope = scope,
        practiceIds = listOf("AUTHORITY_INTEGRITY"),
        rationale = "A migration requires a bounded deviation.",
        compensatingControls = listOf("Review append-only authority manually."),
        controlEvidence = listOf(ExceptionControlEvidence("README.md", readmeHash, "The current authority declaration.")),
        requestedFrom = "2026-07-19T00:00:00Z",
        requestedUntil = "2026-08-01T00:00:00Z",
    )

    private fun exceptionOutput(readmeHash: String): String = """
        {
          "findings": [{
            "findingId": "FINDING_AUTHORITY",
            "practiceId": "AUTHORITY_INTEGRITY",
            "disposition": "EXCEPTION_ACTIVE",
            "summary": "An admitted scoped exception covers the observed deviation.",
            "citations": [{"path":"README.md","contentHash":"$readmeHash","observation":"The authority declaration is supplied evidence."}],
            "affectedPaths": ["README.md"],
            "acceptanceCriteria": [],
            "verificationCommands": [],
            "confidence": 1.0
          }],
          "proposedBacklog": []
        }
    """.trimIndent()

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class Fixture(
        val workspace: WorkspaceStore,
        val projectId: Int,
        val repository: Path,
        val bindings: FileRepositoryBindingStore,
        val standardsStore: TransientEngineeringStandardsStore,
        val policy: StandardsPolicyService,
        val readmeHash: String,
    )

    private class FixedExceptionModel(private val output: String) : ModelProvider {
        override suspend fun triage(prompt: String): String = error("Unsupported")
        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("Unsupported")
        override fun bindingProfile() = ModelBindingProfile(
            "test:exception", "test", "fixed-exception-model", 96_000, setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
        override suspend fun executeRepositoryAnalysis(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int) =
            ModelGeneration(output, 100, 100)
    }
}