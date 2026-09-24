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
import io.ktor.client.request.post
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
    fun `graph local analysis paths traverse only exact scope anchors and proven neighbors`() {
        val graph = RepositoryIntelligenceGraph(
            graphId = 1,
            projectId = 1,
            repositoryRevision = "a".repeat(40),
            genesisRevision = 1,
            orchardAuthorityHash = "b".repeat(64),
            nodes = listOf(
                RepositoryIntelligenceNode("service", INTELLIGENCE_NODE_SOURCE, "Service.kt", "src/Service.kt", unresolvedBoundaryIds = listOf("boundary:1")),
                RepositoryIntelligenceNode("consumer", INTELLIGENCE_NODE_SOURCE, "Consumer.kt", "src/Consumer.kt"),
                RepositoryIntelligenceNode("test", INTELLIGENCE_NODE_TEST, "ServiceTest.kt", "test/ServiceTest.kt"),
                RepositoryIntelligenceNode("document", INTELLIGENCE_NODE_DOCUMENT, "service.md", "docs/service.md"),
                RepositoryIntelligenceNode("unrelated", INTELLIGENCE_NODE_SOURCE, "Unrelated.kt", "src/Unrelated.kt"),
            ),
            edges = listOf(
                RepositoryIntelligenceEdge("imports", INTELLIGENCE_EDGE_IMPORTS, "consumer", "service", "src/Consumer.kt", "Imports service."),
                RepositoryIntelligenceEdge("tests", INTELLIGENCE_EDGE_TESTS, "test", "service", "test/ServiceTest.kt", "Tests service."),
                RepositoryIntelligenceEdge("documents", INTELLIGENCE_EDGE_DOCUMENTS, "document", "service", "docs/service.md", "Documents service."),
            ),
            coverage = RepositoryIntelligenceCoverage(5, 5, 5, 0, 5, 3),
            manifestKey = RepositoryIntelligenceManifestKey(1, "a".repeat(40), 1, 1),
            unresolvedBoundaries = listOf(
                RepositoryIntelligenceUnresolvedBoundary("boundary:1", "REFLECTION", "src/Service.kt", "Class.forName", "REQUIRE_EXPLICIT_CONTEXT", "service")
            ),
            hash = "c".repeat(64),
        )
        val selection = graphLocalRepositoryAnalysisSelection(graph, listOf("Modify `src/Service.kt` for the accepted behavior."))

        assertEquals(
            listOf("src/Service.kt", "docs/service.md", "src/Consumer.kt", "test/ServiceTest.kt"),
            selection.selectedPaths,
        )
        assertEquals(listOf("boundary:1"), selection.unresolvedBoundaryIds)
        assertEquals(REPOSITORY_INTELLIGENCE_GRAPH_LOCAL_QUERY, selection.toTrace().graphQuery)
    }

    @Test
    fun `run scoped ensure reports missing workflow run`() {
        val workspace = WorkspaceStore()
        val traceStore = TransientRepositoryIntelligenceTraceStore()
        val importer = RepositoryIntelligenceImporter(workspace, traceStore = traceStore)

        testApplication {
            application {
                workspaceApi(workspace, repositoryIntelligenceImporter = importer)
            }

            assertEquals(HttpStatusCode.NotFound, client.post("/api/repository-intelligence/runs/1/ensure").status)
        }
        assertEquals(RepositoryIntelligenceRunEnsureStatus.RUN_NOT_FOUND.name, traceStore.load().single().diagnosticCode)
        assertEquals(RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE, traceStore.load().single().kind)
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
            "backend/src/main/kotlin/example/Routes.kt" to "package example\nimport io.ktor.server.application.*\nimport example.OrderAuthority\nfun routes(authority: OrderAuthority) = Unit\n",
            "backend/src/main/kotlin/example/Dto.kt" to "package example\n@kotlinx.serialization.Serializable\ndata class Dto(val id: String)\n",
            "backend/src/main/kotlin/example/OrderAuthority.kt" to "package example\nimport example.OrderRepository\nimport example.OrderProjection\nclass OrderAuthority(val repository: OrderRepository, val projection: OrderProjection) { fun admit() { repository.append(); projection.toString() } }\n",
            "backend/src/main/kotlin/example/OrderRepository.kt" to "package example\nclass OrderRepository { fun append() = Unit }\n",
            "backend/src/main/kotlin/example/OrderProjection.kt" to "package example\nclass OrderProjection\n",
            "backend/src/main/kotlin/example/Specification.kt" to "package example\nclass Specification\n",
            "backend/src/main/kotlin/example/WildcardConsumer.kt" to "package example\nimport example.*\nclass WildcardConsumer\n",
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
        val lifecycleStore = FileRepositoryIntelligenceLifecycleStore(state)
        val traceStore = FileRepositoryIntelligenceTraceStore(state)
        val importer = RepositoryIntelligenceImporter(workspace, store, lifecycleStore = lifecycleStore, traceStore = traceStore)

        val firstEnsure = importer.ensure(1, repository.toString(), revision)
        val repeatedEnsure = importer.ensure(1, repository.toString(), revision)
        val first = firstEnsure.graph
        val repeated = repeatedEnsure.graph
        val policyBumpedEnsure = RepositoryIntelligenceImporter(
            workspace,
            store,
            policyVersion = 2,
        ).ensure(1, repository.toString(), revision)
        val extractorBumpedEnsure = RepositoryIntelligenceImporter(
            workspace,
            store,
            extractorVersion = 2,
        ).ensure(1, repository.toString(), revision)

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
        assertEquals(RepositoryIntelligenceManifestKey(1, revision, 1, 1), first.manifestKey)
        assertEquals(files.keys.sorted(), first.nodes.filter {
            it.path != null && it.kind !in setOf(INTELLIGENCE_NODE_MODULE, INTELLIGENCE_NODE_SYMBOL)
        }.mapNotNull { it.path }.sorted())
        assertTrue(first.nodes.any { it.kind == INTELLIGENCE_NODE_SYMBOL && it.attributes["qualifiedName"] == "example.Service" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_IMPORTS })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_IMPORTS && it.provenance.any { provenance -> provenance.ruleId == "kotlin-import-resolution" } })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_TESTS && it.evidencePath?.endsWith("ServiceTest.kt") == true })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_DOCUMENTS && it.evidencePath == "docs/adrs/001-service.md" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_DEPENDS_ON && it.evidencePath == "frontend/build.gradle.kts" })
        assertTrue(first.nodes.any { it.kind == INTELLIGENCE_NODE_PROJECT })
        assertTrue(first.nodes.single { it.path == "frontend/src/main/kotlin/example/App.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_UI))
        assertTrue(first.nodes.single { it.path == "backend/src/main/kotlin/example/Routes.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_TRANSPORT_SERVER))
        assertTrue(first.nodes.single { it.path == "backend/src/main/kotlin/example/Dto.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_MODEL))
        assertTrue(first.nodes.single { it.path == "backend/src/main/kotlin/example/OrderAuthority.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_AUTHORITY))
        assertTrue(first.nodes.single { it.path == "backend/src/main/kotlin/example/OrderRepository.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_PERSISTENCE))
        assertTrue(first.nodes.single { it.path == "backend/src/main/kotlin/example/OrderProjection.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL }.roles.contains(INTELLIGENCE_ROLE_PROJECTION))
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_ADMITS_COMMAND && it.evidencePath == "backend/src/main/kotlin/example/Routes.kt" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_APPENDS_RECORD && it.evidencePath == "backend/src/main/kotlin/example/OrderAuthority.kt" })
        assertTrue(first.edges.any { it.kind == INTELLIGENCE_EDGE_DERIVES_PROJECTION && it.evidencePath == "backend/src/main/kotlin/example/OrderAuthority.kt" })
        assertTrue(first.unresolvedBoundaries.any { it.kind == "WILDCARD_IMPORT" && it.path == "backend/src/main/kotlin/example/WildcardConsumer.kt" })
        assertEquals(INTELLIGENCE_NODE_SOURCE, first.nodes.single {
            it.path == "backend/src/main/kotlin/example/Specification.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(INTELLIGENCE_NODE_DOCUMENT, first.nodes.single {
            it.path == "docs/latest.md" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(INTELLIGENCE_NODE_TEST, first.nodes.single {
            it.path == "test/helpers.kt" && it.kind != INTELLIGENCE_NODE_SYMBOL
        }.kind)
        assertEquals(RepositoryIntelligenceEnsureDisposition.BUILT, firstEnsure.disposition)
        assertEquals(RepositoryIntelligenceEnsureDisposition.REUSED, repeatedEnsure.disposition)
        assertEquals(RepositoryIntelligenceEnsureDisposition.BUILT, policyBumpedEnsure.disposition)
        assertEquals(RepositoryIntelligenceEnsureDisposition.BUILT, extractorBumpedEnsure.disposition)
        assertEquals(RepositoryIntelligenceManifestKey(1, revision, 1, 2), policyBumpedEnsure.graph.manifestKey)
        assertEquals(RepositoryIntelligenceManifestKey(1, revision, 2, 1), extractorBumpedEnsure.graph.manifestKey)
        assertTrue(lifecycleStore.load().any {
            it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1) && it.state == RepositoryIntelligenceManifestState.ABSENT
        })
        assertTrue(lifecycleStore.load().any {
            it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1) && it.state == RepositoryIntelligenceManifestState.BUILDING
        })
        assertTrue(lifecycleStore.load().any {
            it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1) &&
                it.state == RepositoryIntelligenceManifestState.READY &&
                it.disposition == RepositoryIntelligenceEnsureDisposition.REUSED
        })
        assertTrue(traceStore.load().any {
            it.kind == RepositoryIntelligenceTraceSpanKind.MANIFEST_EXTRACT &&
                it.status == RepositoryIntelligenceEnsureDisposition.BUILT.name &&
                it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1)
        })
        assertTrue(traceStore.load().any {
            it.kind == RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE &&
                it.status == RepositoryIntelligenceEnsureDisposition.REUSED.name &&
                it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1)
        })
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
        assertEquals(listOf(first, policyBumpedEnsure.graph, extractorBumpedEnsure.graph), FileRepositoryIntelligenceGraphStore(state).load())

        createEpic(workspace)
        val successor = importer.import(1, repository.toString(), revision)

        assertNotEquals(first.orchardAuthorityHash, successor.orchardAuthorityHash)
        assertEquals(revision, successor.repositoryRevision)
        assertTrue(successor.nodes.any { it.kind == INTELLIGENCE_NODE_WORK_ITEM && it.label == "Imported intelligence" })
        assertEquals(4, FileRepositoryIntelligenceGraphStore(state).load().size)

        testApplication {
            application {
                workspaceApi(workspace, repositoryIntelligenceImporter = importer)
            }
            val response = client.get("/api/projects/1/repository-intelligence")
            assertEquals(HttpStatusCode.OK, response.status)
            val projected = Json.decodeFromString<RepositoryIntelligenceGraph>(response.bodyAsText())
            assertEquals(successor.hash, projected.hash)
            assertEquals(files.size, projected.coverage.trackedFileCount)

            val lifecycleResponse = client.get("/api/projects/1/repository-intelligence/lifecycle")
            assertEquals(HttpStatusCode.OK, lifecycleResponse.status)
            val lifecycleProjection = Json.decodeFromString<List<RepositoryIntelligenceManifestLifecycleProjection>>(lifecycleResponse.bodyAsText())
            assertTrue(lifecycleProjection.any {
                it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1) &&
                    it.latestState == RepositoryIntelligenceManifestState.READY
            })

            val traceResponse = client.get("/api/projects/1/repository-intelligence/traces")
            assertEquals(HttpStatusCode.OK, traceResponse.status)
            val traceProjection = Json.decodeFromString<List<RepositoryIntelligenceTraceSpan>>(traceResponse.bodyAsText())
            assertTrue(traceProjection.any {
                it.kind == RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE &&
                    it.manifestKey == RepositoryIntelligenceManifestKey(1, revision, 1, 1)
            })
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