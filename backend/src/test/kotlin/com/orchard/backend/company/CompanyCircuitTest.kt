package com.orchard.backend.company

import com.orchard.backend.analysis.DISPOSITION_PARTIALLY_IMPLEMENTED
import com.orchard.backend.analysis.DISPOSITION_SCAFFOLD_ONLY
import com.orchard.backend.analysis.ExecutionPlanOperation
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryAnalysisTickStatus
import com.orchard.backend.analysis.compactRepositoryContextToBudget
import com.orchard.backend.analysis.TransientRepositoryExecutionPlanStore
import com.orchard.backend.analysis.RepositoryEvidenceCitation
import com.orchard.backend.agent.CODING_FILE_WRITE
import com.orchard.backend.agent.CodingFileOperation
import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingPatchProposal
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.CodingWorkerTickStatus
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.agent.TransientCodingWorkerStore
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.ArchitectureComponent
import com.orchard.backend.workspace.ArchitectureDecision
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.FileCircuitDispatchStore
import com.orchard.backend.workspace.FileDefinitionCollaborationStore
import com.orchard.backend.workspace.FileDesignGovernanceStore
import com.orchard.backend.workspace.FileModelExperienceStore
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileStagedDeliveryPlanStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CompanyCircuitTest {
    @Test
    fun `repository analysis retains the largest ranked evidence prefix within budget`() {
        val context = CodingRepositoryContext(
            files = (1..6).map { index -> CodingContextFile("src/$index.kt", "x".repeat(400)) },
            omittedFileCount = 3,
        )
        val twoFileBudget = estimateModelTokens(Json.encodeToString(context.copy(
            files = context.files.take(2),
            omittedFileCount = 7,
        )))

        val compacted = requireNotNull(compactRepositoryContextToBudget(context, twoFileBudget) { candidate ->
            Json.encodeToString(candidate)
        })

        assertEquals(listOf("src/1.kt", "src/2.kt"), compacted.files.map { it.path })
        assertEquals(7, compacted.omittedFileCount)
    }

    @Test
    fun `company repairs failed work and audit violations before local promotion`() = runTest {
        val state = createTempDirectory("orchard-company-acceptance-state-")
        val projects = createTempDirectory("orchard-company-acceptance-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val staff = ScenarioStaffModel()
        val company = CompanyControlService(workspace, listOf(staff), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(staff),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
        )
        val worker = CodingWorkerService(
            workspace = workspace,
            modelProviders = listOf(staff),
            workerStore = TransientCodingWorkerStore(),
            workspaceGateway = LocalCodingWorkspaceGateway(),
            companyControl = company,
            repositoryAnalysis = analysis,
            retryBudget = 5,
        )

        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        assertEquals(DISPOSITION_SCAFFOLD_ONLY, analysis.plans().single().content.disposition)
        val reservedPath = Path.of(workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.repository.path)
        val initialRevision = LocalCodingWorkspaceGateway().currentRevision(reservedPath.toString())
        val initialReadme = Files.readString(reservedPath.resolve("README.md"))
        assertEquals(CodingWorkerTickStatus.PLAN_BLOCKED, worker.tick().status)
        assertEquals(1, staff.codingCallCount)
        assertEquals(initialRevision, LocalCodingWorkspaceGateway().currentRevision(reservedPath.toString()))
        assertEquals(initialReadme, Files.readString(reservedPath.resolve("README.md")))
        assertEquals(CodingWorkerTickStatus.VERIFICATION_FAILED, worker.tick().status)
        assertEquals(2, staff.codingCallCount)
        assertEquals(CodingWorkerTickStatus.PLAN_STALE, worker.tick().status)
        assertEquals(2, staff.codingCallCount)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        val secondAttempt = worker.tick()
        assertEquals(
            CodingWorkerTickStatus.CANDIDATE_COMPLETED,
            secondAttempt.status,
            secondAttempt.execution?.result?.diagnostic,
        )
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId
        assertTrue(company.projectView(1).escalations.any { it.runId == runId && it.requiredRole == ROLE_IMPLEMENTER })

        val audit = CompanyAuditService(workspace, worker, company, LocalCodingWorkspaceGateway())
        val violation = audit.tick()
        assertEquals(CompanyAuditTickStatus.VIOLATION, violation.status, violation.diagnostic)
        assertEquals("EVIDENCE_BLOCKED", workspace.snapshot(MESSAGE_READY).workflowRuns.single().state)
        assertEquals(CodingWorkerTickStatus.PLAN_STALE, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        assertEquals(CodingWorkerTickStatus.CANDIDATE_COMPLETED, worker.tick().status)
        assertTrue(analysis.plans().drop(1).all { it.content.disposition == DISPOSITION_PARTIALLY_IMPLEMENTED })
        assertTrue(analysis.plans().all { "build.gradle.kts" in it.content.reuse })
        assertTrue(worker.executions().all { it.claim.executionPlanId != null && it.claim.executionPlanHash != null })

        val repaired = requireNotNull(worker.executions().last().result)
        val repairedRevision = requireNotNull(repaired.revision)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val repairedDiff = requireNotNull(run.evidence.last { it.kind == "SOURCE_DIFF" && it.revision == repairedRevision })
        val staleEvidenceIds = run.evidence.filter { it.revision != repairedRevision }.map { it.evidenceId }
        assertTrue(staleEvidenceIds.isNotEmpty())
        assertEquals(CompanyMutationStatus.RECORDED, company.assign(runId, ROLE_QUALITY_AUDITOR, RISK_HIGH).status)
        assertEquals(
            CompanyMutationStatus.EVIDENCE_STALE,
            company.recordAudit(
                runId,
                ROLE_QUALITY_AUDITOR,
                repairedRevision,
                repairedDiff.outputHash,
                requireNotNull(company.projectView(1).ruleSet).rules.map {
                    AuditFinding(it.ruleId, AUDIT_CONFORMING, "Stale evidence cannot prove this repair.", staleEvidenceIds)
                },
                "Attempted to reuse evidence from the superseded candidate.",
            ).status,
        )

        assertEquals(CompanyAuditTickStatus.ACCEPTED, audit.tick().status)
        assertEquals(CompanyAuditTickStatus.ACCEPTED, audit.tick().status)
        assertEquals(CompanyAuditTickStatus.ACCEPTED, audit.tick().status)
        val accepted = company.projectView(1).acceptances.single()
        assertEquals(repairedRevision, accepted.candidateRevision)

        assertEquals(CompanyMutationStatus.RECORDED, company.escalate(runId, ROLE_QUALITY_AUDITOR, "Rotate the auditor after acceptance.").status)
        assertEquals(CompanyMutationStatus.RECORDED, company.assign(runId, ROLE_QUALITY_AUDITOR, RISK_HIGH).status)
        assertTrue(requireNotNull(company.assignment(runId, ROLE_QUALITY_AUDITOR)).evidenceSampleCount >= 1)

        assertEquals(CompanyMutationStatus.RECORDED, company.promote(runId).status)
        val promoted = company.projectView(1).promotions.single()
        assertEquals(repairedRevision, promoted.destinationRevision)
        assertEquals(repairedRevision, bindings.resolveHead(1)?.commitHash)
    }

    @Test
    fun `company circuit materializes dispatch authority and resumes after restart`() {
        val state = createTempDirectory("orchard-company-circuit-state-")
        val projects = createTempDirectory("orchard-company-circuit-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val company = CompanyControlService(workspace, listOf(StaffModel), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)

        val started = circuit.start(1)
        val snapshot = workspace.snapshot(MESSAGE_READY)

        assertEquals(CompanyCircuitStatus.STARTED, started.status, started.diagnostic)
        assertTrue(snapshot.repositories.getValue(1).available)
        assertTrue(Files.isDirectory(Path.of(snapshot.repositories.getValue(1).path).resolve(".git")))
        assertEquals(4, workspace.entityCount)
        assertEquals(3, snapshot.designRevisions.size)
        assertTrue(snapshot.designRevisions.all { it.status == "ADMITTED" })
        assertEquals(2, snapshot.stagedPlans.size)
        assertEquals(1, snapshot.circuitDispatches.size)
        assertEquals(1, snapshot.workflowRuns.size)
        assertNotNull(company.projectView(1).ruleSet)

        val recoveredBindings = FileRepositoryBindingStore(state)
        val recoveredWorkspace = workspace(state, recoveredBindings)
        val recoveredCompany = CompanyControlService(
            recoveredWorkspace,
            listOf(StaffModel),
            FileCompanyControlStore(state),
            recoveredBindings,
        )
        val recoveredCircuit = CompanyCircuitService(recoveredWorkspace, recoveredCompany, projects)

        val resumed = recoveredCircuit.start(1)
        val recovered = recoveredWorkspace.snapshot(MESSAGE_READY)

        assertEquals(CompanyCircuitStatus.ALREADY_STARTED, resumed.status, resumed.diagnostic)
        assertEquals(4, recoveredWorkspace.entityCount)
        assertEquals(3, recovered.designRevisions.size)
        assertEquals(2, recovered.stagedPlans.size)
        assertEquals(1, recovered.circuitDispatches.size)
        assertEquals(1, recovered.workflowRuns.size)
        assertEquals(1, recoveredCompany.projectView(1).ruleSet?.revision)
    }

    private fun workspace(state: Path, bindings: FileRepositoryBindingStore) = WorkspaceStore(
        repository = FileWorkspaceRepository(state),
        repositoryBindings = bindings,
        workflowMemory = FileWorkflowMemoryStore(state),
        definitionStore = FileWorkDefinitionStore(state),
        collaborationStore = FileDefinitionCollaborationStore(state),
        modelExperienceStore = FileModelExperienceStore(state),
        stagedPlanStore = FileStagedDeliveryPlanStore(state),
        circuitDispatchStore = FileCircuitDispatchStore(state),
        designGovernanceStore = FileDesignGovernanceStore(state),
        projectGenesisStore = FileProjectGenesisStore(state),
        enforceProjectGenesis = true,
    )

    private fun createProjectAndEpic(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Local product")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "First experience", projectId = 1)))
        workspace.commitBatch()
    }

    private fun admitGenesis(workspace: WorkspaceStore) {
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    classification = PROJECT_GREENFIELD_LOCAL,
                    productIntent = "A local product whose primary journey is continuously governed.",
                    baseRevision = 0,
                ),
            ).status,
        )
        var genesis = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision)
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    experience = ExperienceContract(
                        audience = "Local architects",
                        productPromise = "The architect can observe one complete governed journey.",
                        primaryJourney = listOf("Start the company", "Observe evidence", "Promote locally"),
                        interactionPrinciples = listOf("Expose only valid decisions"),
                        emotionalQualities = listOf("Calm", "Continuous"),
                        mustNotFeelLike = listOf("An IDE"),
                        accessibility = listOf("Keyboard operable"),
                    ),
                    baseRevision = genesis.revision,
                    baseHash = genesis.hash,
                ),
            ).status,
        )
        genesis = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision)
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    components = listOf(
                        ArchitectureComponent(
                            componentId = "app",
                            name = "Application",
                            responsibility = "Deliver the admitted primary journey.",
                            repositoryPaths = listOf("src"),
                        )
                    ),
                    decisions = listOf(
                        ArchitectureDecision(
                            decisionId = "local-first",
                            title = "Remain local",
                            context = "The product is piloted by one local architect.",
                            decision = "Keep all execution and promotion local.",
                            consequences = listOf("No remote push authority"),
                            componentIds = listOf("app"),
                        )
                    ),
                    firstEpicId = 2,
                    baseRevision = genesis.revision,
                    baseHash = genesis.hash,
                ),
            ).status,
        )
        genesis = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision)
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    blueprint = RepositoryBlueprint(
                        rootName = "first-local-product",
                        toolchain = "gradle",
                        modules = listOf("src", "test"),
                        verificationCommands = listOf("./gradlew test --no-daemon"),
                        policyPackIds = listOf("orchard.default-toolchains"),
                    ),
                    baseRevision = genesis.revision,
                    baseHash = genesis.hash,
                ),
            ).status,
        )
        assertEquals(ProjectGenesisStatus.ADMITTED, workspace.admitProjectGenesis(1).status)
        assertEquals(GENESIS_READY, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    private fun intent(type: Int, title: String, projectId: Int = 0) = DocumentIntent(
        actionTypeId = ACTION_CREATE,
        entityTypeId = type,
        boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
        projectId = projectId,
        title = title,
    )

    private object StaffModel : ModelProvider {
        override suspend fun triage(prompt: String): String = "{}"
        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = "{}"
        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:staff",
            provider = "test",
            model = "staff-model",
            contextWindowTokens = 100_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
    }

    private class ScenarioStaffModel : ModelProvider {
        private var codingCalls = 0
        private var auditCalls = 0
        private var analysisCalls = 0
        val codingCallCount: Int get() = codingCalls

        override suspend fun triage(prompt: String): String = "{}"

        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = "{}"

        override suspend fun executeCodingPatch(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            val content = when (codingCalls++) {
                0 -> "plugins { base }\n"
                1 -> "plugins { this is not valid Kotlin }\n"
                2 -> "plugins { base }\n\ndescription = \"Initial governed candidate\"\n\ntasks.register(\"test\") { dependsOn(\"check\") }\n"
                else -> "plugins { base }\n\ndescription = \"Repaired after independent audit\"\n\ntasks.register(\"test\") { dependsOn(\"check\") }\n"
            }
            val operations = if (codingCalls == 1) {
                listOf(
                    CodingFileOperation(CODING_FILE_WRITE, "build.gradle.kts", content),
                    CodingFileOperation(CODING_FILE_WRITE, "README.md", "Unauthorized scope expansion.\n"),
                )
            } else {
                listOf(CodingFileOperation(CODING_FILE_WRITE, "build.gradle.kts", content))
            }
            val output = Json.encodeToString(
                CodingPatchProposal(
                    summary = "Implement the governed local experience.",
                    operations = operations,
                )
            )
            return ModelGeneration(output, prompt.length, output.length)
        }

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            val contentHash = requireNotNull(
                Regex("\\\"path\\\":\\\"build\\.gradle\\.kts\\\",\\\"content\\\":.*?\\\"contentHash\\\":\\\"([0-9a-f]{64})\\\"")
                    .find(prompt)
            ).groupValues[1]
            val criterion = "The architect can observe one complete governed journey."
            val disposition = if (analysisCalls++ == 0) DISPOSITION_SCAFFOLD_ONLY else DISPOSITION_PARTIALLY_IMPLEMENTED
            val output = Json.encodeToString(
                RepositoryAnalysisPlanContent(
                    disposition = disposition,
                    summary = if (disposition == DISPOSITION_SCAFFOLD_ONLY) {
                        "The generated Gradle project is wired but has no admitted product behavior."
                    } else {
                        "The existing Gradle implementation should be repaired in place."
                    },
                    evidence = listOf(
                        RepositoryEvidenceCitation(
                            "build.gradle.kts",
                            observation = "The existing build surface is the owning implementation and must be reused.",
                            contentHash = contentHash,
                        )
                    ),
                    reuse = listOf("build.gradle.kts"),
                    preservedInvariants = listOf("Preserve the admitted local Gradle toolchain."),
                    nonGoals = listOf("Do not create a parallel build implementation."),
                    operations = listOf(
                        ExecutionPlanOperation(
                            1,
                            PLAN_OPERATION_MODIFY,
                            "build.gradle.kts",
                            instruction = "Implement the accepted journey by extending the existing Gradle surface.",
                            acceptanceCriteria = listOf(criterion),
                        )
                    ),
                    verificationCommands = listOf("./gradlew test --no-daemon"),
                )
            )
            return ModelGeneration(output, prompt.length, output.length)
        }

        override suspend fun executeCircuitSynthesis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            val ruleIds = Regex("\\\"ruleId\\\":\\\"([^\\\"]+)\\\"")
                .findAll(prompt).map { it.groupValues[1] }.distinct().toList()
            val evidenceIds = Regex("\\\"evidenceId\\\":([0-9]+)")
                .findAll(prompt).map { it.groupValues[1].toLong() }.distinct().toList()
            val status = if (auditCalls++ == 0) AUDIT_VIOLATION else AUDIT_CONFORMING
            val output = Json.encodeToString(
                AuditProposalOutput(
                    findings = ruleIds.map {
                        AuditFinding(it, status, if (status == AUDIT_VIOLATION) "The first candidate violates the admitted rule." else "The repaired candidate conforms.", evidenceIds)
                    },
                    rationale = if (status == AUDIT_VIOLATION) {
                        "The candidate violates admitted architecture and requires repair."
                    } else {
                        "Objective evidence and repository context demonstrate conformance."
                    },
                )
            )
            return ModelGeneration(output, prompt.length, output.length)
        }

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:scenario-staff",
            provider = "test",
            model = "scenario-staff-model",
            contextWindowTokens = 100_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
    }

    @Serializable
    private data class AuditProposalOutput(
        val findings: List<AuditFinding>,
        val rationale: String,
    )
}
