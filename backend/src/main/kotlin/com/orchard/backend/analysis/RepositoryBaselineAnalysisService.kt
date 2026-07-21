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
    val stage: String,
    val requiredCoverage: List<String>,
    val priorSections: List<RepositoryBaselinePromptSection>,
    val repositoryContext: CodingRepositoryContext,
    val requiredOutputSchema: String,
)

class RepositoryBaselineAnalysisService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val store: RepositoryBaselineAnalysisStore = TransientRepositoryBaselineAnalysisStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
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
        return store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
                it.genesisRevision == genesisRevision
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
        val predecessor = store.load().lastOrNull {
            it.projectId == projectId &&
                it.repositoryRevision == repositoryRevision &&
                it.genesisRevision == genesisRevision
        }
        if (predecessor?.complete == true) {
            return RepositoryBaselineTickResult(RepositoryBaselineTickStatus.IDLE, projectId, analysis = predecessor)
        }
        val stage = REPOSITORY_BASELINE_STAGES[predecessor?.sections?.size ?: 0]
        val context = runCatching {
            workspaceGateway.collectAnalysisContext(repository.path, stageQuery(stage))
        }.getOrElse {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
                projectId,
                stage,
                diagnostic = it.message.orEmpty(),
            )
        }
        if (context.files.isEmpty()) {
            return RepositoryBaselineTickResult(
                RepositoryBaselineTickStatus.REPOSITORY_UNAVAILABLE,
                projectId,
                stage,
                diagnostic = "No tracked repository evidence was selected for $stage analysis.",
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
            stage = stage,
            requiredCoverage = stageCoverage(stage),
            priorSections = predecessor?.sections.orEmpty().map {
                RepositoryBaselinePromptSection(it.stage, it.summary, it.findings)
            },
            repositoryContext = candidate,
            requiredOutputSchema = OUTPUT_SCHEMA,
        )
        val boundedContext = compactRepositoryContextToBudget(context, profile.inputBudgetTokens) { candidate ->
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

        fun stageQuery(stage: String): String = when (stage) {
            BASELINE_STAGE_STRUCTURE ->
                "repository modules architecture components entry points APIs frontend backend persistence dependencies build toolchain"
            BASELINE_STAGE_DECISIONS ->
                "architecture decision records ADR decisions constraints consequences status superseded proposed accepted documentation"
            BASELINE_STAGE_VERIFICATION ->
                "tests testing unit integration end to end fixtures build verification CI workflows quality coverage lint"
            BASELINE_STAGE_DELIVERY ->
                "runtime deployment configuration scripts operations delivery workflows roadmap current implementation risks telemetry reports"
            else -> error("Unknown repository baseline stage $stage")
        }

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
    }
}