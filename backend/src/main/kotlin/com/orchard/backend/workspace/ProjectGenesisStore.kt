package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val GENESIS_CLASSIFICATION = "CLASSIFICATION"
const val GENESIS_EXPERIENCE = "EXPERIENCE"
const val GENESIS_ARCHITECTURE = "ARCHITECTURE"
const val GENESIS_BLUEPRINT = "BLUEPRINT"
const val GENESIS_ADMISSION = "ADMISSION"
const val GENESIS_READY = "READY"

const val PROJECT_GREENFIELD_LOCAL = "GREENFIELD_LOCAL"
const val PROJECT_EXISTING_LOCAL = "EXISTING_LOCAL"
const val PROJECT_ORGANIZATION_GOVERNED = "ORGANIZATION_GOVERNED"

@Serializable
data class ExperienceContract(
    val audience: String = "",
    val productPromise: String = "",
    val primaryJourney: List<String> = emptyList(),
    val interactionPrinciples: List<String> = emptyList(),
    val emotionalQualities: List<String> = emptyList(),
    val mustNotFeelLike: List<String> = emptyList(),
    val accessibility: List<String> = emptyList(),
)

@Serializable
data class ArchitectureComponent(
    val componentId: String,
    val name: String,
    val responsibility: String,
    val dependsOn: List<String> = emptyList(),
    val requirementIds: List<String> = emptyList(),
    val repositoryPaths: List<String> = emptyList(),
)

@Serializable
data class ArchitectureDecision(
    val decisionId: String,
    val title: String,
    val status: String = DESIGN_STATUS_CANDIDATE,
    val context: String,
    val decision: String,
    val consequences: List<String> = emptyList(),
    val componentIds: List<String> = emptyList(),
    val requirementIds: List<String> = emptyList(),
)

@Serializable
data class RepositoryBlueprint(
    val rootName: String = "",
    val toolchain: String = "",
    val modules: List<String> = emptyList(),
    val verificationCommands: List<String> = emptyList(),
    val policyPackIds: List<String> = emptyList(),
)

@Serializable
data class ProjectGenesisSubmission(
    val classification: String? = null,
    val productIntent: String? = null,
    val experience: ExperienceContract? = null,
    val components: List<ArchitectureComponent>? = null,
    val decisions: List<ArchitectureDecision>? = null,
    val firstEpicId: Int? = null,
    val blueprint: RepositoryBlueprint? = null,
    val baseRevision: Int,
    val baseHash: String? = null,
)

@Serializable
data class ProjectGenesisRevision(
    val genesisId: Long,
    val projectId: Int,
    val revision: Int,
    val phase: String,
    val classification: String? = null,
    val productIntent: String = "",
    val experience: ExperienceContract = ExperienceContract(),
    val components: List<ArchitectureComponent> = emptyList(),
    val decisions: List<ArchitectureDecision> = emptyList(),
    val firstEpicId: Int? = null,
    val blueprint: RepositoryBlueprint? = null,
    val admitted: Boolean = false,
    val actor: String,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
    val conversationCommand: ConversationCommandReference? = null,
)

@Serializable
data class ProjectGenesisEvent(
    val eventId: Long,
    val revision: ProjectGenesisRevision,
)

@Serializable
data class ProjectGenesisView(
    val projectId: Int,
    val phase: String,
    val revision: ProjectGenesisRevision? = null,
    val progress: Int,
    val nextQuestion: String,
    val permittedAction: String,
    val blockingReason: String? = null,
)

enum class ProjectGenesisStatus {
    RECORDED,
    ADMITTED,
    PROJECT_NOT_FOUND,
    STALE_REVISION,
    INVALID_TRANSITION,
    ORGANIZATION_POLICY_REQUIRED,
    STORAGE_UNAVAILABLE,
}

data class ProjectGenesisResult(
    val status: ProjectGenesisStatus,
    val snapshot: WorkspaceSnapshot,
)

interface ProjectGenesisStore {
    fun loadEvents(): List<ProjectGenesisEvent>
    fun append(event: ProjectGenesisEvent)
}

class TransientProjectGenesisStore : ProjectGenesisStore {
    private val events = mutableListOf<ProjectGenesisEvent>()

    @Synchronized
    override fun loadEvents(): List<ProjectGenesisEvent> = events.toList()

    @Synchronized
    override fun append(event: ProjectGenesisEvent) {
        events += event
    }
}

class FileProjectGenesisStore(private val directory: Path) : ProjectGenesisStore {
    private val path = directory.resolve("project-genesis.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun loadEvents(): List<ProjectGenesisEvent> =
        loadRecoverableJsonl(path, "project-genesis") { line, recordNumber ->
            val envelope = json.decodeFromString<ProjectGenesisEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) {
                "Unsupported project genesis format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in project genesis event $recordNumber"
            }
            envelope.value
        }

    @Synchronized
    override fun append(event: ProjectGenesisEvent) {
        val payload = json.encodeToString(event)
        val line = json.encodeToString(
            ProjectGenesisEnvelope(value = event, checksum = stagedPlanHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = Files.readAllLines(path, Charsets.UTF_8).filter(String::isNotBlank)
                val expectedEventId = existing.lastOrNull()?.let { persisted ->
                    json.decodeFromString<ProjectGenesisEnvelope>(persisted).value.eventId + 1
                } ?: 1L
                require(event.eventId == expectedEventId) { "Expected project genesis event ID $expectedEventId" }
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

@Serializable
private data class ProjectGenesisEnvelope(
    val version: Int = 1,
    val value: ProjectGenesisEvent,
    val checksum: String,
)