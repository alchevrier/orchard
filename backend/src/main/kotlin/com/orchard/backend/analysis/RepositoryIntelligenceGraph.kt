package com.orchard.backend.analysis

import com.orchard.backend.agent.sha256Content
import com.orchard.backend.workspace.ProjectGenesisView
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val INTELLIGENCE_NODE_REPOSITORY = "REPOSITORY"
const val INTELLIGENCE_NODE_MODULE = "MODULE"
const val INTELLIGENCE_NODE_SOURCE = "SOURCE_FILE"
const val INTELLIGENCE_NODE_TEST = "TEST_FILE"
const val INTELLIGENCE_NODE_ADR = "ADR"
const val INTELLIGENCE_NODE_BUILD = "BUILD_FILE"
const val INTELLIGENCE_NODE_WORKFLOW = "WORKFLOW_FILE"
const val INTELLIGENCE_NODE_DOCUMENT = "DOCUMENT"
const val INTELLIGENCE_NODE_CONFIG = "CONFIG_FILE"
const val INTELLIGENCE_NODE_SCRIPT = "SCRIPT"
const val INTELLIGENCE_NODE_ASSET = "ASSET"
const val INTELLIGENCE_NODE_SYMBOL = "SYMBOL"
const val INTELLIGENCE_NODE_PROJECT = "ORCHARD_PROJECT"
const val INTELLIGENCE_NODE_WORK_ITEM = "ORCHARD_WORK_ITEM"
const val INTELLIGENCE_NODE_COMPONENT = "ORCHARD_COMPONENT"
const val INTELLIGENCE_NODE_DECISION = "ORCHARD_DECISION"
const val INTELLIGENCE_NODE_RUN = "ORCHARD_WORKFLOW_RUN"
const val INTELLIGENCE_NODE_EVIDENCE = "ORCHARD_EVIDENCE"

const val INTELLIGENCE_EDGE_CONTAINS = "CONTAINS"
const val INTELLIGENCE_EDGE_BINDS = "BINDS"
const val INTELLIGENCE_EDGE_DECLARES = "DECLARES"
const val INTELLIGENCE_EDGE_IMPORTS = "IMPORTS"
const val INTELLIGENCE_EDGE_DEPENDS_ON = "DEPENDS_ON"
const val INTELLIGENCE_EDGE_TESTS = "TESTS"
const val INTELLIGENCE_EDGE_DOCUMENTS = "DOCUMENTS"
const val INTELLIGENCE_EDGE_MAPS_TO = "MAPS_TO"
const val INTELLIGENCE_EDGE_AFFECTS = "AFFECTS"
const val INTELLIGENCE_EDGE_EXECUTES = "EXECUTES"
const val INTELLIGENCE_EDGE_PRODUCES = "PRODUCES"
const val INTELLIGENCE_EDGE_PROVES = "PROVES"

