@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.analysis

import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val DISPOSITION_ABSENT = "ABSENT"
const val DISPOSITION_SCAFFOLD_ONLY = "SCAFFOLD_ONLY"
const val DISPOSITION_PARTIALLY_IMPLEMENTED = "PARTIALLY_IMPLEMENTED"
const val DISPOSITION_IMPLEMENTED_DIFFERENT_FORM = "IMPLEMENTED_DIFFERENT_FORM"
const val DISPOSITION_IMPLEMENTED_NONCONFORMING = "IMPLEMENTED_BUT_NONCONFORMING"
const val DISPOSITION_COMPLETE = "COMPLETE"
const val DISPOSITION_CONFLICTING = "CONFLICTING_IMPLEMENTATIONS"

const val PLAN_OPERATION_CREATE = "CREATE"
const val PLAN_OPERATION_MODIFY = "MODIFY"
const val PLAN_OPERATION_DELETE = "DELETE"
const val PLAN_OPERATION_VERIFY = "VERIFY"

@Serializable
data class RepositoryEvidenceCitation(
    val path: String,
    val symbol: String? = null,
    val observation: String,
    val contentHash: String,
)

@Serializable
data class ExecutionPlanOperation(
    val order: Int,
    val action: String,
    val path: String,
    val symbol: String? = null,
    val instruction: String,
    val acceptanceCriteria: List<String>,
)

@Serializable
data class RepositoryAnalysisPlanContent(
    val disposition: String,
    val summary: String,
    val evidence: List<RepositoryEvidenceCitation>,
    val reuse: List<String>,
    val preservedInvariants: List<String>,
    val nonGoals: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val coveredScope: List<String> = emptyList(),
    val operations: List<ExecutionPlanOperation>,
    val verificationCommands: List<String>,
    val unresolvedQuestions: List<String> = emptyList(),
)

@Serializable
data class AnalysisExecutionProvenance(
    val executionProfileId: String,
    val bindingFingerprint: String,
    val promptHash: String,
    val contextHash: String,
    val outputHash: String,
    val modelExecutionId: Long,
)

@Serializable
data class RepositoryExecutionPlan(
    val planId: Long,
    val runId: Long,
    val revision: Int,
    val projectId: Int,
    val baseRevision: String,
    val content: RepositoryAnalysisPlanContent,
    val provenance: AnalysisExecutionProvenance,
    val acceptedAt: String = Instant.now().toString(),
    val hash: String,
)

interface RepositoryExecutionPlanStore {
    fun load(): List<RepositoryExecutionPlan>
    fun append(plan: RepositoryExecutionPlan)
    fun appendNext(runId: Long, create: (planId: Long, revision: Int) -> RepositoryExecutionPlan): RepositoryExecutionPlan
}

class TransientRepositoryExecutionPlanStore : RepositoryExecutionPlanStore {
    private val plans = mutableListOf<RepositoryExecutionPlan>()

    @Synchronized
    override fun load(): List<RepositoryExecutionPlan> = plans.toList()

    @Synchronized
    override fun append(plan: RepositoryExecutionPlan) {
        validateRepositoryExecutionPlan(plan, plans)
        plans += plan
    }

    @Synchronized
    override fun appendNext(
        runId: Long,
        create: (planId: Long, revision: Int) -> RepositoryExecutionPlan,
    ): RepositoryExecutionPlan {
        val plan = create(plans.size + 1L, plans.count { it.runId == runId } + 1)
        append(plan)
        return plan
    }
}

class FileRepositoryExecutionPlanStore(private val directory: Path) : RepositoryExecutionPlanStore {
    private val path = directory.resolve("repository-analysis-plans.jsonl")
    private val lockPath = directory.resolve("repository-analysis-plans.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<RepositoryExecutionPlan> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    private fun loadUnlocked(): List<RepositoryExecutionPlan> = mutableListOf<RepositoryExecutionPlan>().also { plans ->
        loadRecoverableJsonl(path, "repository-analysis-plans") { line, recordNumber ->
            val envelope = json.decodeFromString<RepositoryExecutionPlanEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported repository execution plan format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in repository execution plan $recordNumber"
            }
            validateRepositoryExecutionPlan(envelope.value, plans)
            plans += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun append(plan: RepositoryExecutionPlan) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val plans = loadUnlocked()
                validateRepositoryExecutionPlan(plan, plans)
                val payload = json.encodeToString(plan)
                val line = json.encodeToString(
                    RepositoryExecutionPlanEnvelope(value = plan, checksum = stagedPlanHash(payload))
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    @Synchronized
    override fun appendNext(
        runId: Long,
        create: (planId: Long, revision: Int) -> RepositoryExecutionPlan,
    ): RepositoryExecutionPlan {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val plans = loadUnlocked()
                val plan = create(plans.size + 1L, plans.count { it.runId == runId } + 1)
                validateRepositoryExecutionPlan(plan, plans)
                appendUnlocked(plan)
                plan
            }
        }
    }

