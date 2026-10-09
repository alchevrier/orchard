package com.orchard.backend.company

import com.orchard.backend.workspaceApi
import com.orchard.backend.promoteAcceptedAudit
import com.orchard.backend.analysis.DISPOSITION_PARTIALLY_IMPLEMENTED
import com.orchard.backend.analysis.DISPOSITION_SCAFFOLD_ONLY
import com.orchard.backend.analysis.ExecutionPlanOperation
import com.orchard.backend.analysis.ExecutionPlanScopeCoverage
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.PLAN_OPERATION_VERIFY
import com.orchard.backend.analysis.RepositoryAnalysisPlanContent
import com.orchard.backend.analysis.RepositoryAnalysisCandidate
import com.orchard.backend.analysis.RepositoryAnalysisAttempt
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryAnalysisTickStatus
import com.orchard.backend.analysis.FileRepositoryIntelligenceGraphStore
import com.orchard.backend.analysis.RepositoryIntelligenceImporter
import com.orchard.backend.analysis.RepositoryIntelligenceTraceSpanKind
import com.orchard.backend.analysis.TransientRepositoryIntelligenceTraceStore
import com.orchard.backend.analysis.compactRepositoryContextToBudget
import com.orchard.backend.analysis.TransientRepositoryExecutionPlanStore
import com.orchard.backend.analysis.TransientRepositoryAnalysisAttemptStore
import com.orchard.backend.analysis.TransientExecutableWorkPackageStore
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_BLOCKED
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_RETRY_AUTHORIZED
import com.orchard.backend.analysis.ANALYSIS_ATTEMPT_RUNNING
import com.orchard.backend.analysis.RepositoryEvidenceCitation
import com.orchard.backend.agent.CODING_FILE_REPLACE
import com.orchard.backend.agent.CODING_FILE_WRITE
import com.orchard.backend.agent.BOUNDED_TOOL_REWRITE_FILE
import com.orchard.backend.agent.BoundedCodingToolBatch
import com.orchard.backend.agent.BoundedCodingToolOperation
import com.orchard.backend.agent.CodingFileOperation
import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingPatchProposal
import com.orchard.backend.agent.CodingTextReplacement
import com.orchard.backend.agent.CodingWorkerService
import com.orchard.backend.agent.CodingWorkerTickStatus
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.CandidatePullRequestDispositionService
import com.orchard.backend.agent.CANDIDATE_DISPOSITION_REVIEW_REQUIRED
import com.orchard.backend.agent.CANDIDATE_DISPOSITION_SUPERSEDED
import com.orchard.backend.agent.CodingWorkerAttempt
import com.orchard.backend.agent.CODING_ATTEMPT_BLOCKED
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.agent.TransientCodingWorkerAttemptStore
import com.orchard.backend.agent.TransientCodingWorkerStore
import com.orchard.backend.agent.TransientCandidatePullRequestStore
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.TransientModelProfileSettingsStore
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
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.ProjectGenesisFirstOutcomeStatus
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CompanyCircuitTest {
    @Test
    fun `analysis wire citation contract decodes and validates pinned provenance before plan creation`() = runTest {
        for (mode in listOf("valid", "missing-hash", "stale-hash", "placeholder", "read-only-source", "absent-source")) {
            val state = createTempDirectory("orchard-citation-$mode-")
            val projects = createTempDirectory("orchard-citation-projects-")
            val bindings = FileRepositoryBindingStore(state)
            val workspace = workspace(state, bindings)
            createProjectAndEpic(workspace)
            admitGenesis(workspace)
            workspace.beginBatch()
            assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_STORY,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Prove the primary product journey")))
            assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_TASK,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Implement the first experience slice")))
            workspace.commitBatch()
            assertEquals(com.orchard.backend.workspace.WorkDefinitionStatus.RECORDED, workspace.submitWorkDefinition(4,
                com.orchard.backend.workspace.WorkDefinitionSubmission(
                    requestedOutcome = "The architect can observe one complete governed journey.",
                    currentBehavior = "The admitted product experience has no working user outcome implemented yet.",
                    requiredBehavior = "Start the company -> Observe evidence -> Promote locally",
                    scope = listOf("Implement `build.gradle.kts` product behavior.", "Inspect `README.md` provenance."),
                    nonGoals = listOf("An IDE"), constraints = listOf("Keep all execution and promotion local."),
                    acceptanceCriteria = listOf(com.orchard.backend.workspace.AcceptanceCriterion("The architect can observe one complete governed journey.", "./gradlew test --no-daemon")),
                )).status)
            val scenario = ScenarioStaffModel()
            val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val engine = io.ktor.client.engine.mock.MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                requests += body
                val prompt = body.getValue("prompt").jsonPrimitive.content.substringAfter("<|start|>user<|message|>").substringBeforeLast("<|end|><|start|>assistant")
                val schema = body.getValue("format").jsonObject
                assertTrue(prompt.contains(schema.toString()))
                val envelope = Json.parseToJsonElement(prompt.substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
                val supplied = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
                val projection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), envelope.getValue("attention"))
                val frame = Json.decodeFromJsonElement(com.orchard.backend.attention.AnalysisAttentionFrame.serializer(), com.orchard.backend.attention.resolveCanonicalAttention(projection, supplied))
                val properties = schema.getValue("properties").jsonObject
                val sourceChoices = properties.getValue("sourcePaths").jsonObject.getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(frame.correlations.filter { it.disposition == com.orchard.backend.attention.ATTENTION_DISPOSITION_ANALYSIS_CANDIDATE }.flatMap { it.evidencePaths }.toSet(), sourceChoices)
                val citationChoices = properties.getValue("evidence").jsonObject.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("path").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(frame.correlations.flatMap { it.evidencePaths }.toSet(), citationChoices)
                assertTrue("README.md" in citationChoices)
                assertTrue("README.md" !in sourceChoices, Json.encodeToString(frame))
                assertTrue(listOf("...", "src/Absent.kt").none { it in sourceChoices || it in citationChoices })
                val required = schema.getValue("properties").jsonObject.getValue("evidence").jsonObject.getValue("items").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(setOf("path", "observation", "contentHash"), required)
                val generated = scenario.executeRepositoryAnalysis(prompt, 4_000, 28_000)
                val candidate = Json.parseToJsonElement(generated.text).jsonObject
                val citation = candidate.getValue("evidence").jsonArray.single().jsonObject
                val changed = when (mode) {
                    "missing-hash" -> citation - "contentHash"
                    "stale-hash" -> citation + ("contentHash" to kotlinx.serialization.json.JsonPrimitive("0".repeat(64)))
                    else -> citation
                }
                val citations = mutableListOf(kotlinx.serialization.json.JsonObject(changed))
                val readme = supplied.files.single { it.path == "README.md" }
                citations += Json.encodeToJsonElement(RepositoryEvidenceCitation.serializer(), RepositoryEvidenceCitation(readme.path,
                    observation = "The pinned README remains read-only verification evidence.", contentHash = readme.contentHash)).jsonObject
                val paths = when (mode) {
                    "placeholder" -> listOf("...")
                    "read-only-source" -> listOf("README.md")
                    "absent-source" -> listOf("src/Absent.kt")
                    else -> listOf("build.gradle.kts")
                }
                val output = kotlinx.serialization.json.JsonObject(candidate + mapOf(
                    "evidence" to kotlinx.serialization.json.JsonArray(citations),
                    "sourcePaths" to kotlinx.serialization.json.JsonArray(paths.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )).toString()
                respond("""{"response":${Json.encodeToString(output)},"done":true}""", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            }
            val catalog = com.orchard.backend.vector.defaultLocalModelProviderCatalog()
            val provider = com.orchard.backend.vector.CatalogModelProvider(catalog.endpoints.single(), catalog.bindings.single().copy(model = "gpt-oss:120b"), engine = engine)
            try {
                val company = CompanyControlService(workspace, listOf(provider), FileCompanyControlStore(state), bindings)
                assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
                val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
                val gateway = LocalCodingWorkspaceGateway()
                val revision = gateway.currentRevision(requireNotNull(run.context.repository.path))
                val settings = TransientModelProfileSettingsStore()
                val override = ModelProfileOverride(DefaultModelExecutionProfiles.broadRepositoryAnalysis.id, 24_000, 4_000)
                settings.save(listOf(override))
                val attempts = TransientRepositoryAnalysisAttemptStore()
                val plans = TransientRepositoryExecutionPlanStore()
                val analysis = RepositoryAnalysisService(workspace, listOf(provider), plans, gateway,
                    companyControl = company, attemptStore = attempts, profileSettingsStore = settings)
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { analysis.tick(run.runId) }
                assertEquals(1, requests.size)
                assertEquals(listOf(override), settings.load())
                assertEquals(revision, gateway.currentRevision(requireNotNull(run.context.repository.path)))
                val observation = workspace.modelExecutions().single()
                val valid = mode == "valid"
                assertEquals(valid || mode == "stale-hash", observation.schemaValid)
                assertTrue(observation.outputHash != null)
                assertEquals(if (valid) com.orchard.backend.attention.ContextQualityStatus.PASS else com.orchard.backend.attention.ContextQualityStatus.FAIL,
                    observation.qualityReport?.downstream)
                if (valid) {
                    assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, result.status, result.diagnostic)
                    val plan = requireNotNull(result.plan)
                    val citation = plan.content.evidence.single { it.path == "build.gradle.kts" }
                    val original = scenario.analysisPrompts.single().substringAfter("Authoritative repository analysis envelope:\n")
                    val context = Json.parseToJsonElement(original).jsonObject.getValue("repositoryContext").jsonObject
                    val file = context.getValue("files").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == citation.path }.jsonObject
                    assertEquals(file.getValue("contentHash").jsonPrimitive.content, citation.contentHash)
                    val readmeCitation = plan.content.evidence.single { it.path == "README.md" }
                    val readmeFile = context.getValue("files").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == readmeCitation.path }.jsonObject
                    assertEquals(readmeFile.getValue("contentHash").jsonPrimitive.content, readmeCitation.contentHash)
                    val inspection = plan.content.scopeCoverage.single { it.scope == "Inspect `README.md` provenance." }
                    assertEquals(listOf("README.md"), inspection.evidencePaths)
                    assertTrue(inspection.compliantEvidencePaths.isEmpty())
                    assertTrue(inspection.operationOrders.isNotEmpty())
                    assertTrue(inspection.operationOrders.all { order ->
                        plan.content.operations.single { it.order == order }.action == PLAN_OPERATION_VERIFY
                    })
                    assertTrue(plan.content.operations.none { it.path == "README.md" })
                } else {
                    assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, result.status, result.diagnostic)
                    val diagnostic = when (mode) {
                        "missing-hash" -> "contentHash"
                        "stale-hash" -> "wrong content hash"
                        else -> "candidate Attention evidence"
                    }
                    assertTrue(result.diagnostic.contains(diagnostic), result.diagnostic)
                    assertEquals(null, result.plan)
                    assertTrue(plans.load().isEmpty())
                    assertEquals(ANALYSIS_ATTEMPT_BLOCKED, attempts.load().last().state)
                }
            } finally {
                provider.close()
            }
        }
    }

    @Test
    fun `real pinned collection and analysis admission share complete Kotlin evidence contract`() = runTest {
        for (hasBehavior in listOf(true, false)) {
            val state = createTempDirectory("orchard-collected-evidence-")
            val projects = createTempDirectory("orchard-collected-projects-")
            val bindings = FileRepositoryBindingStore(state)
            val workspace = workspace(state, bindings)
            createProjectAndEpic(workspace)
            admitGenesis(workspace, repositoryPath = "build.gradle.kts")
            val model = RejectingAnalysisModel()
            val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
            assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
            val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
            val workspacePath = requireNotNull(run.context.repository.path)
            val entries = (1..250).joinToString(",\n") { "    STATUS_$it" }
            val source = "enum class Status {\n$entries\n}\n" + if (hasBehavior) "class Owner {\n    fun answer(): Int {\n        return 42\n    }\n}\n" else ""
            Files.writeString(Path.of(workspacePath).resolve("build.gradle.kts"), source)
            val commit = ProcessBuilder("git", "-C", workspacePath, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test",
                "commit", "-am", "Pinned evidence fixture").redirectErrorStream(true).start()
            assertTrue(commit.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, commit.exitValue(), commit.inputStream.bufferedReader().use { it.readText() })
            val local = LocalCodingWorkspaceGateway()
            val revision = requireNotNull(local.currentRevision(workspacePath))
            val gateway = object : CodingWorkspaceGateway by local {
                override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>): CodingRepositoryContext {
                    val collected = local.collectPlanContext(workspacePath, revision, listOf("build.gradle.kts"), query, 768)
                    return collected.copy(files = collected.files.map { file -> file.copy(
                        matchedEvidenceSelectorIds = selectors.filter { file.path in it.pathGlobs }.map { it.selectorId },
                    ) })
                }
            }
            val settings = TransientModelProfileSettingsStore()
            val override = ModelProfileOverride(DefaultModelExecutionProfiles.broadRepositoryAnalysis.id, 24_000, 4_000)
            settings.save(listOf(override))
            val attempts = TransientRepositoryAnalysisAttemptStore()
            val analysis = RepositoryAnalysisService(workspace, listOf(model), TransientRepositoryExecutionPlanStore(), gateway,
                companyControl = company, attemptStore = attempts, profileSettingsStore = settings)
            val result = analysis.tick(run.runId)
            assertEquals(listOf(override), settings.load())
            assertEquals(revision, local.currentRevision(workspacePath))
            val attempt = attempts.load().single { it.contextSelection != null }
            assertEquals(revision, attempt.baseRevision)
            val report = requireNotNull(attempt.contextSelection?.qualityReport)
            if (hasBehavior) {
                assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, result.status, result.diagnostic)
                assertEquals(1, model.analysisCallCount)
                val envelope = Json.parseToJsonElement(model.analysisPrompts.single().substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
                val supplied = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
                assertEquals(null, com.orchard.backend.agent.repositoryContextAdequacyDiagnostic(supplied))
                val file = supplied.files.single()
                assertTrue(file.content.contains("return 42"))
                assertTrue(file.content.contains("// Orchard source lines "))
                assertEquals(CodingContextFile(file.path, source).contentHash, file.contentHash)
                assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, report.adequacy)
            } else {
                assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, result.status, result.diagnostic)
                assertTrue(result.diagnostic.contains("SOURCE_EVIDENCE_INADEQUATE"), result.diagnostic)
                assertEquals(0, model.analysisCallCount)
                assertEquals(null, attempt.promptHash)
                assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, report.adequacy)
                assertEquals(com.orchard.backend.attention.ContextQualityStatus.NOT_ATTEMPTED, report.downstream)
            }
        }
    }

    @Test
    fun `governed coding rejects oversized typed batch before repository mutation`() = runTest {
        val state = createTempDirectory("orchard-typed-batch-")
        val projects = createTempDirectory("orchard-typed-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val staff = ScenarioStaffModel(oversizedCodingBatch = true)
        val company = CompanyControlService(workspace, listOf(staff), FileCompanyControlStore(state), bindings)
        assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
        val gateway = LocalCodingWorkspaceGateway()
        val analysis = RepositoryAnalysisService(workspace, listOf(staff), TransientRepositoryExecutionPlanStore(), gateway, companyControl = company)
        val worker = CodingWorkerService(workspace, listOf(staff), TransientCodingWorkerStore(), gateway, companyControl = company, repositoryAnalysis = analysis)
        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        val workspacePath = workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.repository.path
        val revision = gateway.currentRevision(workspacePath)
        val buildFile = Files.readString(Path.of(workspacePath).resolve("build.gradle.kts"))
        val result = worker.tick()
        assertEquals(CodingWorkerTickStatus.INVALID_PROPOSAL, result.status)
        assertTrue(requireNotNull(result.execution?.result?.diagnostic).contains("operations has 13 items; at most 12"))
        assertTrue(staff.codingPrompts.single().contains(com.orchard.backend.vector.ModelOutputContract.BOUNDED_CODING_TOOL_BATCH.instruction))
        assertEquals(revision, gateway.currentRevision(workspacePath))
        assertEquals(buildFile, Files.readString(Path.of(workspacePath).resolve("build.gradle.kts")))
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, workspace.modelExecutions().last().qualityReport?.downstream)
    }

    @Test
    fun `governed coding retains read-only verification evidence without granting mutation authority`() = runTest {
        val state = createTempDirectory("orchard-read-only-evidence-")
        val projects = createTempDirectory("orchard-read-only-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_STORY,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Prove the primary product journey")))
        assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_TASK,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Implement the first experience slice")))
        workspace.commitBatch()
        assertEquals(com.orchard.backend.workspace.WorkDefinitionStatus.RECORDED, workspace.submitWorkDefinition(4,
            com.orchard.backend.workspace.WorkDefinitionSubmission(
                requestedOutcome = "The architect can observe one complete governed journey.",
                currentBehavior = "The admitted product experience has no working user outcome implemented yet.",
                requiredBehavior = "Start the company -> Observe evidence -> Promote locally",
                scope = listOf("Implement `build.gradle.kts` product behavior.", "Verify that `README.md` remains unchanged."),
                nonGoals = listOf("An IDE"), constraints = listOf("Keep all execution and promotion local."),
                acceptanceCriteria = listOf(com.orchard.backend.workspace.AcceptanceCriterion("The architect can observe one complete governed journey.", "./gradlew test --no-daemon")),
            )).status)
        val staff = ScenarioStaffModel()
        val company = CompanyControlService(workspace, listOf(staff), FileCompanyControlStore(state), bindings)
        assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
        val gateway = LocalCodingWorkspaceGateway()
        val analysis = RepositoryAnalysisService(workspace, listOf(staff), TransientRepositoryExecutionPlanStore(), gateway, companyControl = company)
        val worker = CodingWorkerService(workspace, listOf(staff), TransientCodingWorkerStore(), gateway, companyControl = company, repositoryAnalysis = analysis)
        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        val analysisResult = analysis.tick()
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysisResult.status, analysisResult.diagnostic)
        val workspacePath = workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.repository.path
        val revision = gateway.currentRevision(workspacePath)
        val readme = Files.readString(Path.of(workspacePath).resolve("README.md"))
        val codingResult = worker.tick()
        assertEquals(CodingWorkerTickStatus.INVALID_PROPOSAL, codingResult.status, codingResult.execution?.result?.diagnostic)
        val envelope = Json.parseToJsonElement(staff.codingPrompts.single().substringAfter("Authoritative coding execution envelope:\n")).jsonObject
        val projection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), envelope.getValue("attention"))
        val context = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
        val frame = com.orchard.backend.attention.resolveCanonicalAttention(projection, context)
        assertTrue(context.files.any { it.path == "README.md" })
        val readOnly = frame.getValue("correlations").jsonArray.single {
            it.jsonObject.getValue("disposition").jsonPrimitive.content == "EVIDENCE_ONLY" &&
                it.jsonObject.getValue("ownerPaths").jsonArray.any { owner -> owner.jsonPrimitive.content == "README.md" }
        }.jsonObject
        assertTrue(readOnly.getValue("allowedActions").jsonArray.isEmpty())
        assertTrue(frame.getValue("ownershipPaths").jsonArray.none { it.jsonPrimitive.content == "README.md" })
        assertTrue(requireNotNull(codingResult.execution?.result?.diagnostic).contains("README.md"))
        assertEquals(revision, gateway.currentRevision(workspacePath))
        assertEquals(readme, Files.readString(Path.of(workspacePath).resolve("README.md")))
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, workspace.modelExecutions().last().qualityReport?.downstream)
    }

    @Test
    fun `analysis compaction retains producer consumer and regression evidence or blocks dispatch`() = runTest {
        for (oversizedBody in listOf(false, true)) {
            val state = createTempDirectory("orchard-dependency-context-")
            val projects = createTempDirectory("orchard-dependency-projects-")
            val bindings = FileRepositoryBindingStore(state)
            val workspace = workspace(state, bindings)
            createProjectAndEpic(workspace)
            admitGenesis(workspace)
            workspace.beginBatch()
            assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_STORY,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Prove the primary product journey")))
            assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_TASK,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Implement the first experience slice")))
            workspace.commitBatch()
            val paths = listOf("src/Producer.kt", "src/Consumer.kt", "src/ConsumerTest.kt")
            assertEquals(com.orchard.backend.workspace.WorkDefinitionStatus.RECORDED, workspace.submitWorkDefinition(4,
                com.orchard.backend.workspace.WorkDefinitionSubmission(
                    requestedOutcome = "The architect can observe one complete governed journey.",
                    currentBehavior = "The admitted product experience has no working user outcome implemented yet.",
                    requiredBehavior = "Start the company -> Observe evidence -> Promote locally",
                    scope = listOf("Implement `${paths[0]}` producer behavior.", "Implement `${paths[1]}` consumer behavior.", "Implement `${paths[2]}` regression coverage."),
                    nonGoals = listOf("An IDE"), constraints = listOf("Keep all execution and promotion local."),
                    acceptanceCriteria = listOf(com.orchard.backend.workspace.AcceptanceCriterion("The architect can observe one complete governed journey.", "./gradlew test --no-daemon")),
                )).status)
            val model = RejectingAnalysisModel()
            val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
            assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
            val producer = if (oversizedBody) "fun produce(): Int {\n" + "    println(42)\n".repeat(10_000) + "    return 42\n}\n" else "fun produce() = 42\n"
            val preamble = (1..4_000).joinToString("\n") { "import example.Dependency$it" } + "\n"
            val originals = CodingRepositoryContext(listOf(
                CodingContextFile(paths[0], preamble + producer),
                CodingContextFile(paths[1], preamble + "fun consume() = produce() + 1\n"),
                CodingContextFile(paths[2], preamble + "fun regression() { check(consume() == 43) }\n"),
            ), 0)
            val anchors = com.orchard.backend.agent.repositoryEvidenceAnchors(originals, paths.toSet())
            if (!oversizedBody) {
                val excerpts = originals.copy(files = originals.files.map { it.copy(content = com.orchard.backend.agent.contextFileExcerpt(it, setOf("produce", "consume", "regression"), 1_024)) })
                assertEquals(anchors, com.orchard.backend.agent.repositoryEvidenceAnchors(excerpts, paths.toSet()))
                assertEquals(null, com.orchard.backend.agent.repositoryEvidenceRetentionDiagnostic(anchors, excerpts))
            }
            val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
                override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>) =
                    originals.copy(files = originals.files.map { file -> file.copy(
                        matchedEvidenceSelectorIds = selectors.filter { file.path in it.pathGlobs }.map { it.selectorId },
                    ) })
            }
            val attempts = TransientRepositoryAnalysisAttemptStore()
            val analysis = RepositoryAnalysisService(workspace, listOf(model), TransientRepositoryExecutionPlanStore(), gateway,
                companyControl = company, attemptStore = attempts)
            val result = analysis.tick(workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId)
            val selection = requireNotNull(attempts.load().single { it.contextSelection != null }.contextSelection)
            val report = requireNotNull(selection.qualityReport)
            assertTrue(report.transformations.any { it.stage == "required-source-compaction" })
            if (oversizedBody) {
                assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, result.status, result.diagnostic)
                assertTrue(result.diagnostic.contains("SOURCE_EVIDENCE_INADEQUATE"), result.diagnostic)
                assertEquals(0, model.analysisCallCount)
                assertTrue(report.transformations.none { it.fits && it.adequate })
            } else {
                assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, result.status, result.diagnostic)
                val envelope = Json.parseToJsonElement(model.analysisPrompts.single().substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
                val supplied = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
                assertEquals(paths.toSet(), supplied.files.map { it.path }.toSet())
                assertEquals(null, com.orchard.backend.agent.repositoryEvidenceRetentionDiagnostic(anchors, supplied))
                assertTrue(supplied.files.sumOf { it.content.length } < originals.files.sumOf { it.content.length })
                assertEquals(originals.files.map { it.contentHash }, supplied.files.map { it.contentHash })
                assertTrue(supplied.files.all { it.content.contains("// Orchard source lines") })
                assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, report.adequacy)
                assertEquals(1, model.analysisCallCount)
            }
        }
    }

    @Test
    fun `analysis admission uses final raw provider accounting without changing explicit apertures`() = runTest {
        for (format in listOf("raw", "mismatch", "automatic", "oversized", "native")) {
            val state = createTempDirectory("orchard-accounting-$format-")
            val projects = createTempDirectory("orchard-accounting-projects-")
            val bindings = FileRepositoryBindingStore(state)
            val workspace = workspace(state, bindings)
            createProjectAndEpic(workspace)
            admitGenesis(workspace)
            val requests = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val engine = io.ktor.client.engine.mock.MockEngine { request ->
                requests += Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                val output = if (format == "oversized") Json.encodeToString(RepositoryAnalysisCandidate(
                    disposition = DISPOSITION_SCAFFOLD_ONLY, summary = "An oversized candidate.", evidence = emptyList(), reuse = emptyList(),
                    preservedInvariants = emptyList(), nonGoals = emptyList(), sourcePaths = List(13) { "build.gradle.kts" }, unresolvedQuestions = emptyList(),
                )) else "{}"
                respond("""{"response":${Json.encodeToString(output)},"done":true}""", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            }
            val catalog = com.orchard.backend.vector.defaultLocalModelProviderCatalog()
            val configuration = catalog.bindings.single().configuration + when (format) {
                "raw", "oversized" -> mapOf("input.format" to "raw")
                "mismatch" -> mapOf("input.format" to "raw", "tokenizer.model" to "different-model")
                "native" -> emptyMap()
                else -> mapOf("input.format" to "automatic")
            }
            val provider = com.orchard.backend.vector.CatalogModelProvider(catalog.endpoints.single(),
                catalog.bindings.single().copy(model = "gpt-oss:120b", configuration = configuration), engine = engine)
            try {
                val company = CompanyControlService(workspace, listOf(provider), FileCompanyControlStore(state), bindings)
                assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
                val settings = TransientModelProfileSettingsStore()
                val override = ModelProfileOverride(DefaultModelExecutionProfiles.broadRepositoryAnalysis.id, 8_000, 4_000)
                settings.save(listOf(override))
                val attempts = TransientRepositoryAnalysisAttemptStore()
                val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
                    override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>): CodingRepositoryContext {
                        val collected = LocalCodingWorkspaceGateway().collectAnalysisContext(workspacePath, query, selectors)
                        return collected.copy(files = collected.files.map { file ->
                            val content = file.content + "\n// \u4f60\u597d \uD83D\uDE80 hello world\n"
                            file.copy(content = content, contentHash = CodingContextFile(file.path, content).contentHash)
                        })
                    }
                }
                val service = RepositoryAnalysisService(workspace, listOf(provider), TransientRepositoryExecutionPlanStore(), gateway,
                    companyControl = company, attemptStore = attempts, profileSettingsStore = settings)
                val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { service.tick(runId) }
                val records = attempts.load()
                assertEquals(ANALYSIS_ATTEMPT_BLOCKED, records.last().state)
                val selection = requireNotNull(records.single { it.runId == runId && it.contextSelection != null }.contextSelection)
                assertEquals(listOf(override), settings.load())
                assertEquals(5_600, selection.modelInputBudgetTokens)
                val metadata = requireNotNull(selection.minimumEnvelopeAccounting)
                assertTrue(metadata.contentBytes > selection.modelInputBudgetTokens, metadata.toString())
                if (format == "raw" || format == "oversized" || format == "native") {
                    assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, result.status, result.diagnostic)
                    assertEquals(listOf(ANALYSIS_ATTEMPT_RUNNING, ANALYSIS_ATTEMPT_BLOCKED), records.map { it.state })
                    assertTrue(metadata.totalTokens < selection.modelInputBudgetTokens)
                    val request = requests.single()
                    assertEquals("true", request.getValue("raw").jsonPrimitive.content)
                    val wirePrompt = request.getValue("prompt").jsonPrimitive.content
                    val prompt = if (format == "native") wirePrompt.substringAfter("<|start|>user<|message|>").substringBeforeLast("<|end|><|start|>assistant") else wirePrompt
                    assertTrue(prompt.contains("\u4f60\u597d"))
                    val contract = com.orchard.backend.vector.ModelOutputContract.REPOSITORY_ANALYSIS_CANDIDATE
                    assertTrue(prompt.contains(request.getValue("format").toString()))
                    val schemaProperties = request.getValue("format").jsonObject.getValue("properties").jsonObject
                    contract.arrayLimits.forEach { (field, limit) ->
                        assertEquals(limit.toString(), schemaProperties.getValue(field).jsonObject.getValue("maxItems").jsonPrimitive.content)
                    }
                    if (format == "oversized") {
                        assertTrue(result.diagnostic.contains("sourcePaths has 13 items; at most 12"), result.diagnostic)
                        assertTrue(service.plans().isEmpty())
                    }
                    val accounting = com.orchard.backend.vector.accountModelInput(prompt, provider.bindingProfile())
                    assertEquals(selection.tokenAccounting, accounting)
                    if (format == "native") {
                        assertEquals(com.orchard.backend.vector.MODEL_INPUT_FORMAT_GPT_OSS_HARMONY, accounting.providerFormat)
                        assertTrue(accounting.providerOverheadTokens > 2)
                        assertEquals(wirePrompt, requireNotNull(com.orchard.backend.vector.formatModelInput(prompt, provider.bindingProfile())).prompt)
                        assertTrue(wirePrompt.startsWith("<|start|>system<|message|>"))
                        assertEquals(wirePrompt.encodeToByteArray().size, accounting.serializedInputBytes)
                        assertEquals(com.orchard.backend.workspace.stagedPlanHash(wirePrompt), accounting.serializedInputHash)
                        assertTrue("input.format" !in configuration)
                    } else {
                        assertEquals(0, accounting.providerOverheadTokens)
                        assertEquals("raw-input-v1", accounting.providerFormat)
                    }
                    assertTrue(accounting.contentBytes > accounting.contentTokens)
                    val observation = workspace.modelExecutions().single()
                    assertEquals(accounting, observation.inputAccounting)
                    assertEquals(accounting.totalTokens, observation.inputTokens)
                    val envelope = Json.parseToJsonElement(prompt.substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
                    val projection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), envelope.getValue("attention"))
                    val context = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
                    val frame = com.orchard.backend.attention.resolveCanonicalAttention(projection, context)
                    assertEquals(workspace.snapshot(MESSAGE_READY).workflowRuns.single().workDefinition?.hash,
                        frame.getValue("objective").jsonObject.getValue("sourceHash").jsonPrimitive.content)
                    assertEquals(projection, observation.qualityReport?.references)
                    assertEquals("4000", request.getValue("options").jsonObject.getValue("num_predict").jsonPrimitive.content)
                    assertEquals("12000", request.getValue("options").jsonObject.getValue("num_ctx").jsonPrimitive.content)
                } else {
                    assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, result.status, result.diagnostic)
                    assertTrue(requests.isEmpty())
                    assertTrue(workspace.modelExecutions().isEmpty())
                    if (format == "automatic") {
                        assertTrue(result.diagnostic.contains("PROVIDER_FORMAT_UNAVAILABLE"), result.diagnostic)
                        assertEquals(false, selection.tokenAccounting?.capacityEstablished)
                    } else {
                        assertEquals(com.orchard.backend.vector.MODEL_TOKEN_COUNT_BYTE_FALLBACK, selection.tokenAccounting?.method)
                        assertEquals(metadata.contentBytes, metadata.totalTokens)
                    }
                }
            } finally {
                provider.close()
            }
        }
    }

    @Test
    fun `analysis dispatch preserves every admitted coordinate when source is deferred by budget`() = runTest {
        val state = createTempDirectory("orchard-large-scope-state-")
        val projects = createTempDirectory("orchard-large-scope-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        val paths = (1..100).map { "src/Owner$it.kt" }
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_STORY,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, title = "Prove the primary product journey")))
        assertTrue(workspace.applyIntent(DocumentIntent(actionTypeId = ACTION_CREATE, entityTypeId = com.orchard.backend.workspace.ENTITY_TASK,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID, projectId = 1, epicId = 2, storyId = 3, title = "Implement the first experience slice")))
        workspace.commitBatch()
        val definitionResult = workspace.submitWorkDefinition(4, com.orchard.backend.workspace.WorkDefinitionSubmission(
            requestedOutcome = "The architect can observe one complete governed journey.",
            currentBehavior = "The admitted product experience has no working user outcome implemented yet.",
            requiredBehavior = "Start the company -> Observe evidence -> Promote locally",
            scope = paths.chunked(10).map { group -> "Implement ${group.joinToString(" ") { "`$it`" }}." },
            nonGoals = listOf("An IDE"), constraints = listOf("Keep all execution and promotion local."),
            acceptanceCriteria = listOf(com.orchard.backend.workspace.AcceptanceCriterion("The architect can observe one complete governed journey.", "./gradlew test --no-daemon")),
        ))
        assertEquals(com.orchard.backend.workspace.WorkDefinitionStatus.RECORDED, definitionResult.status)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val started = CompanyCircuitService(workspace, company, projects).start(1)
        assertEquals(CompanyCircuitStatus.STARTED, started.status, started.diagnostic)
        val context = CodingRepositoryContext(paths.mapIndexed { index, path ->
            CodingContextFile(path, "fun owner$index() = $index\n" + "// context padding\n".repeat(60))
        }, 0)
        val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
            override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>): CodingRepositoryContext {
                assertTrue(selectors.all { it.contentLiterals.isEmpty() && it.pathGlobs.all(paths::contains) })
                return context.copy(files = context.files.map { file ->
                    file.copy(matchedEvidenceSelectorIds = selectors.filter { file.path in it.pathGlobs }.map { it.selectorId })
                })
            }
        }
        val attempts = TransientRepositoryAnalysisAttemptStore()
        val analysis = RepositoryAnalysisService(workspace, listOf(model), TransientRepositoryExecutionPlanStore(), gateway, companyControl = company, attemptStore = attempts)
        val admittedRun = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val result = analysis.tick(admittedRun.runId)
        val compilation = attempts.load().lastOrNull()?.contextSelection
        assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, result.status,
            "${result.diagnostic}; allowance=${compilation?.qualityReport?.inputAllowanceTokens}; metadata=${compilation?.minimumEnvelopeAccounting?.totalTokens}; " +
                "transformations=${compilation?.qualityReport?.transformations?.map { Triple(it.stage, it.fits, it.adequate) }}")
        val envelope = Json.parseToJsonElement(model.analysisPrompts.single().substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
        val projection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), envelope.getValue("attention"))
        val supplied = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
        val frame = Json.decodeFromJsonElement(com.orchard.backend.attention.AnalysisAttentionFrame.serializer(), com.orchard.backend.attention.resolveCanonicalAttention(projection, supplied))
        assertEquals(requireNotNull(admittedRun.workDefinition).definition.scope, frame.correlations.map { it.scope.text })
        assertEquals(paths.toSet(), frame.correlations.flatMap { it.coordinatePaths }.toSet())
        val evidence = frame.correlations.flatMap { it.evidencePaths }.toSet()
        val deferred = frame.correlations.flatMap { it.deferredPaths }.toSet()
        assertEquals(supplied.files.map { it.path }.toSet(), evidence)
        assertTrue(deferred.isNotEmpty())
        assertTrue(evidence.intersect(deferred).isEmpty())
        assertEquals(paths.toSet(), evidence + deferred)
        assertTrue(requireNotNull(com.orchard.backend.attention.analysisAttentionCandidateDiagnostic(frame, emptyList(), listOf(deferred.first()))).contains("deferred or absent"))
        val report = requireNotNull(attempts.load().first().contextSelection?.qualityReport)
        assertTrue(Json.encodeToString(context).encodeToByteArray().size > report.inputAllowanceTokens)
        assertEquals(deferred, report.deferredPaths.toSet())
        assertEquals(projection, report.references)
        assertEquals(1, model.analysisCallCount)
    }

    @Test
    fun `missing repository source persists failed adequacy without overflow or inference`() = runTest {
        val state = createTempDirectory("orchard-missing-evidence-state-")
        val projects = createTempDirectory("orchard-missing-evidence-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace, repositoryPath = "build.gradle.kts")
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
        val attempts = com.orchard.backend.analysis.FileRepositoryAnalysisAttemptStore(state)
        val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
            override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>) = CodingRepositoryContext(emptyList(), 0)
        }
        val analysis = RepositoryAnalysisService(workspace, listOf(model), TransientRepositoryExecutionPlanStore(), gateway, companyControl = company, attemptStore = attempts)
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId
        val result = analysis.tick(runId)
        assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, result.status)
        assertEquals(0, model.analysisCallCount)
        val attempt = attempts.load().single()
        assertEquals(null, attempt.promptHash)
        val report = requireNotNull(attempt.contextSelection?.qualityReport)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, report.adequacy)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.UNKNOWN, report.capacity)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.NOT_ATTEMPTED, report.downstream)
        assertTrue(report.diagnostics.any { it.contains("SOURCE_EVIDENCE_EMPTY") })
        assertTrue("build.gradle.kts" in report.missingPaths, report.missingPaths.toString())
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(runId).status)
        assertEquals(1, attempts.load().size)
        val ledger = state.resolve("repository-analysis-attempts.jsonl")
        val beforeRestart = Files.readAllBytes(ledger)
        val recoveredAttempts = com.orchard.backend.analysis.FileRepositoryAnalysisAttemptStore(state)
        assertEquals(attempt, recoveredAttempts.load().single())
        assertEquals(com.orchard.backend.attention.contextQualityReportHash(report),
            com.orchard.backend.attention.contextQualityReportHash(requireNotNull(recoveredAttempts.load().single().contextSelection?.qualityReport)))
        val recovered = RepositoryAnalysisService(workspace(state, bindings), listOf(model), TransientRepositoryExecutionPlanStore(), gateway, attemptStore = recoveredAttempts)
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, recovered.tick(runId).status)
        assertEquals(0, model.analysisCallCount)
        assertTrue(beforeRestart.contentEquals(Files.readAllBytes(ledger)))
    }

    @Test
    fun `analysis rejects header-only source before dispatch despite generous input aperture`() = runTest {
        val state = createTempDirectory("orchard-header-evidence-state-")
        val projects = createTempDirectory("orchard-header-evidence-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
        val attempts = TransientRepositoryAnalysisAttemptStore()
        val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
            override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>) =
                CodingRepositoryContext(listOf(CodingContextFile("build.gradle.kts", "import example.Owner\nclass Owner {", "a".repeat(64))), 0)
        }
        val analysis = RepositoryAnalysisService(workspace, listOf(model), TransientRepositoryExecutionPlanStore(), gateway, companyControl = company, attemptStore = attempts)
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId
        val result = analysis.tick(runId)
        assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, result.status, result.diagnostic)
        assertEquals(0, model.analysisCallCount)
        val attempt = attempts.load().single()
        assertEquals(null, attempt.promptHash)
        val report = requireNotNull(attempt.contextSelection?.qualityReport)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, report.adequacy)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.NOT_ATTEMPTED, report.downstream)
        assertTrue(report.diagnostics.any { it.contains("SOURCE_EVIDENCE_INADEQUATE") }, report.diagnostics.toString())
        assertTrue(report.inputAllowanceTokens > 1_000)
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(runId).status)
        assertEquals(1, attempts.load().size)
    }

    @Test
    fun `governed coding rejects header-only source before model dispatch`() = runTest {
        val state = createTempDirectory("orchard-coding-header-state-")
        val projects = createTempDirectory("orchard-coding-header-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val staff = ScenarioStaffModel()
        val company = CompanyControlService(workspace, listOf(staff), FileCompanyControlStore(state), bindings)
        assertEquals(CompanyCircuitStatus.STARTED, CompanyCircuitService(workspace, company, projects).start(1).status)
        val analysis = RepositoryAnalysisService(workspace, listOf(staff), TransientRepositoryExecutionPlanStore(), LocalCodingWorkspaceGateway(), companyControl = company)
        val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
            override fun collectPlanContext(workspacePath: String, repositoryRevision: String, paths: List<String>, query: String, maxSerializedBytes: Int) =
                CodingRepositoryContext(paths.map { CodingContextFile(it, "import example.Owner\nclass Owner {", "a".repeat(64)) }, 0)
        }
        val worker = CodingWorkerService(workspace, listOf(staff), TransientCodingWorkerStore(), gateway, companyControl = company, repositoryAnalysis = analysis)
        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        val result = worker.tick()
        assertEquals(CodingWorkerTickStatus.APPLICATION_FAILED, result.status, result.execution?.result?.diagnostic)
        assertTrue(requireNotNull(result.execution?.result?.diagnostic).contains("SOURCE_EVIDENCE_INADEQUATE"))
        assertEquals(0, staff.codingCallCount)
        assertTrue(staff.codingPrompts.isEmpty())
    }

    @Test
    fun `fixed metadata floor exceeds byte fallback but fits supported token admission`() {
        val context = CodingRepositoryContext(listOf(CodingContextFile("src/Owner.kt", "fun answer() = 42\n")), 0)
        val binding = ModelBindingProfile("test", "ollama", "gpt-oss:120b", 131_072, emptySet())
        fun render(candidate: CodingRepositoryContext) = kotlinx.serialization.json.buildJsonObject {
            put("task", kotlinx.serialization.json.JsonPrimitive("Preserve admitted authority. ".repeat(1_000)))
            put("repositoryContext", Json.parseToJsonElement(Json.encodeToString(candidate)))
        }.toString()
        val metadataOnly = render(context.copy(files = context.files.map { it.copy(content = "") }))
        assertTrue(com.orchard.backend.vector.accountModelInput(metadataOnly, binding).totalTokens < 12_000)
        assertTrue(com.orchard.backend.vector.accountModelInput(metadataOnly, binding.copy(model = "unknown")).totalTokens > 12_000)
        val supported = compactRepositoryContextToBudget(
            context, 12_000, setOf("src/Owner.kt"),
            tokenCounter = { com.orchard.backend.vector.accountModelInput(it, binding).totalTokens },
            contextAdequacy = { com.orchard.backend.agent.repositoryContextAdequacyDiagnostic(it) == null },
            promptFor = ::render,
        )
        assertEquals(context, supported)
        val fallback = compactRepositoryContextToBudget(
            context, 12_000, setOf("src/Owner.kt"),
            tokenCounter = { com.orchard.backend.vector.accountModelInput(it, binding.copy(model = "unknown")).totalTokens },
            promptFor = ::render,
        )
        assertEquals(null, fallback)
    }

    @Test
    fun `analysis compaction uses model tokens rather than source bytes for a supported binding`() {
        val source = "class Owner { fun answer() = 42 }\n" + "// existing behavior\n".repeat(1_500)
        val context = CodingRepositoryContext(listOf(CodingContextFile("src/Owner.kt", source)), 0)
        val binding = com.orchard.backend.vector.ModelBindingProfile("test", "ollama", "gpt-oss:120b", 131_072, emptySet())
        val compacted = compactRepositoryContextToBudget(
            context,
            12_000,
            setOf("src/Owner.kt"),
            tokenCounter = { com.orchard.backend.vector.accountModelInput(it, binding).totalTokens },
        ) { Json.encodeToString(it) }

        assertTrue(Json.encodeToString(context).encodeToByteArray().size > 12_000)
        assertEquals(context, compacted)
    }

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
    fun `repository analysis retains required evidence beyond the budgeted prefix`() {
        val context = CodingRepositoryContext(
            files = (1..6).map { index -> CodingContextFile("src/$index.kt", "x".repeat(400)) },
            omittedFileCount = 0,
        )
        val requiredPaths = setOf("src/5.kt", "src/6.kt")
        val threeFileBudget = estimateModelTokens(Json.encodeToString(context.copy(
            files = listOf(context.files[0], context.files[4], context.files[5]),
            omittedFileCount = 3,
        )))

        val compacted = requireNotNull(compactRepositoryContextToBudget(context, threeFileBudget, requiredPaths) { candidate ->
            Json.encodeToString(candidate)
        })

        assertEquals(listOf("src/1.kt", "src/5.kt", "src/6.kt"), compacted.files.map { it.path })
        assertEquals(3, compacted.omittedFileCount)
    }

    @Test
    fun `repository analysis compacts declarations before required evidence`() {
        val declarations = (1..8).map { index -> "private fun RequiredOwner$index() = Unit" }
        val context = CodingRepositoryContext(
            files = (1..2).map { index ->
                CodingContextFile("src/Required$index.kt", "focused owner $index", matchedDeclarations = declarations)
            },
            omittedFileCount = 0,
        )
        val requiredPaths = context.files.mapTo(hashSetOf()) { it.path }
        val oneDeclarationBudget = estimateModelTokens(Json.encodeToString(context.copy(
            files = context.files.map { it.copy(matchedDeclarations = declarations.take(1)) },
        )))

        val compacted = requireNotNull(
            compactRepositoryContextToBudget(context, oneDeclarationBudget, requiredPaths) { Json.encodeToString(it) }
        )

        assertEquals(context.files.map { it.path }, compacted.files.map { it.path })
        assertEquals(listOf(declarations.first()), compacted.files.first().matchedDeclarations)
        assertEquals(0, compacted.omittedFileCount)
    }

    @Test
    fun `repository analysis compacts required evidence excerpts after declarations`() {
        val context = CodingRepositoryContext(
            files = (1..2).map { index ->
                CodingContextFile("src/Required$index.kt", "owner $index\n" + "x".repeat(20_000))
            },
            omittedFileCount = 0,
        )
        val requiredPaths = context.files.mapTo(hashSetOf()) { it.path }
        val budget = estimateModelTokens(Json.encodeToString(context.copy(
            files = context.files.map { it.copy(content = it.content.take(1_000)) },
        )))

        val compacted = requireNotNull(
            compactRepositoryContextToBudget(
                context,
                budget,
                requiredPaths,
                contentCompactor = { content, maxBytes -> content.take(maxBytes) },
            ) { Json.encodeToString(it) }
        )

        assertEquals(context.files.map { it.path }, compacted.files.map { it.path })
        assertTrue(compacted.files.all { it.content.length < 20_000 })
        assertEquals(context.files.map { it.contentHash }, compacted.files.map { it.contentHash })
    }

    @Test
    fun `deterministic repository analysis rejection requires explicit retry`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-retry-state-")
        val projects = createTempDirectory("orchard-company-analysis-retry-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val attempts = TransientRepositoryAnalysisAttemptStore()
        val retrievalQueries = mutableListOf<String>()
        val gateway = object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
            override fun collectAnalysisContext(workspacePath: String, query: String, selectors: List<com.orchard.backend.workspace.RepositoryEvidenceSelector>): CodingRepositoryContext {
                val admitted = requireNotNull(workspace.snapshot(MESSAGE_READY).workflowRuns.single().workDefinition)
                if (retrievalQueries.isEmpty()) {
                    assertEquals(admitted.definition.requestedOutcome, query.lineSequence().first())
                    assertTrue(admitted.definition.scope.all(query::contains))
                    assertTrue(!query.startsWith(workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.title))
                }
                retrievalQueries += query
                return LocalCodingWorkspaceGateway().collectAnalysisContext(workspacePath, query, selectors)
            }
        }
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            gateway,
            companyControl = company,
            attemptStore = attempts,
        )
        val admittedRun = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val runId = admittedRun.runId
        val admittedDefinition = requireNotNull(admittedRun.workDefinition)

        val first = analysis.tick(runId)
        assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, first.status)
        assertEquals(1, retrievalQueries.size)
        val analysisEnvelope = Json.parseToJsonElement(model.analysisPrompts.single().substringAfter("Authoritative repository analysis envelope:\n")).jsonObject
        val projection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), analysisEnvelope.getValue("attention"))
        val suppliedContext = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), analysisEnvelope.getValue("repositoryContext"))
        val attention = com.orchard.backend.attention.resolveCanonicalAttention(projection, suppliedContext)
        assertTrue(analysisEnvelope.getValue("requiredOutputSchema").jsonPrimitive.content.startsWith("RepositoryAnalysisCandidate("))
        assertEquals("WORK_DEFINITION", attention.getValue("objective").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(admittedDefinition.definitionId.toString(), attention.getValue("objective").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(admittedDefinition.hash, attention.getValue("objective").jsonObject.getValue("sourceHash").jsonPrimitive.content)
        assertEquals(admittedDefinition.definition.scope, attention.getValue("correlations").jsonArray.map {
            it.jsonObject.getValue("scope").jsonObject.getValue("text").jsonPrimitive.content
        })
        assertTrue(attention.getValue("correlations").jsonArray.all {
            it.jsonObject.getValue("scope").jsonObject.getValue("sourceHash").jsonPrimitive.content == admittedDefinition.hash
        })
        assertEquals(suppliedContext.files.indices.toList(), projection.sources.values.filter { "fileIndex" in it }.map {
            it.getValue("fileIndex").jsonPrimitive.content.toInt()
        }.sorted())
        assertTrue(suppliedContext.files.none { it.contentHash in Json.encodeToString(projection) })
        assertEquals("READ_SOURCE", attention.getValue("allowedActions").jsonArray.first().jsonPrimitive.content)
        assertTrue(attention.getValue("correlations").jsonArray.isNotEmpty())
        assertTrue(attention.getValue("hash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        assertTrue(listOf("requiredEvidence", "requiredScope", "requiredEvidencePathGroups", "requiredScopeEvidencePathGroupIds").none { it in analysisEnvelope })
        assertTrue(analysisEnvelope.getValue("repositoryContext").jsonObject.getValue("files").jsonArray.isNotEmpty())
        val selection = requireNotNull(attempts.load().first().contextSelection)
        assertEquals(com.orchard.backend.vector.MODEL_TOKEN_COUNT_BYTE_FALLBACK, selection.tokenAccounting?.method)
        assertEquals("analysis-input-allocation-v1:70-percent", selection.budgetPolicy)
        assertTrue(selection.componentAccounting.containsKey("repositoryContext"))
        val quality = requireNotNull(selection.qualityReport)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, quality.validity)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, quality.relevance)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, quality.adequacy)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.PASS, quality.capacity)
        assertTrue(quality.transformations.isNotEmpty())
        assertEquals(quality.frameHash, attention.getValue("hash").jsonPrimitive.content)
        assertEquals(projection, quality.references)
        assertEquals(suppliedContext.files.map { it.path }, quality.evidence.map { it.path })
        assertEquals(suppliedContext.files.map { it.contentHash }, quality.evidence.map { it.sourceHash })
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, workspace.modelExecutions().last().qualityReport?.downstream)
        assertEquals(emptyList(), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(runId).status)
        assertEquals(1, model.analysisCallCount)
        assertEquals(RepositoryAnalysisTickStatus.RETRY_AUTHORIZED, analysis.authorizeRetry(runId).status)
        assertEquals(listOf(runId), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.INVALID_ANALYSIS, analysis.tick(runId).status)
        assertTrue(model.analysisPrompts.last().contains(first.diagnostic))
        assertEquals(emptyList(), analysis.eligibleRunIds())
        assertEquals(2, model.analysisCallCount)
        assertEquals(
            listOf(
                ANALYSIS_ATTEMPT_RUNNING,
                ANALYSIS_ATTEMPT_BLOCKED,
                ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                ANALYSIS_ATTEMPT_RUNNING,
                ANALYSIS_ATTEMPT_BLOCKED,
            ),
            attempts.load().map { it.state },
        )
    }

    @Test
    fun `repository analysis requires compatible intelligence before model dispatch`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-intelligence-state-")
        val projects = createTempDirectory("orchard-company-analysis-intelligence-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace, repositoryPath = "build.gradle.kts")
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val traceStore = TransientRepositoryIntelligenceTraceStore()
        val importer = RepositoryIntelligenceImporter(
            workspace,
            FileRepositoryIntelligenceGraphStore(state),
            traceStore = traceStore,
        )
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            repositoryIntelligenceImporter = importer,
        )
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val reservation = requireNotNull(run.context.workspaceReservation)

        assertEquals(RepositoryAnalysisTickStatus.INTELLIGENCE_UNAVAILABLE, analysis.tick(run.runId).status)
        importer.ensure(run.context.projectId, reservation.path, reservation.baseRevision)

        assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, analysis.tick(run.runId).status)
        assertEquals(0, model.analysisCallCount)
    }

    @Test
    fun `repository analysis blocks graph-enabled work without exact scope anchors`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-unanchored-state-")
        val projects = createTempDirectory("orchard-company-analysis-unanchored-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val importer = RepositoryIntelligenceImporter(workspace, FileRepositoryIntelligenceGraphStore(state))
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            repositoryIntelligenceImporter = importer,
        )
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val reservation = requireNotNull(run.context.workspaceReservation)

        importer.ensure(run.context.projectId, reservation.path, reservation.baseRevision)

        assertEquals(RepositoryAnalysisTickStatus.CONTEXT_UNAVAILABLE, analysis.tick(run.runId).status)
        assertEquals(0, model.analysisCallCount)
    }

    @Test
    fun `terminal coding plan block requests a grounded repository analysis revision`() = runTest {
        val state = createTempDirectory("orchard-company-plan-revision-state-")
        val projects = createTempDirectory("orchard-company-plan-revision-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = ScenarioStaffModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val codingAttempts = TransientCodingWorkerAttemptStore()
        val analysisAttempts = TransientRepositoryAnalysisAttemptStore()
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            codingAttemptStore = codingAttempts,
            attemptStore = analysisAttempts,
        )
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId

        val initialAnalysis = analysis.tick(runId)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, initialAnalysis.status, initialAnalysis.diagnostic)
        assertEquals(emptyList(), analysis.eligibleRunIds())
        val blockedPlan = analysis.plans().single()
        val diagnostic = "The accepted plan requires a cosmetic source mutation."
        codingAttempts.appendNext { attemptId ->
            CodingWorkerAttempt(
                attemptId = attemptId,
                runId = runId,
                executionPlanId = blockedPlan.planId,
                executionPlanHash = blockedPlan.hash,
                state = CODING_ATTEMPT_BLOCKED,
                resultStatus = CodingWorkerTickStatus.PLAN_BLOCKED.name,
                diagnostic = diagnostic,
            )
        }

        assertEquals(listOf(runId), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick(runId).status)
        assertEquals(listOf(1, 2), analysis.plans().map { it.revision })
        assertTrue(model.analysisPrompts.last().contains(diagnostic))
        assertEquals(emptyList(), analysis.eligibleRunIds())

        val currentRevision = requireNotNull(workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.workspaceReservation).baseRevision
        analysisAttempts.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                runId,
                currentRevision,
                ANALYSIS_ATTEMPT_BLOCKED,
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                "The successor plan omitted required evidence.",
                "a".repeat(64),
            )
        }
        assertEquals(RepositoryAnalysisTickStatus.RETRY_AUTHORIZED, analysis.authorizeRetry(runId).status)
        assertEquals(listOf(runId), analysis.eligibleRunIds())
    }

    @Test
    fun `cancelled repository analysis consumes retry authority into durable block`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-cancellation-state-")
        val projects = createTempDirectory("orchard-company-analysis-cancellation-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = CancellingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val attempts = TransientRepositoryAnalysisAttemptStore()
        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            attemptStore = attempts,
        )
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId

        assertFailsWith<CancellationException> { analysis.tick(runId) }

        assertEquals(emptyList(), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(runId).status)
        assertEquals(1, model.analysisCallCount)
        assertEquals(listOf(ANALYSIS_ATTEMPT_RUNNING, ANALYSIS_ATTEMPT_BLOCKED), attempts.load().map { it.state })
        assertEquals(RepositoryAnalysisTickStatus.BUSY.name, attempts.load().first().resultStatus)
        assertEquals(RepositoryAnalysisTickStatus.CANCELLED.name, attempts.load().last().resultStatus)
    }

    @Test
    fun `startup blocks an authorized retry interrupted before durable attempt outcome`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-interruption-state-")
        val projects = createTempDirectory("orchard-company-analysis-interruption-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val revision = requireNotNull(run.context.workspaceReservation).baseRevision
        val attempts = TransientRepositoryAnalysisAttemptStore()
        attempts.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                run.runId,
                revision,
                ANALYSIS_ATTEMPT_BLOCKED,
                RepositoryAnalysisTickStatus.INVALID_ANALYSIS.name,
                "Initial rejection.",
                "a".repeat(64),
                "2026-07-23T15:00:00Z",
            )
        }
        attempts.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId,
                run.runId,
                revision,
                ANALYSIS_ATTEMPT_RETRY_AUTHORIZED,
                RepositoryAnalysisTickStatus.RETRY_AUTHORIZED.name,
                "Explicit retry.",
                "a".repeat(64),
                "2026-07-23T15:01:00Z",
            )
        }
        workspace.recordModelExecution(
            ModelExecutionObservationDraft(
                profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis,
                binding = model.bindingProfile(),
                workflowStepId = DefaultModelExecutionProfiles.broadRepositoryAnalysis.id,
                workItemId = run.context.workItemId,
                envelopeHash = "b".repeat(64),
                promptHash = "c".repeat(64),
                outputHash = null,
                inputTokens = 100,
                outputTokens = 0,
                latencyMillis = 1,
                schemaValid = false,
            )
        )

        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            attemptStore = attempts,
        )

        assertEquals(emptyList(), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(run.runId).status)
        assertEquals(0, model.analysisCallCount)
        assertEquals(RepositoryAnalysisTickStatus.CANCELLED.name, attempts.load().last().resultStatus)
        assertEquals("c".repeat(64), attempts.load().last().promptHash)
    }

    @Test
    fun `startup recovers in-flight repository analysis ownership record`() = runTest {
        val state = createTempDirectory("orchard-company-analysis-running-state-")
        val projects = createTempDirectory("orchard-company-analysis-running-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        val revision = requireNotNull(run.context.workspaceReservation).baseRevision
        val attempts = TransientRepositoryAnalysisAttemptStore()
        attempts.appendNext { attemptId ->
            RepositoryAnalysisAttempt(
                attemptId = attemptId,
                runId = run.runId,
                baseRevision = revision,
                state = ANALYSIS_ATTEMPT_RUNNING,
                resultStatus = RepositoryAnalysisTickStatus.BUSY.name,
                diagnostic = "Repository analysis model execution is running.",
                promptHash = "d".repeat(64),
                executionProfileId = DefaultModelExecutionProfiles.broadRepositoryAnalysis.id,
                providerFingerprint = "e".repeat(64),
                inputTokens = 500,
            )
        }

        RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            attemptStore = attempts,
        )

        assertEquals(
            listOf(ANALYSIS_ATTEMPT_RUNNING, ANALYSIS_ATTEMPT_BLOCKED, ANALYSIS_ATTEMPT_RETRY_AUTHORIZED),
            attempts.load().map { it.state },
        )
        assertEquals(RepositoryAnalysisTickStatus.CANCELLED.name, attempts.load()[1].resultStatus)
        assertEquals("d".repeat(64), attempts.load()[1].promptHash)
    }

    @Test
    fun `legacy repeated repository analysis outcomes bootstrap a durable block`() = runTest {
        val state = createTempDirectory("orchard-company-legacy-analysis-state-")
        val projects = createTempDirectory("orchard-company-legacy-analysis-projects-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        createProjectAndEpic(workspace)
        admitGenesis(workspace)
        val model = RejectingAnalysisModel()
        val company = CompanyControlService(workspace, listOf(model), FileCompanyControlStore(state), bindings)
        val circuit = CompanyCircuitService(workspace, company, projects)
        assertEquals(CompanyCircuitStatus.STARTED, circuit.start(1).status)
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.single()
        repeat(2) {
            workspace.recordModelExecution(
                ModelExecutionObservationDraft(
                    profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis,
                    binding = model.bindingProfile(),
                    workflowStepId = DefaultModelExecutionProfiles.broadRepositoryAnalysis.id,
                    workItemId = run.context.workItemId,
                    envelopeHash = "a".repeat(64),
                    promptHash = "b".repeat(64),
                    outputHash = "c".repeat(64),
                    inputTokens = 100,
                    outputTokens = 10,
                    latencyMillis = 1,
                    schemaValid = true,
                )
            )
        }
        val attempts = TransientRepositoryAnalysisAttemptStore()

        val analysis = RepositoryAnalysisService(
            workspace,
            listOf(model),
            TransientRepositoryExecutionPlanStore(),
            LocalCodingWorkspaceGateway(),
            companyControl = company,
            attemptStore = attempts,
        )

        assertEquals(emptyList(), analysis.eligibleRunIds())
        assertEquals(RepositoryAnalysisTickStatus.ATTEMPT_BLOCKED, analysis.tick(run.runId).status)
        assertEquals(0, model.analysisCallCount)
        assertEquals(ANALYSIS_ATTEMPT_BLOCKED, attempts.load().single().state)
        assertEquals("b".repeat(64), attempts.load().single().promptHash)
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
        val workPackages = TransientExecutableWorkPackageStore()
        val pullRequests = TransientCandidatePullRequestStore()
        val candidateDispositions = CandidatePullRequestDispositionService(pullRequests)
        val worker = CodingWorkerService(
            workspace = workspace,
            modelProviders = listOf(staff),
            workerStore = TransientCodingWorkerStore(),
            workspaceGateway = LocalCodingWorkspaceGateway(),
            companyControl = company,
            repositoryAnalysis = analysis,
            retryBudget = 5,
            workPackageStore = workPackages,
            pullRequestStore = pullRequests,
            dispositionService = candidateDispositions,
        )

        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        assertEquals(DISPOSITION_SCAFFOLD_ONLY, analysis.plans().single().content.disposition)
        val reservedPath = Path.of(workspace.snapshot(MESSAGE_READY).workflowRuns.single().context.repository.path)
        val initialRevision = LocalCodingWorkspaceGateway().currentRevision(reservedPath.toString())
        val initialReadme = Files.readString(reservedPath.resolve("README.md"))
        val firstCodingAttempt = worker.tick()
        assertEquals(CodingWorkerTickStatus.INVALID_PROPOSAL, firstCodingAttempt.status, firstCodingAttempt.execution?.result?.diagnostic)
        assertEquals(1, workPackages.load().size)
        assertEquals(workPackages.load().single().packageId, worker.executions().single().claim.workPackageId)
        assertEquals(workPackages.load().single().hash, worker.executions().single().claim.workPackageHash)
        assertEquals(1, staff.codingCallCount)
        val codingEnvelope = Json.parseToJsonElement(staff.codingPrompts.single().substringAfter("Authoritative coding execution envelope:\n")).jsonObject
        val codingProjection = Json.decodeFromJsonElement(com.orchard.backend.attention.CanonicalAttentionProjection.serializer(), codingEnvelope.getValue("attention"))
        val codingContext = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), codingEnvelope.getValue("repositoryContext"))
        val codingFrame = com.orchard.backend.attention.resolveCanonicalAttention(codingProjection, codingContext)
        val codingDefinition = requireNotNull(workspace.snapshot(MESSAGE_READY).workflowRuns.single().workDefinition)
        assertEquals(codingDefinition.hash, codingFrame.getValue("objective").jsonObject.getValue("sourceHash").jsonPrimitive.content)
        assertTrue(codingContext.files.none { it.contentHash in Json.encodeToString(codingProjection) })
        val codingObservation = workspace.modelExecutions().last()
        val codingQuality = requireNotNull(codingObservation.qualityReport)
        assertTrue(codingObservation.schemaValid)
        assertEquals(com.orchard.backend.attention.ContextQualityStatus.FAIL, codingQuality.downstream)
        assertEquals(codingProjection, codingQuality.references)
        assertEquals(codingFrame.getValue("hash").jsonPrimitive.content, codingObservation.attentionFrameHash)
        assertEquals(codingObservation.attentionFrameHash, codingQuality.frameHash)
        assertEquals(codingContext.files.map { it.path }, codingQuality.evidence.map { it.path })
        assertEquals(codingContext.files.map { it.contentHash }, codingQuality.evidence.map { it.sourceHash })
        assertEquals(initialRevision, LocalCodingWorkspaceGateway().currentRevision(reservedPath.toString()))
        assertEquals(initialReadme, Files.readString(reservedPath.resolve("README.md")))
        assertEquals(
            CodingWorkerTickStatus.RETRY_AUTHORIZED,
            worker.authorizeRetry(workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId).status,
        )
        assertEquals(CodingWorkerTickStatus.CANDIDATE_COMPLETED, worker.tick().status)
        assertEquals(2, staff.codingCallCount)
        val secondAttempt = worker.executions().last()
        assertEquals(
            CodingWorkerTickStatus.CANDIDATE_COMPLETED,
            requireNotNull(secondAttempt.result).let { CodingWorkerTickStatus.CANDIDATE_COMPLETED },
            secondAttempt.result?.diagnostic,
        )
        assertEquals(1, workPackages.load().size)
        assertEquals(workPackages.load().single().packageId, secondAttempt.claim.workPackageId)
        assertEquals(workPackages.load().single().hash, secondAttempt.claim.workPackageHash)
        val firstPullRequest = worker.pullRequests().single()
        assertEquals(secondAttempt.result?.revision, firstPullRequest.candidateRevision)
        assertEquals(workPackages.load().single().hash, firstPullRequest.workPackageHash)
        assertTrue(firstPullRequest.evidence.all { it.passed })
        assertEquals(
            listOf(CANDIDATE_DISPOSITION_REVIEW_REQUIRED),
            candidateDispositions.dispositions(firstPullRequest.pullRequestId).map { it.status },
        )
        val runId = workspace.snapshot(MESSAGE_READY).workflowRuns.single().runId
        val auditProfileSettings = TransientModelProfileSettingsStore().also { settings ->
            settings.save(
                listOf(
                    ModelProfileOverride(
                        DefaultModelExecutionProfiles.boundedIndependentAudit.id,
                        80_000,
                        6_000,
                    )
                )
            )
        }
        val audit = CompanyAuditService(
            workspace,
            worker,
            company,
            LocalCodingWorkspaceGateway(),
            profileSettingsStore = auditProfileSettings,
        )
        val invalid = audit.tick()
        assertEquals(CompanyAuditTickStatus.INVALID_JUDGMENT, invalid.status, invalid.diagnostic)
        assertTrue(invalid.diagnostic.contains("blank summary"))
        val rejectedCorrection = audit.tick()
        assertEquals(CompanyAuditTickStatus.MODEL_FAILED, rejectedCorrection.status, rejectedCorrection.diagnostic)
        assertEquals(CompanyAuditTickStatus.IDLE, audit.tick().status)
        assertEquals(2, staff.auditCallCount)
        testApplication {
            application { workspaceApi(workspace, companyControl = company, companyAudit = audit) }
            val retry = client.post("/api/company-audits/runs/$runId/retry")
            assertEquals(HttpStatusCode.Accepted, retry.status)
        }
        val violation = audit.tick()
        assertEquals(CompanyAuditTickStatus.VIOLATION, violation.status, violation.diagnostic)
        assertEquals(6_000, staff.auditMaxOutputTokens)
        assertEquals(86_000, staff.auditContextWindowTokens)
        assertTrue(staff.auditCorrectionDiagnosticObserved)
        val persistedAudit = company.projectView(1).audits.last()
        val candidateEvidenceIds = workspace.snapshot(MESSAGE_READY).workflowRuns.single().evidence
            .filter { it.revision == persistedAudit.candidateRevision }
            .map { it.evidenceId }
            .distinct()
            .sorted()
        assertTrue(persistedAudit.findings.all { it.evidenceIds == candidateEvidenceIds })
        assertEquals(
            listOf(
                AUDIT_ATTEMPT_BLOCKED,
                AUDIT_ATTEMPT_RETRY_AUTHORIZED,
                AUDIT_ATTEMPT_RETRY_CONSUMED,
                AUDIT_ATTEMPT_BLOCKED,
                AUDIT_ATTEMPT_RETRY_AUTHORIZED,
                AUDIT_ATTEMPT_RETRY_CONSUMED,
            ),
            audit.attempts().map { it.state },
        )
        assertEquals("EVIDENCE_BLOCKED", workspace.snapshot(MESSAGE_READY).workflowRuns.single().state)
        assertEquals(CodingWorkerTickStatus.ANALYSIS_REQUIRED, worker.tick().status)
        assertEquals(RepositoryAnalysisTickStatus.PLAN_CREATED, analysis.tick().status)
        assertEquals(CodingWorkerTickStatus.CANDIDATE_COMPLETED, worker.tick().status)
        val repairedPullRequests = worker.pullRequests()
        assertEquals(2, repairedPullRequests.size)
        assertEquals(firstPullRequest.pullRequestId, repairedPullRequests.last().parentPullRequestId)
        assertEquals(
            listOf(CANDIDATE_DISPOSITION_REVIEW_REQUIRED, CANDIDATE_DISPOSITION_SUPERSEDED),
            candidateDispositions.dispositions(firstPullRequest.pullRequestId).map { it.status },
        )
        assertEquals(
            listOf(CANDIDATE_DISPOSITION_REVIEW_REQUIRED),
            candidateDispositions.dispositions(repairedPullRequests.last().pullRequestId).map { it.status },
        )
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
        assertTrue(company.projectView(1).acceptances.isEmpty())

        val acceptedAudit = audit.tick()
        assertEquals(CompanyAuditTickStatus.ACCEPTED, acceptedAudit.status)
        assertEquals(CompanyAuditTickStatus.ACCEPTED, audit.tick().status)
        assertEquals(CompanyAuditTickStatus.ACCEPTED, audit.tick().status)
        val accepted = company.projectView(1).acceptances.single()
        assertEquals(repairedRevision, accepted.candidateRevision)

        assertEquals(CompanyMutationStatus.RECORDED, company.escalate(runId, ROLE_QUALITY_AUDITOR, "Rotate the auditor after acceptance.").status)
        assertEquals(CompanyMutationStatus.RECORDED, company.assign(runId, ROLE_QUALITY_AUDITOR, RISK_HIGH).status)
        assertTrue(requireNotNull(company.assignment(runId, ROLE_QUALITY_AUDITOR)).evidenceSampleCount >= 1)

        val driftedCompany = CompanyControlService(
            workspace,
            listOf(staff),
            FileCompanyControlStore(state),
            bindings,
            object : CodingWorkspaceGateway by LocalCodingWorkspaceGateway() {
                override fun currentRevision(workspacePath: String): String = "0".repeat(40)
            },
        )
        assertEquals(CompanyMutationStatus.EVIDENCE_STALE, driftedCompany.promote(runId).status)
        assertTrue(driftedCompany.projectView(1).promotions.isEmpty())

        assertEquals(
            CompanyMutationStatus.RECORDED,
            promoteAcceptedAudit(acceptedAudit.status, acceptedAudit.runId, company),
        )
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

    @Test
    fun `repository first genesis compiles product intent governance`() {
        val state = createTempDirectory("orchard-repository-first-company-")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = workspace(state, bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Repository product")))
        workspace.commitBatch()
        workspace.beginBatch()
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    classification = PROJECT_GREENFIELD_LOCAL,
                    productIntent = "Evolve the existing product under governed delivery.",
                    baseRevision = 0,
                ),
            ).status,
        )
        val classified = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision)
        assertEquals(
            ProjectGenesisStatus.RECORDED,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    experience = ExperienceContract(
                        audience = "Local maintainers",
                        productPromise = "Existing product changes remain governed.",
                        primaryJourney = listOf("Define an outcome", "Deliver it"),
                        interactionPrinciples = listOf("Preserve authority"),
                        emotionalQualities = listOf("Calm"),
                        mustNotFeelLike = listOf("An ungoverned script"),
                    ),
                    baseRevision = classified.revision,
                    baseHash = classified.hash,
                ),
            ).status,
        )
        val experienced = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision)
        workspace.commitBatch()
        assertEquals(
            ProjectGenesisFirstOutcomeStatus.CREATED,
            workspace.createProjectGenesisFirstOutcome(
                projectId = 1,
                title = "Deliver the first repository outcome",
                baseRevision = experienced.revision,
                baseHash = experienced.hash,
                confirmedProductIntent = "Evolve the existing product under governed delivery.",
            ).status,
        )
        assertEquals(ProjectGenesisStatus.ADMITTED, workspace.admitProjectGenesis(1).status)
        val company = CompanyControlService(workspace, listOf(StaffModel), FileCompanyControlStore(state), bindings)

        assertEquals(CompanyMutationStatus.RECORDED, company.compileRules(1).status)
        val rule = requireNotNull(company.projectView(1).ruleSet).rules.single()
        assertEquals("PRODUCT_INTENT", rule.ruleId)
        assertEquals("Evolve the existing product under governed delivery.", rule.statement)
        assertTrue(rule.requiresIndependentAudit)
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

    private fun admitGenesis(workspace: WorkspaceStore, repositoryPath: String = "src") {
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
                            repositoryPaths = listOf(repositoryPath),
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

    private class RejectingAnalysisModel : ModelProvider {
        var analysisCallCount = 0
            private set
        val analysisPrompts = mutableListOf<String>()

        override suspend fun triage(prompt: String): String = "{}"

        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = "{}"

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:rejecting-analysis",
            provider = "test",
            model = "rejecting-analysis",
            contextWindowTokens = 100_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            analysisCallCount++
            analysisPrompts += prompt
            return ModelGeneration("{}", 1, 1)
        }
    }

    private class CancellingAnalysisModel : ModelProvider {
        var analysisCallCount = 0
            private set

        override suspend fun triage(prompt: String): String = "{}"

        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = "{}"

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "test:cancelling-analysis",
            provider = "test",
            model = "cancelling-analysis",
            contextWindowTokens = 100_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            analysisCallCount++
            throw CancellationException("cancelled for test")
        }
    }

    private class ScenarioStaffModel(private val oversizedCodingBatch: Boolean = false) : ModelProvider {
        private var codingCalls = 0
        private var auditCalls = 0
        private var analysisCalls = 0
        val codingCallCount: Int get() = codingCalls
        val auditCallCount: Int get() = auditCalls
        val analysisPrompts = mutableListOf<String>()
        val codingPrompts = mutableListOf<String>()
        var auditMaxOutputTokens: Int? = null
            private set
        var auditContextWindowTokens: Int? = null
            private set
        var auditCorrectionDiagnosticObserved = false
            private set

        override suspend fun triage(prompt: String): String = "{}"

        override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = "{}"

        override suspend fun executeCodingPatch(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            codingPrompts += prompt
            if (codingCalls == 1) {
                assertTrue(prompt.contains("priorRejectedCodingDiagnostic"))
                assertTrue(prompt.contains("do not reverse-trace to the admitted objective"))
            }
            val content = when (codingCalls++) {
                0 -> "plugins { base }\n"
                1 -> "plugins { base }\n\ndescription = \"Initial governed candidate\"\n\ntasks.register(\"test\") { dependsOn(\"check\") }\n"
                else -> "plugins { base }\n\ndescription = \"Repaired after independent audit\"\n\ntasks.register(\"test\") { dependsOn(\"check\") }\n"
            }
            val envelope = Json.parseToJsonElement(
                prompt.substringAfter("Authoritative coding execution envelope:\n")
            ).jsonObject
            val expectedRevision = envelope.getValue("currentRevision").jsonPrimitive.content
            val operations = if (codingCalls == 1 && oversizedCodingBatch) {
                List(13) { BoundedCodingToolOperation(BOUNDED_TOOL_REWRITE_FILE, "build.gradle.kts", content = content) }
            } else if (codingCalls == 1) {
                listOf(
                    BoundedCodingToolOperation(BOUNDED_TOOL_REWRITE_FILE, "build.gradle.kts", content = content),
                    BoundedCodingToolOperation(BOUNDED_TOOL_REWRITE_FILE, "README.md", content = "Unauthorized scope expansion.\n"),
                )
            } else {
                listOf(
                    BoundedCodingToolOperation(BOUNDED_TOOL_REWRITE_FILE, "build.gradle.kts", content = content)
                )
            }
            val output = Json.encodeToString(
                BoundedCodingToolBatch(
                    summary = "Implement the governed local experience.",
                    expectedRevision = expectedRevision,
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
            analysisPrompts += prompt
            val contentHash = requireNotNull(
                Regex("\\\"path\\\":\\\"build\\.gradle\\.kts\\\",\\\"content\\\":.*?\\\"contentHash\\\":\\\"([0-9a-f]{64})\\\"")
                    .find(prompt)
            ).groupValues[1]
            val envelope = Json.parseToJsonElement(
                prompt.substringAfter("Authoritative repository analysis envelope:\n")
            ).jsonObject
            assertTrue("run" !in envelope)
            val task = requireNotNull(envelope["task"]).jsonObject
            assertTrue(requireNotNull(task["title"]).jsonPrimitive.content.isNotBlank())
            assertTrue("acceptanceCriteria" !in task)
            assertTrue(
                requireNotNull(envelope["requiredAcceptanceCriteria"]).jsonArray
                    .all { it.jsonPrimitive.isString }
            )
            val criterion = "The architect can observe one complete governed journey."
            val disposition = if (analysisCalls++ == 0) DISPOSITION_SCAFFOLD_ONLY else DISPOSITION_PARTIALLY_IMPLEMENTED
            val output = Json.encodeToString(
                RepositoryAnalysisCandidate(
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
                    sourcePaths = listOf("build.gradle.kts"),
                )
            )
            return ModelGeneration(output, prompt.length, output.length)
        }

        override suspend fun executeCircuitSynthesis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            auditMaxOutputTokens = maxOutputTokens
            auditContextWindowTokens = contextWindowTokens
            assertTrue(prompt.contains("candidatePullRequest"))
            assertTrue(prompt.contains("Does the actual code and evidence support every candidate PR implementation claim?"))
            assertTrue(prompt.contains("Does the implementation satisfy the admitted intent and design without undeclared deviation?"))
            val auditCall = auditCalls++
            if (auditCall in 1..2) {
                auditCorrectionDiagnosticObserved = auditCorrectionDiagnosticObserved || prompt.contains("blank summary")
            }
            if (auditCall == 1) error("audit provider unavailable")
            val ruleIds = Regex("\\\"ruleId\\\":\\\"([^\\\"]+)\\\"")
                .findAll(prompt).map { it.groupValues[1] }.distinct().toList()
            val status = if (auditCall <= 2) AUDIT_VIOLATION else AUDIT_CONFORMING
            val output = Json.encodeToString(
                AuditProposalOutput(
                    findings = ruleIds.map {
                        AuditFindingOutput(
                            it,
                            status,
                            if (auditCall <= 1) "" else if (status == AUDIT_VIOLATION) {
                                "The first candidate violates the admitted rule."
                            } else {
                                "The repaired candidate conforms."
                            },
                        )
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
        val findings: List<AuditFindingOutput>,
        val rationale: String,
    )

    @Serializable
    private data class AuditFindingOutput(
        val ruleId: String,
        val status: String,
        val summary: String,
    )
}
