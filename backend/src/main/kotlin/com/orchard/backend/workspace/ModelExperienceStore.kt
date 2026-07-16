package com.orchard.backend.workspace

import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelExecutionProfile
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.resource.ResourceAdmissionEvidence
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

const val MODEL_SATISFACTION_REVISION_REQUESTED = "REVISION_REQUESTED"
const val MODEL_SATISFACTION_ACCEPTED_UNCHANGED = "ACCEPTED_UNCHANGED"
const val MODEL_SATISFACTION_ACCEPTED_AFTER_EDIT = "ACCEPTED_AFTER_EDIT"

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModelExecutionObservation(
    val executionId: Long,
    val profile: ModelExecutionProfile,
    val binding: ModelBindingProfile,
    val workflowStepId: String,
    val workItemId: Int,
    val envelopeHash: String,
    val promptHash: String,
    val outputHash: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMillis: Long,
    val schemaValid: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val resourceAdmission: ResourceAdmissionEvidence? = null,
    val recordedAt: String = Instant.now().toString(),
)

data class ModelExecutionObservationDraft(
    val profile: ModelExecutionProfile,
    val binding: ModelBindingProfile,
    val workflowStepId: String,
    val workItemId: Int,
    val envelopeHash: String,
    val promptHash: String,
    val outputHash: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val latencyMillis: Long,
    val schemaValid: Boolean,
    val resourceAdmission: ResourceAdmissionEvidence? = null,
)

@Serializable
data class ModelSatisfactionObservation(
    val satisfactionId: Long,
    val executionId: Long,
    val workItemId: Int,
    val proposalId: Long,
    val signal: String,
    val humanRevisionFields: Int = 0,
    val recordedAt: String = Instant.now().toString(),
)

@Serializable
data class ModelExperienceEvent(
    val eventId: Long,
    val execution: ModelExecutionObservation? = null,
    val satisfaction: ModelSatisfactionObservation? = null,
)

@Serializable
data class ModelCapabilityProfile(
    val executionProfileId: String,
    val executionProfileVersion: Int,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val binding: ModelBindingProfile,
    val bindingFingerprint: String,
    val sampleCount: Int,
    val schemaValidityRate: Double,
    val acceptedUnchangedCount: Int,
    val acceptedAfterEditCount: Int,
    val revisionRequestedCount: Int,
    val averageHumanRevisionFields: Double,
    val medianLatencyMillis: Long,
    val confidence: Double,
)

interface ModelExperienceStore {
    fun loadEvents(): List<ModelExperienceEvent>
    fun appendEvent(event: ModelExperienceEvent)
}

class TransientModelExperienceStore : ModelExperienceStore {
    private val events = mutableListOf<ModelExperienceEvent>()

    @Synchronized
    override fun loadEvents(): List<ModelExperienceEvent> = events.toList()

    @Synchronized
    override fun appendEvent(event: ModelExperienceEvent) {
        validateModelExperienceEvent(event, events.size + 1L, events)
        events += event
    }
}

class FileModelExperienceStore(private val directory: Path) : ModelExperienceStore {
    private val path = directory.resolve("model-experience.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<ModelExperienceEvent> {
        val events = mutableListOf<ModelExperienceEvent>()
        return loadRecoverableJsonl(path, "model-experience") { line, recordNumber ->
            val envelope = json.decodeFromString<ModelExperienceEnvelope>(line)
            require(envelope.version == MODEL_EXPERIENCE_FORMAT_VERSION) {
                "Unsupported model experience format ${envelope.version}"
            }
            require(envelope.checksum == modelExperienceHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in model experience event $recordNumber"
            }
            validateModelExperienceEvent(envelope.value, recordNumber.toLong(), events)
            events += envelope.value
            envelope.value
        }
    }

