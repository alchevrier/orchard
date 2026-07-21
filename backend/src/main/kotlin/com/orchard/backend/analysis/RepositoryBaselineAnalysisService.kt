package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RepositoryBaselineTickStatus {
    IDLE,
    BUSY,
    STAGE_COMPLETED,
    COMPLETE,
    PROJECT_NOT_FOUND,
    REPOSITORY_UNAVAILABLE,
    CONTEXT_BUDGET_EXCEEDED,
    NO_COMPATIBLE_MODEL,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_ANALYSIS,
    REPOSITORY_CHANGED,
    STORAGE_UNAVAILABLE,
}

data class RepositoryBaselineTickResult(
    val status: RepositoryBaselineTickStatus,
    val projectId: Int,
    val stage: String? = null,
    val analysis: RepositoryBaselineAnalysis? = null,
    val diagnostic: String = "",
)

@Serializable
private data class RepositoryBaselineStageOutput(
    val summary: String,
    val findings: List<RepositoryCapabilityClaim>,
    val unresolvedQuestions: List<String> = emptyList(),
)

@Serializable
private data class RepositoryBaselinePromptSection(
    val stage: String,
    val summary: String,
    val findings: List<RepositoryCapabilityClaim>,
)

@Serializable
private data class RepositoryBaselineEnvelope(
    val analysisVersion: Int,
    val projectId: Int,
    val genesisRevision: Int,
    val repositoryRevision: String,
    val graphHash: String,
    val stage: String,
    val requiredCoverage: List<String>,
    val priorSections: List<RepositoryBaselinePromptSection>,
    val graph: RepositoryBaselineGraphSlice,
    val repositoryContext: CodingRepositoryContext,
    val requiredOutputSchema: String,
)

@Serializable
private data class RepositoryBaselineGraphSlice(
    val coverage: RepositoryIntelligenceCoverage,
    val nodeKindCounts: Map<String, Int>,
    val edgeKindCounts: Map<String, Int>,
    val nodes: List<RepositoryIntelligenceNode>,
    val edges: List<RepositoryIntelligenceEdge>,
)

