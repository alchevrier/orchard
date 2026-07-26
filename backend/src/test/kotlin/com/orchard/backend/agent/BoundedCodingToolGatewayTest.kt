package com.orchard.backend.agent

import com.orchard.backend.analysis.EXECUTABLE_WORK_PACKAGE_VERSION
import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_READ_SOURCE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_CREATE_FILE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_REWRITE_FILE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_RUN_CHECK
import com.orchard.backend.analysis.WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION
import com.orchard.backend.analysis.WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY
import com.orchard.backend.analysis.WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED
import com.orchard.backend.analysis.WORK_PACKAGE_ESCALATE_STALE_REVISION
import com.orchard.backend.analysis.WorkPackageCheck
import com.orchard.backend.analysis.WorkPackageDesignAuthority
import com.orchard.backend.analysis.WorkPackageIntentAuthority
import com.orchard.backend.analysis.WorkPackageOwnershipBoundary
import com.orchard.backend.analysis.WorkPackageSource
import com.orchard.backend.analysis.executableWorkPackageHash
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedCodingToolGatewayTest {
    @Test
    fun `literal replacement uses expected cardinality and commits inside package boundary`() {
        val repository = repository()
        val gateway = LocalCodingWorkspaceGateway()
        val revision = requireNotNull(gateway.currentRevision(repository.toString()))
        val packageAuthority = workPackage(revision, Files.readString(repository.resolve("src/Main.kt")))

        val candidate = gateway.applyBoundedToolBatch(
            repository.toString(),
            packageAuthority,
            BoundedCodingToolBatch(
                "Use platform typography.",
                revision,
                listOf(BoundedCodingToolOperation(
                    BOUNDED_TOOL_REPLACE_LITERAL,
                    "src/Main.kt",
                    expectedLiteral = "FontFamily.Serif",
                    replacement = "FontFamily.Default",
                    expectedCount = 2,
                )),
            ),
            executionId = 1,
        )

        assertEquals(listOf("src/Main.kt"), candidate.changedPaths)
        assertEquals(0, Files.readString(repository.resolve("src/Main.kt")).windowed("FontFamily.Serif".length).count { it == "FontFamily.Serif" })
        assertTrue(Files.readString(repository.resolve("src/Main.kt")).contains("FontFamily.Default"))
        assertEquals("", gitOutput(repository, "status", "--porcelain"))
    }

    @Test
    fun `batch rejects stale unauthorized and wrong-cardinality operations before mutation`() {
        val repository = repository()
        val gateway = LocalCodingWorkspaceGateway()
        val revision = requireNotNull(gateway.currentRevision(repository.toString()))
        val original = Files.readString(repository.resolve("src/Main.kt"))
        val packageAuthority = workPackage(revision, original)

        val cardinality = assertFailsWith<IllegalArgumentException> {
            gateway.applyBoundedToolBatch(
                repository.toString(), packageAuthority,
                BoundedCodingToolBatch("Wrong count.", revision, listOf(BoundedCodingToolOperation(
                    BOUNDED_TOOL_REPLACE_LITERAL, "src/Main.kt", expectedLiteral = "FontFamily.Serif",
                    replacement = "FontFamily.Default", expectedCount = 1,
                ))), 2,
            )
        }
        assertEquals("REPLACE_LITERAL src/Main.kt found 2 occurrences; expected 1", cardinality.message)
        val unauthorized = assertFailsWith<IllegalArgumentException> {
            gateway.applyBoundedToolBatch(
                repository.toString(), packageAuthority,
                BoundedCodingToolBatch("Escape scope.", revision, listOf(BoundedCodingToolOperation(
                    BOUNDED_TOOL_REWRITE_FILE, "src/Other.kt", content = "fun other() = 2\n",
                ))), 3,
            )
        }
        assertTrue(unauthorized.message.orEmpty().contains("outside the work-package ownership boundary"))
        val stale = assertFailsWith<IllegalArgumentException> {
            gateway.readAuthorizedSource(repository.toString(), packageAuthority, "f".repeat(40), "src/Main.kt")
        }
        assertEquals("Coding workspace revision is stale", stale.message)
        assertEquals(original, Files.readString(repository.resolve("src/Main.kt")))
        assertEquals(revision, gateway.currentRevision(repository.toString()))
    }

    @Test
    fun `authorized source reads and named checks use package authority`() {
        val repository = repository()
        Files.writeString(repository.resolve("verify.sh"), "#!/bin/sh\nprintf 'bounded check passed\\n'\n")
        repository.resolve("verify.sh").toFile().setExecutable(true)
        git(repository, "add", "verify.sh")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Add check")
        val gateway = LocalCodingWorkspaceGateway()
        val revision = requireNotNull(gateway.currentRevision(repository.toString()))
        val packageAuthority = workPackage(revision, Files.readString(repository.resolve("src/Main.kt")), "./verify.sh")

        assertTrue(gateway.readAuthorizedSource(repository.toString(), packageAuthority, revision, "src/Main.kt").content.contains("Serif"))
        val observation = gateway.runNamedCheck(repository.toString(), packageAuthority, revision, "check-1")
        assertEquals(0, observation.exitCode)
        assertTrue(observation.summary.contains("bounded check passed"))
        assertFailsWith<IllegalArgumentException> {
            gateway.runNamedCheck(repository.toString(), packageAuthority, revision, "invented-check")
        }
    }

    @Test
    fun `create tool writes only an authorized absent path`() {
        val repository = repository()
        val gateway = LocalCodingWorkspaceGateway()
        val revision = requireNotNull(gateway.currentRevision(repository.toString()))
        val base = workPackage(revision, Files.readString(repository.resolve("src/Main.kt")))
        val draft = base.copy(
            ownership = base.ownership.copy(
                paths = base.ownership.paths + "src/New.kt",
                likelyImplementationPaths = base.ownership.likelyImplementationPaths + "src/New.kt",
                createPaths = listOf("src/New.kt"),
                allowedActions = (base.ownership.allowedActions + WORK_PACKAGE_ACTION_CREATE_FILE).sorted(),
            ),
            hash = "",
        )
        val packageAuthority = draft.copy(hash = executableWorkPackageHash(draft))

        gateway.applyBoundedToolBatch(
            repository.toString(), packageAuthority,
            BoundedCodingToolBatch("Create the admitted file.", revision, listOf(BoundedCodingToolOperation(
                BOUNDED_TOOL_CREATE_FILE, "src/New.kt", content = "fun created() = true\n",
            ))), 4,
        )

        assertEquals("fun created() = true\n", Files.readString(repository.resolve("src/New.kt")))
        val candidateRevision = requireNotNull(gateway.currentRevision(repository.toString()))
        assertFailsWith<IllegalArgumentException> {
            gateway.applyBoundedToolBatch(
                repository.toString(), packageAuthority,
                BoundedCodingToolBatch("Do not overwrite.", candidateRevision, listOf(BoundedCodingToolOperation(
                    BOUNDED_TOOL_CREATE_FILE, "src/New.kt", content = "fun created() = false\n",
                ))), 5,
            )
        }
    }

    @Test
    fun `typography replay repairs an injected check failure on the same candidate lineage`() {
        val repository = createTempDirectory("orchard-typography-replay-")
        git(repository, "init")
        Files.createDirectories(repository.resolve("frontend/src/main"))
        Files.createDirectories(repository.resolve("frontend/src/test"))
        val productionPath = "frontend/src/main/GuidedGenesisWorkspace.kt"
        val testPath = "frontend/src/test/ProjectInboxWorkspaceTest.kt"
        val production = (1..6).joinToString("\n", postfix = "\n") { "val family$it = FontFamily.Serif" }
        val testSource = "fun typographyPolicy() = check(true)\n"
        Files.writeString(repository.resolve(productionPath), production)
        Files.writeString(repository.resolve(testPath), testSource)
        Files.writeString(
            repository.resolve("verify.sh"),
            "#!/bin/sh\n! grep -q 'FontFamily.Serif' '$productionPath' && ! grep -q 'undefinedTypographyPolicy' '$testPath'\n",
        )
        repository.resolve("verify.sh").toFile().setExecutable(true)
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val gateway = LocalCodingWorkspaceGateway()
        val baseRevision = requireNotNull(gateway.currentRevision(repository.toString()))
        val base = workPackage(baseRevision, production, "./verify.sh")
        val packageDraft = base.copy(
            ownership = base.ownership.copy(
                paths = listOf(productionPath, testPath),
                likelyImplementationPaths = listOf(productionPath, testPath),
            ),
            sources = listOf(
                WorkPackageSource(productionPath, production, stagedPlanHash(production)),
                WorkPackageSource(testPath, testSource, stagedPlanHash(testSource)),
            ),
            hash = "",
        )
        val packageAuthority = packageDraft.copy(hash = executableWorkPackageHash(packageDraft))

        val failedCandidate = gateway.applyBoundedToolBatch(
            repository.toString(), packageAuthority,
            BoundedCodingToolBatch("Remove serif and add regression coverage.", baseRevision, listOf(
                BoundedCodingToolOperation(
                    BOUNDED_TOOL_REPLACE_LITERAL,
                    productionPath,
                    expectedLiteral = "FontFamily.Serif",
                    replacement = "FontFamily.Default",
                    expectedCount = 6,
                ),
                BoundedCodingToolOperation(
                    BOUNDED_TOOL_REWRITE_FILE,
                    testPath,
                    content = "fun typographyPolicy() = undefinedTypographyPolicy()\n",
                ),
            )), 20,
        )
        assertEquals(1, gateway.runNamedCheck(repository.toString(), packageAuthority, failedCandidate.revision, "check-1").exitCode)

        val repairedCandidate = gateway.applyBoundedToolBatch(
            repository.toString(), packageAuthority,
            BoundedCodingToolBatch("Repair the regression test.", failedCandidate.revision, listOf(
                BoundedCodingToolOperation(
                    BOUNDED_TOOL_REWRITE_FILE,
                    testPath,
                    content = "fun typographyPolicy() = check(java.io.File(\"$productionPath\").readText().contains(\"FontFamily.Default\"))\n",
                ),
            )), 21,
        )

        assertEquals(0, gateway.runNamedCheck(repository.toString(), packageAuthority, repairedCandidate.revision, "check-1").exitCode)
        assertEquals(failedCandidate.revision, gitOutput(repository, "rev-parse", "${repairedCandidate.revision}^"))
        assertEquals(listOf(productionPath, testPath), failedCandidate.changedPaths)
        assertEquals(listOf(testPath), repairedCandidate.changedPaths)
        assertEquals(0, Files.readString(repository.resolve(productionPath)).windowed("FontFamily.Serif".length).count { it == "FontFamily.Serif" })
    }

    private fun repository() = createTempDirectory("orchard-bounded-tools-").also { repository ->
        git(repository, "init")
        Files.createDirectories(repository.resolve("src"))
        Files.writeString(
            repository.resolve("src/Main.kt"),
            "val heading = FontFamily.Serif\nval body = FontFamily.Serif\n",
        )
        Files.writeString(repository.resolve("src/Other.kt"), "fun other() = 1\n")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
    }

    private fun workPackage(revision: String, source: String, check: String = "./gradlew test"): ExecutableWorkPackage {
        val draft = ExecutableWorkPackage(
            formatVersion = EXECUTABLE_WORK_PACKAGE_VERSION,
            packageId = 1,
            revision = 1,
            projectId = 1,
            runId = 1,
            repositoryRevision = revision,
            intent = WorkPackageIntentAuthority(1, 1, "a".repeat(64), "Use native typography.", "Serif is explicit.", "Use platform typography.", emptyList(), listOf("No serif remains.")),
            design = WorkPackageDesignAuthority(1, 1, "b".repeat(64), "Update the existing owner.", emptyList(), emptyList()),
            ownership = WorkPackageOwnershipBoundary(
                listOf("src/Main.kt"), listOf("src/Main.kt"), emptyList(),
                listOf(WORK_PACKAGE_ACTION_READ_SOURCE, WORK_PACKAGE_ACTION_REWRITE_FILE, WORK_PACKAGE_ACTION_RUN_CHECK),
            ),
            expectedBehavior = listOf("No serif remains."),
            unresolvedAuthorityQuestions = emptyList(),
            sources = listOf(WorkPackageSource("src/Main.kt", source, stagedPlanHash(source))),
            checks = listOf(WorkPackageCheck("check-1", check)),
            escalationConditions = listOf(
                WORK_PACKAGE_ESCALATE_STALE_REVISION,
                WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED,
                WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY,
                WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION,
            ),
        )
        return draft.copy(hash = executableWorkPackageHash(draft))
    }

    private fun git(repository: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
    }

    private fun gitOutput(repository: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}