    @Synchronized
    override fun appendEvent(event: ModelExperienceEvent) {
        val events = loadEvents()
        validateModelExperienceEvent(event, events.size + 1L, events)
        val payload = json.encodeToString(event)
        val line = json.encodeToString(
            ModelExperienceEnvelope(value = event, checksum = modelExperienceHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }
}

fun modelCapabilityProfiles(
    events: List<ModelExperienceEvent>,
    authoritativeSatisfaction: List<ModelSatisfactionObservation> = emptyList(),
): List<ModelCapabilityProfile> {
    val executions = events.mapNotNull { it.execution }
    val satisfaction = (events.mapNotNull { it.satisfaction } + authoritativeSatisfaction).groupBy { it.executionId }
    return executions.groupBy {
        listOf(
            it.profile.id,
            it.profile.version.toString(),
            it.profile.inputBudgetTokens.toString(),
            it.profile.outputBudgetTokens.toString(),
            modelBindingFingerprint(it.binding),
        )
    }
        .values
        .map { samples ->
            val outcomes = samples.flatMap { satisfaction[it.executionId].orEmpty() }
            val edited = outcomes.filter { it.signal == MODEL_SATISFACTION_ACCEPTED_AFTER_EDIT }
            ModelCapabilityProfile(
                executionProfileId = samples.first().profile.id,
                executionProfileVersion = samples.first().profile.version,
                inputBudgetTokens = samples.first().profile.inputBudgetTokens,
                outputBudgetTokens = samples.first().profile.outputBudgetTokens,
                binding = samples.first().binding,
                bindingFingerprint = modelBindingFingerprint(samples.first().binding),
                sampleCount = samples.size,
                schemaValidityRate = samples.count { it.schemaValid }.toDouble() / samples.size,
                acceptedUnchangedCount = outcomes.count { it.signal == MODEL_SATISFACTION_ACCEPTED_UNCHANGED },
                acceptedAfterEditCount = edited.size,
                revisionRequestedCount = outcomes.count { it.signal == MODEL_SATISFACTION_REVISION_REQUESTED },
                averageHumanRevisionFields = edited.map { it.humanRevisionFields }.average().takeUnless(Double::isNaN) ?: 0.0,
                medianLatencyMillis = samples.map { it.latencyMillis }.sorted()[samples.size / 2],
                confidence = samples.size.toDouble() / (samples.size + 10.0),
            )
        }
        .sortedWith(compareBy(ModelCapabilityProfile::executionProfileId, { modelBindingFingerprint(it.binding) }))
}

private fun validateModelExperienceEvent(
    event: ModelExperienceEvent,
    expectedId: Long,
    previousEvents: List<ModelExperienceEvent>,
) {
    require(event.eventId == expectedId) { "Expected model experience event ID $expectedId" }
    require(listOfNotNull(event.execution, event.satisfaction).size == 1) {
        "A model experience event must contain exactly one observation"
    }
    event.execution?.let { execution ->
        require(execution.executionId == event.eventId) { "Execution ID must match its event ID" }
        require(execution.profile.id.isNotBlank() && execution.profile.version > 0) { "Execution profile is invalid" }
        require(execution.binding.bindingId.isNotBlank() && execution.binding.model.isNotBlank()) { "Model binding is invalid" }
        require(execution.workflowStepId.isNotBlank() && execution.workItemId > 0) { "Execution target is invalid" }
        require(execution.envelopeHash.matches(SHA256_LOWER) && execution.promptHash.matches(SHA256_LOWER)) {
            "Execution hashes are invalid"
        }
        require(execution.outputHash == null || execution.outputHash.matches(SHA256_LOWER)) { "Output hash is invalid" }
        require(execution.inputTokens >= 0 && execution.outputTokens >= 0 && execution.latencyMillis >= 0) {
            "Execution measurements are invalid"
        }
    }
    event.satisfaction?.let { observation ->
        require(observation.satisfactionId == event.eventId) { "Satisfaction ID must match its event ID" }
        require(previousEvents.mapNotNull { it.execution }.any { it.executionId == observation.executionId }) {
            "Satisfaction references an unknown execution"
        }
        require(observation.signal in MODEL_SATISFACTION_SIGNALS) { "Satisfaction signal is invalid" }
        require(observation.workItemId > 0 && observation.proposalId > 0 && observation.humanRevisionFields >= 0) {
            "Satisfaction target is invalid"
        }
    }
}

private val MODEL_SATISFACTION_SIGNALS = setOf(
    MODEL_SATISFACTION_REVISION_REQUESTED,
    MODEL_SATISFACTION_ACCEPTED_UNCHANGED,
    MODEL_SATISFACTION_ACCEPTED_AFTER_EDIT,
)
private val SHA256_LOWER = Regex("[0-9a-f]{64}")
private const val MODEL_EXPERIENCE_FORMAT_VERSION = 1

@Serializable
private data class ModelExperienceEnvelope(
    val version: Int = MODEL_EXPERIENCE_FORMAT_VERSION,
    val value: ModelExperienceEvent,
    val checksum: String,
)

private fun modelExperienceHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }