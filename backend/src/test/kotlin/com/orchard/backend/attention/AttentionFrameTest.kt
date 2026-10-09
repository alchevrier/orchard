package com.orchard.backend.attention

import com.orchard.backend.analysis.AnalysisExecutionProvenance
import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.ExecutionPlanOperation
import com.orchard.backend.analysis.ExecutionPlanScopeCoverage
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.WorkPackageCheck
import com.orchard.backend.analysis.WorkPackageDesignAuthority
import com.orchard.backend.analysis.WorkPackageEvidenceAuthority
import com.orchard.backend.analysis.WorkPackageEvidenceCitation
import com.orchard.backend.analysis.WorkPackageIntentAuthority
import com.orchard.backend.analysis.WorkPackageOperation
import com.orchard.backend.analysis.WorkPackageOperationAuthority
import com.orchard.backend.analysis.WorkPackageOwnershipBoundary
import com.orchard.backend.analysis.WorkPackageSource
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttentionFrameTest {
    @Test
    fun `quality dimensions and failed compilation reports are independent and reproducible without inference`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(listOf(com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun answer() = 42\n")), 0)
        val frame = bindRepositoryAnalysisAttentionContext(analysisFrame(), emptyMap(), setOf("src/Main.kt"))
        val serialized = kotlinx.serialization.json.Json.parseToJsonElement(kotlinx.serialization.json.Json.encodeToString(AnalysisAttentionFrame.serializer(), frame)) as kotlinx.serialization.json.JsonObject
        val binding = com.orchard.backend.vector.ModelBindingProfile("test", "test", "gpt-oss:120b", 10000, emptySet(), mapOf("protocol" to "OPENAI_COMPATIBLE"))
        val input = com.orchard.backend.vector.accountModelInput("hello world", binding)
        val report = compileContextQualityReport(serialized, context, setOf("src/Main.kt", "src/Missing.kt"), input, 1000)
        assertEquals(ContextQualityStatus.PASS, report.validity)
        assertEquals(ContextQualityStatus.PASS, report.relevance)
        assertEquals(ContextQualityStatus.FAIL, report.adequacy)
        assertEquals(ContextQualityStatus.UNKNOWN, report.capacity)
        assertEquals(ContextQualityStatus.NOT_ATTEMPTED, report.downstream)
        assertEquals(listOf("src/Missing.kt"), report.missingPaths)
        assertEquals(listOf("docs/Contract.md"), report.deferredPaths)
        assertEquals(listOf("scope-1"), report.evidence.single().supportedScopes)
        assertEquals(context.files.single().contentHash, report.evidence.single().sourceHash)
        assertEquals(contextQualityReportHash(report), contextQualityReportHash(compileContextQualityReport(serialized, context, setOf("src/Main.kt", "src/Missing.kt"), input, 1000)))
        val overBudget = compileContextQualityReport(serialized, context, setOf("src/Main.kt"), com.orchard.backend.vector.accountModelInput("hello world", binding.copy(configuration = emptyMap())), 1)
        assertEquals(ContextQualityStatus.PASS, overBudget.adequacy)
        assertEquals(ContextQualityStatus.FAIL, overBudget.capacity)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(ContextQualityReport.serializer(), kotlinx.serialization.json.Json.encodeToString(ContextQualityReport.serializer(), report))
        assertEquals(report, decoded)
    }

    @Test
    fun `canonical model references preserve pinned authority and reject missing or ambiguous identities`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(listOf(
            com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun answer() = 42\n"),
            com.orchard.backend.agent.CodingContextFile("docs/Contract.md", "The answer is forty two.\n"),
        ), 0)
        val frame = bindRepositoryAnalysisAttentionContext(analysisFrame(), emptyMap(), context.files.map { it.path }.toSet())
        val serialized = kotlinx.serialization.json.Json.parseToJsonElement(kotlinx.serialization.json.Json.encodeToString(AnalysisAttentionFrame.serializer(), frame)) as kotlinx.serialization.json.JsonObject
        val projection = canonicalAttentionProjection(serialized, context)
        assertEquals(serialized, resolveCanonicalAttention(projection, context))
        assertEquals(1, projection.hashes.values.count { it == "1".repeat(64) })
        assertFalse(projection.frame.toString().contains("src/Main.kt"))
        assertFalse(projection.frame.toString().contains("docs/Contract.md"))
        assertTrue(runCatching { resolveCanonicalAttention(projection.copy(sources = emptyMap()), context) }.isFailure)
        assertTrue(runCatching { canonicalAttentionProjection(serialized, context.copy(files = context.files + context.files.first())) }.isFailure)
        assertTrue(runCatching { resolveCanonicalAttention(projection.copy(authorities = emptyMap()), context) }.isFailure)
    }

    @Test
    fun `large admitted analysis scope retains every coordinate through bounded serialized evidence`() {
        val paths = (1..100).map { "src/Owner$it.kt" }
        val definition = com.orchard.backend.workspace.WorkDefinitionManifest(
            definitionId = 8, revision = 1, workItemId = 9, createdAt = "2026-10-04T00:00:00Z",
            systemWorkflow = com.orchard.backend.workspace.DefaultSystemWorkflow.resolve(com.orchard.backend.workspace.ENTITY_TASK),
            definition = com.orchard.backend.workspace.WorkDefinitionSubmission(
                requestedOutcome = "Preserve every admitted scope.", currentBehavior = "Old behavior.", requiredBehavior = "New behavior.",
                scope = paths.map { "Modify `$it` behavior." }, nonGoals = emptyList(), constraints = emptyList(), acceptanceCriteria = emptyList(),
                repositoryCoordinates = paths.mapIndexed { index, path -> com.orchard.backend.workspace.RepositoryCoordinate("coordinate-$index", path, listOf(index)) },
            ),
            assessment = com.orchard.backend.workspace.DefinitionAssessment("READY", emptyList()), hash = "1".repeat(64),
        )
        val frame = compileRepositoryAnalysisAttentionFrame("repository-analysis", "a".repeat(40), definition, paths.indices.toSet())
        val bound = bindRepositoryAnalysisAttentionContext(frame, emptyMap(), paths.take(24).toSet())
        val serialized = kotlinx.serialization.json.Json.encodeToString(AnalysisAttentionFrame.serializer(), bound)
        val restored = kotlinx.serialization.json.Json.decodeFromString(AnalysisAttentionFrame.serializer(), serialized)

        assertEquals(100, restored.correlations.size)
        assertEquals(frame.correlations.map { it.scope }, restored.correlations.map { it.scope })
        assertEquals(paths, restored.correlations.flatMap { it.coordinatePaths })
        assertEquals(paths.take(24), restored.correlations.flatMap { it.evidencePaths })
        assertEquals(paths.drop(24), restored.correlations.flatMap { it.deferredPaths })
        assertEquals(76, restored.correlations.count { it.unresolved })
        assertEquals(listOf("READ_SOURCE", "PROPOSE_ANALYSIS"), restored.allowedActions)
        assertEquals(analysisAttentionFrameHash(restored), restored.hash)
        assertTrue(requireNotNull(analysisAttentionCandidateDiagnostic(restored, emptyList(), listOf(paths.last()))).contains("deferred or absent"))
    }

    @Test
    fun `compiler preserves bidirectional task code correlation and explicit deferred work`() {
        val plan = plan()
        val workPackage = workPackage()

        val frame = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())

        assertEquals(listOf(1), frame.slice.activeOperationOrders)
        assertEquals(listOf(2), frame.slice.deferredOperationOrders)
        assertFalse(frame.slice.completesObjective)
        assertEquals(listOf(1), frame.correlations.map { it.operationOrder }.distinct())
        assertEquals(listOf("src/Main.kt"), frame.correlations.flatMap { it.ownerPaths }.distinct())
        assertEquals(listOf("Return forty two."), frame.correlations.map { it.requirement.text })
        assertEquals(frame.hash, compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds()).hash)
        assertTrue(verifyCodingAttentionFrame(frame, plan, workPackage).adequate)
        assertTrue(codingAttentionContextQuery(frame).contains("Return 42."))
        assertFalse(codingAttentionContextQuery(frame).contains("Expose the answer through the API."))
    }

    @Test
    fun `verifier rejects rehashed changes to the active operation instruction`() {
        val plan = plan()
        val workPackage = workPackage()
        val valid = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())
        val changed = valid.copy(correlations = valid.correlations.map {
            it.copy(expectedBehavior = listOf("Redesign the entire module."))
        }).let { it.copy(hash = attentionFrameHash(it)) }

        assertTrue(verifyCodingAttentionFrame(changed, plan, workPackage).diagnostics.contains("Correlation 1 changes admitted behavior."))
    }

    @Test
    fun `verifier rejects orphan operations and omitted invariants`() {
        val plan = plan()
        val workPackage = workPackage()
        val valid = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())
        val invalid = valid.copy(correlations = emptyList(), invariants = emptyList(), hash = "")
            .let { it.copy(hash = attentionFrameHash(it)) }

        val report = verifyCodingAttentionFrame(invalid, plan, workPackage)

        assertFalse(report.adequate)
        assertTrue(report.diagnostics.contains("Active operations lack task correlation: 1."))
        assertTrue(report.diagnostics.contains("Attention frame changes mandatory governing authority."))
    }

    @Test
    fun `verifier rejects rehashed authority widening`() {
        val plan = plan()
        val workPackage = workPackage()
        val valid = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())
        val widened = valid.copy(
            ownershipPaths = valid.ownershipPaths + "src/Unrelated.kt",
            allowedActions = valid.allowedActions + "DELETE_FILE",
            hash = "",
        ).let { it.copy(hash = attentionFrameHash(it)) }

        assertEquals(
            listOf("Attention frame changes executable ownership or action authority."),
            verifyCodingAttentionFrame(widened, plan, workPackage).diagnostics,
        )
    }

    @Test
    fun `compiler represents transversal operational and security owners without losing reverse traceability`() {
        val plan = plan().let { original ->
            original.copy(content = original.content.copy(
                scopeCoverage = listOf(
                    ExecutionPlanScopeCoverage("Migrate persisted records and deployment recovery.", listOf("src/Main.kt", "src/Api.kt"), listOf(1, 2)),
                    ExecutionPlanScopeCoverage("Preserve ACL enforcement and denied-access auditing.", listOf("src/Main.kt", "src/Api.kt"), listOf(1, 2)),
                ),
            ))
        }
        val packageAuthority = workPackage().let { original ->
            original.copy(
                ownership = original.ownership.copy(
                    paths = listOf("src/Main.kt", "src/Api.kt"),
                    likelyImplementationPaths = listOf("src/Main.kt", "src/Api.kt"),
                    requiredImplementationPaths = listOf("src/Main.kt", "src/Api.kt"),
                ),
                operations = WorkPackageOperationAuthority(listOf(
                    original.operations.operations.single(),
                    WorkPackageOperation(2, PLAN_OPERATION_MODIFY, "src/Api.kt", "answerApi", "Expose the migrated answer securely.", listOf("Authorized access returns 42 and denied access is audited.")),
                )),
            )
        }

        val frame = compileCodingAttentionFrame(
            "DELIVER_CHANGE:CODING_PATCH",
            9,
            plan,
            packageAuthority,
            mapOf(0 to AttentionScopeKind.OPERATIONAL, 1 to AttentionScopeKind.SECURITY),
        )

        assertTrue(frame.slice.completesObjective)
        assertEquals(setOf(AttentionScopeKind.OPERATIONAL, AttentionScopeKind.SECURITY), frame.correlations.map { it.scopeKind }.toSet())
        assertEquals(setOf(1, 2), frame.correlations.mapNotNull { it.operationOrder }.toSet())
        assertEquals(setOf("src/Main.kt", "src/Api.kt"), frame.correlations.flatMap { it.ownerPaths }.toSet())
        assertTrue(verifyCodingAttentionFrame(frame, plan, packageAuthority).adequate)
    }

    @Test
    fun `compiler keeps compliant evidence visible without granting an operation`() {
        val plan = plan().let { original ->
            original.copy(content = original.content.copy(scopeCoverage = original.content.scopeCoverage +
                ExecutionPlanScopeCoverage("Document the unchanged compatibility contract.", listOf("docs/Contract.md"), compliantEvidencePaths = listOf("docs/Contract.md"))))
        }
        val packageAuthority = workPackage().let { original ->
            original.copy(evidence = WorkPackageEvidenceAuthority(original.evidence.citations +
                WorkPackageEvidenceCitation("docs/Contract.md", null, "The contract already documents compatibility.", "5".repeat(64), true)))
        }

        val frame = compileCodingAttentionFrame(
            "DELIVER_CHANGE:CODING_PATCH",
            9,
            plan,
            packageAuthority,
            scopeKinds() + (2 to AttentionScopeKind.DOCUMENTATION),
        )

        val evidenceOnly = frame.correlations.single { it.disposition == ATTENTION_DISPOSITION_EVIDENCE_ONLY }
        assertEquals(null, evidenceOnly.operationOrder)
        assertEquals(listOf("docs/Contract.md"), evidenceOnly.ownerPaths)
        assertTrue(verifyCodingAttentionFrame(frame, plan, packageAuthority).adequate)
        assertEquals(listOf("src/Main.kt", "docs/Contract.md"), codingAttentionContextPaths(frame, plan))
        assertEquals(listOf("src/Main.kt"), frame.ownershipPaths)
        assertEquals(null, codingAttentionContextDiagnostic(frame, plan, setOf("src/Main.kt", "docs/Contract.md")))
        assertEquals(
            "Coding context omits Attention evidence: docs/Contract.md.",
            codingAttentionContextDiagnostic(frame, plan, setOf("src/Main.kt")),
        )
        assertEquals(
            "Proposed paths do not reverse-trace to the admitted objective: docs/Contract.md.",
            attentionOperationDiagnostic(frame, listOf(
                AttentionProposedOperation("REPLACE_LITERAL", "src/Main.kt"),
                AttentionProposedOperation("REWRITE_FILE", "docs/Contract.md"),
            )),
        )
    }

    @Test
    fun `actionable path guard rejects drift and incomplete forward coverage`() {
        val plan = plan()
        val workPackage = workPackage()
        val frame = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, workPackage, scopeKinds())

        assertEquals(null, attentionOperationDiagnostic(frame, listOf(AttentionProposedOperation("REPLACE_LITERAL", "src/Main.kt"))))
        assertEquals(
            "Proposed paths do not reverse-trace to the admitted objective: src/Unrelated.kt.",
            attentionOperationDiagnostic(frame, listOf(
                AttentionProposedOperation("REWRITE_FILE", "src/Main.kt"),
                AttentionProposedOperation("REWRITE_FILE", "src/Unrelated.kt"),
            )),
        )
        assertEquals(
            "Proposed operations omit actionable task-code correlations: src/Main.kt.",
            attentionOperationDiagnostic(frame, emptyList()),
        )
        assertEquals(
            "Proposed action DELETE_FILE on src/Main.kt does not reverse-trace to the admitted operation.",
            attentionOperationDiagnostic(frame, listOf(AttentionProposedOperation("DELETE_FILE", "src/Main.kt"))),
        )
    }

    @Test
    fun `scope classifier preserves security and operational impact`() {
        assertEquals(AttentionScopeKind.SECURITY, attentionScopeKind("Preserve ACL enforcement and authorization denial."))
        assertEquals(AttentionScopeKind.OPERATIONAL, attentionScopeKind("Provide deployment rollback and recovery metrics."))
        assertEquals(AttentionScopeKind.MIGRATION, attentionScopeKind("Maintain backward compatibility during migration."))
    }

    @Test
    fun `coding attention pins admitted project purpose`() {
        val plan = plan()
        val workPackage = workPackage()
        val purpose = projectPurpose("5".repeat(64))
        val frame = compileCodingAttentionFrame(
            "DELIVER_CHANGE:CODING_PATCH",
            9,
            plan,
            workPackage,
            scopeKinds(),
            purpose,
        )

        assertEquals("PROJECT_PURPOSE", frame.projectPurpose?.kind)
        assertEquals(purpose.hash, frame.projectPurpose?.sourceHash)
        assertTrue(verifyCodingAttentionFrame(frame, plan, workPackage, purpose).adequate)
        assertFalse(verifyCodingAttentionFrame(frame, plan, workPackage, projectPurpose("6".repeat(64))).adequate)
    }

    @Test
    fun `coding context includes prerequisite owners without selecting unrelated deferred work`() {
        val plan = plan()
        val original = workPackage()
        val packageAuthority = original.copy(
            ownership = original.ownership.copy(paths = listOf("src/Api.kt")),
            operations = WorkPackageOperationAuthority(listOf(
                WorkPackageOperation(2, PLAN_OPERATION_MODIFY, "src/Api.kt", "answerApi", "Expose the answer.", listOf("The API returns 42.")),
            )),
            evidence = WorkPackageEvidenceAuthority(emptyList()),
        )
        val frame = compileCodingAttentionFrame("DELIVER_CHANGE:CODING_PATCH", 9, plan, packageAuthority, scopeKinds())

        assertEquals(listOf(1), frame.slice.prerequisiteOperationOrders)
        assertEquals(listOf("src/Api.kt", "src/Main.kt"), codingAttentionContextPaths(frame, plan))
        assertEquals(listOf("src/Api.kt"), frame.ownershipPaths)
        assertEquals(
            "Proposed paths do not reverse-trace to the admitted objective: src/Main.kt.",
            attentionOperationDiagnostic(frame, listOf(
                AttentionProposedOperation("REPLACE_LITERAL", "src/Api.kt"),
                AttentionProposedOperation("REWRITE_FILE", "src/Main.kt"),
            )),
        )
    }

    @Test
    fun `analysis binding preserves scope coverage and excludes unrelated source`() {
        val frame = analysisFrame()
        val bound = bindRepositoryAnalysisAttentionContext(
            frame,
            mapOf("implementation" to listOf("src/Main.kt", "src/MainTest.kt")),
            setOf("src/Main.kt", "docs/Contract.md", "src/Unrelated.kt"),
        )

        assertEquals(listOf("src/Main.kt"), bound.correlations[0].evidencePaths)
        assertEquals(listOf("src/MainTest.kt"), bound.correlations[0].deferredPaths)
        assertEquals(listOf("docs/Contract.md"), bound.correlations[1].evidencePaths)
        assertFalse(bound.correlations.any { it.unresolved })
        assertEquals(listOf("READ_SOURCE", "PROPOSE_ANALYSIS"), bound.allowedActions)
        assertEquals(analysisAttentionFrameHash(bound), bound.hash)
        assertEquals(null, analysisAttentionCandidateDiagnostic(bound, listOf("src/Main.kt"), listOf("docs/Contract.md")))
        assertEquals(
            "Analysis source paths do not trace to candidate Attention evidence: docs/Contract.md.",
            analysisAttentionCandidateDiagnostic(bound, listOf("docs/Contract.md"), emptyList()),
        )
        assertEquals(
            "Analysis source paths do not trace to candidate Attention evidence: src/Unrelated.kt.",
            analysisAttentionCandidateDiagnostic(bound, listOf("src/Unrelated.kt"), emptyList()),
        )
    }

    @Test
    fun `analysis binding explicitly defers removed evidence and restores it without stale deferrals`() {
        val bound = bindRepositoryAnalysisAttentionContext(
            analysisFrame(), emptyMap(), setOf("src/Main.kt", "docs/Contract.md"),
        )
        val compacted = bindRepositoryAnalysisAttentionContext(bound, emptyMap(), setOf("src/Main.kt"))

        assertEquals(bound.correlations.map { it.scope }, compacted.correlations.map { it.scope })
        assertTrue(compacted.correlations[1].unresolved)
        assertEquals(listOf("docs/Contract.md"), compacted.correlations[1].deferredPaths)
        assertEquals(
            "Analysis citations refer to evidence deferred or absent from Attention context: docs/Contract.md.",
            analysisAttentionCandidateDiagnostic(compacted, listOf("src/Main.kt"), listOf("docs/Contract.md")),
        )
        val restored = bindRepositoryAnalysisAttentionContext(compacted, emptyMap(), setOf("src/Main.kt", "docs/Contract.md"))
        assertFalse(restored.correlations[1].unresolved)
        assertTrue(restored.correlations[1].deferredPaths.isEmpty())
        assertEquals(bound.hash, restored.hash)
    }

    private fun analysisFrame() = AnalysisAttentionFrame(
        workflowStepId = "repository-analysis",
        workItemId = 9,
        repositoryRevision = "a".repeat(40),
        objective = AttentionAuthorityReference("WORK_DEFINITION", "8", "1".repeat(64), "Return the answer."),
        correlations = listOf(
            AnalysisAttentionCorrelation(
                AttentionAuthorityReference("WORK_DEFINITION_SCOPE", "scope-1", "1".repeat(64), "Return forty two."),
                AttentionScopeKind.IMPLEMENTATION,
                ATTENTION_DISPOSITION_ANALYSIS_CANDIDATE,
                listOf("src/Main.kt"),
                listOf("implementation"),
            ),
            AnalysisAttentionCorrelation(
                AttentionAuthorityReference("WORK_DEFINITION_SCOPE", "scope-2", "1".repeat(64), "Inspect the compatibility contract."),
                AttentionScopeKind.DOCUMENTATION,
                ATTENTION_DISPOSITION_EVIDENCE_ONLY,
                listOf("docs/Contract.md"),
                emptyList(),
            ),
        ),
    )

    private fun scopeKinds() = mapOf(
        0 to AttentionScopeKind.IMPLEMENTATION,
        1 to AttentionScopeKind.API,
    )

    private fun projectPurpose(hash: String) = ProjectGenesisRevision(
        genesisId = 11,
        projectId = 1,
        revision = 5,
        phase = GENESIS_READY,
        productIntent = "Deliver governed software with justified completion.",
        experience = ExperienceContract(productPromise = "The governed outcome is demonstrable."),
        admitted = true,
        actor = "owner",
        hash = hash,
    )

    private fun plan(): RepositoryExecutionPlan = RepositoryExecutionPlan(
        planId = 4,
        runId = 2,
        revision = 1,
        projectId = 1,
        baseRevision = "a".repeat(40),
        content = RepositoryAnalysisPlanContent(
            disposition = "PARTIALLY_IMPLEMENTED",
            summary = "Return forty two and expose it through the API.",
            evidence = emptyList(),
            reuse = emptyList(),
            preservedInvariants = listOf("Keep the public function name."),
            nonGoals = listOf("Do not redesign the module."),
            scopeCoverage = listOf(
                ExecutionPlanScopeCoverage("Return forty two.", listOf("src/Main.kt"), listOf(1)),
                ExecutionPlanScopeCoverage("Expose the answer through the API.", listOf("src/Api.kt"), listOf(2)),
            ),
            operations = listOf(
                ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", instruction = "Return 42.", acceptanceCriteria = listOf("The function returns 42.")),
                ExecutionPlanOperation(2, PLAN_OPERATION_MODIFY, "src/Api.kt", instruction = "Expose the answer.", acceptanceCriteria = listOf("The API returns 42."), dependsOnOrders = listOf(1)),
            ),
            verificationCommands = listOf("./gradlew test"),
        ),
        provenance = AnalysisExecutionProvenance("analysis", "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64), 3),
        hash = "f".repeat(64),
    )

    private fun workPackage(): ExecutableWorkPackage = ExecutableWorkPackage(
        packageId = 7,
        revision = 1,
        projectId = 1,
        runId = 2,
        repositoryRevision = "a".repeat(40),
        intent = WorkPackageIntentAuthority(8, 1, "1".repeat(64), "Return the answer.", "Returns one.", "Returns forty two.", listOf("Keep the API stable."), listOf("The answer is 42.")),
        design = WorkPackageDesignAuthority(4, 1, "f".repeat(64), "Change the owner.", listOf("Keep the public function name."), listOf("Do not redesign the module.")),
        ownership = WorkPackageOwnershipBoundary(listOf("src/Main.kt"), listOf("src/Main.kt"), emptyList(), listOf("READ_SOURCE", "REWRITE_FILE", "RUN_CHECK"), listOf("src/Main.kt")),
        evidence = WorkPackageEvidenceAuthority(listOf(WorkPackageEvidenceCitation("src/Main.kt", "answer", "The owner returns one.", "2".repeat(64), false))),
        operations = WorkPackageOperationAuthority(listOf(WorkPackageOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "answer", "Return 42.", listOf("The function returns 42.")))),
        expectedBehavior = listOf("Returns forty two."),
        unresolvedAuthorityQuestions = emptyList(),
        sources = listOf(WorkPackageSource("src/Main.kt", "fun answer() = 1\n", "3".repeat(64))),
        checks = listOf(WorkPackageCheck("check-1", "./gradlew test")),
        escalationConditions = listOf("STALE_REVISION", "SCOPE_REQUIRED", "MISSING_AUTHORITY", "DESIGN_CONTRADICTION"),
        hash = "4".repeat(64),
    )
}