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

    private fun service(fixture: Fixture, model: ModelProvider) = RepositoryBaselineAnalysisService(
        workspace = fixture.workspace,
        modelProviders = listOf(model),
        store = FileRepositoryBaselineAnalysisStore(fixture.state),
        workspaceGateway = LocalCodingWorkspaceGateway(),
    )

    private fun fixture(): Fixture {
        val state = createTempDirectory("orchard-baseline-state-")
        val repository = createTempDirectory("orchard-baseline-repository-")
        val contentByPath = linkedMapOf(
            "build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
            "src/main/kotlin/App.kt" to "class AppService { fun start() = Unit }\n",
            "docs/adrs/001-runtime.md" to "# Runtime decision\nStatus: Accepted\nUse a local JVM service.\n",
            "src/test/kotlin/AppTest.kt" to "class AppTest { fun serviceStarts() = Unit }\n",
            "run.sh" to "#!/bin/sh\n./gradlew test\n",
        )
        contentByPath.forEach { (relative, content) ->
            repository.resolve(relative).also { file ->
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
        }
        git(repository, "init")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
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

    @Serializable
    private data class StageOutput(
        val summary: String,
        val findings: List<RepositoryCapabilityClaim>,
        val unresolvedQuestions: List<String> = emptyList(),
    )
}