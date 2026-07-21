package com.orchard.backend.analysis

import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.agent.sha256Content
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RepositoryBaselineAnalysisServiceTest {
    @Test
    fun `version one baseline history remains replayable but unpinned`() {
        val state = createTempDirectory("orchard-baseline-v1-")
        val json = Json { encodeDefaults = true }
        val section = RepositoryBaselineSection(
            BASELINE_STAGE_STRUCTURE,
            "Legacy structure summary.",
            listOf("primary", "secondary").map { suffix ->
                RepositoryCapabilityClaim("structure-$suffix", "Legacy $suffix finding.", CLAIM_UNESTABLISHED)
            },
            model = "legacy-model",
            promptHash = "a".repeat(64),
            outputHash = "b".repeat(64),
        )
        val unsigned = LegacyBaseline(
            1,
            1,
            1,
            "c".repeat(40),
            listOf(section),
            complete = false,
            analyzedAt = "2026-01-01T00:00:00Z",
            hash = "",
        )
        val legacy = unsigned.copy(hash = stagedPlanHash(json.encodeToString(unsigned)))
        val payload = json.encodeToString(legacy)
        val line = json.encodeToString(LegacyEnvelope(value = legacy, checksum = stagedPlanHash(payload))) + "\n"
        Files.writeString(state.resolve("repository-baseline-analyses.jsonl"), line)

        val loaded = FileRepositoryBaselineAnalysisStore(state).load().single()

        assertEquals("", loaded.graphHash)
        assertEquals(0, loaded.graphCoverage.trackedFileCount)
        assertEquals(listOf(section), loaded.sections)
    }

    @Test
    fun `baseline advances one durable stage per tick across restart`() = runTest {
        val fixture = fixture()
        val model = StageModel(fixture.contentByPath)
        val first = service(fixture, model)

        val structure = first.tick(1)
        assertEquals(RepositoryBaselineTickStatus.STAGE_COMPLETED, structure.status, structure.diagnostic)
        assertEquals(listOf(BASELINE_STAGE_STRUCTURE), first.latest(1)?.sections?.map { it.stage })

        val recovered = service(fixture, model)
        assertEquals(RepositoryBaselineTickStatus.STAGE_COMPLETED, recovered.tick(1).status)
        assertEquals(RepositoryBaselineTickStatus.STAGE_COMPLETED, recovered.tick(1).status)
        assertEquals(RepositoryBaselineTickStatus.COMPLETE, recovered.tick(1).status)

        val baseline = requireNotNull(recovered.latest(1))
        assertTrue(baseline.complete)
        assertEquals(REPOSITORY_BASELINE_STAGES, baseline.sections.map { it.stage })
        assertEquals(8, baseline.sections.sumOf { it.findings.size })
        assertEquals(4, FileRepositoryBaselineAnalysisStore(fixture.state).load().size)
        assertTrue(model.promptFor(BASELINE_STAGE_DECISIONS).contains("docs/adrs/001-runtime.md"))
        assertTrue(model.promptFor(BASELINE_STAGE_VERIFICATION).contains("src/test/kotlin/AppTest.kt"))
    }

    @Test
    fun `baseline rejects evidence outside pinned stage context`() = runTest {
        val fixture = fixture()
        val result = service(fixture, StageModel(fixture.contentByPath, invalidCitation = true)).tick(1)

        assertEquals(RepositoryBaselineTickStatus.INVALID_ANALYSIS, result.status)
        assertTrue(result.diagnostic.contains("outside the pinned context"))
        assertTrue(FileRepositoryBaselineAnalysisStore(fixture.state).load().isEmpty())
    }

    @Test
    fun `baseline completes when graph establishes no ADR or test artifacts`() = runTest {
        val fixture = fixture(includeAdrAndTest = false)
        val service = service(fixture, InventoryAwareModel(fixture.contentByPath))

        repeat(3) {
            assertEquals(RepositoryBaselineTickStatus.STAGE_COMPLETED, service.tick(1).status)
        }
        assertEquals(RepositoryBaselineTickStatus.COMPLETE, service.tick(1).status)

        val baseline = requireNotNull(service.latest(1))
        assertTrue(baseline.sections.single { it.stage == BASELINE_STAGE_DECISIONS }.findings.all {
            it.status == CLAIM_UNESTABLISHED
        })
        assertTrue(baseline.sections.single { it.stage == BASELINE_STAGE_VERIFICATION }.findings.all {
            it.status == CLAIM_UNESTABLISHED
        })
    }

    @Test
    fun `empty committed repository completes with unestablished findings`() = runTest {
        val fixture = fixture(includeRepositoryFiles = false)
        val service = service(fixture, CompleteAbsenceModel())

        repeat(3) {
            assertEquals(RepositoryBaselineTickStatus.STAGE_COMPLETED, service.tick(1).status)
        }
        assertEquals(RepositoryBaselineTickStatus.COMPLETE, service.tick(1).status)

        val baseline = requireNotNull(service.latest(1))
        assertEquals(0, baseline.graphCoverage.trackedFileCount)
        assertTrue(baseline.sections.flatMap { it.findings }.all { it.status == CLAIM_UNESTABLISHED })
    }

    private fun service(fixture: Fixture, model: ModelProvider) = RepositoryBaselineAnalysisService(
        workspace = fixture.workspace,
        modelProviders = listOf(model),
        store = FileRepositoryBaselineAnalysisStore(fixture.state),
        workspaceGateway = LocalCodingWorkspaceGateway(),
        intelligenceImporter = RepositoryIntelligenceImporter(
            fixture.workspace,
            FileRepositoryIntelligenceGraphStore(fixture.state),
        ),
    )

    private fun fixture(
        includeAdrAndTest: Boolean = true,
        includeRepositoryFiles: Boolean = true,
    ): Fixture {
        val state = createTempDirectory("orchard-baseline-state-")
        val repository = createTempDirectory("orchard-baseline-repository-")
        val contentByPath = linkedMapOf<String, String>()
        if (includeRepositoryFiles) {
            contentByPath["build.gradle.kts"] = "plugins { kotlin(\"jvm\") }\n"
            contentByPath["src/main/kotlin/App.kt"] = "class AppService { fun start() = Unit }\n"
            contentByPath["run.sh"] = "#!/bin/sh\n./gradlew test\n"
        }
        if (includeRepositoryFiles && includeAdrAndTest) {
            contentByPath["docs/adrs/001-runtime.md"] = "# Runtime decision\nStatus: Accepted\nUse a local JVM service.\n"
            contentByPath["src/test/kotlin/AppTest.kt"] = "class AppTest { fun serviceStarts() = Unit }\n"
        }
        contentByPath.forEach { (relative, content) ->
            repository.resolve(relative).also { file ->
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
        }
        git(repository, "init")
        git(repository, "add", ".")
        git(
            repository,
            "-c", "user.name=Orchard Test",
            "-c", "user.email=orchard@example.test",
            "commit", "--allow-empty", "-m", "Initial",
        )
        val bindings = FileRepositoryBindingStore(state)
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(
            ACTION_CREATE,
            ENTITY_PROJECT,
            DEFAULT_DELIVERY_WORKFLOW_ID,
            title = "Baseline project",
        )))
        workspace.commitBatch()
        assertEquals(RepositoryBindStatus.BOUND, workspace.bindRepository(1, repository.toString()).status)
        return Fixture(state, workspace, contentByPath)
    }

    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
    }

    private data class Fixture(
        val state: Path,
        val workspace: WorkspaceStore,
        val contentByPath: Map<String, String>,
    )

    private class StageModel(
        private val contentByPath: Map<String, String>,
        private val invalidCitation: Boolean = false,
    ) : ModelProvider {
        val prompts = mutableListOf<String>()

        fun promptFor(stage: String): String = prompts.single {
            Regex("\\\"stage\\\":\\\"([A-Z]+)\\\"").find(it)?.groupValues?.get(1) == stage
        }

        override suspend fun triage(prompt: String): String = error("unused")

        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "baseline-test-model",
            provider = "test",
            model = "baseline-test-model",
            contextWindowTokens = 96_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            prompts += prompt
            val stage = requireNotNull(Regex("\\\"stage\\\":\\\"([A-Z]+)\\\"").find(prompt)).groupValues[1]
            val path = when (stage) {
                BASELINE_STAGE_STRUCTURE -> "src/main/kotlin/App.kt"
                BASELINE_STAGE_DECISIONS -> "docs/adrs/001-runtime.md"
                BASELINE_STAGE_VERIFICATION -> "src/test/kotlin/AppTest.kt"
                BASELINE_STAGE_DELIVERY -> "run.sh"
                else -> error("unknown stage")
            }
            val citedPath = if (invalidCitation) "outside/context.kt" else path
            val evidence = RepositoryClaimEvidence(
                citedPath,
                sha256Content(requireNotNull(contentByPath[path])),
                "The cited bytes establish a material ${stage.lowercase()} property.",
            )
            val output = StageOutput(
                summary = "$stage analysis established two material repository findings.",
                findings = listOf(
                    RepositoryCapabilityClaim(
                        "${stage.lowercase()}-primary",
                        "$stage has an established primary capability.",
                        CLAIM_SUPPORTED,
                        support = listOf(evidence),
                    ),
                    RepositoryCapabilityClaim(
                        "${stage.lowercase()}-secondary",
                        "$stage has an established secondary constraint.",
                        CLAIM_SUPPORTED,
                        support = listOf(evidence),
                    ),
                ),
            )
            val text = Json.encodeToString(output)
            return ModelGeneration(text, estimateModelTokens(prompt), estimateModelTokens(text))
        }
    }

    private class InventoryAwareModel(
        private val contentByPath: Map<String, String>,
    ) : ModelProvider {
        override suspend fun triage(prompt: String): String = error("unused")

        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "inventory-aware-model",
            provider = "test",
            model = "inventory-aware-model",
            contextWindowTokens = 96_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            val stage = requireNotNull(Regex("\\\"stage\\\":\\\"([A-Z]+)\\\"").find(prompt)).groupValues[1]
            val absent = stage in setOf(BASELINE_STAGE_DECISIONS, BASELINE_STAGE_VERIFICATION)
            val evidencePath = if (stage == BASELINE_STAGE_DELIVERY) "run.sh" else "build.gradle.kts"
            val evidence = RepositoryClaimEvidence(
                evidencePath,
                sha256Content(requireNotNull(contentByPath[evidencePath])),
                "The cited bytes establish the available ${stage.lowercase()} surface.",
            )
            val output = StageOutput(
                summary = if (absent) "$stage artifacts are not established by the complete graph inventory."
                else "$stage evidence is established.",
                findings = listOf("primary", "secondary").map { suffix ->
                    RepositoryCapabilityClaim(
                        "${stage.lowercase()}-$suffix",
                        if (absent) "$stage $suffix evidence is unestablished."
                        else "$stage $suffix evidence is established.",
                        if (absent) CLAIM_UNESTABLISHED else CLAIM_SUPPORTED,
                        support = if (absent) emptyList() else listOf(evidence),
                    )
                },
            )
            val text = Json.encodeToString(output)
            return ModelGeneration(text, estimateModelTokens(prompt), estimateModelTokens(text))
        }
    }

    private class CompleteAbsenceModel : ModelProvider {
        override suspend fun triage(prompt: String): String = error("unused")

        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override fun bindingProfile() = ModelBindingProfile(
            bindingId = "complete-absence-model",
            provider = "test",
            model = "complete-absence-model",
            contextWindowTokens = 96_000,
            capabilities = setOf(MODEL_CAPABILITY_STRICT_JSON),
        )

        override suspend fun executeRepositoryAnalysis(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration {
            val stage = requireNotNull(Regex("\\\"stage\\\":\\\"([A-Z]+)\\\"").find(prompt)).groupValues[1]
            val output = StageOutput(
                summary = "$stage evidence is unestablished by the empty repository graph.",
                findings = listOf("primary", "secondary").map { suffix ->
                    RepositoryCapabilityClaim(
                        "${stage.lowercase()}-$suffix",
                        "$stage $suffix evidence is unestablished.",
                        CLAIM_UNESTABLISHED,
                    )
                },
            )
            val text = Json.encodeToString(output)
            return ModelGeneration(text, estimateModelTokens(prompt), estimateModelTokens(text))
        }
    }

    @Serializable
    private data class StageOutput(
        val summary: String,
        val findings: List<RepositoryCapabilityClaim>,
        val unresolvedQuestions: List<String> = emptyList(),
    )

    @Serializable
    private data class LegacyBaseline(
        val analysisId: Long,
        val projectId: Int,
        val genesisRevision: Int,
        val repositoryRevision: String,
        val sections: List<RepositoryBaselineSection>,
        val complete: Boolean,
        val analyzedAt: String,
        val hash: String,
    )

    @Serializable
    private data class LegacyEnvelope(
        val version: Int = 1,
        val value: LegacyBaseline,
        val checksum: String,
    )
}