package com.orchard.backend.standards

import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CodingWorkspaceGateway
import com.orchard.backend.agent.LocalCodingWorkspaceGateway
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_BUG
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EngineeringStandardSubmission(
    val name: String,
    val practices: List<EngineeringPractice>,
    val actor: String = "HUMAN",
)

@Serializable
data class EngineeringStandardsView(
    val baseline: List<EngineeringPractice>,
    val standards: List<EngineeringStandardRevision>,
    val scans: List<RepositoryConformanceScan>,
    val admissions: List<ConformanceBacklogAdmission>,
)

enum class StandardUpdateStatus { UPDATED, PROJECT_NOT_FOUND, INVALID_STANDARD, STORAGE_UNAVAILABLE }

@Serializable
data class StandardUpdateResult(
    val status: StandardUpdateStatus,
    val standard: EngineeringStandardRevision? = null,
    val diagnostic: String = "",
)

enum class ConformanceScanStatus {
    CREATED,
    BUSY,
    PROJECT_NOT_FOUND,
    STANDARD_NOT_FOUND,
    REPOSITORY_UNAVAILABLE,
    REPOSITORY_DIRTY,
    ALREADY_SCANNED,
    CONTEXT_UNAVAILABLE,
    CONTEXT_BUDGET_EXCEEDED,
    NO_COMPATIBLE_MODEL,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_OUTPUT,
    REPOSITORY_DRIFTED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class ConformanceScanResult(
    val status: ConformanceScanStatus,
    val scan: RepositoryConformanceScan? = null,
    val diagnostic: String = "",
)

enum class BacklogAdmissionStatus {
    ADMITTED,
    SCAN_NOT_FOUND,
    ALREADY_ADMITTED,
    REPOSITORY_DRIFTED,
    EMPTY_BACKLOG,
    CAPACITY_EXCEEDED,
    INVALID_BACKLOG,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class BacklogAdmissionResult(
    val status: BacklogAdmissionStatus,
    val admission: ConformanceBacklogAdmission? = null,
    val diagnostic: String = "",
)

@Serializable
private data class ConformanceAnalysisEnvelope(
    val standard: EngineeringStandardRevision,
    val repositoryRevision: String,
    val repositoryContext: CodingRepositoryContext,
    val allowedVerificationCommands: List<String>,
    val maxBacklogNodes: Int,
    val requiredOutputSchema: String = "engineering-conformance-v1",
)

@Serializable
private data class ConformanceAnalysisOutput(
    val findings: List<ConformanceFinding>,
    val proposedBacklog: List<BacklogProposalNode>,
)

class EngineeringStandardsService(
    private val workspace: WorkspaceStore,
    private val repositoryBindings: RepositoryBindingStore,
    private val modelProviders: List<ModelProvider>,
    private val store: EngineeringStandardsStore = TransientEngineeringStandardsStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val systemPrompt: String = loadPrompt(),
) {
    private val scanMutex = Mutex()

    fun view(projectId: Int): EngineeringStandardsView = EngineeringStandardsView(
        baseline = defaultEngineeringPractices(),
        standards = store.standards().filter { it.projectId == projectId },
        scans = store.scans().filter { it.projectId == projectId },
        admissions = store.admissions().filter { it.projectId == projectId },
    )

    @Synchronized
    fun updateStandard(projectId: Int, submission: EngineeringStandardSubmission): StandardUpdateResult {
        if (!projectExists(projectId)) return StandardUpdateResult(StandardUpdateStatus.PROJECT_NOT_FOUND)
        val prior = store.standards().filter { it.projectId == projectId }.maxByOrNull { it.revision }
        val standard = runCatching {
            newEngineeringStandardRevision(
                standardId = prior?.standardId ?: (store.standards().maxOfOrNull { it.standardId } ?: 0L) + 1L,
                projectId = projectId,
                revision = (prior?.revision ?: 0) + 1,
                name = submission.name.trim(),
                practices = submission.practices,
                actor = submission.actor.trim(),
                createdAt = Instant.now().toString(),
                previousHash = prior?.hash,
            )
        }.getOrElse { return StandardUpdateResult(StandardUpdateStatus.INVALID_STANDARD, diagnostic = it.message.orEmpty()) }
        return runCatching { store.appendStandard(standard) }.fold(
            onSuccess = { StandardUpdateResult(StandardUpdateStatus.UPDATED, standard) },
            onFailure = { StandardUpdateResult(StandardUpdateStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    suspend fun scan(projectId: Int): ConformanceScanResult {
        if (!scanMutex.tryLock()) return ConformanceScanResult(ConformanceScanStatus.BUSY)
        return try {
            scanLocked(projectId)
        } finally {
            scanMutex.unlock()
        }
    }

    @Synchronized
    fun admitBacklog(scanId: Long, actor: String = "HUMAN"): BacklogAdmissionResult {
        val scan = store.scans().singleOrNull { it.scanId == scanId }
            ?: return BacklogAdmissionResult(BacklogAdmissionStatus.SCAN_NOT_FOUND)
        if (store.admissions().any { it.scanId == scanId }) return BacklogAdmissionResult(BacklogAdmissionStatus.ALREADY_ADMITTED)
        if (scan.proposedBacklog.isEmpty()) return BacklogAdmissionResult(BacklogAdmissionStatus.EMPTY_BACKLOG)
        val head = repositoryBindings.resolveHead(scan.projectId)
            ?: return BacklogAdmissionResult(BacklogAdmissionStatus.REPOSITORY_DRIFTED)
        if (!head.clean || head.commitHash != scan.repositoryRevision) return BacklogAdmissionResult(BacklogAdmissionStatus.REPOSITORY_DRIFTED)
        val priorEntityIds = existingAdmissionEntityIds(scan)
        if (priorEntityIds != null) return appendAdmission(scan, actor, priorEntityIds)
        if (workspace.entityCount + scan.proposedBacklog.size > MAX_WORKSPACE_ENTITIES) {
            return BacklogAdmissionResult(BacklogAdmissionStatus.CAPACITY_EXCEEDED)
        }
        val createdIds = mutableListOf<Int>()
        val entityIds = mutableMapOf<String, Int>()
        workspace.beginBatch()
        try {
            scan.proposedBacklog.forEach { node ->
                val parent = node.parentNodeId?.let(entityIds::get)
                val intent = backlogIntent(scan, node, parent, entityIds)
                if (!workspace.applyIntent(intent)) {
                    workspace.rollbackBatch()
                    return BacklogAdmissionResult(BacklogAdmissionStatus.INVALID_BACKLOG, diagnostic = "Backlog node ${node.nodeId} violates workspace hierarchy.")
                }
                val entity = workspace.entityAt(workspace.entityCount - 1)
                entityIds[node.nodeId] = entity.id
                createdIds += entity.id
            }
            workspace.commitBatch()
        } catch (error: Exception) {
            runCatching { workspace.rollbackBatch() }
            return BacklogAdmissionResult(BacklogAdmissionStatus.STORAGE_UNAVAILABLE, diagnostic = error.message.orEmpty())
        }
        return appendAdmission(scan, actor, createdIds)
    }

    private fun appendAdmission(
        scan: RepositoryConformanceScan,
        actor: String,
        entityIds: List<Int>,
    ): BacklogAdmissionResult {
        val admission = newConformanceBacklogAdmission(
            ConformanceBacklogAdmission(
                admissionId = (store.admissions().maxOfOrNull { it.admissionId } ?: 0L) + 1L,
                scanId = scan.scanId,
                scanHash = scan.hash,
                projectId = scan.projectId,
                repositoryRevision = scan.repositoryRevision,
                admittedEntityIds = entityIds,
                actor = actor,
                admittedAt = Instant.now().toString(),
                hash = "",
            )
        )
        return runCatching { store.appendAdmission(admission) }.fold(
            onSuccess = { BacklogAdmissionResult(BacklogAdmissionStatus.ADMITTED, admission) },
            onFailure = { BacklogAdmissionResult(BacklogAdmissionStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    private fun existingAdmissionEntityIds(scan: RepositoryConformanceScan): List<Int>? {
        val marker = "Conformance scan: ${scan.hash}"
        val entities = (0 until workspace.entityCount).map(workspace::entityAt).filter { marker in it.content }
        if (entities.isEmpty()) return null
        val expectedTitles = scan.proposedBacklog.map { it.title }.sorted()
        require(entities.size == scan.proposedBacklog.size && entities.map { it.title }.sorted() == expectedTitles) {
            "Workspace contains an incomplete or altered admission batch for scan ${scan.scanId}."
        }
        return entities.map { it.id }
    }

    private suspend fun scanLocked(projectId: Int): ConformanceScanResult {
        if (!projectExists(projectId)) return ConformanceScanResult(ConformanceScanStatus.PROJECT_NOT_FOUND)
        val standard = store.standards().filter { it.projectId == projectId }.maxByOrNull { it.revision }
            ?: return ConformanceScanResult(ConformanceScanStatus.STANDARD_NOT_FOUND)
        val head = repositoryBindings.resolveHead(projectId)
            ?: return ConformanceScanResult(ConformanceScanStatus.REPOSITORY_UNAVAILABLE)
        if (!head.clean) return ConformanceScanResult(ConformanceScanStatus.REPOSITORY_DIRTY)
        if (store.scans().any { it.projectId == projectId && it.standardHash == standard.hash && it.repositoryRevision == head.commitHash }) {
            return ConformanceScanResult(ConformanceScanStatus.ALREADY_SCANNED)
        }
        val query = standard.practices.filter { it.enabled }.joinToString("\n") {
            "${it.practiceId} ${it.title} ${it.category} ${it.applicability} ${it.requirement} ${it.requiredEvidence.joinToString(" ")}"
        }
        val context = runCatching { workspaceGateway.collectAnalysisContext(head.path, query) }.getOrElse {
            return ConformanceScanResult(ConformanceScanStatus.CONTEXT_UNAVAILABLE, diagnostic = it.message.orEmpty())
        }
        if (context.files.isEmpty()) return ConformanceScanResult(ConformanceScanStatus.CONTEXT_UNAVAILABLE, diagnostic = "No repository evidence was selected.")
        val commands = verificationCommands(repositoryBindings.views(setOf(projectId))[projectId]?.buildSystem.orEmpty())
        val envelope = ConformanceAnalysisEnvelope(standard, head.commitHash, context, commands, MAX_BACKLOG_NODES)
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative conformance envelope:\n$envelopeJson"
        val profile = DefaultModelExecutionProfiles.broadRepositoryAnalysis
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) return ConformanceScanResult(ConformanceScanStatus.CONTEXT_BUDGET_EXCEEDED)
        val provider = runCatching { ModelProfileResolver.resolve(profile, modelProviders) }.getOrNull()
            ?: return ConformanceScanResult(ConformanceScanStatus.NO_COMPATIBLE_MODEL)
        val admission = resourceController.tryAcquire(provider.resourceDemand(profile))
        val lease = admission.lease ?: return ConformanceScanResult(ConformanceScanStatus.RESOURCE_BLOCKED, diagnostic = admission.evidence.reason)
        val generation = try {
            lease.use { provider.executeRepositoryAnalysis(prompt, profile.outputBudgetTokens, profile.inputBudgetTokens + profile.outputBudgetTokens) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            return ConformanceScanResult(ConformanceScanStatus.MODEL_FAILED, diagnostic = error.message.orEmpty())
        }
        val output = generation.takeIf {
            it.promptTokens <= profile.inputBudgetTokens && it.completionTokens <= profile.outputBudgetTokens &&
                estimateModelTokens(it.text) <= profile.outputBudgetTokens
        }?.let { runCatching { json.decodeFromString<ConformanceAnalysisOutput>(it.text) }.getOrNull() }
            ?: return ConformanceScanResult(ConformanceScanStatus.INVALID_OUTPUT, diagnostic = "The model did not return valid strict JSON.")
        val invalid = validateOutput(standard, context, commands, output)
        if (invalid != null) return ConformanceScanResult(ConformanceScanStatus.INVALID_OUTPUT, diagnostic = invalid)
        if (workspaceGateway.currentRevision(head.path) != head.commitHash) return ConformanceScanResult(ConformanceScanStatus.REPOSITORY_DRIFTED)
        val binding = provider.bindingProfile()
        val scan = newRepositoryConformanceScan(
            RepositoryConformanceScan(
                scanId = (store.scans().maxOfOrNull { it.scanId } ?: 0L) + 1L,
                projectId = projectId,
                standardId = standard.standardId,
                standardRevision = standard.revision,
                standardHash = standard.hash,
                repositoryRevision = head.commitHash,
                findings = output.findings,
                proposedBacklog = output.proposedBacklog,
                modelBindingFingerprint = modelBindingFingerprint(binding),
                promptHash = sha256(prompt),
                contextHash = sha256(envelopeJson),
                outputHash = sha256(generation.text),
                createdAt = Instant.now().toString(),
                hash = "",
            )
        )
        return runCatching { store.appendScan(scan) }.fold(
            onSuccess = { ConformanceScanResult(ConformanceScanStatus.CREATED, scan) },
            onFailure = { ConformanceScanResult(ConformanceScanStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    private fun validateOutput(
        standard: EngineeringStandardRevision,
        context: CodingRepositoryContext,
        commands: List<String>,
        output: ConformanceAnalysisOutput,
    ): String? {
        val enabled = standard.practices.filter { it.enabled }.map { it.practiceId }.toSet()
        if (output.findings.map { it.practiceId }.toSet() != enabled || output.findings.size != enabled.size) {
            return "The scan must return exactly one finding for every enabled practice."
        }
        val files = context.files.associateBy { it.path }
        if (output.findings.flatMap { it.citations }.any { files[it.path]?.contentHash != it.contentHash }) {
            return "The scan cites repository evidence outside the supplied revision context."
        }
        if (output.findings.flatMap { it.affectedPaths }.any { it !in files }) return "A finding targets an unobserved repository path."
        if (output.findings.flatMap { it.verificationCommands }.any { it !in commands }) return "A finding invented a verification command."
        val actionable = output.findings.filter {
            it.disposition in setOf(CONFORMANCE_NONCONFORMING, CONFORMANCE_PARTIAL, CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING)
        }.map { it.findingId }.toSet()
        val requiredPracticeIds = standard.practices.filter { it.enabled && it.severity == PRACTICE_SEVERITY_REQUIRED }
            .map { it.practiceId }.toSet()
        if (output.findings.any {
                it.practiceId in requiredPracticeIds && it.findingId in actionable &&
                    (it.acceptanceCriteria.isEmpty() || it.verificationCommands.isEmpty())
            }) {
            return "An actionable required practice must include acceptance criteria and verification commands."
        }
        if (output.proposedBacklog.flatMap { it.findingIds }.any { it !in actionable }) return "Backlog includes a non-actionable finding."
        if (actionable != output.proposedBacklog.flatMap { it.findingIds }.toSet()) return "Backlog must cover every actionable finding."
        if (output.proposedBacklog.flatMap { it.verificationCommands }.any { it !in commands }) return "Backlog invented a verification command."
        if (output.proposedBacklog.size > MAX_BACKLOG_NODES) return "Backlog proposal exceeds the onboarding limit."
        return null
    }

    private fun backlogIntent(
        scan: RepositoryConformanceScan,
        node: BacklogProposalNode,
        parentId: Int?,
        entityIds: Map<String, Int>,
    ): DocumentIntent {
        val parentNode = node.parentNodeId?.let { id -> scan.proposedBacklog.singleOrNull { it.nodeId == id } }
        val epicId = when {
            node.type == BACKLOG_STORY -> parentId ?: 0
            node.type in setOf(BACKLOG_TASK, BACKLOG_BUG, BACKLOG_INVESTIGATION) ->
                parentNode?.parentNodeId?.let(entityIds::get) ?: 0
            else -> 0
        }
        val type = when (node.type) {
            BACKLOG_EPIC -> ENTITY_EPIC
            BACKLOG_STORY -> ENTITY_STORY
            BACKLOG_BUG -> ENTITY_BUG
            BACKLOG_TASK, BACKLOG_INVESTIGATION -> ENTITY_TASK
            else -> error("Unsupported backlog node type ${node.type}")
        }
        return DocumentIntent(
            actionTypeId = ACTION_CREATE,
            entityTypeId = type,
            boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
            projectId = scan.projectId,
            epicId = epicId,
            storyId = if (type in setOf(ENTITY_TASK, ENTITY_BUG)) parentId ?: 0 else 0,
            title = node.title,
            content = buildString {
                appendLine(node.description)
                appendLine("Conformance scan: ${scan.hash}")
                appendLine("Conformance findings: ${node.findingIds.joinToString()}")
                if (node.acceptanceCriteria.isNotEmpty()) appendLine("Acceptance: ${node.acceptanceCriteria.joinToString(" | ")}")
                if (node.verificationCommands.isNotEmpty()) append("Verification: ${node.verificationCommands.joinToString(" | ")}")
            }.trim(),
        )
    }

    private fun projectExists(projectId: Int): Boolean = (0 until workspace.entityCount)
        .map(workspace::entityAt)
        .any { it.id == projectId && it.type == com.orchard.backend.workspace.ENTITY_PROJECT }

    private fun verificationCommands(buildSystem: String): List<String> = when (buildSystem.lowercase()) {
        "gradle" -> listOf("./gradlew build --no-daemon", "./gradlew test --no-daemon")
        "maven" -> listOf("./mvnw test")
        "cargo" -> listOf("cargo test")
        "meson" -> listOf("meson test -C build")
        "cmake" -> listOf("ctest --test-dir build")
        "node" -> listOf("npm test")
        else -> emptyList()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_BACKLOG_NODES = 24
        const val MAX_WORKSPACE_ENTITIES = 32

        fun loadPrompt(): String = requireNotNull(
            EngineeringStandardsService::class.java.getResourceAsStream("/default-system-prompts/engineering_conformance_agent.md")
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

fun defaultEngineeringPractices(): List<EngineeringPractice> = listOf(
    EngineeringPractice(
        "AUTHORITY_INTEGRITY",
        "Persisted authority is corruption-detectable",
        "DATA_INTEGRITY",
        PRACTICE_SEVERITY_REQUIRED,
        "Authoritative durable state",
        "Persisted authority must use integrity verification, recovery validation, and atomic or append-only publication.",
        listOf("source implementation", "recovery test", "corruption test"),
        "Add checksummed serialization, fail-closed recovery validation, and tests for valid recovery and corruption handling.",
    ),
    EngineeringPractice(
        "MODEL_AUTHORITY_BOUNDARY",
        "Model output remains candidate data",
        "AGENT_GOVERNANCE",
        PRACTICE_SEVERITY_REQUIRED,
        "All model-generated mutations and decisions",
        "Model output must be decoded and deterministically validated before it can mutate repository or workflow authority.",
        listOf("typed output schema", "deterministic validator", "adversarial test"),
        "Separate generation from admission and add deterministic validation before mutation.",
    ),
    EngineeringPractice(
        "EVIDENCE_BOUND_CHANGE",
        "Changes are revision and evidence bound",
        "DELIVERY_GOVERNANCE",
        PRACTICE_SEVERITY_REQUIRED,
        "Repository-changing workflows",
        "Repository changes must be pinned to an immutable base revision and accepted through explicit verification evidence.",
        listOf("revision binding", "verification evidence", "stale-revision test"),
        "Pin work to repository revision, reject drift, and require passing verification evidence before acceptance.",
    ),
    EngineeringPractice(
        "SECRET_EXTERNAL_AUTHORITY",
        "Secrets remain outside persisted authority",
        "SECURITY",
        PRACTICE_SEVERITY_REQUIRED,
        "Credentials and external service authentication",
        "Persist only secret references; resolve secret values at the request boundary and exclude them from provenance and diagnostics.",
        listOf("credential reference schema", "request-time resolution", "secret-leak test"),
        "Replace persisted credential values with external references and test persistence and diagnostics for leakage.",
    ),
    EngineeringPractice(
        "DECISION_IMPLEMENTATION_CORRELATION",
        "Architectural decisions correlate with implementation",
        "ARCHITECTURE",
        PRACTICE_SEVERITY_ADVISORY,
        "Accepted ADRs and architecture declarations",
        "Accepted architectural decisions must cite or be traceable to implementing code and verification; implementation that changes architecture requires a corresponding decision update.",
        listOf("ADR evidence", "implementation evidence", "verification evidence"),
        "Update stale decisions or implement the missing behavior, then record explicit code and test correlations.",
    ),
)
