package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PLAN_NODE_BLOCKED_DEPENDENCY = "BLOCKED_DEPENDENCY"
const val PLAN_NODE_BLOCKED_DEFINITION = "BLOCKED_DEFINITION"
const val PLAN_NODE_ELIGIBLE = "ELIGIBLE"
const val PLAN_NODE_RUNNING = "RUNNING"
const val PLAN_NODE_DONE = "DONE"
const val PLAN_NODE_CANCELLED = "CANCELLED"

@Serializable
data class StagedPlanArtifact(
    val kind: String,
    val name: String,
    val evidenceKind: String = "SOURCE_DIFF",
)

@Serializable
data class StagedPlanArtifactRequirement(
    val producerNodeId: String,
    val kind: String,
)

@Serializable
data class StagedPlanNodeSubmission(
    val nodeId: String,
    val workItemId: Int,
    val dependsOn: List<String> = emptyList(),
    val consumes: List<StagedPlanArtifactRequirement> = emptyList(),
    val produces: List<StagedPlanArtifact> = emptyList(),
)

@Serializable
data class StagedPlanStageSubmission(
    val stageId: String,
    val title: String,
    val executionWorkflowId: String,
    val executionWorkflowVersion: Int = 1,
    val nodes: List<StagedPlanNodeSubmission>,
)

@Serializable
data class StagedDeliveryPlanSubmission(
    val scopeId: Int,
    val title: String,
    val stages: List<StagedPlanStageSubmission>,
    val baseRevision: Int = 0,
    val baseHash: String? = null,
    val sourceProposal: CircuitProposalReference? = null,
)

@Serializable
data class StageExecutionWorkflowDefinition(
    val id: String,
    val version: Int,
    val entryPolicy: String,
    val exitPolicy: String,
    val description: String,
)

object StageExecutionWorkflowRegistry {
    private val workflows = listOf(
        StageExecutionWorkflowDefinition(
            "contract-design-v1",
            1,
            "DEPENDENCIES_AND_ARTIFACTS_ACCEPTED",
            "ALL_STAGE_NODES_DONE_AND_OUTPUTS_ACCEPTED",
            "Produce and accept boundary contracts before fan-out.",
        ),
        StageExecutionWorkflowDefinition(
            "parallel-implementation-v1",
            1,
            "DEPENDENCIES_AND_ARTIFACTS_ACCEPTED",
            "ALL_STAGE_NODES_DONE",
            "Run independent implementation nodes when their inputs are accepted.",
        ),
        StageExecutionWorkflowDefinition(
            "integration-v1",
            1,
            "ALL_PRIOR_STAGE_OUTPUTS_ACCEPTED",
            "ALL_STAGE_NODES_DONE",
            "Join completed branches and verify integrated evidence.",
        ),
        StageExecutionWorkflowDefinition(
            "sequential-delivery-v1",
            1,
            "ALL_PRIOR_STAGE_NODES_DONE",
            "ALL_STAGE_NODES_DONE",
            "Execute a general evidence-gated delivery stage.",
        ),
    )

    fun all(): List<StageExecutionWorkflowDefinition> = workflows

    fun resolve(id: String, version: Int): StageExecutionWorkflowDefinition? =
        workflows.singleOrNull { it.id == id && it.version == version }
}

@Serializable
data class StagedPlanNode(
    val nodeId: String,
    val label: String,
    val workItemId: Int,
    val dependsOn: List<String>,
    val consumes: List<StagedPlanArtifactRequirement>,
    val produces: List<StagedPlanArtifact>,
)

@Serializable
data class StagedPlanStage(
    val stageId: String,
    val ordinal: Int,
    val title: String,
    val executionWorkflowId: String,
    val executionWorkflowVersion: Int,
    val nodes: List<StagedPlanNode>,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class StagedDeliveryPlan(
    val planId: Long,
    val revision: Int,
    val scopeId: Int,
    val scopeType: Int,
    val title: String,
    val stages: List<StagedPlanStage>,
    val acceptedBy: String = COLLABORATOR_HUMAN,
    val acceptedAt: String = Instant.now().toString(),
    val hash: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sourceProposal: CircuitProposalReference? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptedProposalUnchanged: Boolean = false,
)

@Serializable
data class StagedPlanNodeView(
    val node: StagedPlanNode,
    val state: String,
    val blockedReason: String = "",
)

@Serializable
data class StagedPlanArtifactInstance(
    val producerNodeId: String,
    val workItemId: Int,
    val kind: String,
    val name: String,
    val workflowRunId: Long,
    val evidenceId: Long,
    val evidenceKind: String,
    val revision: String,
    val outputHash: String,
    val evidenceHash: String,
)

@Serializable
data class StagedDeliveryPlanView(
    val plan: StagedDeliveryPlan,
    val nodes: List<StagedPlanNodeView>,
    val artifacts: List<StagedPlanArtifactInstance> = emptyList(),
)

enum class StagedPlanStatus {
    ACCEPTED,
    SCOPE_NOT_FOUND,
    PROPOSAL_NOT_FOUND,
    INVALID_SCOPE,
    INVALID_PLAN,
    PLAN_LOCKED,
    STALE_PLAN,
    STORAGE_UNAVAILABLE,
}

data class StagedPlanResult(
    val status: StagedPlanStatus,
    val snapshot: WorkspaceSnapshot,
)

interface StagedDeliveryPlanStore {
    fun load(): List<StagedDeliveryPlan>
    fun append(plan: StagedDeliveryPlan)
}

class TransientStagedDeliveryPlanStore : StagedDeliveryPlanStore {
    private val plans = mutableListOf<StagedDeliveryPlan>()

    @Synchronized
    override fun load(): List<StagedDeliveryPlan> = plans.toList()

    @Synchronized
    override fun append(plan: StagedDeliveryPlan) {
        plans += plan
    }
}

class FileStagedDeliveryPlanStore(private val directory: Path) : StagedDeliveryPlanStore {
    private val path = directory.resolve("staged-delivery-plans.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<StagedDeliveryPlan> {
        val plans = mutableListOf<StagedDeliveryPlan>()
        return loadRecoverableJsonl(path, "staged-delivery-plans") { line, recordNumber ->
            val envelope = json.decodeFromString<StagedDeliveryPlanEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported staged delivery plan format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in staged delivery plan $recordNumber"
            }
            plans += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun append(plan: StagedDeliveryPlan) {
        val payload = json.encodeToString(plan)
        val line = json.encodeToString(
            StagedDeliveryPlanEnvelope(value = plan, checksum = stagedPlanHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = Files.readAllLines(path, Charsets.UTF_8).filter(String::isNotBlank)
                val expectedPlanId = existing.lastOrNull()?.let { persisted ->
                    json.decodeFromString<StagedDeliveryPlanEnvelope>(persisted).value.planId + 1
                } ?: 1L
                require(plan.planId == expectedPlanId) { "Expected staged plan ID $expectedPlanId" }
                channel.position(channel.size())
                val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun stagedPlanHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

@Serializable
private data class StagedDeliveryPlanEnvelope(
    val version: Int = 1,
    val value: StagedDeliveryPlan,
    val checksum: String,
)
