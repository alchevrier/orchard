@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.analysis

import com.orchard.backend.agent.sha256Content
import com.orchard.backend.workspace.ProjectGenesisView
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.exactRepositoryScopePaths
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
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val INTELLIGENCE_NODE_REPOSITORY = "REPOSITORY"
const val INTELLIGENCE_NODE_MODULE = "MODULE"
const val INTELLIGENCE_NODE_SOURCE = "SOURCE_FILE"
const val INTELLIGENCE_NODE_CONFIG = "CONFIG_FILE"
const val INTELLIGENCE_NODE_SCRIPT = "SCRIPT"
const val INTELLIGENCE_NODE_TEST = "TEST_FILE"
const val INTELLIGENCE_NODE_ADR = "ADR"
const val INTELLIGENCE_NODE_BUILD = "BUILD_FILE"
const val INTELLIGENCE_NODE_WORKFLOW = "WORKFLOW_FILE"
const val INTELLIGENCE_NODE_DOCUMENT = "DOCUMENT"
const val INTELLIGENCE_NODE_ASSET = "ASSET"
const val INTELLIGENCE_NODE_SYMBOL = "SYMBOL"
const val INTELLIGENCE_NODE_PROJECT = "ORCHARD_PROJECT"
const val INTELLIGENCE_NODE_WORK_ITEM = "ORCHARD_WORK_ITEM"
const val INTELLIGENCE_NODE_COMPONENT = "ORCHARD_COMPONENT"
const val INTELLIGENCE_NODE_DECISION = "ORCHARD_DECISION"
const val INTELLIGENCE_NODE_RUN = "ORCHARD_WORKFLOW_RUN"
const val INTELLIGENCE_NODE_EVIDENCE = "ORCHARD_EVIDENCE"

const val INTELLIGENCE_ROLE_UNCLASSIFIED = "UNCLASSIFIED"
const val INTELLIGENCE_ROLE_TRANSPORT_SERVER = "TRANSPORT_SERVER"
const val INTELLIGENCE_ROLE_MODEL = "MODEL"
const val INTELLIGENCE_ROLE_SERVICE = "SERVICE"
const val INTELLIGENCE_ROLE_AUTHORITY = "AUTHORITY"
const val INTELLIGENCE_ROLE_PERSISTENCE = "PERSISTENCE"
const val INTELLIGENCE_ROLE_PROJECTION = "PROJECTION"
const val INTELLIGENCE_ROLE_UI = "UI"
const val INTELLIGENCE_ROLE_TEST = "TEST"
const val INTELLIGENCE_ROLE_TOOLCHAIN = "TOOLCHAIN"

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
const val INTELLIGENCE_EDGE_RECEIVES_EVENT = "RECEIVES_EVENT"
const val INTELLIGENCE_EDGE_EMITS_EVENT = "EMITS_EVENT"
const val INTELLIGENCE_EDGE_ADMITS_COMMAND = "ADMITS_COMMAND"
const val INTELLIGENCE_EDGE_APPENDS_RECORD = "APPENDS_RECORD"
const val INTELLIGENCE_EDGE_DERIVES_PROJECTION = "DERIVES_PROJECTION"

const val REPOSITORY_INTELLIGENCE_GRAPH_LOCAL_QUERY = "GRAPH_LOCAL_SCOPE_NEIGHBORS_V1"

@Serializable
data class RepositoryIntelligenceManifestKey(
    val repositoryId: Int,
    val commitHash: String,
    val extractorVersion: Int,
    val policyVersion: Int,
)

@Serializable
data class RepositoryIntelligenceProvenance(
    val ruleId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evidencePaths: List<String> = emptyList(),
    val extractorVersion: Int,
    val policyVersion: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val limitation: String? = null,
)

@Serializable
data class RepositoryIntelligenceUnresolvedBoundary(
    val boundaryId: String,
    val kind: String,
    val path: String,
    val detail: String,
    val handlingPolicy: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val nodeId: String? = null,
)

@Serializable
data class RepositoryIntelligenceContextTrace(
    val graphQuery: String,
    val acceptedScope: List<String>,
    val anchorPaths: List<String>,
    val selectedNodeIds: List<String>,
    val selectedPaths: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val omittedPaths: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val unresolvedBoundaryIds: List<String> = emptyList(),
)

internal data class RepositoryGraphLocalSelection(
    val acceptedScope: List<String>,
    val anchorPaths: List<String>,
    val selectedNodeIds: List<String>,
    val selectedPaths: List<String>,
    val omittedPaths: List<String>,
    val unresolvedBoundaryIds: List<String>,
) {
    fun toTrace() = RepositoryIntelligenceContextTrace(
        REPOSITORY_INTELLIGENCE_GRAPH_LOCAL_QUERY,
        acceptedScope,
        anchorPaths,
        selectedNodeIds,
        selectedPaths,
        omittedPaths,
        unresolvedBoundaryIds,
    )
}

internal fun graphLocalRepositoryAnalysisPaths(
    graph: RepositoryIntelligenceGraph,
    acceptedScope: List<String>,
    maxPaths: Int = 24,
): List<String> = graphLocalRepositoryAnalysisSelection(graph, acceptedScope, maxPaths).selectedPaths

internal fun graphLocalRepositoryAnalysisSelection(
    graph: RepositoryIntelligenceGraph,
    acceptedScope: List<String>,
    maxPaths: Int = 24,
): RepositoryGraphLocalSelection {
    require(maxPaths > 0) { "Graph-local analysis path limit must be positive" }
    val anchors = exactRepositoryScopePaths(acceptedScope)
        .toCollection(linkedSetOf())
    val nodesById = graph.nodes.associateBy { it.nodeId }
    val anchorNodeIds = graph.nodes.filter { it.path in anchors }.mapTo(linkedSetOf()) { it.nodeId }
    val relatedNodeIds = graph.edges.asSequence()
        .filter { it.kind in setOf(INTELLIGENCE_EDGE_IMPORTS, INTELLIGENCE_EDGE_TESTS, INTELLIGENCE_EDGE_DOCUMENTS) }
        .filter { it.fromNodeId in anchorNodeIds || it.toNodeId in anchorNodeIds }
        .flatMap { sequenceOf(it.fromNodeId, it.toNodeId) }
        .toCollection(linkedSetOf())
    val candidateNodeIds = (anchorNodeIds + relatedNodeIds).toList()
    val candidatePaths = candidateNodeIds.asSequence()
        .mapNotNull { nodesById[it]?.path }
        .distinct()
        .sortedWith(compareBy<String> { it !in anchors }.thenBy { it })
        .toList()
    val selectedPaths = candidatePaths.take(maxPaths)
    val selectedNodeIds = candidateNodeIds.filter { nodeId -> nodesById[nodeId]?.path in selectedPaths }
    val omittedPaths = (anchors.filterNot { it in selectedPaths } + candidatePaths.drop(maxPaths)).distinct()
    val unresolvedBoundaryIds = graph.unresolvedBoundaries.asSequence()
        .filter { boundary ->
            boundary.nodeId in selectedNodeIds || boundary.path in selectedPaths
        }
        .map { it.boundaryId }
        .distinct()
        .sorted()
        .toList()
    return RepositoryGraphLocalSelection(
        acceptedScope = acceptedScope,
        anchorPaths = anchors.toList(),
        selectedNodeIds = selectedNodeIds,
        selectedPaths = selectedPaths,
        omittedPaths = omittedPaths,
        unresolvedBoundaryIds = unresolvedBoundaryIds,
    )
}

@Serializable
data class RepositoryIntelligenceNode(
    val nodeId: String,
    val kind: String,
    val label: String,
    val path: String? = null,
    val contentHash: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val roles: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val provenance: List<RepositoryIntelligenceProvenance> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unresolvedBoundaryIds: List<String> = emptyList(),
)

