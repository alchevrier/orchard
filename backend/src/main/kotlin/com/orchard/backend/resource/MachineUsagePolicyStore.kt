package com.orchard.backend.resource

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileMachineUsagePolicyStore(private val directory: Path) : MachineUsagePolicyStore {
    private val path = directory.resolve("machine-usage-policy.json")
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val compactJson = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): MachineUsagePolicy {
        if (!Files.exists(path)) return MachineUsagePolicy()
        val envelope = runCatching {
            json.decodeFromString<MachineUsagePolicyEnvelope>(Files.readString(path, Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("Cannot decode machine usage policy at $path", it) }
        require(envelope.version == FORMAT_VERSION) { "Unsupported machine usage policy version ${envelope.version}" }
        require(envelope.checksum == checksum(compactJson.encodeToString(envelope.policy))) {
            "Machine usage policy checksum mismatch at $path"
        }
        require(envelope.policy.capacityPercent in 1..100) { "Machine capacity percentage is invalid" }
        require(envelope.policy.minimumFreeMemoryBytes >= 0) { "Machine free-memory reserve is invalid" }
        require(envelope.policy.maxConcurrentModelExecutions > 0) { "Machine concurrency is invalid" }
        return envelope.policy
    }

    @Synchronized
    override fun save(policy: MachineUsagePolicy) {
        val envelope = MachineUsagePolicyEnvelope(
            policy = policy,
            checksum = checksum(compactJson.encodeToString(policy)),
        )
        Files.createDirectories(directory)
        val temporaryPath = Files.createTempFile(directory, "${path.fileName}.", ".tmp")
        FileChannel.open(
            temporaryPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val bytes = ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
private data class MachineUsagePolicyEnvelope(
    val version: Int = 1,
    val policy: MachineUsagePolicy,
    val checksum: String,
)