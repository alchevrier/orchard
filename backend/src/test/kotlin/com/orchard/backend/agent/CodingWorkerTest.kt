package com.orchard.backend.agent

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileCircuitDispatchStore
import com.orchard.backend.workspace.FileModelExperienceStore
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileStagedDeliveryPlanStore
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanNodeSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.stagedPlanHash
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.RepositoryEvidenceSelector
import com.orchard.backend.workspace.REPOSITORY_EVIDENCE_AFFINE_TEST
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CodingWorkerTest {
    @Test
    fun `governed worker commits and completes only through workflow evidence`() = runTest {
        val directory = createTempDirectory("orchard-coding-worker-e2e-")
        val repository = initializedRepository()
        val gradle = repository.resolve("gradlew")
        Files.writeString(gradle, "#!/bin/sh\nprintf 'verified %s\\n' \"$1\"\n")
        gradle.toFile().setExecutable(true)
        Files.writeString(repository.resolve("settings.gradle.kts"), "rootProject.name = \"worker-test\"\n")
        Files.writeString(repository.resolve("src/Main.kt"), "// " + "context".repeat(4_000) + "\nfun answer() = 1\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add verifier")
        val workspace = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            repositoryBindings = FileRepositoryBindingStore(directory),
            workflowMemory = FileWorkflowMemoryStore(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            modelExperienceStore = FileModelExperienceStore(directory),
            stagedPlanStore = FileStagedDeliveryPlanStore(directory),
            circuitDispatchStore = FileCircuitDispatchStore(directory),
        )
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Implement answer", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
        workspace.bindRepository(1, repository.toString())
        workspace.submitWorkDefinition(
            4,
            WorkDefinitionSubmission(
                requestedOutcome = "Return the required answer",
                currentBehavior = "The application returns one",
                requiredBehavior = "The application returns forty two",
                scope = listOf("src/Main.kt"),
                nonGoals = listOf("Changing build tooling"),
                constraints = listOf("Keep the function signature"),
                acceptanceCriteria = listOf(AcceptanceCriterion("The answer is forty two", "Run ./gradlew test")),
            ),
        )
        workspace.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Autonomous delivery",
                listOf(
                    StagedPlanStageSubmission(
                        "delivery",
                        "Delivery",
                        "sequential-delivery-v1",
                        nodes = listOf(StagedPlanNodeSubmission("task", 4)),
                    )
                ),
            )
        )
        val proposal = CodingPatchProposal(
            "Return the required answer.",
            listOf(CodingFileOperation(CODING_FILE_WRITE, "src/Main.kt", "fun answer() = 42\n")),
        )
        val model = FixedCodingModel(Json.encodeToString(proposal))
        val profileSettings = TransientModelProfileSettingsStore().apply {
            save(listOf(ModelProfileOverride(
                profileId = "bounded-coding-patch-v1",
                inputBudgetTokens = 80_000,
                outputBudgetTokens = 8_000,
                preferredBindingId = "test:coding-model",
            )))
        }
        val worker = CodingWorkerService(
            workspace,
            listOf(model),
            TransientCodingWorkerStore(),
            LocalCodingWorkspaceGateway(),
            profileSettingsStore = profileSettings,
        )

        val result = worker.tick()
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()

        assertEquals(CodingWorkerTickStatus.CANDIDATE_COMPLETED, result.status)
        assertEquals(CODING_EXECUTION_COMPLETED, requireNotNull(result.execution?.result).status)
        assertEquals("orchard.default-toolchains", result.execution?.claim?.toolchainPackId)
        assertEquals("gradle-wrapper", result.execution?.claim?.toolchainProfileId)
        assertTrue(requireNotNull(result.execution?.claim?.toolchainPolicyHash).matches(Regex("[0-9a-f]{64}")))
        assertEquals(RUN_STATE_DONE, run.state)
        assertEquals(setOf("SOURCE_DIFF", "BUILD", "TEST", "ACCEPTANCE"), run.evidence.mapTo(hashSetOf()) { it.kind })
        assertTrue(run.evidence.all { it.passed })
        assertEquals("fun answer() = 42\n", Files.readString(Path.of(run.context.repository.path).resolve("src/Main.kt")))
        assertEquals(8_000, model.maxOutputTokens)
        assertEquals(88_000, model.contextWindowTokens)
    }

    @Test
    fun `worker journal allows independent active runs and rejects a second claim for one run`() {
        val directory = createTempDirectory("orchard-coding-worker-store-")
        val store = FileCodingWorkerStore(directory)
        val claim = claim(executionId = 1, runId = 17, attempt = 1)
        store.append(CodingWorkerEvent(eventId = 1, claim = claim))

        val competing = claim(executionId = 2, runId = 18, attempt = 1)
        store.append(CodingWorkerEvent(eventId = 2, claim = competing))

        val duplicateRun = claim(executionId = 3, runId = 17, attempt = 2)
        assertFailsWith<IllegalArgumentException> {
            store.append(CodingWorkerEvent(eventId = 3, claim = duplicateRun))
        }

        val resultDraft = CodingWorkerResult(
            executionId = 1,
            status = CODING_EXECUTION_COMPLETED,
            modelExecutionId = 4,
            proposalHash = "c".repeat(64),
            changedPaths = listOf("src/Main.kt"),
            revision = "d".repeat(40),
            diagnostic = "Candidate committed.",
            completedAt = "2026-06-21T00:01:00Z",
            hash = "",
        )
        val result = resultDraft.copy(hash = codingWorkerResultHash(resultDraft))
        store.append(CodingWorkerEvent(eventId = 3, result = result))

        val restored = codingWorkerExecutions(FileCodingWorkerStore(directory).loadEvents())
        assertEquals(2, restored.size)
        assertEquals(result, restored.single { it.claim.runId == 17L }.result)
        assertEquals(null, restored.single { it.claim.runId == 18L }.result)
    }

    @Test
    fun `worker journal preserves deferred retry timing without breaking attempt sequence`() {
        val store = TransientCodingWorkerStore()
        val firstClaim = claim(executionId = 1, runId = 21, attempt = 1)
        store.append(CodingWorkerEvent(eventId = 1, claim = firstClaim))
        val deferredDraft = CodingWorkerResult(
            executionId = 1,
            status = CODING_EXECUTION_DEFERRED,
            diagnostic = "Capacity is temporarily unavailable.",
            retryAfter = "2026-06-21T00:01:00Z",
            completedAt = "2026-06-21T00:00:00Z",
            hash = "",
        )
        store.append(
            CodingWorkerEvent(
                eventId = 2,
                result = deferredDraft.copy(hash = codingWorkerResultHash(deferredDraft)),
            )
        )

        val secondClaim = claim(executionId = 3, runId = 21, attempt = 2)
        store.append(CodingWorkerEvent(eventId = 3, claim = secondClaim))

        assertEquals(2, codingWorkerExecutions(store.loadEvents()).size)
        assertEquals(CODING_EXECUTION_DEFERRED, codingWorkerExecutions(store.loadEvents()).first().result?.status)
    }

    @Test
    fun `worker journal replays completed claims written before toolchain policy pinning`() {
        val store = TransientCodingWorkerStore()
        val draft = CodingWorkerClaim(
            executionId = 1,
            runId = 30,
            attempt = 1,
            contextHash = "a".repeat(64),
            workspacePath = "/tmp/legacy-worktree",
            bindingFingerprint = "b".repeat(64),
            claimedAt = "2026-06-20T00:00:00Z",
            hash = "",
        )
        val legacyHash = stagedPlanHash(
            "${draft.executionId}:${draft.runId}:${draft.attempt}:${draft.contextHash}:${draft.workspacePath}:" +
                "${draft.bindingFingerprint}:${draft.claimedAt}"
        )
        store.append(CodingWorkerEvent(eventId = 1, claim = draft.copy(hash = legacyHash)))
        val resultDraft = CodingWorkerResult(
            executionId = 1,
            status = CODING_EXECUTION_COMPLETED,
            modelExecutionId = 2,
            proposalHash = "c".repeat(64),
            changedPaths = listOf("src/Main.kt"),
            revision = "d".repeat(40),
            diagnostic = "Legacy candidate completed.",
            completedAt = "2026-06-20T00:01:00Z",
            hash = "",
        )

        store.append(CodingWorkerEvent(eventId = 2, result = resultDraft.copy(hash = codingWorkerResultHash(resultDraft))))

        assertEquals(CODING_EXECUTION_COMPLETED, codingWorkerExecutions(store.loadEvents()).single().result?.status)
    }

    @Test
    fun `workspace gateway commits typed operations inside reserved worktree`() {
        val repository = initializedRepository()
        val gateway = LocalCodingWorkspaceGateway()

        val candidate = gateway.applyAndCommit(
            repository.toString(),
            CodingPatchProposal(
                summary = "Update the application and add its test.",
                operations = listOf(
                    CodingFileOperation(CODING_FILE_WRITE, "src/Main.kt", "fun answer() = 42\n"),
                    CodingFileOperation(CODING_FILE_WRITE, "src/MainTest.kt", "fun expected() = 42\n"),
                ),
            ),
            executionId = 9,
        )

        assertEquals(listOf("src/Main.kt", "src/MainTest.kt"), candidate.changedPaths)
        assertTrue(candidate.revision.matches(Regex("[0-9a-f]{40}")))
        assertNotEquals(run(repository, "git", "rev-parse", "HEAD~1"), candidate.revision)
        assertEquals("fun answer() = 42\n", Files.readString(repository.resolve("src/Main.kt")))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway applies bounded exact replacements to a large file`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        Files.writeString(source, "// " + "context".repeat(10_000) + "\nfun answer() = 1\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add large source")

        val candidate = LocalCodingWorkspaceGateway().applyAndCommit(
            repository.toString(),
            CodingPatchProposal(
                summary = "Update the bounded implementation.",
                operations = listOf(CodingFileOperation(
                    action = CODING_FILE_REPLACE,
                    path = "src/Main.kt",
                    replacements = listOf(CodingTextReplacement("fun answer() = 1", "fun answer() = 42")),
                )),
            ),
            executionId = 10,
        )

        assertEquals(listOf("src/Main.kt"), candidate.changedPaths)
        assertTrue(Files.readString(source).endsWith("fun answer() = 42\n"))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway excerpts query matches from oversized source files`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        val secondary = repository.resolve("src/Secondary.kt")
        val content = buildString {
            repeat(2_000) { appendLine("val filler$it = $it") }
            appendLine("val heading = FontFamily.Serif")
            repeat(2_000) { appendLine("val moreFiller$it = $it") }
            appendLine("val telemetry = FontFamily.Monospace")
            repeat(2_000) { appendLine("val finalFiller$it = $it") }
        }
        Files.writeString(source, content)
        Files.writeString(secondary, content.replace("FontFamily.Serif", "Typography.Default"))
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add oversized source")

        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(
            repository.toString(),
            "Remove serif and review monospace typography.",
            listOf(RepositoryEvidenceSelector(
                selectorId = "font-owners",
                scopeIndexes = listOf(0),
                pathGlobs = listOf("src/*.kt"),
                contentLiterals = listOf("FontFamily."),
            )),
        )
        val excerpt = context.files.single { it.path == "src/Main.kt" }.content
        val secondaryExcerpt = context.files.single { it.path == "src/Secondary.kt" }.content

        assertTrue(excerpt.encodeToByteArray().size < content.encodeToByteArray().size)
        assertTrue(secondaryExcerpt.encodeToByteArray().size < content.encodeToByteArray().size)
        assertTrue(excerpt.contains("[Orchard excerpt lines"))
        assertTrue(excerpt.contains("FontFamily.Serif"))
        assertTrue(excerpt.contains("FontFamily.Monospace"))
        assertTrue(secondaryExcerpt.contains("FontFamily.Monospace"))
        assertTrue(context.files.single { it.path == "src/Main.kt" }.matchedDeclarations.any { "FontFamily.Serif" in it })
        assertEquals(listOf("font-owners"), context.files.single { it.path == "src/Main.kt" }.matchedEvidenceSelectorIds)
    }

    @Test
    fun `analysis context retains all typography owners and a test beyond the file cap`() {
        val repository = initializedRepository()
        repeat(110) { index ->
            Files.writeString(
                repository.resolve("src/Distractor$index.kt"),
                "fun projectTypographySettingsDelivery$index() = \"project typography settings delivery regression\"\n",
            )
        }
        Files.createDirectories(repository.resolve("src/main/ui"))
        val ownerPaths = (1..4).map { index -> "src/main/ui/Surface$index.kt" }.onEachIndexed { index, path ->
            Files.writeString(repository.resolve(path), "val surface$index = FontFamily.Monospace\n")
        }
        val testPath = "src/test/ui/TypographyRegressionTest.kt"
        Files.createDirectories(repository.resolve("src/test/ui"))
        Files.writeString(repository.resolve(testPath), "class TypographyRegressionTest\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add ranked analysis context")

        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(
            repository.toString(),
            "Inspect typography across all surfaces and add regression tests.",
            listOf(
                RepositoryEvidenceSelector(
                    selectorId = "owners",
                    scopeIndexes = listOf(0),
                    pathGlobs = listOf("src/main/**/*.kt"),
                    contentLiterals = listOf("FontFamily.Monospace"),
                ),
                RepositoryEvidenceSelector(
                    selectorId = "tests",
                    scopeIndexes = listOf(0),
                    pathGlobs = listOf("src/test/**/*.kt"),
                    selection = REPOSITORY_EVIDENCE_AFFINE_TEST,
                    affinitySelectorId = "owners",
                ),
            ),
        )

        assertTrue(ownerPaths.all { path -> context.files.any { it.path == path } })
        assertTrue(context.files.any { it.path == testPath })
        assertEquals(
            (ownerPaths + testPath).sorted(),
            context.files.filter { it.matchedEvidenceSelectorIds.isNotEmpty() }.map { it.path }.sorted(),
        )
    }

    @Test
    fun `focused excerpts retain late owning declarations over repeated early usages`() {
        val content = buildString {
            repeat(300) { appendLine("Text(fontFamily = FontFamily.Monospace) // usage $it") }
            appendLine("private fun OrchardTheme(content: @Composable () -> Unit) = MaterialTheme(content = content)")
            repeat(300) { appendLine("Text(fontFamily = FontFamily.Monospace) // trailing $it") }
        }

        val excerpt = focusedContextExcerpt(content, setOf("font", "family", "orchard", "theme"), 2_048)

        assertTrue(excerpt.encodeToByteArray().size <= 2_048)
        assertTrue(excerpt.contains("private fun OrchardTheme"))
    }

    @Test
    fun `focused excerpts reserve rare surface owner declarations`() {
        val content = buildString {
            repeat(100) { appendLine("Text(\"project workspace usage $it\")") }
            repeat(80) { appendLine("private fun ProjectPanel$it() = Unit") }
            appendLine("private fun ModelSettingsDialog() = Unit")
            appendLine("private fun DeliveryTimeline() = Unit")
            repeat(100) { appendLine("Text(\"project workspace trailing $it\")") }
        }

        val excerpt = focusedContextExcerpt(content, setOf("project", "settings", "delivery"), 2_048)

        assertTrue(excerpt.encodeToByteArray().size <= 2_048)
        assertTrue(excerpt.contains("private fun ModelSettingsDialog"))
        assertTrue(excerpt.contains("private fun DeliveryTimeline"))
        val declarations = matchedSourceDeclarations(content, setOf("project", "settings", "delivery"))
        assertTrue(declarations.any { "private fun ModelSettingsDialog" in it })
        assertTrue(declarations.any { "private fun DeliveryTimeline" in it })
    }

    @Test
    fun `workspace gateway rejects ambiguous replacements without mutation`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        Files.writeString(source, "fun answer() = 1\nfun answer() = 1\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add ambiguous source")

        assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Attempt an ambiguous replacement.",
                    operations = listOf(CodingFileOperation(
                        action = CODING_FILE_REPLACE,
                        path = "src/Main.kt",
                        replacements = listOf(CodingTextReplacement("fun answer() = 1", "fun answer() = 42")),
                    )),
                ),
                executionId = 11,
            )
        }

        assertEquals("fun answer() = 1\nfun answer() = 1\n", Files.readString(source))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway rejects paths outside the reservation before mutation`() {
        val repository = initializedRepository()
        val gateway = LocalCodingWorkspaceGateway()

        assertFailsWith<IllegalArgumentException> {
            gateway.applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Escape the worktree.",
                    operations = listOf(CodingFileOperation(CODING_FILE_WRITE, "../outside.txt", "forbidden")),
                ),
                executionId = 10,
            )
        }

        assertEquals("fun answer() = 1\n", Files.readString(repository.resolve("src/Main.kt")))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway rejects a dirty index before applying model operations`() {
        val repository = initializedRepository()
        val gateway = LocalCodingWorkspaceGateway()
        Files.writeString(repository.resolve("user-change.txt"), "owned by user\n")
        run(repository, "git", "add", "user-change.txt")

        assertFailsWith<IllegalArgumentException> {
            gateway.applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Change the application.",
                    operations = listOf(CodingFileOperation(CODING_FILE_WRITE, "src/Main.kt", "fun answer() = 42\n")),
                ),
                executionId = 11,
            )
        }

        assertEquals("fun answer() = 1\n", Files.readString(repository.resolve("src/Main.kt")))
        assertTrue(run(repository, "git", "status", "--porcelain").contains("A  user-change.txt"))
    }

    @Test
    fun `workspace gateway runs an allowed verification command without a shell`() {
        val repository = initializedRepository()
        val script = repository.resolve("gradlew")
        Files.writeString(script, "#!/bin/sh\nprintf 'verified %s\\n' \"$1\"\n")
        script.toFile().setExecutable(true)
        run(repository, "git", "add", "gradlew")
        run(repository, "git", "commit", "-m", "Add verifier")

        val observation = LocalCodingWorkspaceGateway().executeVerification(
            repository.toString(),
            VerificationCommand("./gradlew", listOf("test", "--no-daemon")),
        )

        assertEquals(0, observation.exitCode)
        assertEquals("./gradlew test --no-daemon", observation.command)
        assertTrue(observation.summary.contains("verified test"))
        assertTrue(observation.outputHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `workspace gateway bounds large verification output`() {
        val repository = initializedRepository()
        val script = repository.resolve("large-output")
        Files.writeString(script, "#!/bin/sh\nhead -c 1048576 /dev/zero | tr '\\000' x\n")
        script.toFile().setExecutable(true)
        run(repository, "git", "add", "large-output")
        run(repository, "git", "commit", "-m", "Add large-output verifier")

        val observation = LocalCodingWorkspaceGateway().executeVerification(
            repository.toString(),
            VerificationCommand("./large-output"),
        )

        assertEquals(0, observation.exitCode)
        assertTrue(observation.summary.length <= 4_096)
        assertTrue(observation.outputHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `admitted verification rejects quoting that cannot round trip as typed arguments`() {
        assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().parseVerificationCommand("./gradlew 'test suite'")
        }
        assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().parseVerificationCommand("./gradlew  test")
        }
    }

        @Test
        fun `external toolchain pack adds verification without rebuilding Orchard`() {
                val repository = initializedRepository()
                Files.writeString(repository.resolve("community.build"), "community-toolchain-v1\n")
                val verifier = repository.resolve("community-verify")
                Files.writeString(verifier, "#!/bin/sh\nprintf 'community %s\\n' \"$1\"\n")
                verifier.toFile().setExecutable(true)
                run(repository, "git", "add", ".")
                run(repository, "git", "commit", "-m", "Add community toolchain")
                val policyDirectory = createTempDirectory("orchard-toolchain-packs-")
                val gateway = LocalCodingWorkspaceGateway(FileToolchainPolicyCatalog(policyDirectory))
                assertNull(gateway.resolveToolchainPolicy(repository.toString()))
                Files.writeString(
                        policyDirectory.resolve("community.json"),
                        """
                        {
                            "schemaVersion": 1,
                            "packId": "community.example-toolchain",
                            "packVersion": 3,
                            "profiles": [{
                                "id": "community-build",
                                "priority": 500,
                                "allFiles": ["community.build"],
                                "commands": {
                                    "BUILD": { "executable": "./community-verify", "arguments": ["build"] },
                                    "TEST": { "executable": "./community-verify", "arguments": ["test"] }
                                }
                            }]
                        }
                        """.trimIndent(),
                )
                val policy = requireNotNull(gateway.resolveToolchainPolicy(repository.toString()))
                val observation = gateway.executeVerification(repository.toString(), requireNotNull(policy.commands["TEST"]))

                assertEquals("community.example-toolchain", policy.packId)
                assertEquals(3, policy.packVersion)
                assertEquals("community-build", policy.profileId)
                assertTrue(policy.policyHash.matches(Regex("[0-9a-f]{64}")))
                assertEquals("./community-verify test", observation.command)
                assertTrue(observation.summary.contains("community test"))
        }

        @Test
        fun `external toolchain pack validation rejects repository escape detectors`() {
                val repository = initializedRepository()
                val policyDirectory = createTempDirectory("orchard-invalid-toolchain-packs-")
                Files.writeString(
                        policyDirectory.resolve("invalid.json"),
                        """
                        {
                            "schemaVersion": 1,
                            "packId": "community.invalid",
                            "packVersion": 1,
                            "profiles": [{
                                "id": "escape",
                                "allFiles": ["../outside"],
                                "commands": {
                                    "BUILD": { "executable": "./verify", "arguments": ["build"] }
                                }
                            }]
                        }
                        """.trimIndent(),
                )

                assertFailsWith<IllegalArgumentException> {
                        FileToolchainPolicyCatalog(policyDirectory).resolve(repository)
                }
        }

        @Test
        fun `external toolchain pack wins an equal-priority match explicitly`() {
                val repository = initializedRepository()
                val gradle = repository.resolve("gradlew")
                Files.writeString(gradle, "#!/bin/sh\nexit 0\n")
                gradle.toFile().setExecutable(true)
                run(repository, "git", "add", "gradlew")
                run(repository, "git", "commit", "-m", "Add Gradle wrapper")
                val policyDirectory = createTempDirectory("orchard-priority-toolchain-packs-")
                Files.writeString(
                        policyDirectory.resolve("override.json"),
                        """
                        {
                            "schemaVersion": 1,
                            "packId": "community.gradle-policy",
                            "packVersion": 1,
                            "profiles": [{
                                "id": "gradle-wrapper",
                                "priority": 100,
                                "allFiles": ["gradlew"],
                                "commands": {
                                    "BUILD": { "executable": "./gradlew", "arguments": ["community-build"] }
                                }
                            }]
                        }
                        """.trimIndent(),
                )

                val policy = requireNotNull(FileToolchainPolicyCatalog(policyDirectory).resolve(repository))

                assertEquals("community.gradle-policy", policy.packId)
                assertEquals(listOf("community-build"), policy.commands.getValue("BUILD").arguments)
        }

        @Test
        fun `external toolchain pack file size is bounded before decoding`() {
                val repository = initializedRepository()
                val policyDirectory = createTempDirectory("orchard-oversized-toolchain-packs-")
                Files.writeString(policyDirectory.resolve("oversized.json"), " ".repeat(256 * 1024 + 1))

                assertFailsWith<IllegalArgumentException> {
                        FileToolchainPolicyCatalog(policyDirectory).resolve(repository)
                }
        }

            @Test
            fun `malformed hot-loaded pack is distinct from a valid catalog with no match`() {
                val repository = initializedRepository()
                val policyDirectory = createTempDirectory("orchard-malformed-toolchain-packs-")
                val catalog = FileToolchainPolicyCatalog(policyDirectory)
                assertNull(catalog.resolve(repository))
                Files.writeString(policyDirectory.resolve("partial.json"), "{\"schemaVersion\":1")

                assertFailsWith<IllegalStateException> {
                    catalog.resolve(repository)
                }
            }

    @Test
    fun `repository validation binds evidence to a stable canonical diff hash`() {
        val repository = initializedRepository()
        val authority = createTempDirectory("orchard-coding-repository-authority-")
        val bindings = FileRepositoryBindingStore(authority)
        bindings.bind(1, repository.toString())
        val base = run(repository, "git", "rev-parse", "HEAD")
        Files.writeString(repository.resolve("src/Main.kt"), "fun answer() = 42\n")
        run(repository, "git", "add", "src/Main.kt")
        run(repository, "git", "commit", "-m", "Candidate")
        val target = run(repository, "git", "rev-parse", "HEAD")

        val first = bindings.validateRevision(1, base, target)
        val second = bindings.validateRevision(1, base, target)

        assertTrue(requireNotNull(first).changedFromBase)
        assertTrue(requireNotNull(first.diffHash).matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, second)
    }

    private fun claim(executionId: Long, runId: Long, attempt: Int): CodingWorkerClaim {
        val draft = CodingWorkerClaim(
            executionId = executionId,
            runId = runId,
            attempt = attempt,
            contextHash = "a".repeat(64),
            workspacePath = "/tmp/orchard-worktree-$runId",
            bindingFingerprint = "b".repeat(64),
            toolchainPackId = "orchard.default-toolchains",
            toolchainPackVersion = 1,
            toolchainProfileId = "gradle-wrapper",
            toolchainPolicyHash = "c".repeat(64),
            claimedAt = "2026-06-21T00:00:00Z",
            hash = "",
        )
        return draft.copy(hash = codingWorkerClaimHash(draft))
    }

    private fun intent(
        type: Int,
        title: String,
        projectId: Int = 0,
        epicId: Int = 0,
        storyId: Int = 0,
    ) = DocumentIntent(
        actionTypeId = ACTION_CREATE,
        entityTypeId = type,
        boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
        projectId = projectId,
        epicId = epicId,
        storyId = storyId,
        title = title,
    )

    private fun initializedRepository(): Path {
        val directory = createTempDirectory("orchard-coding-worktree-")
        run(directory, "git", "init")
        run(directory, "git", "config", "user.name", "Orchard Test")
        run(directory, "git", "config", "user.email", "orchard-test@localhost")
        Files.createDirectories(directory.resolve("src"))
        Files.writeString(directory.resolve("src/Main.kt"), "fun answer() = 1\n")
        run(directory, "git", "add", ".")
        run(directory, "git", "commit", "-m", "Initial")
        return directory
    }

    private fun run(directory: Path, vararg command: String): String {
        val process = ProcessBuilder(command.toList())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "Command ${command.joinToString(" ")} failed: $output" }
        return output
    }

    private class FixedCodingModel(private val output: String) : ModelProvider {
        var maxOutputTokens: Int? = null
        var contextWindowTokens: Int? = null

        override suspend fun triage(prompt: String): String = error("Unsupported")

        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("Unsupported")

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:coding-model",
            provider = "test",
            model = "fixed-coding-model",
            contextWindowTokens = 131_072,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeCodingPatch(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            this.maxOutputTokens = maxOutputTokens
            this.contextWindowTokens = contextWindowTokens
            return ModelGeneration(output, prompt.length, output.length)
        }
    }
}
