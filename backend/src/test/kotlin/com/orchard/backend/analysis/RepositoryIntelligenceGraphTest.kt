package com.orchard.backend.analysis

import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.agent.sha256Content
import com.orchard.backend.workspaceApi
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RepositoryIntelligenceGraphTest {
    @Test
    fun `imports committed submodule gitlinks as opaque pinned artifacts`() {
        val state = createTempDirectory("orchard-intelligence-gitlink-state-")
        val repository = createTempDirectory("orchard-intelligence-gitlink-repository-")
        Files.writeString(repository.resolve("README.md"), "# Parent\n")
        git(repository, "init")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val submoduleRevision = git(repository, "rev-parse", "HEAD")
        git(repository, "update-index", "--add", "--cacheinfo", "160000,$submoduleRevision,vendor/library")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Pin submodule")
        val revision = git(repository, "rev-parse", "HEAD")
        val workspace = WorkspaceStore(repositoryBindings = FileRepositoryBindingStore(state))
        createProject(workspace)
        assertEquals(RepositoryBindStatus.BOUND, workspace.bindRepository(1, repository.toString()).status)

        val graph = RepositoryIntelligenceImporter(workspace).import(1, repository.toString(), revision)
        val gitlink = graph.nodes.single { it.path == "vendor/library" && it.kind == INTELLIGENCE_NODE_ASSET }

        assertEquals(sha256Content(submoduleRevision), gitlink.contentHash)
        assertEquals("true", gitlink.attributes["opaque"])
        assertEquals(2, graph.coverage.trackedFileCount)
    }

    @Test
    fun `gateway accepts SHA-256 Git repository revisions`() {
        val repository = createTempDirectory("orchard-intelligence-sha256-repository-")
        git(repository, "init", "--object-format=sha256")
        Files.writeString(repository.resolve("README.md"), "# SHA-256 repository\n")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val revision = git(repository, "rev-parse", "HEAD")

        val gateway = LocalCodingWorkspaceGateway()
        val context = gateway.collectIntelligenceContext(repository.toString(), revision, listOf("README.md"))

        assertEquals(64, revision.length)
        assertEquals(revision, gateway.currentRevision(repository.toString()))
        assertEquals("# SHA-256 repository\n", context.files.single().content)
    }

    @Test
    fun `imports every tracked file and correlates repository and Orchard authority`() {
        val state = createTempDirectory("orchard-intelligence-state-")
        val repository = createTempDirectory("orchard-intelligence-repository-")
        val files = linkedMapOf(
            "settings.gradle.kts" to "include(\":backend\", \":frontend\")\n",
            "backend/build.gradle.kts" to "plugins { kotlin(\"jvm\") }\n",
            "frontend/build.gradle.kts" to "dependencies { implementation(project(\":backend\")) }\n",
            "backend/src/main/kotlin/example/Service.kt" to "package example\nclass Service\n",
            "backend/src/main/kotlin/example/Specification.kt" to "package example\nclass Specification\n",
            "frontend/src/main/kotlin/example/App.kt" to "package example\nimport example.Service\nclass App(val service: Service)\n",
            "backend/src/test/kotlin/example/ServiceTest.kt" to "package example\nclass ServiceTest\n",
            "test/helpers.kt" to "fun helper() = Unit\n",
            "docs/adrs/001-service.md" to "# Service boundary\nStatus: Accepted\nImplemented by `backend/src/main/kotlin/example/Service.kt`.\n",
            "docs/latest.md" to "# Latest state\n",
            ".github/workflows/verify.yml" to "steps:\n  - run: ./gradlew test\n",
            "logo.bin" to "\u0000\u0001\u0002",
        )
        files.forEach { (relative, content) ->
            repository.resolve(relative).also { file ->
                Files.createDirectories(file.parent)
                Files.write(file, content.toByteArray(Charsets.UTF_8))
            }
        }
        git(repository, "init")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val revision = git(repository, "rev-parse", "HEAD")
        val bindings = FileRepositoryBindingStore(state)
        val workspace = WorkspaceStore(repositoryBindings = bindings)
        createProject(workspace)
        assertEquals(RepositoryBindStatus.BOUND, workspace.bindRepository(1, repository.toString()).status)
        val store = FileRepositoryIntelligenceGraphStore(state)
        val importer = RepositoryIntelligenceImporter(workspace, store)

        val first = importer.import(1, repository.toString(), revision)
        val repeated = importer.import(1, repository.toString(), revision)

        Files.writeString(repository.resolve("backend/src/main/kotlin/example/Service.kt"), "package changed\nclass Replacement\n")
        Files.writeString(repository.resolve("docs/latest.md"), "not committed\n")
        val freshState = createTempDirectory("orchard-intelligence-fresh-state-")
        val reproduced = RepositoryIntelligenceImporter(
            workspace,
            FileRepositoryIntelligenceGraphStore(freshState),
        ).import(1, repository.toString(), revision)

        assertEquals(files.size, first.coverage.trackedFileCount)
        assertEquals(files.size, first.coverage.contentAddressedFileCount)
        assertEquals(1, first.coverage.opaqueFileCount)
        assertEquals(files.keys.sorted(), first.nodes.filter {
            it.path != null && it.kind !in setOf(INTELLIGENCE_NODE_MODULE, INTELLIGENCE_NODE_SYMBOL)
        }.mapNotNull { it.path }.sorted())
        assertTrue(first.nodes.any { it.kind == INTELLIGENCE_NODE_SYMBOL && it.attributes["qualifiedName"] == "example.Service" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_IMPORTS })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_TESTS && it.evidencePath?.endsWith("ServiceTest.kt") == true })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_DOCUMENTS && it.evidencePath == "docs/adrs/001-service.md" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_DEPENDS_ON && it.evidencePath == "frontend/build.gradle.kts" })
        assertTrue(first.nodes.any { it.kind == INTELLIGENCE_NODE_PROJECT })
        assertEquals(INTELLIGENCE_NODE_SOURCE, first.nodes.single {
            it.path == "backend/src/main/kotlin/example/Specification.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(INTELLIGENCE_NODE_DOCUMENT, first.nodes.single {
            it.path == "docs/latest.md" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(INTELLIGENCE_NODE_TEST, first.nodes.single {
            it.path == "test/helpers.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(first, repeated)
        assertEquals(first.hash, reproduced.hash)
        assertEquals(first.hash, repositoryIntelligenceGraphHash(first.copy(graphId = 999, importedAt = "later")))
        assertEquals(
            first.nodes.map { it.nodeId to it.contentHash },
            reproduced.nodes.map { it.nodeId to it.contentHash },
        )
        assertEquals(
            first.nodes.single { it.path == "docs/latest.md" && it.kind != INTELLIGENCE_NODE_SYMBOL }.contentHash,
            reproduced.nodes.single { it.path == "docs/latest.md" && it.kind != INTELLIGENCE_NODE_SYMBOL }.contentHash,
        )
        assertEquals(listOf(first), FileRepositoryIntelligenceGraphStore(state).load())

        createEpic(workspace)
        val successor = importer.import(1, repository.toString(), revision)

        assertNotEquals(first.orchardAuthorityHash, successor.orchardAuthorityHash)
        assertEquals(revision, successor.repositoryRevision)
        assertTrue(successor.nodes.any { it.kind == INTELLIGENCE_NODE_WORK_ITEM && it.label == "Imported intelligence" })
        assertEquals(2, FileRepositoryIntelligenceGraphStore(state).load().size)

        testApplication {
            application {
                workspaceApi(workspace, repositoryIntelligenceImporter = importer)
            }
            val response = client.get("/api/projects/1/repository-intelligence")
            assertEquals(HttpStatusCode.OK, response.status)
            val projected = Json.decodeFromString<RepositoryIntelligenceGraph>(response.bodyAsText())
            assertEquals(successor.hash, projected.hash)
            assertEquals(files.size, projected.coverage.trackedFileCount)
        }
    }

    private fun createProject(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(
            ACTION_CREATE,
            ENTITY_PROJECT,
            DEFAULT_DELIVERY_WORKFLOW_ID,
            title = "Repository intelligence",
        )))
        workspace.commitBatch()
    }

    private fun createEpic(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(DocumentIntent(
            ACTION_CREATE,
            ENTITY_EPIC,
            DEFAULT_DELIVERY_WORKFLOW_ID,
            projectId = 1,
            title = "Imported intelligence",
        )))
        workspace.commitBatch()
    }

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}