class RepositoryBaselineAnalysisService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val store: RepositoryBaselineAnalysisStore = TransientRepositoryBaselineAnalysisStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val intelligenceImporter: RepositoryIntelligenceImporter = RepositoryIntelligenceImporter(workspace),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val systemPrompt: String = loadPrompt(),
) {
    private val projectMutexes = ConcurrentHashMap<Int, Mutex>()

    init {
        store.load()
    }

    fun analyses(): List<RepositoryBaselineAnalysis> = store.load()

    fun latest(projectId: Int): RepositoryBaselineAnalysis? {
        val snapshot = workspace.snapshot(0)
        val repository = snapshot.repositories[projectId]?.takeIf { it.available && !it.dirty } ?: return null
        val repositoryRevision = runCatching { workspaceGateway.currentRevision(repository.path) }.getOrNull() ?: return null
        val genesisRevision = snapshot.projectGenesis.singleOrNull { it.projectId == projectId }?.revision?.revision ?: 0
        val graph = runCatching { intelligenceImporter.import(projectId, repository.path, repositoryRevision) }.getOrNull() ?: return null
        return store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
            it.genesisRevision == genesisRevision &&
            it.graphHash == graph.hash
        }
    }

    suspend fun tick(projectId: Int): RepositoryBaselineTickResult {
        val mutex = projectMutexes.computeIfAbsent(projectId) { Mutex() }
        if (!mutex.tryLock()) return RepositoryBaselineTickResult(RepositoryBaselineTickStatus.BUSY, projectId)
        return try {
            analyzeNextStage(projectId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun analyzeNextStage(projectId: Int): RepositoryBaselineTickResult {
        val snapshot = workspace.snapshot(0)
        val genesis = snapshot.projectGenesis.singleOrNull { it.projectId == projectId }
            ?: return RepositoryBaselineTickResult(RepositoryBaselineTickStatus.PROJECT_NOT_FOUND, projectId)
        val repository = snapshot.repositories[projectId]?.takeIf { it.available && !it.dirty }
            ?: return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
                projectId,
                diagnostic = "The bound repository is unavailable or has uncommitted changes.",
            )
        val repositoryRevision = runCatching { workspaceGateway.currentRevision(repository.path) }.getOrNull()
            ?: return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
                projectId,
                diagnostic = "The bound repository revision is unavailable.",
            )
        val genesisRevision = genesis.revision?.revision ?: 0
        val graph = runCatching {
            intelligenceImporter.import(projectId, repository.path, repositoryRevision)
        }.getOrElse {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.STORAGE_UNAVAILABLE,
                projectId,
                diagnostic = "Repository intelligence import failed: ${it.message.orEmpty()}",
            )
        }
        val predecessor = store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
                it.genesisRevision == genesisRevision &&
                it.graphHash == graph.hash
        }
        if (predecessor?.complete == true) {
            return RepositoryBaselineTickResult(RepositoryBaselineTickStatus.IDLE, projectId, analysis = predecessor)
        }
        val stage = REPOSITORY_BASELINE_STAGES[predecessor?.sections?.size ?: 0]
        val context = runCatching {
            workspaceGateway.collectIntelligenceContext(repository.path, repositoryRevision, graphPaths(stage, graph))
        }.getOrElse {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
                projectId,
                stage,
                diagnostic = it.message.orEmpty(),
            )
        }
        val profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis
        val provider = runCatching { ModelProfileResolver.resolve(profile, modelProviders) }.getOrNull()
            ?: return RepositoryBaselineTickResult(RepositoryBaselineTickStatus.NO_COMPATIBLE_MODEL, projectId, stage)
        fun envelopeFor(candidate: CodingRepositoryContext) = RepositoryBaselineEnvelope(
            analysisVersion = ANALYSIS_VERSION,
            projectId = projectId,
            genesisRevision = genesisRevision,
            repositoryRevision = repositoryRevision,
            graphHash = graph.hash,
            stage = stage,
            requiredCoverage = stageCoverage(stage),
            priorSections = predecessor?.sections.orEmpty().map {
                RepositoryBaselinePromptSection(it.stage, it.summary, it.findings)
            },
            graph = graphSlice(graph, candidate.files.map { it.path }),
            repositoryContext = candidate,
            requiredOutputSchema = OUTPUT_SCHEMA,
        )
        val boundedContext = if (context.files.isEmpty()) context else compactRepositoryContextToBudget(
            context,
            profile.inputBudgetTokens,
        ) { candidate ->
            prompt(json.encodeToString(envelopeFor(candidate)))
        } ?: return RepositoryBaselineTickResult(
            RepositoryBaselineTickStatus.CONTEXT_BUDGET_EXCEEDED,
            projectId,
            stage,
            diagnostic = "The minimum $stage evidence envelope exceeds the repository-analysis model budget.",
        )
        val envelopeJson = json.encodeToString(envelopeFor(boundedContext))
        val prompt = prompt(envelopeJson)
        val promptTokens = estimateModelTokens(prompt)
        val admission = resourceController.tryAcquire(provider.resourceDemand(profile, promptTokens))
        val lease = admission.lease ?: return RepositoryBaselineTickResult(
            RepositoryBaselineTickStatus.RESOURCE_BLOCKED,
            projectId,
            stage,
            diagnostic = admission.evidence.reason,
        )
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use {
                provider.executeRepositoryAnalysis(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            recordExecution(projectId, stage, profile, provider, envelopeJson, prompt, null, startedAt, false, admission.evidence)
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.MODEL_FAILED,
                projectId,
                stage,
                diagnostic = listOfNotNull(error::class.simpleName, error.message).joinToString(": "),
            )
        }
        val output = generation.takeIf {
            it.promptTokens <= profile.inputBudgetTokens &&
                it.completionTokens <= profile.outputBudgetTokens &&
                estimateModelTokens(it.text) <= profile.outputBudgetTokens
        }?.let { runCatching { json.decodeFromString<RepositoryBaselineStageOutput>(it.text) }.getOrNull() }
        val invalid = output?.let { validateOutput(stage, boundedContext, predecessor, it) }
        recordExecution(
            projectId,
            stage,
            profile,
            provider,
            envelopeJson,
            prompt,
            generation,
            startedAt,
            output != null && invalid == null,
            admission.evidence,
        )
        if (output == null || invalid != null) {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.INVALID_ANALYSIS,
                projectId,
                stage,
                diagnostic = invalid ?: "The analysis model did not return valid strict JSON for $stage.",
            )
        }
        if (workspaceGateway.currentRevision(repository.path) != repositoryRevision) {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_CHANGED,
                projectId,
                stage,
                diagnostic = "The repository changed during $stage analysis.",
            )
        }
        val section = RepositoryBaselineSection(
            stage = stage,
            summary = output.summary,
            findings = output.findings,
            unresolvedQuestions = output.unresolvedQuestions,
            omittedRepositoryFileCount = boundedContext.omittedFileCount,
            model = provider.bindingProfile().model,
            promptHash = sha256(prompt),
            outputHash = sha256(generation.text),
        )
        val analysis = runCatching {
            store.appendNext { analysisId ->
                newRepositoryBaselineAnalysis(
                    analysisId = analysisId,
                    projectId = projectId,
                    genesisRevision = genesisRevision,
                    repositoryRevision = repositoryRevision,
                    graphHash = graph.hash,
                    graphCoverage = graph.coverage,
                    sections = predecessor?.sections.orEmpty() + section,
                )
            }
        }.getOrElse {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.STORAGE_UNAVAILABLE,
                projectId,
                stage,
                diagnostic = it.message.orEmpty(),
            )
        }
        return RepositoryBaselineTickResult(
            if (analysis.complete) RepositoryBaselineTickStatus.COMPLETE else RepositoryBaselineTickStatus.STAGE_COMPLETED,
            projectId,
            stage,
            analysis,
        )
    }

    private fun validateOutput(
        stage: String,
        context: CodingRepositoryContext,
        predecessor: RepositoryBaselineAnalysis?,
        output: RepositoryBaselineStageOutput,
    ): String? {
        if (output.summary.isBlank()) return "$stage analysis has no summary."
        repositoryCapabilityClaimsError(output.findings)?.let { return it }
        if (output.findings.size !in 2..8) return "$stage analysis must contain 2 to 8 findings."
        val prefix = stage.lowercase() + "-"
        if (output.findings.any { !it.claimId.startsWith(prefix) }) {
            return "$stage finding IDs must start with '$prefix'."
        }
        val priorIds = predecessor?.sections.orEmpty().flatMap { it.findings }.mapTo(mutableSetOf()) { it.claimId }
        if (output.findings.any { it.claimId in priorIds }) return "$stage analysis duplicates a prior finding ID."
        val files = context.files.associateBy { it.path }
        if (output.findings.flatMap { it.support + it.defeaters }.any { citation ->
                files[citation.path]?.contentHash != citation.contentHash || citation.observation.isBlank()
            }) return "$stage analysis cited evidence outside the pinned context."
        if (output.unresolvedQuestions.size > 8 || output.unresolvedQuestions.any { it.isBlank() || !it.endsWith('?') }) {
            return "$stage unresolved questions must be direct questions."
        }
        return null
    }

    private fun recordExecution(
        projectId: Int,
        stage: String,
        profile: com.orchard.backend.vector.ModelExecutionProfile,
        provider: ModelProvider,
        envelopeJson: String,
        prompt: String,
        generation: com.orchard.backend.vector.ModelGeneration?,
        startedAt: Long,
        schemaValid: Boolean,
        admission: com.orchard.backend.resource.ResourceAdmissionEvidence,
    ) {
        workspace.recordModelExecution(
            ModelExecutionObservationDraft(
                profile = profile,
                binding = provider.bindingProfile(),
                workflowStepId = "repository-baseline-${stage.lowercase()}",
                workItemId = projectId,
                envelopeHash = sha256(envelopeJson),
                promptHash = sha256(prompt),
                outputHash = generation?.text?.let(::sha256),
                inputTokens = generation?.promptTokens ?: estimateModelTokens(prompt),
                outputTokens = generation?.completionTokens ?: 0,
                latencyMillis = (System.nanoTime() - startedAt) / 1_000_000,
                schemaValid = schemaValid,
                resourceAdmission = admission,
            )
        )
    }

    private fun prompt(envelopeJson: String): String =
        "$systemPrompt\n\nAuthoritative repository baseline envelope:\n$envelopeJson"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val ANALYSIS_VERSION = 2
        const val OUTPUT_SCHEMA =
            "RepositoryBaselineStageOutput(summary, findings[claimId, statement, status, support, defeaters], unresolvedQuestions)"

        fun loadPrompt(): String = requireNotNull(
            RepositoryBaselineAnalysisService::class.java.classLoader
                .getResourceAsStream("default-system-prompts/repository_baseline_analysis_agent.md")
        ).bufferedReader().use { it.readText() }

        fun stageCoverage(stage: String): List<String> = when (stage) {
            BASELINE_STAGE_STRUCTURE -> listOf(
                "module and build topology",
                "runtime components and entry points",
                "important dependencies and persistence boundaries",
            )
            BASELINE_STAGE_DECISIONS -> listOf(
                "current ADR inventory and recorded status",
                "decisions and constraints established by those records",
                "gaps between recorded decisions and observed implementation",
            )
            BASELINE_STAGE_VERIFICATION -> listOf(
                "test suites and their scopes",
                "build, lint, and verification commands",
                "continuous integration and material verification gaps",
            )
            BASELINE_STAGE_DELIVERY -> listOf(
                "runtime and launch topology",
                "delivery workflows, automation, and operational evidence",
                "current risks, incomplete surfaces, and useful first outcomes",
            )
            else -> error("Unknown repository baseline stage $stage")
        }

        fun graphPaths(stage: String, graph: RepositoryIntelligenceGraph): List<String> {
            val primaryKinds = when (stage) {
                BASELINE_STAGE_STRUCTURE -> setOf(
                    INTELLIGENCE_NODE_SOURCE,
                    INTELLIGENCE_NODE_BUILD,
                    INTELLIGENCE_NODE_CONFIG,
                )
                BASELINE_STAGE_DECISIONS -> setOf(INTELLIGENCE_NODE_ADR, INTELLIGENCE_NODE_DOCUMENT)
                BASELINE_STAGE_VERIFICATION -> setOf(
                    INTELLIGENCE_NODE_TEST,
                    INTELLIGENCE_NODE_WORKFLOW,
                    INTELLIGENCE_NODE_BUILD,
                    INTELLIGENCE_NODE_SCRIPT,
                )
                BASELINE_STAGE_DELIVERY -> setOf(
                    INTELLIGENCE_NODE_WORKFLOW,
                    INTELLIGENCE_NODE_CONFIG,
                    INTELLIGENCE_NODE_SCRIPT,
                    INTELLIGENCE_NODE_BUILD,
                    INTELLIGENCE_NODE_DOCUMENT,
                )
                else -> error("Unknown repository baseline stage $stage")
            }
            val primary = graph.nodes.filter { it.kind in primaryKinds && it.path != null }
            val primaryIds = primary.mapTo(mutableSetOf()) { it.nodeId }
            val connectedIds = graph.edges.filter {
                it.kind in setOf(
                    INTELLIGENCE_EDGE_TESTS,
                    INTELLIGENCE_EDGE_DOCUMENTS,
                    INTELLIGENCE_EDGE_MAPS_TO,
                    INTELLIGENCE_EDGE_DEPENDS_ON,
                ) && (it.fromNodeId in primaryIds || it.toNodeId in primaryIds)
            }.flatMapTo(mutableSetOf()) { listOf(it.fromNodeId, it.toNodeId) }
            val selected = graph.nodes.filter { it.path != null && (it.nodeId in primaryIds || it.nodeId in connectedIds) }
                .sortedWith(compareBy<RepositoryIntelligenceNode> { it.nodeId !in primaryIds }.thenBy { it.path })
                .mapNotNull { it.path }
                .distinct()
            if (selected.isNotEmpty()) return selected
            return graph.nodes.filter {
                it.path != null && it.kind in setOf(
                    INTELLIGENCE_NODE_BUILD,
                    INTELLIGENCE_NODE_SOURCE,
                    INTELLIGENCE_NODE_CONFIG,
                    INTELLIGENCE_NODE_DOCUMENT,
                    INTELLIGENCE_NODE_SCRIPT,
                )
            }.mapNotNull { it.path }.distinct().sorted()
        }

        fun graphSlice(graph: RepositoryIntelligenceGraph, retainedPaths: List<String>): RepositoryBaselineGraphSlice {
            val retained = retainedPaths.toSet()
            val fileAndSymbolIds = graph.nodes.filter { node ->
                node.path in retained || node.kind in setOf(
                    INTELLIGENCE_NODE_REPOSITORY,
                    INTELLIGENCE_NODE_MODULE,
                    INTELLIGENCE_NODE_PROJECT,
                    INTELLIGENCE_NODE_WORK_ITEM,
                    INTELLIGENCE_NODE_COMPONENT,
                    INTELLIGENCE_NODE_DECISION,
                    INTELLIGENCE_NODE_RUN,
                    INTELLIGENCE_NODE_EVIDENCE,
                )
            }.mapTo(mutableSetOf()) { it.nodeId }
            val edges = graph.edges.filter { it.fromNodeId in fileAndSymbolIds && it.toNodeId in fileAndSymbolIds }
            return RepositoryBaselineGraphSlice(
                graph.coverage,
                graph.nodes.groupingBy { it.kind }.eachCount().toSortedMap(),
                graph.edges.groupingBy { it.kind }.eachCount().toSortedMap(),
                graph.nodes.filter { it.nodeId in fileAndSymbolIds },
                edges,
            )
        }
    }
}