@Serializable
data class RepositoryIntelligenceNode(
    val nodeId: String,
    val kind: String,
    val label: String,
    val path: String? = null,
    val contentHash: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class RepositoryIntelligenceEdge(
    val edgeId: String,
    val kind: String,
    val fromNodeId: String,
    val toNodeId: String,
    val evidencePath: String? = null,
    val rationale: String,
)

@Serializable
data class RepositoryIntelligenceCoverage(
    val trackedFileCount: Int,
    val contentAddressedFileCount: Int,
    val analyzedTextFileCount: Int,
    val opaqueFileCount: Int,
    val nodeCount: Int,
    val edgeCount: Int,
)

@Serializable
data class RepositoryIntelligenceGraph(
    val graphId: Long,
    val projectId: Int,
    val repositoryRevision: String,
    val genesisRevision: Int,
    val orchardAuthorityHash: String,
    val nodes: List<RepositoryIntelligenceNode>,
    val edges: List<RepositoryIntelligenceEdge>,
    val coverage: RepositoryIntelligenceCoverage,
    val importedAt: String = Instant.now().toString(),
    val hash: String,
)

interface RepositoryIntelligenceGraphStore {
    fun load(): List<RepositoryIntelligenceGraph>
    fun appendNext(create: (graphId: Long) -> RepositoryIntelligenceGraph): RepositoryIntelligenceGraph
}

class TransientRepositoryIntelligenceGraphStore : RepositoryIntelligenceGraphStore {
    private val graphs = mutableListOf<RepositoryIntelligenceGraph>()

    @Synchronized
    override fun load(): List<RepositoryIntelligenceGraph> = graphs.toList()

    @Synchronized
    override fun appendNext(create: (graphId: Long) -> RepositoryIntelligenceGraph): RepositoryIntelligenceGraph {
        val graph = create(graphs.size + 1L)
        validateRepositoryIntelligenceGraph(graph, graphs)
        graphs += graph
        return graph
    }
}

class FileRepositoryIntelligenceGraphStore(private val directory: Path) : RepositoryIntelligenceGraphStore {
    private val path = directory.resolve("repository-intelligence-graphs.jsonl")
    private val lockPath = directory.resolve("repository-intelligence-graphs.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<RepositoryIntelligenceGraph> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<RepositoryIntelligenceGraph> = mutableListOf<RepositoryIntelligenceGraph>().also { graphs ->
        loadRecoverableJsonl(path, "repository-intelligence-graphs") { line, recordNumber ->
            val envelope = json.decodeFromString<RepositoryIntelligenceGraphEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) {
                "Unsupported repository intelligence graph format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in repository intelligence graph $recordNumber"
            }
            validateRepositoryIntelligenceGraph(envelope.value, graphs)
            graphs += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (graphId: Long) -> RepositoryIntelligenceGraph): RepositoryIntelligenceGraph {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val graphs = loadUnlocked()
                val graph = create(graphs.size + 1L)
                validateRepositoryIntelligenceGraph(graph, graphs)
                val payload = json.encodeToString(graph)
                val line = json.encodeToString(
                    RepositoryIntelligenceGraphEnvelope(value = graph, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                graph
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

class RepositoryIntelligenceImporter(
    private val workspace: WorkspaceStore,
    private val store: RepositoryIntelligenceGraphStore = TransientRepositoryIntelligenceGraphStore(),
) {
    private val json = Json { encodeDefaults = true }

    init {
        store.load()
    }

    fun latest(projectId: Int, repositoryRevision: String? = null): RepositoryIntelligenceGraph? = store.load().lastOrNull {
        it.projectId == projectId && (repositoryRevision == null || it.repositoryRevision == repositoryRevision)
    }

    fun current(projectId: Int): RepositoryIntelligenceGraph? {
        val repository = workspace.snapshot(0).repositories[projectId]?.takeIf { it.available } ?: return null
        val root = Path.of(repository.path).toRealPath()
        val revision = gitText(root, listOf("rev-parse", "--verify", "HEAD")).trim()
        return import(projectId, repository.path, revision)
    }

    @Synchronized
    fun import(projectId: Int, repositoryPath: String, repositoryRevision: String): RepositoryIntelligenceGraph {
        val projection = authorityProjection(projectId)
        val authorityHash = stagedPlanHash(json.encodeToString(projection))
        val genesisRevision = projection.genesis?.revision?.revision ?: 0
        store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
                it.genesisRevision == genesisRevision &&
                it.orchardAuthorityHash == authorityHash
        }?.let { return it }
        val root = Path.of(repositoryPath).toRealPath()
        val trackedEntries = gitTrackedEntries(root, repositoryRevision)
        val importedFiles = trackedEntries.map { entry -> importFile(root, repositoryRevision, entry) }
        return store.appendNext { graphId ->
            buildGraph(graphId, projectId, repositoryRevision, authorityHash, projection, importedFiles)
        }
    }

    private fun buildGraph(
        graphId: Long,
        projectId: Int,
        repositoryRevision: String,
        authorityHash: String,
        authority: OrchardAuthorityProjection,
        files: List<ImportedFile>,
    ): RepositoryIntelligenceGraph {
        val nodes = linkedMapOf<String, RepositoryIntelligenceNode>()
        val edges = linkedMapOf<String, RepositoryIntelligenceEdge>()
        fun addNode(node: RepositoryIntelligenceNode) {
            require(nodes.putIfAbsent(node.nodeId, node) == null) { "Duplicate repository intelligence node ${node.nodeId}" }
        }
        fun addEdge(kind: String, from: String, to: String, evidencePath: String?, rationale: String) {
            val edgeId = stableId("edge", "$kind\n$from\n$to\n${evidencePath.orEmpty()}")
            edges.putIfAbsent(edgeId, RepositoryIntelligenceEdge(edgeId, kind, from, to, evidencePath, rationale))
        }

        val repositoryNodeId = "repository:$projectId"
        addNode(RepositoryIntelligenceNode(
            repositoryNodeId,
            INTELLIGENCE_NODE_REPOSITORY,
            "Repository at $repositoryRevision",
            attributes = mapOf("revision" to repositoryRevision),
        ))
        val projectNodeId = "orchard-project:$projectId"
        val project = authority.entities.single { it.id == projectId }
        addNode(RepositoryIntelligenceNode(
            projectNodeId,
            INTELLIGENCE_NODE_PROJECT,
            project.title,
            attributes = mapOf("entityId" to project.id.toString(), "status" to project.status.toString()),
        ))
        addEdge(INTELLIGENCE_EDGE_BINDS, projectNodeId, repositoryNodeId, null, "The Orchard project is bound to this repository revision.")

        val moduleRoots = moduleRoots(files)
        moduleRoots.forEach { root ->
            val moduleId = moduleNodeId(root)
            addNode(RepositoryIntelligenceNode(
                moduleId,
                INTELLIGENCE_NODE_MODULE,
                root.ifEmpty { "root" },
                path = root.ifEmpty { "." },
            ))
            addEdge(INTELLIGENCE_EDGE_CONTAINS, repositoryNodeId, moduleId, null, "The repository contains this build module.")
        }

        files.forEach { file ->
            addNode(RepositoryIntelligenceNode(
                nodeId = fileNodeId(file.path),
                kind = file.kind,
                label = file.path.substringAfterLast('/'),
                path = file.path,
                contentHash = file.contentHash,
                attributes = linkedMapOf(
                    "sizeBytes" to file.sizeBytes.toString(),
                    "opaque" to file.opaque.toString(),
                    "language" to language(file.path),
                ),
            ))
            val moduleRoot = moduleRoots.filter { it.isEmpty() || file.path == it || file.path.startsWith("$it/") }
                .maxByOrNull { it.length }
            addEdge(
                INTELLIGENCE_EDGE_CONTAINS,
                moduleRoot?.let(::moduleNodeId) ?: repositoryNodeId,
                fileNodeId(file.path),
                file.path,
                "The tracked file belongs to this repository scope.",
            )
        }

        val symbols = files.flatMap(::extractSymbols)
        val symbolsByQualifiedName = symbols.groupBy { it.qualifiedName }
        val symbolsBySimpleName = symbols.groupBy { it.name }
        symbols.forEach { symbol ->
            addNode(RepositoryIntelligenceNode(
                symbol.nodeId,
                INTELLIGENCE_NODE_SYMBOL,
                symbol.name,
                path = symbol.path,
                contentHash = files.single { it.path == symbol.path }.contentHash,
                attributes = mapOf("qualifiedName" to symbol.qualifiedName, "declarationKind" to symbol.kind),
            ))
            addEdge(
                INTELLIGENCE_EDGE_DECLARES,
                fileNodeId(symbol.path),
                symbol.nodeId,
                symbol.path,
                "The source file declares this symbol.",
            )
        }
        files.forEach { file ->
            extractImports(file).forEach { importedName ->
                val targets = symbolsByQualifiedName[importedName]
                    ?: symbolsBySimpleName[importedName.substringAfterLast('.')].orEmpty()
                targets.forEach { target ->
                    addEdge(
                        INTELLIGENCE_EDGE_IMPORTS,
                        fileNodeId(file.path),
                        target.nodeId,
                        file.path,
                        "The source imports the declared symbol ${target.qualifiedName}.",
                    )
                }
            }
        }

        correlateModules(files, moduleRoots).forEach { (from, to, path) ->
            addEdge(INTELLIGENCE_EDGE_DEPENDS_ON, moduleNodeId(from), moduleNodeId(to), path, "The build declares a module dependency.")
        }
        correlateTests(files).forEach { (test, source) ->
            addEdge(INTELLIGENCE_EDGE_TESTS, fileNodeId(test.path), fileNodeId(source.path), test.path, "The test name correlates to the source artifact.")
        }
        correlateDocuments(files).forEach { (document, target) ->
            addEdge(
                INTELLIGENCE_EDGE_DOCUMENTS,
                fileNodeId(document.path),
                fileNodeId(target.path),
                document.path,
                "The document explicitly references the tracked repository path.",
            )
        }

        addOrchardAuthority(authority, files, repositoryNodeId, projectNodeId, ::addNode, ::addEdge)

        val orderedNodes = nodes.values.sortedBy { it.nodeId }
        val orderedEdges = edges.values.sortedBy { it.edgeId }
        val coverage = RepositoryIntelligenceCoverage(
            trackedFileCount = files.size,
            contentAddressedFileCount = files.count { it.contentHash.isNotBlank() },
            analyzedTextFileCount = files.count { !it.opaque },
            opaqueFileCount = files.count { it.opaque },
            nodeCount = orderedNodes.size,
            edgeCount = orderedEdges.size,
        )
        val unsigned = RepositoryIntelligenceGraph(
            graphId = graphId,
            projectId = projectId,
            repositoryRevision = repositoryRevision,
            genesisRevision = authority.genesis?.revision?.revision ?: 0,
            orchardAuthorityHash = authorityHash,
            nodes = orderedNodes,
            edges = orderedEdges,
            coverage = coverage,
            hash = "",
        )
        return unsigned.copy(hash = repositoryIntelligenceGraphHash(unsigned))
    }

    private fun addOrchardAuthority(
        authority: OrchardAuthorityProjection,
        files: List<ImportedFile>,
        repositoryNodeId: String,
        projectNodeId: String,
        addNode: (RepositoryIntelligenceNode) -> Unit,
        addEdge: (String, String, String, String?, String) -> Unit,
    ) {
        authority.entities.filter { it.id != authority.projectId }.forEach { entity ->
            val nodeId = "orchard-work-item:${entity.id}"
            addNode(RepositoryIntelligenceNode(
                nodeId,
                INTELLIGENCE_NODE_WORK_ITEM,
                entity.title,
                attributes = mapOf("entityId" to entity.id.toString(), "entityType" to entity.type.toString()),
            ))
            val parentId = if (entity.parentId == authority.projectId) projectNodeId else "orchard-work-item:${entity.parentId}"
            addEdge(INTELLIGENCE_EDGE_CONTAINS, parentId, nodeId, null, "Orchard authority contains this work item.")
        }
        authority.genesis?.revision?.let { genesis ->
            genesis.components.forEach { component ->
                val nodeId = "orchard-component:${authority.projectId}:${component.componentId}"
                addNode(RepositoryIntelligenceNode(
                    nodeId,
                    INTELLIGENCE_NODE_COMPONENT,
                    component.name,
                    attributes = mapOf("componentId" to component.componentId, "responsibility" to component.responsibility),
                ))
                addEdge(INTELLIGENCE_EDGE_CONTAINS, projectNodeId, nodeId, null, "Genesis defines this architecture component.")
                component.repositoryPaths.forEach { path ->
                    files.filter { it.path == path || it.path.startsWith(path.trimEnd('/') + "/") }.forEach { file ->
                        addEdge(INTELLIGENCE_EDGE_MAPS_TO, nodeId, fileNodeId(file.path), file.path, "The admitted component maps to this repository path.")
                    }
                }
            }
            genesis.components.forEach { component ->
                component.dependsOn.forEach { dependency ->
                    addEdge(
                        INTELLIGENCE_EDGE_DEPENDS_ON,
                        "orchard-component:${authority.projectId}:${component.componentId}",
                        "orchard-component:${authority.projectId}:$dependency",
                        null,
                        "Genesis records this component dependency.",
                    )
                }
            }
            genesis.decisions.forEach { decision ->
                val nodeId = "orchard-decision:${authority.projectId}:${decision.decisionId}"
                addNode(RepositoryIntelligenceNode(
                    nodeId,
                    INTELLIGENCE_NODE_DECISION,
                    decision.title,
                    attributes = mapOf("decisionId" to decision.decisionId, "status" to decision.status, "decision" to decision.decision),
                ))
                addEdge(INTELLIGENCE_EDGE_CONTAINS, projectNodeId, nodeId, null, "Genesis records this architecture decision.")
                decision.componentIds.forEach { componentId ->
                    addEdge(
                        INTELLIGENCE_EDGE_AFFECTS,
                        nodeId,
                        "orchard-component:${authority.projectId}:$componentId",
                        null,
                        "The decision identifies the affected component.",
                    )
                }
            }
        }
        authority.runs.forEach { run ->
            val runNodeId = "orchard-run:${run.runId}"
            addNode(RepositoryIntelligenceNode(
                runNodeId,
                INTELLIGENCE_NODE_RUN,
                run.context.title,
                attributes = mapOf("runId" to run.runId.toString(), "state" to run.state),
            ))
            addEdge(INTELLIGENCE_EDGE_EXECUTES, runNodeId, "orchard-work-item:${run.context.workItemId}", null, "The workflow run executes this work item.")
            addEdge(INTELLIGENCE_EDGE_MAPS_TO, runNodeId, repositoryNodeId, null, "The workflow run is pinned to this repository.")
            run.evidence.forEach { evidence ->
                val evidenceNodeId = "orchard-evidence:${evidence.evidenceId}"
                addNode(RepositoryIntelligenceNode(
                    evidenceNodeId,
                    INTELLIGENCE_NODE_EVIDENCE,
                    evidence.kind,
                    contentHash = evidence.outputHash,
                    attributes = mapOf(
                        "evidenceId" to evidence.evidenceId.toString(),
                        "revision" to evidence.revision,
                        "passed" to evidence.passed.toString(),
                        "summary" to evidence.summary,
                    ),
                ))
                addEdge(INTELLIGENCE_EDGE_PRODUCES, runNodeId, evidenceNodeId, null, "The workflow run produced this evidence.")
                addEdge(INTELLIGENCE_EDGE_PROVES, evidenceNodeId, repositoryNodeId, null, "The evidence is revision-bound repository proof.")
            }
        }
    }

    private fun authorityProjection(projectId: Int): OrchardAuthorityProjection {
        val entities = workspace.entities()
        val project = entities.single { it.id == projectId && it.type == 1 }
        val descendants = entities.filter { entity ->
            entity.id == project.id || generateSequence(entity.parentId) { parentId ->
                entities.singleOrNull { it.id == parentId }?.parentId?.takeIf { it > 0 }
            }.any { it == projectId }
        }
        val snapshot = workspace.snapshot(0)
        return OrchardAuthorityProjection(
            projectId,
            descendants.sortedBy { it.id },
            snapshot.projectGenesis.singleOrNull { it.projectId == projectId },
            snapshot.workflowRuns.filter { it.context.projectId == projectId }.sortedBy { it.runId },
        )
    }

    private fun importFile(root: Path, revision: String, entry: GitTreeEntry): ImportedFile {
        if (entry.type == "commit") {
            return ImportedFile(
                entry.path,
                INTELLIGENCE_NODE_ASSET,
                sha256Content(entry.objectId),
                entry.objectId.length.toLong(),
                opaque = true,
                content = null,
            )
        }
        return withGitOutput(root, listOf("show", "$revision:${entry.path}")) { output ->
            val size = Files.size(output)
            val contentHash = sha256(output)
            val bytes = if (size <= MAX_ANALYZED_FILE_BYTES) Files.readAllBytes(output) else null
            val opaque = bytes == null || bytes.any { it == 0.toByte() }
            ImportedFile(
                entry.path,
                classify(entry.path),
                contentHash,
                size,
                opaque,
                bytes?.takeUnless { opaque }?.toString(Charsets.UTF_8),
            )
        }
    }

    private fun gitTrackedEntries(root: Path, revision: String): List<GitTreeEntry> = splitZero(
        gitBytes(root, listOf("ls-tree", "-r", "-z", revision))
    ).filter(String::isNotBlank).map { record ->
        val metadata = record.substringBefore('\t').split(' ')
        require(metadata.size == 3 && '\t' in record) { "Git repository tree entry is invalid" }
        GitTreeEntry(metadata[1], metadata[2], record.substringAfter('\t'))
    }.sortedBy { it.path }

    private fun gitText(root: Path, arguments: List<String>): String = gitBytes(root, arguments).toString(Charsets.UTF_8)

    private fun gitBytes(root: Path, arguments: List<String>): ByteArray = withGitOutput(root, arguments, Files::readAllBytes)

    private fun <T> withGitOutput(root: Path, arguments: List<String>, read: (Path) -> T): T {
        val outputPath = Files.createTempFile("orchard-intelligence-git-", ".out")
        val errorPath = Files.createTempFile("orchard-intelligence-git-", ".log")
        try {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
                .redirectOutput(outputPath.toFile())
                .redirectError(errorPath.toFile())
                .start()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Git repository intelligence command timed out")
            }
            require(process.exitValue() == 0) {
                "Git repository intelligence command failed: ${Files.readString(errorPath).take(512)}"
            }
            return read(outputPath)
        } finally {
            Files.deleteIfExists(outputPath)
            Files.deleteIfExists(errorPath)
        }
    }

    private fun splitZero(bytes: ByteArray): List<String> {
        val values = mutableListOf<String>()
        var start = 0
        bytes.forEachIndexed { index, byte ->
            if (byte == 0.toByte()) {
                values += bytes.copyOfRange(start, index).toString(Charsets.UTF_8)
                start = index + 1
            }
        }
        if (start < bytes.size) values += bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
        return values
    }

    private fun moduleRoots(files: List<ImportedFile>): List<String> {
        val roots = files.filter { it.path.substringAfterLast('/') in BUILD_FILE_NAMES }
            .map { it.path.substringBeforeLast('/', "") }
            .toMutableSet()
        roots += ""
        files.filter { it.path == "settings.gradle.kts" || it.path == "settings.gradle" }.forEach { settings ->
            MODULE_INCLUDE.findAll(settings.content.orEmpty()).flatMap { match ->
                match.groupValues[1].split(',').asSequence()
            }.map { it.trim().trim('"', '\'').removePrefix(":").replace(':', '/') }
                .filter(String::isNotBlank)
                .forEach(roots::add)
        }
        return roots.sortedWith(compareBy<String> { it.count { char -> char == '/' } }.thenBy { it })
    }

    private fun extractSymbols(file: ImportedFile): List<ImportedSymbol> {
        if (file.kind !in setOf(INTELLIGENCE_NODE_SOURCE, INTELLIGENCE_NODE_TEST) || file.content == null) return emptyList()
        val packageName = PACKAGE_DECLARATION.find(file.content)?.groupValues?.get(1).orEmpty()
        return SYMBOL_DECLARATION.findAll(file.content).map { match ->
            val kind = match.groupValues[1].replace(Regex("\\s+"), " ").trim()
            val name = match.groupValues[2]
            val qualifiedName = if (packageName.isBlank()) name else "$packageName.$name"
            ImportedSymbol(stableId("symbol", "${file.path}\n$qualifiedName"), file.path, name, qualifiedName, kind)
        }.distinctBy { it.nodeId }.toList()
    }

    private fun extractImports(file: ImportedFile): Set<String> = file.content?.let { content ->
        IMPORT_DECLARATION.findAll(content).map { it.groupValues[1].removeSuffix(".*") }.toSet()
    }.orEmpty()

    private fun correlateModules(files: List<ImportedFile>, roots: List<String>): List<Triple<String, String, String>> = files
        .filter { it.path.substringAfterLast('/') in BUILD_FILE_NAMES && it.content != null }
        .flatMap { file ->
            val from = roots.filter { it.isEmpty() || file.path.startsWith("$it/") }.maxByOrNull { it.length } ?: ""
            PROJECT_DEPENDENCY.findAll(requireNotNull(file.content)).mapNotNull { match ->
                val target = match.groupValues[1].removePrefix(":").replace(':', '/')
                target.takeIf { it in roots }?.let { Triple(from, it, file.path) }
            }.toList()
        }

    private fun correlateTests(files: List<ImportedFile>): List<Pair<ImportedFile, ImportedFile>> {
        val sourcesByStem = files.filter { it.kind == INTELLIGENCE_NODE_SOURCE }
            .groupBy { it.path.substringAfterLast('/').substringBeforeLast('.').lowercase() }
        return files.filter { it.kind == INTELLIGENCE_NODE_TEST }.mapNotNull { test ->
            val stem = test.path.substringAfterLast('/').substringBeforeLast('.')
                .replace(Regex("(Test|Tests|Spec|IT)$", RegexOption.IGNORE_CASE), "")
                .lowercase()
            sourcesByStem[stem]?.singleOrNull()?.let { test to it }
        }
    }

    private fun correlateDocuments(files: List<ImportedFile>): List<Pair<ImportedFile, ImportedFile>> {
        val byPath = files.associateBy { it.path }
        return files.filter { it.kind in setOf(INTELLIGENCE_NODE_ADR, INTELLIGENCE_NODE_DOCUMENT) && it.content != null }
            .flatMap { document ->
                DOCUMENT_PATH.findAll(requireNotNull(document.content)).mapNotNull { match ->
                    byPath[match.groupValues[1].removePrefix("./")]?.takeIf { it.path != document.path }?.let { document to it }
                }.distinctBy { it.second.path }.toList()
            }
    }

    private fun classify(path: String): String {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        val segments = lower.split('/')
        val sourceTestName = extension(path) in SOURCE_EXTENSIONS && (
            Regex("^(test_|.*_test\\.|.*(?:Test|Tests|Spec|IT)\\.)").containsMatchIn(path.substringAfterLast('/'))
        )
        return when {
            lower.startsWith("docs/adrs/") || Regex("(^|/)(adr|decision)[-_ ]?\\d+.*\\.md$").containsMatchIn(lower) -> INTELLIGENCE_NODE_ADR
            segments.dropLast(1).any { it in setOf("test", "tests", "__tests__") } || sourceTestName -> INTELLIGENCE_NODE_TEST
            name in BUILD_FILE_NAMES || name in setOf("gradle.properties", "libs.versions.toml") -> INTELLIGENCE_NODE_BUILD
            lower.startsWith(".github/workflows/") || lower.startsWith(".gitlab/") || name == "jenkinsfile" -> INTELLIGENCE_NODE_WORKFLOW
            extension(path) in SOURCE_EXTENSIONS -> INTELLIGENCE_NODE_SOURCE
            extension(path) in setOf("md", "mdx", "rst", "adoc") -> INTELLIGENCE_NODE_DOCUMENT
            extension(path) in setOf("sh", "bash", "zsh", "bat", "cmd", "ps1") -> INTELLIGENCE_NODE_SCRIPT
            extension(path) in setOf("json", "yaml", "yml", "toml", "xml", "properties", "conf", "ini") -> INTELLIGENCE_NODE_CONFIG
            else -> INTELLIGENCE_NODE_ASSET
        }
    }

    private fun language(path: String): String = when (extension(path)) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "js", "jsx" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "py" -> "Python"
        "go" -> "Go"
        "rs" -> "Rust"
        "c", "h" -> "C"
        "cc", "cpp", "cxx", "hpp" -> "C++"
        "swift" -> "Swift"
        "rb" -> "Ruby"
        "php" -> "PHP"
        else -> ""
    }

    private fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()
    private fun fileNodeId(path: String): String = stableId("file", path)
    private fun moduleNodeId(path: String): String = stableId("module", path.ifEmpty { "." })
    private fun stableId(kind: String, identity: String): String = "$kind:${sha256Content(identity).take(24)}"
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    @Serializable
    private data class OrchardAuthorityProjection(
        val projectId: Int,
        val entities: List<WorkspaceEntity>,
        val genesis: ProjectGenesisView?,
        val runs: List<WorkflowRunView>,
    )

    private data class ImportedFile(
        val path: String,
        val kind: String,
        val contentHash: String,
        val sizeBytes: Long,
        val opaque: Boolean,
        val content: String?,
    )

    private data class GitTreeEntry(
        val type: String,
        val objectId: String,
        val path: String,
    )

    private data class ImportedSymbol(
        val nodeId: String,
        val path: String,
        val name: String,
        val qualifiedName: String,
        val kind: String,
    )

    private companion object {
        const val MAX_ANALYZED_FILE_BYTES = 1024 * 1024
        const val GIT_TIMEOUT_SECONDS = 30L
        val BUILD_FILE_NAMES = setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json", "cargo.toml", "go.mod", "makefile",
        )
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "go", "rs",
            "c", "h", "cc", "cpp", "cxx", "hpp", "swift", "rb", "php",
        )
        val MODULE_INCLUDE = Regex("""include\(([^)]*)\)""")
        val PROJECT_DEPENDENCY = Regex("""project\(\s*["'](:[^"']+)["']\s*\)""")
        val PACKAGE_DECLARATION = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        val IMPORT_DECLARATION = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.*]*)""")
        val SYMBOL_DECLARATION = Regex(
            """(?m)^\s*(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|static\s+|suspend\s+|inline\s+|data\s+|value\s+)*(class|interface|object|enum\s+class|fun|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        val DOCUMENT_PATH = Regex("""(?:`|\()([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)(?:`|\))""")
    }
}