    private fun appendUnlocked(plan: RepositoryExecutionPlan) {
        val payload = json.encodeToString(plan)
        val line = json.encodeToString(
            RepositoryExecutionPlanEnvelope(value = plan, checksum = stagedPlanHash(payload))
        ) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun newRepositoryExecutionPlan(
    planId: Long,
    runId: Long,
    revision: Int,
    projectId: Int,
    baseRevision: String,
    content: RepositoryAnalysisPlanContent,
    provenance: AnalysisExecutionProvenance,
): RepositoryExecutionPlan {
    val unsigned = RepositoryExecutionPlan(planId, runId, revision, projectId, baseRevision, content, provenance, hash = "")
    return unsigned.copy(hash = repositoryExecutionPlanHash(unsigned))
}

fun repositoryExecutionPlanHash(plan: RepositoryExecutionPlan): String = stagedPlanHash(
    planJson.encodeToString(plan.copy(hash = ""))
)

private fun validateRepositoryExecutionPlan(plan: RepositoryExecutionPlan, previous: List<RepositoryExecutionPlan>) {
    require(plan.planId == previous.size + 1L) { "Repository execution plan ID is not monotonic" }
    val priorRunPlans = previous.filter { it.runId == plan.runId }
    require(plan.revision == (priorRunPlans.maxOfOrNull { it.revision } ?: 0) + 1) { "Execution plan revision is not monotonic" }
    require(plan.runId > 0 && plan.projectId > 0 && plan.baseRevision.matches(GIT_HASH)) { "Execution plan identity is invalid" }
    require(plan.content.disposition in DISPOSITIONS) { "Execution plan disposition is invalid" }
    require(plan.content.summary.isNotBlank() && plan.content.unresolvedQuestions.isEmpty()) { "Execution plan is unresolved" }
    require(plan.content.evidence.isNotEmpty()) { "Execution plan requires repository evidence" }
    require(plan.content.evidence.all { validPath(it.path) && it.observation.isNotBlank() && it.contentHash.matches(SHA256) }) {
        "Execution plan evidence is invalid"
    }
    require(plan.content.operations.map { it.order } == (1..plan.content.operations.size).toList()) {
        "Execution plan operations must be ordered"
    }
    require(plan.content.operations.all {
        it.action in PLAN_OPERATIONS && validOperationPath(it) && it.instruction.isNotBlank() && it.acceptanceCriteria.isNotEmpty()
    }) { "Execution plan operation is invalid" }
    if (plan.content.disposition == DISPOSITION_COMPLETE) require(plan.content.operations.all { it.action == PLAN_OPERATION_VERIFY })
    else require(plan.content.operations.any { it.action != PLAN_OPERATION_VERIFY }) { "Executable plan has no source operation" }
    require(plan.provenance.executionProfileId.isNotBlank() && plan.provenance.bindingFingerprint.matches(SHA256)) {
        "Execution plan provenance is invalid"
    }
    require(listOf(plan.provenance.promptHash, plan.provenance.contextHash, plan.provenance.outputHash).all { it.matches(SHA256) }) {
        "Execution plan provenance hashes are invalid"
    }
    require(plan.provenance.modelExecutionId > 0 && plan.hash == repositoryExecutionPlanHash(plan)) {
        "Execution plan authority hash is invalid"
    }
}

private fun validOperationPath(operation: ExecutionPlanOperation): Boolean =
    (operation.action == PLAN_OPERATION_VERIFY && operation.path == ".") || validPath(operation.path)

private fun validPath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." } && path != ".git" && !path.startsWith(".git/") &&
    path != ".orchard" && !path.startsWith(".orchard/")

@Serializable
private data class RepositoryExecutionPlanEnvelope(
    val version: Int = 1,
    val value: RepositoryExecutionPlan,
    val checksum: String,
)

private val DISPOSITIONS = setOf(
    DISPOSITION_ABSENT,
    DISPOSITION_SCAFFOLD_ONLY,
    DISPOSITION_PARTIALLY_IMPLEMENTED,
    DISPOSITION_IMPLEMENTED_DIFFERENT_FORM,
    DISPOSITION_IMPLEMENTED_NONCONFORMING,
    DISPOSITION_COMPLETE,
    DISPOSITION_CONFLICTING,
)
private val PLAN_OPERATIONS = setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY, PLAN_OPERATION_DELETE, PLAN_OPERATION_VERIFY)
private val planJson = Json { encodeDefaults = true }
private val GIT_HASH = Regex("[0-9a-f]{40}")
private val SHA256 = Regex("[0-9a-f]{64}")