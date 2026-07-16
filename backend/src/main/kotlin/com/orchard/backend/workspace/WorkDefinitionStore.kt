package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkDefinitionManifest(
    val definitionId: Long,
    val revision: Int,
    val workItemId: Int,
    val createdAt: String,
    val systemWorkflow: SystemWorkflow,
    val definition: WorkDefinitionSubmission,
    val assessment: DefinitionAssessment,
    val hash: String,
)

interface WorkDefinitionStore {
    fun load(): List<WorkDefinitionManifest>
    fun append(manifest: WorkDefinitionManifest)
}

class TransientWorkDefinitionStore : WorkDefinitionStore {
    private val manifests = mutableListOf<WorkDefinitionManifest>()

    @Synchronized
    override fun load(): List<WorkDefinitionManifest> = manifests.toList()

    @Synchronized
    override fun append(manifest: WorkDefinitionManifest) {
        require(manifest.definitionId == manifests.size + 1L) { "Definition IDs must be monotonic" }
        manifests += manifest
    }
}

class FileWorkDefinitionStore(private val directory: Path) : WorkDefinitionStore {
    private val path = directory.resolve("work-definitions.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<WorkDefinitionManifest> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path, Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                val envelope = runCatching { json.decodeFromString<DefinitionEnvelope>(line) }
                    .getOrElse { throw IllegalStateException("Cannot decode work definition ${index + 1}", it) }
                require(envelope.version == FORMAT_VERSION) { "Unsupported work definition format ${envelope.version}" }
                require(envelope.checksum == definitionChecksum(json.encodeToString(envelope.value))) {
                    "Checksum mismatch in work definition ${index + 1}"
                }
                validate(envelope.value, index + 1L)
                envelope.value
            }
    }

    @Synchronized
    override fun append(manifest: WorkDefinitionManifest) {
        val existing = load()
        validate(manifest, existing.size + 1L)
        val payload = json.encodeToString(manifest)
        val line = json.encodeToString(
            DefinitionEnvelope(value = manifest, checksum = definitionChecksum(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun validate(manifest: WorkDefinitionManifest, expectedId: Long) {
        require(manifest.definitionId == expectedId) { "Expected definition ID $expectedId" }
        require(manifest.revision > 0 && manifest.workItemId > 0) { "Definition identity is invalid" }
        require(manifest.createdAt.isNotBlank()) { "Definition timestamp is required" }
        require(manifest.hash == workDefinitionHash(manifest.copy(hash = ""))) { "Definition manifest hash mismatch" }
        DefaultSystemWorkflow.resolve(manifest.systemWorkflow.workItemType)
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun newWorkDefinitionManifest(
    definitionId: Long,
    revision: Int,
    workItemId: Int,
    workflow: SystemWorkflow,
    definition: WorkDefinitionSubmission,
    assessment: DefinitionAssessment,
): WorkDefinitionManifest {
    val unsigned = WorkDefinitionManifest(
        definitionId = definitionId,
        revision = revision,
        workItemId = workItemId,
        createdAt = Instant.now().toString(),
        systemWorkflow = workflow,
        definition = definition,
        assessment = assessment,
        hash = "",
    )
    return unsigned.copy(hash = workDefinitionHash(unsigned))
}

fun workDefinitionHash(manifest: WorkDefinitionManifest): String = definitionChecksum(
    definitionJson.encodeToString(manifest.copy(hash = ""))
)

private fun definitionChecksum(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

@Serializable
private data class DefinitionEnvelope(
    val version: Int = 1,
    val value: WorkDefinitionManifest,
    val checksum: String,
)

private val definitionJson = Json { encodeDefaults = true }