@Serializable
data class RepositoryIntelligenceEdge(
    val edgeId: String,
    val kind: String,
    val fromNodeId: String,
    val toNodeId: String,
    val evidencePath: String? = null,
    val rationale: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val provenance: List<RepositoryIntelligenceProvenance> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unresolvedBoundaryIds: List<String> = emptyList(),
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val manifestKey: RepositoryIntelligenceManifestKey? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unresolvedBoundaries: List<RepositoryIntelligenceUnresolvedBoundary> = emptyList(),
    val importedAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
enum class RepositoryIntelligenceEnsureDisposition {
    BUILT,
    REUSED,
}

@Serializable
data class RepositoryIntelligenceEnsureResult(
    val disposition: RepositoryIntelligenceEnsureDisposition,
    val graph: RepositoryIntelligenceGraph,
)

@Serializable
enum class RepositoryIntelligenceRunEnsureStatus {
    READY,
    RUN_NOT_FOUND,
    REPOSITORY_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class RepositoryIntelligenceRunEnsureResult(
    val status: RepositoryIntelligenceRunEnsureStatus,
    val runId: Long,
    val message: String,
    val ensure: RepositoryIntelligenceEnsureResult? = null,
)

@Serializable
enum class RepositoryIntelligenceManifestState {
    ABSENT,
    BUILDING,
    READY,
    FAILED,
    STALE,
}

@Serializable
data class RepositoryIntelligenceManifestLifecycleRecord(
    val recordId: Long,
    val traceId: String,
    val projectId: Int,
    val repositoryRevision: String,
    val manifestKey: RepositoryIntelligenceManifestKey,
    val state: RepositoryIntelligenceManifestState,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val disposition: RepositoryIntelligenceEnsureDisposition? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val graphId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnosticCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnostic: String? = null,
    val recordedAt: String = Instant.now().toString(),
)

@Serializable
data class RepositoryIntelligenceManifestLifecycleProjection(
    val manifestKey: RepositoryIntelligenceManifestKey,
    val latestState: RepositoryIntelligenceManifestState,
    val projectId: Int,
    val repositoryRevision: String,
    val traceId: String,
    val recordedAt: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val latestDisposition: RepositoryIntelligenceEnsureDisposition? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val readyGraphId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnosticCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnostic: String? = null,
)

@Serializable
enum class RepositoryIntelligenceTraceSpanKind {
    MANIFEST_ENSURE,
    MANIFEST_EXTRACT,
    GRAPH_QUERY,
    CONTEXT_COMPILE,
}

@Serializable
data class RepositoryIntelligenceTraceSpan(
    val spanId: Long,
    val traceId: String,
    val kind: RepositoryIntelligenceTraceSpanKind,
    val status: String,
    val projectId: Int,
    val repositoryRevision: String,
    val elapsedMillis: Long,
    val recordedAt: String = Instant.now().toString(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val runId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val manifestKey: RepositoryIntelligenceManifestKey? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val graphId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val graphQuery: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptedScopeCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val anchorPathCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val selectedNodeCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val selectedPathCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val omittedPathCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val unresolvedBoundaryCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contextFileCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contextOmittedFileCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val contextBytes: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnosticCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val diagnostic: String? = null,
)

class RepositoryIntelligenceService(
    private val workspace: WorkspaceStore,
    private val importer: RepositoryIntelligenceImporter,
) {
    fun ensure(runId: Long): RepositoryIntelligenceRunEnsureResult {
        val run = workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == runId }
            ?: return RepositoryIntelligenceRunEnsureResult(
                RepositoryIntelligenceRunEnsureStatus.RUN_NOT_FOUND,
                runId,
                "The workflow run does not exist.",
            ).also {
                importer.recordEnsureServiceFailure(
                    runId = runId,
                    diagnosticCode = RepositoryIntelligenceRunEnsureStatus.RUN_NOT_FOUND.name,
                    diagnostic = it.message,
                )
            }
        val reservation = run.context.workspaceReservation
        val repositoryPath = reservation?.path ?: run.context.repository.path
        val repositoryRevision = reservation?.baseRevision ?: run.context.repository.commitHash
        if (repositoryPath.isBlank() || repositoryRevision.isBlank()) {
            return RepositoryIntelligenceRunEnsureResult(
                RepositoryIntelligenceRunEnsureStatus.REPOSITORY_UNAVAILABLE,
                runId,
                "The workflow run has no pinned repository context.",
            ).also {
                importer.recordEnsureServiceFailure(
                    runId = runId,
                    projectId = run.context.projectId,
                    repositoryRevision = repositoryRevision.ifBlank { null },
                    diagnosticCode = RepositoryIntelligenceRunEnsureStatus.REPOSITORY_UNAVAILABLE.name,
                    diagnostic = it.message,
                )
            }
        }
        val ensure = runCatching {
            importer.ensure(run.context.projectId, repositoryPath, repositoryRevision, runId = runId)
        }.getOrElse {
            return RepositoryIntelligenceRunEnsureResult(
                RepositoryIntelligenceRunEnsureStatus.STORAGE_UNAVAILABLE,
                runId,
                "Repository intelligence could not be ensured for the pinned workflow context.",
            )
        }
        return RepositoryIntelligenceRunEnsureResult(
            RepositoryIntelligenceRunEnsureStatus.READY,
            runId,
            "Repository intelligence is ready for the pinned workflow context.",
            ensure,
        )
    }
}

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
    private var cachedGraphs: List<RepositoryIntelligenceGraph>? = null

    @Synchronized
    override fun load(): List<RepositoryIntelligenceGraph> {
        cachedGraphs?.let { return it }
        Files.createDirectories(directory)
        val graphs = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
        cachedGraphs = graphs
        return graphs
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
                cachedGraphs = graphs + graph
                graph
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

interface RepositoryIntelligenceLifecycleStore {
    fun load(): List<RepositoryIntelligenceManifestLifecycleRecord>
    fun appendNext(
        create: (recordId: Long) -> RepositoryIntelligenceManifestLifecycleRecord,
    ): RepositoryIntelligenceManifestLifecycleRecord
}

class TransientRepositoryIntelligenceLifecycleStore : RepositoryIntelligenceLifecycleStore {
    private val records = mutableListOf<RepositoryIntelligenceManifestLifecycleRecord>()

    @Synchronized
    override fun load(): List<RepositoryIntelligenceManifestLifecycleRecord> = records.toList()

    @Synchronized
    override fun appendNext(
        create: (recordId: Long) -> RepositoryIntelligenceManifestLifecycleRecord,
    ): RepositoryIntelligenceManifestLifecycleRecord {
        val record = create(records.size + 1L)
        validateRepositoryIntelligenceLifecycleRecord(record, records)
        records += record
        return record
    }
}

class FileRepositoryIntelligenceLifecycleStore(private val directory: Path) : RepositoryIntelligenceLifecycleStore {
    private val path = directory.resolve("repository-intelligence-lifecycle.jsonl")
    private val lockPath = directory.resolve("repository-intelligence-lifecycle.lock")
    private val json = Json { encodeDefaults = true }
    private var cachedRecords: List<RepositoryIntelligenceManifestLifecycleRecord>? = null

    @Synchronized
    override fun load(): List<RepositoryIntelligenceManifestLifecycleRecord> {
        cachedRecords?.let { return it }
        Files.createDirectories(directory)
        val records = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
        cachedRecords = records
        return records
    }

    private fun loadUnlocked(): List<RepositoryIntelligenceManifestLifecycleRecord> =
        mutableListOf<RepositoryIntelligenceManifestLifecycleRecord>().also { records ->
            loadRecoverableJsonl(path, "repository-intelligence-lifecycle") { line, recordNumber ->
                val envelope = json.decodeFromString<RepositoryIntelligenceLifecycleEnvelope>(line)
                require(envelope.version == FORMAT_VERSION) {
                    "Unsupported repository intelligence lifecycle format ${envelope.version}"
                }
                require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                    "Checksum mismatch in repository intelligence lifecycle $recordNumber"
                }
                validateRepositoryIntelligenceLifecycleRecord(envelope.value, records)
                records += envelope.value
                envelope.value
            }
        }

    @Synchronized
    override fun appendNext(
        create: (recordId: Long) -> RepositoryIntelligenceManifestLifecycleRecord,
    ): RepositoryIntelligenceManifestLifecycleRecord {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val records = loadUnlocked()
                val record = create(records.size + 1L)
                validateRepositoryIntelligenceLifecycleRecord(record, records)
                val payload = json.encodeToString(record)
                val line = json.encodeToString(
                    RepositoryIntelligenceLifecycleEnvelope(value = record, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                cachedRecords = records + record
                record
            }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

interface RepositoryIntelligenceTraceStore {
    fun load(): List<RepositoryIntelligenceTraceSpan>
    fun appendNext(create: (spanId: Long) -> RepositoryIntelligenceTraceSpan): RepositoryIntelligenceTraceSpan
}

class TransientRepositoryIntelligenceTraceStore : RepositoryIntelligenceTraceStore {
    private val spans = mutableListOf<RepositoryIntelligenceTraceSpan>()

    @Synchronized
    override fun load(): List<RepositoryIntelligenceTraceSpan> = spans.toList()

    @Synchronized
    override fun appendNext(create: (spanId: Long) -> RepositoryIntelligenceTraceSpan): RepositoryIntelligenceTraceSpan {
        val span = create(spans.size + 1L)
        validateRepositoryIntelligenceTraceSpan(span, spans)
        spans += span
        return span
    }
}

class FileRepositoryIntelligenceTraceStore(private val directory: Path) : RepositoryIntelligenceTraceStore {
    private val path = directory.resolve("repository-intelligence-trace-spans.jsonl")
    private val lockPath = directory.resolve("repository-intelligence-trace-spans.lock")
    private val json = Json { encodeDefaults = true }
    private var cachedSpans: List<RepositoryIntelligenceTraceSpan>? = null

    @Synchronized
    override fun load(): List<RepositoryIntelligenceTraceSpan> {
        cachedSpans?.let { return it }
        Files.createDirectories(directory)
        val spans = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
        cachedSpans = spans
        return spans
    }

    private fun loadUnlocked(): List<RepositoryIntelligenceTraceSpan> = mutableListOf<RepositoryIntelligenceTraceSpan>().also { spans ->
        loadRecoverableJsonl(path, "repository-intelligence-trace-spans") { line, recordNumber ->
            val envelope = json.decodeFromString<RepositoryIntelligenceTraceEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) {
                "Unsupported repository intelligence trace format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in repository intelligence trace span $recordNumber"
            }
            validateRepositoryIntelligenceTraceSpan(envelope.value, spans)
            spans += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendNext(create: (spanId: Long) -> RepositoryIntelligenceTraceSpan): RepositoryIntelligenceTraceSpan {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val spans = loadUnlocked()
                val span = create(spans.size + 1L)
                validateRepositoryIntelligenceTraceSpan(span, spans)
                val payload = json.encodeToString(span)
                val line = json.encodeToString(
                    RepositoryIntelligenceTraceEnvelope(value = span, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                cachedSpans = spans + span
                span
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
    private val extractorVersion: Int = DEFAULT_EXTRACTOR_VERSION,
    private val policyVersion: Int = DEFAULT_POLICY_VERSION,
    private val lifecycleStore: RepositoryIntelligenceLifecycleStore = TransientRepositoryIntelligenceLifecycleStore(),
    private val traceStore: RepositoryIntelligenceTraceStore = TransientRepositoryIntelligenceTraceStore(),
) {
    private val json = Json { encodeDefaults = true }

    init {
        store.load()
        lifecycleStore.load()
        traceStore.load()
    }

    fun lifecycle(projectId: Int, repositoryRevision: String? = null, limit: Int = 16): List<RepositoryIntelligenceManifestLifecycleProjection> {
        require(projectId > 0) { "Project ID must be positive" }
        require(limit > 0) { "Lifecycle projection limit must be positive" }
        val latestByManifest = linkedMapOf<RepositoryIntelligenceManifestKey, RepositoryIntelligenceManifestLifecycleRecord>()
        lifecycleStore.load().asReversed().forEach { record ->
            if (record.projectId != projectId) return@forEach
            if (repositoryRevision != null && record.repositoryRevision != repositoryRevision) return@forEach
            latestByManifest.putIfAbsent(record.manifestKey, record)
        }
        return latestByManifest.values.take(limit).map { record ->
            RepositoryIntelligenceManifestLifecycleProjection(
                manifestKey = record.manifestKey,
                latestState = record.state,
                projectId = record.projectId,
                repositoryRevision = record.repositoryRevision,
                traceId = record.traceId,
                recordedAt = record.recordedAt,
                latestDisposition = record.disposition,
                readyGraphId = record.graphId,
                diagnosticCode = record.diagnosticCode,
                diagnostic = record.diagnostic,
            )
        }
    }

    fun traces(projectId: Int, repositoryRevision: String? = null, runId: Long? = null, limit: Int = 64): List<RepositoryIntelligenceTraceSpan> {
        require(projectId > 0) { "Project ID must be positive" }
        require(limit > 0) { "Trace projection limit must be positive" }
        return traceStore.load().asReversed().asSequence()
            .filter { it.projectId == projectId }
            .filter { repositoryRevision == null || it.repositoryRevision == repositoryRevision }
            .filter { runId == null || it.runId == runId }
            .take(limit)
            .toList()
    }

    fun recordEnsureServiceFailure(
        runId: Long,
        projectId: Int = 0,
        repositoryRevision: String? = null,
        diagnosticCode: String,
        diagnostic: String,
    ): String {
        val traceId = newRepositoryIntelligenceTraceId(
            operation = "manifest-ensure",
            projectId = projectId,
            repositoryRevision = repositoryRevision.orEmpty(),
            runId = runId,
        )
        emitTrace(
            traceId = traceId,
            kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE,
            status = diagnosticCode,
            projectId = projectId,
            repositoryRevision = repositoryRevision.orEmpty(),
            runId = runId,
            elapsedMillis = 0,
            diagnosticCode = diagnosticCode,
            diagnostic = diagnostic,
        )
        return traceId
    }

    fun latest(projectId: Int, repositoryRevision: String? = null): RepositoryIntelligenceGraph? = store.load().lastOrNull {
        it.projectId == projectId && (repositoryRevision == null || it.repositoryRevision == repositoryRevision)
    }

    fun requiredManifestKey(projectId: Int, repositoryRevision: String): RepositoryIntelligenceManifestKey =
        RepositoryIntelligenceManifestKey(projectId, repositoryRevision, extractorVersion, policyVersion)

    fun compatible(projectId: Int, repositoryRevision: String): RepositoryIntelligenceGraph? {
        val projection = authorityProjection(projectId)
        val authorityHash = stagedPlanHash(json.encodeToString(projection))
        val genesisRevision = projection.genesis?.revision?.revision ?: 0
        val manifestKey = requiredManifestKey(projectId, repositoryRevision)
        return store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
                it.genesisRevision == genesisRevision &&
                it.orchardAuthorityHash == authorityHash &&
                it.effectiveManifestKey() == manifestKey
        }
    }

    fun current(projectId: Int): RepositoryIntelligenceGraph? {
        val repository = workspace.snapshot(0).repositories[projectId]?.takeIf { it.available } ?: return null
        val root = Path.of(repository.path).toRealPath()
        val revision = gitText(root, listOf("rev-parse", "--verify", "HEAD")).trim()
        return import(projectId, repository.path, revision)
    }

    @Synchronized
    fun import(projectId: Int, repositoryPath: String, repositoryRevision: String): RepositoryIntelligenceGraph =
        ensure(projectId, repositoryPath, repositoryRevision).graph

    @Synchronized
    fun ensure(
        projectId: Int,
        repositoryPath: String,
        repositoryRevision: String,
        runId: Long? = null,
        traceId: String = newRepositoryIntelligenceTraceId("manifest-ensure", projectId, repositoryRevision, runId),
    ): RepositoryIntelligenceEnsureResult {
        val ensureStartedAt = System.nanoTime()
        compatible(projectId, repositoryRevision)?.let {
            emitLifecycle(
                manifestKey = it.effectiveManifestKey(),
                state = RepositoryIntelligenceManifestState.READY,
                traceId = traceId,
                disposition = RepositoryIntelligenceEnsureDisposition.REUSED,
                graphId = it.graphId,
            )
            emitTrace(
                traceId = traceId,
                kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE,
                status = RepositoryIntelligenceEnsureDisposition.REUSED.name,
                projectId = projectId,
                repositoryRevision = repositoryRevision,
                runId = runId,
                manifestKey = it.effectiveManifestKey(),
                graphId = it.graphId,
                elapsedMillis = elapsedMillisSince(ensureStartedAt),
            )
            return RepositoryIntelligenceEnsureResult(RepositoryIntelligenceEnsureDisposition.REUSED, it)
        }
        val projection = authorityProjection(projectId)
        val authorityHash = stagedPlanHash(json.encodeToString(projection))
        val manifestKey = requiredManifestKey(projectId, repositoryRevision)
        val priorGraph = store.load().lastOrNull { it.projectId == projectId }
        emitLifecycle(
            manifestKey = manifestKey,
            state = if (priorGraph == null) RepositoryIntelligenceManifestState.ABSENT else RepositoryIntelligenceManifestState.STALE,
            traceId = traceId,
            diagnosticCode = if (priorGraph == null) null else "INCOMPATIBLE_MANIFEST",
            diagnostic = if (priorGraph == null) null else "A repository intelligence manifest exists, but it is not compatible with the required manifest key.",
        )
        emitLifecycle(
            manifestKey = manifestKey,
            state = RepositoryIntelligenceManifestState.BUILDING,
            traceId = traceId,
        )
        val extractStartedAt = System.nanoTime()
        return runCatching {
            val root = Path.of(repositoryPath).toRealPath()
            val trackedEntries = gitTrackedEntries(root, repositoryRevision)
            val importedFiles = trackedEntries.map { entry -> importFile(root, repositoryRevision, entry) }
            val graph = store.appendNext { graphId ->
                buildGraph(graphId, manifestKey, authorityHash, projection, importedFiles)
            }
            emitTrace(
                traceId = traceId,
                kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_EXTRACT,
                status = RepositoryIntelligenceEnsureDisposition.BUILT.name,
                projectId = projectId,
                repositoryRevision = repositoryRevision,
                runId = runId,
                manifestKey = manifestKey,
                graphId = graph.graphId,
                elapsedMillis = elapsedMillisSince(extractStartedAt),
            )
            emitLifecycle(
                manifestKey = manifestKey,
                state = RepositoryIntelligenceManifestState.READY,
                traceId = traceId,
                disposition = RepositoryIntelligenceEnsureDisposition.BUILT,
                graphId = graph.graphId,
            )
            emitTrace(
                traceId = traceId,
                kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE,
                status = RepositoryIntelligenceEnsureDisposition.BUILT.name,
                projectId = projectId,
                repositoryRevision = repositoryRevision,
                runId = runId,
                manifestKey = manifestKey,
                graphId = graph.graphId,
                elapsedMillis = elapsedMillisSince(ensureStartedAt),
            )
            RepositoryIntelligenceEnsureResult(RepositoryIntelligenceEnsureDisposition.BUILT, graph)
        }.getOrElse { failure ->
            emitLifecycle(
                manifestKey = manifestKey,
                state = RepositoryIntelligenceManifestState.FAILED,
                traceId = traceId,
                diagnosticCode = "ENSURE_FAILED",
                diagnostic = failure.message.orEmpty().ifBlank { failure::class.simpleName.orEmpty() },
            )
            emitTrace(
                traceId = traceId,
                kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_EXTRACT,
                status = "FAILED",
                projectId = projectId,
                repositoryRevision = repositoryRevision,
                runId = runId,
                manifestKey = manifestKey,
                elapsedMillis = elapsedMillisSince(extractStartedAt),
                diagnosticCode = "ENSURE_FAILED",
                diagnostic = failure.message.orEmpty().ifBlank { failure::class.simpleName.orEmpty() },
            )
            emitTrace(
                traceId = traceId,
                kind = RepositoryIntelligenceTraceSpanKind.MANIFEST_ENSURE,
                status = "FAILED",
                projectId = projectId,
                repositoryRevision = repositoryRevision,
                runId = runId,
                manifestKey = manifestKey,
                elapsedMillis = elapsedMillisSince(ensureStartedAt),
                diagnosticCode = "ENSURE_FAILED",
                diagnostic = failure.message.orEmpty().ifBlank { failure::class.simpleName.orEmpty() },
            )
            throw failure
        }
    }

    private fun emitLifecycle(
        manifestKey: RepositoryIntelligenceManifestKey,
        state: RepositoryIntelligenceManifestState,
        traceId: String,
        disposition: RepositoryIntelligenceEnsureDisposition? = null,
        graphId: Long? = null,
        diagnosticCode: String? = null,
        diagnostic: String? = null,
    ): RepositoryIntelligenceManifestLifecycleRecord = lifecycleStore.appendNext { recordId ->
        RepositoryIntelligenceManifestLifecycleRecord(
            recordId = recordId,
            traceId = traceId,
            projectId = manifestKey.repositoryId,
            repositoryRevision = manifestKey.commitHash,
            manifestKey = manifestKey,
            state = state,
            disposition = disposition,
            graphId = graphId,
            diagnosticCode = diagnosticCode,
            diagnostic = diagnostic,
        )
    }

    fun emitTrace(
        traceId: String,
        kind: RepositoryIntelligenceTraceSpanKind,
        status: String,
        projectId: Int,
        repositoryRevision: String,
        elapsedMillis: Long,
        runId: Long? = null,
        manifestKey: RepositoryIntelligenceManifestKey? = null,
        graphId: Long? = null,
        graphQuery: String? = null,
        acceptedScopeCount: Int? = null,
        anchorPathCount: Int? = null,
        selectedNodeCount: Int? = null,
        selectedPathCount: Int? = null,
        omittedPathCount: Int? = null,
        unresolvedBoundaryCount: Int? = null,
        contextFileCount: Int? = null,
        contextOmittedFileCount: Int? = null,
        contextBytes: Long? = null,
        diagnosticCode: String? = null,
        diagnostic: String? = null,
    ): RepositoryIntelligenceTraceSpan = traceStore.appendNext { spanId ->
        RepositoryIntelligenceTraceSpan(
            spanId = spanId,
            traceId = traceId,
            kind = kind,
            status = status,
            projectId = projectId,
            repositoryRevision = repositoryRevision,
            elapsedMillis = elapsedMillis,
            runId = runId,
            manifestKey = manifestKey,
            graphId = graphId,
            graphQuery = graphQuery,
            acceptedScopeCount = acceptedScopeCount,
            anchorPathCount = anchorPathCount,
            selectedNodeCount = selectedNodeCount,
            selectedPathCount = selectedPathCount,
            omittedPathCount = omittedPathCount,
            unresolvedBoundaryCount = unresolvedBoundaryCount,
            contextFileCount = contextFileCount,
            contextOmittedFileCount = contextOmittedFileCount,
            contextBytes = contextBytes,
            diagnosticCode = diagnosticCode,
            diagnostic = diagnostic,
        )
    }

    private fun buildGraph(
        graphId: Long,
        manifestKey: RepositoryIntelligenceManifestKey,
        authorityHash: String,
        authority: OrchardAuthorityProjection,
        files: List<ImportedFile>,
    ): RepositoryIntelligenceGraph {
        val nodes = linkedMapOf<String, RepositoryIntelligenceNode>()
        val edges = linkedMapOf<String, RepositoryIntelligenceEdge>()
        val unresolvedBoundaries = linkedMapOf<String, RepositoryIntelligenceUnresolvedBoundary>()
        fun provenance(ruleId: String, evidencePaths: List<String> = emptyList(), limitation: String? = null) =
            RepositoryIntelligenceProvenance(ruleId, evidencePaths.distinct(), extractorVersion, policyVersion, limitation)
        fun authoritativeRoles(vararg roles: String): List<String> = roles.toList().ifEmpty { listOf(INTELLIGENCE_ROLE_UNCLASSIFIED) }
        fun addNode(node: RepositoryIntelligenceNode) {
            require(nodes.putIfAbsent(node.nodeId, node) == null) { "Duplicate repository intelligence node ${node.nodeId}" }
        }
        fun addEdge(
            kind: String,
            from: String,
            to: String,
            evidencePath: String?,
            rationale: String,
            provenance: List<RepositoryIntelligenceProvenance> = emptyList(),
            unresolvedBoundaryIds: List<String> = emptyList(),
        ) {
            val edgeId = stableId("edge", "$kind\n$from\n$to\n${evidencePath.orEmpty()}")
            edges.putIfAbsent(edgeId, RepositoryIntelligenceEdge(edgeId, kind, from, to, evidencePath, rationale, provenance, unresolvedBoundaryIds))
        }
        fun addBoundary(boundary: RepositoryIntelligenceUnresolvedBoundary): String {
            unresolvedBoundaries.putIfAbsent(boundary.boundaryId, boundary)
            return boundary.boundaryId
        }

        val projectId = manifestKey.repositoryId
        val repositoryRevision = manifestKey.commitHash
        val repositoryNodeId = "repository:$projectId"
        addNode(RepositoryIntelligenceNode(
            repositoryNodeId,
            INTELLIGENCE_NODE_REPOSITORY,
            "Repository at $repositoryRevision",
            attributes = mapOf("revision" to repositoryRevision),
            roles = authoritativeRoles(),
            provenance = listOf(provenance("repository-root")),
        ))
        val projectNodeId = "orchard-project:$projectId"
        val project = authority.entities.single { it.id == projectId }
        addNode(RepositoryIntelligenceNode(
            projectNodeId,
            INTELLIGENCE_NODE_PROJECT,
            project.title,
            attributes = mapOf("entityId" to project.id.toString(), "status" to project.status.toString()),
            roles = authoritativeRoles(),
            provenance = listOf(provenance("orchard-project-binding")),
        ))
        addEdge(
            INTELLIGENCE_EDGE_BINDS,
            projectNodeId,
            repositoryNodeId,
            null,
            "The Orchard project is bound to this repository revision.",
            provenance = listOf(provenance("orchard-project-binding")),
        )

        val moduleRoots = moduleRoots(files)
        val symbols = files.flatMap(::extractSymbols)
        val symbolsByQualifiedName = symbols.groupBy { it.qualifiedName }
        val symbolsBySimpleName = symbols.groupBy { it.name }
        val fileIntelligence = files.associate { file ->
            file.path to classifyFileIntelligence(file, symbolsByQualifiedName, symbolsBySimpleName, ::provenance, ::addBoundary)
        }
        moduleRoots.forEach { root ->
            val moduleId = moduleNodeId(root)
            addNode(RepositoryIntelligenceNode(
                moduleId,
                INTELLIGENCE_NODE_MODULE,
                root.ifEmpty { "root" },
                path = root.ifEmpty { "." },
                roles = authoritativeRoles(INTELLIGENCE_ROLE_TOOLCHAIN),
                provenance = listOf(provenance("gradle-module-root", listOfNotNull(root.takeIf(String::isNotBlank)))),
            ))
            addEdge(
                INTELLIGENCE_EDGE_CONTAINS,
                repositoryNodeId,
                moduleId,
                null,
                "The repository contains this build module.",
                provenance = listOf(provenance("gradle-module-root")),
            )
        }

        files.forEach { file ->
            val intelligence = requireNotNull(fileIntelligence[file.path])
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
                roles = intelligence.roles,
                provenance = intelligence.provenance,
                unresolvedBoundaryIds = intelligence.unresolvedBoundaryIds,
            ))
            val moduleRoot = moduleRoots.filter { it.isEmpty() || file.path == it || file.path.startsWith("$it/") }
                .maxByOrNull { it.length }
            addEdge(
                INTELLIGENCE_EDGE_CONTAINS,
                moduleRoot?.let(::moduleNodeId) ?: repositoryNodeId,
                fileNodeId(file.path),
                file.path,
                "The tracked file belongs to this repository scope.",
                provenance = listOf(provenance("tracked-file-membership", listOf(file.path))),
            )
        }

        symbols.forEach { symbol ->
            val symbolIntelligence = classifySymbolIntelligence(symbol, requireNotNull(fileIntelligence[symbol.path]), ::provenance)
            addNode(RepositoryIntelligenceNode(
                symbol.nodeId,
                INTELLIGENCE_NODE_SYMBOL,
                symbol.name,
                path = symbol.path,
                contentHash = files.single { it.path == symbol.path }.contentHash,
                attributes = mapOf("qualifiedName" to symbol.qualifiedName, "declarationKind" to symbol.kind),
                roles = symbolIntelligence.roles,
                provenance = symbolIntelligence.provenance,
            ))
            addEdge(
                INTELLIGENCE_EDGE_DECLARES,
                fileNodeId(symbol.path),
                symbol.nodeId,
                symbol.path,
                "The source file declares this symbol.",
                provenance = listOf(provenance("symbol-declaration", listOf(symbol.path))),
            )
        }
        files.forEach { file ->
            requireNotNull(fileIntelligence[file.path]).importReferences.forEach { importedReference ->
                val importedName = importedReference.name
                val targets = symbolsByQualifiedName[importedName]
                    ?: symbolsBySimpleName[importedName.substringAfterLast('.')].orEmpty()
                targets.forEach { target ->
                    addEdge(
                        INTELLIGENCE_EDGE_IMPORTS,
                        fileNodeId(file.path),
                        target.nodeId,
                        file.path,
                        "The source imports the declared symbol ${target.qualifiedName}.",
                        provenance = listOf(
                            provenance(
                                if (importedReference.wildcard) "kotlin-import-wildcard-resolution" else "kotlin-import-resolution",
                                listOf(file.path),
                                limitation = importedReference.takeIf { it.wildcard }?.let {
                                    "Wildcard imports remain unresolved until the referenced symbol is admitted into graph-local context."
                                },
                            )
                        ),
                    )
                }
            }
        }

        files.filter { it.kind == INTELLIGENCE_NODE_SOURCE }.forEach { file ->
            val sourceNodeId = fileNodeId(file.path)
            val sourceRoles = requireNotNull(fileIntelligence[file.path]).roles.toSet()
            val content = file.content.orEmpty()
            val importedTargets = requireNotNull(fileIntelligence[file.path]).importReferences
                .flatMap { importedReference ->
                    symbolsByQualifiedName[importedReference.name]
                        ?: symbolsBySimpleName[importedReference.name.substringAfterLast('.')].orEmpty()
                }
                .map { it.path }
                .distinct()
            importedTargets.forEach { targetPath ->
                val targetRoles = fileIntelligence[targetPath]?.roles.orEmpty().toSet()
                val targetNodeId = fileNodeId(targetPath)
                when {
                    INTELLIGENCE_ROLE_TRANSPORT_SERVER in sourceRoles && INTELLIGENCE_ROLE_AUTHORITY in targetRoles ->
                        addEdge(
                            INTELLIGENCE_EDGE_ADMITS_COMMAND,
                            sourceNodeId,
                            targetNodeId,
                            file.path,
                            "The transport boundary imports an authority-bearing source.",
                            listOf(provenance("transport-authority-import", listOf(file.path))),
                        )
                    INTELLIGENCE_ROLE_AUTHORITY in sourceRoles && INTELLIGENCE_ROLE_PERSISTENCE in targetRoles &&
                        PERSISTENCE_OPERATION.containsMatchIn(content) ->
                        addEdge(
                            INTELLIGENCE_EDGE_APPENDS_RECORD,
                            sourceNodeId,
                            targetNodeId,
                            file.path,
                            "The authority source invokes a recognized persistence operation on an imported persistence source.",
                            listOf(provenance("authority-persistence-operation", listOf(file.path))),
                        )
                    INTELLIGENCE_ROLE_AUTHORITY in sourceRoles && INTELLIGENCE_ROLE_PROJECTION in targetRoles ->
                        addEdge(
                            INTELLIGENCE_EDGE_DERIVES_PROJECTION,
                            sourceNodeId,
                            targetNodeId,
                            file.path,
                            "The authority source imports a projection-bearing source.",
                            listOf(provenance("authority-projection-import", listOf(file.path))),
                        )
                }
            }
            if (EMIT_OPERATION.containsMatchIn(content)) {
                addEdge(
                    INTELLIGENCE_EDGE_EMITS_EVENT,
                    sourceNodeId,
                    sourceNodeId,
                    file.path,
                    "The source contains a recognized event emission operation.",
                    listOf(provenance("event-emission-operation", listOf(file.path))),
                )
            }
            if (RECEIVE_OPERATION.containsMatchIn(content)) {
                addEdge(
                    INTELLIGENCE_EDGE_RECEIVES_EVENT,
                    sourceNodeId,
                    sourceNodeId,
                    file.path,
                    "The source contains a recognized event observation operation.",
                    listOf(provenance("event-observation-operation", listOf(file.path))),
                )
            }
        }

        correlateModules(files, moduleRoots).forEach { (from, to, path) ->
            addEdge(
                INTELLIGENCE_EDGE_DEPENDS_ON,
                moduleNodeId(from),
                moduleNodeId(to),
                path,
                "The build declares a module dependency.",
                provenance = listOf(provenance("gradle-project-dependency", listOf(path))),
            )
        }
        correlateTests(files).forEach { (test, source) ->
            addEdge(
                INTELLIGENCE_EDGE_TESTS,
                fileNodeId(test.path),
                fileNodeId(source.path),
                test.path,
                "The test name correlates to the source artifact.",
                provenance = listOf(provenance("test-name-correlation", listOf(test.path))),
            )
        }
        correlateDocuments(files).forEach { (document, target) ->
            addEdge(
                INTELLIGENCE_EDGE_DOCUMENTS,
                fileNodeId(document.path),
                fileNodeId(target.path),
                document.path,
                "The document explicitly references the tracked repository path.",
                provenance = listOf(provenance("document-path-reference", listOf(document.path))),
            )
        }

        addOrchardAuthority(authority, files, repositoryNodeId, projectNodeId, ::addNode, ::addEdge, ::provenance)

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
            manifestKey = manifestKey,
            unresolvedBoundaries = unresolvedBoundaries.values.sortedBy { it.boundaryId },
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
        addEdge: (String, String, String, String?, String, List<RepositoryIntelligenceProvenance>, List<String>) -> Unit,
        provenance: (String, List<String>, String?) -> RepositoryIntelligenceProvenance,
    ) {
        authority.entities.filter { it.id != authority.projectId }.forEach { entity ->
            val nodeId = "orchard-work-item:${entity.id}"
            addNode(RepositoryIntelligenceNode(
                nodeId,
                INTELLIGENCE_NODE_WORK_ITEM,
                entity.title,
                attributes = mapOf("entityId" to entity.id.toString(), "entityType" to entity.type.toString()),
                roles = listOf(INTELLIGENCE_ROLE_UNCLASSIFIED),
                provenance = listOf(provenance("orchard-authority-entity", emptyList(), null)),
            ))
            val parentId = if (entity.parentId == authority.projectId) projectNodeId else "orchard-work-item:${entity.parentId}"
            addEdge(
                INTELLIGENCE_EDGE_CONTAINS,
                parentId,
                nodeId,
                null,
                "Orchard authority contains this work item.",
                listOf(provenance("orchard-authority-entity", emptyList(), null)),
                emptyList(),
            )
        }
        authority.genesis?.revision?.let { genesis ->
            genesis.components.forEach { component ->
                val nodeId = "orchard-component:${authority.projectId}:${component.componentId}"
                addNode(RepositoryIntelligenceNode(
                    nodeId,
                    INTELLIGENCE_NODE_COMPONENT,
                    component.name,
                    attributes = mapOf("componentId" to component.componentId, "responsibility" to component.responsibility),
                    roles = listOf(INTELLIGENCE_ROLE_UNCLASSIFIED),
                    provenance = listOf(provenance("orchard-genesis-component", emptyList(), null)),
                ))
                addEdge(
                    INTELLIGENCE_EDGE_CONTAINS,
                    projectNodeId,
                    nodeId,
                    null,
                    "Genesis defines this architecture component.",
                    listOf(provenance("orchard-genesis-component", emptyList(), null)),
                    emptyList(),
                )
                component.repositoryPaths.forEach { path ->
                    files.filter { it.path == path || it.path.startsWith(path.trimEnd('/') + "/") }.forEach { file ->
                        addEdge(
                            INTELLIGENCE_EDGE_MAPS_TO,
                            nodeId,
                            fileNodeId(file.path),
                            file.path,
                            "The admitted component maps to this repository path.",
                            listOf(provenance("orchard-genesis-component-path", listOf(file.path), null)),
                            emptyList(),
                        )
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
                        listOf(provenance("orchard-genesis-component-dependency", emptyList(), null)),
                        emptyList(),
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
                    roles = listOf(INTELLIGENCE_ROLE_UNCLASSIFIED),
                    provenance = listOf(provenance("orchard-genesis-decision", emptyList(), null)),
                ))
                addEdge(
                    INTELLIGENCE_EDGE_CONTAINS,
                    projectNodeId,
                    nodeId,
                    null,
                    "Genesis records this architecture decision.",
                    listOf(provenance("orchard-genesis-decision", emptyList(), null)),
                    emptyList(),
                )
                decision.componentIds.forEach { componentId ->
                    addEdge(
                        INTELLIGENCE_EDGE_AFFECTS,
                        nodeId,
                        "orchard-component:${authority.projectId}:$componentId",
                        null,
                        "The decision identifies the affected component.",
                        listOf(provenance("orchard-genesis-decision-affects", emptyList(), null)),
                        emptyList(),
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
                roles = listOf(INTELLIGENCE_ROLE_UNCLASSIFIED),
                provenance = listOf(provenance("orchard-workflow-run", emptyList(), null)),
            ))
            addEdge(
                INTELLIGENCE_EDGE_EXECUTES,
                runNodeId,
                "orchard-work-item:${run.context.workItemId}",
                null,
                "The workflow run executes this work item.",
                listOf(provenance("orchard-workflow-run", emptyList(), null)),
                emptyList(),
            )
            addEdge(
                INTELLIGENCE_EDGE_MAPS_TO,
                runNodeId,
                repositoryNodeId,
                null,
                "The workflow run is pinned to this repository.",
                listOf(provenance("orchard-workflow-run", emptyList(), null)),
                emptyList(),
            )
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
                    roles = listOf(INTELLIGENCE_ROLE_UNCLASSIFIED),
                    provenance = listOf(provenance("orchard-workflow-evidence", emptyList(), null)),
                ))
                addEdge(
                    INTELLIGENCE_EDGE_PRODUCES,
                    runNodeId,
                    evidenceNodeId,
                    null,
                    "The workflow run produced this evidence.",
                    listOf(provenance("orchard-workflow-evidence", emptyList(), null)),
                    emptyList(),
                )
                addEdge(
                    INTELLIGENCE_EDGE_PROVES,
                    evidenceNodeId,
                    repositoryNodeId,
                    null,
                    "The evidence is revision-bound repository proof.",
                    listOf(provenance("orchard-workflow-evidence", emptyList(), null)),
                    emptyList(),
                )
            }
        }
    }

    private fun classifyFileIntelligence(
        file: ImportedFile,
        symbolsByQualifiedName: Map<String, List<ImportedSymbol>>,
        symbolsBySimpleName: Map<String, List<ImportedSymbol>>,
        provenance: (String, List<String>, String?) -> RepositoryIntelligenceProvenance,
        addBoundary: (RepositoryIntelligenceUnresolvedBoundary) -> String,
    ): FileIntelligence {
        val roles = linkedSetOf<String>()
        val nodeId = fileNodeId(file.path)
        val provenances = mutableListOf<RepositoryIntelligenceProvenance>()
        fun assign(role: String, ruleId: String) {
            if (roles.add(role)) provenances += provenance(ruleId, listOf(file.path), null)
        }
        when (file.kind) {
            INTELLIGENCE_NODE_TEST -> assign(INTELLIGENCE_ROLE_TEST, "file-kind-test")
            INTELLIGENCE_NODE_BUILD, INTELLIGENCE_NODE_WORKFLOW, INTELLIGENCE_NODE_SCRIPT, INTELLIGENCE_NODE_CONFIG ->
                assign(INTELLIGENCE_ROLE_TOOLCHAIN, "file-kind-toolchain")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.path.startsWith("frontend/")) {
            assign(INTELLIGENCE_ROLE_UI, "frontend-source-root")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.path.substringAfterLast('/').substringBeforeLast('.').endsWith("Service")) {
            assign(INTELLIGENCE_ROLE_SERVICE, "service-file-name")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.path.substringAfterLast('/').substringBeforeLast('.').endsWith("Authority")) {
            assign(INTELLIGENCE_ROLE_AUTHORITY, "authority-file-name")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.path.substringAfterLast('/').substringBeforeLast('.').matches(Regex(".*(Repository|Store|Dao)$"))) {
            assign(INTELLIGENCE_ROLE_PERSISTENCE, "persistence-file-name")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.path.substringAfterLast('/').substringBeforeLast('.').matches(Regex(".*(Projection|ViewModel|Presenter)$"))) {
            assign(INTELLIGENCE_ROLE_PROJECTION, "projection-file-name")
        }
        if (file.kind == INTELLIGENCE_NODE_SOURCE && file.content.orEmpty().contains("io.ktor.server")) {
            assign(INTELLIGENCE_ROLE_TRANSPORT_SERVER, "ktor-server-import")
        }
        if (
            file.kind == INTELLIGENCE_NODE_SOURCE &&
            (file.content.orEmpty().contains("@Serializable") || file.content.orEmpty().contains("kotlinx.serialization.Serializable"))
        ) {
            assign(INTELLIGENCE_ROLE_MODEL, "serializable-annotation")
        }
        val boundaries = mutableListOf<String>()
        val importReferences = extractImports(file)
        importReferences.filter { it.wildcard }.forEach { importedReference ->
            boundaries += addBoundary(
                unresolvedBoundary(
                    kind = "WILDCARD_IMPORT",
                    path = file.path,
                    detail = importedReference.name,
                    handlingPolicy = "REQUIRE_EXPLICIT_CONTEXT",
                    nodeId = nodeId,
                )
            )
        }
        importReferences.filterNot { it.wildcard }.forEach { importedReference ->
            val targets = symbolsByQualifiedName[importedReference.name]
                ?: symbolsBySimpleName[importedReference.name.substringAfterLast('.')].orEmpty()
            if (targets.isEmpty()) {
                boundaries += addBoundary(
                    unresolvedBoundary(
                        kind = "UNRESOLVED_IMPORT",
                        path = file.path,
                        detail = importedReference.name,
                        handlingPolicy = "REQUIRE_EXPLICIT_CONTEXT",
                        nodeId = nodeId,
                    )
                )
            }
        }
        if (file.content.orEmpty().contains("Class.forName(")) {
            boundaries += addBoundary(
                unresolvedBoundary(
                    kind = "REFLECTION",
                    path = file.path,
                    detail = "Class.forName",
                    handlingPolicy = "REQUIRE_EXPLICIT_CONTEXT",
                    nodeId = nodeId,
                )
            )
        }
        if (file.content.orEmpty().contains("ServiceLoader.load(")) {
            boundaries += addBoundary(
                unresolvedBoundary(
                    kind = "CONFIGURATION_SELECTED_IMPLEMENTATION",
                    path = file.path,
                    detail = "ServiceLoader.load",
                    handlingPolicy = "REQUIRE_EXPLICIT_CONTEXT",
                    nodeId = nodeId,
                )
            )
        }
        return FileIntelligence(
            roles = roles.toList().ifEmpty { listOf(INTELLIGENCE_ROLE_UNCLASSIFIED) },
            provenance = provenances.ifEmpty { listOf(provenance("tracked-file", listOf(file.path), null)) },
            unresolvedBoundaryIds = boundaries.distinct(),
            importReferences = importReferences,
        )
    }

    private fun classifySymbolIntelligence(
        symbol: ImportedSymbol,
        fileIntelligence: FileIntelligence,
        provenance: (String, List<String>, String?) -> RepositoryIntelligenceProvenance,
    ): SymbolIntelligence {
        val roles = linkedSetOf<String>()
        val provenances = mutableListOf<RepositoryIntelligenceProvenance>()
        fun assign(role: String, ruleId: String) {
            if (roles.add(role)) provenances += provenance(ruleId, listOf(symbol.path), null)
        }
        fileIntelligence.roles.filter { it != INTELLIGENCE_ROLE_TOOLCHAIN && it != INTELLIGENCE_ROLE_UNCLASSIFIED }.forEach { role ->
            assign(role, "symbol-inherits-file-role")
        }
        if (symbol.name.endsWith("Service")) assign(INTELLIGENCE_ROLE_SERVICE, "service-symbol-name")
        if (symbol.name.endsWith("Test")) assign(INTELLIGENCE_ROLE_TEST, "test-symbol-name")
        return SymbolIntelligence(
            roles = roles.toList().ifEmpty { listOf(INTELLIGENCE_ROLE_UNCLASSIFIED) },
            provenance = provenances.ifEmpty { listOf(provenance("symbol-declaration", listOf(symbol.path), null)) },
        )
    }

    private fun unresolvedBoundary(
        kind: String,
        path: String,
        detail: String,
        handlingPolicy: String,
        nodeId: String,
    ): RepositoryIntelligenceUnresolvedBoundary {
        val identity = listOf(kind, path, detail, handlingPolicy, nodeId).joinToString("\n")
        return RepositoryIntelligenceUnresolvedBoundary(
            boundaryId = stableId("boundary", identity),
            kind = kind,
            path = path,
            detail = detail,
            handlingPolicy = handlingPolicy,
            nodeId = nodeId,
        )
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

    private fun extractImports(file: ImportedFile): Set<ImportedReference> = file.content?.let { content ->
        IMPORT_DECLARATION.findAll(content).map { match ->
            val imported = match.groupValues[1]
            ImportedReference(imported.removeSuffix(".*"), imported.endsWith(".*"))
        }.toSet()
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

    private data class ImportedReference(
        val name: String,
        val wildcard: Boolean,
    )

    private data class FileIntelligence(
        val roles: List<String>,
        val provenance: List<RepositoryIntelligenceProvenance>,
        val unresolvedBoundaryIds: List<String>,
        val importReferences: Set<ImportedReference>,
    )

    private data class SymbolIntelligence(
        val roles: List<String>,
        val provenance: List<RepositoryIntelligenceProvenance>,
    )

    private companion object {
        const val DEFAULT_EXTRACTOR_VERSION = 1
        const val DEFAULT_POLICY_VERSION = 1
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
        val PERSISTENCE_OPERATION = Regex("""\.(append|save|insert|update|delete)\s*\(""")
        val EMIT_OPERATION = Regex("""\.(emit|send|tryEmit)\s*\(""")
        val RECEIVE_OPERATION = Regex("""\.(collect|receive|consume)\s*\{""")
        val SYMBOL_DECLARATION = Regex(
            """(?m)^\s*(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|final\s+|static\s+|suspend\s+|inline\s+|data\s+|value\s+)*(class|interface|object|enum\s+class|fun|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        val DOCUMENT_PATH = Regex("""(?:`|\()([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+)(?:`|\))""")
    }
}

fun repositoryIntelligenceGraphHash(graph: RepositoryIntelligenceGraph): String = stagedPlanHash(
    intelligenceGraphJson.encodeToString(graph.copy(graphId = 0, importedAt = "", hash = ""))
)

fun RepositoryIntelligenceGraph.effectiveManifestKey(): RepositoryIntelligenceManifestKey = manifestKey
    ?: RepositoryIntelligenceManifestKey(
        repositoryId = projectId,
        commitHash = repositoryRevision,
        extractorVersion = 1,
        policyVersion = 1,
    )

private fun validateRepositoryIntelligenceGraph(
    graph: RepositoryIntelligenceGraph,
    previous: List<RepositoryIntelligenceGraph>,
) {
    require(graph.graphId == previous.size + 1L) { "Repository intelligence graph ID is not monotonic" }
    require(graph.projectId > 0 && graph.genesisRevision >= 0) { "Repository intelligence graph identity is invalid" }
    require(graph.repositoryRevision.matches(GIT_REVISION)) { "Repository intelligence graph revision is invalid" }
    require(graph.orchardAuthorityHash.matches(SHA256)) { "Repository intelligence authority hash is invalid" }
    require(graph.effectiveManifestKey().repositoryId == graph.projectId) { "Repository intelligence manifest project is invalid" }
    require(graph.effectiveManifestKey().commitHash == graph.repositoryRevision) { "Repository intelligence manifest revision is invalid" }
    require(graph.effectiveManifestKey().extractorVersion > 0 && graph.effectiveManifestKey().policyVersion > 0) {
        "Repository intelligence manifest versions are invalid"
    }
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
    require(graph.nodes.all { node ->
        node.roles.distinct().size == node.roles.size && node.roles.all { it in VALID_ROLES } &&
            node.provenance.all { it.ruleId.isNotBlank() && it.extractorVersion > 0 && it.policyVersion > 0 } &&
            node.unresolvedBoundaryIds.distinct().size == node.unresolvedBoundaryIds.size
    }) { "Repository intelligence node role or provenance is invalid" }
    require(graph.edges.all { edge ->
        edge.provenance.all { it.ruleId.isNotBlank() && it.extractorVersion > 0 && it.policyVersion > 0 } &&
            edge.unresolvedBoundaryIds.distinct().size == edge.unresolvedBoundaryIds.size
    }) { "Repository intelligence edge provenance is invalid" }
    require(graph.unresolvedBoundaries.map { it.boundaryId }.distinct().size == graph.unresolvedBoundaries.size) {
        "Repository intelligence unresolved boundaries are duplicated"
    }
    require(graph.unresolvedBoundaries.all { boundary ->
        boundary.kind.isNotBlank() && validRepositoryIntelligencePath(boundary.path) && boundary.detail.isNotBlank() &&
            boundary.handlingPolicy.isNotBlank() && boundary.nodeId in nodeIds
    }) { "Repository intelligence unresolved boundary is invalid" }
    val boundaryIds = graph.unresolvedBoundaries.mapTo(mutableSetOf()) { it.boundaryId }
    require(graph.nodes.all { it.unresolvedBoundaryIds.all(boundaryIds::contains) } && graph.edges.all { it.unresolvedBoundaryIds.all(boundaryIds::contains) }) {
        "Repository intelligence unresolved boundary reference is invalid"
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

private fun validateRepositoryIntelligenceLifecycleRecord(
    record: RepositoryIntelligenceManifestLifecycleRecord,
    previous: List<RepositoryIntelligenceManifestLifecycleRecord>,
) {
    require(record.recordId == previous.size + 1L) { "Repository intelligence lifecycle ID is not monotonic" }
    require(record.projectId > 0) { "Repository intelligence lifecycle project is invalid" }
    require(record.repositoryRevision.matches(GIT_REVISION)) { "Repository intelligence lifecycle revision is invalid" }
    require(record.traceId.isNotBlank()) { "Repository intelligence lifecycle trace ID is invalid" }
    require(record.manifestKey.repositoryId == record.projectId) { "Repository intelligence lifecycle manifest project is invalid" }
    require(record.manifestKey.commitHash == record.repositoryRevision) { "Repository intelligence lifecycle manifest revision is invalid" }
    require(record.manifestKey.extractorVersion > 0 && record.manifestKey.policyVersion > 0) {
        "Repository intelligence lifecycle manifest versions are invalid"
    }
    require(record.graphId == null || record.graphId > 0) { "Repository intelligence lifecycle graph reference is invalid" }
}

private fun validateRepositoryIntelligenceTraceSpan(
    span: RepositoryIntelligenceTraceSpan,
    previous: List<RepositoryIntelligenceTraceSpan>,
) {
    require(span.spanId == previous.size + 1L) { "Repository intelligence trace span ID is not monotonic" }
    require(span.traceId.isNotBlank()) { "Repository intelligence trace ID is invalid" }
    require(span.projectId >= 0) { "Repository intelligence trace project is invalid" }
    require(span.repositoryRevision.isBlank() || span.repositoryRevision.matches(GIT_REVISION)) {
        "Repository intelligence trace revision is invalid"
    }
    require(span.status.isNotBlank()) { "Repository intelligence trace status is invalid" }
    require(span.elapsedMillis >= 0) { "Repository intelligence trace duration is invalid" }
    require(span.runId == null || span.runId > 0) { "Repository intelligence trace run reference is invalid" }
    require(span.graphId == null || span.graphId > 0) { "Repository intelligence trace graph reference is invalid" }
    require(span.manifestKey == null || (
        span.manifestKey.repositoryId == span.projectId &&
            span.manifestKey.commitHash == span.repositoryRevision &&
            span.manifestKey.extractorVersion > 0 &&
            span.manifestKey.policyVersion > 0
        )) { "Repository intelligence trace manifest key is invalid" }
}

@Serializable
private data class RepositoryIntelligenceGraphEnvelope(
    val version: Int = 1,
    val value: RepositoryIntelligenceGraph,
    val checksum: String,
)

@Serializable
private data class RepositoryIntelligenceLifecycleEnvelope(
    val version: Int = 1,
    val value: RepositoryIntelligenceManifestLifecycleRecord,
    val checksum: String,
)

@Serializable
private data class RepositoryIntelligenceTraceEnvelope(
    val version: Int = 1,
    val value: RepositoryIntelligenceTraceSpan,
    val checksum: String,
)

private val intelligenceGraphJson = Json { encodeDefaults = true }
private val GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val SHA256 = Regex("[0-9a-f]{64}")
private val VALID_ROLES = setOf(
    INTELLIGENCE_ROLE_UNCLASSIFIED,
    INTELLIGENCE_ROLE_TRANSPORT_SERVER,
    INTELLIGENCE_ROLE_MODEL,
    INTELLIGENCE_ROLE_SERVICE,
    INTELLIGENCE_ROLE_AUTHORITY,
    INTELLIGENCE_ROLE_PERSISTENCE,
    INTELLIGENCE_ROLE_PROJECTION,
    INTELLIGENCE_ROLE_UI,
    INTELLIGENCE_ROLE_TEST,
    INTELLIGENCE_ROLE_TOOLCHAIN,
)
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

private fun validRepositoryIntelligencePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." } && path != ".git" && !path.startsWith(".git/") &&
    path != ".orchard" && !path.startsWith(".orchard/")

internal fun newRepositoryIntelligenceTraceId(
    operation: String,
    projectId: Int,
    repositoryRevision: String,
    runId: Long? = null,
): String = stableRepositoryIntelligenceTraceId(
    operation = operation,
    projectId = projectId,
    repositoryRevision = repositoryRevision,
    runId = runId,
    recordedAt = Instant.now().toString(),
)

private fun stableRepositoryIntelligenceTraceId(
    operation: String,
    projectId: Int,
    repositoryRevision: String,
    runId: Long?,
    recordedAt: String,
): String = "trace:${sha256Content(listOf(operation, projectId.toString(), repositoryRevision, runId?.toString().orEmpty(), recordedAt).joinToString("\n")).take(24)}"

private fun elapsedMillisSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L