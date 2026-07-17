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

const val CIRCUIT_DISPATCH_PENDING = "PENDING"

@Serializable
data class CircuitDispatch(
    val dispatchId: Long,
    val planId: Long,
    val planRevision: Int,
    val planHash: String,
    val scopeId: Int,
    val stageId: String,
    val nodeId: String,
    val workItemId: Int,
    val priority: Int,
    val integrationOwner: Boolean,
    val state: String = CIRCUIT_DISPATCH_PENDING,
    val createdAt: String = Instant.now().toString(),
    val hash: String,
)

@Serializable
data class CircuitDispatchView(
    val dispatch: CircuitDispatch,
    val state: String,
    val workflowRunId: Long? = null,
)

interface CircuitDispatchStore {
    fun load(): List<CircuitDispatch>
    fun append(dispatch: CircuitDispatch)
}

class TransientCircuitDispatchStore : CircuitDispatchStore {
    private val dispatches = mutableListOf<CircuitDispatch>()

    @Synchronized
    override fun load(): List<CircuitDispatch> = dispatches.toList()

    @Synchronized
    override fun append(dispatch: CircuitDispatch) {
        dispatches += dispatch
    }
}

class FileCircuitDispatchStore(private val directory: Path) : CircuitDispatchStore {
    private val path = directory.resolve("circuit-dispatches.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<CircuitDispatch> = loadRecoverableJsonl(path, "circuit-dispatches") { line, recordNumber ->
        val envelope = json.decodeFromString<CircuitDispatchEnvelope>(line)
        require(envelope.version == FORMAT_VERSION) { "Unsupported circuit dispatch format ${envelope.version}" }
        require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
            "Checksum mismatch in circuit dispatch $recordNumber"
        }
        envelope.value
    }

    @Synchronized
    override fun append(dispatch: CircuitDispatch) {
        val payload = json.encodeToString(dispatch)
        val line = json.encodeToString(
            CircuitDispatchEnvelope(value = dispatch, checksum = stagedPlanHash(payload))
        ) + "\n"
        Files.createDirectories(directory)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val existing = Files.readAllLines(path, Charsets.UTF_8).filter(String::isNotBlank)
                val expectedDispatchId = existing.lastOrNull()?.let { persisted ->
                    json.decodeFromString<CircuitDispatchEnvelope>(persisted).value.dispatchId + 1
                } ?: 1L
                require(dispatch.dispatchId == expectedDispatchId) { "Expected circuit dispatch ID $expectedDispatchId" }
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
private data class CircuitDispatchEnvelope(
    val version: Int = 1,
    val value: CircuitDispatch,
    val checksum: String,
)