fun repositoryIntelligenceGraphHash(graph: RepositoryIntelligenceGraph): String = stagedPlanHash(
    intelligenceGraphJson.encodeToString(graph.copy(graphId = 0, importedAt = "", hash = ""))
)

private fun validateRepositoryIntelligenceGraph(
    graph: RepositoryIntelligenceGraph,
    previous: List<RepositoryIntelligenceGraph>,
) {
    require(graph.graphId == previous.size + 1L) { "Repository intelligence graph ID is not monotonic" }
    require(graph.projectId > 0 && graph.genesisRevision >= 0) { "Repository intelligence graph identity is invalid" }
    require(graph.repositoryRevision.matches(GIT_REVISION)) { "Repository intelligence graph revision is invalid" }
    require(graph.orchardAuthorityHash.matches(SHA256)) { "Repository intelligence authority hash is invalid" }
    require(graph.nodes.isNotEmpty() && graph.nodes.map { it.nodeId }.distinct().size == graph.nodes.size) {
        "Repository intelligence graph nodes are empty or duplicated"
    }
    require(graph.edges.map { it.edgeId }.distinct().size == graph.edges.size) {
        "Repository intelligence graph edges are duplicated"
    }
    val nodeIds = graph.nodes.mapTo(mutableSetOf()) { it.nodeId }
    require(graph.edges.all { it.fromNodeId in nodeIds && it.toNodeId in nodeIds && it.rationale.isNotBlank() }) {
        "Repository intelligence graph contains an invalid edge"
    }
    val fileNodes = graph.nodes.count { it.path != null && it.kind !in NON_FILE_NODE_KINDS }
    require(graph.coverage.trackedFileCount == fileNodes) { "Repository intelligence tracked-file coverage is incomplete" }
    require(graph.coverage.contentAddressedFileCount == fileNodes) { "Repository intelligence files are not fully content-addressed" }
    require(graph.coverage.analyzedTextFileCount + graph.coverage.opaqueFileCount == fileNodes) {
        "Repository intelligence text coverage is inconsistent"
    }
    require(graph.coverage.nodeCount == graph.nodes.size && graph.coverage.edgeCount == graph.edges.size) {
        "Repository intelligence graph coverage totals are inconsistent"
    }
    require(graph.hash == repositoryIntelligenceGraphHash(graph)) { "Repository intelligence graph hash is invalid" }
}

@Serializable
private data class RepositoryIntelligenceGraphEnvelope(
    val version: Int = 1,
    val value: RepositoryIntelligenceGraph,
    val checksum: String,
)

private val intelligenceGraphJson = Json { encodeDefaults = true }
private val GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val NON_FILE_NODE_KINDS = setOf(
    INTELLIGENCE_NODE_REPOSITORY,
    INTELLIGENCE_NODE_MODULE,
    INTELLIGENCE_NODE_SYMBOL,
    INTELLIGENCE_NODE_PROJECT,
    INTELLIGENCE_NODE_WORK_ITEM,
    INTELLIGENCE_NODE_COMPONENT,
    INTELLIGENCE_NODE_DECISION,
    INTELLIGENCE_NODE_RUN,
    INTELLIGENCE_NODE_EVIDENCE,
)