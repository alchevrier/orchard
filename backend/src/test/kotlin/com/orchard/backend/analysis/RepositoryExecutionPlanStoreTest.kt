package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.workspace.REPOSITORY_EVIDENCE_AFFINE_TEST
import com.orchard.backend.workspace.RepositoryEvidenceSelector
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryExecutionPlanStoreTest {
    @Test
    fun `repository analysis attempt block requires durable explicit retry`() {
        val directory = createTempDirectory("orchard-analysis-attempts-")
        val store = FileRepositoryAnalysisAttemptStore(directory)
        val revision = "a".repeat(40)
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_BLOCKED,
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                "The plan omitted required selector coverage.",
                "b".repeat(64),
            )
        }

        assertTrue(FileRepositoryAnalysisAttemptStore(directory).isBlocked(11, revision))
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                "RETRY_AUTHORIZED",
                "A human explicitly authorized one successor attempt.",
            )
        }

        val restored = FileRepositoryAnalysisAttemptStore(directory)
        assertFalse(restored.isBlocked(11, revision))
        assertEquals(
            "Current rejection (1 occurrence): The plan omitted required selector coverage.",
            restored.retryDiagnostic(11, revision),
        )
        assertEquals(listOf(ANALYSIS_ATTEMPT_BLOCKED, ANALYSIS_ATTEMPT_RETRY_AUTHORIZED), restored.load().map { it.state })
        assertFailsWith<IllegalArgumentException> {
            store.appendNext { attemptId ->
                RepositoryAnalysisAttempt(
                    attemptId,
                    11,
                    revision,
                    ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                    "RETRY_AUTHORIZED",
                    "Duplicate authorization.",
                )
            }
        }
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_BLOCKED,
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                "The plan omitted a required source operation.",
                "c".repeat(64),
            )
        }
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                "RETRY_AUTHORIZED",
                "A human explicitly authorized another successor attempt.",
            )
        }

        assertEquals(
            "Current rejection (1 occurrence): The plan omitted a required source operation.\n" +
                "Previously rejected defects that must remain fixed:\n" +
                "- The plan omitted required selector coverage.",
            store.retryDiagnostic(11, revision),
        )
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_BLOCKED,
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                "The plan omitted a required source operation.",
                "d".repeat(64),
            )
        }
        store.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                11,
                revision,
                ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                "RETRY_AUTHORIZED",
                "A human explicitly authorized a repeated correction.",
            )
        }

        assertEquals(
            "Current rejection (2 occurrences): The plan omitted a required source operation.\n" +
                "Previously rejected defects that must remain fixed:\n" +
                "- The plan omitted required selector coverage.",
            store.retryDiagnostic(11, revision),
        )
    }

    @Test
    fun `repository analysis trusts measured generation tokens instead of utf8 byte size`() {
        val generation = com.orchard.backend.vector.ModelGeneration("x".repeat(10_000), 18_343, 2_722)

        assert(repositoryAnalysisGenerationWithinBudget(generation, 96_000, 8_000))
        assert(!repositoryAnalysisGenerationWithinBudget(generation.copy(completionTokens = 8_001), 96_000, 8_000))
        assert(!repositoryAnalysisGenerationWithinBudget(generation.copy(promptTokens = 96_001), 96_000, 8_000))
    }

    @Test
    fun `repository acceptance coverage normalizes punctuation and reports exact gaps`() {
        val accepted = listOf("Use platform-default sans.", "Keep machine-readable output.")
        val content = plan(1, 1, "a".repeat(40)).content.copy(
            operations = listOf(
                ExecutionPlanOperation(
                    1,
                    PLAN_OPERATION_MODIFY,
                    "src/Main.kt",
                    "main",
                    "Update it.",
                    listOf("Use platform\u2011default sans.", "Keep machine\u2011readable output."),
                )
            ),
        )

        assertNull(repositoryAcceptanceCoverageDiagnostic(accepted, content))
        assertEquals(
            "Execution operations must cover the exact acceptance criteria. Missing: Keep machine-readable output. Unexpected: Invent behavior.",
            repositoryAcceptanceCoverageDiagnostic(
                accepted,
                content.copy(operations = content.operations.map { it.copy(acceptanceCriteria = listOf("Use platform-default sans.", "Invent behavior.")) }),
            ),
        )
    }

    @Test
    fun `repository analysis decoding diagnostics preserve bounded root cause`() {
        val generation = com.orchard.backend.vector.ModelGeneration("{}", 1, 1)

        assertEquals(
            "The analysis model output exceeded the admitted token budget.",
            repositoryAnalysisDecodeDiagnostic(null, null),
        )
        val diagnostic = repositoryAnalysisDecodeDiagnostic(
            generation,
            IllegalArgumentException("Missing required field\noperations" + "x".repeat(600)),
        )
        assert(diagnostic.startsWith("The analysis model did not return valid strict JSON: Missing required field operations"))
        assert(diagnostic.length <= 570)
    }

    @Test
    fun `repository analyst prompt includes a complete strict json example`() {
        val prompt = requireNotNull(
            RepositoryAnalysisService::class.java.classLoader.getResourceAsStream(
                "default-system-prompts/repository_analysis_agent.md"
            )
        ).bufferedReader().use { it.readText() }

        assert(prompt.contains("A valid response has exactly this shape:"))
        assert(prompt.contains("\"disposition\":\"PARTIALLY_IMPLEMENTED\""))
        assert(prompt.contains("Include exactly the disposition, summary, evidence, reuse, preservedInvariants, nonGoals, scopeCoverage, operations, verificationCommands, and unresolvedQuestions top-level keys."))
        assert(prompt.contains("copying scope exactly without paraphrasing, omission, or invention"))
        assert(prompt.contains("or from a path targeted by a CREATE, MODIFY, or DELETE operation"))
        assert(prompt.contains("Every evidencePath in implementation scopeCoverage must also be targeted by a CREATE, MODIFY, or DELETE operation"))
        assert(prompt.contains("every referenced order must exist"))
        assert(prompt.contains("Treat universal scope words such as all, every, and across as exhaustive"))
        assert(prompt.contains("typed repository evidence selectors evaluated against complete supplied source"))
        assert(prompt.contains("return those gaps in unresolvedQuestions instead of claiming complete scope coverage"))
        assert(prompt.contains("matchedDeclarations selected from its complete source before content excerpting"))
        assert(prompt.contains("do not claim an owner or surface is absent when matchedDeclarations identifies it"))
        assert(prompt.contains("requiredSourcePathGroups is deterministic authority"))
        assert(prompt.contains("Group IDs are selector IDs"))
        assert(prompt.contains("Never omit a grouped path"))
        assert(prompt.contains("each value is a list of selector IDs"))
        assert(prompt.contains("copy that exact union to the matching scopeCoverage evidencePaths"))
        assert(prompt.contains("Scope clauses beginning with Inspect, Analyze, or Audit are evidence-only analysis scope"))
        assert(prompt.contains("for each path in evidencePaths, include a CREATE, MODIFY, or DELETE operation whose path is that same string"))
        assert(prompt.contains("A VERIFY operation on \".\" or another path never satisfies this source-path requirement"))
        assert(prompt.contains("put only the created or modified test source path in evidencePaths"))
        assert(prompt.contains("never put a requiredScope value in acceptanceCriteria"))
        assert(prompt.contains("verificationCommands may contain only exact values from requiredVerificationCommands"))
        assert(prompt.contains("copy path and contentHash together as one unchanged pair from requiredEvidence"))
        assert(prompt.contains("Every operation, including every CREATE, MODIFY, DELETE, and VERIFY operation, must contain at least one exact value from requiredAcceptanceCriteria"))
        assert(prompt.contains("never emit an empty acceptanceCriteria array"))
        assert(prompt.contains("Copy values from requiredAcceptanceCriteria and requiredVerificationCommands exactly; do not paraphrase them."))
        assert(prompt.contains("Copy the complete requiredAcceptanceCriteria list into the final VERIFY operation"))
    }

    @Test
    fun `execution plans recover immutable successors including revised plans on one base`() {
        val directory = createTempDirectory("orchard-analysis-plans-")
        val store = FileRepositoryExecutionPlanStore(directory)
        val first = plan(1, 1, "a".repeat(40))
        val successor = plan(2, 2, "b".repeat(40))
        val revisedSuccessor = plan(3, 3, "b".repeat(40))

        store.append(first)
        store.append(successor)
        store.append(revisedSuccessor)

        assertEquals(listOf(first, successor, revisedSuccessor), FileRepositoryExecutionPlanStore(directory).load())
        assertFailsWith<IllegalArgumentException> {
            store.append(plan(4, 3, "c".repeat(40)))
        }
        assertEquals(3, FileRepositoryExecutionPlanStore(directory).load().size)
        assertEquals(3, Files.readAllLines(directory.resolve("repository-analysis-plans.jsonl")).size)
    }

    @Test
    fun `repository operation diagnostics identify invalid path shape`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(
            files = listOf(com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun main() = Unit")),
            omittedFileCount = 0,
        )
        val valid = plan(1, 1, "a".repeat(40)).content

        assertNull(repositoryOperationShapeDiagnostic(context, valid))
        assertEquals(
            "Execution operation 1 cannot CREATE existing path src/Main.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = valid.operations.map { it.copy(action = PLAN_OPERATION_CREATE) }),
            ),
        )
        assertNull(
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_CREATE, "src/New.kt", "new", "Create it.", listOf("Behavior works.")),
                    ExecutionPlanOperation(2, PLAN_OPERATION_VERIFY, "src/New.kt", "new", "Verify it.", listOf("Behavior works.")),
                )),
            )
        )
        assertNull(
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "main", "Change it.", listOf("Behavior works.")),
                    ExecutionPlanOperation(2, PLAN_OPERATION_VERIFY, ".", null, "Verify the repository.", listOf("Behavior works.")),
                )),
            )
        )
        assertEquals(
            "Execution operation 1 cannot VERIFY unavailable path src/New.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_VERIFY, "src/New.kt", "new", "Verify it.", listOf("Behavior works.")),
                )),
            ),
        )
        assertEquals(
            "Execution operation 1 cannot MODIFY unobserved path src/Missing.kt.",
            repositoryOperationShapeDiagnostic(
                context,
                valid.copy(operations = valid.operations.map { it.copy(path = "src/Missing.kt") }),
            ),
        )
    }

    @Test
    fun `repository evidence diagnostics identify unavailable paths and changed hashes`() {
        val context = com.orchard.backend.agent.CodingRepositoryContext(
            files = listOf(com.orchard.backend.agent.CodingContextFile("src/Main.kt", "fun main() = Unit", "c".repeat(64))),
            omittedFileCount = 0,
        )
        val valid = plan(1, 1, "a".repeat(40)).content

        assertNull(repositoryEvidenceDiagnostic(context, valid))
        assertEquals(
            "Repository evidence citation 1 uses unavailable path src/Missing.kt.",
            repositoryEvidenceDiagnostic(
                context,
                valid.copy(evidence = valid.evidence.map { it.copy(path = "src/Missing.kt") }),
            ),
        )
        assertEquals(
            "Repository evidence citation 1 has the wrong content hash for src/Main.kt.",
            repositoryEvidenceDiagnostic(
                context,
                valid.copy(evidence = valid.evidence.map { it.copy(contentHash = "d".repeat(64)) }),
            ),
        )
        assertNull(repositoryAnalysisIdentityDiagnostic(context, valid.copy(operations = emptyList())))
        assertEquals(
            "Analysis identity is incomplete.",
            repositoryAnalysisIdentityDiagnostic(context, valid.copy(summary = "")),
        )
    }

    @Test
    fun `repository scope coverage requires exact clauses pinned evidence and source operations`() {
        val scope = listOf("Inspect the owner.", "Add regression coverage.")
        val content = plan(1, 1, "a".repeat(40)).content.copy(
            coveredScope = scope,
            scopeCoverage = listOf(
                ExecutionPlanScopeCoverage(scope[0], listOf("src/Main.kt"), listOf(1)),
                ExecutionPlanScopeCoverage(scope[1], listOf("src/MainTest.kt"), listOf(2)),
            ),
            operations = listOf(
                ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", null, "Change it.", listOf("Behavior works.")),
                ExecutionPlanOperation(2, PLAN_OPERATION_MODIFY, "src/MainTest.kt", null, "Test it.", listOf("Behavior works.")),
            ),
        )

        assertNull(repositoryScopeCoverageDiagnostic(scope, content))
        assertNull(
            repositoryScopeCoverageDiagnostic(
                scope,
                content.copy(scopeCoverage = content.scopeCoverage.map {
                    if (it.scope == "Add regression coverage.") it else it.copy(scope = "Inspect the owner.\u00a0")
                }),
            ),
        )
        val hyphenatedScope = listOf("Review machine-readable output.")
        assertNull(
            repositoryScopeCoverageDiagnostic(
                hyphenatedScope,
                content.copy(
                    scopeCoverage = listOf(
                        ExecutionPlanScopeCoverage("Review machine\u2011readable output.", listOf("src/Main.kt"), listOf(1))
                    ),
                ),
            ),
        )
        assertEquals(
            "Execution plan scope coverage must map every accepted scope clause exactly once. Missing: Add regression coverage.",
            repositoryScopeCoverageDiagnostic(scope, content.copy(scopeCoverage = content.scopeCoverage.take(1))),
        )
        assertEquals(
            "Execution plan scope coverage must map every accepted scope clause exactly once. Duplicated: Inspect the owner. Unexpected: Invent another scope.",
            repositoryScopeCoverageDiagnostic(
                scope,
                content.copy(scopeCoverage = listOf(
                    content.scopeCoverage.first(),
                    content.scopeCoverage.first(),
                    content.scopeCoverage.last().copy(scope = "Invent another scope."),
                    content.scopeCoverage.last(),
                )),
            ),
        )
        assertEquals(
            "Scope coverage 1 does not cite pinned evidence or a concrete source operation.",
            repositoryScopeCoverageDiagnostic(
                scope,
                content.copy(scopeCoverage = content.scopeCoverage.map { it.copy(evidencePaths = listOf("src/Missing.kt")) }),
            ),
        )
        val createContent = content.copy(
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/NewTest.kt"), listOf(1)) },
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_CREATE, "src/NewTest.kt", null, "Create it.", listOf("Behavior works."))),
        )
        assertNull(repositoryScopeCoverageDiagnostic(scope, createContent))
        val modifiedWithoutDuplicateCitation = content.copy(
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/OtherTest.kt"), listOf(1)) },
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/OtherTest.kt", null, "Change it.", listOf("Behavior works."))),
        )
        assertNull(repositoryScopeCoverageDiagnostic(scope, modifiedWithoutDuplicateCitation))
        val verifyOnly = content.copy(
            evidence = content.evidence + RepositoryEvidenceCitation("src/MainTest.kt", null, "Test owner.", "d".repeat(64)),
            scopeCoverage = content.scopeCoverage.map { it.copy(operationOrders = listOf(1)) },
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_VERIFY, ".", null, "Verify it.", listOf("Behavior works."))),
        )
        assertEquals(
            "Scope coverage 2 cites paths without corresponding source operations: src/MainTest.kt. " +
                "Linked source operation paths: <none>.",
            repositoryScopeCoverageDiagnostic(scope, verifyOnly),
        )
        val unmatchedEvidence = content.copy(
            evidence = content.evidence + RepositoryEvidenceCitation("src/Other.kt", null, "Another owner.", "d".repeat(64)),
            scopeCoverage = scope.map { ExecutionPlanScopeCoverage(it, listOf("src/Other.kt"), listOf(1)) },
        )
        assertEquals(
            "Scope coverage 2 cites paths without corresponding source operations: src/Other.kt. " +
                "Linked source operation paths: src/Main.kt.",
            repositoryScopeCoverageDiagnostic(scope, unmatchedEvidence),
        )
        assertNull(
            repositoryScopeCoverageDiagnostic(
                listOf("Inspect the owners."),
                unmatchedEvidence.copy(
                    scopeCoverage = listOf(ExecutionPlanScopeCoverage("Inspect the owners.", listOf("src/Other.kt"), listOf(1))),
                ),
            ),
        )
    }

    @Test
    fun `repository selectors require every matched owner and an affine test operation`() {
        val context = CodingRepositoryContext(
            listOf(
                CodingContextFile("frontend/src/main/Theme.kt", "[excerpt without declaration]", matchedEvidenceSelectorIds = listOf("owners")),
                CodingContextFile("frontend/src/main/Inbox.kt", "[excerpt without declaration]", matchedEvidenceSelectorIds = listOf("owners")),
                CodingContextFile("frontend/src/main/Body.kt", "Text(\"Body\")"),
                CodingContextFile(
                    "backend/src/test/AnalysisTest.kt",
                    "val fixture = \"FontFamily.Serif\"",
                    matchedEvidenceSelectorIds = listOf("tests"),
                ),
                CodingContextFile("frontend/src/test/TypographyTest.kt", "class TypographyTest", matchedEvidenceSelectorIds = listOf("tests")),
            ),
            omittedFileCount = 0,
        )
        val selectors = listOf(
            RepositoryEvidenceSelector(
                selectorId = "owners",
                scopeIndexes = listOf(0),
                pathGlobs = listOf("frontend/src/main/*.kt"),
                contentLiterals = listOf("PlatformTypography"),
            ),
            RepositoryEvidenceSelector(
                selectorId = "tests",
                scopeIndexes = listOf(1),
                pathGlobs = listOf("**/src/test/*.kt"),
                selection = REPOSITORY_EVIDENCE_AFFINE_TEST,
                affinitySelectorId = "owners",
            ),
        )
        val scope = listOf("Inspect typography across all surfaces.", "Add focused regression coverage.")
        val complete = plan(1, 1, "a".repeat(40)).content.copy(
            evidence = listOf(context.files[0], context.files[1], context.files[4]).map {
                RepositoryEvidenceCitation(it.path, null, "Explicit typography owner.", it.contentHash)
            },
            scopeCoverage = listOf(
                ExecutionPlanScopeCoverage(scope[0], context.files.take(2).map { it.path }, listOf(1, 2)),
                ExecutionPlanScopeCoverage(scope[1], listOf("frontend/src/test/TypographyTest.kt"), listOf(3, 4)),
            ),
            operations = listOf(
                ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, context.files[0].path, null, "Use native typography.", listOf("Behavior works.")),
                ExecutionPlanOperation(2, PLAN_OPERATION_MODIFY, context.files[1].path, null, "Review monospace.", listOf("Behavior works.")),
                ExecutionPlanOperation(3, PLAN_OPERATION_MODIFY, "frontend/src/test/TypographyTest.kt", null, "Add coverage.", listOf("Behavior works.")),
                ExecutionPlanOperation(4, PLAN_OPERATION_VERIFY, ".", null, "Verify visually.", listOf("Behavior works.")),
            ),
        )

        assertNull(repositoryScopeCoverageDiagnostic(scope, complete))
        assertEquals(
            listOf(
                "frontend/src/main/Inbox.kt",
                "frontend/src/main/Theme.kt",
                "frontend/src/test/TypographyTest.kt",
            ),
            requiredRepositorySourceOperationPaths(selectors, context),
        )
        assertNull(repositoryUniversalScopeCoverageDiagnostic(scope, selectors, context, complete))
        assertNull(repositoryRequiredScopeSourcePathsDiagnostic(scope, selectors, context, complete))
        assertNull(repositoryEvidenceSelectionDiagnostic(selectors, context))
        assertEquals(
            "Repository evidence selectors matched no required paths: owners, tests.",
            repositoryEvidenceSelectionDiagnostic(
                selectors,
                context.copy(files = context.files.map { it.copy(matchedEvidenceSelectorIds = emptyList()) }),
            ),
        )
        assertEquals(
            "Scope coverage 2 paths differ from deterministic scope authority. " +
                "Expected: frontend/src/test/TypographyTest.kt. Actual: frontend/src/main/Theme.kt.",
            repositoryRequiredScopeSourcePathsDiagnostic(
                scope,
                selectors,
                context,
                complete.copy(scopeCoverage = complete.scopeCoverage.map {
                    if (it.scope == scope[1]) it.copy(evidencePaths = listOf(context.files[0].path)) else it
                }),
            ),
        )
        val misplacedTest = complete.copy(scopeCoverage = complete.scopeCoverage.map {
            if (it.scope == scope[0]) {
                it.copy(evidencePaths = listOf("frontend/src/test/TypographyTest.kt"), operationOrders = listOf(4))
            } else {
                it
            }
        })
        assertNull(repositoryScopeIdentityDiagnostic(scope, misplacedTest))
        assertEquals(
            "Scope coverage 1 paths differ from deterministic scope authority. " +
                "Expected: frontend/src/main/Inbox.kt, frontend/src/main/Theme.kt. " +
                "Actual: frontend/src/test/TypographyTest.kt.",
            repositoryRequiredScopeSourcePathsDiagnostic(scope, selectors, context, misplacedTest),
        )
        val swappedScopePaths = complete.copy(scopeCoverage = complete.scopeCoverage.map {
            when (it.scope) {
                scope[0] -> it.copy(evidencePaths = listOf("frontend/src/test/TypographyTest.kt"))
                scope[1] -> it.copy(evidencePaths = listOf("frontend/src/main/Theme.kt"))
                else -> it
            }
        })
        assertEquals(
            "Scope coverage 1 paths differ from deterministic scope authority. " +
                "Expected: frontend/src/main/Inbox.kt, frontend/src/main/Theme.kt. " +
                "Actual: frontend/src/test/TypographyTest.kt.\n" +
                "Scope coverage 2 paths differ from deterministic scope authority. " +
                "Expected: frontend/src/test/TypographyTest.kt. Actual: frontend/src/main/Theme.kt.",
            repositoryRequiredScopeSourcePathsDiagnostic(scope, selectors, context, swappedScopePaths),
        )
        val compiledScopeAuthority = compileRepositoryScopeAuthority(scope, selectors, context, swappedScopePaths)
        assertEquals(scope, compiledScopeAuthority.scopeCoverage.map { it.scope })
        assertEquals(
            listOf(
                listOf("frontend/src/main/Inbox.kt", "frontend/src/main/Theme.kt"),
                listOf("frontend/src/test/TypographyTest.kt"),
            ),
            compiledScopeAuthority.scopeCoverage.map { it.evidencePaths },
        )
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), compiledScopeAuthority.scopeCoverage.map { it.operationOrders })
        assertNull(repositoryRequiredScopeSourcePathsDiagnostic(scope, selectors, context, compiledScopeAuthority))
        assertNull(repositoryScopeCoverageDiagnostic(scope, compiledScopeAuthority))
        assertEquals(
            "Required source operation paths omit evidence: frontend/src/main/Inbox.kt, frontend/src/test/TypographyTest.kt.",
            repositoryUniversalScopeCoverageDiagnostic(scope, selectors, context, complete.copy(evidence = complete.evidence.take(1))),
        )
        assertEquals(
            "Required source operation paths omit source operations: frontend/src/main/Inbox.kt.",
            repositoryUniversalScopeCoverageDiagnostic(scope, selectors, context, complete.copy(operations = complete.operations.filter { it.order != 2 })),
        )
        val missingPinnedOwner = complete.copy(
            evidence = complete.evidence.take(1),
            operations = complete.operations.filter { it.order != 2 },
        )
        assertEquals(
            "Scope coverage 1 does not cite pinned evidence or a concrete source operation.",
            repositoryScopeCoverageDiagnostic(scope, missingPinnedOwner),
        )
        assertEquals(
            "Required source operation paths omit evidence: frontend/src/main/Inbox.kt, frontend/src/test/TypographyTest.kt.\n" +
                "Required source operation paths omit source operations: frontend/src/main/Inbox.kt.",
            repositoryScopeAuthorityDiagnostic(scope, selectors, context, missingPinnedOwner),
        )
        assertEquals(
            "Scope coverage 2 requires a test source operation.",
            repositoryScopeCoverageDiagnostic(
                scope,
                complete.copy(
                    scopeCoverage = complete.scopeCoverage.map {
                        if (it.scope == scope[1]) it.copy(evidencePaths = listOf(context.files[0].path), operationOrders = listOf(1, 4)) else it
                    },
                ),
            ),
        )
    }

    private fun plan(planId: Long, revision: Int, baseRevision: String): RepositoryExecutionPlan =
        newRepositoryExecutionPlan(
            planId = planId,
            runId = 7,
            revision = revision,
            projectId = 1,
            baseRevision = baseRevision,
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Reuse the existing implementation surface.",
                evidence = listOf(
                    RepositoryEvidenceCitation("src/Main.kt", "main", "The owning implementation exists.", "c".repeat(64))
                ),
                reuse = listOf("main"),
                preservedInvariants = listOf("Preserve the public entrypoint."),
                nonGoals = listOf("Do not create a parallel implementation."),
                operations = listOf(
                    ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "main", "Extend the existing behavior.", listOf("Behavior works."))
                ),
                verificationCommands = listOf("./gradlew test"),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "broad-repository-analysis-v1",
                bindingFingerprint = "d".repeat(64),
                promptHash = "e".repeat(64),
                contextHash = "f".repeat(64),
                outputHash = "1".repeat(64),
                modelExecutionId = planId,
            ),
        )
}
