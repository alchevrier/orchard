package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CandidatePullRequestCorrection
import com.orchard.backend.agent.CandidatePullRequestReviewFinding
import com.orchard.backend.agent.CandidatePullRequestWorkPackageRecompileService
import com.orchard.backend.agent.CandidatePullRequestCorrectionDispatchService
import com.orchard.backend.agent.PackageStoreWorkPackageCorrectionRecompileGateway
import com.orchard.backend.agent.REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE
import com.orchard.backend.agent.TransientCandidatePullRequestCorrectionDispatchStore
import com.orchard.backend.agent.TransientCandidatePullRequestCorrectionStore
import com.orchard.backend.agent.candidatePullRequestCorrectionHash
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFINITION_READY
import com.orchard.backend.workspace.DefinitionAssessment
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.SystemWorkflow
import com.orchard.backend.workspace.WorkDefinitionManifest
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutableWorkPackageTest {
    @Test
    fun `compiler pins intent design ownership source and checks`() {
        val source = "fun answer() = 1\n"
        val packageAuthority = workPackage(source)

        assertEquals(EXECUTABLE_WORK_PACKAGE_VERSION, packageAuthority.formatVersion)
        assertEquals(1, packageAuthority.packageId)
        assertEquals(1, packageAuthority.revision)
        assertEquals("a".repeat(40), packageAuthority.repositoryRevision)
        assertEquals(listOf("src/Main.kt"), packageAuthority.ownership.paths)
        assertEquals(listOf("src/Main.kt"), packageAuthority.ownership.likelyImplementationPaths)
        assertEquals(emptyList(), packageAuthority.ownership.createPaths)
        assertEquals(listOf("src/Main.kt"), packageAuthority.ownership.requiredImplementationPaths)
        assertEquals(emptyList(), packageAuthority.ownership.compliantEvidencePaths)
        assertEquals(
            listOf(WORK_PACKAGE_ACTION_READ_SOURCE, WORK_PACKAGE_ACTION_REWRITE_FILE, WORK_PACKAGE_ACTION_RUN_CHECK),
            packageAuthority.ownership.allowedActions,
        )
        assertEquals(listOf("src/Main.kt"), packageAuthority.evidence.citations.map { it.path })
        assertFalse(packageAuthority.evidence.citations.single().compliantReadOnly)
        assertEquals(listOf("src/Main.kt"), packageAuthority.operations.operations.map { it.path })
        assertEquals(source, packageAuthority.sources.single().content)
        assertEquals(listOf("./gradlew test"), packageAuthority.checks.map { it.command })
        assertEquals(executableWorkPackageHash(packageAuthority), packageAuthority.hash)
        assertTrue(verifyExecutableWorkPackage(packageAuthority).adequate)
    }

    @Test
    fun `adequacy rejects missing source stale bytes unverifiable work and hidden contradictions`() {
        val valid = workPackage("fun answer() = 1\n")
        val invalid = valid.copy(
            intent = valid.intent.copy(constraints = listOf("Preserve the public API.")),
            design = valid.design.copy(nonGoals = listOf("Preserve the public API.")),
            sources = valid.sources.map { it.copy(content = "fun answer() = 2\n") },
            checks = emptyList(),
            hash = "",
        ).let { it.copy(hash = executableWorkPackageHash(it)) }

        val report = verifyExecutableWorkPackage(invalid)

        assertFalse(report.adequate)
        assertTrue(report.diagnostics.contains("Source src/Main.kt does not match its pinned content hash."))
        assertTrue(report.diagnostics.contains("Package has no executable verification strategy."))
        assertTrue(report.diagnostics.contains("Constraints contradict admitted non-goals: Preserve the public API.."))

        val missingSource = valid.copy(sources = emptyList(), hash = "").let { it.copy(hash = executableWorkPackageHash(it)) }
        assertEquals(
            "Ownership boundary lacks pinned source: src/Main.kt.",
            verifyExecutableWorkPackage(missingSource).diagnostics.single(),
        )

        val unresolved = valid.copy(
            unresolvedAuthorityQuestions = listOf("Choose between two storage architectures."),
            hash = "",
        ).let { it.copy(hash = executableWorkPackageHash(it)) }
        assertEquals(
            "Package leaves design authority unresolved: Choose between two storage architectures..",
            verifyExecutableWorkPackage(unresolved).diagnostics.single(),
        )
    }

    @Test
    fun `adequacy keeps evidence ownership and operation authority distinct`() {
        val valid = workPackage("fun answer() = 1\n")
        val invalid = valid.copy(
            ownership = valid.ownership.copy(requiredImplementationPaths = emptyList()),
            hash = "",
        ).let { it.copy(hash = executableWorkPackageHash(it)) }

        assertEquals(
            "Required implementation ownership does not match operation authority.",
            verifyExecutableWorkPackage(invalid).diagnostics.single(),
        )
    }

    @Test
    fun `compiler preserves compliant read only evidence without requiring a source operation`() {
        val packageAuthority = workPackage("fun answer() = 1\n", withCompliantEvidence = true)

        assertEquals(listOf("docs/Contract.md"), packageAuthority.ownership.compliantEvidencePaths)
        assertEquals(listOf("src/Main.kt"), packageAuthority.ownership.requiredImplementationPaths)
        assertEquals(
            listOf("docs/Contract.md"),
            packageAuthority.evidence.citations.filter { it.compliantReadOnly }.map { it.path },
        )
        assertEquals(listOf("src/Main.kt"), packageAuthority.operations.operations.map { it.path })
        assertTrue(verifyExecutableWorkPackage(packageAuthority).adequate)
    }

    @Test
    fun `compiler admits one source operation per reviewable package`() {
        val original = plan()
        val plan = original.copy(content = original.content.copy(operations = original.content.operations +
            ExecutionPlanOperation(
                2,
                PLAN_OPERATION_CREATE,
                "src/Next.kt",
                null,
                "Add the next independently verifiable step.",
                listOf("The next step is available."),
            )))

        val packageAuthority = compileExecutableWorkPackage(
            packageId = 1,
            revision = 1,
            definition = definition(),
            plan = plan,
            repositoryContext = context("fun answer() = 1\n"),
        )

        assertEquals(listOf("src/Main.kt"), packageAuthority.ownership.paths)
        assertEquals(listOf("src/Main.kt"), packageAuthority.operations.operations.map { it.path })
        assertEquals(
            listOf(WORK_PACKAGE_ACTION_READ_SOURCE, WORK_PACKAGE_ACTION_REWRITE_FILE, WORK_PACKAGE_ACTION_RUN_CHECK),
            packageAuthority.ownership.allowedActions,
        )
        assertTrue(verifyExecutableWorkPackage(packageAuthority).adequate)
    }

    @Test
    fun `recompiler pins review correction without widening package authority`() {
        val prior = workPackage("fun answer() = 1\n")
        val correctionDraft = CandidatePullRequestCorrection(
            correctionId = 8,
            reviewId = 9,
            reviewHash = "b".repeat(64),
            pullRequestId = 4,
            pullRequestHash = "c".repeat(64),
            runId = prior.runId,
            workPackageId = prior.packageId,
            workPackageHash = prior.hash,
            correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "The answer is forty two.",
                    observation = "Preserve the existing API while returning forty two.",
                    severity = "BLOCKER",
                    correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        )
        val correction = correctionDraft.copy(hash = candidatePullRequestCorrectionHash(correctionDraft))

        val successor = recompileExecutableWorkPackage(2, 2, prior, correction)

        assertEquals(prior.ownership, successor.ownership)
        assertEquals(prior.operations, successor.operations)
        assertEquals(listOf(correction.correctionId), successor.corrections.map { it.correctionId })
        assertTrue(successor.expectedBehavior.contains(correction.findings.single().observation))
        assertTrue(verifyExecutableWorkPackage(successor).adequate)
    }

    @Test
    fun `compile correction selects only named test paths`() {
        val base = workPackage("fun answer() = 1\n")
        val prior = base.copy(
            ownership = base.ownership.copy(
                paths = listOf("frontend/src/desktopTest/kotlin/ExampleTest.kt", "frontend/src/desktopMain/kotlin/Example.kt"),
            ),
            hash = "",
        ).let { it.copy(hash = executableWorkPackageHash(it)) }
        val correction = CandidatePullRequestCorrection(
            correctionId = 8,
            reviewId = 9,
            reviewHash = "b".repeat(64),
            pullRequestId = 4,
            pullRequestHash = "c".repeat(64),
            runId = prior.runId,
            workPackageId = prior.packageId,
            workPackageHash = prior.hash,
            correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "Verification TEST failed: frontend compilation failed.",
                    observation = "frontend/src/desktopTest/kotlin/ExampleTest.kt:12: Unresolved reference 'answer'.",
                    severity = "BLOCKER",
                    correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        ).let { it.copy(hash = candidatePullRequestCorrectionHash(it)) }

        assertEquals(
            listOf("frontend/src/desktopTest/kotlin/ExampleTest.kt"),
            compileFailureCorrectionPaths(prior, correction),
        )
    }

    @Test
    fun `work package correction dispatch creates one successor from pinned authority`() {
        val packages = TransientExecutableWorkPackageStore()
        val prior = packages.appendNext(7) { packageId, revision -> workPackage("fun answer() = 1\n", packageId, revision) }
        val correctionDraft = CandidatePullRequestCorrection(
            correctionId = 1,
            reviewId = 9,
            reviewHash = "b".repeat(64),
            pullRequestId = 4,
            pullRequestHash = "c".repeat(64),
            runId = prior.runId,
            workPackageId = prior.packageId,
            workPackageHash = prior.hash,
            correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
            findings = listOf(
                CandidatePullRequestReviewFinding(
                    criterion = "The answer is forty two.",
                    observation = "Keep the public API while correcting the implementation.",
                    severity = "BLOCKER",
                    correctionTarget = REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE,
                    evidenceHashes = listOf("d".repeat(64)),
                ),
            ),
            hash = "",
        )
        val correction = correctionDraft.copy(hash = candidatePullRequestCorrectionHash(correctionDraft))
        val corrections = TransientCandidatePullRequestCorrectionStore().also { store ->
            store.appendNext { correction }
        }
        val dispatches = TransientCandidatePullRequestCorrectionDispatchStore()
        val dispatcher = CandidatePullRequestWorkPackageRecompileService(
            corrections,
            dispatches,
            PackageStoreWorkPackageCorrectionRecompileGateway(packages),
        )

        val dispatch = dispatcher.tick()

        assertEquals(1, dispatch?.correctionId)
        assertEquals(2, packages.load().size)
        val successor = packages.load().last()
        assertEquals(prior.ownership, successor.ownership)
        assertEquals(prior.operations, successor.operations)
        assertEquals(listOf(correction.correctionId), successor.corrections.map { it.correctionId })
        assertEquals(1, dispatches.load().size)
    }

    @Test
    fun `file store replays adequate monotonic package revisions and rejects invalid successors`() {
        val directory = createTempDirectory("orchard-work-packages-")
        val store = FileExecutableWorkPackageStore(directory)
        val first = store.appendNext(7) { packageId, revision ->
            workPackage("fun answer() = 1\n", packageId, revision)
        }
        val second = store.appendNext(7) { packageId, revision ->
            workPackage("fun answer() = 1\n", packageId, revision)
        }

        assertEquals(listOf(first, second), FileExecutableWorkPackageStore(directory).load())
        assertEquals(listOf(1, 2), listOf(first.revision, second.revision))
        assertTrue(Files.size(directory.resolve("executable-work-packages.jsonl")) > 0)

        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            store.appendNext(7) { packageId, revision ->
                workPackage("fun answer() = 1\n", packageId, revision).copy(checks = emptyList()).rehash()
            }
        }
        assertTrue(error.message.orEmpty().contains("Package has no executable verification strategy."))
        assertEquals(2, store.load().size)
    }

    private fun workPackage(
        source: String,
        packageId: Long = 1,
        revision: Int = 1,
        withCompliantEvidence: Boolean = false,
    ) = compileExecutableWorkPackage(
        packageId = packageId,
        revision = revision,
        definition = definition(),
        plan = plan(withCompliantEvidence),
        repositoryContext = context(source, withCompliantEvidence),
    )

    private fun ExecutableWorkPackage.rehash() = copy(hash = "").let { it.copy(hash = executableWorkPackageHash(it)) }

    private fun definition() = WorkDefinitionManifest(
        definitionId = 5,
        revision = 2,
        workItemId = 4,
        createdAt = "2026-07-26T00:00:00Z",
        systemWorkflow = SystemWorkflow("task-definition", 1, ENTITY_TASK, emptyList()),
        definition = WorkDefinitionSubmission(
            requestedOutcome = "Return the admitted answer.",
            currentBehavior = "The function returns one.",
            requiredBehavior = "The function returns forty two.",
            scope = listOf("src/Main.kt"),
            nonGoals = listOf("Do not change build tooling."),
            constraints = listOf("Preserve the public API."),
            acceptanceCriteria = listOf(AcceptanceCriterion("The answer is forty two.", "./gradlew test")),
        ),
        assessment = DefinitionAssessment(DEFINITION_READY, emptyList()),
        hash = "b".repeat(64),
    )

    private fun plan(withCompliantEvidence: Boolean = false) = newRepositoryExecutionPlan(
        planId = 3,
        runId = 7,
        revision = 1,
        projectId = 1,
        baseRevision = "a".repeat(40),
        content = RepositoryAnalysisPlanContent(
            disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
            summary = "Modify the existing function in place.",
            evidence = if (withCompliantEvidence) {
                listOf(RepositoryEvidenceCitation("docs/Contract.md", null, "The contract already specifies the outcome.", stagedPlanHash("The answer must be forty two.\n")))
            } else {
                listOf(RepositoryEvidenceCitation("src/Main.kt", "answer", "The function owns the behavior.", stagedPlanHash("fun answer() = 1\n")))
            },
            reuse = listOf("answer"),
            preservedInvariants = listOf("Preserve the function signature."),
            nonGoals = emptyList(),
            coveredScope = listOf("src/Main.kt"),
            scopeCoverage = if (withCompliantEvidence) {
                listOf(ExecutionPlanScopeCoverage("src/Main.kt", listOf("docs/Contract.md"), emptyList(), listOf("docs/Contract.md")))
            } else {
                listOf(ExecutionPlanScopeCoverage("src/Main.kt", listOf("src/Main.kt"), listOf(1)))
            },
            operations = listOf(ExecutionPlanOperation(1, PLAN_OPERATION_MODIFY, "src/Main.kt", "answer", "Return forty two.", listOf("The answer is forty two."))),
            verificationCommands = listOf("./gradlew test"),
        ),
        provenance = AnalysisExecutionProvenance(
            executionProfileId = "test-analysis",
            bindingFingerprint = "c".repeat(64),
            promptHash = "d".repeat(64),
            contextHash = "e".repeat(64),
            outputHash = "f".repeat(64),
            modelExecutionId = 9,
        ),
    )

    private fun context(source: String, withCompliantEvidence: Boolean = false) = CodingRepositoryContext(
        files = buildList {
            add(CodingContextFile("src/Main.kt", source, stagedPlanHash(source), listOf("fun answer()")))
            if (withCompliantEvidence) {
                add(CodingContextFile("docs/Contract.md", "The answer must be forty two.\n", stagedPlanHash("The answer must be forty two.\n")))
            }
        },
        omittedFileCount = 0,
    )
}