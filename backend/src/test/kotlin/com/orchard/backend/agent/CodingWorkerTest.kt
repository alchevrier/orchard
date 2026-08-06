package com.orchard.backend.agent

import com.orchard.backend.analysis.AnalysisExecutionProvenance
import com.orchard.backend.analysis.DISPOSITION_PARTIALLY_IMPLEMENTED
import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.WorkPackageCheck
import com.orchard.backend.analysis.WorkPackageDesignAuthority
import com.orchard.backend.analysis.WorkPackageIntentAuthority
import com.orchard.backend.analysis.WorkPackageOperation
import com.orchard.backend.analysis.WorkPackageOperationAuthority
import com.orchard.backend.analysis.WorkPackageOwnershipBoundary
import com.orchard.backend.analysis.WorkPackageSource
import com.orchard.backend.analysis.repositoryPlanRequiresRevision
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.resource.ModelResourceDemand
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
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
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
    fun `coding work package projection excludes durable prompt bloat`() {
        val sourceContent = "x".repeat(100_000)
        val workPackage = ExecutableWorkPackage(
            packageId = 7,
            revision = 1,
            projectId = 1,
            runId = 2,
            repositoryRevision = "a".repeat(40),
            intent = WorkPackageIntentAuthority(3, 1, "b".repeat(64), "Outcome", "Current", "Required", emptyList(), listOf("Accepted")),
            design = WorkPackageDesignAuthority(4, 1, "c".repeat(64), "Design", listOf("Invariant"), listOf("Non-goal")),
            ownership = WorkPackageOwnershipBoundary(listOf("src/Main.kt"), emptyList(), emptyList(), listOf(CODING_FILE_WRITE)),
            operations = WorkPackageOperationAuthority(listOf(WorkPackageOperation(1, "MODIFY", "src/Main.kt", instruction = "Change it", acceptanceCriteria = listOf("Accepted")))),
            expectedBehavior = listOf("Works"),
            unresolvedAuthorityQuestions = listOf("Unused authority"),
            sources = listOf(WorkPackageSource("src/Main.kt", sourceContent, "d".repeat(64))),
            checks = listOf(WorkPackageCheck("build", "./gradlew check")),
            escalationConditions = listOf("Unused escalation"),
            hash = "e".repeat(64),
        )

        val projection = codingWorkPackageProjection(workPackage)
        val serialized = Json.encodeToString(projection)

        assertEquals(7, projection.packageId)
        assertEquals(listOf("src/Main.kt"), projection.ownershipPaths)
        assertEquals(listOf("build"), projection.checks.map { it.checkId })
        assertTrue(sourceContent !in serialized)
        assertTrue("Unused authority" !in serialized)
        assertTrue("Unused escalation" !in serialized)
        assertTrue("Invariant" !in serialized)
    }

    @Test
    fun `test proposal rejects nullable assert true condition`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val old = "assertEquals(\"Calm\", proposal.submission.experience?.emotionalQualities?.single())"
        val proposal = CodingPatchProposal(
            "Add experience quality coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    old,
                    "$old\nassertTrue(proposal.submission.experience?.emotionalQualities?.contains(\"calm\"))",
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, old)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("assertTrue with a nullable condition"))
    }

    @Test
    fun `test write proposal rejects nullable assert true without source context`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val proposal = CodingPatchProposal(
            "Add experience quality coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_WRITE,
                path = path,
                content = "assertTrue(proposal.submission.experience?.domainCorrelation != null)",
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(emptyList(), omittedFileCount = 1),
        )

        assertTrue(requireNotNull(diagnostic).contains("assertTrue with a nullable condition"))
    }

    @Test
    fun `test proposal rejects assert not null without import`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val old = "assertEquals(\"Calm\", proposal.submission.experience?.emotionalQualities?.single())"
        val proposal = CodingPatchProposal(
            "Add experience quality coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    old,
                    "$old\nassertNotNull(proposal.submission.conversation)",
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, old)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("assertNotNull without importing"))
    }

    @Test
    fun `test write proposal rejects assert not null without source context`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val proposal = CodingPatchProposal(
            "Add conversation correlation coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_WRITE,
                path = path,
                content = "assertNotNull(proposal.conversation)",
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(emptyList(), omittedFileCount = 1),
        )

        assertTrue(requireNotNull(diagnostic).contains("assertNotNull without importing"))
    }

    @Test
    fun `test bounded replacement materializes assert not null import`() {
        val diagnostic = boundedCodingToolBehaviorDiagnostic(BoundedCodingToolBatch(
            "Add conversation correlation coverage.",
            "a".repeat(40),
            listOf(BoundedCodingToolOperation(
                BOUNDED_TOOL_REPLACE_LITERAL,
                "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt",
                expectedLiteral = "assertEquals(1, 1)",
                replacement = "assertNotNull(proposal.conversation)",
                expectedCount = 1,
            )),
        ))

        assertEquals(null, diagnostic)
        assertTrue(
            materializeRequiredTestImports(
                "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt",
                "package com.orchard.frontend.network\n\nimport kotlin.test.assertTrue\n\nassertNotNull(proposal)",
            ).contains("import kotlin.test.assertNotNull"),
        )
    }

    @Test
    fun `test bounded tool batch rejects unsupported proposal conversation property`() {
        val diagnostic = boundedCodingToolBehaviorDiagnostic(BoundedCodingToolBatch(
            "Add conversation coverage.",
            "a".repeat(40),
            listOf(BoundedCodingToolOperation(
                BOUNDED_TOOL_REPLACE_LITERAL,
                "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt",
                expectedLiteral = "assertEquals(1, 1)",
                replacement = "assertNotNull(proposal.conversation)",
                expectedCount = 1,
            )),
        ))

        assertTrue(requireNotNull(diagnostic).contains("proposal.conversation"))
    }

    @Test
    fun `test write proposal rejects assert not null without import`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val old = "import kotlin.test.assertEquals\n\nfun test() = assertEquals(1, 1)"
        val proposal = CodingPatchProposal(
            "Add assertion coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_WRITE,
                path = path,
                content = "$old\nassertNotNull(result)",
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, old)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("assertNotNull without importing"))
    }

    @Test
    fun `test proposal rejects local inserted before existing local`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val old = """
            assertTrue(result.overlays.isEmpty())
        """.trimIndent()
        val original = """
            fun `returns standards policy`() = runBlocking {
                $old

                client.close()
                val conversation = client.getConversation(1, 42)
                assertNotNull(conversation)
            }
        """.trimIndent()
        val proposal = CodingPatchProposal(
            "Add correlation authority coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    old,
                    """
                        $old
                        val conversation = client.getConversation(42L)
                        assertNotNull(conversation)
                    """.trimIndent(),
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, original)), omittedFileCount = 0),
        )

        assertTrue(
            requireNotNull(diagnostic).contains("introduces local declaration conversation before an existing declaration"),
            diagnostic,
        )
    }

    @Test
    fun `test proposal rejects duplicate local in expression-bodied test`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val original = """
            fun `returns standards policy`() = runBlocking {
                val result = client.getStandardsPolicy(1, "backend/standards", 42)
                assertTrue(result.overlays.isEmpty())
                val conversation = client.getConversation(42L)
                assertNotNull(conversation)
            }
        """.trimIndent()
        val proposal = CodingPatchProposal(
            "Add correlation authority coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    "assertTrue(result.overlays.isEmpty())",
                    """
                        assertTrue(result.overlays.isEmpty())
                        val conversation = client.getConversation(42, "conversation/42")
                    """.trimIndent(),
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, original)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("introduces duplicate local declaration conversation"))
    }

    @Test
    fun `test proposal rejects endpoint insertion into another endpoint test`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val original = """
            fun `returns standards policy`() {
                val result = client.getStandardsPolicy(1, "backend/standards", 42)
                assertTrue(result.overlays.isEmpty())
            }

            fun `returns conversation`() {
                val conversation = client.getConversation(1, "conversation/1")
                assertNotNull(conversation)
            }
        """.trimIndent()
        val replaced = """
            val result = client.getStandardsPolicy(1, "backend/standards", 42)
            assertTrue(result.overlays.isEmpty())
        """.trimIndent()
        val proposal = CodingPatchProposal(
            "Add correlation authority coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    replaced,
                    """
                        $replaced
                        val conversation = client.getConversation(42, "conversation/42")
                        assertNotNull(conversation)
                    """.trimIndent(),
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, original)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("adds unrelated client endpoint call getConversation to an existing endpoint test"))
    }

    @Test
    fun `test proposal rejects duplicate local declaration before candidate commit`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val original = """
            fun `returns standards policy`() {
                val conversation = client.getConversation(1, "conversation/1")
                assertNotNull(conversation)
            }
        """.trimIndent()
        val proposal = CodingPatchProposal(
            "Add correlation authority coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    "assertNotNull(conversation)",
                    """
                        val correlation = DomainCorrelation(42, 31)
                        assertTrue(correlation.projectId == 42)
                        val conversation = ConversationResponse(31, "Delivery", "HUMAN", "2026-07-22T00:00:00Z", "a")
                        assertNotNull(conversation)
                    """.trimIndent(),
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, original)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("introduces duplicate local declaration conversation"))
    }

    @Test
    fun `test proposal rejects unrelated endpoint assertion before candidate commit`() {
        val path = "frontend/src/desktopTest/kotlin/com/orchard/frontend/network/DesktopNetworkClientTest.kt"
        val original = """
            val result = client.getStandardsPolicy(1, \"backend/standards\", 42)
            assertTrue(result.overlays.isEmpty())
        """.trimIndent()
        val proposal = CodingPatchProposal(
            "Add correlation authority coverage.",
            listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(
                    original,
                    """
                        $original

                        // Admit backend correlation authority behavior
                        val conversation = client.getConversation(42, \"conversation/42\")
                        assertEquals(\"conversation/42\", conversation.id)
                    """.trimIndent(),
                )),
            )),
        )

        val diagnostic = codingProposalBehaviorDiagnostic(
            proposal,
            emptyList(),
            CodingRepositoryContext(listOf(CodingContextFile(path, original)), omittedFileCount = 0),
        )

        assertTrue(requireNotNull(diagnostic).contains("adds unrelated client endpoint call getConversation"))
    }

    @Test
    fun `candidate semantic verification rejects residual forbidden literals`() {
        val criterion = "None of the bounded production files contains FontFamily.Serif or another decorative family."

        assertTrue(
            requireNotNull(candidateForbiddenLiteralDiagnostic(
                listOf(criterion),
                CodingRepositoryContext(
                    listOf(CodingContextFile("src/GuidedGenesisWorkspace.kt", "FontFamily.Serif\n".repeat(5))),
                    omittedFileCount = 0,
                ),
            )).startsWith("Candidate retains forbidden literal FontFamily.Serif 5 times in src/GuidedGenesisWorkspace.kt."),
        )
        assertNull(candidateForbiddenLiteralDiagnostic(
            listOf(criterion),
            CodingRepositoryContext(
                listOf(CodingContextFile("src/GuidedGenesisWorkspace.kt", "FontFamily.Default")),
                omittedFileCount = 0,
            ),
        ))
        assertEquals(
            "Candidate semantic verification is missing scoped production paths.",
            candidateForbiddenLiteralDiagnostic(listOf(criterion), CodingRepositoryContext(emptyList(), omittedFileCount = 1)),
        )
        val groundedDiagnostic = requireNotNull(candidateForbiddenLiteralDiagnostic(
            listOf(criterion),
            CodingRepositoryContext(
                listOf(CodingContextFile(
                    "src/GuidedGenesisWorkspace.kt",
                    "first = FontFamily.Serif\nsecond = FontFamily.Serif\n",
                )),
                omittedFileCount = 0,
            ),
        ))
        assertTrue(groundedDiagnostic.contains("exact source-backed unique anchor suggestions"))
        assertTrue(groundedDiagnostic.contains("first = FontFamily.Serif"))
        assertTrue(groundedDiagnostic.contains("second = FontFamily.Serif"))
    }

    @Test
    fun `governed worker does not substitute generic tests for acceptance evidence`() = runTest {
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
                acceptanceCriteria = listOf(
                    AcceptanceCriterion("The answer is forty two", "Inspect the returned value"),
                    AcceptanceCriterion("The signature is preserved", "Review the source diff"),
                ),
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

        assertEquals(CodingWorkerTickStatus.VERIFICATION_FAILED, result.status)
        assertEquals(CODING_EXECUTION_FAILED, requireNotNull(result.execution?.result).status)
        val reservation = requireNotNull(run.context.workspaceReservation)
        val candidateRevision = requireNotNull(result.execution?.result?.revision)
        assertEquals(candidateRevision, run(Path.of(reservation.path), "git", "rev-parse", "$candidateRevision^{commit}"))
        assertEquals("", run(Path.of(reservation.path), "git", "diff", "--name-only", "$candidateRevision^", "HEAD"))
        assertEquals("", run(Path.of(reservation.path), "git", "status", "--porcelain"))
        assertTrue(requireNotNull(result.execution?.result?.diagnostic).contains("Evidence ACCEPTANCE has no admitted or repository verification command."))
        assertEquals("orchard.default-toolchains", result.execution?.claim?.toolchainPackId)
        assertEquals("gradle-wrapper", result.execution?.claim?.toolchainProfileId)
        assertTrue(requireNotNull(result.execution?.claim?.toolchainPolicyHash).matches(Regex("[0-9a-f]{64}")))
        assertTrue(requireNotNull(model.prompt).contains("Plan instructions describe intent and do not prove that any literal exists."))
        assertTrue(model.prompt?.contains("Copy every REPLACE old value as one exact contiguous substring") == true)
        assertTrue(model.prompt?.contains("count its exact old value in the supplied content") == true)
        assertTrue(model.prompt?.contains("extend old with unchanged preceding and following source lines until it is unique") == true)
        assertTrue(model.prompt?.contains("A rejected REPLACE old value is a defect in the prior proposal") == true)
        assertTrue(model.prompt?.contains("rejected old-text value named in priorRejectedCodingDiagnostic as forbidden") == true)
        assertTrue(model.prompt?.contains("Every WRITE and REPLACE operation must change its target bytes") == true)
        assertTrue(model.prompt?.contains("operations must not be empty") == true)
        assertTrue(model.prompt?.contains("forbidden only as the complete `old` value") == true)
        assertTrue(model.prompt?.contains("include it inside a larger exact source-backed `old` value") == true)
        assertTrue(model.prompt?.contains("Never add or modify comments, imports, annotations, whitespace, or formatting merely to cover a required path") == true)
        assertTrue(model.prompt?.contains("include an assertion whose result depends on the production behavior or production source") == true)
        assertTrue(model.prompt?.contains("A comment-only test replacement is forbidden") == true)
        assertTrue(model.prompt?.contains("do not return an empty operations array") == true)
        assertTrue(model.prompt?.contains("Orchard deterministically admits repositoryContext") == true)
        assertTrue(model.prompt?.contains("Never fabricate cosmetic coverage") == true)
        assertTrue(model.prompt?.contains("pairwise non-overlapping old values and order replacements from the bottom") == true)
        assertTrue(model.prompt?.contains("excerpt headers are context metadata, not repository source") == true)
        assertTrue(model.prompt?.contains("The operations array must contain only operation objects") == true)
        assertTrue(model.prompt?.contains("every operation matches one of the allowed payload shapes") == true)
        assertEquals(RUN_STATE_EVIDENCE_PENDING, run.state)
        assertEquals(setOf("SOURCE_DIFF", "BUILD", "TEST"), run.evidence.mapTo(hashSetOf()) { it.kind })
        assertTrue(run.evidence.all { it.passed })
        assertEquals("fun answer() = 42", run(Path.of(reservation.path), "git", "show", "$candidateRevision:src/Main.kt"))
        assertEquals(
            run(Path.of(reservation.path), "git", "show", "$candidateRevision^:src/Main.kt"),
            Files.readString(Path.of(reservation.path).resolve("src/Main.kt")).trimEnd(),
        )
        assertEquals(8_000, model.maxOutputTokens)
        assertEquals(88_000, model.contextWindowTokens)
        assertTrue(requireNotNull(model.resourceDemandInputTokens) in 1 until 80_000)
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
    fun `worker journal replays checksum from stored value before defaulted package fields`() {
        val directory = createTempDirectory("orchard-legacy-worker-envelope-")
        Files.writeString(
            directory.resolve("coding-worker.jsonl"),
            """{"version":1,"value":{"eventId":1,"claim":{"executionId":1,"runId":6,"attempt":1,"contextHash":"6c73098f77fea74187473a55a7e7f80d55fcdbe6b565641524ec08ebe17ec526","workspacePath":"/Users/rogue-leader/.orchard/projects/workspace/worktrees/circuit-dispatch-6","bindingFingerprint":"f624e16b4d8cab0667ea26710ce63b02a9c86d063c5c85ea031cb2e9d8d8798e","assignmentId":9,"staffRole":"IMPLEMENTER","riskClass":"HIGH","executionPlanId":2,"executionPlanHash":"0f2d3cefd1ebb57a0e345baa2f2b29f96cf77bfdd1fde14a765904232d52389c","toolchainPackId":"orchard.default-toolchains","toolchainPackVersion":1,"toolchainProfileId":"gradle-wrapper","toolchainPolicyHash":"1e3a971c50db584970b34fdce7daed7f52fd4ef666717bac73ebc6a2b72f859a","claimedAt":"2026-07-23T11:13:27.413031Z","hash":"5afefd9662251711ee6b6cfc0b6a278f8edc29d53622b457d2b78ab36297770c"},"result":null},"checksum":"407c88954bdf5cc37e9c448ca25fd29e98399b47e5b8c33143a3d996f11dc92e"}
            """,
        )

        val events = FileCodingWorkerStore(directory).loadEvents()

        assertEquals(1, events.size)
        assertEquals(null, events.single().claim?.workPackageId)
    }

    @Test
    fun `background tick recovers the oldest interrupted claim before candidate selection`() = runTest {
        val store = TransientCodingWorkerStore()
        val claim = claim(executionId = 1, runId = 17, attempt = 1)
        store.append(CodingWorkerEvent(eventId = 1, claim = claim))
        val worker = CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = store,
        )

        val result = worker.tick()

        assertEquals(CodingWorkerTickStatus.INTERRUPTED_RECOVERED, result.status)
        assertEquals(CODING_EXECUTION_INTERRUPTED, result.execution?.result?.status)
        assertEquals(1, result.execution?.claim?.executionId)
    }

    @Test
    fun `explicit retry can bind the latest terminal model failure to its current plan`() {
        val planHash = "d".repeat(64)
        val matchingClaimDraft = claim(executionId = 3, runId = 19, attempt = 3).copy(
            executionPlanId = 23,
            executionPlanHash = planHash,
            hash = "",
        )
        val matchingClaim = matchingClaimDraft.copy(hash = codingWorkerClaimHash(matchingClaimDraft))
        val failedResultDraft = CodingWorkerResult(
            executionId = 3,
            status = CODING_EXECUTION_FAILED,
            diagnostic = "Provider rejected the structured request.",
            completedAt = "2026-06-21T00:03:00Z",
            hash = "",
        )
        val failed = CodingWorkerExecutionView(
            matchingClaim,
            failedResultDraft.copy(hash = codingWorkerResultHash(failedResultDraft)),
        )
        val deferred = failed.copy(result = failed.result?.copy(status = CODING_EXECUTION_DEFERRED))
        val attempts = TransientCodingWorkerAttemptStore()
        val plan = RepositoryExecutionPlan(
            planId = 23,
            runId = 19,
            revision = 1,
            projectId = 1,
            baseRevision = "e".repeat(40),
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Apply the admitted change.",
                evidence = emptyList(),
                reuse = emptyList(),
                preservedInvariants = emptyList(),
                nonGoals = emptyList(),
                operations = emptyList(),
                verificationCommands = emptyList(),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "test-analysis",
                bindingFingerprint = "f".repeat(64),
                promptHash = "1".repeat(64),
                contextHash = "2".repeat(64),
                outputHash = "3".repeat(64),
                modelExecutionId = 1,
            ),
            hash = planHash,
        )

        assertEquals(
            failed,
            codingRetryableTerminalFailure(listOf(deferred, failed), 19, 23, planHash),
        )
        assertEquals(null, codingRetryableTerminalFailure(listOf(failed), 19, 24, planHash))
        assertEquals(null, codingRetryableTerminalFailure(listOf(deferred), 19, 23, planHash))
        val blocked = attempts.retryBasisForTerminalFailure(listOf(deferred, failed), 19, plan)
        assertEquals(CODING_ATTEMPT_BLOCKED, blocked?.state)
        assertEquals(failed.result?.diagnostic, blocked?.diagnostic)
        attempts.appendNext { attemptId ->
            CodingWorkerAttempt(
                attemptId = attemptId,
                runId = 19,
                executionPlanId = 23,
                executionPlanHash = planHash,
                state = CODING_ATTEMPT_RETRY_AUTHORIZED,
                resultStatus = CodingWorkerTickStatus.RETRY_AUTHORIZED.name,
                diagnostic = "A human explicitly authorized one successor coding attempt.",
            )
        }

        assertEquals(
            CODING_ATTEMPT_RETRY_AUTHORIZED,
            attempts.retryBasisForTerminalFailure(listOf(failed), 19, plan)?.state,
        )
        assertEquals(2, attempts.load().size)
    }

    @Test
    fun `terminal plan context block after consumed retry requests reanalysis and recovers idempotently`() {
        val planHash = "d".repeat(64)
        val claimDraft = claim(executionId = 1, runId = 19, attempt = 1).copy(
            executionPlanId = 23,
            executionPlanHash = planHash,
            hash = "",
        )
        val boundClaim = claimDraft.copy(hash = codingWorkerClaimHash(claimDraft))
        val resultDraft = CodingWorkerResult(
            executionId = boundClaim.executionId,
            status = CODING_EXECUTION_BLOCKED,
            diagnostic = "Repository plan context budget is too small",
            completedAt = "2026-06-21T00:05:00Z",
            hash = "",
        )
        val blockedResult = resultDraft.copy(hash = codingWorkerResultHash(resultDraft))
        val workerStore = TransientCodingWorkerStore().apply {
            append(CodingWorkerEvent(eventId = 1, claim = boundClaim))
            append(CodingWorkerEvent(eventId = 2, result = blockedResult))
        }
        val attempts = TransientCodingWorkerAttemptStore().apply {
            appendNext { attemptId -> codingAttempt(attemptId, CODING_ATTEMPT_BLOCKED, "Rejected cosmetic mutation.", planHash) }
            appendNext { attemptId -> codingAttempt(attemptId, CODING_ATTEMPT_RETRY_AUTHORIZED, "One correction authorized.", planHash) }
            appendNext { attemptId -> codingAttempt(attemptId, CODING_ATTEMPT_RETRY_CONSUMED, "Correction consumed.", planHash) }
        }
        val plan = RepositoryExecutionPlan(
            planId = 23,
            runId = 19,
            revision = 1,
            projectId = 1,
            baseRevision = "e".repeat(40),
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Apply the admitted change.",
                evidence = emptyList(),
                reuse = emptyList(),
                preservedInvariants = emptyList(),
                nonGoals = emptyList(),
                operations = emptyList(),
                verificationCommands = emptyList(),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "test-analysis",
                bindingFingerprint = "f".repeat(64),
                promptHash = "1".repeat(64),
                contextHash = "2".repeat(64),
                outputHash = "3".repeat(64),
                modelExecutionId = 1,
            ),
            hash = planHash,
        )

        repeat(2) {
            CodingWorkerService(
                workspace = WorkspaceStore(),
                modelProviders = emptyList(),
                workerStore = workerStore,
                attemptStore = attempts,
            )
        }

        assertEquals(4, attempts.load().size)
        assertEquals(CODING_ATTEMPT_BLOCKED, attempts.load().last().state)
        assertEquals(blockedResult.diagnostic, attempts.load().last().diagnostic)
        assertTrue(repositoryPlanRequiresRevision(plan, attempts.load()))
    }

    @Test
    fun `new execution plan does not inherit exhausted repair budget from prior plan`() {
        val oldPlanHash = "d".repeat(64)
        val newPlanHash = "e".repeat(64)
        val oldExecutions = (1L..3L).map { executionId ->
            val claimDraft = claim(executionId, runId = 19, attempt = executionId.toInt()).copy(
                executionPlanId = 23,
                executionPlanHash = oldPlanHash,
                hash = "",
            )
            val resultDraft = CodingWorkerResult(
                executionId = executionId,
                status = CODING_EXECUTION_FAILED,
                diagnostic = "Prior plan repair failed.",
                completedAt = "2026-06-21T00:0${executionId}:00Z",
                hash = "",
            )
            CodingWorkerExecutionView(
                claimDraft.copy(hash = codingWorkerClaimHash(claimDraft)),
                resultDraft.copy(hash = codingWorkerResultHash(resultDraft)),
            )
        }
        val currentPlan = RepositoryExecutionPlan(
            planId = 24,
            runId = 19,
            revision = 2,
            projectId = 1,
            baseRevision = "f".repeat(40),
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Repair the current candidate.",
                evidence = emptyList(),
                reuse = emptyList(),
                preservedInvariants = emptyList(),
                nonGoals = emptyList(),
                operations = emptyList(),
                verificationCommands = emptyList(),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "test-analysis",
                bindingFingerprint = "1".repeat(64),
                promptHash = "2".repeat(64),
                contextHash = "3".repeat(64),
                outputHash = "4".repeat(64),
                modelExecutionId = 1,
            ),
            hash = newPlanHash,
        )

        assertTrue(
            codingRunCanExecute(
                executions = oldExecutions,
                attempts = emptyList(),
                currentPlan = currentPlan,
                bindToCurrentPlan = true,
                retryBudget = 3,
            )
        )
        assertEquals(
            false,
            codingRunCanExecute(
                executions = oldExecutions,
                attempts = emptyList(),
                currentPlan = null,
                bindToCurrentPlan = false,
                retryBudget = 3,
            ),
        )
    }

    @Test
    fun `coding context query includes accepted plan semantics`() {
        val plan = RepositoryExecutionPlan(
            planId = 23,
            runId = 19,
            revision = 1,
            projectId = 5,
            baseRevision = "e".repeat(40),
            content = RepositoryAnalysisPlanContent(
                disposition = DISPOSITION_PARTIALLY_IMPLEMENTED,
                summary = "Declare shared platform-default typography.",
                evidence = listOf(com.orchard.backend.analysis.RepositoryEvidenceCitation(
                    path = "src/Theme.kt",
                    symbol = "OrchardTheme",
                    observation = "MaterialTheme owns the shared typography defaults.",
                    contentHash = "f".repeat(64),
                )),
                reuse = emptyList(),
                preservedInvariants = emptyList(),
                nonGoals = emptyList(),
                operations = listOf(com.orchard.backend.analysis.ExecutionPlanOperation(
                    order = 1,
                    action = "MODIFY",
                    path = "src/Theme.kt",
                    symbol = "OrchardCircuitBinder",
                    instruction = "Set the default font family on shared typography.",
                    acceptanceCriteria = listOf("Human-readable text uses FontFamily.Default."),
                )),
                verificationCommands = emptyList(),
                scopeCoverage = listOf(com.orchard.backend.analysis.ExecutionPlanScopeCoverage(
                    scope = "Existing compliant inbox typography",
                    evidencePaths = listOf("src/Inbox.kt", "src/Theme.kt"),
                    operationOrders = listOf(1),
                    compliantEvidencePaths = listOf("src/Inbox.kt"),
                )),
            ),
            provenance = AnalysisExecutionProvenance(
                executionProfileId = "test-analysis",
                bindingFingerprint = "a".repeat(64),
                promptHash = "b".repeat(64),
                contextHash = "c".repeat(64),
                outputHash = "d".repeat(64),
                modelExecutionId = 1,
            ),
            hash = "1".repeat(64),
        )

        val query = codingPlanContextQuery(plan)

        assertTrue(query.contains("OrchardTheme"))
        assertTrue(query.contains("MaterialTheme owns the shared typography defaults."))
        assertTrue(query.contains("Set the default font family on shared typography."))
        assertTrue(query.contains("Human-readable text uses FontFamily.Default."))
        assertEquals(listOf("src/Theme.kt", "src/Inbox.kt"), codingPlanContextPaths(plan))
    }

    @Test
    fun `coding proposal cannot reuse a rejected anchor for the same path`() {
        val path = "src/OrchardCircuitBinder.kt"
        val old = "fontFamily = FontFamily.Serif"
        val attempts = listOf(CodingWorkerAttempt(
            attemptId = 1,
            runId = 19,
            executionPlanId = 23,
            executionPlanHash = "a".repeat(64),
            state = CODING_ATTEMPT_BLOCKED,
            resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
            diagnostic = "The coding proposal could not be applied: REPLACE $path replacement 1 old text occurs 0 times; " +
                "expected exactly once; ${rejectedReplacementAnchor(old)}",
        ))
        val proposal = CodingPatchProposal(
            summary = "Attempt the rejected theme anchor again.",
            operations = listOf(CodingFileOperation(
                action = CODING_FILE_REPLACE,
                path = path,
                replacements = listOf(CodingTextReplacement(old, "fontFamily = FontFamily.Default")),
            )),
        )
        val testPath = "src/OrchardCircuitBinderTest.kt"
        val context = CodingRepositoryContext(listOf(CodingContextFile(
            path = path,
            content = """[Orchard excerpt lines 40-46 of 100]
                |private fun UnrelatedPanel() = Unit
                |val heading = FontFamily.Serif
                |@Composable
                |private fun OrchardTheme(content: @Composable () -> Unit) {
                |    val body = FontFamily.Serif
                |    MaterialTheme(
                |        colors = MaterialTheme.colors.copy(
                |            primary = OrchardColors.moss,
                |        ),
                |        content = content,
                |""".trimMargin(),
            contentHash = "b".repeat(64),
            matchedDeclarations = listOf(
                "private fun UnrelatedPanel() = Unit",
                "private fun OrchardTheme(content: @Composable () -> Unit)",
            ),
        ), CodingContextFile(
            path = testPath,
            content = """package orchard
                |
                |import java.io.File
                |
                |class OrchardCircuitBinderTest
                |fun existingSourcePolicyTest() = check(true)
                |""".trimMargin(),
            contentHash = "c".repeat(64),
            matchedDeclarations = listOf("class OrchardCircuitBinderTest"),
        )), 0)

        val diagnostic = codingRejectedAnchorDiagnostic(
            proposal,
            attempts,
            runId = 19,
            planId = 23,
            planHash = "a".repeat(64),
            repositoryContext = context,
        )

        assertTrue(requireNotNull(diagnostic).contains("reuses a previously rejected source anchor"))
        assertTrue(diagnostic.contains("private fun OrchardTheme"))
        assertTrue(diagnostic.contains("Exact contiguous source text near matched declarations"))
        assertTrue(diagnostic.contains("MaterialTheme.colors.copy"))
        assertTrue(!diagnostic.contains("[Orchard excerpt lines"))
        val legacyDiagnostic = attempts.single().diagnostic +
            " Candidate retains forbidden literal FontFamily.Serif. Required test path: $testPath."
        val groundedLegacyDiagnostic = sourceGroundedRetryDiagnostic(legacyDiagnostic, context)
        assertTrue(requireNotNull(groundedLegacyDiagnostic).contains("Exact contiguous source text for this correction"))
        assertTrue(groundedLegacyDiagnostic.contains("MaterialTheme.colors.copy"))
        assertTrue(groundedLegacyDiagnostic.contains("import java.io.File"))
        assertTrue(groundedLegacyDiagnostic.contains("class OrchardCircuitBinderTest"))
        assertTrue(groundedLegacyDiagnostic.contains("fun existingSourcePolicyTest"))
        assertTrue(groundedLegacyDiagnostic.contains("val heading = FontFamily.Serif"))
        assertTrue(groundedLegacyDiagnostic.contains("val body = FontFamily.Serif"))
        assertTrue(
            Json.encodeToString(groundedLegacyDiagnostic).encodeToByteArray().size -
                Json.encodeToString(legacyDiagnostic).encodeToByteArray().size < 4_096,
        )
        assertEquals(groundedLegacyDiagnostic, sourceGroundedRetryDiagnostic(groundedLegacyDiagnostic, context))
        assertNull(codingRejectedAnchorDiagnostic(
            proposal,
            attempts,
            runId = 20,
            planId = 23,
            planHash = "a".repeat(64),
            repositoryContext = context,
        ))
    }

    @Test
    fun `diff check rejection preserves bounded proposal for mechanical correction`() {
        val proposal = CodingPatchProposal("Remove serif", listOf(CodingFileOperation(
            action = CODING_FILE_REPLACE,
            path = "src/Theme.kt",
            replacements = listOf(CodingTextReplacement("FontFamily.Serif", "FontFamily.Default")),
        )))

        val diagnostic = codingApplicationDiagnostic("Candidate patch failed git diff --check: trailing whitespace", proposal)

        assertTrue(diagnostic.contains("Prior proposal JSON to correct without redesign"))
        assertTrue(diagnostic.contains("FontFamily.Default"))
        assertEquals(
            "The coding proposal could not be applied: exact anchor missing",
            codingApplicationDiagnostic("exact anchor missing", proposal),
        )
    }

    @Test
    fun `rejected anchor reuse includes literal centered unique suggestions`() {
        val path = "src/Theme.kt"
        val old = "fontFamily = FontFamily.Serif,"
        val attempts = listOf(CodingWorkerAttempt(
            attemptId = 1,
            runId = 19,
            executionPlanId = 23,
            executionPlanHash = "a".repeat(64),
            state = CODING_ATTEMPT_BLOCKED,
            resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
            diagnostic = "REPLACE $path replacement 1 old text occurs 2 times; ${rejectedReplacementAnchor(old)}",
        ))
        val proposal = CodingPatchProposal("Update typography", listOf(CodingFileOperation(
            action = CODING_FILE_REPLACE,
            path = path,
            replacements = listOf(CodingTextReplacement(old, "fontFamily = FontFamily.Default,")),
        )))
        val context = CodingRepositoryContext(listOf(CodingContextFile(
            path = path,
            content = """
                Text(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                )
            """.trimIndent(),
            contentHash = "b".repeat(64),
        )), 0)

        val diagnostic = codingRejectedAnchorDiagnostic(
            proposal, attempts, runId = 19, planId = 23, planHash = "a".repeat(64), repositoryContext = context,
        )

        assertTrue(diagnostic?.contains("fontWeight = FontWeight.Medium,") == true)
        assertTrue(diagnostic?.contains("fontWeight = FontWeight.Bold,") == true)
    }

    @Test
    fun `explicit retry rejects an active coding execution before resolving its plan`() {
        val store = TransientCodingWorkerStore()
        store.append(CodingWorkerEvent(eventId = 1, claim = claim(executionId = 1, runId = 19, attempt = 1)))
        val worker = CodingWorkerService(
            workspace = WorkspaceStore(),
            modelProviders = emptyList(),
            workerStore = store,
        )

        assertEquals(CodingWorkerTickStatus.BUSY, worker.authorizeRetry(19).status)
        assertTrue(worker.attempts().isEmpty())
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
    fun `workspace gateway reverts failed candidate while preserving its revision`() {
        val repository = initializedRepository()
        val gateway = LocalCodingWorkspaceGateway()
        val parent = run(repository, "git", "rev-parse", "HEAD")
        val candidate = gateway.applyAndCommit(
            repository.toString(),
            CodingPatchProposal(
                summary = "Add a candidate implementation.",
                operations = listOf(CodingFileOperation(CODING_FILE_WRITE, "src/Main.kt", "fun answer() = 42\n")),
            ),
            executionId = 9,
        )

        val restored = gateway.revertCandidate(repository.toString(), candidate.revision, executionId = 9)

        assertNotEquals(candidate.revision, restored)
        assertEquals("", run(repository, "git", "diff", "--name-only", parent, restored))
        assertEquals(candidate.revision, run(repository, "git", "rev-parse", "${candidate.revision}^{commit}"))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
        assertFailsWith<IllegalArgumentException> {
            gateway.revertCandidate(repository.toString(), candidate.revision, executionId = 9)
        }
    }

    @Test
    fun `workspace gateway restores an all-failed candidate chain to its pinned base tree`() {
        val repository = initializedRepository()
        val gateway = LocalCodingWorkspaceGateway()
        val base = run(repository, "git", "rev-parse", "HEAD")
        val first = gateway.applyAndCommit(
            repository.toString(),
            CodingPatchProposal("First failed candidate.", listOf(
                CodingFileOperation(CODING_FILE_REPLACE, "src/Main.kt", replacements = listOf(
                    CodingTextReplacement("fun answer() = 1", "fun answer() = 2"),
                )),
            )),
            executionId = 1,
        )
        val second = gateway.applyAndCommit(
            repository.toString(),
            CodingPatchProposal("Second failed candidate.", listOf(
                CodingFileOperation(CODING_FILE_REPLACE, "src/Main.kt", replacements = listOf(
                    CodingTextReplacement("fun answer() = 2", "fun answer() = 3"),
                )),
            )),
            executionId = 2,
        )

        val restored = gateway.restoreTree(repository.toString(), second.revision, base, runId = 19)

        assertEquals("", run(repository, "git", "diff", "--name-only", base, restored))
        assertEquals(first.revision, run(repository, "git", "rev-parse", "${first.revision}^{commit}"))
        assertEquals(second.revision, run(repository, "git", "rev-parse", "${second.revision}^{commit}"))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
        assertFailsWith<IllegalArgumentException> {
            gateway.restoreTree(repository.toString(), second.revision, base, runId = 19)
        }
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
    fun `workspace gateway rejects a no-op replacement before mutating other files`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        val secondary = repository.resolve("src/Secondary.kt")
        Files.writeString(secondary, "fun label() = \"default\"\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add secondary source")

        val error = assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Attempt one change and one no-op.",
                    operations = listOf(
                        CodingFileOperation(
                            action = CODING_FILE_REPLACE,
                            path = "src/Main.kt",
                            replacements = listOf(CodingTextReplacement("fun answer() = 1", "fun answer() = 42")),
                        ),
                        CodingFileOperation(
                            action = CODING_FILE_REPLACE,
                            path = "src/Secondary.kt",
                            replacements = listOf(CodingTextReplacement("default", "default")),
                        ),
                    ),
                ),
                executionId = 11,
            )
        }

        assertEquals(
            "REPLACE src/Secondary.kt does not change file content; no-op replacement indices: 1; " +
                "every replacement new text must differ from its old text",
            error.message,
        )
        assertEquals("fun answer() = 1\n", Files.readString(source))
        assertEquals("fun label() = \"default\"\n", Files.readString(secondary))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway rejects comment-only path coverage before mutating other files`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        val secondary = repository.resolve("src/Secondary.kt")
        Files.writeString(secondary, "fun label() = \"default\"\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add secondary source")

        val error = assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Attempt one implementation change and one placeholder.",
                    operations = listOf(
                        CodingFileOperation(
                            action = CODING_FILE_REPLACE,
                            path = "src/Main.kt",
                            replacements = listOf(CodingTextReplacement("fun answer() = 1", "fun answer() = 42")),
                        ),
                        CodingFileOperation(
                            action = CODING_FILE_REPLACE,
                            path = "src/Secondary.kt",
                            replacements = listOf(CodingTextReplacement(
                                "fun label() = \"default\"",
                                "fun label() = \"default\" // typography update placeholder",
                            )),
                        ),
                    ),
                ),
                executionId = 12,
            )
        }

        assertEquals(
            "REPLACE src/Secondary.kt only changes line comments on unchanged source; " +
                "cosmetic replacement indices: 1; every required operation must change source behavior",
            error.message,
        )
        assertEquals("fun answer() = 1\n", Files.readString(source))
        assertEquals("fun label() = \"default\"\n", Files.readString(secondary))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `workspace gateway rejects reworded comments but preserves double slash string changes`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        Files.writeString(source, "fun endpoint() = \"https://old.example\" // typography verified\n")
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add endpoint")
        val gateway = LocalCodingWorkspaceGateway()

        val error = assertFailsWith<IllegalArgumentException> {
            gateway.applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Reword a placeholder comment.",
                    operations = listOf(CodingFileOperation(
                        action = CODING_FILE_REPLACE,
                        path = "src/Main.kt",
                        replacements = listOf(CodingTextReplacement(
                            "fun endpoint() = \"https://old.example\" // typography verified",
                            "fun endpoint() = \"https://old.example\" // typography confirmed",
                        )),
                    )),
                ),
                executionId = 13,
            )
        }

        assertTrue(requireNotNull(error.message).contains("only changes line comments on unchanged source"))
        gateway.applyAndCommit(
            repository.toString(),
            CodingPatchProposal(
                summary = "Change the endpoint value.",
                operations = listOf(CodingFileOperation(
                    action = CODING_FILE_REPLACE,
                    path = "src/Main.kt",
                    replacements = listOf(CodingTextReplacement(
                        "https://old.example",
                        "https://new.example",
                    )),
                )),
            ),
            executionId = 14,
        )

        assertEquals("fun endpoint() = \"https://new.example\" // typography verified\n", Files.readString(source))
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
    fun `generic test scope does not crowd production owners out of analysis context`() {
        val repository = initializedRepository()
        repeat(110) { index ->
            Files.writeString(
                repository.resolve("src/Unrelated${index}Test.kt"),
                "class Unrelated${index}Test // focused executable tests\n",
            )
        }
        val ownerPath = "src/ConversationAuthority.kt"
        Files.writeString(
            repository.resolve(ownerPath),
            "class ConversationAuthority // append-only conversation authority\n",
        )
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add analysis crowding fixture")

        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(
            repository.toString(),
            "Implement append-only conversation authority and focused executable tests.",
        )

        assertTrue(context.files.any { it.path == ownerPath })
    }

    @Test
    fun `analysis context retains production owner paired by test basename`() {
        val repository = initializedRepository()
        repeat(110) { index ->
            Files.writeString(repository.resolve("src/Distractor$index.kt"), "fun inboxCorrelation$index() = Unit\n")
        }
        Files.createDirectories(repository.resolve("frontend/src/desktopMain/ui"))
        Files.createDirectories(repository.resolve("frontend/src/desktopTest/ui"))
        val owner = "frontend/src/desktopMain/ui/DurableConversationWorkspace.kt"
        Files.writeString(repository.resolve(owner), "fun durableConversationWorkspace() = Unit\n")
        Files.writeString(
            repository.resolve("frontend/src/desktopTest/ui/DurableConversationWorkspaceTest.kt"),
            "class DurableConversationWorkspaceTest\n",
        )
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add affine workspace owner")

        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(
            repository.toString(),
            "Implement cross-project Inbox correlation integration.",
        )

        assertTrue(context.files.any { it.path == owner })
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
    fun `focused excerpts report query literal presence and absence`() {
        val content = buildString {
            repeat(300) { appendLine("val filler$it = $it") }
            appendLine("Text(fontFamily = FontFamily.Monospace)")
            repeat(300) { appendLine("val trailing$it = $it") }
        }

        val excerpt = focusedContextExcerpt(content, setOf("serif", "monospace"), 1_024)

        assertTrue(excerpt.contains("monospace=1"))
        assertTrue(excerpt.contains("serif=0"))
        assertTrue(excerpt.contains("FontFamily.Monospace"))
        assertTrue(excerpt.encodeToByteArray().size <= 1_024)
    }

    @Test
    fun `focused excerpts prioritize present literals over absent query noise`() {
        val content = buildString {
            repeat(300) { appendLine("val filler$it = $it") }
            appendLine("Text(fontFamily = FontFamily.Serif)")
            repeat(300) { appendLine("val trailing$it = $it") }
        }
        val absentTokens = (1..200).mapTo(mutableSetOf()) { "absent$it" }

        val excerpt = focusedContextExcerpt(content, absentTokens + "serif", 512)

        assertTrue(excerpt.contains("serif=1"))
        assertTrue(excerpt.encodeToByteArray().size <= 512)
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

        val error = assertFailsWith<IllegalArgumentException> {
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

        assertTrue(error.message?.startsWith(
            "REPLACE src/Main.kt replacement 1 old text occurs 2 times; expected exactly once; " +
                rejectedReplacementAnchor("fun answer() = 1"),
        ) == true)
        assertTrue(error.message?.contains("exact source-backed unique anchor suggestions") == true)
        assertEquals("fun answer() = 1\nfun answer() = 1\n", Files.readString(source))
        assertEquals("", run(repository, "git", "status", "--porcelain"))
    }

    @Test
    fun `ambiguous replacement diagnostics provide exact unique source anchors`() {
        val content = """
            Text(fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
            )
            Text(fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
            )
        """.trimIndent()

        val diagnostic = ambiguousReplacementAnchorDiagnostic(content, "fontFamily = FontFamily.Serif,")

        assertTrue(diagnostic.contains("fontWeight = FontWeight.Medium,"))
        assertTrue(diagnostic.contains("fontWeight = FontWeight.Bold,"))
        assertTrue(diagnostic.contains("submit one separate replacement for each occurrence"))
        assertTrue(diagnostic.contains("exact source-backed unique anchor suggestions"))
    }

    @Test
    fun `absent replacement diagnostics reject fabricated placeholder anchors`() {
        val diagnostic = replacementAnchorDiagnostic("class ProjectInboxWorkspaceTest", "// Existing test imports and setup")

        assertTrue(diagnostic.contains("old text is absent from pinned source"))
        assertTrue(diagnostic.contains("Do not invent placeholder anchors"))
        assertTrue(diagnostic.contains("copy old text verbatim"))
    }

    @Test
    fun `workspace gateway identifies replacement anchors invalidated by an earlier replacement`() {
        val repository = initializedRepository()
        val source = repository.resolve("src/Main.kt")
        val original = "fun heading() = \"serif\"\nfun label() = \"mono\"\n"
        Files.writeString(source, original)
        run(repository, "git", "add", ".")
        run(repository, "git", "commit", "-m", "Add typography source")

        val error = assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().applyAndCommit(
                repository.toString(),
                CodingPatchProposal(
                    summary = "Attempt overlapping typography replacements.",
                    operations = listOf(CodingFileOperation(
                        action = CODING_FILE_REPLACE,
                        path = "src/Main.kt",
                        replacements = listOf(
                            CodingTextReplacement(original, original.replace("serif", "sans").replace("mono", "sans")),
                            CodingTextReplacement("fun label() = \"mono\"", "fun label() = \"sans\""),
                        ),
                    )),
                ),
                executionId = 12,
            )
        }

        assertEquals(
            "REPLACE src/Main.kt replacement 2 old text occurs 0 times after prior replacements but once in the original source; " +
                "replacements must use non-overlapping anchors ordered from bottom to top; " +
                rejectedReplacementAnchor("fun label() = \"mono\""),
            error.message,
        )
        assertEquals(original, Files.readString(source))
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
    fun `built in Gradle policy uses the cross project check lifecycle`() {
        val repository = initializedRepository()
        val gradle = repository.resolve("gradlew")
        Files.writeString(gradle, "#!/bin/sh\nexit 0\n")
        gradle.toFile().setExecutable(true)
        run(repository, "git", "add", "gradlew")
        run(repository, "git", "commit", "-m", "Add Gradle wrapper")

        val policy = requireNotNull(FileToolchainPolicyCatalog().resolve(repository))

        assertEquals("orchard.default-toolchains", policy.packId)
        assertEquals(2, policy.packVersion)
        assertEquals("gradle-wrapper", policy.profileId)
        assertEquals("./gradlew check --no-daemon", policy.commands.getValue("TEST").canonical())
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

    private fun codingAttempt(
        attemptId: Long,
        state: String,
        diagnostic: String,
        planHash: String,
    ) = CodingWorkerAttempt(
        attemptId = attemptId,
        runId = 19,
        executionPlanId = 23,
        executionPlanHash = planHash,
        state = state,
        resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
        diagnostic = diagnostic,
    )

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
        var resourceDemandInputTokens: Int? = null
        var prompt: String? = null

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

        override fun resourceDemand(
            profile: com.orchard.backend.vector.ModelExecutionProfile,
            inputTokens: Int,
        ): ModelResourceDemand {
            resourceDemandInputTokens = inputTokens
            return ModelResourceDemand(0, 1)
        }

        override suspend fun executeCodingPatch(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            this.prompt = prompt
            this.maxOutputTokens = maxOutputTokens
            this.contextWindowTokens = contextWindowTokens
            return ModelGeneration(output, prompt.length, output.length)
        }
    }
}
