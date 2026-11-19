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

@Serializable
data class ProjectArchive(
    val archiveId: Long,
    val projectId: Int,
    val actor: String,
    val createdAt: String = Instant.now().toString(),
    val conversationCommand: ConversationCommandReference? = null,
)

@Serializable
enum class ProjectArchiveStatus {
    ARCHIVED, ALREADY_ARCHIVED, PROJECT_NOT_FOUND, ACTIVE_WORK, INVALID_ACTOR, STORAGE_UNAVAILABLE,
}

@Serializable
data class ProjectArchiveRequest(val actor: String = "HUMAN")

@Serializable
data class ProjectArchiveResult(
    val status: ProjectArchiveStatus,
    val archive: ProjectArchive? = null,
    val diagnostic: String = "",
)

interface ProjectArchiveStore {
    fun load(): List<ProjectArchive>
    fun append(archive: ProjectArchive)
}

class TransientProjectArchiveStore : ProjectArchiveStore {
    private val archives = mutableListOf<ProjectArchive>()
    override fun load(): List<ProjectArchive> = archives.toList()
    override fun append(archive: ProjectArchive) { archives += archive }
}

class FileProjectArchiveStore(private val directory: Path) : ProjectArchiveStore {
    private val path = directory.resolve("project-archives.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<ProjectArchive> = loadRecoverableJsonl(path, "project-archives") { line, number ->
        val envelope = json.decodeFromString<ProjectArchiveEnvelope>(line)
        require(envelope.version == 1) { "Unsupported project archive format at record $number" }
        require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
            "Checksum mismatch in project archive record $number"
        }
        envelope.value
    }

    @Synchronized
    override fun append(archive: ProjectArchive) {
        Files.createDirectories(directory)
        val line = json.encodeToString(ProjectArchiveEnvelope(
            value = archive,
            checksum = stagedPlanHash(json.encodeToString(archive)),
        )) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val last = Files.readAllLines(path, Charsets.UTF_8).lastOrNull()
                    ?.let { json.decodeFromString<ProjectArchiveEnvelope>(it).value.archiveId } ?: 0L
                require(archive.archiveId == last + 1) { "Expected project archive ID ${last + 1}" }
                channel.position(channel.size())
                val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
    }
}

@Serializable
private data class ProjectArchiveEnvelope(
    val version: Int = 1,
    val value: ProjectArchive,
    val checksum: String,
)
